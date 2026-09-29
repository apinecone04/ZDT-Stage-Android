package com.zdt.stage.data.model

import kotlinx.serialization.Serializable

val AXES = listOf("x", "y", "z")
val DEFAULT_TRAVEL = mapOf("x" to 600.0, "y" to 400.0, "z" to 300.0)

@Serializable
data class AppConfig(
    val port: String = "",
    val baud: Int = 115200,
    val addresses: Map<String, Int> = mapOf("x" to 1, "y" to 2, "z" to 3),
    val poll_interval: Double = 0.1,
    val axes: Map<String, AxisConfig> = mapOf(
        "x" to AxisConfig(travel_mm = 600.0, calibration = Calibration(invert = false)),
        "y" to AxisConfig(travel_mm = 400.0, calibration = Calibration(invert = true)), // Y 轴默认反转
        "z" to AxisConfig(travel_mm = 300.0, calibration = Calibration(invert = false))
    ),
    val presets: List<Preset> = emptyList(),
    val script: List<ScriptPoint> = emptyList()
) {
    companion object {
        fun default(): AppConfig = AppConfig()
    }
}
