package com.example.myempty.aibot.gameai

import android.graphics.Bitmap
import android.util.Log
import com.example.myempty.aibot.gameai.data.*
import com.example.myempty.aibot.gameai.inference.*
import com.example.myempty.aibot.gameai.touch.TouchInjector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.hypot

class GameAiEngine(private val appCtx: android.content.Context) {

    companion object {
        private const val TAG = "GameAiEngine"
        private var activeEngine: GameAiEngine? = null
        private val engineLock = Any()

        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: android.content.Context, gameId: String) {
            synchronized(engineLock) {
                activeEngine?.stop()
                activeEngine = GameAiEngine(context).apply { start(gameId) }
            }
        }

        fun stopActiveSession(reason: String) {
            synchronized(engineLock) {
                activeEngine?.stop()
                activeEngine = null
            }
        }
    }

    private val config = GameAiConfig(appCtx)
    private val modelManager = ModelManager(appCtx)
    private val liteRtService = LiteRTInferenceService(appCtx)
    private val onnxService = OnnxInferenceService(appCtx)
    private val touchInjector = TouchInjector(appCtx)
    
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var gameJob: Job? = null
    
    private val frameLock = Any()
    private var lastBitmap: Bitmap? = null
    
    private var activeGameId = "valorant"
    private var coordinateMapper: CoordinateMapper? = null
    
    private var realW = 2560
    private var realH = 1600
    
    var currentFps = 0f
    var currentLatency = 0.0
    var detectionCount = 0

    fun start(gameId: String) {
        if (isRunning) return
        isRunning = true
        activeGameId = gameId

        val modelSize = modelManager.getModelInputSize(gameId)
        com.example.myempty.aibot.ScreenCaptureService.targetModelSize = modelSize

        com.example.myempty.aibot.ScreenCaptureService.frameListener = { bitmap ->
            synchronized(frameLock) {
                lastBitmap = bitmap
            }
        }

        gameJob = engineScope.launch {
            try {
                // 🔥 แก้จุดตาย: Restart Engine เฉพาะเมื่อ Model หรือ Backend เปลี่ยนเท่านั้น!
                config.getSettings(gameId)
                    .map { s -> (s.selectedModelId ?: "valorant_320") + s.inferenceBackend }
                    .distinctUntilChanged()
                    .collectLatest { _ ->
                        val settings = config.getCurrentSettingsSync(gameId)
                        val modelId = settings.selectedModelId
                        val preferredExt = if (settings.inferenceBackend == "ONNX") ".onnx" else ".tflite"

                        OverlayService.statusMessage = "LOADING..."
                        val modelPath = modelManager.getModelPath(modelId, preferredExt) ?: return@collectLatest

                        val actualBackend = if (modelPath.endsWith(".onnx", true)) "ONNX" else settings.inferenceBackend
                        val inferenceService: InferenceService = if (actualBackend == "ONNX") onnxService else liteRtService
                        
                        inferenceService.setClassLabels(modelManager.getClassLabels(gameId))

                        val scW2 = com.example.myempty.aibot.ScreenCaptureService.realDisplayW
                        val scH2 = com.example.myempty.aibot.ScreenCaptureService.realDisplayH
                        if (scW2 > 0 && scH2 > 0) { realW = scW2; realH = scH2 }
                        
                        val captureW = com.example.myempty.aibot.ScreenCaptureService.captureBaseSize
                        val captureH = (realH * captureW / realW).coerceIn(captureW / 2, captureW * 2)

                        coordinateMapper = CoordinateMapper(
                            screenWidth = realW, screenHeight = realH,
                            modelWidth = captureW, modelHeight = captureH,
                            flipY = settings.flipY
                        )

                        OverlayService.statusMessage = "COMPILING..."
                        if (inferenceService.loadModel(modelPath, settings)) {
                            OverlayService.backendName = if (actualBackend == "ONNX") "ONNX+NNAPI" else "LiteRT+NNAPI"
                            OverlayService.statusMessage = "ACTIVE"
                            runInferenceLoop(inferenceService)
                        } else {
                            OverlayService.statusMessage = "LOAD FAILED"
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Engine error", e)
                OverlayService.statusMessage = "ERROR: ${e.message}"
                isRunning = false
            }
        }
    }

    private suspend fun runInferenceLoop(inferenceService: InferenceService) {
        var frames = 0
        var lastFpsTime = System.currentTimeMillis()
        val screenCenterX = realW / 2f
        val screenCenterY = realH / 2f

        try {
            while (isRunning) {
                val bitmap = synchronized(frameLock) {
                    val b = lastBitmap
                    lastBitmap = null
                    b
                }

                if (bitmap != null) {
                    val startTime = System.currentTimeMillis()
                    // Get LIVE settings from UI without restarting the loop
                    val liveS = OverlayService.overlayInstance?.settings ?: continue
                    
                    val settings = GameSettings(
                        confidenceThreshold = liveS.confidenceThreshold,
                        useRawPixels = liveS.useRawPixels,
                        useBGR = liveS.useBGR,
                        flipY = liveS.flipY,
                        fovFraction = liveS.fovFraction,
                        useGridCorrection = liveS.useGridCorrection
                    )

                    coordinateMapper = coordinateMapper?.copy(flipY = settings.flipY)

                    val detections = inferenceService.runInference(bitmap, settings)
                    
                    // 1. Get current model dimensions for mapping
                    val modelW_actual = inferenceService.getInputSize().toFloat()
                    val modelH_actual = modelW_actual // YOLO models are square
                    
                    val lbOffX = com.example.myempty.aibot.ScreenCaptureService.letterboxOffsetX.toFloat()
                    val lbOffY = com.example.myempty.aibot.ScreenCaptureService.letterboxOffsetY.toFloat()
                    val lbScale = com.example.myempty.aibot.ScreenCaptureService.letterboxScale

                    val realDisplayW = this.realW.toFloat()
                    val realDisplayH = this.realH.toFloat()

                    val screenDetections = detections.map { det ->
                        // Reverse Letterbox: (ModelCoord - Offset) / Scale
                        val mappedX = (det.x - lbOffX) / lbScale
                        val mappedY = (det.y - lbOffY) / lbScale
                        val mappedW = det.w / lbScale
                        val mappedH = det.h / lbScale

                        val centerX = mappedX + mappedW / 2f
                        val centerY = mappedY + mappedH / 2f
                        
                        val dist = hypot(centerX - screenCenterX, centerY - screenCenterY)
                        val fovRadius = maxOf(realDisplayW, realDisplayH) / 2f * settings.fovFraction
                        val isInsideFov = dist <= fovRadius

                        ScreenDetection(
                            x = mappedX,
                            y = mappedY,
                            w = mappedW,
                            h = mappedH,
                            confidence = det.confidence,
                            classId = det.classId,
                            className = det.className,
                            isTarget = isInsideFov
                        )
                    }

                    currentLatency = (System.currentTimeMillis() - startTime).toDouble()
                    detectionCount = screenDetections.size
                    
                    frames++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsTime >= 1000) {
                        currentFps = frames * 1000f / (now - lastFpsTime)
                        frames = 0
                        lastFpsTime = now
                    }
                    
                    // --- 🔥 Logging แบบจัดเต็ม ---
                    if (screenDetections.isNotEmpty()) {
                        val best = screenDetections.maxBy { it.confidence }
                        Log.d(TAG, "AI Report: Found ${screenDetections.size} targets | " +
                                "Best: ${best.className} (${(best.confidence * 100).toInt()}%) " +
                                "at [${best.x.toInt()}, ${best.y.toInt()}] | Speed: ${currentLatency.toInt()}ms")
                    }

                    OverlayService.currentFps = currentFps
                    OverlayService.currentLatencyMs = currentLatency
                    OverlayService.currentDetectionCount = detectionCount
                    OverlayService.overlayInstance?.updateDetections(screenDetections)

                    // AUTO-AIM
                    val target = screenDetections.filter { it.isTarget }.maxByOrNull { it.confidence }
                    if (target != null) {
                        val aimX = target.x + target.w * (coordinateMapper?.aimXRatio ?: 0.5f)
                        val aimY = target.y + target.h * (coordinateMapper?.aimYRatio ?: 0.3f)
                        
                        if (settings.useGridCorrection) {
                            if (touchInjector.isDown()) {
                                touchInjector.continuousMove(aimX, aimY)
                            } else {
                                touchInjector.continuousDown(aimX, aimY)
                            }
                        }
                    } else {
                        if (touchInjector.isDown()) touchInjector.continuousUp()
                    }
                }
                delay(1)
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Inference loop cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Inference loop error", e)
        } finally {
            if (touchInjector.isDown()) touchInjector.continuousUp()
        }
    }

    fun stop() {
        isRunning = false
        gameJob?.cancel()
        com.example.myempty.aibot.ScreenCaptureService.frameListener = null
        OverlayService.statusMessage = "STOPPED"
        touchInjector.shutdown()
    }

    private fun getRealDisplaySize(context: android.content.Context): Pair<Int, Int> {
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val display = wm.defaultDisplay
        val size = android.graphics.Point()
        display.getRealSize(size)
        return Pair(size.x, size.y)
    }
}
