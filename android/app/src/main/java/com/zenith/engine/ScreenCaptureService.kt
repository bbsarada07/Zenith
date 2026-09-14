package com.zenith.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ScreenCaptureService: High-Throughput Android 14+ Foreground Service for Real-Time Game HUD Analysis.
 *
 * Key System-Level Guarantees:
 * 1. Touch Pass-Through: Uses WRAP_CONTENT dimensions and strict non-modal flags
 *    (FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_NOT_TOUCH_MODAL) without full-screen layout masks
 *    to guarantee underlying touches are never blocked.
 * 2. VirtualDisplay Optimization: Configured with DISPLAY_FLAG_PUBLIC to prevent Hardware Composer (HWC)
 *    GPU starvation across external applications.
 * 3. Strict 10 FPS Frame Throttling & Buffer Queue Flushing: Paces ingestion to a 100ms interval and
 *    immediately acquires & closes dropped frames in try-finally blocks to avoid buffer exhaustion.
 * 4. Thread Isolation: Model inference runs synchronously on the dedicated HandlerThread, while ONLY
 *    overlay UI mutations are posted to the Main UI Thread.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ZenithCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "zenith_npu_pipeline"
        private const val NOTIFICATION_ID = 9001

        const val ACTION_START = "com.zenith.engine.action.START_CAPTURE"
        const val ACTION_STOP = "com.zenith.engine.action.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "DATA_INTENT"

        const val ACTION_DETECTIONS_BROADCAST = "com.zenith.engine.DETECTIONS_UPDATED"
        const val EXTRA_DETECTION_COUNT = "extra_detection_count"

        // Frame Throttling Configuration: 10 FPS Max -> 100ms min interval
        private const val TARGET_MAX_FPS = 10
        private const val MIN_FRAME_INTERVAL_MS = 1000L / TARGET_MAX_FPS

        private val _detectionsFlow = MutableSharedFlow<List<ZenithDetector.Detection>>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val detectionsFlow: SharedFlow<List<ZenithDetector.Detection>> = _detectionsFlow.asSharedFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var imageReaderThread: HandlerThread? = null
    private var imageReaderHandler: Handler? = null

    private var zenithDetector: ZenithDetector? = null
    private var overlayHudView: OverlayHudView? = null

    private val isInferring = AtomicBoolean(false)
    private val lastFrameTimestampMs = AtomicLong(0L)

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection session stopped by system or user.")
            stopCapture()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        imageReaderThread = HandlerThread("ZenithImageReaderThread", android.os.Process.THREAD_PRIORITY_DISPLAY).apply {
            start()
            imageReaderHandler = Handler(looper)
        }

        try {
            zenithDetector = ZenithDetector(applicationContext, "yolov8n_int8_qnn.onnx")
            Log.i(TAG, "ZenithDetector initialized inside Foreground Service.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ZenithDetector: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        startForegroundServiceWithNotification()

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
            Log.d(TAG, "MediaProjection token received. Starting Virtual Display...")
            startCapture(resultCode, dataIntent)
        } else {
            Log.e(TAG, "Invalid MediaProjection result code or intent data.")
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
            .setContentTitle("Zenith Engine Active")
            .setContentText("Hardware NPU Game HUD streaming at 640x640")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null) {
            Log.w(TAG, "Capture pipeline already active.")
            return
        }

        val projection = mediaProjectionManager?.getMediaProjection(resultCode, resultData) ?: run {
            Log.e(TAG, "Failed to obtain MediaProjection token from system.")
            return
        }
        mediaProjection = projection

        projection.registerCallback(mediaProjectionCallback, imageReaderHandler)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenDensity = metrics.densityDpi

        val reader = ImageReader.newInstance(
            ZenithDetector.INPUT_WIDTH,
            ZenithDetector.INPUT_HEIGHT,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader

        reader.setOnImageAvailableListener({ imageReaderInstance ->
            onImageAvailable(imageReaderInstance)
        }, imageReaderHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "ZenithNpuVirtualDisplay",
            ZenithDetector.INPUT_WIDTH,
            ZenithDetector.INPUT_HEIGHT,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            reader.surface,
            null,
            imageReaderHandler
        )

        Log.i(TAG, "MediaProjection VirtualDisplay established [640x640@${screenDensity}dpi].")

        try {
            if (overlayHudView == null) {
                val hud = OverlayHudView(applicationContext)
                val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                // Touch pass-through layout parameters: WRAP_CONTENT with non-focus, non-touch, and non-touch-modal flags
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                windowManager.addView(hud, params)
                overlayHudView = hud
                Log.i(TAG, "OverlayHudView successfully displayed with touch pass-through enabled.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach OverlayHudView: ${e.message}", e)
        }
    }

    /**
     * Synchronous Ingestion Loop:
     * - Throttles capture rate to max 10 FPS (100ms minimum interval).
     * - Discards early / excess frames immediately inside try-finally to flush ImageReader queue.
     * - Executes inference synchronously on dedicated imageReaderHandler thread.
     * - Posts ONLY OverlayHudView detection updates to the main thread.
     */
    private fun onImageAvailable(reader: ImageReader) {
        val currentTimeMs = SystemClock.elapsedRealtime()
        val lastTimeMs = lastFrameTimestampMs.get()

        // 1. Dynamic Frame Throttling (10 FPS Limit / 100ms interval)
        if (currentTimeMs - lastTimeMs < MIN_FRAME_INTERVAL_MS) {
            val droppedImage = reader.acquireLatestImage() ?: return
            try {
                // Flush buffer immediately to enforce 10 FPS limit
            } finally {
                droppedImage.close()
            }
            return
        }

        // 2. Acquire latest image buffer
        val image = reader.acquireLatestImage() ?: return

        // 3. Drop frame if previous NPU/detector inference cycle is still executing
        if (!isInferring.compareAndSet(false, true)) {
            try {
                // Flush buffer immediately to prevent queue buildup during active inference
            } finally {
                image.close()
            }
            return
        }

        // Record timestamp for frame pacing
        lastFrameTimestampMs.set(currentTimeMs)

        try {
            val detector = zenithDetector
            if (detector != null && image.planes.isNotEmpty()) {
                val plane = image.planes[0]
                val buffer = plane.buffer

                // Run inference synchronously on the dedicated HandlerThread
                val detections = detector.detectImagePlane(
                    planeBuffer = buffer,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    width = image.width,
                    height = image.height
                )

                // Emit flow to background coroutine scope
                serviceScope.launch {
                    _detectionsFlow.emit(detections)
                }

                // Update UI overlay view strictly on the Main UI Thread
                mainHandler.post {
                    overlayHudView?.updateDetections(detections)
                }

                // Broadcast results
                val intent = Intent(ACTION_DETECTIONS_BROADCAST).apply {
                    putExtra(EXTRA_DETECTION_COUNT, detections.size)
                }
                sendBroadcast(intent)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Inference pipeline error: ${e.message}", e)
        } finally {
            // ALWAYS release image buffer synchronously before exiting callback
            image.close()
            isInferring.set(false)
        }
    }

    private fun stopCapture() {
        overlayHudView?.let { hud ->
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

        Log.i(TAG, "Capture pipeline stopped.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Zenith Engine NPU Inference",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background frame capture and hardware acceleration stream"
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

        imageReaderThread?.quitSafely()
        imageReaderThread = null
        imageReaderHandler = null

        zenithDetector?.close()
        zenithDetector = null
        Log.i(TAG, "ScreenCaptureService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}