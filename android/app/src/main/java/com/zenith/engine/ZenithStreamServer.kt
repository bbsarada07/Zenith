package com.zenith.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ZenithStreamServer: Embedded Ktor CIO Streaming Server & ML Kit Vision Intelligence Engine.
 *
 * Capabilities:
 * 1. Hosts the desktop Web Command Center (`/`) directly from APK assets (`web/index.html`).
 * 2. Broadcasts ultra-low latency JPEG keyframe streams and JSON telemetry over WebSockets (`/stream`).
 * 3. Handles remote gestures (tap, swipe), clipboard synchronization, and on-device ML Kit OCR over (`/control` and `/stream`).
 */
class ZenithStreamServer(
    val context: Context,
    val port: Int = 8080,
    val latestFrameProvider: () -> Bitmap? = { null }
) : AutoCloseable {

    companion object {
        private const val TAG = "ZenithStreamServer"
    }

    interface ServerEventListener {
        fun onRemoteTouchReceived(normalizedX: Float, normalizedY: Float)
        fun onRemoteClipboardReceived(text: String)
        fun onRemoteReasoningTriggered(prompt: String)
        fun onOcrCompleted(fullText: String, blockCount: Int)
    }

    var eventListener: ServerEventListener? = null

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ktorServer: ApplicationEngine? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // On-Device ML Kit Text Recognizer
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // Thread-safe set of connected WebSocket stream sessions
    private val streamSessions = Collections.newSetFromMap(ConcurrentHashMap<WebSocketSession, Boolean>())
    // Thread-safe set of connected WebSocket control sessions
    private val controlSessions = Collections.newSetFromMap(ConcurrentHashMap<WebSocketSession, Boolean>())

    // Metrics & Screen State
    @Volatile private var screenWidth: Int = 1080
    @Volatile private var screenHeight: Int = 2400
    @Volatile private var lastNpuMs: Float = 4.2f
    @Volatile private var lastLatencyMs: Long = 14L
    @Volatile private var lastDetections: List<ZenithDetector.Detection> = emptyList()
    private val frameTimestamps = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    fun updateScreenDimensions(width: Int, height: Int) {
        this.screenWidth = width
        this.screenHeight = height
        Log.i(TAG, "Screen dimensions updated: ${width}x${height}")
    }

    fun recordMetrics(latencyMs: Long, npuMs: Float, detections: List<ZenithDetector.Detection>) {
        val now = android.os.SystemClock.elapsedRealtime()
        frameTimestamps.addLast(now)
        while (frameTimestamps.isNotEmpty() && (frameTimestamps.peekFirst()?.let { now - it > 1000L } == true)) {
            frameTimestamps.pollFirst()
        }
        lastLatencyMs = latencyMs
        lastNpuMs = npuMs
        lastDetections = detections
    }

    fun getRollingFps(): Int {
        val now = android.os.SystemClock.elapsedRealtime()
        while (frameTimestamps.isNotEmpty() && (frameTimestamps.peekFirst()?.let { now - it > 1000L } == true)) {
            frameTimestamps.pollFirst()
        }
        return frameTimestamps.size
    }

    fun start() {
        if (ktorServer != null) return

        try {
            startTelemetryTicker()

            ktorServer = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                install(WebSockets)

                routing {
                    // 1. Serve Web Command Center Dashboard
                    get("/") {
                        try {
                            val html = this@ZenithStreamServer.context.assets
                                .open("web/index.html")
                                .bufferedReader()
                                .use { it.readText() }
                            call.respondText(html, ContentType.Text.Html)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading web/index.html from assets: ${e.message}")
                            call.respondText(
                                "Zenith Engine: Failed to load web/index.html (${e.message})",
                                ContentType.Text.Plain,
                                HttpStatusCode.InternalServerError
                            )
                        }
                    }

                    // 2. Stream WebSocket Endpoint: Dispatches binary frames & processes incoming control packets
                    webSocket("/stream") {
                        streamSessions.add(this)
                        Log.i(TAG, "Web client connected to /stream. Total: ${streamSessions.size}")

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    handleInboundPayload(text, this)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "WebSocket /stream session error: ${e.message}")
                        } finally {
                            streamSessions.remove(this)
                            Log.i(TAG, "Web client disconnected from /stream. Total: ${streamSessions.size}")
                        }
                    }

                    // 3. Dedicated Remote Control & Intelligence WebSocket Endpoint
                    webSocket("/control") {
                        controlSessions.add(this)
                        Log.i(TAG, "Web client connected to /control. Total: ${controlSessions.size}")

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    handleInboundPayload(text, this)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "WebSocket /control session error: ${e.message}")
                        } finally {
                            controlSessions.remove(this)
                            Log.i(TAG, "Web client disconnected from /control. Total: ${controlSessions.size}")
                        }
                    }
                }
            }.start(wait = false)

            Log.i(TAG, "ZenithStreamServer started successfully on http://0.0.0.0:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start embedded Ktor server on port $port: ${e.message}", e)
        }
    }

    /**
     * Periodic 300ms telemetry ticker pushing engine health stats.
     */
    private fun startTelemetryTicker() {
        serverScope.launch {
            while (isActive) {
                delay(300)
                if (streamSessions.isNotEmpty() || controlSessions.isNotEmpty()) {
                    pushTelemetryPacket()
                }
            }
        }
    }

    // Macro & Voice Automation Engines
    val macroEngine by lazy { MacroEngine(frameProvider = latestFrameProvider) }
    val voiceControlService by lazy {
        VoiceControlService(
            context = context,
            frameProvider = latestFrameProvider,
            eventBroadcaster = { transcript, action, executed ->
                val voiceJson = JSONObject().apply {
                    put("type", "voice_command")
                    put("transcript", transcript)
                    put("action", action)
                    put("executed", executed)
                }
                broadcastJson(voiceJson)
            }
        )
    }

    private fun pushTelemetryPacket() {
        val runtime = Runtime.getRuntime()
        val ramMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).toInt()
        val fps = getRollingFps()

        val json = JSONObject().apply {
            put("type", "telemetry")
            put("fps", fps)
            put("latency_ms", lastLatencyMs)
            put("npu_ms", (lastNpuMs * 10).toInt() / 10.0)
            put("ram_mb", ramMb)
            put("privacy_masks_active", ScreenCaptureService.getPrivacyMaskCount())
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)

            // Backwards compatibility keys
            put("timestamp", System.currentTimeMillis())
            put("npuLatencyMs", lastNpuMs)
            put("ramUsedMb", ramMb)

            val detArray = JSONArray()
            for (det in lastDetections) {
                val dObj = JSONObject().apply {
                    put("x1", det.x1)
                    put("y1", det.y1)
                    put("x2", det.x2)
                    put("y2", det.y2)
                    put("score", det.score)
                    put("classId", det.classId)
                }
                detArray.put(dObj)
            }
            put("detections", detArray)
        }

        broadcastJson(json)
    }

    /**
     * Broadcasts a compressed JPEG frame to all connected streaming clients.
     */
    fun broadcastFrame(jpegBytes: ByteArray) {
        if (streamSessions.isEmpty()) return

        val frame = Frame.Binary(true, jpegBytes)
        serverScope.launch {
            val iterator = streamSessions.iterator()
            while (iterator.hasNext()) {
                val session = iterator.next()
                try {
                    session.send(frame)
                } catch (e: ClosedSendChannelException) {
                    iterator.remove()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to dispatch frame to client: ${e.message}")
                }
            }
        }
    }

    /**
     * Broadcasts JSON telemetry metadata (FPS, Latency, Memory, Detections).
     */
    fun broadcastTelemetry(
        fps: Float,
        npuLatencyMs: Float,
        detections: List<ZenithDetector.Detection>,
        logMessage: String? = null
    ) {
        if (streamSessions.isEmpty() && controlSessions.isEmpty()) return

        val runtime = Runtime.getRuntime()
        val ramUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("fps", fps)
            put("npuLatencyMs", npuLatencyMs)
            put("ramUsedMb", ramUsedMb)
            put("privacy_masks_active", ScreenCaptureService.getPrivacyMaskCount())
            if (logMessage != null) {
                put("logMessage", logMessage)
            }

            val detArray = JSONArray()
            for (det in detections) {
                val dObj = JSONObject().apply {
                    put("x1", det.x1)
                    put("y1", det.y1)
                    put("x2", det.x2)
                    put("y2", det.y2)
                    put("score", det.score)
                    put("classId", det.classId)
                }
                detArray.put(dObj)
            }
            put("detections", detArray)
        }

        broadcastJson(json)
    }

    /**
     * Broadcasts arbitrary JSON objects to all connected clients.
     */
    fun broadcastJson(json: JSONObject) {
        val textFrame = Frame.Text(json.toString())
        serverScope.launch {
            val allSessions = streamSessions + controlSessions
            for (session in allSessions) {
                try {
                    session.send(textFrame)
                } catch (e: Exception) {
                    // Ignore transient network errors
                }
            }
        }
    }

    /**
     * Processes inbound JSON command frames and executes actions.
     */
    private fun handleInboundPayload(payloadText: String, session: WebSocketSession) {
        try {
            val json = JSONObject(payloadText)
            val action = if (json.has("action")) json.optString("action") else json.optString("type")

            when (action.lowercase()) {
                "tap", "touch" -> {
                    val normX = json.optDouble("x", 0.0).toFloat()
                    val normY = json.optDouble("y", 0.0).toFloat()
                    val label = if (json.has("label")) json.getString("label") else null

                    if (macroEngine.isRecording) {
                        macroEngine.recordTap(normX, normY, label)
                    }

                    // Execute touch via Accessibility Service
                    ZenithAccessibilityService.performTap(normX, normY)
                    eventListener?.onRemoteTouchReceived(normX, normY)
                }

                "swipe" -> {
                    val startX = json.optDouble("startX", 0.5).toFloat()
                    val startY = json.optDouble("startY", 0.7).toFloat()
                    val endX = json.optDouble("endX", 0.5).toFloat()
                    val endY = json.optDouble("endY", 0.3).toFloat()
                    val duration = json.optLong("durationMs", 250L)

                    if (macroEngine.isRecording) {
                        macroEngine.recordSwipe(startX, startY, endX, endY, duration)
                    }

                    ZenithAccessibilityService.performSwipe(startX, startY, endX, endY, duration)
                }

                "system_key", "key_event", "hardware_key" -> {
                    val key = json.optString("key", "back").lowercase()
                    when (key) {
                        "back" -> ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        "home" -> ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                        "recents", "app_switch" -> ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                        "notifications" -> ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                        "quick_settings" -> ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                    }
                    Log.i(TAG, "Executed system global key action: $key")
                }

                "add_mask", "mask_rect" -> {
                    val left = json.optDouble("left", 0.0).toFloat()
                    val top = json.optDouble("top", 0.0).toFloat()
                    val right = json.optDouble("right", 0.0).toFloat()
                    val bottom = json.optDouble("bottom", 0.0).toFloat()
                    ScreenCaptureService.addPrivacyMask(android.graphics.RectF(left, top, right, bottom))
                    broadcastJson(JSONObject().apply {
                        put("type", "privacy_mask_update")
                        put("activeMasks", ScreenCaptureService.getPrivacyMaskCount())
                    })
                }

                "clear_masks", "unmask" -> {
                    ScreenCaptureService.clearPrivacyMasks()
                    broadcastJson(JSONObject().apply {
                        put("type", "privacy_mask_update")
                        put("activeMasks", 0)
                    })
                }

                "macro_record_start" -> {
                    macroEngine.startRecording()
                    broadcastJson(JSONObject().apply {
                        put("type", "macro_status")
                        put("isRecording", true)
                    })
                }

                "macro_record_stop" -> {
                    val macroJson = macroEngine.stopRecording()
                    broadcastJson(JSONObject().apply {
                        put("type", "macro_status")
                        put("isRecording", false)
                        put("macroJson", macroJson)
                    })
                }

                "macro_play" -> {
                    val macroJson = json.optString("macro_json", "")
                    val selfHealing = json.optBoolean("self_healing", true)
                    macroEngine.playMacro(macroJson, enableSelfHealing = selfHealing) { stepNum, total, desc ->
                        broadcastJson(JSONObject().apply {
                            put("type", "macro_progress")
                            put("currentStep", stepNum)
                            put("totalSteps", total)
                            put("description", desc)
                        })
                    }
                }

                "macro_stop" -> {
                    macroEngine.stopPlayback()
                }

                "voice_start", "voice_listen" -> {
                    voiceControlService.startListening()
                    broadcastJson(JSONObject().apply {
                        put("type", "voice_status")
                        put("isListening", true)
                    })
                }

                "voice_stop" -> {
                    voiceControlService.stopListening()
                    broadcastJson(JSONObject().apply {
                        put("type", "voice_status")
                        put("isListening", false)
                    })
                }

                "inject_text" -> {
                    val text = json.optString("text", "")
                    if (text.isNotEmpty()) {
                        ZenithAccessibilityService.injectTextToFocus(text)
                    }
                }

                "ocr", "extract_text" -> {
                    processOnDeviceOcr(session)
                }

                "clipboard", "set_clipboard" -> {
                    val text = json.optString("text", "")
                    if (text.isNotEmpty()) {
                        mainHandler.post {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Zenith Web Remote", text)
                            clipboard.setPrimaryClip(clip)
                        }
                        eventListener?.onRemoteClipboardReceived(text)
                        Log.i(TAG, "Inbound remote clipboard synced: $text")
                    }
                }

                "trigger_reasoning", "reason", "summarize" -> {
                    val prompt = json.optString("prompt", "Analyze screen context")
                    eventListener?.onRemoteReasoningTriggered(prompt)
                    Log.i(TAG, "Inbound reasoning command triggered: $prompt")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling inbound JSON payload: ${e.message}", e)
        }
    }

    /**
     * Performs Google ML Kit Text Recognition with Regex Sensitive Redaction & Intent Parsing.
     */
    private fun processOnDeviceOcr(session: WebSocketSession) {
        val bitmap = latestFrameProvider() ?: run {
            Log.w(TAG, "No frame available for OCR analysis.")
            sendOcrError(session, "No active frame captured yet.")
            return
        }

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val fullText = visionText.text
                    val blocksArray = JSONArray()

                    val creditCardRegex = Regex("""\b(?:\d[ -]*?){13,16}\b""")
                    val sensitiveLabelRegex = Regex("""(?i)\b(password|pin|cvv|cvc|ssn|secret|passcode|token)\b[:\s]*\S+""")
                    val otpRegex = Regex("""(?i)\b(otp|code|verification)\b[:\s]*\d{4,8}|\b\d{6}\b""")

                    val actionableKeywords = listOf(
                        "Pay", "Submit", "Allow", "Confirm", "Settings", "Search", "Login",
                        "Sign In", "Cart", "Cancel", "Back", "Delete", "Done", "Next", "Save", "Continue", "Order", "Checkout"
                    )

                    var autoMasksAdded = 0

                    for (block in visionText.textBlocks) {
                        val rect = block.boundingBox
                        val blockText = block.text

                        // 1. Sensitive Data Detection (Credit Cards, Passwords, OTPs)
                        val isSensitive = creditCardRegex.containsMatchIn(blockText) ||
                                sensitiveLabelRegex.containsMatchIn(blockText) ||
                                otpRegex.containsMatchIn(blockText)

                        if (isSensitive && rect != null) {
                            val maskRect = android.graphics.RectF(
                                rect.left.toFloat(),
                                rect.top.toFloat(),
                                rect.right.toFloat(),
                                rect.bottom.toFloat()
                            )
                            ScreenCaptureService.addPrivacyMask(maskRect)
                            autoMasksAdded++
                        }

                        // 2. Intent Classification
                        var matchedCategory: String? = null
                        for (kw in actionableKeywords) {
                            if (blockText.contains(kw, ignoreCase = true)) {
                                matchedCategory = kw
                                break
                            }
                        }

                        val blockJson = JSONObject().apply {
                            put("text", blockText)
                            put("isSensitive", isSensitive)
                            if (matchedCategory != null) {
                                put("category", matchedCategory)
                            }
                            if (rect != null) {
                                put("left", rect.left)
                                put("top", rect.top)
                                put("right", rect.right)
                                put("bottom", rect.bottom)
                            }
                        }
                        blocksArray.put(blockJson)
                    }

                    val response = JSONObject().apply {
                        put("type", "ocr_result")
                        put("fullText", fullText)
                        put("blockCount", visionText.textBlocks.size)
                        put("autoMasksAdded", autoMasksAdded)
                        put("privacyMasksTotal", ScreenCaptureService.getPrivacyMaskCount())
                        put("blocks", blocksArray)
                        put("frameWidth", bitmap.width)
                        put("frameHeight", bitmap.height)
                    }

                    serverScope.launch {
                        try {
                            session.send(Frame.Text(response.toString()))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send OCR response: ${e.message}")
                        }
                    }

                    eventListener?.onOcrCompleted(fullText, visionText.textBlocks.size)
                    Log.i(TAG, "ML Kit OCR completed: ${visionText.textBlocks.size} blocks found ($autoMasksAdded sensitive regions auto-masked).")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit OCR failed: ${e.message}", e)
                    sendOcrError(session, "OCR Recognition failed: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error during ML Kit OCR processing: ${e.message}", e)
            sendOcrError(session, "OCR processing error: ${e.message}")
        }
    }

    private fun sendOcrError(session: WebSocketSession, errorMsg: String) {
        val errorJson = JSONObject().apply {
            put("type", "ocr_error")
            put("message", errorMsg)
        }
        serverScope.launch {
            try {
                session.send(Frame.Text(errorJson.toString()))
            } catch (ignored: Exception) {}
        }
    }

    fun stop() {
        close()
    }

    override fun close() {
        serverScope.cancel()
        try {
            textRecognizer.close()
        } catch (ignored: Exception) {}

        try {
            ktorServer?.stop(500, 1500)
            ktorServer = null
            streamSessions.clear()
            controlSessions.clear()
            Log.i(TAG, "ZenithStreamServer stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ZenithStreamServer: ${e.message}", e)
        }
    }
}
