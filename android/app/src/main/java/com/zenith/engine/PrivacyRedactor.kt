package com.zenith.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log

/**
 * PrivacyRedactor: Zero-Trust On-Device Pixel-Buffer Privacy Redaction Filter.
 *
 * Scans recognized text blocks for sensitive PII (Credit cards, OTPs, PINs, Passwords)
 * and directly renders solid black redaction rectangles over raw pixel coordinates on
 * mutable bitmap frames BEFORE network transmission or web streaming.
 */
object PrivacyRedactor {

    private const val TAG = "PrivacyRedactor"

    // Sensitive Pattern Regular Expressions
    private val CREDIT_CARD_REGEX = Regex("""\b(?:\d[ -]*?){13,16}\b""")
    private val OTP_PIN_REGEX = Regex("""\b\d{4,6}\b""")

    private val CONTEXT_KEYWORDS = listOf(
        "code", "otp", "pin", "password", "verification", "auth", "cvv", "cvc", "secret"
    )

    private val redactionPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    /**
     * Inspects recognized text blocks, flags sensitive tokens, and draws opaque black
     * redaction boxes directly over sensitive coordinate regions in the Bitmap buffer.
     *
     * @param sourceBitmap The input screen capture Bitmap.
     * @param blocks List of RecognizedBlock items produced by [SpatialVisionEngine].
     * @return A redacted mutable [Bitmap] ready for encoding and streaming.
     */
    fun redactBitmap(sourceBitmap: Bitmap, blocks: List<SpatialVisionEngine.RecognizedBlock>): Bitmap {
        // Ensure mutable bitmap for canvas drawing
        val targetBitmap = if (sourceBitmap.isMutable) {
            sourceBitmap
        } else {
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        var sensitiveCount = 0
        var canvas: Canvas? = null

        for (block in blocks) {
            val text = block.text
            val lowerText = text.lowercase()

            val isCreditCard = CREDIT_CARD_REGEX.containsMatchIn(text)
            val hasContextKeyword = CONTEXT_KEYWORDS.any { lowerText.contains(it) }
            val isOtpOrPin = OTP_PIN_REGEX.containsMatchIn(text) && hasContextKeyword

            if (isCreditCard || isOtpOrPin) {
                block.isSensitive = true
                sensitiveCount++

                if (canvas == null) {
                    canvas = Canvas(targetBitmap)
                }

                if (!block.rawRect.isEmpty) {
                    canvas.drawRect(block.rawRect, redactionPaint)
                }
            }
        }

        if (sensitiveCount > 0) {
            Log.d(TAG, "Redacted $sensitiveCount sensitive regions (Credit cards / OTPs / PINs).")
        }

        return targetBitmap
    }

    /**
     * Checks if a given text contains sensitive patterns.
     */
    fun isSensitiveText(text: String): Boolean {
        val lowerText = text.lowercase()
        val isCreditCard = CREDIT_CARD_REGEX.containsMatchIn(text)
        val hasContextKeyword = CONTEXT_KEYWORDS.any { lowerText.contains(it) }
        val isOtpOrPin = OTP_PIN_REGEX.containsMatchIn(text) && hasContextKeyword
        return isCreditCard || isOtpOrPin
    }
}
