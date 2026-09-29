package com.zdt.stage.core.motion

import com.zdt.stage.data.model.Calibration
import kotlin.math.roundToLong

/**
 * 运动单位换算引擎（100% 对齐 Python 版本算法）：
 * - mm ↔ 微步脉冲 (delta_mm_to_pulses)
 * - 编码器 counts ↔ mm
 * - 标定公式计算
 */
object MotionUnits {

    /**
     * 相对位移 mm -> move 指令的微步脉冲数 (带符号)。
     * deltaMm 为显示坐标系的位移；invert 轴需先取反再换算。
     * 返回带符号长整数：正=CW，负=CCW。未标定返回 0。
     */
    fun deltaMmToPulses(deltaMm: Double, cal: Calibration): Long {
        if (!cal.valid || cal.pulses_per_mm == 0.0) return 0L
        val d = if (cal.invert) -deltaMm else deltaMm
        return (d * cal.pulses_per_mm).roundToLong()
    }

    /**
     * 微步脉冲数 -> 相对位移 mm
     */
    fun pulsesToDeltaMm(pulses: Long, cal: Calibration): Double {
        if (!cal.valid || cal.pulses_per_mm == 0.0) return 0.0
        val raw = pulses.toDouble() / cal.pulses_per_mm
        return if (cal.invert) -raw else raw
    }

    /**
     * 标定计算：根据发送的脉冲数与实测距离计算 pulses_per_mm
     */
    fun calcPulsesPerMm(pulses: Long, measuredMm: Double): Double {
        if (measuredMm == 0.0) return 0.0
        return pulses.toDouble() / kotlin.math.abs(measuredMm)
    }

    /**
     * 标定计算：根据编码器角度增量与实测距离计算 angle_per_mm
     */
    fun calcAnglePerMm(deltaAngle: Long, measuredMm: Double): Double {
        if (measuredMm == 0.0) return 0.0
        return kotlin.math.abs(deltaAngle.toDouble()) / kotlin.math.abs(measuredMm)
    }
}
