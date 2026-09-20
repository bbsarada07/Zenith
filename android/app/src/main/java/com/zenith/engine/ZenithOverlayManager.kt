package com.zenith.engine

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ZenithOverlayManager: System Window Floating Overlay & Visual Feedback HUD.
 *
 * Capabilities:
 * 1. Live Status Capsule: Displays real-time agent state ("Planning...", "Executing Tap...", "Verifying State...").
 * 2. Visual Target Reticle: Draws hardware-accelerated semi-transparent bounding box overlays on target coordinates.
 * 3. Global Emergency Stop: Red persistent floating kill-switch that instantly aborts automation and alerts controller.
 * 4. Safety Guardrail Approval Banner: Intercepts high-impact actions for user tap confirmation.
 */
class ZenithOverlayManager(
    private val context: Context,
    private val onEmergencyStopTriggered: () -> Unit,
    private val onConfirmationResult: (approved: Boolean, reason: String) -> Unit
) {

    companion object {
        private const val TAG = "ZenithOverlayManager"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isOverlayAttached = false
    private var rootOverlayLayout: FrameLayout? = null
    private var reticleCanvasView: ReticleCanvasView? = null
    private var statusBadge: TextView? = null
    private var confirmationContainer: LinearLayout? = null
    private var confirmationReasonText: TextView? = null

    // Touch dragging state for HUD capsule
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    init {
        mainHandler.post {
            attachOverlay()
        }
    }

    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachOverlay() {
        if (isOverlayAttached) return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 1. Fullscreen Passthrough Overlay Frame for Reticle Drawing
        val fullParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val rootFrame = FrameLayout(context)
        val canvasView = ReticleCanvasView(context)
        rootFrame.addView(
            canvasView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        reticleCanvasView = canvasView

        try {
            windowManager.addView(rootFrame, fullParams)
            rootOverlayLayout = rootFrame
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach reticle overlay: ${e.message}", e)
        }

        // 2. Interactive Control Capsule (Status Badge + Emergency Stop + Confirmation)
        val capsuleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 120
        }

        val controlCapsule = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            background = GradientDrawable().apply {
                setColor(0xE60E1422.toInt())
                cornerRadius = 24f
                setStroke(2, 0xFF00F3FF.toInt())
            }
            elevation = 16f
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Status Badge
        val status = TextView(context).apply {
            text = "⚡ ZENITH IDLE"
            setTextColor(0xFF00F3FF.toInt())
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(12, 6, 16, 6)
        }
        statusBadge = status
        topRow.addView(status)

        // Global Emergency Stop (Kill-Switch) Button
        val stopButton = Button(context).apply {
            text = "🛑 STOP"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFFFF0055.toInt())
                cornerRadius = 16f
            }
            setPadding(16, 4, 16, 4)
            setOnClickListener {
                Log.w(TAG, "Emergency Stop button tapped by user.")
                updateStatus("🛑 EMERGENCY STOP", 0xFFFF0055.toInt())
                onEmergencyStopTriggered()
            }
        }
        topRow.addView(stopButton)
        controlCapsule.addView(topRow)

        // Safety Interceptor Confirmation Panel (Hidden by default)
        val confirmPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(8, 12, 8, 8)
            val panelBg = GradientDrawable().apply {
                setColor(0xEE2A1018.toInt())
                cornerRadius = 16f
                setStroke(2, 0xFFFF0055.toInt())
            }
            background = panelBg
        }

        val confirmTitle = TextView(context).apply {
            text = "⚠️ SENSITIVE ACTION INTERCEPTED"
            setTextColor(0xFFFFE600.toInt())
            textSize = 11f
            paint.isFakeBoldText = true
        }
        confirmPanel.addView(confirmTitle)

        val confirmReason = TextView(context).apply {
            text = "Requires user approval to proceed."
            setTextColor(0xFFF0F4F8.toInt())
            textSize = 12f
            setPadding(0, 4, 0, 8)
        }
        confirmationReasonText = confirmReason
        confirmPanel.addView(confirmReason)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val rejectBtn = Button(context).apply {
            text = "REJECT"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF444444.toInt())
                cornerRadius = 12f
            }
            setOnClickListener {
                confirmPanel.visibility = View.GONE
                updateStatus("ACTION REJECTED", 0xFFFF0055.toInt())
                onConfirmationResult(false, "User rejected confirmation")
            }
        }
        btnRow.addView(rejectBtn)

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(12, 1)
        }
        btnRow.addView(spacer)

        val approveBtn = Button(context).apply {
            text = "APPROVE"
            textSize = 10f
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(0xFF00FF88.toInt())
                cornerRadius = 12f
            }
            setOnClickListener {
                confirmPanel.visibility = View.GONE
                updateStatus("APPROVED. RESUMING...", 0xFF00FF88.toInt())
                onConfirmationResult(true, "User approved")
            }
        }
        btnRow.addView(approveBtn)

        confirmPanel.addView(btnRow)
        confirmationContainer = confirmPanel
        controlCapsule.addView(confirmPanel)

        // Drag listener for capsule movement
        controlCapsule.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = capsuleParams.x
                    initialY = capsuleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    capsuleParams.x = initialX - (event.rawX - initialTouchX).toInt()
                    capsuleParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(controlCapsule, capsuleParams)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(controlCapsule, capsuleParams)
            isOverlayAttached = true
            Log.i(TAG, "Zenith Floating Overlay attached successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach control capsule overlay: ${e.message}", e)
        }
    }

    /**
     * Updates the status badge text and cyber accent color.
     */
    fun updateStatus(text: String, colorInt: Int = 0xFF00F3FF.toInt()) {
        mainHandler.post {
            statusBadge?.text = text
            statusBadge?.setTextColor(colorInt)
        }
    }

    /**
     * Highlights target coordinates with a semi-transparent glowing bounding box.
     */
    fun highlightTargetBounds(bounds: RectF, label: String? = null, durationMs: Long = 1200L) {
        mainHandler.post {
            reticleCanvasView?.showReticle(bounds, label, durationMs)
        }
    }

    /**
     * Displays sensitive intent approval banner on the floating HUD.
     */
    fun promptUserConfirmation(reason: String) {
        mainHandler.post {
            confirmationReasonText?.text = reason
            confirmationContainer?.visibility = View.VISIBLE
            updateStatus("⚠️ AWAITING APPROVAL", 0xFFFFE600.toInt())
        }
    }

    fun detachOverlay() {
        mainHandler.post {
            rootOverlayLayout?.let {
                try {
                    windowManager.removeView(it)
                } catch (ignored: Exception) {}
            }
            rootOverlayLayout = null
            reticleCanvasView = null
            isOverlayAttached = false
            Log.i(TAG, "Zenith Floating Overlay detached.")
        }
    }

    /**
     * Custom hardware-accelerated canvas for rendering target reticles & bounding boxes.
     */
    private class ReticleCanvasView(context: Context) : View(context) {
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00F3FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x3300F3FF.toInt()
            style = Paint.Style.FILL
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            isFakeBoldText = true
        }

        private var activeRect: RectF? = null
        private var activeLabel: String? = null
        private var alphaAnimator: ValueAnimator? = null

        fun showReticle(rect: RectF, label: String?, durationMs: Long) {
            activeRect = rect
            activeLabel = label

            alphaAnimator?.cancel()
            alphaAnimator = ValueAnimator.ofInt(255, 0).apply {
                duration = durationMs
                addUpdateListener { anim ->
                    val alpha = anim.animatedValue as Int
                    boxPaint.alpha = alpha
                    fillPaint.alpha = (alpha * 0.25f).toInt()
                    textPaint.alpha = alpha
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rect = activeRect ?: return
            canvas.drawRoundRect(rect, 12f, 12f, fillPaint)
            canvas.drawRoundRect(rect, 12f, 12f, boxPaint)

            activeLabel?.let { text ->
                canvas.drawText(text, rect.left + 8f, rect.top - 12f, textPaint)
            }
        }
    }
}
