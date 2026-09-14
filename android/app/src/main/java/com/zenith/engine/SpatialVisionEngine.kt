package com.zenith.engine

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * SpatialVisionEngine: On-Device ML Kit OCR, Sensitive Privacy Redaction & Spatial Intent Engine.
 *
 * Capabilities:
 * 1. Analyzes active screen bitmaps on-device via Google ML Kit Text Recognition.
 * 2. Normalizes text block bounding boxes to normalized coordinates [0.0, 1.0] for responsive remote canvas reticles.
 * 3. Zero-Trust Redaction: Employs regex patterns for credit cards, passwords, PINs, and OTP verification codes,
 *    automatically enrolling sensitive bounding rectangles into ScreenCaptureService.activePrivacyMasks.
 * 4. Actionable Semantic Intent Tagging: Labels actionable elements ("Pay", "Submit", "Settings", "Confirm", etc.).
 */
class SpatialVisionEngine : AutoCloseable {

    companion object {
        private const val TAG = "SpatialVisionEngine"

        // Sensitive pattern regular expressions
        private val CREDIT_CARD_REGEX = Regex("""\b(?:\d[ -]*?){13,16}\b""")
        private val SENSITIVE_LABEL_REGEX = Regex("""(?i)\b(password|pin|cvv|cvc|ssn|secret|passcode|token|key)\b[:\s]*\S+""")
        private val OTP_REGEX = Regex("""(?i)\b(otp|code|verification|auth)\b[:\s]*\d{4,8}|\b\d{6}\b""")

        // Actionable UI categories for autonomous tele-operation
        private val ACTIONABLE_KEYWORDS = listOf(
            "Pay", "Submit", "Allow", "Confirm", "Settings", "Search", "Login",
            "Sign In", "Cart", "Cancel", "Back", "Delete", "Done", "Next", "Save", "Continue", "Order", "Checkout"
        )
    }

    data class OcrBlock(
        val text: String,
        val normBounds: FloatArray, // [left, top, right, bottom] in 0.0..1.0
        val isSensitive: Boolean,
        val category: String?
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
     * Performs asynchronous OCR text recognition, sensitive redaction registration, and coordinate normalization.
     */
    suspend fun analyzeFrame(
        bitmap: Bitmap,
        screenWidth: Int = bitmap.width,
        screenHeight: Int = bitmap.height,
        autoRegisterPrivacyMasks: Boolean = true
    ): OcrAnalysisResult = suspendCancellableCoroutine { continuation ->
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val blocksList = mutableListOf<OcrBlock>()
                    var autoMasksAdded = 0
                    val widthF = bitmap.width.toFloat().coerceAtLeast(1f)
                    val heightF = bitmap.height.toFloat().coerceAtLeast(1f)

                    for (block in visionText.textBlocks) {
                        val text = block.text
                        val rect = block.boundingBox

                        val isSensitive = CREDIT_CARD_REGEX.containsMatchIn(text) ||
                                SENSITIVE_LABEL_REGEX.containsMatchIn(text) ||
                                OTP_REGEX.containsMatchIn(text)

                        val normLeft = if (rect != null) (rect.left / widthF).coerceIn(0f, 1f) else 0f
                        val normTop = if (rect != null) (rect.top / heightF).coerceIn(0f, 1f) else 0f
                        val normRight = if (rect != null) (rect.right / widthF).coerceIn(0f, 1f) else 1f
                        val normBottom = if (rect != null) (rect.bottom / heightF).coerceIn(0f, 1f) else 1f

                        if (isSensitive && rect != null && autoRegisterPrivacyMasks) {
                            // Scale to screen metrics for accurate canvas privacy mask drawing
                            val maskRect = RectF(
                                normLeft * screenWidth,
                                normTop * screenHeight,
                                normRight * screenWidth,
                                normBottom * screenHeight
                            )
                            ScreenCaptureService.addPrivacyMask(maskRect)
                            autoMasksAdded++
                        }

                        var matchedCategory: String? = null
                        for (kw in ACTIONABLE_KEYWORDS) {
                            if (text.contains(kw, ignoreCase = true)) {
                                matchedCategory = kw
                                break
                            }
                        }

                        blocksList.add(
                            OcrBlock(
                                text = text,
                                normBounds = floatArrayOf(normLeft, normTop, normRight, normBottom),
                                isSensitive = isSensitive,
                                category = matchedCategory
                            )
                        )
                    }

                    val result = OcrAnalysisResult(
                        fullText = visionText.text,
                        blockCount = visionText.textBlocks.size,
                        blocks = blocksList,
                        autoMasksAdded = autoMasksAdded,
                        frameWidth = bitmap.width,
                        frameHeight = bitmap.height
                    )
                    Log.i(TAG, "OCR Analysis complete: ${blocksList.size} blocks found ($autoMasksAdded redacted).")
                    continuation.resume(result)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit OCR failed: ${e.message}", e)
                    continuation.resume(
                        OcrAnalysisResult(
                            fullText = "",
                            blockCount = 0,
                            blocks = emptyList(),
                            autoMasksAdded = 0,
                            frameWidth = bitmap.width,
                            frameHeight = bitmap.height
                        )
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating ML Kit OCR: ${e.message}", e)
            continuation.resume(
                OcrAnalysisResult(
                    fullText = "",
                    blockCount = 0,
                    blocks = emptyList(),
                    autoMasksAdded = 0,
                    frameWidth = bitmap.width,
                    frameHeight = bitmap.height
                )
            )
        }
    }

    override fun close() {
        try {
            textRecognizer.close()
        } catch (ignored: Exception) {}
    }
}
