package com.zenith.engine

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * BitmapFrameBuffer: Memory-Efficient Frame Buffer Pool for Zero-Allocation Screen Capture.
 *
 * Maintains a thread-safe, fixed-capacity pool (default size = 3) of pre-allocated ARGB_8888
 * [Bitmap] objects to eliminate GC allocation spikes, heap fragmentation, and stutter during
 * high-framerate (25-30 FPS) screen capture streaming.
 */
class BitmapFrameBuffer(
    @Volatile var targetWidth: Int = 1080,
    @Volatile var targetHeight: Int = 2400,
    val poolSize: Int = POOL_SIZE
) : AutoCloseable {

    companion object {
        private const val TAG = "BitmapFrameBuffer"
        const val POOL_SIZE = 3
    }

    private val pool = ConcurrentLinkedQueue<Bitmap>()
    private val currentCount = AtomicInteger(0)

    init {
        preallocatePool()
    }

    /**
     * Pre-allocates up to [poolSize] bitmaps in advance.
     */
    private fun preallocatePool() {
        val w = targetWidth.coerceAtLeast(1)
        val h = targetHeight.coerceAtLeast(1)
        while (currentCount.get() < poolSize) {
            try {
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                pool.offer(bitmap)
                currentCount.incrementAndGet()
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM during BitmapFrameBuffer pre-allocation: ${e.message}")
                break
            }
        }
        Log.d(TAG, "BitmapFrameBuffer initialized with ${pool.size} buffers ($w x $h).")
    }

    /**
     * Updates target dimensions and resets the buffer pool if dimensions changed.
     */
    fun configure(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (targetWidth != width || targetHeight != height) {
            Log.i(TAG, "Reconfiguring BitmapFrameBuffer dimensions: ${targetWidth}x${targetHeight} -> ${width}x${height}")
            targetWidth = width
            targetHeight = height
            clear()
            preallocatePool()
        }
    }

    /**
     * Obtains a reusable [Bitmap] matching the current target width and height.
     * If the pool has an available buffer, it is dequeued and returned.
     * Otherwise, a new [Bitmap] is allocated.
     */
    fun obtain(): Bitmap {
        val w = targetWidth.coerceAtLeast(1)
        val h = targetHeight.coerceAtLeast(1)

        while (true) {
            val bitmap = pool.poll() ?: break
            currentCount.decrementAndGet()

            if (!bitmap.isRecycled && bitmap.width == w && bitmap.height == h && bitmap.config == Bitmap.Config.ARGB_8888) {
                return bitmap
            } else if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Obtains a reusable [Bitmap] matching explicit width and height specifications.
     */
    fun obtain(width: Int, height: Int): Bitmap {
        configure(width, height)
        return obtain()
    }

    /**
     * Releases a previously obtained [Bitmap] back into the pool for reuse.
     * If the pool is already at capacity or the bitmap is invalid/recycled,
     * the bitmap is recycled immediately to prevent leaks.
     */
    fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) return

        val matchesConfig = bitmap.width == targetWidth &&
                bitmap.height == targetHeight &&
                bitmap.config == Bitmap.Config.ARGB_8888

        if (!matchesConfig) {
            bitmap.recycle()
            return
        }

        if (currentCount.get() < poolSize) {
            pool.offer(bitmap)
            currentCount.incrementAndGet()
        } else {
            bitmap.recycle()
        }
    }

    /**
     * Clears and recycles all pooled bitmaps.
     */
    fun clear() {
        while (true) {
            val bitmap = pool.poll() ?: break
            currentCount.decrementAndGet()
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        currentCount.set(0)
    }

    override fun close() {
        clear()
    }
}
