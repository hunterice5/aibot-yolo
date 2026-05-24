@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.myempty.aibot.gameai.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myempty.aibot.gameai.data.ScreenDetection

/**
 * GameOverlayScreen: Jetpack Compose UI for Game AI settings.
 */
@Composable
fun GameOverlayScreen(
    isRunning: Boolean,
    currentFps: Float,
    currentLatency: Double,
    detectionCount: Int,
    toggleEngine: () -> Unit,
    onNavigateBack: () -> Unit,
    gameId: String = "crossfire",
    onGameChange: (String) -> Unit,
    confidenceThreshold: Float = 0.05f,
    onConfidenceChange: (Float) -> Unit,
    pidKP: Float = 2.0f,
    pidKI: Float = 0.01f,
    pidKD: Float = 0.3f,
    onPidChange: (Float, Float, Float) -> Unit,
    aimMode: String = "head",
    onAimModeChange: (String) -> Unit,
    showFPS: Boolean = true,
    audioRadar: Boolean = false,
    onVisibilityChange: (Boolean, Boolean) -> Unit,
    inferenceBackend: String = "LITERT",
    onBackendChange: (String) -> Unit,
    useRawPixels: Boolean = true,
    onRawPixelsChange: (Boolean) -> Unit,
    useBGR: Boolean = false,
    onBGRChange: (Boolean) -> Unit,
    flipY: Boolean = true,
    onFlipYChange: (Boolean) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Home", "Detection", "PID", "Aim", "Stats")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game AI Overlay", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = toggleEngine,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) Color.Red else Color.Green
                        )
                    ) {
                        Text(if (isRunning) "Stop" else "Start")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (selectedTab) {
                0 -> GameSelectionTab(gameId, onGameChange, isRunning)
                1 -> DetectionTab(
                    confidenceThreshold, onConfidenceChange, inferenceBackend, onBackendChange,
                    useRawPixels = useRawPixels, onRawPixelsChange = onRawPixelsChange,
                    useBGR = useBGR, onBGRChange = onBGRChange,
                    flipY = flipY, onFlipYChange = onFlipYChange
                )
                2 -> PidTab(pidKP, pidKI, pidKD, onPidChange)
                3 -> AimTab(aimMode, onAimModeChange)
                4 -> StatsTab(currentFps, currentLatency, detectionCount, isRunning)
            }
        }
    }
}

@Composable
fun GameSelectionTab(
    currentGameId: String,
    onGameChange: (String) -> Unit,
    isRunning: Boolean
) {
    val games = listOf(
        "fortnite" to "Fortnite (ONNX)",
        "crossfire" to "CrossFire",
        "pubg" to "PUBG Mobile",
        "valorant" to "Valorant Mobile",
        "codm" to "Call of Duty Mobile",
        "delta" to "Delta Force"
    )

    LazyColumn {
        item {
            Text("Select Game", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
        }
        items(games.size) { index ->
            val (id, name) = games[index]
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (id == currentGameId)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, fontWeight = FontWeight.Medium)
                    if (!isRunning) {
                        RadioButton(
                            selected = id == currentGameId,
                            onClick = { onGameChange(id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DetectionTab(
    confidence: Float,
    onConfidenceChange: (Float) -> Unit,
    backend: String = "LITERT",
    onBackendChange: (String) -> Unit,
    useRawPixels: Boolean,
    onRawPixelsChange: (Boolean) -> Unit,
    useBGR: Boolean,
    onBGRChange: (Boolean) -> Unit,
    flipY: Boolean,
    onFlipYChange: (Boolean) -> Unit
) {
    Column {
        Text("Detection Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        Text("Inference Backend", fontWeight = FontWeight.Medium)
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("LITERT" to "LiteRT", "ONNX" to "ONNX").forEach { (id, label) ->
                FilterChip(
                    selected = backend == id,
                    onClick = { onBackendChange(id) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        SliderWithLabel(
            label = "Confidence Threshold",
            value = confidence,
            valueRange = 0.01f..0.9f,
            onValueChange = onConfidenceChange,
            format = { "%.2f".format(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Expert Manual Tuning", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useRawPixels, onCheckedChange = onRawPixelsChange)
            Text("Raw Pixels (0-255) vs (0-1)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useBGR, onCheckedChange = onBGRChange)
            Text("BGR Color Space (Swap R/B)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = flipY, onCheckedChange = onFlipYChange)
            Text("Flip Y-Axis (Inversion)")
        }
    }
}

@Composable
fun PidTab(
    kp: Float,
    ki: Float,
    kd: Float,
    onPidChange: (Float, Float, Float) -> Unit
) {
    Column {
        Text("PID Controller", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        SliderWithLabel(
            label = "KP (Proportional)",
            value = kp,
            valueRange = 0.5f..5.0f,
            onValueChange = { v -> onPidChange(v, ki, kd) },
            format = { "%.1f".format(it) }
        )

        SliderWithLabel(
            label = "KI (Integral)",
            value = ki,
            valueRange = 0.0f..0.05f,
            onValueChange = { v -> onPidChange(kp, v, kd) },
            format = { "%.3f".format(it) }
        )

        SliderWithLabel(
            label = "KD (Derivative)",
            value = kd,
            valueRange = 0.1f..1.0f,
            onValueChange = { v -> onPidChange(kp, ki, v) },
            format = { "%.1f".format(it) }
        )
    }
}

@Composable
fun AimTab(
    aimMode: String,
    onAimModeChange: (String) -> Unit
) {
    Column {
        Text("Aim Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        Text("Aim Point", fontWeight = FontWeight.Medium)
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("head" to "Head", "center" to "Center", "body" to "Body").forEach { (mode, label) ->
                FilterChip(
                    selected = aimMode == mode,
                    onClick = { onAimModeChange(mode) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}

@Composable
fun StatsTab(
    fps: Float,
    latency: Double,
    detectionCount: Int,
    isRunning: Boolean
) {
    Column {
        Text("Statistics", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        StatRow("Status", if (isRunning) "Running" else "Stopped")
        StatRow("FPS", "%.1f".format(fps))
        StatRow("Inference Latency", "%.1f ms".format(latency))
        StatRow("Detections", detectionCount.toString())
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.Medium)
        Text(value, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun SliderWithLabel(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    format: (Float) -> String
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(format(value), color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
