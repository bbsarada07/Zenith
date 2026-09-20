package com.zenith.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * ZenithAccessibilityService: Autonomous Android Spatial Co-Pilot & Hybrid Grounding Engine.
 *
 * Implements:
 * 1. Hybrid Grounding Engine (Pass 1 Semantic Tree + Pass 2 Spatial VLM Fallback).
 * 2. Multi-Agent Reflection Loop: Checks for state mutation 1500ms post-action, capturing screenshots on NO_MUTATION.
 * 3. Real-Time Bidirectional OkHttp WebSocket Telemetry with Node.js Planner controller.
 * 4. WindowManager Floating Overlay Manager with visual reticles, status badges, and Emergency Stop.
 * 5. Debounced Event Processing (100ms throttle) to keep dispatch latency under 150ms.
 */
class ZenithAccessibilityService : AccessibilityService(), ZenithWebSocketClient.CommandListener {

    companion object {
        private const val TAG = "ZenithAccessibility"
        private const val REFLECTION_DELAY_MS = 1500L
        private const val DEBOUNCE_WINDOW_MS = 100L

        @Volatile
        var instance: ZenithAccessibilityService? = null
            private set

        @Volatile
        var isServiceActive: Boolean = false
            private set

        fun isRunning(): Boolean = instance != null && isServiceActive

        fun dispatchTap(xPixels: Float, yPixels: Float, callback: (Boolean) -> Unit = {}) {
            val service = instance ?: run {
                Log.w(TAG, "ZenithAccessibilityService is not connected.")
                callback(false)
                return
            }
            service.dispatchTapInternal(xPixels, yPixels, callback)
        }

        fun performClickAt(normX: Float, normY: Float, callback: (Boolean) -> Unit = {}) {
            val service = instance ?: run {
                Log.w(TAG, "ZenithAccessibilityService is not connected.")
                callback(false)
                return
            }
            val metrics = service.resources.displayMetrics
            val targetX = (normX * metrics.widthPixels).coerceIn(0f, metrics.widthPixels.toFloat())
            val targetY = (normY * metrics.heightPixels).coerceIn(0f, metrics.heightPixels.toFloat())
            service.dispatchTapInternal(targetX, targetY, callback)
        }

        fun performTap(normX: Float, normY: Float, callback: (Boolean) -> Unit = {}) =
            performClickAt(normX, normY, callback)

        fun performSwipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long = 250L,
            callback: (Boolean) -> Unit = {}
        ) {
            val service = instance ?: run {
                Log.w(TAG, "ZenithAccessibilityService is not connected.")
                callback(false)
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                callback(false)
                return
            }

            val metrics = service.resources.displayMetrics
            val x1 = (startX * metrics.widthPixels).coerceIn(0f, metrics.widthPixels.toFloat())
            val y1 = (startY * metrics.heightPixels).coerceIn(0f, metrics.heightPixels.toFloat())
            val x2 = (endX * metrics.widthPixels).coerceIn(0f, metrics.widthPixels.toFloat())
            val y2 = (endY * metrics.heightPixels).coerceIn(0f, metrics.heightPixels.toFloat())

            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }

            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Swipe completed from ($x1, $y1) to ($x2, $y2)")
                    callback(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Swipe cancelled from ($x1, $y1) to ($x2, $y2)")
                    callback(false)
                }
            }, null)
        }

        fun performGlobalAction(actionId: Int): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(actionId)
        }

        fun performGlobalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
        fun performGlobalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
        fun performGlobalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
        fun performGlobalNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        fun performGlobalQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

        fun injectTap(normX: Float, normY: Float, callback: (Boolean) -> Unit = {}) =
            performClickAt(normX, normY, callback)

        fun injectSwipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long = 250L,
            callback: (Boolean) -> Unit = {}
        ) = performSwipe(startX, startY, endX, endY, durationMs, callback)

        fun injectGlobalAction(actionId: Int): Boolean = performGlobalAction(actionId)

        fun inputText(text: String): Boolean {
            val service = instance ?: return false
            try {
                val root = service.rootInActiveWindow ?: return false
                val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

                if (focusedNode != null) {
                    val arguments = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                        )
                    }
                    val setTextSuccess = focusedNode.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        arguments
                    )
                    if (setTextSuccess) return true

                    // Fallback to paste action on node
                    val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null) {
                        val clip = ClipData.newPlainText("ZenithInput", text)
                        clipboard.setPrimaryClip(clip)
                        val pasteSuccess = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        if (pasteSuccess) return true
                    }
                }

                // Global clipboard fallback
                val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    val clip = ClipData.newPlainText("ZenithInput", text)
                    clipboard.setPrimaryClip(clip)
                    return true
                }
                return false
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting text: ${e.message}", e)
                return false
            }
        }

        fun injectTextToFocus(text: String): Boolean = inputText(text)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    // Overlay & WebSocket Client components
    private var overlayManager: ZenithOverlayManager? = null
    private var webSocketClient: ZenithWebSocketClient? = null

    // Execution state
    private val isEmergencyStopped = AtomicBoolean(false)
    private var lastEventTimestampMs = 0L
    private var currentPackageName: String = "unknown"

    // Pending confirmation continuation
    private var pendingConfirmationStepId: String? = null

    fun dispatchTap(xPixels: Float, yPixels: Float, callback: (Boolean) -> Unit = {}) {
        dispatchTapInternal(xPixels, yPixels, callback)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceActive = true
        isEmergencyStopped.set(false)
        Log.i(TAG, "ZenithAccessibilityService connected and ready.")

        // 1. Initialize Floating Overlay Manager
        overlayManager = ZenithOverlayManager(
            context = applicationContext,
            onEmergencyStopTriggered = {
                handleEmergencyStop()
            },
            onConfirmationResult = { approved, reason ->
                handleConfirmationResult(approved, reason)
            }
        )

        // 2. Initialize OkHttp WebSocket Client to Node.js Planner
        webSocketClient = ZenithWebSocketClient(
            serverUrl = "ws://192.168.1.3:8080",
            commandListener = this
        ).apply {
            connect()
        }

        broadcastStatusUpdate("ACTIVE")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        isServiceActive = false
        overlayManager?.detachOverlay()
        overlayManager = null
        webSocketClient?.close()
        webSocketClient = null
        broadcastStatusUpdate("DISABLED")
        Log.i(TAG, "ZenithAccessibilityService unbound.")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "ZenithAccessibilityService interrupted.")
        isServiceActive = false
        broadcastStatusUpdate("INTERRUPTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceActive = false
        serviceScope.cancel()
        overlayManager?.detachOverlay()
        overlayManager = null
        webSocketClient?.close()
        webSocketClient = null
        screenshotExecutor.shutdown()
        Log.i(TAG, "ZenithAccessibilityService destroyed.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrEmpty()) {
            currentPackageName = pkg
        }

        // Debounce WINDOW_CONTENT_CHANGED (100ms throttle)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = System.currentTimeMillis()
            if (now - lastEventTimestampMs < DEBOUNCE_WINDOW_MS) {
                return
            }
            lastEventTimestampMs = now
        }
    }

    // --- WebSocket CommandListener Implementation ---

    override fun onCommandReceived(command: ZenithWebSocketClient.InboundCommand) {
        if (isEmergencyStopped.get()) {
            Log.w(TAG, "Ignoring command '${command.action}' due to active Emergency Stop.")
            sendTelemetryResponse("EMERGENCY_STOP", command.stepId, errorMessage = "Emergency stop is active")
            return
        }

        serviceScope.launch {
            executeAutonomousCommand(command)
        }
    }

    override fun onConnectionStatusChanged(isConnected: Boolean, statusMessage: String) {
        val color = if (isConnected) 0xFF00FF88.toInt() else 0xFFFF0055.toInt()
        overlayManager?.updateStatus(if (isConnected) "⚡ PLANNER LINKED" else "DISCONNECTED", color)
    }

    /**
     * Executes inbound commands through Hybrid Grounding with Reflection Loop.
     */
    private suspend fun executeAutonomousCommand(command: ZenithWebSocketClient.InboundCommand) {
        val stepId = command.stepId

        // Handle user confirmation requests
        if (command.action == "REQUIRE_USER_CONFIRMATION") {
            pendingConfirmationStepId = stepId
            overlayManager?.promptUserConfirmation(command.payload.ifEmpty { "High-impact action intercepted." })
            return
        }

        // 1. Capture Pre-Action State Hash for Reflection Loop
        val preNodes = NodeTreeSerializer.extractSimplifiedNodes(rootInActiveWindow)
        val preHash = NodeTreeSerializer.computeStateHash(currentPackageName, preNodes)

        overlayManager?.updateStatus("EXECUTING: ${command.action}", 0xFFFFE600.toInt())

        var actionDispatched = false

        when (command.action) {
            "CLICK_TEXT" -> {
                actionDispatched = executeHybridClick(command.target, command.coords)
            }

            "CLICK_COORD" -> {
                val coords = command.coords
                if (coords != null) {
                    val (physX, physY) = translateCoordinates(coords.first, coords.second)
                    overlayManager?.highlightTargetBounds(
                        RectF(physX - 30f, physY - 30f, physX + 30f, physY + 30f),
                        label = "TAP ($physX, $physY)"
                    )
                    actionDispatched = dispatchTapSuspend(physX, physY)
                }
            }

            "SWIPE" -> {
                val start = command.coords ?: Pair(0.5f, 0.7f)
                val end = command.swipeEndCoords ?: Pair(0.5f, 0.3f)
                actionDispatched = suspendCancellableCoroutine { continuation ->
                    performSwipe(start.first, start.second, end.first, end.second, 250L) { success ->
                        continuation.resume(success)
                    }
                }
            }

            "TYPE_TEXT" -> {
                val text = command.payload
                actionDispatched = inputText(text)
            }

            "INPUT_KEY" -> {
                actionDispatched = when (command.target.lowercase()) {
                    "back" -> performGlobalBack()
                    "home" -> performGlobalHome()
                    "recents" -> performGlobalRecents()
                    else -> performGlobalBack()
                }
            }
        }

        if (!actionDispatched) {
            Log.w(TAG, "Command '${command.action}' dispatch failed for target '${command.target}'.")
            overlayManager?.updateStatus("NODE NOT FOUND", 0xFFFF0055.toInt())
            sendTelemetryResponse("NODE_NOT_FOUND", stepId, errorMessage = "Target '${command.target}' could not be located.")
            return
        }

        // 2. Reflection Loop: Wait 1500ms and verify screen state mutation
        overlayManager?.updateStatus("VERIFYING STATE...", 0xFF00F3FF.toInt())
        delay(REFLECTION_DELAY_MS)

        val postNodes = NodeTreeSerializer.extractSimplifiedNodes(rootInActiveWindow)
        val postHash = NodeTreeSerializer.computeStateHash(currentPackageName, postNodes)

        val metrics = resources.displayMetrics

        if (preHash == postHash) {
            // NO_MUTATION detected: capture screenshot and send to Planner for visual reasoning
            Log.w(TAG, "Reflection Loop: NO_MUTATION detected for step '$stepId'. Capturing screenshot...")
            overlayManager?.updateStatus("⚠️ NO MUTATION", 0xFFFFE600.toInt())

            val screenshotBase64 = captureScreenshotBase64()
            webSocketClient?.sendTelemetry(
                status = "NO_MUTATION",
                stepId = stepId,
                currentApp = currentPackageName,
                nodeTreeHash = postHash,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                screenBase64 = screenshotBase64,
                errorMessage = "State hash identical after 1500ms reflection delay"
            )
        } else {
            // SUCCESS: UI mutated successfully
            Log.i(TAG, "Reflection Loop: State mutation verified (Hash: $postHash)")
            overlayManager?.updateStatus("STEP COMPLETED", 0xFF00FF88.toInt())
            webSocketClient?.sendTelemetry(
                status = "SUCCESS",
                stepId = stepId,
                currentApp = currentPackageName,
                nodeTreeHash = postHash,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels
            )
        }
    }

    /**
     * Two-Pass Hybrid Grounding Locator:
     * - Pass 1 (Semantic): Exact/Partial text, content description, or resource ID match on AccessibilityNodeInfo tree.
     * - Pass 2 (Spatial/Visual Fallback): Inbound normalized coordinates fallback if tree match is unlabelled.
     */
    private suspend fun executeHybridClick(
        target: String,
        fallbackCoords: Pair<Float, Float>?
    ): Boolean = withContext(Dispatchers.Default) {
        // Pass 1: Semantic Node Tree Crawler
        val root = rootInActiveWindow
        val matchedNode = NodeTreeSerializer.findSemanticNode(root, target)

        if (matchedNode != null) {
            val bounds = Rect()
            matchedNode.getBoundsInScreen(bounds)

            // Highlight target bounds on overlay
            overlayManager?.highlightTargetBounds(
                RectF(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
                label = "MATCH: $target"
            )

            // If node or clickable ancestor can take ACTION_CLICK directly
            var clickableNode: AccessibilityNodeInfo? = matchedNode
            while (clickableNode != null && !clickableNode.isClickable) {
                clickableNode = clickableNode.parent
            }

            if (clickableNode != null && clickableNode.isClickable) {
                val success = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    Log.i(TAG, "Pass 1 (Semantic): Successfully clicked node via ACTION_CLICK for '$target'")
                    return@withContext true
                }
            }

            // Otherwise dispatch gesture tap to bounding box center
            val centerX = bounds.centerX().toFloat()
            val centerY = bounds.centerY().toFloat()
            Log.i(TAG, "Pass 1 (Semantic): Dispatching gesture tap at ($centerX, $centerY) for '$target'")
            return@withContext dispatchTapSuspend(centerX, centerY)
        }

        // Pass 2: Spatial Fallback Coordinates
        if (fallbackCoords != null) {
            val (physX, physY) = translateCoordinates(fallbackCoords.first, fallbackCoords.second)
            Log.i(TAG, "Pass 2 (Spatial Fallback): Dispatching tap at ($physX, $physY) for unlabelled '$target'")

            overlayManager?.highlightTargetBounds(
                RectF(physX - 40f, physY - 40f, physX + 40f, physY + 40f),
                label = "VLM ($physX, $physY)"
            )

            return@withContext dispatchTapSuspend(physX, physY)
        }

        Log.w(TAG, "Hybrid Grounding failed: Target '$target' not found in tree and no spatial fallback provided.")
        return@withContext false
    }

    /**
     * Translates coordinates (0-1000 scale, percentage 0.0-1.0, or raw pixels) to physical display pixels.
     */
    private fun translateCoordinates(xVal: Float, yVal: Float): Pair<Float, Float> {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val physX = when {
            xVal in 0.0f..1.0f -> xVal * width
            xVal in 1.0f..1000.0f -> (xVal / 1000.0f) * width
            else -> xVal
        }

        val physY = when {
            yVal in 0.0f..1.0f -> yVal * height
            yVal in 1.0f..1000.0f -> (yVal / 1000.0f) * height
            else -> yVal
        }

        return Pair(physX.coerceIn(0f, width), physY.coerceIn(0f, height))
    }

    private suspend fun dispatchTapSuspend(xPixels: Float, yPixels: Float): Boolean =
        suspendCancellableCoroutine { continuation ->
            dispatchTapInternal(xPixels, yPixels) { success ->
                continuation.resume(success)
            }
        }

    private fun dispatchTapInternal(xPixels: Float, yPixels: Float, callback: (Boolean) -> Unit = {}) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback(false)
            return
        }

        val path = Path().apply {
            moveTo(xPixels, yPixels)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap completed at ($xPixels, $yPixels)")
                callback(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap cancelled at ($xPixels, $yPixels)")
                callback(false)
            }
        }, null)
    }

    /**
     * Android 11+ Native Screenshot Capture:
     * Converts hardware bitmap to Base64 JPEG (compression 70%).
     */
    private suspend fun captureScreenshotBase64(): String? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Native takeScreenshot requires Android 11+ (API 30+)")
            return@withContext null
        }

        return@withContext suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                if (hardwareBitmap == null) {
                                    continuation.resume(null)
                                    return
                                }

                                // Convert hardware bitmap to software for JPEG compression
                                val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBitmap.recycle()
                                screenshot.hardwareBuffer.close()

                                val outputStream = ByteArrayOutputStream()
                                softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                                softwareBitmap.recycle()

                                val base64String = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                                continuation.resume(base64String)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error encoding screenshot: ${e.message}", e)
                                continuation.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "takeScreenshot failed with error code: $errorCode")
                            continuation.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot exception: ${e.message}", e)
                continuation.resume(null)
            }
        }
    }

    private fun handleEmergencyStop() {
        isEmergencyStopped.set(true)
        val metrics = resources.displayMetrics
        webSocketClient?.sendTelemetry(
            status = "EMERGENCY_STOP",
            stepId = "emergency_stop",
            currentApp = currentPackageName,
            nodeTreeHash = "STOP",
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            errorMessage = "User initiated Emergency Stop from floating HUD"
        )
    }

    private fun handleConfirmationResult(approved: Boolean, reason: String) {
        val stepId = pendingConfirmationStepId ?: "confirmation"
        pendingConfirmationStepId = null
        val metrics = resources.displayMetrics

        webSocketClient?.sendTelemetry(
            status = if (approved) "CONFIRMATION_APPROVED" else "CONFIRMATION_REJECTED",
            stepId = stepId,
            currentApp = currentPackageName,
            nodeTreeHash = if (approved) "APPROVED" else "REJECTED",
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            errorMessage = if (!approved) reason else null
        )
    }

    private fun sendTelemetryResponse(status: String, stepId: String, errorMessage: String? = null) {
        val metrics = resources.displayMetrics
        webSocketClient?.sendTelemetry(
            status = status,
            stepId = stepId,
            currentApp = currentPackageName,
            nodeTreeHash = "",
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            errorMessage = errorMessage
        )
    }

    private fun broadcastStatusUpdate(status: String) {
        val json = JSONObject().apply {
            put("type", "accessibility_status")
            put("status", status)
            put("timestamp", System.currentTimeMillis())
        }
        ScreenCaptureService.broadcastStatusJson(json)
    }
}
