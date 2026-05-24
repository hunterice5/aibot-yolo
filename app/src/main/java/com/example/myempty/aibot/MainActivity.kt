@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.myempty.aibot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myempty.aibot.gameai.OverlayService

import android.media.projection.MediaProjectionManager
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("RESULT_DATA", result.data)
            }
            startForegroundService(serviceIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("YOLO Aimbot Controller", style = MaterialTheme.typography.headlineMedium)
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        Button(
                            onClick = {
                                if (!Settings.canDrawOverlays(this@MainActivity)) {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                                    startActivity(intent)
                                } else {
                                    // Start the overlay menu
                                    startService(Intent(this@MainActivity, OverlayService::class.java))
                                    
                                    // Also prepare screen capture permission
                                    val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                                }
                            },
                            modifier = Modifier.fillMaxWidth(0.7f).height(56.dp)
                        ) {
                            Text("START SERVICE & CAPTURE")
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        OutlinedButton(
                            onClick = {
                                stopService(Intent(this@MainActivity, OverlayService::class.java))
                                val stopIntent = Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                                    action = "STOP"
                                }
                                startService(stopIntent)
                            },
                            modifier = Modifier.fillMaxWidth(0.7f).height(56.dp)
                        ) {
                            Text("STOP ALL")
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        Text("1. กดปุ่มเพื่อขอสิทธิ์ Overlay และ Capture\n2. กดยืนยัน 'Start Now' ที่หน้าจอ\n3. เข้าเกมแล้วใช้งานเมนูลอยได้ทันที", 
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
