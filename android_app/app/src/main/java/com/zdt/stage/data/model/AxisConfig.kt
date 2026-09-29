package com.zdt.stage.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AxisConfig(
    val travel_mm: Double = 0.0,
    val jog_speed: Int = 60,
    val jog_accel: Int = 100,
    val move_speed: Int = 120,
    val move_accel: Int = 150,
    val calibration: Calibration = Calibration()
)
