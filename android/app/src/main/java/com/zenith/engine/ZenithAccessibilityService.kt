package com.zenith.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ZenithAccessibilityService: High-Precision Remote Touch & Accessibility Gesture Dispatcher.
 *
 * Implements low-latency gesture injection for spatial automation, self-healing macros,
 * and remote web command tele-operation via Android's native AccessibilityService APIs.
 */
class ZenithAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ZenithAccessibility"

        @Volatile
        var instance: ZenithAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        /**
         * Dispatches a native tap at raw physical pixel coordinates with a completion callback.
         */
        fun dispatchTap(xPixels: Float, yPixels: Float, callback: (Boolean) -> Unit = {}) {
            val service = instance ?: run {
                Log.w(TAG, "ZenithAccessibilityService is not connected. Cannot dispatch tap.")
                callback(false)
                return
            }
            service.dispatchTap(xPixels, yPixels, callback)
        }

        /**
         * Dispatches a native tap gesture at normalized screen coordinates [0.0 to 1.0].
         */
        fun performTap(normX: Float, normY: Float, callback: (Boolean) -> Unit = {}) {
            val service = instance ?: run {
                Log.w(TAG, "ZenithAccessibilityService is not connected. Cannot perform tap.")
                callback(false)
                return
            }

            val metrics = service.resources.displayMetrics
            val targetX = (normX * metrics.widthPixels).coerceIn(0f, metrics.widthPixels.toFloat())
            val targetY = (normY * metrics.heightPixels).coerceIn(0f, metrics.heightPixels.toFloat())

            service.dispatchTap(targetX, targetY, callback)
        }

        fun injectTap(normX: Float, normY: Float) = performTap(normX, normY)

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
         * Dispatches system global actions (BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS).
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

        fun injectGlobalAction(actionId: Int): Boolean = performGlobalAction(actionId)

        /**
         * Injects text directly into the actively focused input field via Accessibility Node Action.
         */
        fun injectTextToFocus(text: String): Boolean {
            val service = instance ?: return false
            try {
                val root = service.rootInActiveWindow ?: return false
                val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    ?: return false

                val arguments = android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                val success = focusedNode.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    arguments
                )
                Log.i(TAG, "injectTextToFocus executed (Success: $success, Text: $text)")
                return success
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting text to focus field: ${e.message}", e)
                return false
            }
        }
    }

    /**
     * Dispatches a tap gesture at raw physical pixel coordinates (xPixels, yPixels) with a callback.
     */
    fun dispatchTap(xPixels: Float, yPixels: Float, callback: (Boolean) -> Unit = {}) {
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
        Log.i(TAG, "ZenithAccessibilityService connected and ready for remote gestures.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "ZenithAccessibilityService unbound.")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event processing hook if needed
    }

    override fun onInterrupt() {
        Log.w(TAG, "ZenithAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "ZenithAccessibilityService destroyed.")
    }
}
