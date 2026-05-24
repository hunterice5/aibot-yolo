package com.example.myempty.aibot.gameai.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ModelManager — Manages model download, caching, and path resolution.
 * Supports both .tflite (LiteRT) and .onnx (ONNX Runtime) models.
 */
class ModelManager(private val context: Context) {
    companion object {
        private const val TAG = "ModelManager"
        private val PREFERRED_ASSET_MODELS = listOf(
            "best_int8_dimensity.tflite"
        )

        val MODEL_CATALOG = mapOf(
            // Valorant-trained model
            "valorant" to GameModel("valo", "Valorant", 256, listOf("enemy")),
            "crossfire" to GameModel("cf_int8", "CrossFire", 256, listOf("character")),
            "codm" to GameModel("codm_int8", "Call of Duty Mobile", 256, listOf("enemy")),
            "fortnite" to GameModel("best_PfYipLn", "Fortnite", 256, listOf("enemy")),
        )
    }

    data class GameModel(val fileName: String, val displayName: String, val inputSize: Int, val classLabels: List<String>)

    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    fun isModelDownloaded(gameId: String): Boolean {
        val model = MODEL_CATALOG[gameId] ?: return false
        return File(modelsDir, "${model.fileName}.tflite").exists()
    }

    suspend fun downloadModel(gameId: String, modelUrl: String): Result<File> = withContext(Dispatchers.IO) {
        val model = MODEL_CATALOG[gameId]
        if (model == null) {
            return@withContext Result.failure<File>(IllegalArgumentException("Unknown game: $gameId"))
        }
        val destFile = File(modelsDir, "${model.fileName}.tflite")
        if (destFile.exists()) return@withContext Result.success(destFile)

        try {
            val conn = URL(modelUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000; conn.readTimeout = 60000
            if (conn.responseCode != 200) {
                return@withContext Result.failure<File>(Exception("HTTP ${conn.responseCode}"))
            }
            FileOutputStream(destFile).use { out -> conn.inputStream.use { inp -> inp.copyTo(out) } }
            Result.success(destFile)
        } catch (e: Exception) {
            Result.failure<File>(e)
        }
    }

    suspend fun downloadModelWithProgress(
        gameId: String, modelUrl: String,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val model = MODEL_CATALOG[gameId]
        if (model == null) {
            return@withContext Result.failure<File>(IllegalArgumentException("Unknown game: $gameId"))
        }
        val destFile = File(modelsDir, "${model.fileName}.tflite")
        if (destFile.exists()) {
            onProgress(1.0f, destFile.length(), destFile.length())
            return@withContext Result.success(destFile)
        }

        try {
            val conn = URL(modelUrl).openConnection() as HttpURLConnection
            if (conn.responseCode != 200) {
                return@withContext Result.failure<File>(Exception("HTTP ${conn.responseCode}"))
            }
            val contentLength = conn.contentLengthLong
            val buffer = ByteArray(8192)
            var totalRead = 0L
            FileOutputStream(destFile).use { out ->
                conn.inputStream.use { inp ->
                    var n: Int
                    while (inp.read(buffer).also { n = it } > 0) {
                        out.write(buffer, 0, n)
                        totalRead += n
                        if (contentLength > 0) onProgress(totalRead.toFloat() / contentLength, totalRead, contentLength)
                    }
                }
            }
            onProgress(1.0f, totalRead, contentLength)
            Result.success(destFile)
        } catch (e: Exception) {
            Result.failure<File>(e)
        }
    }

    fun getModelPath(gameId: String): String? {
        val model = MODEL_CATALOG[gameId] ?: MODEL_CATALOG["custom"] ?: return null
        val fileName = model.fileName
        
        // Try to find either .tflite or .onnx file
        val extensions = listOf(".tflite", ".onnx")
        
        for (ext in extensions) {
            val f = File(modelsDir, fileName + ext)
            if (f.exists() && f.length() > 0L) return f.absolutePath
        }

        // Fallback: copy from assets to models dir
        try {
            val assetNames = context.assets.list("")?.toList().orEmpty()
            
            var foundAsset: String? = null
            for (ext in extensions) {
                val fullName = fileName + ext
                foundAsset = assetNames.firstOrNull { it.equals(fullName, ignoreCase = true) }
                if (foundAsset != null) break
            }
            
            if (foundAsset == null) {
                // Last resort: find any model file in assets
                foundAsset = assetNames.firstOrNull { it.endsWith(".tflite", ignoreCase = true) || it.endsWith(".onnx", ignoreCase = true) }
            }

            if (foundAsset == null) {
                Log.e(TAG, "No model file (.tflite or .onnx) found in assets/")
                return null
            }

            val destFile = File(modelsDir, foundAsset)
            context.assets.open(foundAsset).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (destFile.length() <= 0L) {
                destFile.delete()
                Log.e(TAG, "Copied model file is empty: ${destFile.absolutePath}")
                return null
            }

            Log.i(TAG, "Copied asset '$foundAsset' → '${destFile.absolutePath}'")
            return destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare model from assets", e)
        }

        return null
    }

    fun getClassLabels(gameId: String): Map<Int, String> {
        val model = MODEL_CATALOG[gameId] ?: return emptyMap()
        return model.classLabels.mapIndexed { idx, label -> idx to label }.toMap()
    }

    fun getModelInputSize(gameId: String): Int = MODEL_CATALOG[gameId]?.inputSize ?: 320

    fun deleteModel(gameId: String): Boolean {
        val model = MODEL_CATALOG[gameId] ?: return false
        return File(modelsDir, "${model.fileName}.tflite").delete()
    }
}
