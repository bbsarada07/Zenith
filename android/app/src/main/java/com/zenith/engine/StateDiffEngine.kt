package com.zenith.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * StateDiffEngine: Real-Time Perceptual Screen State Differential & Dynamic Throttler.
 *
 * Computes luminance-based perceptual difference matrices between consecutive screen frames
 * downsampled to 32x32 grayscale representations to detect visual state shifts and eliminate
 * redundant NPU/OCR inference cycles on static screens.
 */
object StateDiffEngine {

    private const val TAG = "StateDiffEngine"
    private const val DIFF_GRID_SIZE = 32
    private const val TOTAL_PIXELS = DIFF_GRID_SIZE * DIFF_GRID_SIZE
    private const val DEFAULT_DIFF_THRESHOLD = 0.05f // 5% luminance shift threshold

    /**
     * Computes the perceptual difference percentage [0.0 to 1.0] between two [Bitmap] frames.
     *
     * 1. Downsamples both frames to 32x32 matrices.
     * 2. Extracts ITU-R BT.601 perceptual luminance values: (0.299*R + 0.587*G + 0.114*B).
     * 3. Calculates normalized sum of absolute luminance differences.
     *
     * @param bmp1 First comparison bitmap.
     * @param bmp2 Second comparison bitmap.
     * @return Difference percentage between 0.0 (identical) and 1.0 (completely distinct).
     */
    fun computeFrameDifference(bmp1: Bitmap, bmp2: Bitmap): Float {
        if (bmp1.isRecycled || bmp2.isRecycled) return 1.0f
        if (bmp1 === bmp2) return 0.0f

        val small1 = Bitmap.createScaledBitmap(bmp1, DIFF_GRID_SIZE, DIFF_GRID_SIZE, true)
        val small2 = Bitmap.createScaledBitmap(bmp2, DIFF_GRID_SIZE, DIFF_GRID_SIZE, true)

        val pixels1 = IntArray(TOTAL_PIXELS)
        val pixels2 = IntArray(TOTAL_PIXELS)

        try {
            small1.getPixels(pixels1, 0, DIFF_GRID_SIZE, 0, 0, DIFF_GRID_SIZE, DIFF_GRID_SIZE)
            small2.getPixels(pixels2, 0, DIFF_GRID_SIZE, 0, 0, DIFF_GRID_SIZE, DIFF_GRID_SIZE)

            var accumulatedDiff = 0.0
            for (i in 0 until TOTAL_PIXELS) {
                val c1 = pixels1[i]
                val c2 = pixels2[i]

                val r1 = Color.red(c1)
                val g1 = Color.green(c1)
                val b1 = Color.blue(c1)
                val lum1 = 0.299 * r1 + 0.587 * g1 + 0.114 * b1

                val r2 = Color.red(c2)
                val g2 = Color.green(c2)
                val b2 = Color.blue(c2)
                val lum2 = 0.299 * r2 + 0.587 * g2 + 0.114 * b2

                accumulatedDiff += Math.abs(lum1 - lum2)
            }

            val maxPossibleDiff = TOTAL_PIXELS * 255.0
            val normalizedDiff = (accumulatedDiff / maxPossibleDiff).toFloat().coerceIn(0f, 1f)
            return normalizedDiff
        } finally {
            if (small1 != bmp1 && !small1.isRecycled) small1.recycle()
            if (small2 != bmp2 && !small2.isRecycled) small2.recycle()
        }
    }

    /**
     * Determines whether the live viewport has undergone a meaningful visual state transition.
     *
     * @param previous Previous screen capture frame, or null if first invocation.
     * @param current Live incoming frame.
     * @param threshold Minimum differential percentage required to trigger change flag (default 5%).
     * @return True if difference exceeds threshold or if previous frame is null.
     */
    fun hasScreenChanged(previous: Bitmap?, current: Bitmap, threshold: Float = DEFAULT_DIFF_THRESHOLD): Boolean {
        if (previous == null || previous.isRecycled) return true
        if (current.isRecycled) return false

        val diff = computeFrameDifference(previous, current)
        val changed = diff >= threshold
        if (changed) {
            Log.d(TAG, "Screen state shift detected: ${(diff * 100).toInt()}% (Threshold: ${(threshold * 100).toInt()}%)")
        }
        return changed
    }
}
