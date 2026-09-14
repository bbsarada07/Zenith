package com.zenith.engine

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ZenithControlHUD: Sleek Multi-Modal Floating Control Capsule & Assistant Interface.
 *
 * Components:
 * - [Mic Button]: Hold-to-speak voice prompt triggering multimodal vision reasoning.
 * - [Vision Lock Toggle]: Instant toggle pausing/resuming screen stream ingestion via StateFlow.
 * - [Stealth / Opacity Slider]: Adjusts overlay visibility from 0% (Stealth) to 100% (High-Contrast).
 * - [Action Drawer]: Slide-out panel displaying live AI insights, OCR extractions, and one-tap clipboard auto-inject.
 */
class ZenithControlHUD @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val COLOR_CYBER_CYAN = 0xFF00F0FF.toInt()
        private const val COLOR_NEON_YELLOW = 0xFFFFE600.toInt()
        private const val COLOR_DANGER_RED = 0xFFFF0055.toInt()
        private const val COLOR_CAPSULE_BG = 0xEE0B0F17.toInt()
        private const val COLOR_DRAWER_BG = 0xF50D131F.toInt()
    }

    interface HUDInteractionListener {
        fun onVoicePromptTriggered(isHolding: Boolean)
        fun onVisionLockToggled(isLocked: Boolean)
        fun onOpacityChanged(opacity: Float)
        fun onActionExecuted(action: DualInferenceEngine.ActionPill)
    }

    var hudListener: HUDInteractionListener? = null

    // Vision Lock Reactive State
    private val _isVisionLocked = MutableStateFlow(false)
    val isVisionLocked: StateFlow<Boolean> = _isVisionLocked.asStateFlow()

    // Window Management & Touch State
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWindowX = 0
    private var initialWindowY = 0
    private var isDragging = false

    // UI Subviews
    private val capsuleBar: LinearLayout
    private val micButton: TextView
    private val lockButton: TextView
    private val drawerToggleButton: TextView
    private val statusIndicator: TextView

    // Expandable Action Drawer
    private val actionDrawer: LinearLayout
    private val drawerContentText: TextView
    private val copyClipboardButton: Button
    private val opacitySeekBar: SeekBar
    private var isDrawerExpanded = false

    init {
        // Root Container Setup
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 32f
            setColor(COLOR_CAPSULE_BG)
            setStroke(2, COLOR_CYBER_CYAN)
        }
        background = bgDrawable
        setPadding(16, 12, 16, 12)

        val mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 1. Horizontal Capsule Control Pill
        capsuleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        // Status / Logo Badge
        statusIndicator = TextView(context).apply {
            text = "ZENITH AI"
            setTextColor(COLOR_CYBER_CYAN)
            textSize = 12f
            setPadding(12, 8, 16, 8)
            paint.isFakeBoldText = true
        }
        capsuleBar.addView(statusIndicator)

        // Mic Button (Hold to speak)
        micButton = TextView(context).apply {
            text = "🎤 TALK"
            setTextColor(Color.WHITE)
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(16, 10, 16, 10)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(0xFF1E293B.toInt())
                setStroke(1, COLOR_CYBER_CYAN)
            }
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        setBackgroundColor(COLOR_DANGER_RED)
                        hudListener?.onVoicePromptTriggered(true)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        background = GradientDrawable().apply {
                            cornerRadius = 20f
                            setColor(0xFF1E293B.toInt())
                            setStroke(1, COLOR_CYBER_CYAN)
                        }
                        hudListener?.onVoicePromptTriggered(false)
                        true
                    }
                    else -> false
                }
            }
        }
        val micParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = 12
        }
        capsuleBar.addView(micButton, micParams)

        // Vision Lock Button
        lockButton = TextView(context).apply {
            text = "👁 LIVE"
            setTextColor(COLOR_CYBER_CYAN)
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(16, 10, 16, 10)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(0xFF162032.toInt())
                setStroke(1, COLOR_CYBER_CYAN)
            }
            setOnClickListener {
                val newLockState = !_isVisionLocked.value
                _isVisionLocked.value = newLockState
                updateVisionLockUI(newLockState)
                hudListener?.onVisionLockToggled(newLockState)
            }
        }
        val lockParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = 12
        }
        capsuleBar.addView(lockButton, lockParams)

        // Drawer Toggle Button
        drawerToggleButton = TextView(context).apply {
            text = "⚡ INSIGHTS"
            setTextColor(COLOR_NEON_YELLOW)
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(16, 10, 16, 10)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(0xFF222B1E.toInt())
                setStroke(1, COLOR_NEON_YELLOW)
            }
            setOnClickListener {
                toggleActionDrawer()
            }
        }
        capsuleBar.addView(drawerToggleButton)

        mainContainer.addView(capsuleBar)

        // 2. Expandable Action & Insights Drawer
        actionDrawer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LayoutParams(580, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16
            }
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(COLOR_DRAWER_BG)
                setStroke(1, 0xFF334155.toInt())
            }
            setPadding(16, 16, 16, 16)
        }

        val opacityLabel = TextView(context).apply {
            text = "STEALTH OPACITY"
            textSize = 10f
            setTextColor(0xFF94A3B8.toInt())
        }
        actionDrawer.addView(opacityLabel)

        opacitySeekBar = SeekBar(context).apply {
            max = 100
            progress = 95
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val opacity = progress / 100f
                    hudListener?.onOpacityChanged(opacity)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        actionDrawer.addView(opacitySeekBar)

        drawerContentText = TextView(context).apply {
            text = "Zenith Spatial Co-Pilot standing by.\nEvaluating active viewport..."
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 12, 0, 12)
        }
        actionDrawer.addView(drawerContentText)

        copyClipboardButton = Button(context).apply {
            text = "📋 COPY TO CLIPBOARD"
            setTextColor(Color.BLACK)
            textSize = 12f
            background = GradientDrawable().apply {
                cornerRadius = 14f
                setColor(COLOR_CYBER_CYAN)
            }
            setOnClickListener {
                copyInsightToClipboard()
            }
        }
        actionDrawer.addView(copyClipboardButton)

        mainContainer.addView(actionDrawer)
        addView(mainContainer)
    }

    fun attachWindowContext(wm: WindowManager, params: WindowManager.LayoutParams) {
        this.windowManager = wm
        this.layoutParams = params
    }

    private fun updateVisionLockUI(isLocked: Boolean) {
        if (isLocked) {
            lockButton.text = "🔒 LOCKED"
            lockButton.setTextColor(COLOR_NEON_YELLOW)
            statusIndicator.text = "PAUSED"
            statusIndicator.setTextColor(COLOR_NEON_YELLOW)
        } else {
            lockButton.text = "👁 LIVE"
            lockButton.setTextColor(COLOR_CYBER_CYAN)
            statusIndicator.text = "ZENITH AI"
            statusIndicator.setTextColor(COLOR_CYBER_CYAN)
        }
    }

    private fun toggleActionDrawer() {
        isDrawerExpanded = !isDrawerExpanded
        actionDrawer.visibility = if (isDrawerExpanded) View.VISIBLE else View.GONE
        drawerToggleButton.text = if (isDrawerExpanded) "▲ CLOSE" else "⚡ INSIGHTS"

        layoutParams?.let { lp ->
            windowManager?.updateViewLayout(this, lp)
        }
    }

    /**
     * Updates the drawer with live reasoning results or OCR extracts.
     */
    fun displayInsightResult(result: DualInferenceEngine.InferenceResult) {
        drawerContentText.text = "【${result.title} (${result.latencyMs}ms)】\n${result.summary}"
        if (!result.codeFix.isNullOrEmpty()) {
            drawerContentText.append("\n\n--- AUTO PATCH ---\n${result.codeFix}")
        }
    }

    /**
     * Copies active insight directly to Android System Clipboard.
     */
    private fun copyInsightToClipboard() {
        val textToCopy = drawerContentText.text.toString()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Zenith AI Insight", textToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied insight to clipboard!", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = layoutParams ?: return super.onTouchEvent(event)
        val wm = windowManager ?: return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialWindowX = params.x
                initialWindowY = params.y
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - initialTouchX).toInt()
                val deltaY = (event.rawY - initialTouchY).toInt()

                if (Math.abs(deltaX) > 8 || Math.abs(deltaY) > 8) {
                    isDragging = true
                }

                if (isDragging) {
                    params.x = initialWindowX + deltaX
                    params.y = initialWindowY + deltaY
                    try {
                        wm.updateViewLayout(this, params)
                    } catch (ignored: Exception) {}
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    snapToEdge(params, wm)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToEdge(params: WindowManager.LayoutParams, wm: WindowManager) {
        val displayWidth = context.resources.displayMetrics.widthPixels
        val currentX = params.x
        val targetX = if (currentX + (width / 2) < displayWidth / 2) {
            20
        } else {
            displayWidth - width - 20
        }

        val animator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                try {
                    wm.updateViewLayout(this@ZenithControlHUD, params)
                } catch (ignored: Exception) {}
            }
        }
        animator.start()
    }
}
