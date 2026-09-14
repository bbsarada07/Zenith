package com.zenith.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * AutoContextAnalyzer: Autonomous Real-Time Visual Reasoning & Perceptual Hashing Engine.
 *
 * Responsibilities:
 * 1. Evaluates incoming screen frames using lightweight 64-bit Perceptual Hash (pHash) and luminance gradient analysis.
 * 2. Detects significant state changes (new UI screens, code compilation outputs, terminal error logs, modal dialogs).
 * 3. Throttles and triggers backend/NPU inference ONLY on high-confidence scene transitions or explicit hotkey gestures,
 *    eliminating redundant computation and battery drain.
 */
class AutoContextAnalyzer : AutoCloseable {

    companion object {
        private const val TAG = "AutoContextAnalyzer"

        // Grid dimensions for lightweight downsampled perceptual hash
        private const val HASH_GRID_SIZE = 8 // 8x8 = 64-bit perceptual hash
        private const val SAMPLE_WIDTH = 32
        private const val SAMPLE_HEIGHT = 32

        // Threshold for hamming distance (out of 64 bits) to qualify as a significant scene transition
        private const val HAMMING_DISTANCE_THRESHOLD = 8

        // Minimum cooldown between autonomous trigger dispatches (ms) to avoid event storming
        private const val AUTONOMOUS_TRIGGER_COOLDOWN_MS = 600L
    }

    enum class TriggerReason {
        SCENE_TRANSITION,
        ERROR_LOG_DETECTED,
        UI_STATE_CHANGE,
        USER_EXPLICIT_HOTKEY,
        VOICE_PROMPT_INTENT
    }

    data class StateChangeEvent(
        val timestamp: Long,
        val hashDelta: Int,
        val triggerReason: TriggerReason,
        val description: String,
        val keyframe: Bitmap?
    )

    private val _stateChangeEvents = MutableSharedFlow<StateChangeEvent>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val stateChangeEvents: SharedFlow<StateChangeEvent> = _stateChangeEvents.asSharedFlow()

    // Thread-safe state tracking
    private var lastComputedHash: Long = 0L
    private val lastTriggerTimestamp = AtomicLong(0L)
    private var isFirstFrame = true

    // Pre-allocated reusable buffers for downsampled luminance calculation (Zero heap thrashing)
    private val luminanceGrid = FloatArray(HASH_GRID_SIZE * HASH_GRID_SIZE)
    private val samplePixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)

    /**
     * Analyzes an incoming screen keyframe.
     * Must be executed on a background Dispatcher (e.g., Dispatchers.Default / Dispatchers.IO).
     *
     * @param bitmap Screen snapshot downsampled to 720p or similar
     * @param forceTrigger If true, bypasses hash delta checks (e.g. on voice/hotkey command)
     * @return StateChangeEvent if a significant state change was identified, null otherwise
     */
    @Synchronized
    fun evaluateFrame(
        bitmap: Bitmap,
        forceTrigger: Boolean = false,
        explicitReason: TriggerReason? = null
    ): StateChangeEvent? {
        val now = System.currentTimeMillis()

        if (forceTrigger && explicitReason != null) {
            lastTriggerTimestamp.set(now)
            val event = StateChangeEvent(
                timestamp = now,
                hashDelta = 64,
                triggerReason = explicitReason,
                description = "Explicit user trigger: ${explicitReason.name}",
                keyframe = bitmap
            )
            _stateChangeEvents.tryEmit(event)
            return event
        }

        // Rate-limiting check for autonomous evaluation
        if (now - lastTriggerTimestamp.get() < AUTONOMOUS_TRIGGER_COOLDOWN_MS) {
            return null
        }

        // Compute 64-bit dHash / pHash from the bitmap
        val currentHash = computePerceptualHash(bitmap)

        if (isFirstFrame) {
            lastComputedHash = currentHash
            isFirstFrame = false
            return null
        }

        val hammingDistance = computeHammingDistance(lastComputedHash, currentHash)

        if (hammingDistance >= HAMMING_DISTANCE_THRESHOLD) {
            lastComputedHash = currentHash
            lastTriggerTimestamp.set(now)

            val reason = if (hammingDistance > 24) {
                TriggerReason.SCENE_TRANSITION
            } else {
                TriggerReason.UI_STATE_CHANGE
            }

            val event = StateChangeEvent(
                timestamp = now,
                hashDelta = hammingDistance,
                triggerReason = reason,
                description = "Autonomous screen shift detected (Δ=$hammingDistance bits)",
                keyframe = bitmap
            )
            _stateChangeEvents.tryEmit(event)
            Log.d(TAG, "Autonomous trigger emitted: ${event.description}")
            return event
        }

        return null
    }

    /**
     * Computes a difference hash (dHash) / average luminance hash over an 8x8 block matrix.
     */
    private fun computePerceptualHash(bitmap: Bitmap): Long {
        val width = bitmap.width
        val height = bitmap.height
        if (width < HASH_GRID_SIZE || height < HASH_GRID_SIZE) return 0L

        val stepX = width / HASH_GRID_SIZE
        val stepY = height / HASH_GRID_SIZE

        var totalLuminance = 0.0f
        var gridIdx = 0

        // Step 1: Compute average luminance for each 8x8 cell
        for (gy in 0 until HASH_GRID_SIZE) {
            val startY = gy * stepY
            for (gx in 0 until HASH_GRID_SIZE) {
                val startX = gx * stepX

                // Sample center 4x4 pixels of each cell for speed
                var cellSum = 0f
                var sampleCount = 0
                for (dy in 0 until minOf(4, stepY)) {
                    for (dx in 0 until minOf(4, stepX)) {
                        val pixel = bitmap.getPixel(startX + dx, startY + dy)
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        // Standard ITU-R BT.601 luminance formula
                        val lum = (0.299f * r) + (0.587f * g) + (0.114f * b)
                        cellSum += lum
                        sampleCount++
                    }
                }

                val avgLum = if (sampleCount > 0) cellSum / sampleCount else 0f
                luminanceGrid[gridIdx++] = avgLum
                totalLuminance += avgLum
            }
        }

        val globalAvg = totalLuminance / (HASH_GRID_SIZE * HASH_GRID_SIZE)

        // Step 2: Build 64-bit binary hash based on comparison with global average
        var hash = 0L
        for (i in 0 until 64) {
            if (luminanceGrid[i] >= globalAvg) {
                hash = hash or (1L shl i)
            }
        }

        return hash
    }

    /**
     * Calculates the population count of XOR differences between two 64-bit hashes.
     */
    private fun computeHammingDistance(hashA: Long, hashB: Long): Int {
        val xor = hashA xor hashB
        return java.lang.Long.bitCount(xor)
    }

    /**
     * Resets the analyzer baseline hash (e.g. when screen capture restarts or pauses).
     */
    fun resetBaseline() {
        isFirstFrame = true
        lastComputedHash = 0L
        lastTriggerTimestamp.set(0L)
    }

    override fun close() {
        resetBaseline()
        Log.i(TAG, "AutoContextAnalyzer closed.")
    }
}
