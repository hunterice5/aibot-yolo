package com.example.myempty.aibot.gameai.touch

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Manages the standalone uinput binary process (merge touch).
 * Uses a VIRTUAL touch device that doesn't conflict with user's real finger.
 * Protocol: stdin commands, stdout responses.
 *
 * Ported from Aimai's uinput system.
 */
object UinputBinary {
    private const val TAG = "UinputBinary"
    private const val BINARY_NAME = "aimai_uinput"
    private val SHELL_PATH = "/data/local/tmp/$BINARY_NAME"
    private var process: java.lang.Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    @Volatile var available = false
        private set

    // Detected touch device params
    private var touchName = "sec_touchscreen"
    private var touchBus = "0018"
    private var touchVendor = "0000"
    private var touchProduct = "0000"
    private var touchVersion = "0000"

    fun start(context: Context): Boolean {
        if (available) return true

        // 1) Write binary to /data/local/tmp via assets
        try {
            context.assets.open(BINARY_NAME).use { input ->
                File(SHELL_PATH).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Binary extracted to $SHELL_PATH")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract binary", e)
            return false
        }

        // 2) chmod via root
        try {
            val chmodProc = Runtime.getRuntime().exec("su -c chmod 755 $SHELL_PATH")
            chmodProc.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "chmod failed", e)
            return false
        }

        // 3) Start process via root
        return startProcess()
    }

    private fun startProcess(): Boolean {
        return try {
            process = Runtime.getRuntime().exec("su -c $SHELL_PATH")

            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.errorStream))

            // Wait for "READY"
            val ready = reader!!.readLine()
            if (ready != "READY") {
                Log.e(TAG, "Unexpected response: $ready")
                stop(); return false
            }

            // Send config for device compatibility
            sendConfig("name", touchName)
            sendConfig("bus", touchBus)
            sendConfig("vendor", touchVendor)
            sendConfig("product", touchProduct)
            sendConfig("version", touchVersion)

            // Create uinput device
            val ok = sendCommand("create")
            if (ok) {
                available = true
                Log.i(TAG, "uinput ready: $touchName")
            } else {
                Log.w(TAG, "create failed, retrying with defaults...")
                sendConfig("name", "sec_touchscreen")
                sendConfig("bus", "0018")
                val retry = sendCommand("create")
                if (retry) {
                    available = true
                    Log.i(TAG, "uinput ready (default sec_touchscreen)")
                } else {
                    stop()
                }
            }
            available
        } catch (e: Exception) {
            Log.e(TAG, "Start failed", e)
            stop(); false
        }
    }

    /** Touch DOWN at (x, y) via uinput virtual device */
    fun touchDown(x: Int, y: Int): Boolean {
        if (!available) return false
        return sendCommand("down $x $y")
    }

    /** Touch MOVE to (x, y) via uinput (finger stays DOWN) */
    fun touchMove(x: Int, y: Int): Boolean {
        if (!available) return false
        return sendCommand("move $x $y")
    }

    /** Touch UP (release finger) */
    fun touchUp(): Boolean {
        if (!available) return false
        return sendCommand("up")
    }

    fun stop() {
        available = false
        try {
            writer?.write("destroy\nexit\n")
            writer?.flush()
            process?.waitFor()
        } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        writer = null; reader = null; process = null
        Log.i(TAG, "Stopped")
    }

    private fun sendConfig(key: String, value: String): Boolean {
        return try {
            writer?.write("config $key $value\n")
            writer?.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "config $key failed", e)
            false
        }
    }

    private fun sendCommand(cmd: String): Boolean {
        return try {
            writer?.write("$cmd\n")
            writer?.flush()
            val resp = reader?.readLine()
            if (resp != null && resp.startsWith("OK")) true
            else {
                Log.w(TAG, "cmd='$cmd' resp=$resp")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "cmd failed: $cmd", e)
            false
        }
    }
}
