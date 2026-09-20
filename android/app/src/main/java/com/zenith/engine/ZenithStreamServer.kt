package com.zenith.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ZenithStreamServer: High-Throughput Native WebSocket Server & Autonomous Co-Pilot Routing Bridge.
 *
 * Extends [WebSocketServer] on port 8080 to deliver:
 * 1. Ultra-low latency binary JPEG screen streaming with zero-trust privacy redaction.
 * 2. On-demand ML Kit OCR spatial text recognition with StateDiffEngine differential throttling.
 * 3. Semantic tree extraction & multi-modal accessibility fusion (SemanticNodeFusion).
 * 4. Autonomous multi-step plan execution (SpatialPlanExecutor).
 * 5. Hardware-keystore backed credential storage & direct input injection (ZenithSecureVault).
 * 6. Self-healing automation macro replay.
 * 7. Real-time telemetry broadcasting (FPS, Ping RTT, NPU latency, JVM heap RAM).
 */
class ZenithStreamServer(
    val context: Context,
    port: Int = 8080,
    val latestFrameProvider: () -> Bitmap? = { null }
) : WebSocketServer(InetSocketAddress(port)), AutoCloseable {

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
    private val mainHandler = Handler(Looper.getMainLooper())

    // Active Connected Clients
    private val connectedClients = Collections.newSetFromMap(ConcurrentHashMap<WebSocket, Boolean>())

    // Engine Components
    val spatialVisionEngine by lazy { SpatialVisionEngine() }
    val selfHealingMacroEngine by lazy { SelfHealingMacroEngine(spatialVisionEngine) }
    val macroEngine by lazy { MacroEngine(frameProvider = latestFrameProvider) }
    val zenithSecureVault by lazy { ZenithSecureVault.getInstance(context) }
    val spatialPlanExecutor by lazy { SpatialPlanExecutor(context, zenithSecureVault) }
    val engineTelemetry by lazy {
        EngineTelemetry { json -> broadcastJson(json) }
    }

    // OCR StateDiff Cache
    @Volatile private var lastOcrBitmap: Bitmap? = null
    @Volatile private var cachedOcrBlocks: List<SpatialVisionEngine.RecognizedBlock> = emptyList()
    @Volatile private var cachedOcrLatencyMs: Long = 0L

    // Viewport Screen Metrics
    @Volatile var screenWidth: Int = 1080
    @Volatile var screenHeight: Int = 2400

    fun updateScreenDimensions(width: Int, height: Int) {
        this.screenWidth = width
        this.screenHeight = height
        Log.i(TAG, "Screen dimensions updated: ${width}x${height}")
    }

    override fun onStart() {
        Log.i(TAG, "ZenithStreamServer started successfully on port $port")
        engineTelemetry.start(serverScope)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        connectedClients.add(conn)
        Log.i(TAG, "Client connected: ${conn.remoteSocketAddress} (Total clients: ${connectedClients.size})")

        // Send initial connection acknowledgement with screen metrics
        val helloPacket = JSONObject().apply {
            put("type", "CONNECTION_ESTABLISHED")
            put("port", port)
            put("screenWidth", screenWidth)
            put("screenHeight", screenHeight)
            put("serverTime", System.currentTimeMillis())
        }
        conn.send(helloPacket.toString())
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        connectedClients.remove(conn)
        Log.i(TAG, "Client disconnected: ${conn.remoteSocketAddress} (Remaining: ${connectedClients.size})")
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WebSocket error on connection ${conn?.remoteSocketAddress}: ${ex?.message}", ex)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        handleInboundMessage(conn, message)
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        // Binary message handler if needed
    }

    /**
     * Processes inbound JSON action packets from web dashboards and clients.
     */
    private fun handleInboundMessage(conn: WebSocket, messageText: String) {
        try {
            val json = JSONObject(messageText)
            val action = if (json.has("action")) {
                json.optString("action")
            } else {
                json.optString("type")
            }

            when (action.uppercase()) {
                // 1. On-Demand ML Kit OCR Processing (with StateDiffEngine Throttling)
                "SCAN_OCR", "OCR", "EXTRACT_TEXT" -> {
                    handleOcrScanRequest(conn)
                }

                // 2. Semantic Accessibility & OCR Fusion Tree
                "GET_SEMANTIC_TREE", "SEMANTIC_TREE" -> {
                    handleGetSemanticTreeRequest(conn)
                }

                // 3. Multi-Step Autonomous Plan Execution
                "EXECUTE_PLAN", "RUN_PLAN", "SPATIAL_PLAN" -> {
                    handleExecutePlanRequest(conn, json)
                }

                // 4. Secure Keystore Credential Storage
                "STORE_SECURE_TOKEN", "STORE_TOKEN", "SET_SECRET" -> {
                    handleStoreSecureTokenRequest(conn, json)
                }

                // 5. Secure Keystore Credential Injection
                "INJECT_SECURE_TOKEN", "INJECT_TOKEN", "INJECT_SECRET" -> {
                    handleInjectSecureTokenRequest(conn, json)
                }

                // 6. Self-Healing Macro Action Execution
                "PLAY_MACRO", "MACRO_ACTION", "EXECUTE_MACRO" -> {
                    handlePlayMacroRequest(conn, json)
                }

                // 7. Client Round-Trip Latency Ping-Pong
                "PING" -> {
                    val clientTime = json.optLong("timestamp", System.currentTimeMillis())
                    engineTelemetry.recordPingResponse(clientTime)
                    val pongJson = JSONObject().apply {
                        put("type", "PONG")
                        put("clientTimestamp", clientTime)
                        put("serverTimestamp", System.currentTimeMillis())
                    }
                    conn.send(pongJson.toString())
                }

                // 8. Remote Tap & Touch Injection
                "TAP", "TOUCH" -> {
                    val normX = json.optDouble("x", 0.5).toFloat()
                    val normY = json.optDouble("y", 0.5).toFloat()
                    val label = if (json.has("label")) json.getString("label") else null

                    if (macroEngine.isRecording) {
                        macroEngine.recordTap(normX, normY, label)
                    }

                    ZenithAccessibilityService.performTap(normX, normY)
                    eventListener?.onRemoteTouchReceived(normX, normY)
                }

                // 9. Remote Swipe Injection
                "SWIPE" -> {
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

                // 10. System Navigation Hardware Keys
                "KEY", "SYSTEM_KEY", "HARDWARE_KEY" -> {
                    val key = json.optString("key", "back").lowercase()
                    when (key) {
                        "back" -> ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        "home" -> ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                        "recents", "app_switch" -> ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                        "notifications" -> ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                        "quick_settings" -> ZenithAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                    }
                }

                // 11. Text Injection into Focused Edit Field
                "INJECT_TEXT", "TEXT" -> {
                    val text = json.optString("text", "")
                    if (text.isNotEmpty()) {
                        ZenithAccessibilityService.injectTextToFocus(text)
                    }
                }

                // 12. Device Clipboard Sync
                "CLIPBOARD", "SET_CLIPBOARD" -> {
                    val text = if (json.has("content")) json.optString("content", "") else json.optString("text", "")
                    if (text.isNotEmpty()) {
                        mainHandler.post {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Zenith Web Remote", text)
                            clipboard.setPrimaryClip(clip)
                        }
                        eventListener?.onRemoteClipboardReceived(text)
                    }
                }

                // 13. Privacy Mask Management
                "ADD_MASK", "MASK_RECT" -> {
                    val left = json.optDouble("left", 0.0).toFloat()
                    val top = json.optDouble("top", 0.0).toFloat()
                    val right = json.optDouble("right", 0.0).toFloat()
                    val bottom = json.optDouble("bottom", 0.0).toFloat()
                    ScreenCaptureService.addPrivacyMask(android.graphics.RectF(left, top, right, bottom))
                    engineTelemetry.activePrivacyMasks = ScreenCaptureService.getPrivacyMaskCount()
                }

                "CLEAR_MASKS", "UNMASK" -> {
                    ScreenCaptureService.clearPrivacyMasks()
                    engineTelemetry.activePrivacyMasks = 0
                }

                // 14. Macro Recording Controls
                "MACRO_RECORD_START" -> {
                    macroEngine.startRecording()
                    broadcastJson(JSONObject().apply {
                        put("type", "macro_status")
                        put("isRecording", true)
                    })
                }

                "MACRO_RECORD_STOP" -> {
                    val macroJson = macroEngine.stopRecording()
                    broadcastJson(JSONObject().apply {
                        put("type", "macro_status")
                        put("isRecording", false)
                        put("macroJson", macroJson)
                    })
                }

                // 15. Deep AI Reasoning Trigger
                "TRIGGER_REASONING", "REASON" -> {
                    val prompt = json.optString("prompt", "Analyze screen context")
                    eventListener?.onRemoteReasoningTriggered(prompt)
                }

                else -> {
                    Log.d(TAG, "Unhandled action: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling inbound JSON payload: ${e.message}", e)
        }
    }

    /**
     * Handles `SCAN_OCR`: Uses [StateDiffEngine] to check if screen state is static.
     * If screen has not changed, returns cached OCR detection to conserve battery/thermal budget.
     * Otherwise, runs [SpatialVisionEngine] and caches result.
     */
    private fun handleOcrScanRequest(conn: WebSocket) {
        val bitmap = latestFrameProvider() ?: run {
            val errorJson = JSONObject().apply {
                put("type", "OCR_ERROR")
                put("message", "No active screen capture frame available.")
            }
            conn.send(errorJson.toString())
            return
        }

        serverScope.launch {
            try {
                val previousBmp = lastOcrBitmap
                val hasChanged = StateDiffEngine.hasScreenChanged(previousBmp, bitmap)

                val (recognizedBlocks, inferenceLatencyMs) = if (!hasChanged && cachedOcrBlocks.isNotEmpty()) {
                    Log.d(TAG, "Screen unchanged (StateDiff < threshold). Returning cached OCR blocks (${cachedOcrBlocks.size}).")
                    Pair(cachedOcrBlocks, cachedOcrLatencyMs)
                } else {
                    val result = spatialVisionEngine.processFrame(bitmap)
                    cachedOcrBlocks = result.first
                    cachedOcrLatencyMs = result.second

                    // Store thumbnail for future state difference checks
                    try {
                        val old = lastOcrBitmap
                        lastOcrBitmap = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
                        if (old != null && !old.isRecycled && old != lastOcrBitmap) {
                            old.recycle()
                        }
                    } catch (ignored: Exception) {}

                    result
                }

                engineTelemetry.setInferenceLatencyMs(inferenceLatencyMs)

                val blocksArray = JSONArray()
                for (block in recognizedBlocks) {
                    val blockObj = JSONObject().apply {
                        put("id", block.id)
                        put("text", block.text)
                        put("elementType", block.elementType.name)
                        put("isSensitive", block.isSensitive)

                        val boundsObj = JSONObject().apply {
                            put("normLeft", block.bounds.normLeft)
                            put("normTop", block.bounds.normTop)
                            put("normRight", block.bounds.normRight)
                            put("normBottom", block.bounds.normBottom)
                        }
                        put("bounds", boundsObj)

                        val centroidObj = JSONObject().apply {
                            put("x", block.centroid.x)
                            put("y", block.centroid.y)
                        }
                        put("centroid", centroidObj)

                        val rawRectObj = JSONObject().apply {
                            put("left", block.rawRect.left)
                            put("top", block.rawRect.top)
                            put("right", block.rawRect.right)
                            put("bottom", block.rawRect.bottom)
                        }
                        put("rawRect", rawRectObj)
                    }
                    blocksArray.put(blockObj)
                }

                val responseJson = JSONObject().apply {
                    put("type", "OCR_DETECTION")
                    put("action", "SCAN_OCR")
                    put("inferenceMs", inferenceLatencyMs)
                    put("blockCount", recognizedBlocks.size)
                    put("isCached", !hasChanged)
                    put("blocks", blocksArray)
                    put("timestamp", System.currentTimeMillis())
                }

                conn.send(responseJson.toString())
                eventListener?.onOcrCompleted(
                    recognizedBlocks.joinToString("\n") { it.text },
                    recognizedBlocks.size
                )
                Log.i(TAG, "OCR_DETECTION returned ${recognizedBlocks.size} blocks (Cached: ${!hasChanged}) in ${inferenceLatencyMs}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SCAN_OCR: ${e.message}", e)
                val errorJson = JSONObject().apply {
                    put("type", "OCR_ERROR")
                    put("message", e.message ?: "OCR execution failure")
                }
                conn.send(errorJson.toString())
            }
        }
    }

    /**
     * Handles `GET_SEMANTIC_TREE`: Captures accessibility tree and fuses with ML Kit OCR.
     */
    private fun handleGetSemanticTreeRequest(conn: WebSocket) {
        serverScope.launch {
            try {
                val a11yTree = SemanticNodeFusion.captureSemanticTree()
                val bitmap = latestFrameProvider()

                val fusedElements = if (bitmap != null) {
                    val (ocrBlocks, _) = spatialVisionEngine.processFrame(bitmap)
                    SemanticNodeFusion.fuse(ocrBlocks, a11yTree)
                } else if (cachedOcrBlocks.isNotEmpty()) {
                    SemanticNodeFusion.fuse(cachedOcrBlocks, a11yTree)
                } else {
                    a11yTree
                }

                val responseJson = JSONObject().apply {
                    put("type", "SEMANTIC_TREE")
                    put("action", "GET_SEMANTIC_TREE")
                    put("elementCount", fusedElements.size)
                    put("elements", SemanticNodeFusion.toJsonArray(fusedElements))
                    put("timestamp", System.currentTimeMillis())
                }

                conn.send(responseJson.toString())
                Log.i(TAG, "GET_SEMANTIC_TREE returned ${fusedElements.size} fused semantic elements")
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting semantic tree: ${e.message}", e)
                val errorJson = JSONObject().apply {
                    put("type", "SEMANTIC_TREE_ERROR")
                    put("message", e.message ?: "Failed to extract semantic tree")
                }
                conn.send(errorJson.toString())
            }
        }
    }

    /**
     * Handles `EXECUTE_PLAN`: Parses multi-step plan steps and triggers [SpatialPlanExecutor].
     */
    private fun handleExecutePlanRequest(conn: WebSocket, json: JSONObject) {
        serverScope.launch {
            try {
                val stepsArray = when {
                    json.has("steps") -> json.getJSONArray("steps")
                    json.has("plan") -> json.getJSONArray("plan")
                    else -> JSONArray()
                }

                val planSteps = mutableListOf<SpatialPlanExecutor.PlanStep>()
                for (i in 0 until stepsArray.length()) {
                    val stepObj = stepsArray.getJSONObject(i)
                    planSteps.add(SpatialPlanExecutor.PlanStep.fromJson(stepObj))
                }

                if (planSteps.isEmpty()) {
                    val errorJson = JSONObject().apply {
                        put("type", "PLAN_ERROR")
                        put("message", "No plan steps provided.")
                    }
                    conn.send(errorJson.toString())
                    return@launch
                }

                val executionResults = spatialPlanExecutor.executePlan(planSteps)
                val allSuccessful = executionResults.all { it.success }

                val responseJson = JSONObject().apply {
                    put("type", "PLAN_EXECUTION_RESULT")
                    put("action", "EXECUTE_PLAN")
                    put("totalSteps", planSteps.size)
                    put("executedSteps", executionResults.size)
                    put("success", allSuccessful)
                    put("results", spatialPlanExecutor.toJsonArray(executionResults))
                    put("timestamp", System.currentTimeMillis())
                }

                conn.send(responseJson.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error executing spatial plan: ${e.message}", e)
                val errorJson = JSONObject().apply {
                    put("type", "PLAN_ERROR")
                    put("message", e.message ?: "Plan execution error")
                }
                conn.send(errorJson.toString())
            }
        }
    }

    /**
     * Handles `STORE_SECURE_TOKEN`: Stores credential securely in [ZenithSecureVault].
     */
    private fun handleStoreSecureTokenRequest(conn: WebSocket, json: JSONObject) {
        val key = json.optString("key", "")
        val secret = when {
            json.has("secret") -> json.getString("secret")
            json.has("value") -> json.getString("value")
            json.has("token") -> json.getString("token")
            else -> ""
        }

        if (key.isBlank() || secret.isBlank()) {
            val errorJson = JSONObject().apply {
                put("type", "SECURE_VAULT_ERROR")
                put("message", "Key and secret value cannot be blank.")
            }
            conn.send(errorJson.toString())
            return
        }

        zenithSecureVault.storeCredential(key, secret)

        val responseJson = JSONObject().apply {
            put("type", "SECURE_VAULT_RESPONSE")
            put("action", "STORE_SECURE_TOKEN")
            put("key", key)
            put("status", "STORED_SUCCESSFULLY")
            put("timestamp", System.currentTimeMillis())
        }
        conn.send(responseJson.toString())
    }

    /**
     * Handles `INJECT_SECURE_TOKEN`: Decrypts and injects token directly into Accessibility input.
     */
    private fun handleInjectSecureTokenRequest(conn: WebSocket, json: JSONObject) {
        val targetNodeId = when {
            json.has("targetNodeId") -> json.getString("targetNodeId")
            json.has("targetId") -> json.getString("targetId")
            json.has("target") -> json.getString("target")
            else -> ""
        }

        val credentialKey = when {
            json.has("credentialKey") -> json.getString("credentialKey")
            json.has("key") -> json.getString("key")
            else -> ""
        }

        val success = zenithSecureVault.injectCredentialToField(targetNodeId, credentialKey)

        val responseJson = JSONObject().apply {
            put("type", "SECURE_VAULT_RESPONSE")
            put("action", "INJECT_SECURE_TOKEN")
            put("targetId", targetNodeId)
            put("key", credentialKey)
            put("success", success)
            put("status", if (success) "INJECTED_SUCCESSFULLY" else "INJECTION_FAILED")
            put("timestamp", System.currentTimeMillis())
        }
        conn.send(responseJson.toString())
    }

    /**
     * Handles `PLAY_MACRO`: Executes [SelfHealingMacroEngine] against the current frame.
     */
    private fun handlePlayMacroRequest(conn: WebSocket, json: JSONObject) {
        val targetText = json.optString("targetText", "")
        val fallbackX = json.optDouble("x", json.optDouble("fallbackX", json.optDouble("fallbackXRatio", 0.5))).toFloat()
        val fallbackY = json.optDouble("y", json.optDouble("fallbackY", json.optDouble("fallbackYRatio", 0.5))).toFloat()

        val bitmap = latestFrameProvider() ?: run {
            // If no frame is available, fallback directly to coordinates
            val targetX = fallbackX * screenWidth
            val targetY = fallbackY * screenHeight
            ZenithAccessibilityService.instance?.dispatchTap(targetX, targetY)

            val fallbackResponse = JSONObject().apply {
                put("type", "MACRO_RESULT")
                put("action", "PLAY_MACRO")
                put("targetText", targetText)
                put("status", SelfHealingMacroEngine.RESULT_FALLBACK)
                put("timestamp", System.currentTimeMillis())
            }
            conn.send(fallbackResponse.toString())
            return
        }

        serverScope.launch {
            try {
                val action = SelfHealingMacroEngine.MacroAction(
                    targetText = targetText,
                    fallbackXRatio = fallbackX,
                    fallbackYRatio = fallbackY
                )
                val resultStatus = selfHealingMacroEngine.executeAction(
                    action = action,
                    currentBitmap = bitmap,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                )

                val responseJson = JSONObject().apply {
                    put("type", "MACRO_RESULT")
                    put("action", "PLAY_MACRO")
                    put("targetText", targetText)
                    put("status", resultStatus)
                    put("timestamp", System.currentTimeMillis())
                }
                conn.send(responseJson.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error executing self-healing macro: ${e.message}", e)
                val errorJson = JSONObject().apply {
                    put("type", "MACRO_ERROR")
                    put("message", e.message ?: "Macro execution failed")
                }
                conn.send(errorJson.toString())
            }
        }
    }

    /**
     * Broadcasts a compressed JPEG ByteArray frame to all connected WebSocket clients.
     */
    fun broadcastFrame(jpegBytes: ByteArray) {
        if (connectedClients.isEmpty()) return

        try {
            broadcast(jpegBytes)
            engineTelemetry.recordFrameDispatch()
        } catch (e: Exception) {
            Log.w(TAG, "Error broadcasting frame: ${e.message}")
        }
    }

    /**
     * Redacts, compresses, and streams a bitmap frame as binary JPEG over WebSocket,
     * recycling temporary intermediate bitmaps immediately to prevent memory leaks.
     */
    fun sendRedactedFrame(
        sourceBitmap: Bitmap,
        blocks: List<SpatialVisionEngine.RecognizedBlock> = emptyList(),
        quality: Int = 65
    ) {
        if (connectedClients.isEmpty()) return

        val redactedBitmap = if (blocks.any { it.isSensitive }) {
            PrivacyRedactor.redactBitmap(sourceBitmap, blocks)
        } else {
            sourceBitmap
        }

        val outputStream = ByteArrayOutputStream(128 * 1024)
        try {
            redactedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val jpegBytes = outputStream.toByteArray()
            broadcastFrame(jpegBytes)
        } finally {
            if (redactedBitmap != sourceBitmap && !redactedBitmap.isRecycled) {
                redactedBitmap.recycle()
            }
            try {
                outputStream.close()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Broadcasts a JSON payload string to all connected WebSocket clients.
     */
    fun broadcastJson(json: JSONObject) {
        if (connectedClients.isEmpty()) return

        val message = json.toString()
        try {
            broadcast(message)
        } catch (e: Exception) {
            Log.w(TAG, "Error broadcasting JSON: ${e.message}")
        }
    }

    /**
     * Compatibility telemetry broadcaster.
     */
    fun broadcastTelemetry(
        fps: Float,
        npuLatencyMs: Float,
        detections: List<ZenithDetector.Detection>,
        logMessage: String? = null
    ) {
        engineTelemetry.setInferenceLatencyMs(npuLatencyMs.toLong())
        val json = engineTelemetry.collectTelemetryJson().apply {
            put("reportedFps", fps)
            put("detectionCount", detections.size)
            if (logMessage != null) put("logMessage", logMessage)
        }
        broadcastJson(json)
    }

    @Suppress("UNUSED_PARAMETER")
    fun recordMetrics(
        latencyMs: Long,
        npuMs: Float,
        detections: List<ZenithDetector.Detection> = emptyList()
    ) {
        engineTelemetry.setLatencyMs(latencyMs)
        engineTelemetry.setInferenceLatencyMs(npuMs.toLong())
    }

    fun getRollingFps(): Int = engineTelemetry.getRollingFps()

    fun stopServer() {
        close()
    }

    override fun close() {
        serverScope.cancel()
        engineTelemetry.close()
        spatialVisionEngine.close()

        val lastBmp = lastOcrBitmap
        if (lastBmp != null && !lastBmp.isRecycled) {
            lastBmp.recycle()
            lastOcrBitmap = null
        }

        try {
            stop(1000)
            connectedClients.clear()
            Log.i(TAG, "ZenithStreamServer stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ZenithStreamServer: ${e.message}", e)
        }
    }
}
