@file:Suppress("DEPRECATION")
package dev.salt.replica.visual

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import kotlin.math.max
import kotlin.math.roundToInt

/** Renders artwork layers off the UI thread and publishes only completed frames. */
class FlowingLightView(context: Context) : View(context) {
    private data class Request(val artwork: Bitmap, val width: Int, val height: Int, val factor: Float,
                               val elapsed: Long, val reduced: Boolean, val mesh: Int, val dark: Boolean)
    private var artwork: Bitmap? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var engine: Engine? = null
    private val outputPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var displayed: Bitmap? = null
    private var oldFrame: Bitmap? = null
    private var oldShader: BitmapShader? = null
    private var generation = 0
    private var inFlight = false
    private var dirty = true
    private var animate = false
    private var reduced = false
    private var meshIndex = 0
    private var born = SystemClock.elapsedRealtime()
    private var heldTime = 0L
    private var changedAt = 0L
    private var failureLogged = false
    private val fadeEasing = CubicBezierEasing(0f, 0f, .3f, 1f)
    var dark = true
        set(value) {
            if (field != value) {
                oldFrame = displayed
                oldShader = oldFrame?.let { shaderFor(it) }
                changedAt = SystemClock.elapsedRealtime()
                field = value; generation++; dirty = true; requestFrame()
            }
        }

    fun setArtwork(bitmap: Bitmap?) {
        if (bitmap === artwork) return
        oldFrame = displayed?.copy(Bitmap.Config.ARGB_8888, false)
        oldShader = oldFrame?.let { shaderFor(it) }
        artwork = bitmap
        born = SystemClock.elapsedRealtime()
        heldTime = 0
        changedAt = born
        generation++
        dirty = true
        requestFrame()
    }

    fun configure(running: Boolean, reducedEffects: Boolean, mesh: Int = 0) {
        val changed = animate != running || reduced != reducedEffects || meshIndex != mesh
        if (animate != running) {
            if (running) born = SystemClock.elapsedRealtime() - heldTime
            else heldTime = SystemClock.elapsedRealtime() - born
            animate = running
        }
        if (reduced != reducedEffects || meshIndex != mesh) {
            reduced = reducedEffects; meshIndex = mesh.coerceIn(0, 4)
            generation++; dirty = true
        }
        if (!animate) removeCallbacks(tick)
        if (changed || dirty) requestFrame()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        generation++; dirty = true
        requestFrame()
    }

    private fun factor(): Float = if (resources.configuration.densityDpi >= 420) {
        if (reduced) 72f else 32f
    } else if (reduced) 48f else 20f

    private fun shaderFor(bitmap: Bitmap): BitmapShader {
        val factor = factor()
        val matrix = Matrix().apply {
            setScale(factor, factor)
            preTranslate(-(bitmap.width - width / factor) / 2, -(bitmap.height - height / factor) / 2)
        }
        return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
    }

    private val tick = Runnable { requestFrame() }

    private fun requestFrame() {
        if (!isAttachedToWindow || !isShown || width == 0 || height == 0 || inFlight || (!animate && !dirty)) return
        val art = artwork ?: return
        val queue = handler ?: return
        val renderer = engine ?: return
        removeCallbacks(tick)
        val token = generation
        val started = SystemClock.uptimeMillis()
        val request = Request(art, width, height, factor(),
            if (animate) SystemClock.elapsedRealtime() - born else heldTime, reduced, meshIndex, dark)
        inFlight = true
        dirty = false
        queue.post {
            Trace.beginSection("SyncToneFlow.render")
            val result = try { runCatching { renderer.render(request) } } finally { Trace.endSection() }
            post present@{
                if (engine !== renderer) return@present
                inFlight = false
                if (token == generation) result.onSuccess { frame ->
                    displayed = frame
                    outputPaint.shader = shaderFor(frame)
                    invalidate()
                }
                result.onFailure { error ->
                    if (!failureLogged) Log.e("SyncToneFlow", "Background rendering failed", error)
                    failureLogged = true
                }
                if (animate || dirty) postDelayed(tick, (42 - (SystemClock.uptimeMillis() - started)).coerceAtLeast(0))
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (displayed == null) {
            canvas.drawColor(if (dark) Color.rgb(40, 44, 55) else Color.rgb(238, 239, 242))
            return
        }
        val old = oldShader
        val fade = fadeEasing.transform(((SystemClock.elapsedRealtime() - changedAt) / 500f).coerceIn(0f, 1f))
        if (old != null && fade < 1) {
            fadePaint.shader = old
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
            outputPaint.alpha = (fade * 255).roundToInt()
            postInvalidateOnAnimation()
        } else {
            outputPaint.alpha = 255
            oldFrame = null; oldShader = null; fadePaint.shader = null
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), outputPaint)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) requestFrame() else removeCallbacks(tick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val thread = HandlerThread("SyncToneFlow").apply { start() }
        worker = thread; handler = Handler(thread.looper)
        engine = Engine(context.applicationContext)
        dirty = true
        requestFrame()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        generation++; inFlight = false
        val renderer = engine
        engine = null
        handler?.post { renderer?.close() }
        worker?.quitSafely()
        worker = null; handler = null
        displayed = null; outputPaint.shader = null; oldFrame = null; oldShader = null
        super.onDetachedFromWindow()
    }

    private class Engine(private val context: Context) {
        private var a: Bitmap? = null
        private var history: Bitmap? = null
        private var meshCopy: Bitmap? = null
        private val canvas = Canvas()
        private val meshCanvas = Canvas()
        private val historyCanvas = Canvas()
        private val matrix = Matrix()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val historyPaint = Paint().apply { alpha = 64 }
        private val overlays = Paint()
        private val normalColor = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(2.5f) })
        private val reducedColor = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(3.5f) })
        private var rs: RenderScript? = null
        private var blur: ScriptIntrinsicBlur? = null
        private var input: Allocation? = null
        private var output: Allocation? = null
        private var points = FloatArray(72)
        private var previousArt: Bitmap? = null
        private var previousMesh = -1
        private var historyReady = false
        private var previousDark = true
        private var average = Color.BLACK

        fun render(request: Request): Bitmap {
            if (previousDark != request.dark) {
                previousDark = request.dark
                historyReady = false
            }
            val w = (request.width / request.factor + 58).roundToInt().coerceAtLeast(1)
            val h = (request.height / request.factor + 58).roundToInt().coerceAtLeast(1)
            if (a?.width != w || a?.height != h) {
                closeAllocations()
                a = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                meshCopy = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                history = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val runtime = rs ?: RenderScript.create(context).also { rs = it }
                blur = ScriptIntrinsicBlur.create(runtime, Element.U8_4(runtime)).apply { setRadius(25f) }
                input = Allocation.createFromBitmap(runtime, a)
                output = Allocation.createTyped(runtime, input!!.type)
                previousMesh = -1; historyReady = false
            }
            val art = request.artwork
            if (art !== previousArt) {
                previousArt = art; historyReady = false
                var r = 0; var g = 0; var blue = 0
                for (y in 0..4) for (x in 0..4) {
                    val color = art.getPixel(((x + .5f) * art.width / 5).toInt(), ((y + .5f) * art.height / 5).toInt())
                    r += Color.red(color); g += Color.green(color); blue += Color.blue(color)
                }
                average = Color.rgb(r / 25, g / 25, blue / 25)
            }
            if (previousMesh != request.mesh) {
                previousMesh = request.mesh
                val preset = ArtworkMeshPreset.entries[request.mesh].coordinates
                for (i in preset.indices) points[i] = preset[i] * if (i % 2 == 0) w else h
            }
            val target = a!!
            target.eraseColor(average)
            canvas.setBitmap(target)
            val side = (max(w, h) * 1.3f).roundToInt().toFloat()
            val scale = side / max(art.width, art.height)
            paint.colorFilter = if (request.reduced) reducedColor else normalColor
            fun layer(period: Long, reverse: Boolean, dx: Float, dy: Float, extraRotation: Boolean = false) {
                val angle = request.elapsed % period / period.toFloat() * if (reverse) -360f else 360f
                matrix.reset(); matrix.setScale(scale, scale)
                matrix.postRotate(angle, side / 2, side / 2)
                matrix.postTranslate(-(side - w) / 2 + dx, -(side - h) / 2 + dy)
                if (extraRotation) matrix.postRotate(angle, w / 2f, h / 2f)
                canvas.drawBitmap(art, matrix, paint)
            }
            layer(100000, false, 0f, 0f)
            layer(70000, true, -.95f * w, -.7f * h)
            layer(40000, true, -.5f * w, .7f * h, true)
            meshCanvas.setBitmap(meshCopy)
            meshCanvas.drawBitmap(target, 0f, 0f, null)
            target.eraseColor(average)
            canvas.drawBitmapMesh(meshCopy!!, 5, 5, points, 0, null, 0, null)
            overlays.color = if (request.dark) 0x52000000 else 0x95FFFFFF.toInt()
            canvas.drawPaint(overlays)
            overlays.color = if (request.dark) 0x1A000000 else 0x2AFFFFFF
            canvas.drawPaint(overlays)
            input!!.copyFrom(target)
            blur!!.setInput(input); blur!!.forEach(output); output!!.copyTo(target)
            if (historyReady) canvas.drawBitmap(history!!, 0f, 0f, historyPaint)
            historyCanvas.setBitmap(history); historyCanvas.drawBitmap(target, 0f, 0f, null)
            historyReady = true
            // RenderThread can outlive the UI frame. Published pixels must never be edited again.
            return target.copy(Bitmap.Config.ARGB_8888, false)
        }

        private fun closeAllocations() {
            input?.destroy(); output?.destroy(); blur?.destroy()
            input = null; output = null; blur = null
        }

        fun close() {
            closeAllocations()
            canvas.setBitmap(null); meshCanvas.setBitmap(null); historyCanvas.setBitmap(null)
            rs?.destroy(); rs = null
            a = null; meshCopy = null; history = null
        }
    }
}
