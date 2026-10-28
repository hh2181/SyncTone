package dev.salt.desktop.storage

import kotlinx.serialization.json.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.FileSystemException
import java.io.IOException
import dev.salt.desktop.lyrics.DesktopLyricsSettings

enum class PlayMode { ListLoop, RepeatOne, Shuffle }

data class DesktopSession(
    val paths: List<String> = emptyList(),
    val current: String? = null,
    val positionMs: Long = 0,
    val volume: Int = 72,
    val mode: PlayMode = PlayMode.ListLoop,
    val queueIds: List<String>? = null,
    val lyrics: DesktopLyricsSettings = DesktopLyricsSettings(),
)

class DesktopSessionRepository(
    private val preferredDirectory: Path = defaultDirectory(),
    private val fallbackDirectory: Path? = null,
    private val legacyDirectory: Path? = null,
) {
    var directory: Path = preferredDirectory
        private set
    private val file: Path get() = directory.resolve("session.json")
    fun preserveUnreadable(): Path {
        val backup = Files.createTempFile(directory, "session-unreadable-", ".json")
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING)
        return backup
    }

    fun read(): DesktopSession {
        legacyDirectory?.let { LegacyDataMigration.copyIfEmpty(it, directory) }
        // An existing portable session remains authoritative after a previous fallback.
        if (fallbackDirectory != null && Files.isRegularFile(fallbackDirectory.resolve("session.json"))) {
            directory = fallbackDirectory
        }
        val text = try {
            Files.readString(file)
        } catch (_: java.nio.file.NoSuchFileException) {
            return DesktopSession()
        }
        val json = Json.parseToJsonElement(text).jsonObject
        require(json["version"]?.jsonPrimitive?.int in 1..2) { "不支持的桌面数据版本。" }
        return DesktopSession(
            json["paths"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().distinct(),
            json["current"]?.jsonPrimitive?.contentOrNull,
            (json["positionMs"]?.jsonPrimitive?.longOrNull ?: 0).coerceAtLeast(0),
            (json["volume"]?.jsonPrimitive?.intOrNull ?: 72).coerceIn(0, 100),
            runCatching { PlayMode.valueOf(json["mode"]!!.jsonPrimitive.content) }.getOrDefault(PlayMode.ListLoop),
            json["queueIds"]?.takeIf { it != JsonNull }?.jsonArray?.map { it.jsonPrimitive.content },
            DesktopLyricsSettings.fromJson(json["lyrics"]),
        )
    }

    fun write(session: DesktopSession) {
        try {
            writeSession(session)
        } catch (e: IOException) {
            if (fallbackDirectory != null && directory != fallbackDirectory) {
                val previous = directory
                directory = fallbackDirectory
                try {
                    writeSession(session)
                    return
                } catch (fallbackError: IOException) {
                    directory = previous
                    fallbackError.addSuppressed(e)
                    throw saveFailure(fallbackError, fallbackDirectory)
                }
            }
            throw saveFailure(e, directory)
        }
    }

    private fun saveFailure(error: IOException, path: Path): IOException {
        val reason = when (error) {
            is java.nio.file.AccessDeniedException -> "数据目录没有写入权限"
            is java.nio.file.FileAlreadyExistsException -> "数据目录被同名文件占用"
            is FileSystemException -> error.reason ?: "数据目录不可用"
            else -> error.message ?: error.javaClass.simpleName
        }
        return IOException("无法保存播放记录：$reason。\n$path\n当前播放不受影响，稍后会自动重试。", error)
    }

    private fun writeSession(session: DesktopSession) {
        Files.createDirectories(directory)
        val json = buildJsonObject {
            put("version", 2)
            put("paths", JsonArray(session.paths.map(::JsonPrimitive)))
            put("current", session.current?.let(::JsonPrimitive) ?: JsonNull)
            put("positionMs", session.positionMs)
            put("volume", session.volume)
            put("mode", session.mode.name)
            put("queueIds", session.queueIds?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
            put("lyrics", session.lyrics.toJson())
        }
        val temporary = Files.createTempFile(directory, "session-", ".tmp")
        try {
            Files.writeString(temporary, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json))
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        fun forApplication(): DesktopSessionRepository {
            val legacy = if (System.getProperty("synctone.data.dir") == null) {
                System.getenv("LOCALAPPDATA")?.let { Path.of(it, "SyncTone") }
            } else null
            return DesktopSessionRepository(defaultDirectory(), legacyDirectory = legacy)
        }

        fun defaultDirectory(): Path = DesktopPaths.dataDirectory()
    }
}
