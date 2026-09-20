package com.zenith.engine

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/**
 * SpatialVisionEngine: Low-Latency On-Device ML Kit OCR and Spatial Geometry Extraction.
 *
 * Implements real-time text recognition on Android screen frames, normalizing pixel coordinates
 * into viewport spatial ratios [0.0, 1.0] and computing centroid targets for spatial tele-operation,
 * element classification, and zero-trust privacy detection.
 */
class SpatialVisionEngine : AutoCloseable {

    companion object {
        private const val TAG = "SpatialVisionEngine"

        private val CREDIT_CARD_REGEX = Regex("""\b(?:\d[ -]*?){13,16}\b""")
        private val OTP_PIN_REGEX = Regex("""\b\d{4,6}\b""")
        private val SENSITIVE_KEYWORDS = listOf(
            "otp", "code", "pin", "password", "cvv", "cvc", "secret", "auth", "verification"
        )
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

    enum class UIElementType {
        BUTTON,
        INPUT_FIELD,
        SENSITIVE_TEXT,
        GENERAL_LABEL
    }

    data class RecognizedBlock(
        val id: String,
        val text: String,
        val bounds: NormalizedRect,
        val centroid: Centroid,
        val rawRect: Rect,
        val elementType: UIElementType,
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
     * Coroutine suspend wrapper bridging ML Kit's async Task API with Kotlin Coroutines.
     * Processes a single screen capture [Bitmap] frame with Google ML Kit Text Recognition.
     * Computes normalized bounding ratios [0.0, 1.0], centroid targets, element classifications,
     * and total inference execution latency in ms.
     *
     * @param bitmap The live screen capture frame.
     * @return Pair containing the structured [List<RecognizedBlock>] and total inference duration in ms.
     */
    suspend fun processFrame(bitmap: Bitmap): Pair<List<RecognizedBlock>, Long> =
        suspendCancellableCoroutine { continuation ->
            processFrame(bitmap) { blocks, npuInferenceMs ->
                if (continuation.isActive) {
                    continuation.resume(Pair(blocks, npuInferenceMs))
                }
            }
        }

    /**
     * Callback-based execution of Google ML Kit Text Recognition on a [Bitmap].
     *
     * @param bitmap The screen capture frame.
     * @param onComplete Callback invoked with recognized spatial blocks and inference latency in ms.
     */
    fun processFrame(bitmap: Bitmap, onComplete: (List<RecognizedBlock>, Long) -> Unit) {
        val startTime = System.currentTimeMillis()
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val npuInferenceMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    val rawBlocks = visionText.textBlocks
                    val fullScreenText = visionText.text.lowercase()

                    val widthF = bitmap.width.toFloat().coerceAtLeast(1f)
                    val heightF = bitmap.height.toFloat().coerceAtLeast(1f)
                    val recognizedBlocks = ArrayList<RecognizedBlock>(rawBlocks.size)

                    for (index in rawBlocks.indices) {
                        val block = rawBlocks[index]
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

                        // Heuristic Classification
                        val lowerText = text.lowercase()
                        val isCreditCard = CREDIT_CARD_REGEX.containsMatchIn(text)
                        val hasSensitiveKeyword = SENSITIVE_KEYWORDS.any { lowerText.contains(it) } ||
                                SENSITIVE_KEYWORDS.any { fullScreenText.contains(it) }
                        val isOtpOrPin = OTP_PIN_REGEX.containsMatchIn(text) && hasSensitiveKeyword

                        val isSensitive = isCreditCard || isOtpOrPin
                        val elementType = when {
                            isSensitive -> UIElementType.SENSITIVE_TEXT
                            text.trim().length < 25 && (normRight - normLeft) < 0.65f && (normBottom - normTop) < 0.15f -> UIElementType.BUTTON
                            lowerText.contains("search") || lowerText.contains("type") || lowerText.contains("enter") -> UIElementType.INPUT_FIELD
                            else -> UIElementType.GENERAL_LABEL
                        }

                        val id = "block_${index}_${UUID.randomUUID().toString().take(8)}"

                        recognizedBlocks.add(
                            RecognizedBlock(
                                id = id,
                                text = text,
                                bounds = bounds,
                                centroid = centroid,
                                rawRect = rawRect,
                                elementType = elementType,
                                isSensitive = isSensitive
                            )
                        )
                    }

                    Log.d(TAG, "ML Kit OCR recognized ${recognizedBlocks.size} blocks in ${npuInferenceMs}ms")
                    onComplete(recognizedBlocks, npuInferenceMs)
                }
                .addOnFailureListener { e ->
                    val npuInferenceMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    Log.e(TAG, "ML Kit OCR failed: ${e.message}", e)
                    onComplete(emptyList(), npuInferenceMs)
                }
        } catch (e: Exception) {
            val npuInferenceMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            Log.e(TAG, "Error executing ML Kit processFrame: ${e.message}", e)
            onComplete(emptyList(), npuInferenceMs)
        }
    }

    /**
     * Backward-compatible alias for [processFrame].
     */
    suspend fun processFrameSuspend(bitmap: Bitmap): Pair<List<RecognizedBlock>, Long> =
        processFrame(bitmap)

    /**
     * Legacy analyzeFrame method for backward compatibility with voice and context analysis.
     */
    suspend fun analyzeFrame(
        bitmap: Bitmap,
        screenWidth: Int = bitmap.width,
        screenHeight: Int = bitmap.height,
        autoRegisterPrivacyMasks: Boolean = true
    ): OcrAnalysisResult {
        val (blocks, _) = processFrame(bitmap)
        var autoMasksAdded = 0

        val legacyBlocks = blocks.map { b ->
            if (autoRegisterPrivacyMasks && b.isSensitive) {
                val rectF = android.graphics.RectF(
                    b.bounds.normLeft,
                    b.bounds.normTop,
                    b.bounds.normRight,
                    b.bounds.normBottom
                )
                ScreenCaptureService.addPrivacyMask(rectF)
                autoMasksAdded++
            }

            OcrBlock(
                text = b.text,
                normBounds = floatArrayOf(
                    b.bounds.normLeft,
                    b.bounds.normTop,
                    b.bounds.normRight,
                    b.bounds.normBottom
                ),
                isSensitive = b.isSensitive,
                category = b.elementType.name
            )
        }

        return OcrAnalysisResult(
            fullText = blocks.joinToString("\n") { it.text },
            blockCount = blocks.size,
            blocks = legacyBlocks,
            autoMasksAdded = autoMasksAdded,
            frameWidth = screenWidth,
            frameHeight = screenHeight
        )
    }

    override fun close() {
        try {
            textRecognizer.close()
        } catch (ignored: Exception) {}
    }
}
