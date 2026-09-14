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
 * and injects native taps and gestures across the Android OS.
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
                Log.w(TAG, "ZenithAccessibilityService not enabled or instance is null. Cannot perform tap.")
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

        /**
         * Dispatches a native swipe gesture between normalized screen coordinates.
         */
        fun performSwipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long = 200L
        ) {
            val service = instance ?: return
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
        // Event processing for window and text change notifications if needed
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
