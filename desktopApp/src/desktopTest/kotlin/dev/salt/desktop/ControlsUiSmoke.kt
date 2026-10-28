@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.salt.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.*
import dev.salt.desktop.ui.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>): Unit = runBlocking {
    val output = Files.createDirectories(Path.of(args[0]))
    withContext(Dispatchers.Main) {
        var frameTime = System.nanoTime()
        var slider by mutableStateOf(30f)
        var toggled by mutableStateOf(false)
        var checked by mutableStateOf(false)
        var choice by mutableStateOf(0)
        var page by mutableStateOf(0)
        var dark by mutableStateOf(false)
        var text by mutableStateOf("")
        var clicks = 0
        var disabledClicks = 0
        var commits = 0
        var toggleChanges = 0
        val sliderFocus = FocusRequester()
        val buttonFocus = FocusRequester()
        val bounds = mutableMapOf<String, Rect>()
        val pages = mutableMapOf<Int, Rect>()
        val scene = ImageComposeScene(800, 720, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(colorScheme = desktopColorScheme(dark), typography = MiSans.typography, shapes = DesktopShapes) {
                Surface {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DesktopButton({ clicks++ }, Modifier.focusRequester(buttonFocus).onGloballyPositioned { bounds["button"] = it.boundsInRoot() }) { Text("主要操作") }
                            DesktopButton({ disabledClicks++ }, Modifier.onGloballyPositioned { bounds["disabled"] = it.boundsInRoot() }, enabled = false) { Text("暂不可用") }
                            DesktopButton({ dark = !dark }, style = DesktopButtonStyle.Secondary) { Text("切换外观") }
                        }
                        DesktopSettingsGroup {
                            Text("设置滑条")
                            DesktopSlider(slider, { slider = it }, Modifier.fillMaxWidth().focusRequester(sliderFocus)
                                .onGloballyPositioned { bounds["slider"] = it.boundsInRoot() }, valueRange = 20f..44f,
                                onValueChangeFinished = { commits++ })
                            Box(Modifier.onGloballyPositioned { bounds["toggle"] = it.boundsInRoot() }) {
                                DesktopSettingToggle("整行切换", toggled, { toggled = it; toggleChanges++ })
                            }
                            Row {
                                DesktopCheckbox(checked, { checked = it }, modifier = Modifier.onGloballyPositioned { bounds["checkbox"] = it.boundsInRoot() })
                                Text("选择歌曲", Modifier.padding(top = 10.dp))
                            }
                            DesktopChoiceRow(listOf("歌曲", "艺人", "专辑"), choice, { choice = it },
                                Modifier.fillMaxWidth().onGloballyPositioned { bounds["choices"] = it.boundsInRoot() })
                        }
                        DesktopTextField(text, { text = it }, "设备名称", Modifier.fillMaxWidth())
                        AnimatedContent(page, transitionSpec = { desktopPageTransition(targetState > initialState) },
                            modifier = Modifier.fillMaxWidth().height(110.dp).onGloballyPositioned { bounds["page"] = it.boundsInRoot() }) { index ->
                            Box(Modifier.fillMaxSize().background(if (index == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer)
                                .onGloballyPositioned {
                                    val origin = it.positionInRoot()
                                    pages[index] = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                                }) { Text(if (index == 0) "前一页面" else "后一页面", Modifier.padding(16.dp)) }
                        }
                    }
                }
            }
        }
        suspend fun frames(count: Int = 25) {
            repeat(count) { frameTime += 16_666_667; scene.render(frameTime).close(); delay(3) }
        }
        fun pointer(type: PointerEventType, point: Offset) = scene.sendPointerEvent(type, point)
        suspend fun click(name: String, fraction: Float = .5f) {
            val rect = requireNotNull(bounds[name])
            val point = Offset(rect.left + rect.width * fraction, rect.center.y)
            pointer(PointerEventType.Press, point); frames(2); pointer(PointerEventType.Release, point); frames(3)
        }
        suspend fun key(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); frames(1)
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); frames(2)
        }
        fun snapshot(name: String) {
            scene.render(frameTime).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output.resolve(name), it.bytes) } }
        }
        try {
            frames()
            click("button"); check(clicks == 1) { "Button did not activate exactly once" }
            click("disabled"); check(disabledClicks == 0) { "Disabled action fired" }
            click("toggle", .1f); check(toggled && toggleChanges == 1) { "Label toggle did not activate once" }
            click("toggle", .96f); check(!toggled && toggleChanges == 2) { "Switch thumb caused a duplicate toggle" }
            click("checkbox"); check(checked) { "Checkbox did not toggle" }
            click("choices", .5f); check(choice == 1) { "Choice click failed" }
            key(Key.DirectionRight); check(choice == 2) { "Choice keyboard navigation failed" }
            sliderFocus.requestFocus(); frames(2)
            val beforeKey = slider
            key(Key.DirectionRight)
            check(slider > beforeKey && commits > 0) { "Slider keyboard adjustment/commit failed: before=$beforeKey, after=$slider, commits=$commits" }
            val rect = requireNotNull(bounds["slider"])
            val beforeDrag = commits
            pointer(PointerEventType.Press, Offset(rect.left + rect.width * .3f, rect.center.y)); frames(2)
            pointer(PointerEventType.Move, Offset(rect.left + rect.width * .8f, rect.center.y)); frames(3)
            check(commits == beforeDrag) { "Slider committed while dragging" }
            pointer(PointerEventType.Release, Offset(rect.left + rect.width * .8f, rect.center.y)); frames(4)
            check(commits == beforeDrag + 1 && slider in 20f..44f) { "Slider release was not committed once" }
            buttonFocus.requestFocus(); frames(1); key(Key.Spacebar); check(clicks == 2) { "Button keyboard activation failed" }
            frames(); snapshot("controls-light.png")
            val origin = requireNotNull(bounds["page"]).left
            page = 1; frames(4)
            check(requireNotNull(pages[1]).left > origin + 1 && requireNotNull(pages[0]).left < origin - 1) { "Forward page transition did not move left: origin=$origin, pages=$pages" }
            snapshot("page-forward.png"); frames(30)
            page = 0; frames(4)
            check(requireNotNull(pages[0]).left < origin - 1 && requireNotNull(pages[1]).left > origin + 1) { "Back transition did not move right" }
            snapshot("page-backward.png"); frames(30)
            // Repeated, interrupted changes must settle on the final page.
            repeat(5) { page = 1 - page; frames(2) }; page = 0; frames(35)
            check(kotlin.math.abs(requireNotNull(pages[0]).left - origin) < 1)
            dark = true; frames(); snapshot("controls-dark.png")
            Files.writeString(output.resolve("verification.json"), """{"passed":true,"buttonAndDisabled":true,"labelAndThumbToggleOnce":true,"checkbox":true,"choiceKeyboard":true,"sliderKeyboardAndRelease":true,"forwardAndBackwardPages":true,"interruptedNavigation":true,"themes":["light","dark"]}""")
        } finally { scene.close() }
    }
}
