package com.zenith.engine.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * OtpInterceptor: Real-Time Verification Code & SMS OTP Sniffer.
 *
 * Automatically extracts 4 to 6 digit verification tokens from incoming SMS broadcasts
 * and clipboard buffers to enable zero-interaction two-factor authentication during autonomous plans.
 */
class OtpInterceptor : BroadcastReceiver() {

    companion object {
        private const val TAG = "OtpInterceptor"
        private val OTP_REGEX = Regex("""\b\d{4,6}\b""")
        private val KEYWORDS = listOf("otp", "code", "pin", "verify", "verification", "password", "auth", "secret")

        private val _isIntercepting = AtomicBoolean(true)
        private val _interceptCount = AtomicInteger(0)

        private val _latestOtpFlow = MutableStateFlow<String?>(null)
        val latestOtpFlow: StateFlow<String?> = _latestOtpFlow.asStateFlow()

        var lastExtractedOtp: String? = null
            private set

        fun isIntercepting(): Boolean = _isIntercepting.get()

        fun setIntercepting(enabled: Boolean) {
            _isIntercepting.set(enabled)
            Log.i(TAG, "OTP Interception enabled: $enabled")
        }

        fun getInterceptCount(): Int = _interceptCount.get()

        /**
         * Parses text manually to extract verification tokens.
         */
        fun parseOtpFromText(messageText: String): String? {
            val lower = messageText.lowercase()
            val hasKeyword = KEYWORDS.any { lower.contains(it) }
            val match = OTP_REGEX.find(messageText)

            if (match != null && (hasKeyword || messageText.length < 30)) {
                val otp = match.value
                lastExtractedOtp = otp
                _interceptCount.incrementAndGet()
                _latestOtpFlow.value = otp
                Log.i(TAG, "OTP parsed from text: $otp")
                return otp
            }
            return null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!_isIntercepting.get()) return

        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle: Bundle? = intent.extras
            if (bundle != null) {
                try {
                    @Suppress("DEPRECATION")
                    val pdus = bundle.get("pdus") as? Array<*> ?: return
                    val format = bundle.getString("format")

                    val fullMessage = StringBuilder()
                    for (pdu in pdus) {
                        val bytes = pdu as? ByteArray ?: continue
                        val sms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            SmsMessage.createFromPdu(bytes, format)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsMessage.createFromPdu(bytes)
                        }
                        fullMessage.append(sms.displayMessageBody)
                    }

                    val messageBody = fullMessage.toString()
                    val extractedOtp = parseOtpFromText(messageBody)
                    if (extractedOtp != null) {
                        Log.i(TAG, "SMS OTP Intercepted: $extractedOtp")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error intercepting SMS: ${e.message}", e)
                }
            }
        }
    }
}
