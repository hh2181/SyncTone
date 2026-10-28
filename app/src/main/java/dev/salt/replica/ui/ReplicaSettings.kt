package dev.salt.replica.ui

import android.content.Context
import androidx.compose.runtime.*

@Stable
class ReplicaSettings(context: Context) {
    private val prefs = context.getSharedPreferences("visual", Context.MODE_PRIVATE)
    var blur by mutableStateOf(prefs.getBoolean("blur", true))
    var glow by mutableStateOf(prefs.getBoolean("glow", true))
    var stagger by mutableStateOf(prefs.getBoolean("stagger", true))
    var translation by mutableStateOf(prefs.getBoolean("translation", true))
    var center by mutableStateOf(prefs.getBoolean("center", false))
    var reduced by mutableStateOf(prefs.getBoolean("reduced", false))
    var flowing by mutableStateOf(prefs.getBoolean("flowing", true))
    var lightFlowing by mutableStateOf(prefs.getBoolean("lightFlowing", false))
    var fontSize by mutableFloatStateOf(prefs.getFloat("fontSize", 20f))
    var fontWeight by mutableIntStateOf(prefs.getInt("fontWeight", 200))
    var lyricCurve by mutableIntStateOf(prefs.getInt("lyricCurve", 0))
    var lyricSpeed by mutableFloatStateOf(prefs.getFloat("lyricSpeed", 1f))
    var mesh by mutableIntStateOf(prefs.getInt("mesh", 0))
    var themeMode by mutableIntStateOf(prefs.getInt("themeMode", 0))
    fun save() {
        prefs.edit().putBoolean("blur", blur).putBoolean("glow", glow).putBoolean("stagger", stagger)
            .putBoolean("translation", translation).putBoolean("center", center)
            .putBoolean("reduced", reduced).putBoolean("flowing", flowing).putBoolean("lightFlowing", lightFlowing)
            .putFloat("fontSize", fontSize).putInt("fontWeight", fontWeight)
            .putInt("lyricCurve", lyricCurve).putFloat("lyricSpeed", lyricSpeed)
            .putInt("mesh", mesh).putInt("themeMode", themeMode).apply()
    }
}
