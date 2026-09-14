package com.zenith.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * MainActivity: Zenith Engine Launcher, QR Direct Pairing & Remote Command Hub.
 *
 * Capabilities:
 * 1. Live Dynamic Wi-Fi IPv4 Detection & QR Code Generation (ZXing).
 * 2. System Overlay & MediaProjection Authorization Flow.
 * 3. Accessibility Remote Control Diagnostics & Direct Launcher.
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

    private lateinit var statusBadge: TextView
    private lateinit var urlBadge: TextView
    private lateinit var wifiWarningText: TextView
    private lateinit var qrImageView: ImageView
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

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG_DARK)
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 56)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 1. Header & Title
        val titleText = TextView(this).apply {
            text = "ZENITH ENGINE"
            textSize = 28f
            setTextColor(COLOR_CYBER_CYAN)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
        }
        container.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = "Real-Time AI Spatial Co-Pilot & Web Command Center"
            textSize = 13f
            setTextColor(0xFF8A99AD.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 24)
        }
        container.addView(subtitleText)

        // 2. Status Badge
        statusBadge = TextView(this).apply {
            text = "● STANDBY"
            textSize = 13f
            setTextColor(COLOR_NEON_YELLOW)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(28, 12, 28, 12)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(COLOR_PANEL_BG)
                setStroke(2, COLOR_NEON_YELLOW)
            }
        }
        container.addView(statusBadge)

        // 3. Live Server URL & QR Code Card
        val localIp = getDeviceIpAddress()
        val serverUrl = "http://$localIp:8080"

        val urlCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(COLOR_CARD_BG)
                setStroke(1, COLOR_CYBER_CYAN)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 28
                bottomMargin = 28
            }
        }

        val urlTitle = TextView(this).apply {
            text = "DESKTOP PAIRING & COMMAND CENTER"
            textSize = 11f
            setTextColor(0xFF8A99AD.toInt())
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
        }
        urlCard.addView(urlTitle)

        urlBadge = TextView(this).apply {
            text = serverUrl
            textSize = 18f
            setTextColor(COLOR_CYBER_CYAN)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }
        urlCard.addView(urlBadge)

        wifiWarningText = TextView(this).apply {
            text = if (localIp == "127.0.0.1") "⚠ Connect to Wi-Fi for Desktop Remote Control" else "✓ Direct Wi-Fi Access Active"
            textSize = 11f
            setTextColor(if (localIp == "127.0.0.1") COLOR_DANGER_RED else Color.GREEN)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        urlCard.addView(wifiWarningText)

        // QR Code Image
        qrImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(360, 360).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 16
            }
            setImageBitmap(generateQrCodeBitmap(serverUrl, 360))
        }
        urlCard.addView(qrImageView)

        val urlInstruction = TextView(this).apply {
            text = "Scan QR with desktop or tap URL to copy."
            textSize = 11f
            setTextColor(0xFFF0F4F8.toInt())
            gravity = Gravity.CENTER
        }
        urlCard.addView(urlInstruction)

        urlCard.setOnClickListener {
            val currentUrl = urlBadge.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Zenith Command URL", currentUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied URL: $currentUrl", Toast.LENGTH_SHORT).show()
        }
        container.addView(urlCard)

        // 4. Permissions Diagnostics Panel
        val diagCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(COLOR_PANEL_BG)
                setStroke(1, 0xFF1E293B.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 28
            }
        }

        overlayStatusText = TextView(this).apply {
            text = if (Settings.canDrawOverlays(this@MainActivity)) "✓ System Overlay: GRANTED" else "✗ System Overlay: REQUIRED"
            textSize = 12f
            setTextColor(if (Settings.canDrawOverlays(this@MainActivity)) Color.GREEN else COLOR_DANGER_RED)
            setPadding(0, 3, 0, 3)
        }
        diagCard.addView(overlayStatusText)

        accessibilityStatusText = TextView(this).apply {
            text = if (ZenithAccessibilityService.isRunning()) "✓ Remote Touch: ACTIVE" else "⚠ Remote Touch: DISABLED"
            textSize = 12f
            setTextColor(if (ZenithAccessibilityService.isRunning()) Color.GREEN else COLOR_NEON_YELLOW)
            setPadding(0, 3, 0, 3)
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
                cornerRadius = 16f
                setColor(COLOR_CYBER_CYAN)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                130
            ).apply {
                bottomMargin = 20
            }
            setOnClickListener {
                startCapturePipeline()
            }
        }
        container.addView(btnStart)

        val btnAccessibility = Button(this).apply {
            text = "⚡ ENABLE REMOTE TOUCH (ACCESSIBILITY)"
            textSize = 12f
            setTextColor(COLOR_NEON_YELLOW)
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(0xFF1E2415.toInt())
                setStroke(2, COLOR_NEON_YELLOW)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                120
            ).apply {
                bottomMargin = 20
            }
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Enable 'Zenith Remote Touch' under Downloaded Apps", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        container.addView(btnAccessibility)

        val btnOpenBrowser = Button(this).apply {
            text = "🌐 OPEN WEB DASHBOARD LOCALLY"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(0xFF1E293B.toInt())
                setStroke(1, COLOR_CYBER_CYAN)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                120
            )
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlBadge.text.toString()))
                startActivity(intent)
            }
        }
        container.addView(btnOpenBrowser)

        scrollView.addView(container)
        setContentView(scrollView)

        checkOverlayPermission()
    }

    override fun onResume() {
        super.onResume()
        // Refresh IP, QR code & Diagnostics
        val ip = getDeviceIpAddress()
        val currentUrl = "http://$ip:8080"
        urlBadge.text = currentUrl

        if (ip == "127.0.0.1") {
            wifiWarningText.text = "⚠ Connect to Wi-Fi for Desktop Remote Control"
            wifiWarningText.setTextColor(COLOR_DANGER_RED)
        } else {
            wifiWarningText.text = "✓ Direct Wi-Fi Access Active"
            wifiWarningText.setTextColor(Color.GREEN)
        }

        qrImageView.setImageBitmap(generateQrCodeBitmap(currentUrl, 360))

        overlayStatusText.text = if (Settings.canDrawOverlays(this)) "✓ System Overlay: GRANTED" else "✗ System Overlay: REQUIRED"
        overlayStatusText.setTextColor(if (Settings.canDrawOverlays(this)) Color.GREEN else COLOR_DANGER_RED)

        accessibilityStatusText.text = if (ZenithAccessibilityService.isRunning()) "✓ Remote Touch: ACTIVE" else "⚠ Remote Touch: DISABLED (Tap below to enable)"
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
     * Resolves the primary local IPv4 address across active Wi-Fi, Ethernet, and Hotspot adapters.
     */
    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Prioritize Wi-Fi and hotspot interfaces
            val sortedInterfaces = interfaces.sortedByDescending {
                it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("rndis") || it.name.startsWith("eth")
            }

            for (intf in sortedInterfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address && !addr.isLinkLocalAddress) {
                        val host = addr.hostAddress
                        if (!host.isNullOrEmpty() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}
        return "127.0.0.1"
    }

    /**
     * Generates a Cyber-styled 2D QR Code Bitmap using ZXing.
     */
    private fun generateQrCodeBitmap(content: String, size: Int = 360): Bitmap {
        return try {
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (bitMatrix.get(x, y)) COLOR_CYBER_CYAN else COLOR_CARD_BG
                    )
                }
            }
            bitmap
        } catch (e: Exception) {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        }
    }
}