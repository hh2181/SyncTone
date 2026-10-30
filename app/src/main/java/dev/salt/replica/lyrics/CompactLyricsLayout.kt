package dev.salt.replica.lyrics

internal data class CompactLyricsTypography(val originalSp: Float, val secondarySp: Float)

/** Logical width only: physical pixel counts/DPI must not shrink the preview's type. */
internal fun compactLyricsTypography(widthDp: Float): CompactLyricsTypography {
    val original = (widthDp / 21f).coerceIn(16f, 20f)
    return CompactLyricsTypography(original, (original * .875f).coerceAtLeast(14f))
}

/** Line heights are converted from sp by Compose, including the system's font scaling. */
internal fun compactLyricsMinimumHeight(originalLineDp: Float, secondaryLineDp: Float): Float =
    8f + originalLineDp * 2f + secondaryLineDp * 2f

internal fun playerCoverSize(widthDp: Float, heightDp: Float, coverTopDp: Float,
                            previewHeightDp: Float, controlsHeightDp: Float): Float =
    minOf((widthDp - 60f).coerceIn(1f, 480f),
        (heightDp - coverTopDp - 16f - previewHeightDp - controlsHeightDp).coerceAtLeast(1f))
