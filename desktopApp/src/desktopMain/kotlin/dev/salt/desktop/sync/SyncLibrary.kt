package dev.salt.desktop.sync

import dev.salt.desktop.library.DesktopLibrary
import dev.salt.replica.sync.SyncSong
import dev.salt.replica.sync.fileDigest
import dev.salt.replica.sync.safeMusicName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class SyncLibrary(private val library: DesktopLibrary, private val directory: Path) {
    private val music = directory.resolve("synced-music")
    @Volatile var songs: List<SyncSong> = emptyList()
        private set

    suspend fun refresh(): List<SyncSong> {
        library.refreshSyncSources()
        songs = library.state.value.tracks.filter { it.available }.mapNotNull { track ->
            val extension = track.path.fileName.toString().substringAfterLast('.', "").lowercase()
            if (extension !in setOf("mp3", "flac")) return@mapNotNull null
            runCatching { SyncSong(track.id, track.title, track.artist, track.album, track.path.fileName.toString(),
                Files.size(track.path), extension == "flac").also { SyncSong.parse(it.json()) } }.getOrNull()
        }.distinctBy { it.hash }
        return songs
    }

    fun part(song: SyncSong): Path {
        require(song.hash.matches(Regex("[a-f0-9]{64}")))
        return Files.createDirectories(directory.resolve("sync-partial")).resolve("${song.hash}.part")
    }

    fun open(hash: String, offset: Long): InputStream {
        val track = library.state.value.tracks.firstOrNull { it.id == hash && it.available } ?: error("歌曲已不可用")
        val song = songs.firstOrNull { it.hash == hash } ?: error("请重新对比曲库")
        check(Files.size(track.path) == song.size && Files.newInputStream(track.path).use(::fileDigest) == hash) {
            "音乐文件已变化，请重新扫描"
        }
        return Files.newInputStream(track.path).also { it.skipNBytes(offset) }
    }

    suspend fun publish(song: SyncSong, part: Path) {
        check(Files.size(part) == song.size) { "文件长度不匹配，可继续接收" }
        val context = currentCoroutineContext()
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(part).use { input ->
            val buffer = ByteArray(131072)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it) }
        if (actual != song.hash) { Files.deleteIfExists(part); error("文件校验失败，请重试") }
        Files.createDirectories(music)
        val destination = music.resolve(safeMusicName(song.filename, song.hash, song.flac))
        if (Files.exists(destination)) {
            check(Files.size(destination) == song.size && Files.newInputStream(destination).use(::fileDigest) == song.hash) {
                "同步目录存在不同内容的同名文件"
            }
            library.registerSynced(destination, song.hash)
            Files.deleteIfExists(part)
        } else {
            try { Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(part, destination) }
            try { library.registerSynced(destination, song.hash) }
            catch (e: Exception) {
                Files.move(destination, part)
                throw e
            }
        }
        refresh()
    }
}
