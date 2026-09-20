package com.zenith.engine

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * SemanticUIResolver: Intent-Based Semantic UI Grounding & Self-Healing Action Engine.
 *
 * Implements:
 * 1. Semantic Grounding: Maps natural intent queries (e.g. CLICK_TEXT("Settings"),
 *    CLICK_NEAR_TEXT("Submit", direction="RIGHT")) directly to UI coordinates.
 * 2. Fuzzy Levenshtein Matching: Tolerates OCR imperfections (distance <= 2).
 * 3. Directional Anchor Grounding: Computes offset coordinates for directional queries.
 * 4. Self-Healing Retry Loop: Verifies state transition 400ms post-tap and retries if target shifted.
 * 5. Telemetry Reporting: Returns comprehensive JSON payloads with normalized bounding boxes and confidence.
 */
class SemanticUIResolver(
    private val visionEngine: SpatialVisionEngine,
    private val frameProvider: () -> Bitmap?
) {

    companion object {
        private const val TAG = "SemanticUIResolver"
        private const val MAX_LEVENSHTEIN_DISTANCE = 2
        private const val POST_TAP_VERIFY_DELAY_MS = 400L
    }

    data class ResolutionResult(
        val success: Boolean,
        val target: String,
        val matchedText: String? = null,
        val bounds: FloatArray = floatArrayOf(0f, 0f, 0f, 0f), // [normLeft, normTop, normRight, normBottom]
        val clickX: Float = 0.5f,
        val clickY: Float = 0.5f,
        val confidence: Double = 0.0,
        val latencyMs: Long = 0L,
        val errorMessage: String? = null,
        val retryCount: Int = 0
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("status", if (success) "SUCCESS" else "FAILED")
            put("target", target)
            put("matchedText", matchedText ?: "")
            put("bounds", JSONArray().apply {
                for (b in bounds) put(b.toDouble())
            })
            put("clickCoords", JSONObject().apply {
                put("x", clickX.toDouble())
                put("y", clickY.toDouble())
            })
            put("confidence", confidence)
            put("latencyMs", latencyMs)
            put("retryCount", retryCount)
            if (errorMessage != null) {
                put("error", errorMessage)
            }
        }
    }

    /**
     * Resolves and clicks text on the active screen with optional directional offset and self-healing retry.
     */
    suspend fun resolveAndExecute(
        targetQuery: String,
        direction: String? = null,
        offsetPercent: Float = 0.15f,
        enableSelfHealing: Boolean = true,
        onRetryAlert: ((JSONObject) -> Unit)? = null
    ): ResolutionResult = withContext(Dispatchers.Default) {
        val startTime = SystemClock.elapsedRealtime()
        val bitmap = frameProvider() ?: return@withContext ResolutionResult(
            success = false,
            target = targetQuery,
            latencyMs = SystemClock.elapsedRealtime() - startTime,
            errorMessage = "No active screen capture frame available."
        )

        val (blocks, ocrLatency) = visionEngine.processFrame(bitmap)
        val match = findBestMatch(targetQuery, blocks)

        if (match == null) {
            return@withContext ResolutionResult(
                success = false,
                target = targetQuery,
                latencyMs = SystemClock.elapsedRealtime() - startTime,
                errorMessage = "Target '$targetQuery' not found in active viewport."
            )
        }

        // Calculate click coordinates
        val (clickX, clickY) = calculateCoordinates(match.first, direction, offsetPercent)
        val normBounds = floatArrayOf(
            match.first.bounds.normLeft,
            match.first.bounds.normTop,
            match.first.bounds.normRight,
            match.first.bounds.normBottom
        )

        // Dispatch tap via Accessibility
        ZenithAccessibilityService.performClickAt(clickX, clickY)
        Log.i(TAG, "Dispatched tap for '$targetQuery' at ($clickX, $clickY) [Match: '${match.first.text}', Score: ${match.second}]")

        var retryCount = 0

        // Self-Healing Verification Loop
        if (enableSelfHealing) {
            delay(POST_TAP_VERIFY_DELAY_MS)
            val verifyBitmap = frameProvider()
            if (verifyBitmap != null) {
                val hasChanged = StateDiffEngine.hasScreenChanged(bitmap, verifyBitmap)
                if (!hasChanged) {
                    Log.w(TAG, "Self-Healing: Screen unchanged after tap. Scanning for shifted element...")
                    val (verifyBlocks, _) = visionEngine.processFrame(verifyBitmap)
                    val retryMatch = findBestMatch(targetQuery, verifyBlocks)
                    if (retryMatch != null) {
                        val (retryX, retryY) = calculateCoordinates(retryMatch.first, direction, offsetPercent)
                        ZenithAccessibilityService.performClickAt(retryX, retryY)
                        retryCount++
                        Log.i(TAG, "Self-Healing: Secondary offset tap dispatched at ($retryX, $retryY)")

                        val alertJson = JSONObject().apply {
                            put("type", "RETRY_ALERT")
                            put("target", targetQuery)
                            put("retryCount", retryCount)
                            put("message", "Self-Healing triggered secondary tap for shifted target '$targetQuery'")
                            put("timestamp", System.currentTimeMillis())
                        }
                        onRetryAlert?.invoke(alertJson)
                    }
                }
            }
        }

        val totalLatency = SystemClock.elapsedRealtime() - startTime
        ResolutionResult(
            success = true,
            target = targetQuery,
            matchedText = match.first.text,
            bounds = normBounds,
            clickX = clickX,
            clickY = clickY,
            confidence = match.second,
            latencyMs = totalLatency,
            retryCount = retryCount
        )
    }

    /**
     * Calculates final target percentage coordinates based on bounding box and directional offset.
     */
    private fun calculateCoordinates(
        block: SpatialVisionEngine.RecognizedBlock,
        direction: String?,
        offsetPercent: Float
    ): Pair<Float, Float> {
        val centerX = block.centroid.x
        val centerY = block.centroid.y
        val width = block.bounds.normRight - block.bounds.normLeft
        val height = block.bounds.normBottom - block.bounds.normTop

        return when (direction?.uppercase()) {
            "RIGHT" -> Pair(
                (block.bounds.normRight + width * 0.5f + offsetPercent).coerceIn(0.01f, 0.99f),
                centerY
            )
            "LEFT" -> Pair(
                (block.bounds.normLeft - width * 0.5f - offsetPercent).coerceIn(0.01f, 0.99f),
                centerY
            )
            "ABOVE", "TOP" -> Pair(
                centerX,
                (block.bounds.normTop - height * 0.5f - offsetPercent).coerceIn(0.01f, 0.99f)
            )
            "BELOW", "BOTTOM" -> Pair(
                centerX,
                (block.bounds.normBottom + height * 0.5f + offsetPercent).coerceIn(0.01f, 0.99f)
            )
            else -> Pair(centerX.coerceIn(0.01f, 0.99f), centerY.coerceIn(0.01f, 0.99f))
        }
    }

    /**
     * Finds the best matching OCR block using normalized fuzzy Levenshtein distance.
     */
    fun findBestMatch(
        targetQuery: String,
        blocks: List<SpatialVisionEngine.RecognizedBlock>
    ): Pair<SpatialVisionEngine.RecognizedBlock, Double>? {
        val cleanQuery = targetQuery.trim().lowercase()
        if (cleanQuery.isEmpty() || blocks.isEmpty()) return null

        var bestBlock: SpatialVisionEngine.RecognizedBlock? = null
        var highestScore = 0.0

        for (block in blocks) {
            val blockText = block.text.trim().lowercase()

            // 1. Exact Match
            if (blockText == cleanQuery) {
                return Pair(block, 1.0)
            }

            // 2. Substring Match
            if (blockText.contains(cleanQuery)) {
                val score = 0.90 + (cleanQuery.length.toDouble() / blockText.length.toDouble()) * 0.08
                if (score > highestScore) {
                    highestScore = score
                    bestBlock = block
                }
                continue
            }

            // 3. Word-by-Word Fuzzy Match
            val words = blockText.split("\\s+".toRegex())
            for (word in words) {
                if (word == cleanQuery) {
                    val score = 0.95
                    if (score > highestScore) {
                        highestScore = score
                        bestBlock = block
                    }
                    break
                }

                val dist = levenshteinDistance(cleanQuery, word)
                if (dist <= MAX_LEVENSHTEIN_DISTANCE) {
                    val maxLen = maxOf(cleanQuery.length, word.length)
                    val score = 1.0 - (dist.toDouble() / maxLen.toDouble())
                    if (score > 0.70 && score > highestScore) {
                        highestScore = score
                        bestBlock = block
                    }
                }
            }

            // 4. Full String Levenshtein Match
            val fullDist = levenshteinDistance(cleanQuery, blockText)
            if (fullDist <= MAX_LEVENSHTEIN_DISTANCE) {
                val maxLen = maxOf(cleanQuery.length, blockText.length)
                val score = 1.0 - (fullDist.toDouble() / maxLen.toDouble())
                if (score > 0.70 && score > highestScore) {
                    highestScore = score
                    bestBlock = block
                }
            }
        }

        return if (bestBlock != null && highestScore >= 0.70) {
            Pair(bestBlock, highestScore)
        } else {
            null
        }
    }

    /**
     * Standard Dynamic Programming Levenshtein Distance.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1,
                    min(
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + cost
                    )
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
