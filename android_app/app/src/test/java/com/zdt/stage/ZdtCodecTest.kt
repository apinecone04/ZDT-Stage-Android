package com.zdt.stage

import com.zdt.stage.core.protocol.ZdtCodec
import com.zdt.stage.core.protocol.ZdtConstants
import org.junit.Assert.*
import org.junit.Test

class ZdtCodecTest {

    @Test
    fun testSignMagnitudeDecoding() {
        // 5 字节正数: [0x00, 0x00, 0x01, 0x00, 0x00] -> +65536
        val posBytes = byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x00)
        val posVal = ZdtCodec.decodeSignMagnitude(posBytes, 0, 5)
        assertEquals(65536L, posVal)

        // 5 字节负数: [0x01, 0x00, 0x01, 0x00, 0x00] -> -65536
        val negBytes = byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x00)
        val negVal = ZdtCodec.decodeSignMagnitude(negBytes, 0, 5)
        assertEquals(-65536L, negVal)

        // 3 字节速度: [0x01, 0x00, 0x78] -> -120 RPM
        val spdBytes = byteArrayOf(0x01, 0x00, 0x78.toByte())
        val spdVal = ZdtCodec.decodeSignMagnitude(spdBytes, 0, 3)
        assertEquals(-120L, spdVal)
    }

    @Test
    fun testPackFrameChecksum() {
        val body = byteArrayOf(0x01, 0xF3.toByte(), 0xAB.toByte(), 0x01, 0x00)
        val frame = ZdtCodec.packFrame(body)
        assertEquals(6, frame.size)
        assertEquals(ZdtConstants.CS, frame[5])
    }

    @Test
    fun testJogFrameGeneration() {
        // X 轴 (addr 1) 顺时针 60 RPM, 加速度 100
        val frame = ZdtCodec.buildJogFrame(addr = 1, cw = true, speedRpm = 60, accel = 100)
        assertEquals(8, frame.size)
        assertEquals(0x01.toByte(), frame[0])
        assertEquals(ZdtConstants.CODE_JOG, frame[1])
        assertEquals(0x00.toByte(), frame[2]) // CW = 0
        assertEquals(0x00.toByte(), frame[3]) // 60 high byte
        assertEquals(60.toByte(), frame[4])   // 60 low byte
        assertEquals(100.toByte(), frame[5])  // accel
        assertEquals(0x00.toByte(), frame[6]) // sync
        assertEquals(ZdtConstants.CS, frame[7]) // 0x6B
    }

    @Test
    fun testSysStatusParsing() {
        val resp = ByteArray(31)
        resp[0] = 0x02 // Y 轴地址 2
        resp[1] = ZdtConstants.CODE_GET_SYS_STATUS
        resp[2] = 0x1F.toByte()
        resp[3] = 0x09.toByte()

        // 电压 24.15V = 24150 mV = 0x5E56
        resp[4] = 0x5E.toByte()
        resp[5] = 0x56.toByte()

        // 电流 0.42A = 420 mA = 0x01A4
        resp[6] = 0x01.toByte()
        resp[7] = 0xA4.toByte()

        // 实时位置 5 字节: +131072 (2圈) -> sign=0, value=0x00020000
        resp[18] = 0x00.toByte()
        resp[19] = 0x00.toByte()
        resp[20] = 0x02.toByte()
        resp[21] = 0x00.toByte()
        resp[22] = 0x00.toByte()

        // homing_status: bit 2 = 1 (回零中)
        resp[28] = 0x04.toByte()

        // motor_status: bit 0 = 1 (使能), bit 1 = 1 (到位) -> 0x03
        resp[29] = 0x03.toByte()

        resp[30] = ZdtConstants.CS

        val status = ZdtCodec.parseSysStatus(resp, 2)
        assertNotNull(status)
        assertEquals(2, status!!.address)
        assertEquals(24.15f, status.busVoltageV, 0.01f)
        assertEquals(0.42f, status.phaseCurrentA, 0.01f)
        assertEquals(131072L, status.realTimePosition)
        assertTrue(status.isHoming)
        assertFalse(status.isHomingFailed)
        assertTrue(status.isEnabled)
        assertTrue(status.isInPosition)
        assertFalse(status.isStalled)
    }
}
