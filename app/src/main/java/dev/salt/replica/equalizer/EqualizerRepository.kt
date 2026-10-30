package dev.salt.replica.equalizer

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class EqPreset(val id: String, val name: String, val config: EqConfig)
data class EqState(val config: EqConfig = EqConfig(), val presets: List<EqPreset> = emptyList(),
    val selected: String? = null, val error: String? = null)
data class EqRuntime(val model: EqModel? = null, val channels: Int = 2, val connected: Boolean = false,
    val error: String? = null)

/** App-owned settings only. Never writes music files or decoded audio. */
@OptIn(FlowPreview::class)
class EqualizerRepository(private val file: File) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()
    private val mutableRuntime = MutableStateFlow(EqRuntime())
    val runtime = mutableRuntime.asStateFlow()
    private val writes = Any()
    private var closed = false
    private var writable = mutableState.value.error == null
    init {
        scope.launch {
            state.drop(1).debounce(350).collect {
                runCatching { persist() }.onFailure { failure ->
                    mutableState.update { it.copy(error = "无法保存均衡器设置：${failure.message}") }
                }
            }
        }
    }
    fun setEnabled(enabled: Boolean) = updateConfig { it.copy(enabled = enabled) }
    fun setGain(index: Int, value: Int) {
        if (index !in 0..9) return
        updateConfig { c -> c.copy(gains = c.gains.mapIndexed { i, gain -> if (i == index) value.coerceIn(-120, 120) else gain }) }
    }
    fun setHeadroom(enabled: Boolean) = updateConfig { it.copy(autoHeadroom = enabled) }
    fun reset() = updateConfig { it.copy(gains = List(10) { 0 }) }
    private fun updateConfig(update: (EqConfig) -> EqConfig) {
        mutableState.update { it.copy(config = update(it.config).sanitized(), selected = null) }
    }
    fun save(name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        val id = UUID.randomUUID().toString()
        mutableState.update { it.copy(presets = it.presets + EqPreset(id, clean, it.config), selected = id) }
    }
    fun apply(id: String) {
        mutableState.update { s -> s.presets.firstOrNull { it.id == id }?.let {
            s.copy(config = it.config.copy(enabled = true), selected = id)
        } ?: s }
    }
    fun delete(id: String) { mutableState.update { it.copy(presets = it.presets.filterNot { p -> p.id == id }, selected = it.selected.takeUnless { s -> s == id }) } }
    fun reportApplied(model: EqModel, channels: Int) { mutableRuntime.value = EqRuntime(model, channels, true) }
    fun reportRuntime(value: EqRuntime) { mutableRuntime.value = value }
    fun reportFailure(message: String) { mutableRuntime.update { it.copy(connected = false, error = message) } }
    fun disconnect() { mutableRuntime.update { it.copy(connected = false) } }

    private fun load(): EqState {
        if (!file.exists()) return EqState()
        return runCatching {
            require(file.length() <= 1_048_576) { "设置文件过大" }
            val json = JSONObject(file.readText())
            require(json.getInt("version") == 1 && json.getString("model") == EqModelId) { "不支持的滤波模型版本" }
            val presets = json.optJSONArray("presets") ?: JSONArray()
            EqState(readConfig(json.getJSONObject("current")), (0 until presets.length()).map { i ->
                val p = presets.getJSONObject(i)
                EqPreset(p.getString("id"), p.getString("name").take(40), readConfig(p.getJSONObject("config")))
            }, json.optString("selected").takeIf { it.isNotBlank() })
        }.getOrElse { EqState(error = "均衡器记录无法读取，原文件已保留：${it.message}") }
    }
    private fun readConfig(json: JSONObject): EqConfig {
        val gains = json.getJSONArray("gainsTenthDb")
        require(gains.length() == 10)
        return EqConfig(json.optBoolean("enabled", false), List(10) { gains.getInt(it) }, json.optBoolean("autoHeadroom", true)).sanitized()
    }
    private fun configJson(config: EqConfig) = JSONObject().put("enabled", config.enabled)
        .put("autoHeadroom", config.autoHeadroom).put("gainsTenthDb", JSONArray(config.gains))
    private fun persist() = synchronized(writes) {
        if (!writable || closed) return@synchronized
        val value = state.value
        file.parentFile?.mkdirs()
        val json = JSONObject().put("version", 1).put("model", EqModelId).put("current", configJson(value.config))
            .put("selected", value.selected ?: "").put("presets", JSONArray(value.presets.map {
                JSONObject().put("id", it.id).put("name", it.name).put("config", configJson(it.config))
            }))
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeText(json.toString())
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
    override fun close() {
        scope.cancel()
        synchronized(writes) {
            if (!closed) { runCatching { persist() }; closed = true }
        }
    }
}
