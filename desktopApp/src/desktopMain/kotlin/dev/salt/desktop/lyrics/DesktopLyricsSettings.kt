package dev.salt.desktop.lyrics

import kotlinx.serialization.json.*

data class DesktopLyricsSettings(
    val fontSize: Float = 30f,
    val fontWeight: Int = 380,
    val speed: Float = 1f,
    val curve: Int = 0,
    val blur: Boolean = true,
    val glow: Boolean = true,
    val stagger: Boolean = true,
    val translation: Boolean = true,
    val center: Boolean = false,
    val flowing: Boolean = true,
    val reduced: Boolean = false,
    val light: Boolean = false,
    val mesh: Int = 0,
) {
    fun normalized() = copy(fontSize = fontSize.takeIf(Float::isFinite)?.coerceIn(20f, 44f) ?: 30f,
        fontWeight = fontWeight.coerceIn(150, 700), speed = speed.takeIf(Float::isFinite)?.coerceIn(.5f, 2f) ?: 1f,
        curve = curve.coerceIn(0, 2), mesh = mesh.coerceIn(0, 4))

    fun toJson() = buildJsonObject {
        put("fontSize", fontSize); put("fontWeight", fontWeight); put("speed", speed); put("curve", curve)
        put("blur", blur); put("glow", glow); put("stagger", stagger); put("translation", translation)
        put("center", center); put("flowing", flowing); put("reduced", reduced); put("light", light); put("mesh", mesh)
    }

    companion object {
        fun fromJson(element: JsonElement?): DesktopLyricsSettings {
            val json = element as? JsonObject ?: return DesktopLyricsSettings()
            fun number(key: String) = (json[key] as? JsonPrimitive)?.floatOrNull
            fun flag(key: String, fallback: Boolean) = (json[key] as? JsonPrimitive)?.booleanOrNull ?: fallback
            return DesktopLyricsSettings(number("fontSize") ?: 30f, number("fontWeight")?.toInt() ?: 380,
                number("speed") ?: 1f, number("curve")?.toInt() ?: 0,
                flag("blur", true), flag("glow", true), flag("stagger", true), flag("translation", true),
                flag("center", false), flag("flowing", true), flag("reduced", false), flag("light", false),
                number("mesh")?.toInt() ?: 0).normalized()
        }
    }
}
