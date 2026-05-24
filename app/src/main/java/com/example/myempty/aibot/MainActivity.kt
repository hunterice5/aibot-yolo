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

class MainActivity : ComponentActivity() {
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
                                    startService(Intent(this@MainActivity, OverlayService::class.java))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(0.7f).height(56.dp)
                        ) {
                            Text("OPEN FLOATING MENU")
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        OutlinedButton(
                            onClick = {
                                stopService(Intent(this@MainActivity, OverlayService::class.java))
                            },
                            modifier = Modifier.fillMaxWidth(0.7f).height(56.dp)
                        ) {
                            Text("CLOSE ALL SERVICES")
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        Text("1. กดปุ่มเพื่อเปิดเมนูลอย\n2. เข้าเกมแล้วกด START ในเมนู\n3. จูนค่า Raw/Flip ให้กรอบตรงตัวละคร", 
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
