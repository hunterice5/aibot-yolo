package com.example.myempty.aibot.gameai.data

data class CoordinateMapper(
    val screenWidth: Int = 1080,
    val screenHeight: Int = 1920,
    val modelWidth: Int = 320,
    val modelHeight: Int = 320,
    val detectionZoneX: Float = 0.1f,
    val detectionZoneY: Float = 0.05f,
    val detectionZoneW: Float = 0.8f,
    val detectionZoneH: Float = 0.8f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val aimYRatio: Float = 0.3f,
    val aimXRatio: Float = 0.5f,
    var zoomFactor: Float = 1.0f,
    val flipY: Boolean = false,  // Some devices have inverted capture buffer
) {
    data class CalibrationPoint(
        val modelX: Float, val modelY: Float,
        val screenX: Float, val screenY: Float
    )

    private val calibrations = mutableListOf<CalibrationPoint>()

    fun mapToScreen(captureX: Float, captureY: Float, boxWidth: Float = 0f, boxHeight: Float = 0f): Pair<Float, Float> {
        var normX = captureX / modelWidth
        var normY = captureY / modelHeight
        
        if (flipY) normY = 1.0f - normY
        
        val screenX = normX * screenWidth
        val screenY = normY * screenHeight
        
        return Pair(screenX + offsetX, screenY + offsetY)
    }

    fun mapAimPoint(modelX: Float, modelY: Float, boxWidth: Float, boxHeight: Float): Pair<Float, Float> {
        val aimModelX = modelX + boxWidth * aimXRatio
        val aimModelY = modelY + boxHeight * aimYRatio
        return mapToScreen(aimModelX, aimModelY)
    }

    fun addCalibrationPoint(modelX: Float, modelY: Float, screenX: Float, screenY: Float) {
        calibrations.add(CalibrationPoint(modelX, modelY, screenX, screenY))
    }

    fun resetCalibration() {
        calibrations.clear()
    }
}
