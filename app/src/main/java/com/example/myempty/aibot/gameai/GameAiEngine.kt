package com.example.myempty.aibot.gameai

import android.graphics.Bitmap
import android.util.Log
import com.example.myempty.aibot.gameai.data.*
import com.example.myempty.aibot.gameai.inference.*
import com.example.myempty.aibot.gameai.touch.TouchInjector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
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
    
    private var activeGameId = "fortnite"
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
                val modelPath = modelManager.getModelPath(gameId) ?: return@launch

                config.getSettings(gameId).collectLatest { settings ->
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

                    if (inferenceService.loadModel(modelPath, settings)) {
                        OverlayService.backendName = if (actualBackend == "ONNX") "ONNX Runtime" else "LiteRT+NNAPI"
                        runInferenceLoop(settings, inferenceService)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Engine error", e)
                isRunning = false
            }
        }
    }

    private suspend fun runInferenceLoop(settings: GameSettings, inferenceService: InferenceService) {
        var frames = 0
        var lastFpsTime = System.currentTimeMillis()
        val screenCenterX = realW / 2f
        val screenCenterY = realH / 2f

        while (isRunning) {
            val bitmap = synchronized(frameLock) {
                val b = lastBitmap
                lastBitmap = null
                b
            }

            if (bitmap != null) {
                val startTime = System.currentTimeMillis()
                val overlayS = OverlayService.overlayInstance?.settings
                
                val liveSettings = settings.copy(
                    confidenceThreshold = overlayS?.confidenceThreshold ?: settings.confidenceThreshold,
                    useRawPixels = overlayS?.useRawPixels ?: settings.useRawPixels,
                    useBGR = overlayS?.useBGR ?: settings.useBGR,
                    flipY = overlayS?.flipY ?: settings.flipY,
                    useGridCorrection = overlayS?.useGridCorrection ?: settings.useGridCorrection,
                    fovFraction = overlayS?.fovFraction ?: settings.fovFraction
                )

                coordinateMapper = coordinateMapper?.copy(flipY = liveSettings.flipY)

                val detections = inferenceService.runInference(bitmap, liveSettings)
                
                val lbOffX = com.example.myempty.aibot.ScreenCaptureService.letterboxOffsetX.toFloat()
                val lbOffY = com.example.myempty.aibot.ScreenCaptureService.letterboxOffsetY.toFloat()
                val lbScale = com.example.myempty.aibot.ScreenCaptureService.letterboxScale

                val screenDetections = detections.map { det ->
                    val capX = (det.x - lbOffX) / lbScale
                    val capY = (det.y - lbOffY) / lbScale
                    val capW = det.w / lbScale
                    val capH = det.h / lbScale

                    val screenPos = coordinateMapper?.mapToScreen(capX, capY) ?: Pair(0f, 0f)
                    val mapper = coordinateMapper
                    val sW = if (mapper != null) capW * (realW.toFloat() / mapper.modelWidth) else 100f
                    val sH = if (mapper != null) capH * (realH.toFloat() / mapper.modelHeight) else 100f

                    val centerX = screenPos.first
                    val centerY = screenPos.second
                    
                    val dist = hypot(centerX - screenCenterX, centerY - screenCenterY)
                    val fovRadius = maxOf(realW, realH) / 2f * liveSettings.fovFraction
                    val isInsideFov = dist <= fovRadius

                    ScreenDetection(
                        x = centerX - sW / 2f,
                        y = centerY - sH / 2f,
                        w = sW,
                        h = sH,
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
                
                OverlayService.currentFps = currentFps
                OverlayService.currentLatencyMs = currentLatency
                OverlayService.currentDetectionCount = detectionCount
                OverlayService.overlayInstance?.updateDetections(screenDetections)

                // ====== AUTO-AIM LOGIC START ======
                val target = screenDetections.filter { it.isTarget }.maxByOrNull { it.confidence }
                if (target != null) {
                    // Use adjustable aim ratios (aimXRatio=0.5, aimYRatio=0.3)
                    val aimX = target.x + target.w * (coordinateMapper?.aimXRatio ?: 0.5f)
                    val aimY = target.y + target.h * (coordinateMapper?.aimYRatio ?: 0.3f)
                    
                    if (liveSettings.useGridCorrection) { // Using this flag as 'AutoAim' toggle
                        if (touchInjector.isDown()) {
                            touchInjector.continuousMove(aimX, aimY)
                        } else {
                            touchInjector.continuousDown(aimX, aimY)
                        }
                    }
                } else {
                    if (touchInjector.isDown()) {
                        touchInjector.continuousUp()
                    }
                }
                // ====== AUTO-AIM LOGIC END ======
            }
            delay(1)
        }
    }

    fun stop() {
        isRunning = false
        gameJob?.cancel()
        com.example.myempty.aibot.ScreenCaptureService.frameListener = null
    }
}
