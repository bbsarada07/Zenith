package com.zenith.engine.cv

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * ViewportAdapter: High-Precision Coordinate Mapping, Display Cutout & Resolution Transformation Adapter.
 *
 * Translates between normalized viewport coordinate ratios [0.0, 1.0], virtual display capture streams,
 * and physical display pixels, accounting for device orientation, letterboxing, status bar insets,
 * and camera cutouts.
 */
class ViewportAdapter(
    var physicalWidth: Int = 1080,
    var physicalHeight: Int = 2400,
    var captureWidth: Int = 1080,
    var captureHeight: Int = 2400,
    var topInset: Int = 0,
    var bottomInset: Int = 0
) {

    companion object {
        @Volatile
        private var defaultInstance: ViewportAdapter? = null

        fun getDefault(context: Context? = null): ViewportAdapter {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: ViewportAdapter().also { adapter ->
                    if (context != null) {
                        adapter.updateFromContext(context)
                    }
                    defaultInstance = adapter
                }
            }
        }
    }

    /**
     * Updates viewport dimensions dynamically from the active window metrics.
     */
    fun updateFromContext(context: Context) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        this.physicalWidth = metrics.widthPixels
        this.physicalHeight = metrics.heightPixels
    }

    /**
     * Updates virtual stream capture dimensions.
     */
    fun updateCaptureDimensions(width: Int, height: Int) {
        this.captureWidth = width.coerceAtLeast(1)
        this.captureHeight = height.coerceAtLeast(1)
    }

    /**
     * Maps normalized coordinates [0.0 to 1.0] into physical device pixel coordinates.
     */
    fun toPhysicalCoordinates(normX: Float, normY: Float): PointF {
        val px = (normX.coerceIn(0f, 1f) * physicalWidth)
        val py = (normY.coerceIn(0f, 1f) * (physicalHeight - topInset - bottomInset)) + topInset
        return PointF(px, py)
    }

    /**
     * Maps physical pixel coordinates to normalized screen ratios [0.0 to 1.0].
     */
    fun toNormalizedCoordinates(pixelX: Float, pixelY: Float): PointF {
        val normX = (pixelX / physicalWidth.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
        val usableHeight = (physicalHeight - topInset - bottomInset).toFloat().coerceAtLeast(1f)
        val normY = ((pixelY - topInset) / usableHeight).coerceIn(0f, 1f)
        return PointF(normX, normY)
    }

    /**
     * Transforms a capture frame rectangle to physical screen pixel coordinates.
     */
    fun mapCaptureRectToPhysical(captureRect: RectF): RectF {
        val scaleX = physicalWidth.toFloat() / captureWidth.toFloat().coerceAtLeast(1f)
        val scaleY = physicalHeight.toFloat() / captureHeight.toFloat().coerceAtLeast(1f)

        return RectF(
            captureRect.left * scaleX,
            (captureRect.top * scaleY) + topInset,
            captureRect.right * scaleX,
            (captureRect.bottom * scaleY) + topInset
        )
    }

    /**
     * Clamps physical coordinates within safe screen margins.
     */
    fun clampToScreen(x: Float, y: Float, margin: Float = 0f): PointF {
        val clampedX = x.coerceIn(margin, physicalWidth.toFloat() - margin)
        val clampedY = y.coerceIn(margin + topInset, physicalHeight.toFloat() - bottomInset - margin)
        return PointF(clampedX, clampedY)
    }
}
