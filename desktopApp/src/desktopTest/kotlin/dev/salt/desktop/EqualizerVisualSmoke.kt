@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package dev.salt.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.salt.desktop.ui.*
import dev.salt.replica.equalizer.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>): Unit = runBlocking {
    val output = Files.createDirectories(Path.of(args[0]))
    val repository = EqualizerRepository(output.resolve("equalizer-presets.json").toFile())
    val axisCase = args.getOrNull(1) == "axis"
    try {
        repository.setEnabled(true)
        (if (axisCase) listOf(0, 0, 0, 0, 0, 76, 0, 0, 0, 0) else listOf(-8, 0, 0, -68, -65, -26, 0, 0, 0, 0))
            .forEachIndexed(repository::setGain)
        repository.save(if (axisCase) "单段提升" else "低中频微调")
        repository.reportApplied(EqModel.design(repository.state.value.config), 2)
        withContext(Dispatchers.Main) {
            for ((name, width, height) in listOf(Triple("windows", 1280, 1050), Triple("android", 400, 1350), Triple("small-dark", 320, 1100))) {
                var time = System.nanoTime()
                val scene = ImageComposeScene(width, height, Density(1f, if (name == "small-dark") 1.15f else 1f), coroutineContext = coroutineContext) {
                    MaterialTheme(colorScheme = desktopColorScheme(name == "small-dark"), typography = MiSans.typography, shapes = DesktopShapes) {
                        EqualizerScreen(repository, desktop = name == "windows")
                    }
                }
                try {
                    suspend fun capture(label: String) {
                        repeat(35) { delay(10); time += 16_666_667; scene.render(time).close() }
                        scene.render(time + 16_666_667).use { image ->
                            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output.resolve("$label.png"), it.bytes) }
                        }
                    }
                    capture(name)
                    if (axisCase && name == "windows") {
                        repository.setHeadroom(false)
                        repository.reportApplied(EqModel.design(repository.state.value.config), 2)
                        capture("windows-no-headroom")
                        repository.reset()
                        repository.reportApplied(EqModel.design(repository.state.value.config), 2)
                        capture("windows-reset")
                        repository.setHeadroom(true); repository.setGain(5, 76)
                        repository.reportApplied(EqModel.design(repository.state.value.config), 2)
                    }
                    if (axisCase && name == "small-dark") {
                        // A different Nyquist range changes the number of curve
                        // samples. Rendering the switch must not retain old arrays.
                        for (rate in listOf(22_050, 48_000)) {
                            repository.reportApplied(EqModel.design(repository.state.value.config, rate), 2)
                            repeat(25) { delay(10); time += 16_666_667; scene.render(time).close() }
                        }
                    }
                } finally { scene.close() }
            }
        }
        println("EQ visual snapshots: desktop, mobile, 320 dp dark with 1.15 font scale")
    } finally { repository.close() }
}
