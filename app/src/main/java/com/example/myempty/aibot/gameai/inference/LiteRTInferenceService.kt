package com.example.myempty.aibot.gameai.inference

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
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * LiteRT (TFLite) Inference Service — optimized for MediaTek Dimensity NPU.
 *
 * Loads a Full INT8 quantized .tflite model and runs inference via
 * Interpreter with NNAPI delegate.
 *
 * Pipeline:
 *   Bitmap → preprocessToInt8() → [1,320,320,3] int8 NHWC
 *   → Interpreter.run()
 *   → dequantizeOutput() → FloatArray
 *   → DetectionParser.parseOutput()
 *
 * Key differences from ONNX Runtime path:
 *   - Input is int8 (not float32), needs quantization params
 *   - NNAPI is enabled via Interpreter.Options.setUseNNAPI(true)
 *   - TFLite uses NHWC layout (not ONNX NCHW)
 */
class LiteRTInferenceService(private val appContext: Context) : InferenceService {

    companion object {
        private const val TAG = "LiteRTInfer"
    }

    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private var classLabels: Map<Int, String> = emptyMap()

    // TFLite quantization params (read from loaded model)
    private var inputQuantParams: QuantizationParams = QuantizationParams()
    private var outputQuantParams: QuantizationParams = QuantizationParams()

    // Model output metadata
    private var modelInputSize: Int = 320
    private var outputShape: IntArray? = null

    @Volatile private var totalInferences: Long = 0
    @Volatile private var lastInferenceNs: Long = 0

    // Detect if model uses float32 or INT8/UINT8 tensors
    private var isInputQuantized: Boolean = false
    private var isOutputQuantized: Boolean = false
    // zeroPoint=0 means uint8 (unsigned) vs int8 (signed)
    private var isInputUint8: Boolean = false

    private val _detectionFlow = MutableSharedFlow<List<Detection>>(extraBufferCapacity = 1)
    override val detectionFlow: SharedFlow<List<Detection>> = _detectionFlow

    data class QuantizationParams(
        val scale: Float = 1.0f,
        val zeroPoint: Int = 0
    )

    override fun initialize() {
        if (isInitialized) return
        isInitialized = true
        Log.i(TAG, "LiteRT service initialized")
    }

    override fun setClassLabels(labels: Map<Int, String>) {
        classLabels = labels
    }

    /**
     * Load a .tflite model from assets.
     * Uses Interpreter with NNAPI enabled for NPU acceleration.
     */
    override suspend fun loadModel(modelPath: String, settings: GameSettings): Boolean = withContext(Dispatchers.IO) {
        if (!isInitialized) initialize()
        modelInputSize = settings.modelInputSize

        try {
            // Support both asset names and absolute filesystem paths.
            // After ModelManager copies from assets → files/, the path is absolute (e.g.
            // /data/user/0/.../files/models/best_int8_valorant.tflite).
            val modelBytes = if (modelPath.startsWith("/")) {
                java.io.FileInputStream(modelPath).use { it.readAllBytes() }
            } else {
                appContext.assets.open(modelPath).use { it.readBytes() }
            }
            val options = Interpreter.Options().apply {
                setUseNNAPI(true)  // ← Route to NPU via NNAPI
                setNumThreads(4)   // ARM big.LITTLE optimization
            }
            interpreter?.close()

            // TFLite Interpreter needs direct ByteBuffer (required for NNAPI)
            val byteBuffer = java.nio.ByteBuffer.allocateDirect(modelBytes.size)
            byteBuffer.put(modelBytes)
            byteBuffer.rewind()
            interpreter = Interpreter(byteBuffer, options)

            // Read input/output quantization params from the loaded model
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)

            // Override modelInputSize with the actual tensor shape from the model
            // This ensures we always resize to the correct dimensions regardless of config
            val inputShape = inputTensor.shape()
            if (inputShape.size >= 2) {
                val h = inputShape[1]
                val w = inputShape[2]
                val modelSize = maxOf(h, w)
                if (modelSize > 0) {
                    modelInputSize = modelSize
                    Log.i(TAG, "Model input shape: ${inputShape.joinToString()} → using size=$modelInputSize")
                }
            }

            inputQuantParams = QuantizationParams(
                scale = inputTensor.quantizationParams().scale,
                zeroPoint = inputTensor.quantizationParams().zeroPoint
            )
            outputQuantParams = QuantizationParams(
                scale = outputTensor.quantizationParams().scale,
                zeroPoint = outputTensor.quantizationParams().zeroPoint
            )
            outputShape = outputTensor.shape()

            // Detect if model uses quantized (INT8/UINT8) or float tensors
            // scale=0.0 means the tensor is float32 (no quantization params)
            // zeroPoint=0 means uint8 (unsigned byte), zeroPoint!=0 means int8 (signed byte)
            isInputQuantized = inputQuantParams.scale > 0f
            isOutputQuantized = outputQuantParams.scale > 0f
            // zeroPoint=0 → uint8 (raw bytes 0-255), zeroPoint≠0 → int8 (quantized -128..127)
            isInputUint8 = isInputQuantized && inputQuantParams.zeroPoint == 0

            Log.i(TAG, "Input quant: scale=${inputQuantParams.scale} zero=${inputQuantParams.zeroPoint} isQuantized=$isInputQuantized")
            Log.i(TAG, "Output shape: ${outputShape?.joinToString()}")
            Log.i(TAG, "Output quant: scale=${outputQuantParams.scale} zero=${outputQuantParams.zeroPoint} isQuantized=$isOutputQuantized")

            com.example.myempty.aibot.gameai.OverlayService.backendName = "LiteRT+NNAPI"
            Log.i(TAG, "Model loaded: $modelPath (LiteRT+NNAPI)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $modelPath", e)
            false
        }
    }

    /**
     * Run inference on a Bitmap and return parsed Detections.
     */
    override suspend fun runInference(bitmap: Bitmap, settings: GameSettings): List<Detection> {
        if (interpreter == null || !isInitialized) return emptyList()
        val startTime = System.nanoTime()
        // Use the class field modelInputSize (already overridden from actual tensor shape in loadModel)
        val size = modelInputSize

        val detections = try {
            // Step 1: Preprocess Bitmap (auto-detect float32 vs INT8 vs UINT8)
            val inputBuffer = when {
                isInputUint8 -> preprocessToUint8(bitmap, size)
                isInputQuantized -> preprocessToInt8(bitmap, size)
                else -> preprocessToFloat(bitmap, size)
            }

            // Step 2: Run inference
            val outShape = outputShape
            val outputBuffer = if (isOutputQuantized && outShape != null) {
                ByteBuffer.allocateDirect(computeOutputSize(outShape))
            } else if (outShape != null) {
                // Float32 output: each element = 4 bytes
                ByteBuffer.allocateDirect(computeOutputSize(outShape) * 4)
            } else {
                ByteBuffer.allocateDirect(200000)
            }.apply { order(ByteOrder.nativeOrder()) }

            interpreter?.run(inputBuffer, outputBuffer)

            // Step 3: Convert output to FloatArray (auto-detect float32 vs INT8)
            val floatArr = if (isOutputQuantized) {
                dequantizeOutput(outputBuffer)
            } else {
                readFloatOutput(outputBuffer)
            }

            if (floatArr.isEmpty()) {
                emptyList()
            } else {
                // Step 4: Parse detections
                val parser = DetectionParser(
                    numClasses = classLabels.size.coerceAtLeast(1),
                    confidenceThreshold = settings.confidenceThreshold,
                    nmsIoUThreshold = settings.nmsThreshold,
                    maxDetections = settings.maxDetections,
                    classLabels = classLabels,
                    outputMode = DetectionParser.DecodeMode.RAW_NORMALIZED // Model already decoded!
                )
                parser.parseOutput(floatArr, size, size, outputShape?.map { it.toLong() }?.toLongArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            emptyList()
        }

        lastInferenceNs = System.nanoTime() - startTime
        totalInferences++
        _detectionFlow.tryEmit(detections)
        return detections
    }

    /**
     * Preprocess Bitmap → UINT8 ByteBuffer in NHWC layout.
     *
     * For uint8-quantized models (when zeroPoint=0 indicates unsigned input).
     * Outputs raw pixel values [0, 255] in uint8 range.
     */
    private fun preprocessToUint8(bitmap: Bitmap, size: Int): ByteBuffer {
        val src = if (bitmap.width == size && bitmap.height == size) bitmap
                  else Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        src.getPixels(pixels, 0, size, 0, 0, size, size)

        // NHWC: 1 batch × H × W × 3 channels, each uint8 = 1 byte
        val buffer = ByteBuffer.allocateDirect(1 * size * size * 3).apply {
            order(ByteOrder.nativeOrder())
        }

        for (i in 0 until size * size) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF

            // Raw uint8 [0, 255] — no quantization needed for uint8 input models
            buffer.put(r.toByte())
            buffer.put(g.toByte())
            buffer.put(b.toByte())
        }

        buffer.rewind()
        return buffer
    }

    /**
     * Preprocess Bitmap → INT8 ByteBuffer in NHWC layout.
     *
     * MediaProjection produces RGBA_8888. We:
     * 1. Resize to model input size
     * 2. Extract RGB, normalize to [0,1], quantize to int8
     *
     * TFLite expects NHWC: [1, H, W, 3] with interleaved RGB pixels.
     */
    private fun preprocessToInt8(bitmap: Bitmap, size: Int): ByteBuffer {
        val src = if (bitmap.width == size && bitmap.height == size) bitmap
                  else Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        src.getPixels(pixels, 0, size, 0, 0, size, size)

        val scale = inputQuantParams.scale
        val zeroPoint = inputQuantParams.zeroPoint

        // NHWC: 1 batch × H × W × 3 channels
        val buffer = ByteBuffer.allocateDirect(1 * size * size * 3).apply {
            order(ByteOrder.nativeOrder())
        }

        for (i in 0 until size * size) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF

            // Normalize to [0,1], then quantize to int8
            // TFLite int8 quantization: q = round(f / scale) + zeroPoint
            // where f is the float value [0,1]
            buffer.put(quantizeUint8ToInt8(r, scale, zeroPoint))
            buffer.put(quantizeUint8ToInt8(g, scale, zeroPoint))
            buffer.put(quantizeUint8ToInt8(b, scale, zeroPoint))
        }

        buffer.rewind()
        return buffer
    }

    /**
     * Preprocess Bitmap → Float32 ByteBuffer in NHWC layout.
     *
     * For float32 models (when scale=0.0 indicates unquantized inputs).
     * Normalizes each pixel to [0.0, 1.0] in float32 format.
     */
    private fun preprocessToFloat(bitmap: Bitmap, size: Int): ByteBuffer {
        val src = if (bitmap.width == size && bitmap.height == size) bitmap
                  else Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        src.getPixels(pixels, 0, size, 0, 0, size, size)

        // NHWC: 1 batch × H × W × 3 channels, each float32 = 4 bytes
        val buffer = ByteBuffer.allocateDirect(1 * size * size * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        for (i in 0 until size * size) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF

            // Normalize uint8 [0,255] → float32 [0.0, 1.0]
            buffer.putFloat(r / 255.0f)
            buffer.putFloat(g / 255.0f)
            buffer.putFloat(b / 255.0f)
        }

        buffer.rewind()
        return buffer
    }

    /**
     * Quantize a 0-255 uint8 pixel value to int8 for TFLite.
     *
     * Formula: f = pixel / 255.0  (normalize to [0,1])
     *          q = round(f / scale) + zeroPoint
     *          q_clamped = q.clamp(-128, 127)
     */
    private fun quantizeUint8ToInt8(pixel: Int, scale: Float, zeroPoint: Int): Byte {
        val f = pixel / 255.0f
        val q = (f / scale).toInt() + zeroPoint
        return q.coerceIn(-128, 127).toByte()
    }

    /**
     * Dequantize INT8 output buffer → FloatArray.
     *
     * Formula: f = (q - zeroPoint) * scale
     */
    private fun dequantizeOutput(buffer: ByteBuffer): FloatArray {
        buffer.rewind()
        val remaining = buffer.remaining()
        val floatArr = FloatArray(remaining)
        val scale = outputQuantParams.scale
        val zeroPoint = outputQuantParams.zeroPoint

        for (i in 0 until remaining) {
            val q = buffer.get().toInt()
            floatArr[i] = (q - zeroPoint) * scale
        }
        return floatArr
    }

    /**
     * Read Float32 output buffer → FloatArray directly.
     *
     * For float32 models (when scale=0.0 indicates unquantized outputs).
     * Each 4 bytes is already a float32 value — just read them.
     */
    private fun readFloatOutput(buffer: ByteBuffer): FloatArray {
        buffer.rewind()
        val remaining = buffer.remaining()
        val floatArr = FloatArray(remaining / 4)
        for (i in floatArr.indices) {
            floatArr[i] = buffer.getFloat()
        }
        return floatArr
    }

    private fun computeOutputSize(shape: IntArray): Int {
        var size = 1
        for (d in shape) size *= d
        return size
    }

    override fun shutdown() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
    }
}
