package com.example.myempty.aibot.gameai.inference

import android.graphics.Bitmap
import com.example.myempty.aibot.gameai.data.Detection
import com.example.myempty.aibot.gameai.data.GameSettings
import kotlinx.coroutines.flow.SharedFlow

/**
 * Common interface for all inference backends (LiteRT, ONNX, NCNN).
 */
interface InferenceService {
    val detectionFlow: SharedFlow<List<Detection>>
    fun initialize()
    fun setClassLabels(labels: Map<Int, String>)
    suspend fun loadModel(modelPath: String, settings: GameSettings): Boolean
    suspend fun runInference(bitmap: Bitmap, settings: GameSettings): List<Detection>
    fun shutdown()
}
