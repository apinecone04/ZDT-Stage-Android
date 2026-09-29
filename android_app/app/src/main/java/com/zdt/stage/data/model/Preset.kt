package com.zdt.stage.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Preset(
    val name: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0
)
