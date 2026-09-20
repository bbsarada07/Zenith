package com.zenith.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ZenithWebSocketClient: Production OkHttp WebSocket Client with Exponential Backoff & Heartbeat.
 *
 * Implements:
 * 1. Connection Lifecycle: Connects to Node.js Planner agent (`ws://192.168.1.3:8080` or dynamic host).
 * 2. Exponential Backoff Auto-Reconnect: Recovers automatically from network drops (1s -> 30s).
 * 3. 5-Second Heartbeat: Maintains socket keepalive via PING/PONG telemetry frames.
 * 4. Command Dispatching: Routes inbound DAG action commands to [ZenithAccessibilityService].
 * 5. Telemetry Transmission: Emits execution results, state hashes, and screenshot reflection payloads.
 */
class ZenithWebSocketClient(
    private var serverUrl: String = "ws://192.168.1.3:8080",
    private val commandListener: CommandListener
) {

    companion object {
        private const val TAG = "ZenithWSClient"
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val HEARTBEAT_INTERVAL_MS = 5000L
    }

    interface CommandListener {
        fun onCommandReceived(command: InboundCommand)
        fun onConnectionStatusChanged(isConnected: Boolean, statusMessage: String)
    }

    data class InboundCommand(
        val action: String, // "CLICK_TEXT", "CLICK_COORD", "SWIPE", "TYPE_TEXT", "INPUT_KEY", "REQUIRE_USER_CONFIRMATION"
        val target: String = "",
        val coords: Pair<Float, Float>? = null, // Physical or normalized coordinates
        val swipeEndCoords: Pair<Float, Float>? = null,
        val payload: String = "",
        val stepId: String = "",
        val rawJson: JSONObject
    )

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var activeWebSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isManualDisconnect = AtomicBoolean(false)
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

    init {
        startHeartbeatLoop()
    }

    fun updateServerUrl(url: String) {
        this.serverUrl = url
        disconnect()
        connect()
    }

    fun connect() {
        isManualDisconnect.set(false)
        if (isConnected.get()) return

        clientScope.launch {
            try {
                Log.i(TAG, "Connecting to Zenith Planner at $serverUrl...")
                commandListener.onConnectionStatusChanged(false, "CONNECTING: $serverUrl")

                val request = Request.Builder()
                    .url(serverUrl)
                    .build()

                activeWebSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initiate WebSocket connection: ${e.message}", e)
                scheduleReconnect()
            }
        }
    }

    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected.set(true)
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                Log.i(TAG, "Connected to Zenith Planner Server.")
                mainHandler.post {
                    commandListener.onConnectionStatusChanged(true, "CONNECTED: $serverUrl")
                }

                // Send registration / handshake packet
                val handshake = JSONObject().apply {
                    put("type", "AGENT_REGISTER")
                    put("agent", "ANDROID_EXECUTOR")
                    put("version", "2.0.0")
                    put("timestamp", System.currentTimeMillis())
                }
                sendJson(handshake)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleInboundMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: $code / $reason")
                isConnected.set(false)
                mainHandler.post {
                    commandListener.onConnectionStatusChanged(false, "CLOSING: $reason")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $code / $reason")
                isConnected.set(false)
                mainHandler.post {
                    commandListener.onConnectionStatusChanged(false, "DISCONNECTED")
                }
                if (!isManualDisconnect.get()) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnected.set(false)
                mainHandler.post {
                    commandListener.onConnectionStatusChanged(false, "ERROR: ${t.message}")
                }
                if (!isManualDisconnect.get()) {
                    scheduleReconnect()
                }
            }
        }
    }

    private fun handleInboundMessage(messageText: String) {
        try {
            val json = JSONObject(messageText)
            val action = json.optString("action", json.optString("type", "")).uppercase()

            if (action == "PONG") {
                // Heartbeat response
                return
            }

            var coords: Pair<Float, Float>? = null
            if (json.has("coords")) {
                val coordsArr = json.getJSONArray("coords")
                if (coordsArr.length() >= 2) {
                    coords = Pair(
                        coordsArr.getDouble(0).toFloat(),
                        coordsArr.getDouble(1).toFloat()
                    )
                }
            }

            var swipeEndCoords: Pair<Float, Float>? = null
            if (json.has("end_coords")) {
                val endArr = json.getJSONArray("end_coords")
                if (endArr.length() >= 2) {
                    swipeEndCoords = Pair(
                        endArr.getDouble(0).toFloat(),
                        endArr.getDouble(1).toFloat()
                    )
                }
            }

            val command = InboundCommand(
                action = action,
                target = json.optString("target", ""),
                coords = coords,
                swipeEndCoords = swipeEndCoords,
                payload = json.optString("payload", json.optString("text", "")),
                stepId = json.optString("step_id", json.optString("id", "")),
                rawJson = json
            )

            mainHandler.post {
                commandListener.onCommandReceived(command)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing inbound WebSocket message: ${e.message}", e)
        }
    }

    /**
     * Emits structured execution telemetry or reflection payloads to the Planner.
     */
    fun sendTelemetry(
        status: String,
        stepId: String,
        currentApp: String,
        nodeTreeHash: String,
        screenWidth: Int,
        screenHeight: Int,
        screenBase64: String? = null,
        errorMessage: String? = null
    ) {
        val json = JSONObject().apply {
            put("status", status)
            put("step_id", stepId)
            put("current_app", currentApp)
            put("node_tree_hash", nodeTreeHash)
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
            put("timestamp", System.currentTimeMillis())
            if (screenBase64 != null) {
                put("screen_base64", screenBase64)
            }
            if (errorMessage != null) {
                put("error_message", errorMessage)
            }
        }
        sendJson(json)
    }

    fun sendJson(json: JSONObject) {
        val payload = json.toString()
        val socket = activeWebSocket
        if (socket != null && isConnected.get()) {
            socket.send(payload)
        } else {
            Log.w(TAG, "Cannot send telemetry: WebSocket not connected.")
        }
    }

    private fun scheduleReconnect() {
        if (isManualDisconnect.get()) return
        clientScope.launch {
            Log.i(TAG, "Reconnecting in ${reconnectDelayMs}ms...")
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            connect()
        }
    }

    private fun startHeartbeatLoop() {
        clientScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (isConnected.get()) {
                    val ping = JSONObject().apply {
                        put("type", "PING")
                        put("timestamp", System.currentTimeMillis())
                    }
                    sendJson(ping)
                }
            }
        }
    }

    fun disconnect() {
        isManualDisconnect.set(true)
        isConnected.set(false)
        try {
            activeWebSocket?.close(1000, "Client disconnect")
            activeWebSocket = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket: ${e.message}")
        }
    }

    fun close() {
        disconnect()
        clientScope.cancel()
    }
}
