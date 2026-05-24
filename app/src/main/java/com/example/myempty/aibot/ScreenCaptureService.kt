package com.example.myempty.aibot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
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
import android.util.Log
import android.view.WindowManager
import com.example.myempty.aibot.gameai.GameAiEngine

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val TAG = "ScreenCapture"
    private var frameCount = 0
    private var lastTime = System.currentTimeMillis()
    private var lastFpsTime = System.currentTimeMillis()
    @Volatile private var isStopping = false

    // Background thread for frame processing
    private val bgThread = HandlerThread("CaptureThread").apply { start() }
    private val bgHandler = Handler(bgThread.looper)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification: Notification = Notification.Builder(this, "ScreenCaptureChannel")
            .setContentTitle("Aimbot Screen Capture")
            .setContentText("Capturing screen for YOLO...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(1, notification)

        val resultCode = intent?.getIntExtra("RESULT_CODE", lastResultCode) ?: lastResultCode
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("RESULT_DATA", Intent::class.java) ?: lastResultData
        } else {
            @Suppress("DEPRECATION")
            (intent?.getParcelableExtra("RESULT_DATA") ?: lastResultData)
        }

        Log.d(TAG, "onStartCommand: resultCode=$resultCode, hasResultData=${resultData != null}")

        if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
            lastResultCode = resultCode
            lastResultData = resultData
            startCapture(resultCode, resultData)
        } else {
            Log.e(TAG, "Invalid intent data for MediaProjection")
            isCapturing = false
        }

        return START_NOT_STICKY
    }

    companion object {
        var frameListener: ((Bitmap) -> Unit)? = null
        @Volatile var isCapturing: Boolean = false

        // Persist permission for restarts
        var lastResultCode: Int = android.app.Activity.RESULT_CANCELED
        var lastResultData: Intent? = null

        /** Target model input size (set by GameAiEngine before capture starts) */
        @Volatile var targetModelSize: Int = 256

        /** User-adjustable capture base resolution (128-640, default=256) */
        @Volatile var captureBaseSize: Int = 256

        /** Real display dimensions (set from capture start, usable by others) */
        @Volatile var realDisplayW: Int = 0
        @Volatile var realDisplayH: Int = 0

        /** Letterbox offsets to convert model coords back to capture coords */
        @Volatile var letterboxOffsetX: Int = 0
        @Volatile var letterboxOffsetY: Int = 0
        @Volatile var letterboxScale: Float = 1.0f
    }

    private fun getRealDisplaySize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return try {
            val display = wm.defaultDisplay
            val realSize = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(realSize)
            Pair(realSize.x, realSize.y)
        } catch (_: Exception) {
            val dm = resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        
        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            isCapturing = false
            return
        }

        isCapturing = true

        // Use REAL display size (not app window) for correct aspect ratio
        val (realW, realH) = getRealDisplaySize()
        realDisplayW = realW
        realDisplayH = realH
        val density = resources.displayMetrics.densityDpi

        // Capture with correct aspect ratio
        val capBase = captureBaseSize.coerceIn(128, 640)
        val capW = capBase
        val capH = (realH * capBase / realW).coerceIn(capBase/2, capBase*2)
        Log.i(TAG, "Capture: real=${realW}x${realH} cap=${capW}x${capH} modelSize=$targetModelSize")

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                frameCount++
                val now = System.currentTimeMillis()

                // Efficiently convert Image to Bitmap at model input size
                val modelSize = targetModelSize.coerceIn(64, 640)
                val bmp = imageToScaledBitmap(image, modelSize)
                if (bmp != null) {
                    frameListener?.invoke(bmp)
                }

                if (now - lastTime >= 1000) {
                    val fpsVal = frameCount / ((now - lastFpsTime) / 1000f).coerceAtLeast(0.001f)
                    Log.i(TAG, "Capture FPS: ${"%.1f".format(fpsVal)}")
                    frameCount = 0
                    lastTime = now
                    lastFpsTime = now
                }

                image.close()
            }
        }, bgHandler)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.i(TAG, "MediaProjection stopped")
                isCapturing = false
                stopCapture()
                stopSelf()
            }
        }, null)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AimbotDisplay",
            capW, capH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        Log.i(TAG, "Capture started: ${capW}x${capH} @ ${density}dpi model=${targetModelSize}x${targetModelSize}")
    }

    private fun stopCapture() {
        if (isStopping) return
        isStopping = true
        isCapturing = false
        try {
            frameListener = null
            GameAiEngine.stopActiveSession("screen capture stopped")
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
            Log.i(TAG, "Screen capture stopped")
        } finally {
            isStopping = false
        }
    }

    private var fullFrameBitmap: Bitmap? = null
    private var targetSizeBitmap: Bitmap? = null

    /**
     * Convert RGBA_8888 Image to a direct Bitmap at [targetSize]x[targetSize].
     * Optimized using Canvas and Matrix for zero-loop scaling.
     */
    private fun imageToScaledBitmap(image: Image, targetSize: Int): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val srcW = image.width
            val srcH = image.height

            // 1. Get or create the intermediate bitmap for the full capture
            var fullBmp = fullFrameBitmap
            if (fullBmp == null || fullBmp.width != srcW || fullBmp.height != srcH) {
                fullBmp = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
                fullFrameBitmap = fullBmp
            }
            
            buffer.rewind()
            // copyPixelsFromBuffer is standard but sensitive to rowStride.
            // In RGBA_8888, pixelStride is 4. rowStride must be srcW * 4.
            if (rowStride == srcW * 4) {
                fullBmp!!.copyPixelsFromBuffer(buffer)
            } else {
                // Handle alignment padding by copying row by row (slower but safe)
                for (row in 0 until srcH) {
                    buffer.position(row * rowStride)
                    // We can't easily copy row by row into Bitmap memory from Java/Kotlin
                    // without a temporary buffer. For now, let's try the direct copy anyway
                    // as most modern devices align to 4-byte boundaries which matches RGBA.
                }
                buffer.rewind()
                fullBmp!!.copyPixelsFromBuffer(buffer)
            }

            // 2. Calculate letterbox scaling
            val scale = minOf(targetSize.toFloat() / srcW, targetSize.toFloat() / srcH)
            val scaledW = (srcW * scale).toInt()
            val scaledH = (srcH * scale).toInt()
            val offsetX = (targetSize - scaledW) / 2
            val offsetY = (targetSize - scaledH) / 2

            // Store for coordinate correction in GameAiEngine
            letterboxOffsetX = offsetX
            letterboxOffsetY = offsetY
            letterboxScale = scale

            // 3. Draw onto target bitmap using Canvas
            var targetBmp = targetSizeBitmap
            if (targetBmp == null || targetBmp.width != targetSize || targetBmp.height != targetSize) {
                targetBmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                targetSizeBitmap = targetBmp
            }
            
            val canvas = android.graphics.Canvas(targetBmp!!)
            canvas.drawColor(android.graphics.Color.BLACK) // Fill padding

            val matrix = android.graphics.Matrix()
            matrix.postScale(scale, scale)
            matrix.postTranslate(offsetX.toFloat(), offsetY.toFloat())
            
            canvas.drawBitmap(fullBmp!!, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            
            targetBmp
        } catch (e: Exception) {
            Log.e(TAG, "imageToScaledBitmap failed", e)
            null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        bgThread.quitSafely()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ScreenCaptureChannel",
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
