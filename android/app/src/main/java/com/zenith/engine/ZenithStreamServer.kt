package com.zenith.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ZenithStreamServer: Embedded Ktor CIO Streaming & Remote Command Server.
 *
 * Capabilities:
 * 1. Hosts the desktop Web Command Center (`/`) loaded directly from APK assets (`web/index.html`).
 * 2. Broadcasts ultra-low latency JPEG keyframe streams and JSON telemetry packets over WebSockets (`/stream`).
 * 3. Handles bidirectional inbound touch injection and clipboard synchronization via (`/control` and `/stream`).
 */
class ZenithStreamServer(
    val context: Context,
    val port: Int = 8080
) : AutoCloseable {

    companion object {
        private const val TAG = "ZenithStreamServer"
    }

    interface ServerEventListener {
        fun onRemoteTouchReceived(normalizedX: Float, normalizedY: Float)
        fun onRemoteClipboardReceived(text: String)
        fun onRemoteReasoningTriggered(prompt: String)
    }

    var eventListener: ServerEventListener? = null

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ktorServer: ApplicationEngine? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Thread-safe set of connected WebSocket stream sessions
    private val streamSessions = Collections.newSetFromMap(ConcurrentHashMap<WebSocketSession, Boolean>())
    // Thread-safe set of connected WebSocket control sessions
    private val controlSessions = Collections.newSetFromMap(ConcurrentHashMap<WebSocketSession, Boolean>())

    fun start() {
        if (ktorServer != null) return

        try {
            ktorServer = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                install(WebSockets)

                routing {
                    // 1. Serve Web Command Center Index Dashboard
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

                    // 2. Stream WebSocket Endpoint: Dispatches binary frames & handles dashboard interactions
                    webSocket("/stream") {
                        streamSessions.add(this)
                        Log.i(TAG, "Web client connected to /stream. Total: ${streamSessions.size}")

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    handleInboundPayload(text)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "WebSocket /stream session error: ${e.message}")
                        } finally {
                            streamSessions.remove(this)
                            Log.i(TAG, "Web client disconnected from /stream. Total: ${streamSessions.size}")
                        }
                    }

                    // 3. Dedicated Remote Control WebSocket Endpoint
                    webSocket("/control") {
                        controlSessions.add(this)
                        Log.i(TAG, "Web client connected to /control. Total: ${controlSessions.size}")

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    handleInboundPayload(text)
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
            if (logMessage != null) {
                put("logMessage", logMessage)
            }

            val detArray = org.json.JSONArray()
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
     * Processes inbound JSON command frames from either /control or /stream.
     */
    private fun handleInboundPayload(payloadText: String) {
        try {
            val json = JSONObject(payloadText)
            val action = if (json.has("action")) json.optString("action") else json.optString("type")

            when (action.lowercase()) {
                "tap", "touch" -> {
                    val normX = json.optDouble("x", 0.0).toFloat()
                    val normY = json.optDouble("y", 0.0).toFloat()

                    // Execute touch via Accessibility Service
                    ZenithAccessibilityService.performTap(normX, normY)
                    eventListener?.onRemoteTouchReceived(normX, normY)
                    Log.d(TAG, "Inbound remote tap dispatched at ($normX, $normY)")
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

                "trigger_reasoning", "reason" -> {
                    val prompt = json.optString("prompt", "Analyze screen context")
                    eventListener?.onRemoteReasoningTriggered(prompt)
                    Log.i(TAG, "Inbound reasoning command triggered: $prompt")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling inbound JSON payload: ${e.message}", e)
        }
    }

    fun stop() {
        close()
    }

    override fun close() {
        serverScope.cancel()
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
