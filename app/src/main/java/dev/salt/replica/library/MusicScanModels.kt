package dev.salt.replica.library

import java.util.Locale

internal fun isMusicDocument(name: String, mime: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension in setOf("m3u", "m3u8", "pls", "cue", "lrc")) return false
    return extension in setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wave", "amr", "aif", "aiff", "wma", "ape") ||
        mime.lowercase(Locale.ROOT).let { it.startsWith("audio/") && !it.contains("mpegurl") && !it.contains("scpls") }
}

enum class MusicScanPhase { NOT_SCANNED, SCANNING, COMPLETE, FAILED }

data class ScanFolder(val uri: String, val name: String, val path: String, val issue: String? = null)

data class MusicScanState(
    val folders: List<ScanFolder> = emptyList(),
    val phase: MusicScanPhase = MusicScanPhase.NOT_SCANNED,
    val lastScanUtc: Long = 0,
    val found: Int = 0,
    val checked: Int = 0,
    val added: Int = 0,
    val duplicates: Int = 0,
    val unreadable: Int = 0,
    val current: String = "",
    val error: String? = null
)
