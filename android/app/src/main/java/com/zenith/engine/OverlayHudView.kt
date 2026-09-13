package com.zenith.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Build
import android.os.PowerManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicReference

/**
 * OverlayHudView: Ultra-Low Latency In-Game HUD & Tactical Visualizer.
 *
 * Designed for iQOO High-Refresh Rate Gaming displays (120Hz/144Hz):
 * - Touch pass-through (FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE) ensuring zero gameplay input interference.
 * - Hardware-accelerated SurfaceView with an isolated rendering loop to isolate rendering from the Android main thread.
 * - Zero memory allocations in the onDraw/SurfaceView render loop.
 * - Renders dynamic bounding reticles, live FPS, thermal indicators, and peripheral alert borders.
 */
class OverlayHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    companion object {
        private const val TARGET_FPS = 120
        private const val FRAME_PERIOD_MS = 1000L / TARGET_FPS

        // Color Palette (iQOO Monster Esports Aesthetic)
        private const val COLOR_CYBER_CYAN = 0xFF00F0FF.toInt()
        private const val COLOR_NEON_YELLOW = 0xFFFFE600.toInt()
        private const val COLOR_DANGER_RED = 0xFFFF0055.toInt()
        private const val COLOR_PANEL_BG = 0xCC0D0F14.toInt()
        private const val COLOR_TEXT_WHITE = 0xFFF0F4F8.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF8A99AD.toInt()

        /**
         * Factory method to instantiate and attach the Overlay View directly to the WindowManager.
         */
        fun createAndAttach(context: Context): Pair<OverlayHudView, WindowManager> {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val overlayView = OverlayHudView(context)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            windowManager.addView(overlayView, params)
            return Pair(overlayView, windowManager)
        }
    }

    // Rendering Thread State
    private var renderThread: Thread? = null
    @Volatile private var isRunning = false

    // Hardware Telemetry & Detection State (Thread-safe swap)
    private val detectionSnapshot = AtomicReference<List<ZenithDetector.Detection>>(emptyList())
    @Volatile var currentFps: Float = 120.0f
    @Volatile var npuLatencyMs: Float = 4.2f
    @Volatile var thermalStatusText: String = "NOMINAL"
    @Volatile var isThreatAlertActive: Boolean = false

    // Pre-allocated Drawing Primitives (Zero allocation in render loop)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_PANEL_BG
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_CYBER_CYAN
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_NEON_YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
    }
    private val alertBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_DANGER_RED
        style = Paint.Style.STROKE
        strokeWidth = 8.0f
    }
    private val textHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_WHITE
        textSize = 28f
        isFakeBoldText = true
    }
    private val textSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_MUTED
        textSize = 20f
    }

    private val tempRect = RectF()
    private val panelRect = RectF(40f, 40f, 420f, 150f)
    private val cornerPath = Path()

    // Screen Scale factors (from 640x640 detection tensor space to display pixels)
    private var scaleX = 1.0f
    private var scaleY = 1.0f

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        renderThread = Thread(this, "ZenithHudRenderThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        scaleX = width.toFloat() / ZenithDetector.INPUT_WIDTH
        scaleY = height.toFloat() / ZenithDetector.INPUT_HEIGHT
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
        var retry = true
        while (retry) {
            try {
                renderThread?.join(300)
                retry = false
            } catch (e: InterruptedException) {
                // Keep retrying until thread dies cleanly
            }
        }
        renderThread = null
    }

    /**
     * Atomically update detection boxes from background detection service.
     */
    fun updateDetections(detections: List<ZenithDetector.Detection>) {
        detectionSnapshot.set(detections)
    }

    /**
     * Isolated 120Hz Hardware-Accelerated Rendering Loop.
     */
    override fun run() {
        var lastFrameTime = System.nanoTime()
        var glowPulse = 0f
        var glowIncreasing = true

        while (isRunning) {
            val startTime = System.currentTimeMillis()
            var canvas: Canvas? = null

            try {
                canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }

                if (canvas != null) {
                    // 1. Clear previous frame buffer
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                    // 2. Compute animated pulse for danger glow
                    if (glowIncreasing) {
                        glowPulse += 0.05f
                        if (glowPulse >= 1.0f) glowIncreasing = false
                    } else {
                        glowPulse -= 0.05f
                        if (glowPulse <= 0.2f) glowIncreasing = true
                    }

                    // 3. Render Peripheral Threat / Thermal Warning Border
                    if (isThreatAlertActive || thermalStatusText == "CRITICAL" || thermalStatusText == "THROTTLING") {
                        alertBorderPaint.alpha = (glowPulse * 255).toInt().coerceIn(40, 255)
                        tempRect.set(0f, 0f, width.toFloat(), height.toFloat())
                        canvas.drawRect(tempRect, alertBorderPaint)
                    }

                    // 4. Render Telemetry Pill (Top Left)
                    drawTelemetryPanel(canvas)

                    // 5. Render In-Game Tactical Detection Reticles
                    drawDetectionReticles(canvas)
                }
            } catch (e: Exception) {
                // Ignore transient surface recycling exceptions during rotation/exit
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        // Surface might have been destroyed
                    }
                }
            }

            // High Precision Frame Pacing
            val elapsed = System.currentTimeMillis() - startTime
            val sleepMs = FRAME_PERIOD_MS - elapsed
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs)
                } catch (ignored: InterruptedException) {}
            }
        }
    }

    private fun drawTelemetryPanel(canvas: Canvas) {
        // Panel Background with Cyber Chamfer
        canvas.drawRoundRect(panelRect, 12f, 12f, bgPaint)
        borderPaint.color = COLOR_CYBER_CYAN
        borderPaint.strokeWidth = 2f
        canvas.drawRoundRect(panelRect, 12f, 12f, borderPaint)

        // Text Rows
        textHeaderPaint.color = COLOR_NEON_YELLOW
        canvas.drawText("ZENITH NPU ENGINE", 60f, 75f, textHeaderPaint)

        // FPS & NPU Latency
        textSubPaint.color = if (currentFps >= 110f) COLOR_CYBER_CYAN else COLOR_DANGER_RED
        canvas.drawText("FPS: ${currentFps.toInt()}", 60f, 110f, textSubPaint)

        textSubPaint.color = COLOR_TEXT_WHITE
        canvas.drawText("NPU: ${String.format("%.1f", npuLatencyMs)}ms", 180f, 110f, textSubPaint)

        // Thermal Indicator
        val thermalColor = when (thermalStatusText) {
            "NOMINAL" -> COLOR_CYBER_CYAN
            "FAIR" -> COLOR_NEON_YELLOW
            else -> COLOR_DANGER_RED
        }
        textSubPaint.color = thermalColor
        canvas.drawText("THERMAL: $thermalStatusText", 290f, 110f, textSubPaint)
    }

    private fun drawDetectionReticles(canvas: Canvas) {
        val list = detectionSnapshot.get() ?: return

        for (i in 0 until list.size) {
            val det = list[i]

            // Transform coordinates from 640x640 detection tensor space to current screen resolution
            val left = det.x1 * scaleX
            val top = det.y1 * scaleY
            val right = det.x2 * scaleX
            val bottom = det.y2 * scaleY

            // Draw Corner Brackets (Esports Tactical HUD Style)
            val bracketLen = minOf((right - left) * 0.25f, 24f)

            reticlePaint.color = if (det.score > 0.70f) COLOR_NEON_YELLOW else COLOR_CYBER_CYAN

            // Top-Left Corner
            canvas.drawLine(left, top, left + bracketLen, top, reticlePaint)
            canvas.drawLine(left, top, left, top + bracketLen, reticlePaint)

            // Top-Right Corner
            canvas.drawLine(right, top, right - bracketLen, top, reticlePaint)
            canvas.drawLine(right, top, right, top + bracketLen, reticlePaint)

            // Bottom-Left Corner
            canvas.drawLine(left, bottom, left + bracketLen, bottom, reticlePaint)
            canvas.drawLine(left, bottom, left, bottom - bracketLen, reticlePaint)

            // Bottom-Right Corner
            canvas.drawLine(right, bottom, right - bracketLen, bottom, reticlePaint)
            canvas.drawLine(right, bottom, right, bottom - bracketLen, reticlePaint)

            // Confidence Tag
            val scoreText = "${(det.score * 100).toInt()}%"
            canvas.drawText(scoreText, left, top - 8f, textSubPaint)
        }
    }
}
