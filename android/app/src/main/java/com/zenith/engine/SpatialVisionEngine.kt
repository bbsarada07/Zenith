package com.zenith.engine

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * SpatialVisionEngine: Low-Latency On-Device ML Kit OCR and Spatial Geometry Extraction.
 *
 * Implements real-time text recognition on Android screen frames, normalizing pixel coordinates
 * into viewport spatial ratios [0.0, 1.0] and computing centroid targets for spatial tele-operation.
 */
class SpatialVisionEngine : AutoCloseable {

    companion object {
        private const val TAG = "SpatialVisionEngine"
    }

    data class NormalizedRect(
        val normLeft: Float,
        val normTop: Float,
        val normRight: Float,
        val normBottom: Float
    )

    data class Centroid(
        val x: Float,
        val y: Float
    )

    data class RecognizedBlock(
        val text: String,
        val bounds: NormalizedRect,
        val centroid: Centroid,
        val rawRect: Rect,
        var isSensitive: Boolean = false
    )

    // Legacy OcrBlock / OcrAnalysisResult for backwards compatibility
    data class OcrBlock(
        val text: String,
        val normBounds: FloatArray,
        val isSensitive: Boolean,
        val category: String? = null
    )

    data class OcrAnalysisResult(
        val fullText: String,
        val blockCount: Int,
        val blocks: List<OcrBlock>,
        val autoMasksAdded: Int,
        val frameWidth: Int,
        val frameHeight: Int
    ) {
        fun toJson(): JSONObject {
            val json = JSONObject()
            json.put("type", "ocr_results")
            json.put("fullText", fullText)
            json.put("blockCount", blockCount)
            json.put("autoMasksAdded", autoMasksAdded)
            json.put("frameWidth", frameWidth)
            json.put("frameHeight", frameHeight)

            val blocksArray = JSONArray()
            for (b in blocks) {
                val bObj = JSONObject().apply {
                    put("text", b.text)
                    put("is_sensitive", b.isSensitive)
                    if (b.category != null) put("category", b.category)

                    val boundsArr = JSONArray().apply {
                        put((b.normBounds[0] * 1000).toInt() / 1000.0)
                        put((b.normBounds[1] * 1000).toInt() / 1000.0)
                        put((b.normBounds[2] * 1000).toInt() / 1000.0)
                        put((b.normBounds[3] * 1000).toInt() / 1000.0)
                    }
                    put("bounds", boundsArr)
                }
                blocksArray.put(bObj)
            }
            json.put("blocks", blocksArray)
            return json
        }
    }

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Processes a single screen capture Bitmap frame with Google ML Kit Text Recognition.
     * Computes normalized bounding coordinates [0.0, 1.0], centroid targets, and execution latency.
     *
     * @param bitmap The live screen capture frame.
     * @param onComplete Callback delivering the list of recognized spatial blocks and inference latency in ms.
     */
    fun processFrame(bitmap: Bitmap, onComplete: (List<RecognizedBlock>, Long) -> Unit) {
        val startTime = SystemClock.elapsedRealtime()
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val npuInferenceMs = SystemClock.elapsedRealtime() - startTime
                    val recognizedBlocks = mutableListOf<RecognizedBlock>()
                    val widthF = bitmap.width.toFloat().coerceAtLeast(1f)
                    val heightF = bitmap.height.toFloat().coerceAtLeast(1f)

                    for (block in visionText.textBlocks) {
                        val text = block.text
                        val rawRect = block.boundingBox ?: Rect(0, 0, 0, 0)

                        val normLeft = (rawRect.left / widthF).coerceIn(0f, 1f)
                        val normTop = (rawRect.top / heightF).coerceIn(0f, 1f)
                        val normRight = (rawRect.right / widthF).coerceIn(0f, 1f)
                        val normBottom = (rawRect.bottom / heightF).coerceIn(0f, 1f)

                        val bounds = NormalizedRect(
                            normLeft = normLeft,
                            normTop = normTop,
                            normRight = normRight,
                            normBottom = normBottom
                        )

                        val centroid = Centroid(
                            x = ((normLeft + normRight) / 2f).coerceIn(0f, 1f),
                            y = ((normTop + normBottom) / 2f).coerceIn(0f, 1f)
                        )

                        recognizedBlocks.add(
                            RecognizedBlock(
                                text = text,
                                bounds = bounds,
                                centroid = centroid,
                                rawRect = rawRect,
                                isSensitive = false
                            )
                        )
                    }

                    Log.d(TAG, "processFrame completed: ${recognizedBlocks.size} blocks in ${npuInferenceMs}ms")
                    onComplete(recognizedBlocks, npuInferenceMs)
                }
                .addOnFailureListener { e ->
                    val npuInferenceMs = SystemClock.elapsedRealtime() - startTime
                    Log.e(TAG, "ML Kit OCR failed: ${e.message}", e)
                    onComplete(emptyList(), npuInferenceMs)
                }
        } catch (e: Exception) {
            val npuInferenceMs = SystemClock.elapsedRealtime() - startTime
            Log.e(TAG, "Error executing processFrame: ${e.message}", e)
            onComplete(emptyList(), npuInferenceMs)
        }
    }

    /**
     * Coroutine suspend wrapper for [processFrame].
     */
    suspend fun processFrameSuspend(bitmap: Bitmap): Pair<List<RecognizedBlock>, Long> =
        suspendCancellableCoroutine { continuation ->
            processFrame(bitmap) { blocks, latencyMs ->
                continuation.resume(Pair(blocks, latencyMs))
            }
        }

    /**
     * Legacy analyzeFrame method for backward compatibility with voice and context analysis.
     */
    suspend fun analyzeFrame(
        bitmap: Bitmap,
        screenWidth: Int = bitmap.width,
        screenHeight: Int = bitmap.height,
        autoRegisterPrivacyMasks: Boolean = true
    ): OcrAnalysisResult {
        val (blocks, _) = processFrameSuspend(bitmap)
        val legacyBlocks = blocks.map { b ->
            OcrBlock(
                text = b.text,
                normBounds = floatArrayOf(
                    b.bounds.normLeft,
                    b.bounds.normTop,
                    b.bounds.normRight,
                    b.bounds.normBottom
                ),
                isSensitive = b.isSensitive
            )
        }

        return OcrAnalysisResult(
            fullText = blocks.joinToString("\n") { it.text },
            blockCount = blocks.size,
            blocks = legacyBlocks,
            autoMasksAdded = 0,
            frameWidth = bitmap.width,
            frameHeight = bitmap.height
        )
    }

    override fun close() {
        try {
            textRecognizer.close()
        } catch (ignored: Exception) {}
    }
}
