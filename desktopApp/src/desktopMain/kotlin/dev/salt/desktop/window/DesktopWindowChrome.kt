package dev.salt.desktop.window

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Frame
import java.awt.EventQueue
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.RoundRectangle2D
import java.nio.file.Files
import java.nio.file.Path
import java.util.WeakHashMap
import java.util.logging.Level
import java.util.logging.Logger

/** Keeps Windows movement/resizing in the native message loop, outside Compose rendering. */
class DesktopWindowChrome(private val window: Frame) : AutoCloseable {
    private var hwnd: Pointer? = null
    private var previous: Pointer? = null
    private var originalStyle = 0L
    private data class ChildHook(val handle: Pointer, val previous: Pointer?, val procedure: WindowProcedure)
    private val children = mutableListOf<ChildHook>()
    var nativeCorners by mutableStateOf(false)
        private set
    private val procedure = object : WindowProcedure {
        override fun invoke(handle: Pointer, message: Int, wParam: Long, lParam: Long): Long {
            val user = WindowsApi.user
            when (message) {
                0x0083 -> { // WM_NCCALCSIZE: retain the native frame without its title bar.
                    if (user.IsZoomed(handle)) Memory(40).use { info ->
                        info.setInt(0, 40)
                        if (user.GetMonitorInfoW(user.MonitorFromWindow(handle, 2), info)) {
                            // Windows adds an invisible resize border around maximized frames.
                            val client = Pointer(lParam)
                            repeat(4) { client.setInt(it * 4L, info.getInt(20L + it * 4)) }
                        }
                    }
                    return 0
                }
                0x00A1, 0x00A3 -> return user.DefWindowProcW(handle, message, wParam, lParam)
                0x0024 -> {
                    val result = user.CallWindowProcW(previous, handle, message, wParam, lParam)
                    val monitor = user.MonitorFromWindow(handle, 2)
                    Memory(40).use { info ->
                        info.setInt(0, 40)
                        if (user.GetMonitorInfoW(monitor, info)) {
                            val bounds = Pointer(lParam)
                            bounds.setInt(8, info.getInt(28) - info.getInt(20))
                            bounds.setInt(12, info.getInt(32) - info.getInt(24))
                            bounds.setInt(16, info.getInt(20) - info.getInt(4))
                            bounds.setInt(20, info.getInt(24) - info.getInt(8))
                        }
                    }
                    return result
                }
                0x0084 -> { // WM_NCHITTEST: native resize borders and the currently visible title area.
                    Memory(16).use { rect ->
                        if (user.GetWindowRect(handle, rect)) {
                            val x = lParam.toInt().toShort().toInt()
                            val y = (lParam.toInt() shr 16).toShort().toInt()
                            val border = (6 * user.GetDpiForWindow(handle) / 96).coerceAtLeast(6)
                            val resize = !user.IsZoomed(handle)
                            val left = resize && x < rect.getInt(0) + border
                            val right = resize && x >= rect.getInt(8) - border
                            val top = resize && y < rect.getInt(4) + border
                            val bottom = resize && y >= rect.getInt(12) - border
                            return when {
                                top && left -> 13L
                                top && right -> 14L
                                bottom && left -> 16L
                                bottom && right -> 17L
                                left -> 10L
                                right -> 11L
                                top -> 12L
                                bottom -> 15L
                                WindowDragAreas.contains(window, Offset((x - rect.getInt(0)).toFloat(),
                                    (y - rect.getInt(4)).toFloat())) -> 2L
                                else -> user.CallWindowProcW(previous, handle, message, wParam, lParam)
                            }
                        }
                    }
                }
            }
            return user.CallWindowProcW(previous, handle, message, wParam, lParam)
        }
    }
    private val listener = object : ComponentAdapter() {
        override fun componentShown(e: ComponentEvent) = configure()
        override fun componentResized(e: ComponentEvent) {
            configure()
            installChildHooks()
            updateFallbackCorners()
        }
    }

    init {
        window.addComponentListener(listener)
        configure()
    }

    private fun configure() {
        if (!window.isDisplayable || hwnd != null) return
        if (System.getProperty("os.name").startsWith("Windows")) {
            runCatching {
                // Chrome can initialize JNA before the audio engine sets its native path.
                System.getProperty("compose.application.resources.dir")?.let { resources ->
                    val directory = Path.of(resources, "jna")
                    if (Files.isRegularFile(directory.resolve("jnidispatch.dll"))) {
                        System.setProperty("jna.boot.library.path", directory.toString())
                    }
                }
                val handle = Native.getWindowPointer(window)
                val user = WindowsApi.user
                originalStyle = Pointer.nativeValue(user.GetWindowLongPtrW(handle, -16))
                previous = user.GetWindowLongPtrW(handle, -4)
                check(previous != null) { "Unable to install window procedure" }
                user.SetWindowLongPtrW(handle, -4, CallbackReference.getFunctionPointer(procedure))
                hwnd = handle
                user.SetWindowLongPtrW(handle, -16, Pointer(originalStyle or 0x00CF0000L))
                user.SetWindowPos(handle, null, 0, 0, 0, 0, 0x0037)
                Memory(4).use { value ->
                    value.setInt(0, 2) // DWMWCP_ROUND; DWM also handles maximized/snap states.
                    nativeCorners = WindowsApi.dwm.DwmSetWindowAttribute(handle, 33, value, 4) == 0
                }
                installChildHooks()
                EventQueue.invokeLater { installChildHooks() }
            }.onFailure { Logger.getLogger(javaClass.name).log(Level.WARNING, "Native window setup failed", it) }
        }
        updateFallbackCorners()
    }

    private fun updateFallbackCorners() {
        window.shape = if (nativeCorners || window.extendedState and Frame.MAXIMIZED_BOTH != 0) null
        else RoundRectangle2D.Double(0.0, 0.0, window.width.toDouble(), window.height.toDouble(), 24.0, 24.0)
    }

    private fun installChildHooks() {
        val handle = hwnd ?: return
        // Skia's heavyweight canvas receives hit tests before its parent AWT frame.
        WindowsApi.user.EnumChildWindows(handle, object : ChildWindowVisitor {
            override fun invoke(child: Pointer, parameter: Long): Boolean {
                if (children.any { it.handle == child }) return true
                val original = WindowsApi.user.GetWindowLongPtrW(child, -4) ?: return true
                val childProcedure = object : WindowProcedure {
                    override fun invoke(hwnd: Pointer, message: Int, wParam: Long, lParam: Long): Long {
                        if (message == 0x0084) {
                            val hit = procedure.invoke(handle, message, wParam, lParam)
                            if (hit in 2L..17L) return -1L // HTTRANSPARENT delegates to the parent.
                        }
                        return WindowsApi.user.CallWindowProcW(original, hwnd, message, wParam, lParam)
                    }
                }
                WindowsApi.user.SetWindowLongPtrW(child, -4, CallbackReference.getFunctionPointer(childProcedure))
                children += ChildHook(child, original, childProcedure)
                return true
            }
        }, 0)
    }

    override fun close() {
        window.removeComponentListener(listener)
        children.forEach { WindowsApi.user.SetWindowLongPtrW(it.handle, -4, it.previous) }
        children.clear()
        hwnd?.let { handle ->
            WindowsApi.user.SetWindowLongPtrW(handle, -4, previous)
            WindowsApi.user.SetWindowLongPtrW(handle, -16, Pointer(originalStyle))
        }
        hwnd = null
    }
}

internal fun Modifier.windowDragArea(owner: Frame, layer: Int = 0, enabled: Boolean = true): Modifier = composed {
    val token = remember { Any() }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    DisposableEffect(owner, token, enabled, layer, bounds) {
        bounds?.takeIf { enabled }?.let { WindowDragAreas.put(owner, token, layer, it) }
        onDispose { WindowDragAreas.remove(owner, token) }
    }
    onGloballyPositioned { bounds = it.boundsInWindow() }
}

private object WindowDragAreas {
    private data class Area(val layer: Int, val bounds: Rect)
    private val windows = WeakHashMap<Frame, MutableMap<Any, Area>>()
    @Synchronized fun put(window: Frame, token: Any, layer: Int, bounds: Rect) {
        windows.getOrPut(window) { mutableMapOf() }[token] = Area(layer, bounds)
    }
    @Synchronized fun remove(window: Frame, token: Any) { windows[window]?.remove(token) }
    @Synchronized fun contains(window: Frame, position: Offset): Boolean {
        val areas = windows[window]?.values ?: return false
        val layer = areas.maxOfOrNull { it.layer } ?: return false
        return areas.any { it.layer == layer && it.bounds.contains(position) }
    }
}

private interface WindowProcedure : StdCallLibrary.StdCallCallback {
    fun invoke(hwnd: Pointer, message: Int, wParam: Long, lParam: Long): Long
}

private interface ChildWindowVisitor : StdCallLibrary.StdCallCallback {
    fun invoke(hwnd: Pointer, parameter: Long): Boolean
}

private interface User32 : StdCallLibrary {
    fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Pointer?
    fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Pointer?): Pointer?
    fun CallWindowProcW(previous: Pointer?, hwnd: Pointer, message: Int, wParam: Long, lParam: Long): Long
    fun DefWindowProcW(hwnd: Pointer, message: Int, wParam: Long, lParam: Long): Long
    fun EnumChildWindows(hwnd: Pointer, visitor: ChildWindowVisitor, parameter: Long): Boolean
    fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, width: Int, height: Int, flags: Int): Boolean
    fun GetWindowRect(hwnd: Pointer, rect: Pointer): Boolean
    fun IsZoomed(hwnd: Pointer): Boolean
    fun GetDpiForWindow(hwnd: Pointer): Int
    fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer
    fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Boolean
}

private interface DwmApi : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: Pointer, size: Int): Int
}

private object WindowsApi {
    val user: User32 by lazy { Native.load("user32", User32::class.java) }
    val dwm: DwmApi by lazy { Native.load("dwmapi", DwmApi::class.java) }
}
