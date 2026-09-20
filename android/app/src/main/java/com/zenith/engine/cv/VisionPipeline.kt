package com.zenith.engine.cv

import android.content.Context
import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * VisionPipeline: High-Throughput, Low-Latency (<20ms) Zero-Copy Computer Vision Pipeline.
 *
 * Links Android [ImageReader] frames directly to [NativeVisionEngine] using native DirectBuffers,
 * processing frames asynchronously on a high-priority background thread to ensure fluid screen ingestion
 * without garbage collection pauses or buffer stalls.
 */
class VisionPipeline(
    val context: Context,
    val nativeVisionEngine: NativeVisionEngine = NativeVisionEngine()
) : AutoCloseable {

    companion object {
        private const val TAG = "VisionPipeline"
        private const val TARGET_MAX_LATENCY_MS = 20L
    }

    fun interface OnVisionDetectionListener {
        fun onTargetsDetected(targets: Array<VisionTarget>, latencyMs: Long)
    }

    var detectionListener: OnVisionDetectionListener? = null

    private val isRunning = AtomicBoolean(false)
    private val isProcessingFrame = AtomicBoolean(false)
    private val lastInferenceLatencyMs = AtomicLong(0L)

    // Dedicated single-thread worker for sequential zero-copy frame dispatch
    private var visionExecutor: ExecutorService? = null

    private val _targetsFlow = MutableSharedFlow<Array<VisionTarget>>(extraBufferCapacity = 1)
    val targetsFlow: SharedFlow<Array<VisionTarget>> = _targetsFlow.asSharedFlow()

    /**
     * Starts the asynchronous vision pipeline with a dedicated worker thread.
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            visionExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "ZenithVisionWorkerThread").apply {
                    priority = Thread.MAX_PRIORITY
                }
            }
            Log.i(TAG, "VisionPipeline started with dedicated high-priority worker thread.")
        }
    }

    /**
     * Attaches the pipeline directly to an [ImageReader] instance.
     * Listens for available frames and dispatches direct ByteBuffers to the native CV engine.
     */
    fun attachToImageReader(imageReader: ImageReader) {
        start()
        imageReader.setOnImageAvailableListener({ reader ->
            onImageAvailable(reader)
        }, null)
    }

    /**
     * Ingestion handler executed when [ImageReader] has a new frame ready.
     * Guarantees that acquired [Image] instances are always closed in a finally block.
     */
    fun onImageAvailable(imageReader: ImageReader) {
        if (!isRunning.get()) {
            val img = imageReader.acquireLatestImage() ?: return
            img.close()
            return
        }

        // Drop incoming frame if the worker thread is still busy with the previous inference
        if (!isProcessingFrame.compareAndSet(false, true)) {
            val droppedImage = imageReader.acquireLatestImage() ?: return
            try {
                // Drop frame to prevent buffer backlog
            } finally {
                droppedImage.close()
            }
            return
        }

        val image = imageReader.acquireLatestImage() ?: run {
            isProcessingFrame.set(false)
            return
        }

        val executor = visionExecutor
        if (executor == null || executor.isShutdown) {
            image.close()
            isProcessingFrame.set(false)
            return
        }

        executor.execute {
            val startTime = SystemClock.elapsedRealtime()
            try {
                processDirectImage(image, startTime)
            } catch (e: Throwable) {
                Log.e(TAG, "Error executing native vision pipeline: ${e.message}", e)
            } finally {
                // CRITICAL: Always close the image to release the hardware buffer back to the producer queue
                image.close()
                isProcessingFrame.set(false)
            }
        }
    }

    /**
     * Extracts direct buffer from Image plane and executes native NCNN inference.
     */
    private fun processDirectImage(image: Image, startTime: Long) {
        val planes = image.planes
        if (planes.isEmpty()) return

        val plane = planes[0]
        val buffer: ByteBuffer = plane.buffer

        // Ensure direct buffer access for zero-copy JNI address extraction
        if (!buffer.isDirect) {
            Log.w(TAG, "Image buffer is not direct; zero-copy bypass unavailable.")
        }

        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        // Execute native target detection in C++
        val targets = if (NativeVisionEngine.isAvailable()) {
            nativeVisionEngine.detectTargets(buffer, width, height, pixelStride, rowStride)
        } else {
            emptyArray()
        }

        val latencyMs = SystemClock.elapsedRealtime() - startTime
        lastInferenceLatencyMs.set(latencyMs)

        if (latencyMs > TARGET_MAX_LATENCY_MS) {
            Log.d(TAG, "Vision inference completed in ${latencyMs}ms (${targets.size} targets detected)")
        }

        detectionListener?.onTargetsDetected(targets, latencyMs)
        _targetsFlow.tryEmit(targets)
    }

    fun getLastInferenceLatencyMs(): Long = lastInferenceLatencyMs.get()

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            visionExecutor?.shutdownNow()
            visionExecutor = null
            Log.i(TAG, "VisionPipeline stopped.")
        }
    }

    override fun close() {
        stop()
        nativeVisionEngine.close()
    }
}
