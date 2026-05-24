package com.example.myempty.aibot.gameai.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.myempty.aibot.gameai.data.Detection
import com.example.myempty.aibot.gameai.data.DetectionParser
import com.example.myempty.aibot.gameai.data.GameSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.Collections

/**
 * ONNX Runtime Inference Service.
 */
class OnnxInferenceService(private val appContext: Context) : InferenceService {

    companion object {
        private const val TAG = "OnnxInfer"
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isInitialized = false
    private var classLabels: Map<Int, String> = emptyMap()

    private var modelInputSize: Int = 256
    private var outputName: String = ""

    private val _detectionFlow = MutableSharedFlow<List<Detection>>(extraBufferCapacity = 1)
    override val detectionFlow: SharedFlow<List<Detection>> = _detectionFlow

    override fun initialize() {
        if (isInitialized) return
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            isInitialized = true
            Log.i(TAG, "ONNX service initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init ONNX Env", e)
        }
    }

    override fun setClassLabels(labels: Map<Int, String>) {
        classLabels = labels
    }

    override suspend fun loadModel(modelPath: String, settings: GameSettings): Boolean = withContext(Dispatchers.IO) {
        if (!isInitialized) initialize()
        modelInputSize = settings.modelInputSize

        if (!modelPath.endsWith(".onnx", true)) {
            Log.e(TAG, "Not an ONNX file: $modelPath")
            return@withContext false
        }

        try {
            val modelBytes = if (modelPath.startsWith("/")) {
                java.io.FileInputStream(modelPath).use { it.readAllBytes() }
            } else {
                appContext.assets.open(modelPath).use { it.readBytes() }
            }

            val sessionOptions = OrtSession.SessionOptions().apply {
                try {
                    // Simpler call to avoid build errors
                    addNnapi() 
                    Log.i(TAG, "NNAPI acceleration enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI not supported: ${e.message}")
                }
                setInterOpNumThreads(4)
                setIntraOpNumThreads(4)
            }

            try {
                ortSession?.close()
                ortSession = null
            } catch (_: Exception) {}

            ortSession = ortEnv?.createSession(modelBytes, sessionOptions)

            ortSession?.inputInfo?.forEach { (name, info) ->
                val v = info.info
                if (v is TensorInfo) {
                    Log.i(TAG, "Input '$name': shape=${v.shape.joinToString()}, type=${v.type}")
                }
            }

            Log.i(TAG, "ONNX Model loaded: $modelPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model: $modelPath", e)
            false
        }
    }

    private var lastFloatArr: FloatArray? = null
    private var cachedParser: DetectionParser? = null
    private var useBGR = false
    private var useRawPixels = true

    override suspend fun runInference(bitmap: Bitmap, settings: GameSettings): List<Detection> {
        val session = ortSession ?: return emptyList()
        val env = ortEnv ?: return emptyList()
        
        this.useRawPixels = settings.useRawPixels
        this.useBGR = settings.useBGR

        val size = modelInputSize
        val detections = try {
            val floatBuffer = preprocessToNCHW(bitmap, size)
            val inputName = session.inputNames.iterator().next()
            val inputTensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, size.toLong(), size.toLong()))
            
            val results = session.run(Collections.singletonMap(inputName, inputTensor))
            results.use {
                val outputTensor = results.get(0) as OnnxTensor
                val outFloatBuffer = outputTensor.floatBuffer
                val shape = outputTensor.info.getShape()
                
                val bufferSize = outFloatBuffer.remaining()
                if (lastFloatArr == null || lastFloatArr!!.size != bufferSize) {
                    lastFloatArr = FloatArray(bufferSize)
                }
                val floatArr = lastFloatArr!!
                outFloatBuffer.get(floatArr)
                
                val parser = cachedParser ?: DetectionParser(
                    outputMode = DetectionParser.DecodeMode.RAW_NORMALIZED
                ).also { cachedParser = it }
                
                parser.updateSettings(
                    conf = settings.confidenceThreshold,
                    nms = settings.nmsThreshold,
                    maxDet = settings.maxDetections,
                    labels = classLabels,
                    numCls = classLabels.size.coerceAtLeast(1),
                    gridCorrect = settings.useGridCorrection
                )
                
                parser.parseOutput(floatArr, size, size, shape)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            emptyList()
        }

        _detectionFlow.tryEmit(detections)
        return detections
    }

    private fun preprocessToNCHW(bitmap: Bitmap, size: Int): FloatBuffer {
        val src = if (bitmap.width == size && bitmap.height == size) bitmap
                  else Bitmap.createScaledBitmap(bitmap, size, size, true)
        
        val pixels = IntArray(size * size)
        src.getPixels(pixels, 0, size, 0, 0, size, size)

        val floatBuffer = java.nio.ByteBuffer.allocateDirect(1 * 3 * size * size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        
        val c0Offset = 0
        val c1Offset = size * size
        val c2Offset = 2 * size * size
        val divisor = if (useRawPixels) 1.0f else 255.0f

        for (i in 0 until size * size) {
            val color = pixels[i]
            val r = ((color shr 16) and 0xFF) / divisor
            val g = ((color shr 8) and 0xFF) / divisor
            val b = (color and 0xFF) / divisor

            if (useBGR) {
                floatBuffer.put(c0Offset + i, b)
                floatBuffer.put(c1Offset + i, g)
                floatBuffer.put(c2Offset + i, r)
            } else {
                floatBuffer.put(c0Offset + i, r)
                floatBuffer.put(c1Offset + i, g)
                floatBuffer.put(c2Offset + i, b)
            }
        }
        floatBuffer.rewind()
        return floatBuffer
    }

    override fun shutdown() {
        ortSession?.close()
        ortSession = null
        isInitialized = false
    }
}
