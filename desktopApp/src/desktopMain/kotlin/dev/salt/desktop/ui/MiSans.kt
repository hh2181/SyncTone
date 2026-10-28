@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.salt.desktop.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation

object MiSans {
    private val base by lazy {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/fonts/MiSansVF.ttf")).use { it.readBytes() }
        Data.makeFromBytes(bytes).use { checkNotNull(FontMgr.default.makeFromData(it)) }
    }
    private val families = object : LinkedHashMap<Int, FontFamily>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FontFamily>) = size > 24
    }

    @Synchronized fun family(weight: Int = 330): FontFamily = families.getOrPut(weight.coerceIn(150, 700)) {
        val face = base.makeClone(FontVariation("wght", weight.coerceIn(150, 700).toFloat()))
        FontFamily(Typeface(face, "MiSansVF-$weight"))
    }

    val typography by lazy {
        val family = family()
        Typography().let { t -> t.copy(
            displayLarge = t.displayLarge.copy(fontFamily = family), displayMedium = t.displayMedium.copy(fontFamily = family),
            displaySmall = t.displaySmall.copy(fontFamily = family), headlineLarge = t.headlineLarge.copy(fontFamily = family),
            headlineMedium = t.headlineMedium.copy(fontFamily = family), headlineSmall = t.headlineSmall.copy(fontFamily = family),
            titleLarge = t.titleLarge.copy(fontFamily = family), titleMedium = t.titleMedium.copy(fontFamily = family),
            titleSmall = t.titleSmall.copy(fontFamily = family), bodyLarge = t.bodyLarge.copy(fontFamily = family),
            bodyMedium = t.bodyMedium.copy(fontFamily = family), bodySmall = t.bodySmall.copy(fontFamily = family),
            labelLarge = t.labelLarge.copy(fontFamily = family), labelMedium = t.labelMedium.copy(fontFamily = family),
            labelSmall = t.labelSmall.copy(fontFamily = family)) }
    }
}
