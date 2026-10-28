@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.salt.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.salt.buttons.*
import dev.salt.desktop.ui.MiSans
import dev.salt.desktop.ui.desktopColorScheme
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

/** Exercise the actual common code in both density profiles, including cancellation and keys. */
fun main(args: Array<String>): Unit = runBlocking {
    val output = Files.createDirectories(Path.of(args[0]))
    withContext(Dispatchers.Main) {
        for (scale in listOf(1f, 1.5f)) {
            var dark by mutableStateOf(false)
            var frame = System.nanoTime()
            val bounds = mutableMapOf<String, Rect>()
            val focuses = SyncToneButtonDensity.entries.associateWith { FocusRequester() }
            val clicks = mutableMapOf<String, Int>()
            fun record(key: String) { clicks[key] = (clicks[key] ?: 0) + 1 }
            fun Modifier.target(key: String) = onGloballyPositioned { bounds[key] = it.boundsInRoot() }
            val scene = ImageComposeScene(1100, 480, Density(scale), coroutineContext = coroutineContext) {
                MaterialTheme(colorScheme = desktopColorScheme(dark), typography = MiSans.typography) {
                    Surface {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            for (profile in SyncToneButtonDensity.entries) {
                                val key = profile.name
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (profile == SyncToneButtonDensity.Mobile) "手机按钮" else "桌面按钮")
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        SyncToneButton({ record(key) }, Modifier.target(key).focusRequester(focuses.getValue(profile)), density = profile) { Text("主要操作") }
                                        SyncToneButton({}, style = SyncToneButtonStyle.Secondary, density = profile) { Text("次要操作") }
                                        SyncToneButton({}, style = SyncToneButtonStyle.Destructive, density = profile) { Text("删除") }
                                        SyncToneButton({}, style = SyncToneButtonStyle.Ghost, density = profile) { Text("取消") }
                                        SyncToneButton({ error("Disabled button fired") }, Modifier.target("$key-disabled"), enabled = false, density = profile) { Text("暂不可用") }
                                        SyncToneIconButton({ record("$key-icon") }, Modifier.size(42.dp).target("$key-icon"), density = profile) { Text("＋") }
                                        SyncToneIconButton({ error("Disabled icon fired") }, Modifier.size(42.dp).target("$key-icon-disabled"), enabled = false, density = profile) { Text("＋") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            suspend fun frames(count: Int = 20) {
                repeat(count) { frame += 16_666_667; scene.render(frame).close(); delay(3) }
            }
            suspend fun pointer(type: PointerEventType, point: Offset) { scene.sendPointerEvent(type, point); frames(2) }
            suspend fun click(key: String) {
                val point = bounds.getValue(key).center
                pointer(PointerEventType.Press, point)
                pointer(PointerEventType.Release, point)
            }
            suspend fun key(value: Key) {
                scene.sendKeyEvent(KeyEvent(value, KeyEventType.KeyDown)); frames(1)
                scene.sendKeyEvent(KeyEvent(value, KeyEventType.KeyUp)); frames(2)
            }
            try {
                frames()
                for (profile in SyncToneButtonDensity.entries) {
                    val name = profile.name
                    click(name); check(clicks[name] == 1) { "$name click was lost or duplicated" }
                    click("$name-disabled"); click("$name-icon-disabled")
                    pointer(PointerEventType.Press, bounds.getValue(name).center)
                    pointer(PointerEventType.Move, Offset(1090f, 470f))
                    pointer(PointerEventType.Release, Offset(1090f, 470f))
                    check(clicks[name] == 1) { "$name activated after dragging outside" }
                    focuses.getValue(profile).requestFocus(); frames(1)
                    key(Key.Spacebar); key(Key.Enter)
                    check(clicks[name] == 3) { "$name keyboard activation failed" }
                    click("$name-icon"); check(clicks["$name-icon"] == 1)
                }
                for (theme in listOf(false, true)) {
                    dark = theme
                    pointer(PointerEventType.Move, Offset(1090f, 470f)); frames()
                    scene.render(frame).use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use {
                            Files.write(output.resolve("buttons-$scale-${if (theme) "dark" else "light"}.png"), it.bytes)
                        }
                    }
                }
            } finally { scene.close() }
        }
        Files.writeString(output.resolve("verification.json"), """{"passed":true,"profiles":["Mobile","Desktop"],"scales":[1.0,1.5],"themes":["light","dark"],"clickOnce":true,"disabled":true,"cancelOutside":true,"spaceAndEnter":true,"iconButtons":true}""")
    }
}
