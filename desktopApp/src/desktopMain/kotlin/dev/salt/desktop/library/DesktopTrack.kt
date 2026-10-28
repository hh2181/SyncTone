package dev.salt.desktop.library

import dev.salt.replica.lyrics.Lyrics
import dev.salt.replica.lyrics.LyricsParser
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

data class DesktopTrack(
    val path: Path,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    private val artworkBytes: ByteArray?,
    private val parsedLyrics: Lyrics,
    val contentId: String = path.toString(),
    val available: Boolean = true,
    private val assetLoader: (() -> TrackAssets)? = null,
    private val artworkLoader: (() -> ByteArray?)? = null,
    private val lyricsLoader: (() -> Lyrics)? = null,
    private val artworkIdentity: Any? = null,
) {
    // Repository loaders own a bounded cache; tracks must not retain every visited asset.
    private val assets: TrackAssets get() = assetLoader?.invoke() ?: TrackAssets(artworkBytes, parsedLyrics)
    internal val assetKey: Any = artworkIdentity ?: Any()
    val artwork: ByteArray? get() = if (artworkLoader != null) artworkLoader.invoke() else assets.artwork
    val lyrics: Lyrics get() = lyricsLoader?.invoke() ?: assets.lyrics
    val id: String get() = contentId
}

data class TrackAssets(val artwork: ByteArray?, val lyrics: Lyrics)

object DesktopTrackReader {
    val extensions = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aif", "aiff", "wma")

    init { Logger.getLogger("org.jaudiotagger").level = Level.OFF }

    fun read(path: Path): DesktopTrack {
        val canonical = path.toRealPath()
        require(Files.isRegularFile(canonical)) { "不是有效文件：$path" }
        val file = canonical.toFile()
        require(file.extension.lowercase() in extensions) { "暂不支持此文件格式：${file.name}" }
        val audio = try { AudioFileIO.read(file) } catch (e: Exception) {
            throw IllegalArgumentException("音频损坏或格式暂不支持：${file.name}", e)
        }
        val tag = audio.tag
        fun field(key: FieldKey) = runCatching { tag?.getFirst(key)?.trim().orEmpty() }.getOrDefault("")
        val duration = (audio.audioHeader.preciseTrackLength * 1000).toLong()
        require(duration > 0) { "无法读取音频时长：${file.name}" }
        val artwork = runCatching { tag?.firstArtwork?.binaryData?.takeIf { it.size <= 16 * 1024 * 1024 } }.getOrNull()
        val embedded = runCatching {
            if (file.extension.equals("mp3", true)) LyricsParser.fromMp3(file, duration)
            else LyricsParser.parse(field(FieldKey.LYRICS), duration)
        }.getOrDefault(Lyrics.Empty)
        val sidecar = canonical.resolveSibling(file.nameWithoutExtension + ".lrc")
        val lyrics = if (Files.isRegularFile(sidecar) && Files.size(sidecar) <= 4 * 1024 * 1024) {
            runCatching { LyricsParser.parse(LyricsParser.decode(Files.readAllBytes(sidecar)), duration, "LRC") }
                .getOrNull()?.takeIf { it.lines.isNotEmpty() } ?: embedded
        } else embedded
        return DesktopTrack(canonical, field(FieldKey.TITLE).ifBlank { file.nameWithoutExtension },
            field(FieldKey.ARTIST).ifBlank { "未知艺人" }, field(FieldKey.ALBUM).ifBlank { "未知专辑" },
            duration, artwork, lyrics)
    }
}
