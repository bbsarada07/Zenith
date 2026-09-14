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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * VoiceControlService: Native Android Speech Recognition & Voice-Driven Spatial Co-Pilot.
 *
 * Listens for spoken voice commands via phone microphone (e.g., "Zenith click Pay", "Zenith mask screen", "Zenith swipe up"),
 * extracts semantic targets using ML Kit OCR, and executes spatial gestures and system controls autonomously.
 */
class VoiceControlService(
    private val context: Context,
    private val frameProvider: () -> Bitmap? = { null },
    private val eventBroadcaster: ((transcript: String, action: String, executed: Boolean) -> Unit)? = null
) {

    companion object {
        private const val TAG = "ZenithVoiceControl"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile var isListening: Boolean = false
        private set

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun startListening() {
        mainHandler.post {
            if (speechRecognizer == null) {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.w(TAG, "Speech recognition not available on this device.")
                    return@post
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createRecognitionListener())
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
                Log.i(TAG, "SpeechRecognizer started listening...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognition: ${e.message}", e)
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (ignored: Exception) {}
            isListening = false
            Log.i(TAG, "SpeechRecognizer stopped listening.")
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

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Voice listener ready for speech.")
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
                Log.i(TAG, "Voice Command Transcript: '$transcript'")
                processSpokenCommand(transcript)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Log.d(TAG, "Partial Voice: '${matches[0]}'")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Parses spoken command transcripts and triggers spatial gestures, masking, or system navigation.
     */
    fun processSpokenCommand(transcript: String) {
        val lower = transcript.lowercase().trim()
        val cleaned = lower.removePrefix("zenith").trim()

        serviceScope.launch(Dispatchers.Default) {
            var executed = false
            var actionName = "unknown"

            when {
                cleaned.startsWith("click") || cleaned.startsWith("tap") || cleaned.startsWith("press") -> {
                    val target = cleaned.removePrefix("click").removePrefix("tap").removePrefix("press").trim()
                    actionName = "tap_$target"
                    executed = executeSpatialTapByLabel(target)
                }

                cleaned.contains("swipe up") || cleaned.contains("scroll up") -> {
                    actionName = "swipe_up"
                    ZenithAccessibilityService.performSwipe(0.5f, 0.8f, 0.5f, 0.2f, 300)
                    executed = true
                }

                cleaned.contains("swipe down") || cleaned.contains("scroll down") -> {
                    actionName = "swipe_down"
                    ZenithAccessibilityService.performSwipe(0.5f, 0.2f, 0.5f, 0.8f, 300)
                    executed = true
                }

                cleaned.contains("go back") || cleaned.equals("back") -> {
                    actionName = "system_back"
                    executed = ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }

                cleaned.contains("go home") || cleaned.equals("home") -> {
                    actionName = "system_home"
                    executed = ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                }

                cleaned.contains("recents") || cleaned.contains("app switch") -> {
                    actionName = "system_recents"
                    executed = ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                }

                cleaned.contains("mask screen") || cleaned.contains("redact screen") -> {
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

    private fun executeSpatialTapByLabel(targetLabel: String): Boolean {
        if (targetLabel.isEmpty()) return false
        val bitmap = frameProvider() ?: return false

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            var targetFound = false
            var normX = 0.5f
            var normY = 0.5f

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    for (block in visionText.textBlocks) {
                        if (block.text.contains(targetLabel, ignoreCase = true)) {
                            val rect = block.boundingBox
                            if (rect != null) {
                                normX = (rect.centerX().toFloat() / bitmap.width.toFloat()).coerceIn(0f, 1f)
                                normY = (rect.centerY().toFloat() / bitmap.height.toFloat()).coerceIn(0f, 1f)
                                targetFound = true
                                ZenithAccessibilityService.performTap(normX, normY)
                                Log.i(TAG, "Voice tap injected onto '$targetLabel' at ($normX, $normY)")
                                return@addOnSuccessListener
                            }
                        }
                    }
                }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error matching voice target by OCR: ${e.message}", e)
            return false
        }
    }
}
