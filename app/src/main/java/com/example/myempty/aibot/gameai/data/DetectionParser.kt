package com.example.myempty.aibot.gameai.data

import android.util.Log

/**
 * Detection parser for YOLOv8/v11 TFLite output.
 *
 * Supports two output modes:
 * 1. RAW_NORMALIZED — read cx,cy,w,h as-is (model already decoded, values 0-1)
 * 2. GRID_DECODE   — decode grid-relative values using stride 8/16/32
 *
 * The model exports from Ultralytics with tflite+nms=False produce
 * the raw grid output which must be decoded from grid-relative coordinates
 * to image-absolute pixel coordinates.
 *
 * 1344 total anchors = 32² + 16² + 8² across 3 strides.
 */
class DetectionParser(
    private var numClasses: Int = 1,
    private var confidenceThreshold: Float = 0.05f,
    private var nmsIoUThreshold: Float = 0.45f,
    private var maxDetections: Int = 10,
    private var classLabels: Map<Int, String> = emptyMap(),
    private val outputMode: DecodeMode = DecodeMode.RAW_NORMALIZED,
    private var useGridCorrection: Boolean = false
) {
    fun updateSettings(
        conf: Float, 
        nms: Float, 
        maxDet: Int, 
        labels: Map<Int, String>,
        numCls: Int,
        gridCorrect: Boolean = false
    ) {
        this.confidenceThreshold = conf
        this.nmsIoUThreshold = nms
        this.maxDetections = maxDet
        this.classLabels = labels
        this.numClasses = numCls
        this.useGridCorrection = gridCorrect
    }
    companion object {
        private const val TAG = "Parser"

        // YOLO grid configuration for 256x256 input
        private const val GRID_8 = 32    // 32×32
        private const val GRID_16 = 16   // 16×16
        private const val GRID_32 = 8    // 8×8
        private const val STRIDE_8 = 8
        private const val STRIDE_16 = 16
        private const val STRIDE_32 = 32
        private const val ANCHORS_8 = 1024  // 32×32
        private const val ANCHORS_16 = 256  // 16×16
        private const val ANCHORS_32 = 64   // 8×8
        private const val TOTAL_ANCHORS = ANCHORS_8 + ANCHORS_16 + ANCHORS_32  // 1344
    }

    enum class DecodeMode {
        RAW_NORMALIZED,  // Model outputs already-decoded [0-1] coordinates
        GRID_DECODE      // Need grid-aware decoding (stride × grid offset)
    }

    fun parseOutput(
        outputBuffer: FloatArray,
        modelWidth: Int = 256,
        modelHeight: Int = 256,
        outputShape: LongArray? = null
    ): List<Detection> {
        // Step 1: AUTO-DETECT LAYOUT
        val layout = inferLayout(outputBuffer.size, outputShape)
        val numAnchors = layout.numAnchors
        val features = layout.features
        
        if (numAnchors <= 0 || features < 5) return emptyList()

        // Step 2: AUTO-DETECT SCALING (Pixel vs Normalized)
        // Check a few samples to see if coordinates are > 1.1 (meaning absolute pixels)
        var isAbsolutePixels = false
        for (idx in 0 until minOf(5, numAnchors)) {
            val checkX = if (layout.transposed) outputBuffer[idx] else outputBuffer[idx * features]
            if (checkX > 1.1f) { isAbsolutePixels = true; break }
        }

        val detections = mutableListOf<Detection>()

        for (i in 0 until numAnchors) {
            fun at(featureIdx: Int): Float {
                return if (layout.transposed) {
                    outputBuffer[featureIdx * numAnchors + i]
                } else {
                    outputBuffer[i * features + featureIdx]
                }
            }

            var cx = at(0)
            var cy = at(1)
            var w = at(2)
            var h = at(3)

            // Step 3: SCALE COORDINATES
            if (!isAbsolutePixels) {
                // Values are 0..1, scale to model pixels
                cx *= modelWidth
                cy *= modelHeight
                w *= modelWidth
                h *= modelHeight
            }

            val scoreResult = scoreAndClass(::at, features)
            val conf = scoreResult.first
            val classId = scoreResult.second

            if (conf >= confidenceThreshold) {
                if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite()) continue
                if (w <= 0f || h <= 0f) continue
                if (w > modelWidth * 1.50f || h > modelHeight * 1.50f) continue

                // Convert to a clipped top-left box inside the model canvas.
                val x1 = (cx - w / 2f).coerceIn(-w, modelWidth.toFloat() + w)
                val y1 = (cy - h / 2f).coerceIn(-h, modelHeight.toFloat() + h)
                val x2 = (cx + w / 2f).coerceIn(-w, modelWidth.toFloat() + w)
                val y2 = (cy + h / 2f).coerceIn(-h, modelHeight.toFloat() + h)
                val clippedW = x2 - x1
                val clippedH = y2 - y1

                if (clippedW <= 1f || clippedH <= 1f) continue
                
                // Reject boxes that cover almost the entire model input (likely false positives)
                if (clippedW > modelWidth * 0.95f || clippedH > modelHeight * 0.95f) continue

                val centerX = x1 + clippedW / 2f
                val centerY = y1 + clippedH / 2f
                val edgeMarginX = modelWidth * 0.02f  // 2% = ~5px (พอให้ไม่ติดขอบจริงๆ)
                val edgeMarginY = modelHeight * 0.02f
                if (centerX < edgeMarginX || centerX > modelWidth - edgeMarginX) continue
                if (centerY < edgeMarginY || centerY > modelHeight - edgeMarginY) continue

                detections.add(
                    Detection(
                        x = x1,
                        y = y1,
                        w = clippedW,
                        h = clippedH,
                        confidence = conf,
                        classId = classId,
                        className = classLabels[classId] ?: "target"
                    )
                )
            }
        }

        detections.sortByDescending { it.confidence }
        if (detections.isEmpty()) return emptyList()

        val leaderConfidence = detections.first().confidence
        val relativeConfidenceFloor = maxOf(confidenceThreshold, leaderConfidence * 0.85f)
        val clusteredDetections = detections.filter { it.confidence >= relativeConfidenceFloor }
        val result = applyNMS(clusteredDetections)
        
                if (result.isNotEmpty()) {
                    val best = result.first()
                    Log.i(TAG, "Best detection: class=${best.className} conf=${best.confidence} " +
                            "x=${best.x.toInt()} y=${best.y.toInt()} w=${best.w.toInt()} h=${best.h.toInt()} " +
                            "norm=(${"%.3f".format(best.x/modelWidth)}/${"%.3f".format(best.y/modelHeight)})")
                }
        
        return result
    }

    /**
     * Get grid position (stride, gridX, gridY) for anchor index i.
     * 1344 anchors = 32×32 (stride 8) + 16×16 (stride 16) + 8×8 (stride 32)
     */
    private fun getGridPosition(anchorIdx: Int): Triple<Int, Int, Int> {
        return when {
            anchorIdx < ANCHORS_8 -> {
                val gx = anchorIdx % GRID_8
                val gy = anchorIdx / GRID_8
                Triple(STRIDE_8, gx, gy)
            }
            anchorIdx < ANCHORS_8 + ANCHORS_16 -> {
                val idx = anchorIdx - ANCHORS_8
                val gx = idx % GRID_16
                val gy = idx / GRID_16
                Triple(STRIDE_16, gx, gy)
            }
            else -> {
                val idx = anchorIdx - ANCHORS_8 - ANCHORS_16
                val gx = idx % GRID_32
                val gy = idx / GRID_32
                Triple(STRIDE_32, gx, gy)
            }
        }
    }

    private data class OutputLayout(val numAnchors: Int, val features: Int, val transposed: Boolean)

    private fun inferLayout(bufferSize: Int, outputShape: LongArray?): OutputLayout {
        if (outputShape != null && outputShape.size >= 3) {
            val d1 = outputShape[1].toInt()
            val d2 = outputShape[2].toInt()
            if (d1 > 0 && d2 > 0) {
                // TFLite output is typically [1, features, anchors] or [1, anchors, features]
                // [1, features, anchors]: feature values are contiguous → transposed=true
                if (d1 in 5..512 && d2 >= d1) return OutputLayout(d2, d1, true)
                // [1, anchors, features]: anchor values are contiguous → transposed=false
                if (d2 in 5..512 && d1 >= d2) return OutputLayout(d1, d2, false)
            }
        }

        // Fallback: determine from buffer size
        val fallbackFeatures = numClasses + 4  // 4 box coords + N classes (Ultralytics format)
        val fallbackAnchors = bufferSize / fallbackFeatures
        return OutputLayout(fallbackAnchors, fallbackFeatures, true)
    }

    private fun scoreAndClass(at: (Int) -> Float, features: Int): Pair<Float, Int> {
        if (features <= 5) {
            return activateScore(at(4)) to 0
        }

        val expectedClasses = numClasses.coerceAtLeast(1)
        val classOnlyFeatureCount = 4 + expectedClasses

        // Ultralytics YOLOv8/YOLO11 export: [x, y, w, h, cls0..clsN] without objectness
        if (features == classOnlyFeatureCount) {
            var bestClass = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (c in 4 until features) {
                val s = activateScore(at(c))
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c - 4
                }
            }
            return bestScore to bestClass
        }

        val objectness = activateScore(at(4))

        // [x, y, w, h, obj, cls1..clsN]
        if (features >= 5 + expectedClasses) {
            var bestClass = 0
            var bestClassScore = Float.NEGATIVE_INFINITY
            val classEnd = (5 + expectedClasses).coerceAtMost(features)
            for (c in 5 until classEnd) {
                val s = activateScore(at(c))
                if (s > bestClassScore) {
                    bestClassScore = s
                    bestClass = c - 5
                }
            }

            if (bestClassScore <= 0.001f && objectness >= 0.001f) {
                return objectness to 0
            }

            return (objectness * bestClassScore) to bestClass
        }

        // Fallback: [x, y, w, h, cls1..clsN] without objectness
        var bestNoObjClass = 0
        var bestNoObjScore = Float.NEGATIVE_INFINITY
        val classEnd = (4 + expectedClasses).coerceAtMost(features)
        for (c in 4 until classEnd) {
            val s = activateScore(at(c))
            if (s > bestNoObjScore) {
                bestNoObjScore = s
                bestNoObjClass = c - 4
            }
        }
        return bestNoObjScore to bestNoObjClass
    }

    private fun activateScore(raw: Float): Float {
        // Safe activation:
        // If the value is 0.0, sigmoid would make it 0.5 (50% fake confidence).
        // Most Ultralytics exports already include Sigmoid. 
        // We only apply it if the value is clearly a logit (negative or > 1.0).
        return if (raw >= 0f && raw <= 1.0f) raw else sigmoid(raw)
    }

    private fun sigmoid(x: Float): Float {
        val clamped = x.coerceIn(-20f, 20f)
        return (1f / (1f + kotlin.math.exp(-clamped)))
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        val selected = mutableListOf<Detection>()
        val suppressed = BooleanArray(detections.size)

        for (i in detections.indices) {
            if (suppressed[i]) continue
            val current = detections[i]
            selected.add(current)

            for (j in i + 1 until detections.size) {
                if (suppressed[j]) continue
                val other = detections[j]
                val iou = calculateIoU(current, other)
                if (iou > nmsIoUThreshold) suppressed[j] = true
            }
            if (selected.size >= maxDetections) break
        }
        return selected
    }

    private fun calculateIoU(a: Detection, b: Detection): Float {
        val ax1 = a.x; val ay1 = a.y; val ax2 = a.x + a.w; val ay2 = a.y + a.h
        val bx1 = b.x; val by1 = b.y; val bx2 = b.x + b.w; val by2 = b.y + b.h

        val interW = (Math.min(ax2, bx2) - Math.max(ax1, bx1)).coerceAtLeast(0f)
        val interH = (Math.min(ay2, by2) - Math.max(ay1, by1)).coerceAtLeast(0f)
        val interArea = interW * interH
        val unionArea = (a.w * a.h) + (b.w * b.h) - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}
