package com.zdt.stage.data.model

import kotlinx.serialization.Serializable

/**
 * 单轴标定结果。
 * ZDT 电机存在两套坐标系：
 * - move 指令使用微步脉冲数 (16细分下 3200 脉冲/转) -> pulses_per_mm
 * - real_time_position 读取使用编码器角度 (65536 刻度/转) -> angle_per_mm
 * - invert: 方向反转标志（电机 CW 实为 -mm 方向时置 true，如 Y 轴）
 */
@Serializable
data class Calibration(
    val pulses_per_mm: Double = 0.0,
    val angle_per_mm: Double = 0.0,
    val origin_angle: Long = 0L,
    val valid: Boolean = false,
    val invert: Boolean = false
)
