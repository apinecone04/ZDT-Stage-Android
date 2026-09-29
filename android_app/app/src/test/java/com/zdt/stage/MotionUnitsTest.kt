package com.zdt.stage

import com.zdt.stage.core.motion.MotionUnits
import com.zdt.stage.data.model.Calibration
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionUnitsTest {

    @Test
    fun testDeltaMmToPulsesNormal() {
        val cal = Calibration(
            pulses_per_mm = 53.3333333333,
            angle_per_mm = 1092.2666666667,
            valid = true,
            invert = false
        )

        // 正向移动 10 mm -> 533 脉冲 (CW > 0)
        val p1 = MotionUnits.deltaMmToPulses(10.0, cal)
        assertEquals(533L, p1)

        // 反向移动 -10 mm -> -533 脉冲 (CCW < 0)
        val p2 = MotionUnits.deltaMmToPulses(-10.0, cal)
        assertEquals(-533L, p2)
    }

    @Test
    fun testDeltaMmToPulsesInvert() {
        // Y 轴默认 invert = true
        val cal = Calibration(
            pulses_per_mm = 53.3333333333,
            angle_per_mm = 1092.2666666667,
            valid = true,
            invert = true
        )

        // Invert 轴：期望物理 +10 mm -> 相对脉冲取反为 -533
        val p1 = MotionUnits.deltaMmToPulses(10.0, cal)
        assertEquals(-533L, p1)

        // Invert 轴：期望物理 -10 mm -> 相对脉冲取反为 +533
        val p2 = MotionUnits.deltaMmToPulses(-10.0, cal)
        assertEquals(533L, p2)
    }

    @Test
    fun testUncalibrated() {
        val uncal = Calibration(valid = false)
        assertEquals(0L, MotionUnits.deltaMmToPulses(10.0, uncal))
    }

    @Test
    fun testCalcCalibrationFormulas() {
        // 发送 1000 脉冲，量得 18.75 mm
        val pulsesPerMm = MotionUnits.calcPulsesPerMm(1000L, 18.75)
        assertEquals(53.333333, pulsesPerMm, 0.001)

        // 终态 - 起态 = 20480 角度单位，实测 18.75 mm
        val anglePerMm = MotionUnits.calcAnglePerMm(20480L, 18.75)
        assertEquals(1092.2666, anglePerMm, 0.01)
    }
}
