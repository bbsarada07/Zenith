package com.zenith.engine.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.util.Log
import com.zenith.engine.ZenithAccessibilityService
import com.zenith.engine.cv.ViewportAdapter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * AutonomousGestureExecutor: Ultra-Low Latency Gesture & Multi-Touch Dispatcher.
 *
 * Wraps Android's native [AccessibilityService.dispatchGesture] with Kotlin Coroutines
 * ([suspendCancellableCoroutine]), translating and clamping physical coordinates via [ViewportAdapter]
 * and ensuring that gestures are fully confirmed before downstream automation plans resume.
 */
class AutonomousGestureExecutor(
    private val context: Context? = null,
    private val viewportAdapter: ViewportAdapter = ViewportAdapter.getDefault(context)
) {

    companion object {
        private const val TAG = "AutonomousGestureExec"
        private const val DEFAULT_TAP_DURATION_MS = 50L
        private const val DEFAULT_SWIPE_DURATION_MS = 250L

        @Volatile
        private var defaultInstance: AutonomousGestureExecutor? = null

        fun getInstance(context: Context? = null): AutonomousGestureExecutor {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: AutonomousGestureExecutor(context).also { defaultInstance = it }
            }
        }
    }

    /**
     * Suspending tap gesture awaiting hardware event confirmation.
     */
    suspend fun injectTap(x: Float, y: Float, durationMs: Long = DEFAULT_TAP_DURATION_MS): Boolean =
        suspendCancellableCoroutine { continuation ->
            injectTap(x, y, durationMs) { success ->
                if (continuation.isActive) {
                    continuation.resume(success)
                }
            }
        }

    /**
     * Callback-based tap gesture injection.
     */
    fun injectTap(x: Float, y: Float, durationMs: Long = DEFAULT_TAP_DURATION_MS, callback: (Boolean) -> Unit) {
        val service = ZenithAccessibilityService.instance ?: run {
            Log.w(TAG, "ZenithAccessibilityService unavailable for tap injection.")
            callback(false)
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback(false)
            return
        }

        val clampedPoint = viewportAdapter.clampToScreen(x, y)
        val path = Path().apply {
            moveTo(clampedPoint.x, clampedPoint.y)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(10L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap gesture completed at (${clampedPoint.x}, ${clampedPoint.y})")
                callback(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap gesture cancelled at (${clampedPoint.x}, ${clampedPoint.y})")
                callback(false)
            }
        }, null)
    }

    /**
     * Suspending swipe gesture awaiting hardware event confirmation.
     */
    suspend fun injectSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            injectSwipe(startX, startY, endX, endY, durationMs) { success ->
                if (continuation.isActive) {
                    continuation.resume(success)
                }
            }
        }

    /**
     * Callback-based swipe gesture injection.
     */
    fun injectSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS,
        callback: (Boolean) -> Unit
    ) {
        val service = ZenithAccessibilityService.instance ?: run {
            Log.w(TAG, "ZenithAccessibilityService unavailable for swipe injection.")
            callback(false)
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback(false)
            return
        }

        val p1 = viewportAdapter.clampToScreen(startX, startY)
        val p2 = viewportAdapter.clampToScreen(endX, endY)

        val path = Path().apply {
            moveTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe completed from (${p1.x}, ${p1.y}) to (${p2.x}, ${p2.y})")
                callback(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled from (${p1.x}, ${p1.y}) to (${p2.x}, ${p2.y})")
                callback(false)
            }
        }, null)
    }

    /**
     * Suspending multi-touch gesture injection (e.g. pinch, dual-tap).
     */
    suspend fun injectMultiTouch(points: List<PointF>, durationMs: Long = DEFAULT_TAP_DURATION_MS): Boolean =
        suspendCancellableCoroutine { continuation ->
            injectMultiTouch(points, durationMs) { success ->
                if (continuation.isActive) {
                    continuation.resume(success)
                }
            }
        }

    /**
     * Callback-based simultaneous multi-touch gesture injection.
     */
    fun injectMultiTouch(
        points: List<PointF>,
        durationMs: Long = DEFAULT_TAP_DURATION_MS,
        callback: (Boolean) -> Unit
    ) {
        val service = ZenithAccessibilityService.instance ?: run {
            callback(false)
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || points.isEmpty()) {
            callback(false)
            return
        }

        val builder = GestureDescription.Builder()
        for (pt in points) {
            val clamped = viewportAdapter.clampToScreen(pt.x, pt.y)
            val path = Path().apply {
                moveTo(clamped.x, clamped.y)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(10L)))
        }

        service.dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Multi-touch completed (${points.size} touch points)")
                callback(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Multi-touch cancelled")
                callback(false)
            }
        }, null)
    }
}
