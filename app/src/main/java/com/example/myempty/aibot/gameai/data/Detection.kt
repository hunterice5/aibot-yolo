package com.example.myempty.aibot.gameai.data

import android.graphics.Bitmap

/**
 * Data model for a single detection result from YOLO model.
 */
data class Detection(
    val x: Float,          // center X in model coordinates
    val y: Float,          // center Y in model coordinates
    val w: Float,          // width in model coordinates
    val h: Float,          // height in model coordinates
    val confidence: Float, // detection confidence [0.0, 1.0]
    val classId: Int,      // detected class ID
    val className: String  // human-readable class name
) {
    fun toScreenCoords(mappedX: Float, mappedY: Float, mappedW: Float, mappedH: Float): ScreenDetection {
        return ScreenDetection(
            x = mappedX,
            y = mappedY,
            w = mappedW,
            h = mappedH,
            confidence = confidence,
            classId = classId,
            className = className,
            isTarget = false
        )
    }
}

data class ScreenDetection(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val confidence: Float,
    val classId: Int,
    val className: String,
    val isTarget: Boolean = false
) {
    val centerX: Float get() = x + w / 2f
    val centerY: Float get() = y + h / 2f
    val area: Float get() = w * h
}

/**
 * Output tensor buffer creator for ONNX model input.
 */
object FrameProcessor {
    fun preprocess(bitmap: Bitmap, modelSize: Int): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, modelSize, modelSize, true)
        val buffer = FloatArray(1 * 3 * modelSize * modelSize)

        val pixels = IntArray(modelSize * modelSize)
        resized.getPixels(pixels, 0, modelSize, 0, 0, modelSize, modelSize)

        for (i in 0 until modelSize * modelSize) {
            val r = ((pixels[i] shr 16) and 0xFF) / 255f
            val g = ((pixels[i] shr 8) and 0xFF) / 255f
            val b = (pixels[i] and 0xFF) / 255f

            // Normalize: [0,1] -> [-1,1], NCHW format
            buffer[i * 3] = r
            buffer[i * 3 + 1] = g
            buffer[i * 3 + 2] = b
        }

        return buffer
    }
}
