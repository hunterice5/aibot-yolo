package com.example.myempty.aibot.gameai.pid

import kotlin.math.sqrt

/**
 * PID Controller for smooth auto-aim.
 * 
 * Replicates libpid1.so pidProcessCore() and computeTouchOutput().
 * 
 * Purpose: Convert raw detection coordinates into smooth touch movement,
 * avoiding robotic snap movements that game anti-cheat systems detect.
 * 
 * Based on PID formula:
 *   output = kp * error + ki * integral(error) + kd * derivative(error)
 * 
 * The KEY insight from LINK app:
 * - atan2f → angle calculation for target direction
 * - sincosf → smooth rotation with sin+cos (used in trajectory generation)
 * - logf → logarithmic speed curve (fast start, smooth stop at target)
 */
data class PidConfig(
    val kP: Float = 2.0f,        // Proportional: reaction speed to error
    val kI: Float = 0.01f,       // Integral: accumulated error correction
    val kD: Float = 0.3f,        // Derivative: dampening, reduces oscillation
    val maxVelocity: Float = 200f, // Max pixels per frame movement
    val minStep: Float = 2f,      // Minimum movement threshold
    val deadZone: Float = 5f,     // Stop zone around target (to avoid jittering)
)

enum class PidState {
    IDLE,       // No target locked
    TRACKING,   // Target locked, moving towards
    LOCKED,     // Within firing range
    FIRING      // Fire trigger active
}

class PidController(
    private val config: PidConfig = PidConfig()
) {
    // State variables
    private var prevErrorX: Float = 0f
    private var prevErrorY: Float = 0f
    private var integralX: Float = 0f
    private var integralY: Float = 0f
    private var prevVelocityX: Float = 0f
    private var prevVelocityY: Float = 0f
    
    // State
    var currentState: PidState = PidState.IDLE
        private set

    /**
     * Process target position and return smoothed movement vector.
     * 
     * Equivalent to libpid1.so processFrame():
     * Input: (targetX, targetY) from coordinate mapper
     * Output: (velocityX, velocityY) for touch injection
     * 
     * @param targetX Target screen X coordinate
     * @param targetY Target screen Y coordinate
     * @param currentX Current cursor/viewport center X
     * @param currentY Current cursor/viewport center Y
     * @param dt Time delta in seconds
     * @return Pair(velocityX, velocityY) smoothed movement
     */
    fun update(
        targetX: Float,
        targetY: Float,
        currentX: Float,
        currentY: Float,
        dt: Float
    ): Pair<Float, Float> {
        // Calculate error (distance to target)
        val errorX = targetX - currentX
        val errorY = targetY - currentY
        val distance = sqrt(errorX * errorX + errorY * errorY)

        // Dead zone: if very close to target, stop
        if (distance < config.deadZone) {
            currentState = PidState.LOCKED
            integralX = 0f
            integralY = 0f
            prevErrorX = 0f
            prevErrorY = 0f
            return Pair(0f, 0f)
        }

        // Check if tracking or idle
        currentState = if (distance > config.deadZone) {
            PidState.TRACKING
        } else {
            PidState.LOCKED
        }

        // --- PID Calculation ---

        // Proportional term (reacts to current error)
        val pX = config.kP * errorX
        val pY = config.kP * errorY

        // Integral term (accumulates error over time)
        integralX += errorX * dt
        integralY += errorY * dt
        val iX = config.kI * integralX
        val iY = config.kI * integralY

        // Derivative term (reacts to rate of change)
        val dErrorX = (errorX - prevErrorX) / dt
        val dErrorY = (errorY - prevErrorY) / dt
        val dX = config.kD * dErrorX
        val dY = config.kD * dErrorY

        // Combined output
        var velocityX = pX + iX + dX
        var velocityY = pY + iY + dY

        // Clamp max velocity (prevents snap movement)
        val speed = sqrt(velocityX * velocityX + velocityY * velocityY)
        if (speed > config.maxVelocity) {
            val scale = config.maxVelocity / speed
            velocityX *= scale
            velocityY *= scale
        }

        // Minimum step: if movement is too small, stop (prevents micro-jitters)
        if (kotlin.math.abs(velocityX) < config.minStep) velocityX = 0f
        if (kotlin.math.abs(velocityY) < config.minStep) velocityY = 0f

        // Logarithmic speed curve: slow down as approaching target
        // Based on libpid1.so's use of logf
        val ratio = distance / config.maxVelocity
        val logFactor = 1f / (1f + 1f / (1f + kotlin.math.ln(1.0 + kotlin.math.max(0.001, ratio.toDouble())).toFloat()))
        velocityX *= logFactor
        velocityY *= logFactor

        // Store for next iteration
        prevErrorX = errorX
        prevErrorY = errorY

        return Pair(velocityX, velocityY)
    }

    /**
     * Calculate angle to target (based on libpid1.so atan2f usage).
     * Useful for aim direction visualization or trajectory planning.
     */
    fun angleToTarget(
        targetX: Float,
        targetY: Float,
        currentX: Float,
        currentY: Float
    ): Float {
        return kotlin.math.atan2(
            (targetY - currentY).toDouble(),
            (targetX - currentX).toDouble()
        ).toFloat()
    }

    /**
     * Calculate distance to target.
     */
    fun distanceToTarget(
        targetX: Float,
        targetY: Float,
        currentX: Float,
        currentY: Float
    ): Float {
        val dx = targetX - currentX
        val dy = targetY - currentY
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Reset all PID state.
     * Called when switching targets or starting new sequence.
     */
    fun reset() {
        currentState = PidState.IDLE
        prevErrorX = 0f
        prevErrorY = 0f
        integralX = 0f
        integralY = 0f
        prevVelocityX = 0f
        prevVelocityY = 0f
    }
}
