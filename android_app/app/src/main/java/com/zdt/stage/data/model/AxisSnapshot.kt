package com.zdt.stage.data.model

/**
 * 轮询采集的单轴状态快照（不可变只读数据）。
 */
data class AxisSnapshot(
    val axis: String,
    val online: Boolean,
    val angle_position: Long = 0L,
    val target_position: Long = 0L,
    val speed_rpm: Float = 0.0f,
    val bus_voltage: Float = 0.0f,
    val phase_current: Float = 0.0f,
    val enabled: Boolean = false,
    val in_position: Boolean = false,
    val stalled: Boolean = false,
    val stall_protect: Boolean = false,
    val homing: Boolean = false,
    val homing_failed: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 由原始角度位置换算为相对原点的物理位移 mm。
     * 未标定或 angle_per_mm == 0 时返回 0.0。
     * 如果 cal.invert 为 true，需取负值。
     */
    fun mm(cal: Calibration): Double {
        if (!cal.valid || cal.angle_per_mm == 0.0) return 0.0
        val raw = (angle_position - cal.origin_angle) / cal.angle_per_mm
        return if (cal.invert) -raw else raw
    }

    /**
     * 由目标位置换算为目标 mm。
     */
    fun targetMm(cal: Calibration): Double {
        if (!cal.valid || cal.angle_per_mm == 0.0) return 0.0
        val raw = (target_position - cal.origin_angle) / cal.angle_per_mm
        return if (cal.invert) -raw else raw
    }
}
