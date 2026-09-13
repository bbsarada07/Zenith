package com.zenith.engine

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * OfficeKitBridge: Embedded High-Throughput WebSocket Telemetry Server.
 *
 * Streams real-time esports telemetry directly from iQOO device to PC/Laptop secondary monitors
 * over local Wi-Fi / USB reverse tethering (port 8080).
 *
 * Payload features:
 * - Real-time FPS & frame pacing delta
 * - Qualcomm Hexagon NPU inference latency
 * - Snapdragon CPU/GPU thermal status
 * - Live bounding boxes / target detection coordinates
 */
class OfficeKitBridge(
    private val context: Context,
    private val port: Int = 8080
) : AutoCloseable {

    companion object {
        private const val TAG = "ZenithOfficeKitBridge"
    }

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverInstance: InternalWebSocketServer? = null

    init {
        initializeServer()
    }

    private fun initializeServer() {
        try {
            val address = InetSocketAddress("0.0.0.0", port)
            serverInstance = InternalWebSocketServer(address).apply {
                isReuseAddr = true
                isTcpNoDelay = true // Disable Nagle's algorithm for sub-millisecond packet dispatch
                start()
            }
            val ip = getLocalIpAddress(context)
            Log.i(TAG, "OfficeKitBridge WebSocket Server started on ws://$ip:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OfficeKitBridge server: ${e.message}", e)
        }
    }

    /**
     * Broadcasts a telemetry snapshot to all connected laptop/dashboard clients.
     */
    fun broadcastTelemetry(
        fps: Float,
        npuLatencyMs: Float,
        thermalStatus: String,
        batteryTemp: Float,
        detections: List<ZenithDetector.Detection>
    ) {
        val server = serverInstance ?: return
        if (server.connections.isEmpty()) return

        bridgeScope.launch {
            try {
                val jsonPayload = buildJsonPayload(fps, npuLatencyMs, thermalStatus, batteryTemp, detections)
                server.broadcast(jsonPayload)
            } catch (e: Exception) {
                Log.w(TAG, "Telemetry broadcast failed: ${e.message}")
            }
        }
    }

    private fun buildJsonPayload(
        fps: Float,
        npuLatencyMs: Float,
        thermalStatus: String,
        batteryTemp: Float,
        detections: List<ZenithDetector.Detection>
    ): String {
        val sb = StringBuilder(512)
        sb.append("{")
        sb.append("\"timestamp\":").append(System.currentTimeMillis()).append(",")
        sb.append("\"fps\":").append(String.format("%.1f", fps)).append(",")
        sb.append("\"npuLatencyMs\":").append(String.format("%.2f", npuLatencyMs)).append(",")
        sb.append("\"thermalStatus\":\"").append(thermalStatus).append("\",")
        sb.append("\"batteryTemp\":").append(String.format("%.1f", batteryTemp)).append(",")
        sb.append("\"detections\":[")

        for (i in detections.indices) {
            val d = detections[i]
            if (i > 0) sb.append(",")
            sb.append("{")
            sb.append("\"x1\":").append(String.format("%.1f", d.x1)).append(",")
            sb.append("\"y1\":").append(String.format("%.1f", d.y1)).append(",")
            sb.append("\"x2\":").append(String.format("%.1f", d.x2)).append(",")
            sb.append("\"y2\":").append(String.format("%.1f", d.y2)).append(",")
            sb.append("\"score\":").append(String.format("%.3f", d.score)).append(",")
            sb.append("\"classId\":").append(d.classId)
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * Resolves the primary local IPv4 address (Wi-Fi or Hotspot interface).
     */
    fun getLocalIpAddress(context: Context): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.contains("wlan") || intf.name.contains("ap") || intf.name.contains("rndis")) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}
        return "127.0.0.1"
    }

    override fun close() {
        bridgeScope.cancel()
        try {
            serverInstance?.stop(1000)
            serverInstance = null
            Log.i(TAG, "OfficeKitBridge stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket server", e)
        }
    }

    private inner class InternalWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
            Log.i(TAG, "Laptop Dashboard connected: ${conn?.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
            Log.i(TAG, "Laptop Dashboard disconnected: ${conn?.remoteSocketAddress}")
        }

        override fun onMessage(conn: WebSocket?, message: String?) {
            // Optional bidirectional telemetry commands from dashboard (e.g. tuning NMS threshold)
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            Log.w(TAG, "WebSocket error: ${ex?.message}")
        }

        override fun onStart() {
            Log.i(TAG, "WebSocket server worker initialized.")
        }
    }
}
