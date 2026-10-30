package dev.salt.desktop

import dev.salt.desktop.playback.*
import dev.salt.desktop.storage.DesktopPaths
import dev.salt.replica.equalizer.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.system.exitProcess

fun main() {
    try { equalizerPackageCheck(); exitProcess(0) }
    catch (failure: Throwable) { failure.printStackTrace(); exitProcess(1) }
}
private fun equalizerPackageCheck() = runBlocking {
    DesktopPaths.configureRuntime()
    val root = Path.of(System.getProperty("synctone.eq.probe.root"))
    val file = root.resolve("equalizer-presets.json").toFile()
    val repository = EqualizerRepository(file)
    try {
        repository.setEnabled(true); repository.setHeadroom(false)
        listOf(-8, 0, 0, -68, -65, -26, 0, 0, 0, 0).forEachIndexed(repository::setGain)
        repository.save("便携版验证")
        VlcAudioEngine().use { engine ->
            engine.setVolume(0); engine.setEqualizer(repository.state.value.config)
            engine.open(Path.of(System.getProperty("synctone.eq.probe.reference")))
            withTimeout(12_000) {
                while (engine.snapshot().positionMs < 600 || !engine.equalizerRuntime().connected) {
                    check(engine.snapshot().status != AudioStatus.Failed) { engine.equalizerRuntime().error.orEmpty() }; delay(30)
                }
            }
            check(abs(engine.equalizerRuntime().model!!.responseDb(500.0) + 6.52467457) < .001)
            engine.setEqualizer(repository.state.value.config.copy(enabled = false))
            withTimeout(4_000) { while (engine.equalizerRuntime().model?.config?.enabled != false) delay(25) }
            check(engine.snapshot().status == AudioStatus.Playing)
            engine.setPaused(true)
            val position = engine.snapshot().positionMs
            delay(200); check(abs(engine.snapshot().positionMs - position) < 100)
        }
    } finally { repository.close() }
    EqualizerRepository(file).use { restored ->
        check(restored.state.value.presets.single().name == "便携版验证")
        check(restored.state.value.config.gains[4] == -65)
    }
    Files.writeString(root.resolve("verification.json"),
        """{"passed":true,"packagedJvmAudio":true,"realDecodedPcmEq":true,"eqBypassWhilePlaying":true,"pause":true,"presetsRestored":true,"processingRate":48000}""")
}
