package com.zenith.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * VoiceControlEngine: On-Device Voice Co-Pilot & Spoken Gesture Dispatcher.
 *
 * Capabilities:
 * 1. Connects to native Android SpeechRecognizer listening via the mobile microphone.
 * 2. Parses voice commands (e.g., "Click Pay", "Tap Settings", "Mask Passwords", "Swipe Up", "Back", "Home").
 * 3. Matches spoken keywords against active ML Kit OCR bounding boxes via SpatialVisionEngine.
 * 4. Injects native touch gestures (injectTap, injectSwipe) or updates privacy redactions dynamically.
 */
class VoiceControlEngine(
    private val context: Context,
    private val frameProvider: () -> Bitmap? = { null },
    private val spatialVisionEngine: SpatialVisionEngine? = null,
    private val eventBroadcaster: ((transcript: String, action: String, executed: Boolean) -> Unit)? = null
) {

    companion object {
        private const val TAG = "VoiceControlEngine"
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile var isListening: Boolean = false
        private set

    fun startListening() {
        mainHandler.post {
            if (speechRecognizer == null) {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.w(TAG, "Speech recognition unavailable on this hardware.")
                    return@post
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener())
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            try {
                speechRecognizer?.startListening(intent)
                isListening = true
                Log.i(TAG, "VoiceControlEngine listening active...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to activate SpeechRecognizer: ${e.message}", e)
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (ignored: Exception) {}
            isListening = false
            Log.i(TAG, "VoiceControlEngine listening stopped.")
        }
    }

    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (ignored: Exception) {}
            isListening = false
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Voice listener ready.")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech detected.")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
        }

        override fun onError(error: Int) {
            Log.w(TAG, "SpeechRecognizer error: $error")
            isListening = false
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val transcript = matches[0]
                Log.i(TAG, "Spoken voice transcript: '$transcript'")
                processCommand(transcript)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Log.d(TAG, "Partial Voice Transcript: '${matches[0]}'")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun processCommand(transcript: String) {
        val lower = transcript.lowercase().trim()
        val cleaned = lower.removePrefix("zenith").trim()

        engineScope.launch(Dispatchers.Default) {
            var executed = false
            val actionName: String

            when {
                cleaned.startsWith("click") || cleaned.startsWith("tap") || cleaned.startsWith("press") -> {
                    val target = cleaned.removePrefix("click").removePrefix("tap").removePrefix("press").trim()
                    actionName = "tap_$target"
                    executed = executeSpatialTapByOcr(target)
                }

                cleaned.contains("swipe up") || cleaned.contains("scroll up") -> {
                    actionName = "swipe_up"
                    ZenithAccessibilityService.injectSwipe(0.5f, 0.8f, 0.5f, 0.2f, 300)
                    executed = true
                }

                cleaned.contains("swipe down") || cleaned.contains("scroll down") -> {
                    actionName = "swipe_down"
                    ZenithAccessibilityService.injectSwipe(0.5f, 0.2f, 0.5f, 0.8f, 300)
                    executed = true
                }

                cleaned.contains("go back") || cleaned == "back" -> {
                    actionName = "system_back"
                    executed = ZenithAccessibilityService.injectGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }

                cleaned.contains("go home") || cleaned == "home" -> {
                    actionName = "system_home"
                    executed = ZenithAccessibilityService.injectGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                }

                cleaned.contains("recents") || cleaned.contains("app switch") -> {
                    actionName = "system_recents"
                    executed = ZenithAccessibilityService.injectGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                }

                cleaned.contains("mask passwords") || cleaned.contains("mask screen") || cleaned.contains("redact") -> {
                    actionName = "mask_screen"
                    val metrics = context.resources.displayMetrics
                    ScreenCaptureService.addPrivacyMask(RectF(0f, 0f, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat()))
                    executed = true
                }

                cleaned.contains("clear masks") || cleaned.contains("unmask") -> {
                    actionName = "clear_masks"
                    ScreenCaptureService.clearPrivacyMasks()
                    executed = true
                }

                else -> {
                    actionName = "unrecognized"
                }
            }

            eventBroadcaster?.invoke(transcript, actionName, executed)
            Log.i(TAG, "Voice intent '$actionName' executed: $executed")
        }
    }

    private suspend fun executeSpatialTapByOcr(targetLabel: String): Boolean {
        if (targetLabel.isEmpty()) return false
        val bitmap = frameProvider() ?: return false
        val visionEngine = spatialVisionEngine ?: SpatialVisionEngine()

        try {
            val analysis = visionEngine.analyzeFrame(bitmap, autoRegisterPrivacyMasks = false)
            for (block in analysis.blocks) {
                if (block.text.contains(targetLabel, ignoreCase = true)) {
                    val bounds = block.normBounds
                    val normX = ((bounds[0] + bounds[2]) / 2f).coerceIn(0f, 1f)
                    val normY = ((bounds[1] + bounds[3]) / 2f).coerceIn(0f, 1f)

                    ZenithAccessibilityService.injectTap(normX, normY)
                    Log.i(TAG, "Voice spatial tap injected on '$targetLabel' at ($normX, $normY)")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving voice target by OCR: ${e.message}", e)
        }
        return false
    }
}
