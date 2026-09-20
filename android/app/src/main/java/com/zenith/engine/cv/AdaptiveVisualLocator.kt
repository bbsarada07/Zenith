package com.zenith.engine.cv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import android.media.Image
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * AdaptiveVisualLocator: Autonomous ORB Feature Matcher & Fallback Visual Locator.
 *
 * Activated automatically when Android [AccessibilityNodeInfo] hierarchies fail, return null,
 * or are obscured inside game engines (Unity, Unreal) or canvas-rendered surfaces.
 * Utilizes ORB keypoints, Lowe's Ratio Test ($d_1 / d_2 < 0.75$), and RANSAC Homography.
 */
class AdaptiveVisualLocator(
    val context: Context,
    val nativeVisionEngine: NativeVisionEngine = NativeVisionEngine(),
    val viewportAdapter: ViewportAdapter = ViewportAdapter.getDefault(context)
) {

    companion object {
        private const val TAG = "AdaptiveVisualLocator"
        private const val TEMPLATES_ASSET_DIR = "templates"
    }

    data class VisualMatchResult(
        val templateName: String,
        val found: Boolean,
        val screenX: Float,
        val screenY: Float,
        val boundingBox: RectF,
        val confidence: Float
    )

    // In-memory cache of pre-decoded template byte arrays (RGBA)
    private val templateCache = ConcurrentHashMap<String, TemplateData>()

    private data class TemplateData(
        val name: String,
        val width: Int,
        val height: Int,
        val rgbaBytes: ByteArray
    )

    /**
     * Preloads template images from assets/templates/ into native memory buffers.
     */
    fun preloadTemplate(templateName: String): Boolean {
        if (templateCache.containsKey(templateName)) return true

        try {
            val assetPath = "$TEMPLATES_ASSET_DIR/$templateName"
            val inputStream: InputStream = context.assets.open(assetPath)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (bitmap != null) {
                // Extract RGBA raw bytes
                val buffer = ByteBuffer.allocate(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(buffer)
                val rawBytes = buffer.array()

                templateCache[templateName] = TemplateData(
                    name = templateName,
                    width = bitmap.width,
                    height = bitmap.height,
                    rgbaBytes = rawBytes
                )
                Log.i(TAG, "Template '$templateName' cached (${bitmap.width}x${bitmap.height})")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to preload template '$templateName': ${e.message}")
        }
        return false
    }

    /**
     * Registers a template dynamically from a [Bitmap].
     */
    fun registerTemplate(name: String, bitmap: Bitmap) {
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        templateCache[name] = TemplateData(
            name = name,
            width = bitmap.width,
            height = bitmap.height,
            rgbaBytes = buffer.array()
        )
    }

    /**
     * Locates a registered template against the live [Image] frame buffer.
     * Maps the resulting coordinates back to physical device pixels via [ViewportAdapter].
     */
    fun locateTemplateInImage(image: Image, templateName: String): VisualMatchResult {
        var template = templateCache[templateName]
        if (template == null) {
            if (preloadTemplate(templateName)) {
                template = templateCache[templateName]
            }
        }

        if (template == null) {
            Log.e(TAG, "Template '$templateName' is not registered or found in assets.")
            return VisualMatchResult(templateName, false, 0f, 0f, RectF(), 0f)
        }

        val planes = image.planes
        if (planes.isEmpty()) {
            return VisualMatchResult(templateName, false, 0f, 0f, RectF(), 0f)
        }

        val plane = planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride

        val outCoordinates = FloatArray(7) // [centerX, centerY, left, top, right, bottom, confidence]

        val matched = if (NativeVisionEngine.isAvailable()) {
            nativeVisionEngine.matchTemplateOrb(
                directFrameBuffer = buffer,
                width = width,
                height = height,
                rowStride = rowStride,
                templateBytes = template.rgbaBytes,
                templateWidth = template.width,
                templateHeight = template.height,
                outCoordinates = outCoordinates
            )
        } else {
            false
        }

        return if (matched) {
            val captureCenterX = outCoordinates[0]
            val captureCenterY = outCoordinates[1]
            val captureLeft = outCoordinates[2]
            val captureTop = outCoordinates[3]
            val captureRight = outCoordinates[4]
            val captureBottom = outCoordinates[5]
            val confidence = outCoordinates[6]

            val captureRect = RectF(captureLeft, captureTop, captureRight, captureBottom)
            val physicalRect = viewportAdapter.mapCaptureRectToPhysical(captureRect)

            val physicalPoint = viewportAdapter.toPhysicalCoordinates(
                captureCenterX / width.toFloat(),
                captureCenterY / height.toFloat()
            )

            Log.i(
                TAG,
                "Visual match found for '$templateName' (Confidence: ${(confidence * 100).toInt()}%) -> Physical (${physicalPoint.x}, ${physicalPoint.y})"
            )

            VisualMatchResult(
                templateName = templateName,
                found = true,
                screenX = physicalPoint.x,
                screenY = physicalPoint.y,
                boundingBox = physicalRect,
                confidence = confidence
            )
        } else {
            VisualMatchResult(templateName, false, 0f, 0f, RectF(), 0f)
        }
    }

    /**
     * Fallback finder on a [Bitmap] frame.
     */
    fun locateTemplateInBitmap(bitmap: Bitmap, templateName: String): VisualMatchResult {
        var template = templateCache[templateName]
        if (template == null) {
            preloadTemplate(templateName)
            template = templateCache[templateName]
        }

        if (template == null) {
            return VisualMatchResult(templateName, false, 0f, 0f, RectF(), 0f)
        }

        val byteBuffer = ByteBuffer.allocateDirect(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(byteBuffer)
        byteBuffer.rewind()

        val outCoordinates = FloatArray(7)
        val matched = if (NativeVisionEngine.isAvailable()) {
            nativeVisionEngine.matchTemplateOrb(
                directFrameBuffer = byteBuffer,
                width = bitmap.width,
                height = bitmap.height,
                rowStride = bitmap.rowBytes,
                templateBytes = template.rgbaBytes,
                templateWidth = template.width,
                templateHeight = template.height,
                outCoordinates = outCoordinates
            )
        } else {
            false
        }

        return if (matched) {
            val physicalRect = viewportAdapter.mapCaptureRectToPhysical(
                RectF(outCoordinates[2], outCoordinates[3], outCoordinates[4], outCoordinates[5])
            )
            val physicalPoint = PointF(outCoordinates[0], outCoordinates[1])

            VisualMatchResult(
                templateName = templateName,
                found = true,
                screenX = physicalPoint.x,
                screenY = physicalPoint.y,
                boundingBox = physicalRect,
                confidence = outCoordinates[6]
            )
        } else {
            VisualMatchResult(templateName, false, 0f, 0f, RectF(), 0f)
        }
    }
}
