package com.zenith.engine.cv

import android.content.res.AssetManager
import android.graphics.RectF
import android.util.Log
import java.nio.ByteBuffer

/**
 * Data structure representing a detected visual target from the neural network / OpenCV pipeline.
 *
 * @param labelId Class index/identifier of the target.
 * @param confidence Model detection confidence score [0.0 to 1.0].
 * @param boundingBox Physical screen pixel bounds of the detected target.
 */
data class VisionTarget(
    val labelId: Int,
    val confidence: Float,
    val boundingBox: RectF
) {
    val centerX: Float
        get() = boundingBox.centerX()

    val centerY: Float
        get() = boundingBox.centerY()

    val width: Float
        get() = boundingBox.width()

    val height: Float
        get() = boundingBox.height()
}

/**
 * NativeVisionEngine: High-Performance JNI Bridge to Tencent NCNN & OpenCV Mobile.
 *
 * Provides sub-20ms hardware-accelerated target detection and ORB feature matching
 * utilizing DirectBuffer zero-copy memory access and pre-allocated static JNI caches.
 */
class NativeVisionEngine : AutoCloseable {

    companion object {
        private const val TAG = "NativeVisionEngine"
        private var isNativeLibraryLoaded = false

        init {
            try {
                System.loadLibrary("zenith_vision")
                isNativeLibraryLoaded = true
                Log.i(TAG, "libzenith_vision.so loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libzenith_vision.so not found or failed to load: ${e.message}. Using fallback execution mode.")
                isNativeLibraryLoaded = false
            }
        }

        fun isAvailable(): Boolean = isNativeLibraryLoaded
    }

    /**
     * Initializes the NCNN model from Android assets.
     */
    external fun initModel(assetManager: AssetManager, modelPath: String, paramPath: String): Boolean

    /**
     * Executes neural network target detection against direct frame buffer with stride compensation.
     */
    external fun detectTargets(
        directFrameBuffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int
    ): Array<VisionTarget>

    /**
     * Executes ORB feature matching and homography transformation on the direct buffer.
     *
     * @param outCoordinates Output float array of size >= 7: [centerX, centerY, left, top, right, bottom, confidence].
     * @return True if match found with confidence >= threshold.
     */
    external fun matchTemplateOrb(
        directFrameBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        templateBytes: ByteArray,
        templateWidth: Int,
        templateHeight: Int,
        outCoordinates: FloatArray
    ): Boolean

    /**
     * Clears internal net instances and memory pools.
     */
    external fun destroy()

    override fun close() {
        if (isNativeLibraryLoaded) {
            try {
                destroy()
            } catch (ignored: Throwable) {}
        }
    }
}
