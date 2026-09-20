package com.zenith.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * ZenithAccessibilityService: High-Precision Remote Touch & Accessibility Gesture Dispatcher.
 *
 * Implements low-latency gesture injection for spatial automation, self-healing macros,
 * and remote web command tele-operation via Android's native AccessibilityService APIs.
 *
 * Features:
 * 1. Normalized coordinate dispatcher (0.0..1.0 -> physical display pixels).
 * 2. Gestures: performClickAt, performSwipe, performGlobalBack, performGlobalHome, performGlobalRecents.
 * 3. Robust text injection: ACTION_SET_TEXT on focused node with clipboard paste fallback.
 * 4. Diagnostic status endpoint pushing ACTIVE/DISABLED updates over WebSocket.
 */
class ZenithAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ZenithAccessibility"

        @Volatile
        var instance: ZenithAccessibilityService? = null
            private set

        @Volatile
        var isServiceActive: Boolean = false
            private set

        fun isRunning(): Boolean = instance != null && isServiceActive

        /**
         * Dispatches a native tap at raw physical pixel coordinates with a completion callback.
         */
        fun dispatchTap(xPixels: Float, yPixels: Float, callback: (Boolean) -> Unit = {}) {
            val service = instance ?: run {
                Log.w(TAG, "ZenithAccessibilityService is not connected. Cannot dispatch tap.")
                callback(false)
                return
            }
            service.dispatchTapInternal(xPixels, yPixels, callback)
        }

        /**
         * Dispatches a native click at normalized screen coordinates [0.0 to 1.0].
         */
        fun performClickAt(normX: Float, normY: Float, callback: (Boolean) -> Unit = {}) {
            val service = instance ?: run {
                Log.w(TAG, "ZenithAccessibilityService is not connected. Cannot perform click.")
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

        fun injectTap(normX: Float, normY: Float) = performClickAt(normX, normY)

        /**
         * Dispatches a native swipe gesture between normalized screen coordinates.
         */
        fun performSwipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long = 250L,
            callback: (Boolean) -> Unit = {}
        ) {
            val service = instance ?: run {
                Log.w(TAG, "ZenithAccessibilityService is not connected. Cannot perform swipe.")
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

        fun injectSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 250L) =
            performSwipe(startX, startY, endX, endY, durationMs)

        /**
         * Dispatches system global actions.
         */
        fun performGlobalAction(actionId: Int): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "ZenithAccessibilityService is null. Cannot perform global action $actionId.")
                return false
            }
            val result = service.performGlobalAction(actionId)
            Log.i(TAG, "Global action $actionId executed (Result: $result)")
            return result
        }

        fun performGlobalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
        fun performGlobalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
        fun performGlobalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
        fun performGlobalNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        fun performGlobalQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

        fun injectGlobalAction(actionId: Int): Boolean = performGlobalAction(actionId)

        /**
         * Injects text directly into the actively focused input field via Accessibility Node Action,
         * with automatic fallback to clipboard paste simulation if unhandled.
         */
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
                    if (setTextSuccess) {
                        Log.i(TAG, "inputText: Successfully set text via ACTION_SET_TEXT")
                        return true
                    }

                    // Fallback to paste action on node
                    val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null) {
                        val clip = ClipData.newPlainText("ZenithInput", text)
                        clipboard.setPrimaryClip(clip)
                        val pasteSuccess = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        if (pasteSuccess) {
                            Log.i(TAG, "inputText: Fallback ACTION_PASTE succeeded on focused node")
                            return true
                        }
                    }
                }

                // Global clipboard fallback
                val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    val clip = ClipData.newPlainText("ZenithInput", text)
                    clipboard.setPrimaryClip(clip)
                    Log.i(TAG, "inputText: Copied to global clipboard as fallback for custom canvas/webview")
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

    /**
     * Instance-level tap dispatcher accessible on service instances.
     */
    fun dispatchTap(xPixels: Float, yPixels: Float, callback: (Boolean) -> Unit = {}) {
        dispatchTapInternal(xPixels, yPixels, callback)
    }

    private fun dispatchTapInternal(xPixels: Float, yPixels: Float, callback: (Boolean) -> Unit = {}) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture dispatching requires Android 7.0+ (API 24+)")
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceActive = true
        Log.i(TAG, "ZenithAccessibilityService connected and ready for remote gestures.")
        broadcastStatusUpdate("ACTIVE")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        isServiceActive = false
        Log.i(TAG, "ZenithAccessibilityService unbound.")
        broadcastStatusUpdate("DISABLED")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility Event processing hook
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
        broadcastStatusUpdate("DISABLED")
        Log.i(TAG, "ZenithAccessibilityService destroyed.")
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
