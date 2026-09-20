package com.zenith.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log

/**
 * PrivacyRedactor: Zero-Trust On-Device Pixel-Buffer Privacy Redaction Filter.
 *
 * Inspects recognized text blocks flagged as sensitive (Credit cards, OTPs, PINs, Passwords, etc.)
 * and renders solid black redaction rectangles directly over raw pixel coordinates on mutable
 * bitmap frames BEFORE network transmission or web streaming.
 */
object PrivacyRedactor {

    private const val TAG = "PrivacyRedactor"

    private val redactionPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    /**
     * Applies zero-trust pixel redaction by drawing solid black rectangles over sensitive coordinate
     * regions directly on the bitmap buffer.
     *
     * @param sourceBitmap The input screen capture [Bitmap].
     * @param blocks List of [SpatialVisionEngine.RecognizedBlock] items produced by [SpatialVisionEngine].
     * @return A redacted mutable [Bitmap] ready for encoding and streaming.
     */
    fun redactBitmap(sourceBitmap: Bitmap, blocks: List<SpatialVisionEngine.RecognizedBlock>): Bitmap {
        val targetBitmap = if (sourceBitmap.isMutable) {
            sourceBitmap
        } else {
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        var sensitiveCount = 0
        var canvas: Canvas? = null

        for (block in blocks) {
            if (block.isSensitive && !block.rawRect.isEmpty) {
                if (canvas == null) {
                    canvas = Canvas(targetBitmap)
                }
                canvas.drawRect(block.rawRect, redactionPaint)
                sensitiveCount++
            }
        }

        if (sensitiveCount > 0) {
            Log.d(TAG, "Redacted $sensitiveCount sensitive regions (PII / Credentials / OTPs).")
        }

        return targetBitmap
    }

    /**
     * Checks if a given text contains sensitive patterns.
     */
    fun isSensitiveText(text: String): Boolean {
        val lowerText = text.lowercase()
        val creditCardRegex = Regex("""\b(?:\d[ -]*?){13,16}\b""")
        val otpRegex = Regex("""\b\d{4,6}\b""")
        val sensitiveKeywords = listOf("otp", "code", "pin", "password", "cvv", "cvc", "secret", "auth", "verification")

        val isCreditCard = creditCardRegex.containsMatchIn(text)
        val hasKeyword = sensitiveKeywords.any { lowerText.contains(it) }
        val isOtpOrPin = otpRegex.containsMatchIn(text) && hasKeyword

        return isCreditCard || isOtpOrPin
    }
}
