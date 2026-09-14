package com.zenith.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * ZenithAccessibilityService: High-Precision Remote Touch & Gesture Dispatcher.
 *
 * Receives normalized viewport coordinates (0.0 to 1.0) from the local WebSocket streaming server
 * and injects native taps and swipe gestures across the Android OS.
 */
class ZenithAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ZenithAccessibility"

        @Volatile
        var instance: ZenithAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        /**
         * Dispatches a native tap gesture at normalized screen coordinates.
         *
         * @param normX Horizontal coordinate [0.0, 1.0]
         * @param normY Vertical coordinate [0.0, 1.0]
         */
        fun performTap(normX: Float, normY: Float) {
            val service = instance ?: run {
                Log.w(TAG, "ZenithAccessibilityService is not enabled or instance is null. Cannot perform tap.")
                return
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                Log.e(TAG, "Gesture dispatching requires Android 7.0+ (API 24+)")
                return
            }

            val metrics = service.resources.displayMetrics
            val targetX = (normX * metrics.widthPixels).coerceIn(0f, metrics.widthPixels.toFloat())
            val targetY = (normY * metrics.heightPixels).coerceIn(0f, metrics.heightPixels.toFloat())

            val path = Path().apply {
                moveTo(targetX, targetY)
            }

            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Remote tap completed at ($targetX, $targetY)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Remote tap cancelled at ($targetX, $targetY)")
                }
            }, null)
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
            durationMs: Long = 250L
        ) {
            val service = instance ?: run {
                Log.w(TAG, "ZenithAccessibilityService is not enabled. Cannot perform swipe.")
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

            val metrics = service.resources.displayMetrics
            val x1 = (startX * metrics.widthPixels).coerceIn(0f, metrics.widthPixels.toFloat())
            val y1 = (startY * metrics.heightPixels).coerceIn(0f, metrics.heightPixels.toFloat())
            val x2 = (endX * metrics.widthPixels).coerceIn(0f, metrics.widthPixels.toFloat())
            val y2 = (endY * metrics.heightPixels).coerceIn(0f, metrics.heightPixels.toFloat())

            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }

            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            service.dispatchGesture(gesture, null, null)
            Log.d(TAG, "Remote swipe dispatched from ($x1, $y1) to ($x2, $y2)")
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
                val focusedNode = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    ?: return false

                val arguments = android.os.Bundle().apply {
                    putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                val success = focusedNode.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
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
        // Accessibility events
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
