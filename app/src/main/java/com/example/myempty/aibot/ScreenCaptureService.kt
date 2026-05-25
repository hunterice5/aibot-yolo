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

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", lastResultCode) ?: lastResultCode
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("RESULT_DATA", Intent::class.java) ?: lastResultData
        } else {
            @Suppress("DEPRECATION")
            (intent?.getParcelableExtra("RESULT_DATA") ?: lastResultData)
        }

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

        var lastResultCode: Int = android.app.Activity.RESULT_CANCELED
        var lastResultData: Intent? = null

        @Volatile var targetModelSize: Int = 640 // Default to high res for Valo
        /** User-adjustable capture base resolution (128-640, default=320) */
        @Volatile var captureBaseSize: Int = 320
        @Volatile var realDisplayW: Int = 0
        @Volatile var realDisplayH: Int = 0
        @Volatile var letterboxOffsetX: Int = 0
        @Volatile var letterboxOffsetY: Int = 0
        @Volatile var letterboxScale: Float = 1.0f
    }

    private fun getRealDisplaySize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val realSize = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(realSize)
        return Pair(realSize.x, realSize.y)
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        
        if (mediaProjection == null) {
            isCapturing = false
            return
        }

        isCapturing = true
        val (realW, realH) = getRealDisplaySize()
        realDisplayW = realW; realDisplayH = realH
        val density = resources.displayMetrics.densityDpi

        // Match capture resolution to model resolution for maximum clarity!
        val capW = targetModelSize
        val capH = (realH * targetModelSize / realW)
        
        Log.i(TAG, "Capture High-Res: ${capW}x${capH} (Matched to Model: $targetModelSize)")

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                frameCount++
                val now = System.currentTimeMillis()

                val bmp = imageToScaledBitmap(image, targetModelSize)
                if (bmp != null) frameListener?.invoke(bmp)

                if (now - lastTime >= 1000) {
                    frameCount = 0; lastTime = now; lastFpsTime = now
                }
                image.close()
            }
        }, bgHandler)

        // Mandatory callback for Android 14+ to manage resources
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection Stopped")
                stopCapture()
            }
        }, bgHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AimbotDisplay", capW, capH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun stopCapture() {
        if (isStopping) return
        isStopping = true; isCapturing = false
        try {
            frameListener = null
            virtualDisplay?.release(); virtualDisplay = null
            imageReader?.close(); imageReader = null
            mediaProjection?.stop(); mediaProjection = null
        } finally { isStopping = false }
    }

    private var fullFrameBitmap: Bitmap? = null
    private var targetSizeBitmap: Bitmap? = null
    private var rowByteArray: ByteArray? = null

    private fun imageToScaledBitmap(image: Image, targetSize: Int): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val rowStride = planes[0].rowStride
            val pixelStride = planes[0].pixelStride
            val srcW = image.width
            val srcH = image.height

            var fullBmp = fullFrameBitmap
            if (fullBmp == null || fullBmp.width != srcW || fullBmp.height != srcH) {
                fullBmp = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
                fullFrameBitmap = fullBmp
            }
            
            buffer.rewind()
            if (pixelStride == 4 && rowStride == srcW * 4) {
                fullBmp!!.copyPixelsFromBuffer(buffer)
            } else {
                val bytesPerRow = srcW * 4
                if (rowByteArray == null || rowByteArray!!.size < bytesPerRow) rowByteArray = ByteArray(bytesPerRow)
                val cleanBuffer = java.nio.ByteBuffer.allocateDirect(srcH * bytesPerRow)
                for (row in 0 until srcH) {
                    buffer.position(row * rowStride)
                    buffer.get(rowByteArray!!, 0, bytesPerRow)
                    cleanBuffer.put(rowByteArray!!, 0, bytesPerRow)
                }
                cleanBuffer.rewind()
                fullBmp!!.copyPixelsFromBuffer(cleanBuffer)
            }

            // 1. Calculate the mapping from REAL screen to TARGET model size
            val captureScale = srcW.toFloat() / realDisplayW
            val innerScale = minOf(targetSize.toFloat() / srcW, targetSize.toFloat() / srcH)
            val totalScale = captureScale * innerScale

            val scaledW = (srcW * innerScale).toInt()
            val scaledH = (srcH * innerScale).toInt()
            
            // 2. Centering offsets in the target model canvas (e.g. 256x256)
            val offsetX = (targetSize - scaledW) / 2
            val offsetY = (targetSize - scaledH) / 2

            // Store for engine: these are used to map model coords back to screen
            // Formula: ScreenX = (ModelX - offsetX) / totalScale
            letterboxOffsetX = offsetX
            letterboxOffsetY = offsetY
            letterboxScale = totalScale

            var targetBmp = targetSizeBitmap
            if (targetBmp == null || targetBmp.width != targetSize || targetBmp.height != targetSize) {
                targetBmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                targetSizeBitmap = targetBmp
            }
            
            val canvas = android.graphics.Canvas(targetBmp!!)
            canvas.drawColor(android.graphics.Color.BLACK) // Letterbox padding color
            
            val matrix = android.graphics.Matrix()
            // Matrix to draw full screen into the letterboxed area
            matrix.postScale(innerScale, innerScale)
            matrix.postTranslate(offsetX.toFloat(), offsetY.toFloat())
            
            canvas.drawBitmap(fullBmp!!, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            targetBmp
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        stopCapture(); stopForeground(STOP_FOREGROUND_REMOVE); bgThread.quitSafely(); super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("ScreenCaptureChannel", "Screen Capture", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
