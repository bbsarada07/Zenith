package com.zenith.engine

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * EngineTelemetry: Real-Time Metric Collection & WebSocket Telemetry Pipeline.
 *
 * Runs a 500ms continuous ticker collecting stream FPS, round-trip client latency,
 * ML Kit NPU inference time, JVM heap RAM utilization, and active privacy mask counts.
 */
class EngineTelemetry(
    private val broadcastJson: (JSONObject) -> Unit
) : AutoCloseable {

    companion object {
        private const val TAG = "EngineTelemetry"
        private const val TICK_INTERVAL_MS = 500L
    }

    // Rolling Frame Timestamps for FPS calculation
    private val frameTimestamps = ConcurrentLinkedDeque<Long>()

    // Atomic Metrics
    private val _streamLatencyMs = AtomicLong(0L)
    private val _npuInferenceMs = AtomicLong(0L)
    private val _activePrivacyMasks = AtomicInteger(0)

    var streamLatencyMs: Long
        get() = _streamLatencyMs.get()
        set(value) = _npuInferenceMs.set(value)

    var npuInferenceMs: Long
        get() = _npuInferenceMs.get()
        set(value) = _npuInferenceMs.set(value)

    var activePrivacyMasks: Int
        get() = _activePrivacyMasks.get()
        set(value) = _activePrivacyMasks.set(value)

    private var tickerJob: Job? = null

    /**
     * Starts the 500ms telemetry ticker on the provided [CoroutineScope].
     */
    fun start(scope: CoroutineScope) {
        if (tickerJob != null) return

        tickerJob = scope.launch(Dispatchers.Default) {
            Log.i(TAG, "EngineTelemetry ticker started (500ms interval).")
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                try {
                    val telemetryPacket = collectTelemetryJson()
                    broadcastJson(telemetryPacket)
                } catch (e: Exception) {
                    Log.w(TAG, "Error emitting telemetry packet: ${e.message}")
                }
            }
        }
    }

    /**
     * Records a frame dispatch event to calculate rolling FPS.
     */
    fun recordFrameDispatch() {
        val now = SystemClock.elapsedRealtime()
        frameTimestamps.addLast(now)
        pruneOldTimestamps(now)
    }

    /**
     * Updates round-trip stream latency based on client ping timestamp.
     */
    fun recordPingResponse(clientTimestamp: Long) {
        val now = System.currentTimeMillis()
        val rtt = (now - clientTimestamp).coerceAtLeast(0L)
        _streamLatencyMs.set(rtt)
    }

    fun setLatencyMs(latencyMs: Long) {
        _streamLatencyMs.set(latencyMs)
    }

    fun setInferenceLatencyMs(latencyMs: Long) {
        _npuInferenceMs.set(latencyMs)
    }

    fun getRollingFps(): Int {
        val now = SystemClock.elapsedRealtime()
        pruneOldTimestamps(now)
        return frameTimestamps.size
    }

    private fun pruneOldTimestamps(now: Long) {
        while (frameTimestamps.isNotEmpty()) {
            val oldest = frameTimestamps.peekFirst() ?: break
            if (now - oldest > 1000L) {
                frameTimestamps.pollFirst()
            } else {
                break
            }
        }
    }

    /**
     * Collects current system metrics and returns the structured JSON telemetry payload.
     */
    fun collectTelemetryJson(): JSONObject {
        val runtime = Runtime.getRuntime()
        val heapRamMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val fps = getRollingFps()
        val latency = _streamLatencyMs.get()
        val inference = _npuInferenceMs.get()
        val masks = _activePrivacyMasks.get()

        return JSONObject().apply {
            put("type", "TELEMETRY_UPDATE")
            put("fps", fps)
            put("latencyMs", latency)
            put("inferenceMs", inference)
            put("heapMb", heapRamMb)
            put("activePrivacyMasks", masks)

            // Backwards compatibility keys
            put("timestamp", System.currentTimeMillis())
            put("npuLatencyMs", inference)
            put("ramUsedMb", heapRamMb)
            put("privacy_masks_active", masks)
        }
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        frameTimestamps.clear()
        Log.i(TAG, "EngineTelemetry ticker stopped.")
    }

    override fun close() {
        stop()
    }
}
