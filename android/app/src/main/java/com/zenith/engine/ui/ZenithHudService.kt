package com.zenith.engine.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.zenith.engine.cv.ViewportAdapter
import com.zenith.engine.telemetry.OtpInterceptor
import java.util.Locale

/**
 * ZenithHudService: Non-Blocking Floating In-Game HUD & Real-Time Telemetry Overlay.
 *
 * Renders an ultra-low latency floating telemetry capsule and control action panel above game
 * viewports and apps using [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY].
 * Supports Minimizable Pill Mode, Live FPS/Latency Metrics, OTP Sniffer Status, and Drag-to-Move gestures.
 */
class ZenithHudService : Service() {

    companion object {
        private const val TAG = "ZenithHudService"
        private const val NOTIFICATION_CHANNEL_ID = "zenith_hud_overlay_channel"
        private const val NOTIFICATION_ID = 9002

        const val ACTION_SHOW_HUD = "com.zenith.engine.action.SHOW_HUD"
        const val ACTION_HIDE_HUD = "com.zenith.engine.action.HIDE_HUD"

        @Volatile
        var instance: ZenithHudService? = null
            private set

        fun isShowing(): Boolean = instance != null
    }

    enum class ExecutionState {
        IDLE,
        SCANNING,
        EXECUTING,
        PAUSED,
        ABORTED
    }

    interface HudActionListener {
        fun onPlayClicked()
        fun onPauseClicked()
        fun onAbortClicked()
    }

    var actionListener: HudActionListener? = null

    private var windowManager: WindowManager? = null
    private var rootOverlayView: FrameLayout? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    // UI Elements
    private lateinit var pillContainer: LinearLayout
    private lateinit var statusIndicatorDot: View
    private lateinit var statusLabel: TextView
    private lateinit var dashboardContainer: LinearLayout
    private lateinit var fpsLabel: TextView
    private lateinit var latencyLabel: TextView
    private lateinit var stepLabel: TextView
    private lateinit var otpLabel: TextView
    private lateinit var playPauseBtn: Button
    private lateinit var abortBtn: Button

    // HUD State
    private var isExpanded: Boolean = false
    private var currentState: ExecutionState = ExecutionState.IDLE
    private var currentFps: Int = 30
    private var currentLatencyMs: Long = 12L
    private var currentActiveStepName: String = "Standby"

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var viewportAdapter: ViewportAdapter

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        viewportAdapter = ViewportAdapter.getDefault(applicationContext)
        createNotificationChannel()
        startForegroundServiceNotification()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlayViews()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE_HUD -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Zenith HUD Active")
            .setContentText("Autonomous Co-Pilot Overlay & Telemetry Running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Zenith HUD Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time floating telemetry and control HUD"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayViews() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 120
        }
        windowLayoutParams = params

        val root = FrameLayout(this)
        rootOverlayView = root

        // Main Glassmorphic Container
        val mainCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60F172A")) // Dark Slate Slate-900 (90% alpha)
                cornerRadius = dpToPx(16).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#3338BDF8")) // Cyan outline
            }
        }

        // 1. MINIMIZABLE PILL BAR
        pillContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { toggleExpandedMode() }
        }

        statusIndicatorDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply {
                marginEnd = dpToPx(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#22C55E")) // Emerald Green
            }
        }

        statusLabel = TextView(this).apply {
            text = "ZENITH • IDLE"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            paint.isFakeBoldText = true
        }

        val expandIcon = TextView(this).apply {
            text = " ▾"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        pillContainer.addView(statusIndicatorDot)
        pillContainer.addView(statusLabel)
        pillContainer.addView(expandIcon)
        mainCard.addView(pillContainer)

        // 2. TELEMETRY DASHBOARD & CONTROL PANEL (Expanded by default)
        dashboardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
            setPadding(0, dpToPx(8), 0, 0)
        }

        // Live Metric Rows
        fpsLabel = createMetricTextView("FPS: 30 fps", "#38BDF8")
        latencyLabel = createMetricTextView("Inference: 12ms", "#A855F7")
        stepLabel = createMetricTextView("Step: Standby", "#FBBF24")
        otpLabel = createMetricTextView("OTP Sniffer: Active (0 captured)", "#34D399")

        dashboardContainer.addView(fpsLabel)
        dashboardContainer.addView(latencyLabel)
        dashboardContainer.addView(stepLabel)
        dashboardContainer.addView(otpLabel)

        // 3. CONTROL ACTION PANEL
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(8), 0, 0)
        }

        playPauseBtn = Button(this).apply {
            text = "PLAY"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = dpToPx(8).toFloat()
            }
            setOnClickListener {
                if (currentState == ExecutionState.EXECUTING) {
                    setExecutionState(ExecutionState.PAUSED)
                    actionListener?.onPauseClicked()
                } else {
                    setExecutionState(ExecutionState.EXECUTING)
                    actionListener?.onPlayClicked()
                }
            }
        }

        abortBtn = Button(this).apply {
            text = "ABORT"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DC2626"))
                cornerRadius = dpToPx(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(8)
            }
            setOnClickListener {
                setExecutionState(ExecutionState.ABORTED)
                actionListener?.onAbortClicked()
            }
        }

        buttonRow.addView(playPauseBtn)
        buttonRow.addView(abortBtn)
        dashboardContainer.addView(buttonRow)

        mainCard.addView(dashboardContainer)
        root.addView(mainCard)

        // Drag-to-Move Gesture Listener with Margin Clamping
        setupDragListener(root, params)

        try {
            windowManager?.addView(root, params)
            Log.i(TAG, "Zenith HUD Overlay attached to WindowManager.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach HUD view: ${e.message}", e)
        }
    }

    private fun createMetricTextView(initialText: String, colorHex: String): TextView {
        return TextView(this).apply {
            text = initialText
            setTextColor(Color.parseColor(colorHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }
    }

    private fun toggleExpandedMode() {
        isExpanded = !isExpanded
        dashboardContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
    }

    fun updateTelemetry(fps: Int, latencyMs: Long, activeStepName: String? = null) {
        mainHandler.post {
            this.currentFps = fps
            this.currentLatencyMs = latencyMs
            if (activeStepName != null) {
                this.currentActiveStepName = activeStepName
            }

            fpsLabel.text = String.format(Locale.US, "FPS: %d fps", fps)
            latencyLabel.text = String.format(Locale.US, "Inference: %dms", latencyMs)
            stepLabel.text = String.format(Locale.US, "Step: %s", currentActiveStepName)

            val otpCount = OtpInterceptor.getInterceptCount()
            val otpState = if (OtpInterceptor.isIntercepting()) "Active" else "Disabled"
            otpLabel.text = String.format(Locale.US, "OTP Sniffer: %s (%d captured)", otpState, otpCount)
        }
    }

    fun setExecutionState(state: ExecutionState) {
        this.currentState = state
        mainHandler.post {
            when (state) {
                ExecutionState.IDLE -> {
                    statusLabel.text = "ZENITH • IDLE"
                    updateDotColor("#94A3B8") // Slate
                    playPauseBtn.text = "PLAY"
                }
                ExecutionState.SCANNING -> {
                    statusLabel.text = "ZENITH • SCANNING"
                    updateDotColor("#38BDF8") // Sky blue
                }
                ExecutionState.EXECUTING -> {
                    statusLabel.text = "ZENITH • RUNNING"
                    updateDotColor("#22C55E") // Emerald green
                    playPauseBtn.text = "PAUSE"
                }
                ExecutionState.PAUSED -> {
                    statusLabel.text = "ZENITH • PAUSED"
                    updateDotColor("#FBBF24") // Amber
                    playPauseBtn.text = "RESUME"
                }
                ExecutionState.ABORTED -> {
                    statusLabel.text = "ZENITH • ABORTED"
                    updateDotColor("#EF4444") // Red
                    playPauseBtn.text = "RESTART"
                }
            }
        }
    }

    private fun updateDotColor(colorHex: String) {
        (statusIndicatorDot.background as? GradientDrawable)?.setColor(Color.parseColor(colorHex))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                            val targetX = (initialX + dx.toInt()).coerceIn(0, viewportAdapter.physicalWidth - 200)
                            val targetY = (initialY + dy.toInt()).coerceIn(0, viewportAdapter.physicalHeight - 150)
                            params.x = targetX
                            params.y = targetY
                            windowManager?.updateViewLayout(rootOverlayView, params)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        return isDragging
                    }
                }
                return false
            }
        })
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        rootOverlayView?.let { root ->
            try {
                windowManager?.removeView(root)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing HUD view: ${e.message}", e)
            }
        }
        rootOverlayView = null
        Log.i(TAG, "ZenithHudService destroyed.")
    }
}
