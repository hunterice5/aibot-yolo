package com.example.myempty.aibot.gameai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.Paint.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.view.Gravity
import android.view.WindowManager.LayoutParams
import android.view.WindowManager.LayoutParams.*
import androidx.core.app.NotificationCompat
import com.example.myempty.aibot.MainActivity
import com.example.myempty.aibot.ScreenCaptureService
import com.example.myempty.aibot.gameai.data.GameAiConfig
import com.example.myempty.aibot.gameai.data.ScreenDetection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class OverlayService : android.app.Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay_ai"
        private const val NOTIFICATION_ID = 1002

        @Volatile var currentDetections: List<ScreenDetection> = emptyList()
        @Volatile var currentFps: Float = 0f
        @Volatile var currentLatencyMs: Double = 0.0
        @Volatile var currentDetectionCount: Int = 0
        @Volatile var backendName: String = "CPU"
        
        var overlayInstance: OverlayService? = null
            private set
    }

    data class OverlaySettings(
        val showDetectionBoxes: Boolean = true,
        val showFPS: Boolean = true,
        val confidenceThreshold: Float = 0.35f,
        val selectedModelId: String = "fortnite",
        val useRawPixels: Boolean = true,
        val useBGR: Boolean = false,
        val flipY: Boolean = true,
        val useGridCorrection: Boolean = false,
        val aimSmoothness: Float = 0.5f,
        val captureResolution: Int = 256,
        val fovFraction: Float = 0.25f,
        val minimized: Boolean = false,
        val menuX: Float = 50f,
        val menuY: Float = 200f,
        val inferenceBackend: String = "ONNX"
    )

    var settings = OverlaySettings()
        private set

    private lateinit var windowManager: WindowManager
    private var detectionView: FullScreenDetectionView? = null
    private var menuView: FloatingMenuView? = null
    private lateinit var config: GameAiConfig
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlayInstance = this
        config = GameAiConfig(this)
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Start as foreground immediately to prevent system from killing the service
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        observeSettings()
    }

    private fun observeSettings() {
        serviceScope.launch {
            config.getSettings(settings.selectedModelId).collectLatest { s ->
                settings = settings.copy(
                    confidenceThreshold = s.confidenceThreshold,
                    fovFraction = s.fovFraction,
                    inferenceBackend = s.inferenceBackend,
                    useRawPixels = s.useRawPixels,
                    useBGR = s.useBGR,
                    flipY = s.flipY,
                    useGridCorrection = s.useGridCorrection,
                    aimSmoothness = s.aimSmoothness,
                    captureResolution = ScreenCaptureService.captureBaseSize
                )
                detectionView?.postInvalidate()
                menuView?.postInvalidate()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showOverlay()
        return START_STICKY
    }

    private fun showOverlay() {
        if (detectionView == null) {
            val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= 26) TYPE_APPLICATION_OVERLAY else TYPE_PHONE,
                FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            detectionView = FullScreenDetectionView(this)
            windowManager.addView(detectionView, params)
        }
        if (menuView == null) {
            val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) TYPE_APPLICATION_OVERLAY else TYPE_PHONE,
                FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = settings.menuX.toInt(); y = settings.menuY.toInt()
            }
            menuView = FloatingMenuView(this)
            windowManager.addView(menuView, params)
        }
    }

    fun updateDetections(detections: List<ScreenDetection>) {
        currentDetections = detections
        detectionView?.postInvalidate()
    }

    override fun onDestroy() {
        super.onDestroy()
        GameAiEngine.stopActiveSession("destroy")
        try {
            detectionView?.let { windowManager.removeView(it) }
            menuView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        overlayInstance = null
        serviceScope.cancel()
    }

    private fun buildNotification(): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YOLO Aimbot Active").setContentText("Floating menu ready").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentIntent(pi).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Game AI", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private inner class FullScreenDetectionView(context: Context) : View(context) {
        private val boxPaint = Paint().apply { style = Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
        private val textPaint = Paint().apply { textSize = 28f; color = Color.WHITE; isAntiAlias = true }
        private val fovPaint = Paint().apply { style = Style.STROKE; strokeWidth = 1f; color = Color.argb(80, 0, 255, 0); isAntiAlias = true }
        
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val fovPx = maxOf(cx, cy) * settings.fovFraction
            canvas.drawCircle(cx, cy, fovPx, fovPaint)

            if (!GameAiEngine.isRunning) return
            for (det in currentDetections) {
                boxPaint.color = if (det.isTarget) Color.GREEN else Color.RED
                canvas.drawRect(det.x, det.y, det.x + det.w, det.y + det.h, boxPaint)
                canvas.drawText("${det.className} ${(det.confidence*100).toInt()}%", det.x, det.y - 5, textPaint)
            }
        }
    }

    private inner class FloatingMenuView(context: Context) : View(context) {
        private var dragX = 0f; private var dragY = 0f; private var isDragging = false
        private val bgPaint = Paint().apply { color = Color.parseColor("#EE1A1A2E"); style = Style.FILL; isAntiAlias = true }
        private val hitBoxes = mutableMapOf<String, RectF>()

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    for (entry in hitBoxes) {
                        if (entry.value.contains(e.x, e.y)) {
                            handleAction(entry.key); postInvalidate(); return true
                        }
                    }
                    if (e.y < 50) { isDragging = true; dragX = e.rawX - (layoutParams as LayoutParams).x; dragY = e.rawY - (layoutParams as LayoutParams).y }
                }
                MotionEvent.ACTION_MOVE -> if (isDragging) {
                    val lp = layoutParams as LayoutParams
                    lp.x = (e.rawX - dragX).toInt(); lp.y = (e.rawY - dragY).toInt().coerceAtLeast(0)
                    windowManager.updateViewLayout(this, lp)
                }
                MotionEvent.ACTION_UP -> isDragging = false
            }
            return true
        }

        private fun handleAction(key: String) {
            when {
                key == "toggle_engine" -> {
                    if (GameAiEngine.isRunning) GameAiEngine.stopActiveSession("ui")
                    else GameAiEngine.start(context, settings.selectedModelId)
                }
                key == "exp_raw" -> { val v = !settings.useRawPixels; settings = settings.copy(useRawPixels = v); serviceScope.launch { config.updateExpertSettings(v, settings.useBGR, settings.flipY, settings.useGridCorrection) } }
                key == "exp_flip" -> { val v = !settings.flipY; settings = settings.copy(flipY = v); serviceScope.launch { config.updateExpertSettings(settings.useRawPixels, settings.useBGR, v, settings.useGridCorrection) } }
                key == "exp_bgr" -> { val v = !settings.useBGR; settings = settings.copy(useBGR = v); serviceScope.launch { config.updateExpertSettings(settings.useRawPixels, v, settings.flipY, settings.useGridCorrection) } }
                key == "exp_grid" -> { val v = !settings.useGridCorrection; settings = settings.copy(useGridCorrection = v); serviceScope.launch { config.updateExpertSettings(settings.useRawPixels, settings.useBGR, settings.flipY, v) } }
                key == "model_fort" -> { settings = settings.copy(selectedModelId = "fortnite"); GameAiEngine.stopActiveSession("switch") }
                key == "model_val" -> { settings = settings.copy(selectedModelId = "valorant"); GameAiEngine.stopActiveSession("switch") }
                key.startsWith("slide_") -> {} // Handled in touch (to be added)
            }
        }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(320, 680)

        override fun onDraw(canvas: Canvas) {
            hitBoxes.clear()
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 20f, 20f, bgPaint)
            val p = Paint().apply { color = Color.WHITE; textSize = 22f; isAntiAlias = true }
            canvas.drawText("YOLO HUD", 20f, 40f, p)
            
            val fpsColor = if (currentFps > 30) Color.GREEN else Color.RED
            canvas.drawText("FPS: %.1f".format(currentFps), 20f, 70f, p.apply { this.color = fpsColor })
            canvas.drawText("Lat: %.1fms | Det: $currentDetectionCount".format(currentLatencyMs), 130f, 70f, p.apply { this.color = Color.WHITE })

            val isRunning = GameAiEngine.isRunning
            val btnColor = if (isRunning) Color.parseColor("#FF4444") else Color.parseColor("#44FF44")
            drawBtn(canvas, 20f, 90f, 300f, 150f, if (isRunning) "STOP" else "START", btnColor, "toggle_engine")

            var y = 190f
            drawSlider(canvas, 20f, y, "FOV", settings.fovFraction, 0.05f, 0.5f, "slide_fov"); y += 60f
            drawSlider(canvas, 20f, y, "Conf", settings.confidenceThreshold, 0.01f, 0.95f, "slide_conf"); y += 60f
            drawSlider(canvas, 20f, y, "Res", settings.captureResolution.toFloat(), 128f, 640f, "slide_res"); y += 60f

            drawToggle(canvas, 20f, y, "Raw (0-255)", settings.useRawPixels, "exp_raw"); y += 50f
            drawToggle(canvas, 20f, y, "Flip Y", settings.flipY, "exp_flip"); y += 50f
            drawToggle(canvas, 20f, y, "BGR Swap", settings.useBGR, "exp_bgr"); y += 50f

            canvas.drawText("Model:", 20f, y, p.apply { textSize = 20f }); y += 35f
            drawPill(canvas, 20f, y, 150f, y + 40f, "FORT", settings.selectedModelId == "fortnite", "model_fort")
            drawPill(canvas, 170f, y, 300f, y + 40f, "VAL", settings.selectedModelId == "valorant", "model_val")
        }

        private fun drawSlider(c: Canvas, x: Float, y: Float, l: String, v: Float, s: Float, e: Float, key: String) {
            val p = Paint().apply { color = Color.WHITE; textSize = 18f; isAntiAlias = true }
            c.drawText("$l: ${if (e > 1) v.toInt() else "%.2f".format(v)}", x, y + 15, p)
            val bx = 120f; val bw = 160f
            c.drawRect(bx, y + 5f, bx + bw, y + 12f, Paint().apply { this.color = Color.DKGRAY })
            val ratio = ((v - s) / (e - s)).coerceIn(0f, 1f)
            c.drawRect(bx, y + 5f, bx + bw * ratio, y + 12f, Paint().apply { this.color = Color.CYAN })
            hitBoxes[key] = RectF(bx - 20, y - 10, bx + bw + 20, y + 30)
        }

        private fun drawBtn(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, txt: String, color: Int, key: String) {
            c.drawRoundRect(x1, y1, x2, y2, 10f, 10f, Paint().apply { this.color = color; isAntiAlias = true })
            c.drawText(txt, x1 + 60, y1 + 40, Paint().apply { this.color = Color.BLACK; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true })
            hitBoxes[key] = RectF(x1, y1, x2, y2)
        }

        private fun drawToggle(c: Canvas, x: Float, y: Float, txt: String, e: Boolean, key: String) {
            val textPaint = Paint().apply { color = Color.WHITE; textSize = 18f; isAntiAlias = true }
            c.drawText(txt, x, y + 22, textPaint)
            val tx = width - 80f
            c.drawRoundRect(tx, y, tx + 60f, y + 30f, 15f, 15f, Paint().apply { this.color = if (e) Color.GREEN else Color.GRAY; isAntiAlias = true })
            c.drawCircle(if (e) tx + 45f else tx + 15f, y + 15f, 10f, Paint().apply { this.color = Color.WHITE; isAntiAlias = true })
            hitBoxes[key] = RectF(tx - 20, y - 10, width.toFloat(), y + 40)
        }

        private fun drawPill(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, txt: String, sel: Boolean, key: String) {
            c.drawRoundRect(x1, y1, x2, y2, 20f, 20f, Paint().apply { this.color = if (sel) Color.BLUE else Color.DKGRAY; isAntiAlias = true })
            c.drawText(txt, x1 + 25, y1 + 28, Paint().apply { this.color = Color.WHITE; textSize = 16f; isAntiAlias = true })
            hitBoxes[key] = RectF(x1, y1, x2, y2)
        }
    }
}
