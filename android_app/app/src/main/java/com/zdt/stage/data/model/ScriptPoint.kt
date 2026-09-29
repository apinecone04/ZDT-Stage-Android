package com.zdt.stage.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ScriptPoint(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val dwell: Double = 0.0 // 到点后停留时间 (秒)
)
