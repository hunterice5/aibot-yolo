package com.example.myempty.aibot.gameai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class GameSettings(
    val gameId: String = "crossfire",
    val inferenceBackend: String = "LITERT", // "LITERT" or "ONNX"
    val confidenceThreshold: Float = 0.01f,  // 1% — debug: ให้เห็น detection ก่อน ค่อยปรับขึ้น
    val nmsThreshold: Float = 0.45f,
    val maxDetections: Int = 10,
    val useRawPixels: Boolean = true,
    val useBGR: Boolean = false,
    val flipY: Boolean = true,
    val useGridCorrection: Boolean = false,
    val aimSmoothness: Float = 0.5f,
    val detectionZoneX: Float = 0f,
    val detectionZoneY: Float = 0f,
    val detectionZoneW: Float = 1f,
    val detectionZoneH: Float = 1f,
    val pidKP: Float = 2.0f,
    val pidKI: Float = 0.01f,
    val pidKD: Float = 0.3f,
    val maxVelocity: Float = 200f,
    val minStep: Float = 2f,
    val deadZone: Float = 5f,
    val aimMode: String = "head",
    val aimYRatio: Float = 0.3f,
    val aimXRatio: Float = 0.5f,
    val fireEnabled: Boolean = false,
    val fireMode: String = "single",
    val fireDelayMs: Long = 200,
    val fireRange: Float = 30f,
    val calibrationOffsetX: Float = 0f,
    val calibrationOffsetY: Float = 0f,
    val showDetectionBoxes: Boolean = true,
    val showFPS: Boolean = true,
    val audioRadar: Boolean = false,
    val minimizeOnStart: Boolean = false,
    val modelInputSize: Int = 256,
    val fovFraction: Float = 0.35f,  // 35% = ~450px radius ใช้ reference max = 1280px
) {
    val aimTargets: Set<String>
        get() = when (gameId) {
            "valorant" -> setOf("agent_head")
            "crossfire" -> setOf("character", "head", "headshot")
            "pubg", "codm" -> setOf("head", "headshot")
            else -> setOf("head")
        }

    companion object {
        fun defaultsForGame(gameId: String): GameSettings {
            return when (gameId) {
                "fortnite" -> GameSettings(
                    gameId = "fortnite",
                    inferenceBackend = "ONNX",
                    modelInputSize = 256,
                    confidenceThreshold = 0.50f
                )
                "crossfire" -> GameSettings(
                    gameId = "crossfire",
                    aimMode = "head",
                    modelInputSize = 256,
                    confidenceThreshold = 0.80f,
                    maxDetections = 3
                )
                "pubg" -> GameSettings(gameId = "pubg", aimMode = "head", modelInputSize = 256,
                    detectionZoneY = 0f, detectionZoneH = 1f)
                "valorant" -> GameSettings(gameId = "valorant", aimMode = "head", modelInputSize = 256,
                    confidenceThreshold = 0.73f, pidKP = 1.5f, pidKD = 0.4f)
                "codm" -> GameSettings(gameId = "codm", aimMode = "head", modelInputSize = 256)
                "delta" -> GameSettings(gameId = "delta", aimMode = "center", modelInputSize = 192)
                else -> GameSettings(gameId = gameId)
            }
        }
    }
}

class GameAiConfig(private val context: Context) {
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "game_ai_settings")
        private val INFERENCE_BACKEND = stringPreferencesKey("inference_backend")
        private val CONFIDENCE = floatPreferencesKey("confidence")
        private val USE_RAW_PIXELS = booleanPreferencesKey("use_raw_pixels")
        private val USE_BGR = booleanPreferencesKey("use_bgr")
        private val FLIP_Y = booleanPreferencesKey("flip_y")
        private val USE_GRID = booleanPreferencesKey("use_grid")
        private val SMOOTHNESS = floatPreferencesKey("aim_smoothness")
        private val NMS = floatPreferencesKey("nms")
        private val ZONE_X = floatPreferencesKey("zone_x")
        private val ZONE_Y = floatPreferencesKey("zone_y")
        private val ZONE_W = floatPreferencesKey("zone_w")
        private val ZONE_H = floatPreferencesKey("zone_h")
        private val KP = floatPreferencesKey("kp")
        private val KI = floatPreferencesKey("ki")
        private val KD = floatPreferencesKey("kd")
        private val MAX_VEL = floatPreferencesKey("max_vel")
        private val DEAD_ZONE = floatPreferencesKey("dead_zone")
        private val AIM_MODE = stringPreferencesKey("aim_mode")
        private val AIM_Y = floatPreferencesKey("aim_y")
        private val FIRE_ENABLED = booleanPreferencesKey("fire_enabled")
        private val FIRE_DELAY = longPreferencesKey("fire_delay")
        private val SHOW_BOXES = booleanPreferencesKey("show_boxes")
        private val SHOW_FPS = booleanPreferencesKey("show_fps")
        private val AUDIO_RADAR = booleanPreferencesKey("audio_radar")
        private val MINIMIZE_START = booleanPreferencesKey("minimize_start")
        private val MODEL_SIZE = intPreferencesKey("model_size")
        private val CALIB_OFFSET_X = floatPreferencesKey("calib_offset_x")
        private val CALIB_OFFSET_Y = floatPreferencesKey("calib_offset_y")
        private val FOV_FRACTION = floatPreferencesKey("fov_fraction")
    }

    fun getSettings(gameId: String): Flow<GameSettings> {
        return context.dataStore.data.map { prefs ->
            val base = GameSettings.defaultsForGame(gameId)
            base.copy(
                gameId = gameId,
                inferenceBackend = prefs[INFERENCE_BACKEND] ?: base.inferenceBackend,
                confidenceThreshold = prefs[CONFIDENCE] ?: base.confidenceThreshold,
                useRawPixels = prefs[USE_RAW_PIXELS] ?: base.useRawPixels,
                useBGR = prefs[USE_BGR] ?: base.useBGR,
                flipY = prefs[FLIP_Y] ?: base.flipY,
                useGridCorrection = prefs[USE_GRID] ?: base.useGridCorrection,
                aimSmoothness = prefs[SMOOTHNESS] ?: base.aimSmoothness,
                nmsThreshold = prefs[NMS] ?: base.nmsThreshold,
                detectionZoneX = prefs[ZONE_X] ?: base.detectionZoneX,
                detectionZoneY = prefs[ZONE_Y] ?: base.detectionZoneY,
                detectionZoneW = prefs[ZONE_W] ?: base.detectionZoneW,
                detectionZoneH = prefs[ZONE_H] ?: base.detectionZoneH,
                pidKP = prefs[KP] ?: base.pidKP,
                pidKI = prefs[KI] ?: base.pidKI,
                pidKD = prefs[KD] ?: base.pidKD,
                maxVelocity = prefs[MAX_VEL] ?: base.maxVelocity,
                deadZone = prefs[DEAD_ZONE] ?: base.deadZone,
                aimMode = prefs[AIM_MODE] ?: base.aimMode,
                aimYRatio = prefs[AIM_Y] ?: base.aimYRatio,
                fireEnabled = prefs[FIRE_ENABLED] ?: base.fireEnabled,
                fireDelayMs = prefs[FIRE_DELAY] ?: base.fireDelayMs,
                showDetectionBoxes = prefs[SHOW_BOXES] ?: base.showDetectionBoxes,
                showFPS = prefs[SHOW_FPS] ?: base.showFPS,
                audioRadar = prefs[AUDIO_RADAR] ?: base.audioRadar,
                minimizeOnStart = prefs[MINIMIZE_START] ?: base.minimizeOnStart,
                modelInputSize = prefs[MODEL_SIZE] ?: base.modelInputSize,
                calibrationOffsetX = prefs[CALIB_OFFSET_X] ?: base.calibrationOffsetX,
                calibrationOffsetY = prefs[CALIB_OFFSET_Y] ?: base.calibrationOffsetY,
                fovFraction = prefs[FOV_FRACTION] ?: base.fovFraction,
            )
        }
    }

    suspend fun updateExpertSettings(raw: Boolean, bgr: Boolean, flip: Boolean, grid: Boolean) {
        context.dataStore.edit { p ->
            p[USE_RAW_PIXELS] = raw; p[USE_BGR] = bgr; p[FLIP_Y] = flip; p[USE_GRID] = grid
        }
    }

    suspend fun updateAimSmoothness(v: Float) {
        context.dataStore.edit { p -> p[SMOOTHNESS] = v }
    }

    suspend fun updateInferenceBackend(backend: String) {
        context.dataStore.edit { p -> p[INFERENCE_BACKEND] = backend }
    }

    suspend fun updateDetectionZone(x: Float, y: Float, w: Float, h: Float) {
        context.dataStore.edit { p ->
            p[ZONE_X] = x; p[ZONE_Y] = y; p[ZONE_W] = w; p[ZONE_H] = h
        }
    }

    suspend fun updatePID(kp: Float, ki: Float, kd: Float, maxVelocity: Float) {
        context.dataStore.edit { p ->
            p[KP] = kp; p[KI] = ki; p[KD] = kd; p[MAX_VEL] = maxVelocity
        }
    }

    suspend fun updateAimMode(mode: String, yRatio: Float) {
        context.dataStore.edit { p ->
            p[AIM_MODE] = mode; p[AIM_Y] = yRatio
        }
    }

    suspend fun updateFireSettings(enabled: Boolean, delayMs: Long) {
        context.dataStore.edit { p ->
            p[FIRE_ENABLED] = enabled; p[FIRE_DELAY] = delayMs
        }
    }

    suspend fun updateVisibility(showBoxes: Boolean, showFPS: Boolean, audioRadar: Boolean, minimize: Boolean) {
        context.dataStore.edit { p ->
            p[SHOW_BOXES] = showBoxes; p[SHOW_FPS] = showFPS
            p[AUDIO_RADAR] = audioRadar; p[MINIMIZE_START] = minimize
        }
    }

    suspend fun updateCalibration(offsetX: Float, offsetY: Float) {
        context.dataStore.edit { p ->
            p[CALIB_OFFSET_X] = offsetX; p[CALIB_OFFSET_Y] = offsetY
        }
    }

    suspend fun updateModelSize(size: Int) {
        context.dataStore.edit { p -> p[MODEL_SIZE] = size }
    }

    suspend fun updateFovFraction(fraction: Float) {
        context.dataStore.edit { p -> p[FOV_FRACTION] = fraction }
    }
}
