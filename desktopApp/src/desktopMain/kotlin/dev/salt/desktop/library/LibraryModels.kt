package dev.salt.desktop.library

enum class ScanPhase { NotScanned, Scanning, Complete, Cancelled, Failed }
data class MusicFolder(val path: String, val issue: String? = null)
data class ScanProgress(
    val phase: ScanPhase = ScanPhase.NotScanned,
    val found: Int = 0,
    val checked: Int = 0,
    val added: Int = 0,
    val duplicates: Int = 0,
    val unreadable: Int = 0,
    val cached: Int = 0,
    val current: String = "",
    val lastSuccessUtc: Long = 0,
    val error: String? = null,
)
data class DesktopLibraryState(
    val tracks: List<DesktopTrack> = emptyList(),
    val folders: List<MusicFolder> = emptyList(),
    val scan: ScanProgress = ScanProgress(),
    val ready: Boolean = false,
    val importing: Boolean = false,
    val error: String? = null,
    val directory: String = "",
    val lastImport: ImportResult? = null,
)
data class ImportResult(val added: Int, val duplicates: Int, val unreadable: Int)
data class Registration(val id: String, val added: Boolean, val cached: Boolean)
