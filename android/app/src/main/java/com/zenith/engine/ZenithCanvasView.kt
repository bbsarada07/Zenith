package com.zenith.engine

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import java.util.concurrent.atomic.AtomicReference

/**
 * ZenithCanvasView: Hardware-Accelerated Multi-Modal Floating Canvas & Spatial Viewport.
 *
 * Core Capabilities:
 * - Live Screen Pipeline Mirroring with adjustable stealth opacity (0% to 100%).
 * - Pinch-to-zoom, dynamic panning, and smooth snap-to-edge docking animations.
 * - Interactive Augmented Reality (AR) Overlay rendering detection reticles, ambient context cards,
 *   floating quick-action pills ("Fix Error", "Summarize", "Extract Text"), and semi-transparent ghost suggestions.
 * - Zero allocations during active draw cycles to ensure buttery 120Hz rendering.
 */
class ZenithCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val COLOR_CYBER_CYAN = 0xFF00F0FF.toInt()
        private const val COLOR_NEON_YELLOW = 0xFFFFE600.toInt()
        private const val COLOR_DANGER_RED = 0xFFFF0055.toInt()
        private const val COLOR_CARD_BG = 0xDE0E131C.toInt()
        private const val COLOR_PILL_BG = 0xEE141C2B.toInt()
        private const val COLOR_PILL_BORDER = 0xFF00E5FF.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF8A9BA8.toInt()
        private const val COLOR_GHOST_SUGGESTION_BG = 0xAA0D1B2A.toInt()
    }

    interface OnActionListener {
        fun onActionPillClicked(actionPill: DualInferenceEngine.ActionPill)
        fun onGhostSuggestionClicked(text: String)
        fun onCanvasDockStateChanged(isDocked: Boolean, dockedOnLeft: Boolean)
    }

    var actionListener: OnActionListener? = null

    // Stealth / Viewport Opacity (0.0f = Invisible/Stealth, 1.0f = Fully Opaque)
    @Volatile var stealthOpacity: Float = 0.95f
        set(value) {
            field = value.coerceIn(0f, 1f)
            postInvalidateOnAnimation()
        }

    // Live Snapshot / Keyframe Preview
    private val currentPreviewBitmap = AtomicReference<Bitmap?>(null)

    // Detections & Action Overlay State
    private val currentDetections = AtomicReference<List<ZenithDetector.Detection>>(emptyList())
    private val currentActionPills = AtomicReference<List<DualInferenceEngine.ActionPill>>(emptyList())
    @Volatile var ghostSuggestionText: String? = null
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    // Viewport Transformation Matrix State (Zoom & Pan)
    private var scaleFactor = 1.0f
    private var focusX = 0f
    private var focusY = 0f
    private var isDockedToEdge = false

    // Pre-allocated Drawing Objects (Zero GC allocations during draw)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_CARD_BG
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = COLOR_CYBER_CYAN
    }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = COLOR_NEON_YELLOW
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_PILL_BG
    }
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = COLOR_PILL_BORDER
    }
    private val titleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_PRIMARY
        textSize = 28f
        isFakeBoldText = true
    }
    private val bodyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_MUTED
        textSize = 22f
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_CYBER_CYAN
        textSize = 22f
        isFakeBoldText = true
    }
    private val ghostBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_GHOST_SUGGESTION_BG
    }

    private val tempSrcRect = Rect()
    private val tempDstRectF = RectF()
    private val tempCardRectF = RectF()
    private val tempPillRectF = RectF()
    private val tempPath = Path()

    // Touch Handling & Gestures
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWindowX = 0
    private var initialWindowY = 0
    private var isDraggingWindow = false

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)

        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.5f, 3.0f)
                postInvalidateOnAnimation()
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                return handleSingleTap(e.x, e.y)
            }
        })
    }

    fun attachWindowContext(wm: WindowManager, params: WindowManager.LayoutParams) {
        this.windowManager = wm
        this.layoutParams = params
    }

    /**
     * Atomically swaps the live preview keyframe for low-latency viewport mirroring.
     */
    fun updatePreviewKeyframe(bitmap: Bitmap) {
        currentPreviewBitmap.set(bitmap)
        postInvalidateOnAnimation()
    }

    /**
     * Atomically updates detection reticles from local NPU.
     */
    fun updateDetections(detections: List<ZenithDetector.Detection>) {
        currentDetections.set(detections)
        postInvalidateOnAnimation()
    }

    /**
     * Updates spatial action pills and ambient context suggestions.
     */
    fun updateActionPills(pills: List<DualInferenceEngine.ActionPill>) {
        currentActionPills.set(pills)
        postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val params = layoutParams ?: return super.onTouchEvent(event)
        val wm = windowManager ?: return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialWindowX = params.x
                initialWindowY = params.y
                isDraggingWindow = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDraggingWindow && !scaleGestureDetector.isInProgress) {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    params.x = initialWindowX + deltaX
                    params.y = initialWindowY + deltaY
                    try {
                        wm.updateViewLayout(this, params)
                    } catch (ignored: Exception) {}
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingWindow) {
                    isDraggingWindow = false
                    snapToNearestEdge(params, wm)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToNearestEdge(params: WindowManager.LayoutParams, wm: WindowManager) {
        val displayWidth = context.resources.displayMetrics.widthPixels
        val currentX = params.x
        val targetX = if (currentX + (width / 2) < displayWidth / 2) {
            20 // Snap left
        } else {
            displayWidth - width - 20 // Snap right
        }

        val animator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                try {
                    wm.updateViewLayout(this@ZenithCanvasView, params)
                } catch (ignored: Exception) {}
            }
        }
        animator.start()
        isDockedToEdge = true
        actionListener?.onCanvasDockStateChanged(true, targetX <= 40)
    }

    private fun handleSingleTap(x: Float, y: Float): Boolean {
        val pills = currentActionPills.get() ?: emptyList()

        // Check if user tapped inside any floating Action Pill
        var pillTopOffset = 80f
        for (pill in pills) {
            tempPillRectF.set(20f, pillTopOffset, 220f, pillTopOffset + 50f)
            if (tempPillRectF.contains(x, y)) {
                actionListener?.onActionPillClicked(pill)
                return true
            }
            pillTopOffset += 60f
        }

        // Check if ghost suggestion tapped
        ghostSuggestionText?.let { ghostText ->
            tempDstRectF.set(20f, height - 120f, width - 20f, height - 20f)
            if (tempDstRectF.contains(x, y)) {
                actionListener?.onGhostSuggestionClicked(ghostText)
                return true
            }
        }

        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (stealthOpacity <= 0.01f) return // Completely stealth / invisible

        canvas.save()
        val alphaInt = (stealthOpacity * 255).toInt().coerceIn(0, 255)

        // 1. Render Floating Viewport Canvas Background
        backgroundPaint.alpha = (alphaInt * 0.85f).toInt()
        tempDstRectF.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(tempDstRectF, 16f, 16f, backgroundPaint)

        borderPaint.alpha = alphaInt
        canvas.drawRoundRect(tempDstRectF, 16f, 16f, borderPaint)

        // 2. Render Live Mirrored Frame (Scaled & Low Latency)
        val preview = currentPreviewBitmap.get()
        if (preview != null && !preview.isRecycled) {
            tempSrcRect.set(0, 0, preview.width, preview.height)
            tempCardRectF.set(12f, 12f, width - 12f, height - 12f)
            canvas.drawBitmap(preview, tempSrcRect, tempCardRectF, null)
        }

        // 3. Render Tactical Detection Reticles
        val detections = currentDetections.get() ?: emptyList()
        val scaleX = width.toFloat() / ZenithDetector.INPUT_WIDTH
        val scaleY = height.toFloat() / ZenithDetector.INPUT_HEIGHT

        for (i in detections.indices) {
            val det = detections[i]
            val l = det.x1 * scaleX
            val t = det.y1 * scaleY
            val r = det.x2 * scaleX
            val b = det.y2 * scaleY

            reticlePaint.alpha = alphaInt
            reticlePaint.color = if (det.score > 0.65f) COLOR_NEON_YELLOW else COLOR_CYBER_CYAN

            // Corner brackets
            val bracketLen = minOf((r - l) * 0.25f, 20f)
            canvas.drawLine(l, t, l + bracketLen, t, reticlePaint)
            canvas.drawLine(l, t, l, t + bracketLen, reticlePaint)
            canvas.drawLine(r, t, r - bracketLen, t, reticlePaint)
            canvas.drawLine(r, t, r, t + bracketLen, reticlePaint)
            canvas.drawLine(l, b, l + bracketLen, b, reticlePaint)
            canvas.drawLine(l, b, l, b - bracketLen, reticlePaint)
            canvas.drawLine(r, b, r - bracketLen, b, reticlePaint)
            canvas.drawLine(r, b, r, b - bracketLen, reticlePaint)
        }

        // 4. Render Ambient Context Quick-Action Pills
        val pills = currentActionPills.get() ?: emptyList()
        var pillTopOffset = 30f

        for (pill in pills) {
            tempPillRectF.set(20f, pillTopOffset, 240f, pillTopOffset + 46f)

            pillBgPaint.alpha = alphaInt
            canvas.drawRoundRect(tempPillRectF, 23f, 23f, pillBgPaint)

            pillBorderPaint.alpha = alphaInt
            canvas.drawRoundRect(tempPillRectF, 23f, 23f, pillBorderPaint)

            pillTextPaint.alpha = alphaInt
            canvas.drawText(pill.label, 36f, pillTopOffset + 30f, pillTextPaint)

            pillTopOffset += 56f
        }

        // 5. Render Floating Ghost Suggestion (if available)
        ghostSuggestionText?.let { ghost ->
            val ghostTop = height - 100f
            tempDstRectF.set(16f, ghostTop, width - 16f, height - 16f)

            ghostBgPaint.alpha = (alphaInt * 0.9f).toInt()
            canvas.drawRoundRect(tempDstRectF, 12f, 12f, ghostBgPaint)

            borderPaint.color = COLOR_CYBER_CYAN
            canvas.drawRoundRect(tempDstRectF, 12f, 12f, borderPaint)

            titleTextPaint.textSize = 20f
            titleTextPaint.color = COLOR_NEON_YELLOW
            canvas.drawText("✦ GHOST SUGGESTION (TAP TO INJECT)", 28f, ghostTop + 30f, titleTextPaint)

            bodyTextPaint.textSize = 18f
            bodyTextPaint.color = COLOR_TEXT_PRIMARY
            val truncated = if (ghost.length > 55) ghost.substring(0, 52) + "..." else ghost
            canvas.drawText(truncated, 28f, ghostTop + 62f, bodyTextPaint)
        }

        canvas.restore()
    }
}
