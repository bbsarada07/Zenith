package com.zenith.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * MainActivity: Zenith Engine Launcher & Remote Command Hub.
 *
 * Provides:
 * 1. Live Local IP & Port Resolver (e.g. http://192.168.1.15:8080).
 * 2. System Overlay & MediaProjection Authorization Flow.
 * 3. Accessibility Remote Control Setup & Live Diagnostics.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val COLOR_CYBER_CYAN = 0xFF00F0FF.toInt()
        private const val COLOR_NEON_YELLOW = 0xFFFFE600.toInt()
        private const val COLOR_DANGER_RED = 0xFFFF0055.toInt()
        private const val COLOR_BG_DARK = 0xFF080B10.toInt()
        private const val COLOR_PANEL_BG = 0xFF0E131C.toInt()
        private const val COLOR_CARD_BG = 0xFF141C2B.toInt()
    }

    private var hasRequestedProjection = false

    private lateinit var statusBadge: TextView
    private lateinit var urlBadge: TextView
    private lateinit var accessibilityStatusText: TextView
    private lateinit var overlayStatusText: TextView

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            updateStatusUI(isActive = true)
            Toast.makeText(this, "Zenith Engine Active on Port 8080", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Root ScrollView & Container
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG_DARK)
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 1. Header & Title Badge
        val titleText = TextView(this).apply {
            text = "ZENITH ENGINE"
            textSize = 28f
            setTextColor(COLOR_CYBER_CYAN)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
        }
        container.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = "Real-Time AI Spatial Co-Pilot & Web Command Hub"
            textSize = 14f
            setTextColor(0xFF8A99AD.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 36)
        }
        container.addView(subtitleText)

        // 2. Status Badge
        statusBadge = TextView(this).apply {
            text = "● STANDBY"
            textSize = 14f
            setTextColor(COLOR_NEON_YELLOW)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 16)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(COLOR_PANEL_BG)
                setStroke(2, COLOR_NEON_YELLOW)
            }
        }
        container.addView(statusBadge)

        // 3. Live Server URL Card
        val localIp = getDeviceIpAddress()
        val serverUrl = "http://$localIp:8080"

        val urlCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(COLOR_CARD_BG)
                setStroke(1, COLOR_CYBER_CYAN)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 36
                bottomMargin = 36
            }
        }

        val urlTitle = TextView(this).apply {
            text = "DESKTOP COMMAND CENTER URL"
            textSize = 11f
            setTextColor(0xFF8A99AD.toInt())
            paint.isFakeBoldText = true
        }
        urlCard.addView(urlTitle)

        urlBadge = TextView(this).apply {
            text = serverUrl
            textSize = 18f
            setTextColor(COLOR_CYBER_CYAN)
            paint.isFakeBoldText = true
            setPadding(0, 10, 0, 10)
        }
        urlCard.addView(urlBadge)

        val urlInstruction = TextView(this).apply {
            text = "Tap to copy URL or open in desktop browser on the same Wi-Fi network."
            textSize = 12f
            setTextColor(0xFFF0F4F8.toInt())
        }
        urlCard.addView(urlInstruction)

        urlCard.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Zenith Command URL", serverUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied URL: $serverUrl", Toast.LENGTH_SHORT).show()
        }
        container.addView(urlCard)

        // 4. Permissions Diagnostics Panel
        val diagCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(COLOR_PANEL_BG)
                setStroke(1, 0xFF1E293B.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 36
            }
        }

        overlayStatusText = TextView(this).apply {
            text = if (Settings.canDrawOverlays(this@MainActivity)) "✓ System Overlay Permission: GRANTED" else "✗ System Overlay Permission: REQUIRED"
            textSize = 12f
            setTextColor(if (Settings.canDrawOverlays(this@MainActivity)) Color.GREEN else COLOR_DANGER_RED)
            setPadding(0, 4, 0, 4)
        }
        diagCard.addView(overlayStatusText)

        accessibilityStatusText = TextView(this).apply {
            text = if (ZenithAccessibilityService.isRunning()) "✓ Remote Touch Control: ACTIVE" else "⚠ Remote Touch Control: DISABLED (Accessibility Needed)"
            textSize = 12f
            setTextColor(if (ZenithAccessibilityService.isRunning()) Color.GREEN else COLOR_NEON_YELLOW)
            setPadding(0, 4, 0, 4)
        }
        diagCard.addView(accessibilityStatusText)

        container.addView(diagCard)

        // 5. Action Buttons
        val btnStart = Button(this).apply {
            text = "🚀 START SPATIAL CO-PILOT"
            textSize = 14f
            setTextColor(Color.BLACK)
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(COLOR_CYBER_CYAN)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                140
            ).apply {
                bottomMargin = 24
            }
            setOnClickListener {
                startCapturePipeline()
            }
        }
        container.addView(btnStart)

        val btnAccessibility = Button(this).apply {
            text = "⚡ ENABLE REMOTE TOUCH (ACCESSIBILITY)"
            textSize = 13f
            setTextColor(COLOR_NEON_YELLOW)
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(0xFF1E2415.toInt())
                setStroke(2, COLOR_NEON_YELLOW)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                130
            ).apply {
                bottomMargin = 24
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        container.addView(btnAccessibility)

        val btnOpenBrowser = Button(this).apply {
            text = "🌐 LAUNCH WEB DASHBOARD LOCALLY"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(0xFF1E293B.toInt())
                setStroke(1, COLOR_CYBER_CYAN)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                130
            )
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(serverUrl))
                startActivity(intent)
            }
        }
        container.addView(btnOpenBrowser)

        scrollView.addView(container)
        setContentView(scrollView)

        // Check Overlay Permission on Startup
        checkOverlayPermission()
    }

    override fun onResume() {
        super.onResume()
        // Refresh IP & Status Diagnostics
        val ip = getDeviceIpAddress()
        urlBadge.text = "http://$ip:8080"

        overlayStatusText.text = if (Settings.canDrawOverlays(this)) "✓ System Overlay Permission: GRANTED" else "✗ System Overlay Permission: REQUIRED"
        overlayStatusText.setTextColor(if (Settings.canDrawOverlays(this)) Color.GREEN else COLOR_DANGER_RED)

        accessibilityStatusText.text = if (ZenithAccessibilityService.isRunning()) "✓ Remote Touch Control: ACTIVE" else "⚠ Remote Touch Control: DISABLED (Accessibility Needed)"
        accessibilityStatusText.setTextColor(if (ZenithAccessibilityService.isRunning()) Color.GREEN else COLOR_NEON_YELLOW)
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun startCapturePipeline() {
        if (!Settings.canDrawOverlays(this)) {
            checkOverlayPermission()
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun updateStatusUI(isActive: Boolean) {
        if (isActive) {
            statusBadge.text = "● STREAMING ON PORT 8080"
            statusBadge.setTextColor(COLOR_CYBER_CYAN)
            (statusBadge.background as? GradientDrawable)?.setStroke(2, COLOR_CYBER_CYAN)
        } else {
            statusBadge.text = "● STANDBY"
            statusBadge.setTextColor(COLOR_NEON_YELLOW)
            (statusBadge.background as? GradientDrawable)?.setStroke(2, COLOR_NEON_YELLOW)
        }
    }

    /**
     * Resolves the primary local IPv4 address (Wi-Fi or Hotspot).
     */
    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.contains("wlan") || intf.name.contains("ap") || intf.name.contains("rndis") || intf.name.contains("eth")) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress
                            if (!host.isNullOrEmpty()) return host
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}
        return "127.0.0.1"
    }
}