package com.zdt.stage.core.serial

import com.zdt.stage.core.protocol.ZdtConstants
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.concurrent.timer

/**
 * 虚拟仿真串口（Mock Serial Port）：
 * - 模拟 3 轴驱动板电气参数、编码器转动与回零时序
 * - 允许在无物理硬件或 Android 模拟器上 100% 验证所有 UI 与控制逻辑
 */
class MockSerialPort(override val portName: String = "MOCK:ZDT_Stage_Sim") : ISerialPort {

    override var isOpen: Boolean = false
        private set

    private val rxBuffer = ByteArrayOutputStream()

    // 内部三轴模拟状态 (角度 65536/转)
    private class AxisSim(
        val addr: Int,
        var pos: Long = 0L,
        var target: Long = 0L,
        var speed: Long = 0L,
        var enabled: Boolean = true,
        var inPosition: Boolean = true,
        var homing: Boolean = false,
        var homingTicks: Int = 0
    )

    private val axes = mapOf(
        1 to AxisSim(1),
        2 to AxisSim(2),
        3 to AxisSim(3)
    )

    private var simTimer: java.util.Timer? = null

    override fun open(baudRate: Int) {
        isOpen = true
        simTimer = timer(name = "Mock-Physics", period = 50) {
            // 物理步进仿真
            axes.values.forEach { ax ->
                if (ax.homing) {
                    ax.homingTicks++
                    if (ax.homingTicks > 20) { // 1秒后回零完成
                        ax.homing = false
                        ax.pos = 0L
                        ax.target = 0L
                        ax.inPosition = true
                        ax.speed = 0L
                    }
                } else if (ax.pos != ax.target) {
                    val diff = ax.target - ax.pos
                    val step = (diff / 4).coerceIn(-2000L, 2000L)
                    if (kotlin.math.abs(diff) <= 200) {
                        ax.pos = ax.target
                        ax.speed = 0L
                        ax.inPosition = true
                    } else {
                        ax.pos += step
                        ax.speed = 120L
                        ax.inPosition = false
                    }
                }
            }
        }
    }

    override fun close() {
        isOpen = false
        simTimer?.cancel()
        simTimer = null
        rxBuffer.reset()
    }

    override fun purgeHwBuffers(purgeRead: Boolean, purgeWrite: Boolean) {
        if (purgeRead) {
            synchronized(rxBuffer) {
                rxBuffer.reset()
            }
        }
    }

    override fun write(data: ByteArray, timeoutMs: Int) {
        if (!isOpen || data.isEmpty()) return
        val addr = data[0].toInt() and 0xFF
        val code = data[1]
        val ax = axes[addr]

        synchronized(rxBuffer) {
            when (code) {
                ZdtConstants.CODE_GET_SYS_STATUS -> {
                    // 生成 31 字节系统状态响应
                    val resp = ByteArray(31)
                    resp[0] = addr.toByte()
                    resp[1] = ZdtConstants.CODE_GET_SYS_STATUS
                    resp[2] = 0x1F.toByte()
                    resp[3] = 0x09.toByte()

                    // 电压 24.0V = 24000 mV
                    resp[4] = ((24000 shr 8) and 0xFF).toByte()
                    resp[5] = (24000 and 0xFF).toByte()

                    // 电流 0.35A = 350 mA
                    resp[6] = ((350 shr 8) and 0xFF).toByte()
                    resp[7] = (350 and 0xFF).toByte()

                    // 编码器
                    resp[8] = 0x10.toByte()
                    resp[9] = 0x20.toByte()

                    // 目标位置 (5 字节)
                    val tgt = ax?.target ?: 0L
                    encodeSignMag(tgt, resp, 10, 5)

                    // 实时速度 (3 字节)
                    val spd = ax?.speed ?: 0L
                    encodeSignMag(spd, resp, 15, 3)

                    // 实时位置 (5 字节)
                    val cur = ax?.pos ?: 0L
                    encodeSignMag(cur, resp, 18, 5)

                    // 位置误差 (5 字节)
                    encodeSignMag(0L, resp, 23, 5)

                    // homing_status
                    resp[28] = if (ax?.homing == true) 0x04.toByte() else 0x00.toByte()

                    // motor_status: bit 0: enable, bit 1: in_position
                    var mSt = 0
                    if (ax?.enabled == true) mSt = mSt or 0x01
                    if (ax?.inPosition == true) mSt = mSt or 0x02
                    resp[29] = mSt.toByte()

                    resp[30] = ZdtConstants.CS
                    rxBuffer.write(resp)
                }
                ZdtConstants.CODE_MOVE -> {
                    val mode = data[10] // 0=相对, 1=绝对
                    val cw = data[2].toInt() == 0
                    val p3 = (data[6].toLong() and 0xFFL) shl 24
                    val p2 = (data[7].toLong() and 0xFFL) shl 16
                    val p1 = (data[8].toLong() and 0xFFL) shl 8
                    val p0 = data[9].toLong() and 0xFFL
                    val pulse = p3 or p2 or p1 or p0

                    if (ax != null) {
                        if (mode.toInt() == 0) {
                            // 相对移动微步脉冲 (假设 53.333 脉冲/mm 对应 1092 角度/mm)
                            val dAngle = (pulse * 20.48).toLong()
                            ax.target = if (cw) ax.pos + dAngle else ax.pos - dAngle
                        } else {
                            ax.target = pulse
                        }
                        ax.inPosition = false
                    }

                    // 返回成功写响应 (4 字节)
                    rxBuffer.write(byteArrayOf(addr.toByte(), code, ZdtConstants.STATUS_OK, ZdtConstants.CS))
                }
                ZdtConstants.CODE_JOG -> {
                    val cw = data[2].toInt() == 0
                    val spd = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
                    if (ax != null) {
                        ax.speed = spd.toLong()
                        if (spd > 0) {
                            ax.target = if (cw) ax.pos + 50000L else ax.pos - 50000L
                            ax.inPosition = false
                        } else {
                            ax.target = ax.pos
                            ax.inPosition = true
                        }
                    }
                    rxBuffer.write(byteArrayOf(addr.toByte(), code, ZdtConstants.STATUS_OK, ZdtConstants.CS))
                }
                ZdtConstants.CODE_HOME -> {
                    if (ax != null) {
                        ax.homing = true
                        ax.homingTicks = 0
                        ax.inPosition = false
                    }
                    rxBuffer.write(byteArrayOf(addr.toByte(), code, ZdtConstants.STATUS_OK, ZdtConstants.CS))
                }
                ZdtConstants.CODE_STOP_HOME, ZdtConstants.CODE_ESTOP -> {
                    if (ax != null) {
                        ax.homing = false
                        ax.target = ax.pos
                        ax.speed = 0L
                        ax.inPosition = true
                    }
                    rxBuffer.write(byteArrayOf(addr.toByte(), code, ZdtConstants.STATUS_OK, ZdtConstants.CS))
                }
                ZdtConstants.CODE_SET_HOME -> {
                    if (ax != null) {
                        ax.pos = 0L
                        ax.target = 0L
                    }
                    rxBuffer.write(byteArrayOf(addr.toByte(), code, ZdtConstants.STATUS_OK, ZdtConstants.CS))
                }
                ZdtConstants.CODE_ENABLE -> {
                    val en = data[3].toInt() == 1
                    ax?.enabled = en
                    rxBuffer.write(byteArrayOf(addr.toByte(), code, ZdtConstants.STATUS_OK, ZdtConstants.CS))
                }
                else -> {
                    rxBuffer.write(byteArrayOf(addr.toByte(), code, ZdtConstants.STATUS_OK, ZdtConstants.CS))
                }
            }
        }
    }

    override fun read(dest: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            synchronized(rxBuffer) {
                val available = rxBuffer.size()
                if (available >= length) {
                    val bytes = rxBuffer.toByteArray()
                    System.arraycopy(bytes, 0, dest, offset, length)
                    rxBuffer.reset()
                    if (bytes.size > length) {
                        rxBuffer.write(bytes, length, bytes.size - length)
                    }
                    return length
                }
            }
            try { Thread.sleep(5) } catch (_: Exception) {}
        }
        return 0
    }

    private fun encodeSignMag(value: Long, buf: ByteArray, offset: Int, len: Int) {
        val sign = if (value < 0) 1 else 0
        buf[offset] = sign.toByte()
        val absVal = kotlin.math.abs(value)
        val dataLen = len - 1
        for (i in 0 until dataLen) {
            val shift = (dataLen - 1 - i) * 8
            buf[offset + 1 + i] = ((absVal shr shift) and 0xFFL).toByte()
        }
    }
}
