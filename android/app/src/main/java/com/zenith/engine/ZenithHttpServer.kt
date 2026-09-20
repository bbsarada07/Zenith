package com.zenith.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * ZenithHttpServer: Embedded High-Performance Lightweight HTTP Server.
 * Serves the Zenith Spatial Co-Pilot Web Command Center (index.html)
 * directly from APK assets to any browser over Wi-Fi or USB ADB.
 */
class ZenithHttpServer(
    private val context: Context,
    private val port: Int = 8080
) : AutoCloseable {

    companion object {
        private const val TAG = "ZenithHttpServer"
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var isRunning = false

    private val cachedHtml: ByteArray by lazy {
        try {
            context.assets.open("web/index.html").use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read web/index.html from assets: ${e.message}", e)
            "<!DOCTYPE html><html><head><title>Zenith Engine</title></head><body style='background:#090d16;color:#00f3ff;font-family:sans-serif;padding:40px;text-align:center;'><h1>ZENITH ENGINE</h1><p>Web Command Center is initializing...</p></body></html>"
                .toByteArray(StandardCharsets.UTF_8)
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        serverJob = serverScope.launch {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "ZenithHttpServer listening on HTTP port $port")

                while (isActive && isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch(Dispatchers.IO) {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.w(TAG, "Http accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ZenithHttpServer on port $port: ${e.message}", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                if (reader.readLine() == null) return

                // Read and discard remaining request headers
                var line: String? = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    line = reader.readLine()
                }

                val out: OutputStream = s.getOutputStream()
                val responseBytes = cachedHtml

                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=UTF-8\r\n")
                    append("Content-Length: ").append(responseBytes.size).append("\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }.toByteArray(StandardCharsets.UTF_8)

                out.write(header)
                out.write(responseBytes)
                out.flush()
            }
        } catch (e: Exception) {
            // Socket timeout or client disconnected cleanly
        }
    }

    fun stop() {
        isRunning = false
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        Log.i(TAG, "ZenithHttpServer stopped.")
    }

    override fun close() {
        stop()
    }
}
