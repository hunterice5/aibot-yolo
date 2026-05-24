package com.example.myempty.aibot.gameai.touch

import kotlinx.coroutines.delay

import android.content.Context
import android.util.Log
import java.io.DataOutputStream
import kotlin.math.roundToInt

/**
 * Touch Injection System.
 * Supports multiple methods (in priority order):
 *   1. uinput virtual device (merge touch — runs alongside user's real finger)
 *   2. Shell command (input tap/swipe) - fallback
 *   3. Root via /dev/uinput (legacy)
 * 
 * uinput creates a separate virtual touch device. Games see bot touch + user touch
 * as independent fingers — no conflict!
 */
class TouchInjector(private val context: Context) {

    companion object {
        const val TAG = "TouchInjector"
    }

    enum class InjectionMethod {
        UINPUT,
        SHELL_COMMAND,
        DEV_UINPUT,
    }

    private var currentMethod: InjectionMethod = InjectionMethod.SHELL_COMMAND
    private var process: Process? = null
    private var outputStream: DataOutputStream? = null

    // Continuous touch state (uinput mode: keep DOWN between frames)
    @Volatile private var isContinuousDown = false
    @Volatile private var continuousX = 0f
    @Volatile private var continuousY = 0f
    private var lastContinuousEventMs = 0L
    private val reDownTimeoutMs = 2000L  // Re-DOWN if no event for 2 seconds

    suspend fun initialize() {
        // Try uinput first (merge touch — best for coexisting with user's finger)
        try {
            val started = UinputBinary.start(context)
            if (started) {
                currentMethod = InjectionMethod.UINPUT
                Log.i(TAG, "uinput initialized — merge touch enabled!")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "uinput init failed", e)
        }

        val hasRoot = checkRoot()
        currentMethod = if (hasRoot) InjectionMethod.SHELL_COMMAND else InjectionMethod.SHELL_COMMAND
        try {
            process = if (hasRoot) Runtime.getRuntime().exec("su") else Runtime.getRuntime().exec("sh")
            outputStream = DataOutputStream(process?.outputStream)
            Log.i(TAG, "Shell command injection ready (root=$hasRoot)")
        } catch (e: Exception) {
            process = null; outputStream = null
            Log.e(TAG, "All injection methods failed!")
        }
    }

    // ====== CONTINUOUS TOUCH (uinput: DOWN kept alive, only MOVE per frame) ======

    /**
     * Start continuous touch at (x, y).
     * Sends DOWN via uinput. Finger stays DOWN until continuousUp() is called.
     * If already down, just updates position.
     */
    suspend fun continuousDown(x: Float, y: Float) {
        val ix = x.roundToInt(); val iy = y.roundToInt()
        if (currentMethod == InjectionMethod.UINPUT && UinputBinary.available) {
            val now = System.currentTimeMillis()
            // Re-DOWN if timed out or near edge
            val nearEdge = continuousX <= 80f || continuousY <= 80f ||
                continuousX >= 2000f || continuousY >= 2000f
            if (!isContinuousDown || now - lastContinuousEventMs > reDownTimeoutMs || nearEdge) {
                if (isContinuousDown) UinputBinary.touchUp()
                UinputBinary.touchDown(ix, iy)
                isContinuousDown = true
            } else {
                // Already down, just MOVE to new position
                UinputBinary.touchMove(ix, iy)
            }
            continuousX = x; continuousY = y
            lastContinuousEventMs = now
            return
        }
        // Fallback: shell swipe from center with short duration
        continuousX = x; continuousY = y
        executeShellCommand("input swipe $ix $iy $ix $iy 1")
    }

    /**
     * Move continuous touch to new (x, y).
     * Finger stays DOWN (just sends MOVE via uinput).
     * If not down yet, sends DOWN first.
     */
    suspend fun continuousMove(x: Float, y: Float) {
        val ix = x.roundToInt(); val iy = y.roundToInt()
        if (currentMethod == InjectionMethod.UINPUT && UinputBinary.available) {
            val now = System.currentTimeMillis()
            if (!isContinuousDown || now - lastContinuousEventMs > reDownTimeoutMs) {
                // Need to re-establish touch
                continuousDown(x, y)
                return
            }
            UinputBinary.touchMove(ix, iy)
            continuousX = x; continuousY = y
            lastContinuousEventMs = now
            return
        }
        // Fallback: shell swipe from last to new position
        executeShellCommand("input swipe ${continuousX.roundToInt()} ${continuousY.roundToInt()} $ix $iy 1")
        continuousX = x; continuousY = y
    }

    /**
     * Release continuous touch (UP via uinput).
     */
    suspend fun continuousUp() {
        if (currentMethod == InjectionMethod.UINPUT && UinputBinary.available && isContinuousDown) {
            UinputBinary.touchUp()
            isContinuousDown = false
            Log.i(TAG, "continuous UP at (${continuousX.toInt()},${continuousY.toInt()})")
        }
        continuousX = 0f; continuousY = 0f
        lastContinuousEventMs = 0L
    }

    fun isDown(): Boolean = isContinuousDown

    // ====== LEGACY METHODS (shell fallback) ======

    suspend fun tap(x: Float, y: Float) {
        val tapX = x.roundToInt()
        val tapY = y.roundToInt()
        executeShellCommand("input tap $tapX $tapY")
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) {
        val sX1 = x1.roundToInt(); val sY1 = y1.roundToInt()
        val sX2 = x2.roundToInt(); val sY2 = y2.roundToInt()
        executeShellCommand("input swipe $sX1 $sY1 $sX2 $sY2 $duration")
    }

    suspend fun smoothTap(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        steps: Int = 10,
        stepDelayMs: Long = 15
    ) {
        for (i in 0..steps) {
            val progress = i.toFloat() / steps
            val x = fromX + (toX - fromX) * progress
            val y = fromY + (toY - fromY) * progress
            tap(x, y)
            delay(stepDelayMs)
        }
    }

    // ====== HELPERS ======

    private fun checkRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("su -c echo root")
            p.waitFor()
            p.inputStream.bufferedReader().readText().contains("root")
        } catch (e: Exception) { false }
    }

    private suspend fun executeShellCommand(command: String): String {
        return try {
            val os = outputStream
            if (os != null) {
                os.writeBytes("$command\n"); os.flush()
                process?.inputStream?.bufferedReader()?.readText() ?: ""
            } else {
                val p = Runtime.getRuntime().exec(if (checkRoot()) "su -c \"$command\"" else command)
                p.waitFor()
                p.inputStream.bufferedReader().readText()
            }
        } catch (e: Exception) { "" }
    }

    fun shutdown() {
        if (currentMethod == InjectionMethod.UINPUT) {
            if (isContinuousDown) {
                kotlinx.coroutines.runBlocking { continuousUp() }
            }
            UinputBinary.stop()
        }
        outputStream?.close(); process?.destroy()
        outputStream = null; process = null
        isContinuousDown = false
    }

    fun getCurrentMethod(): InjectionMethod = currentMethod
    fun hasRoot(): Boolean = checkRoot()
}
