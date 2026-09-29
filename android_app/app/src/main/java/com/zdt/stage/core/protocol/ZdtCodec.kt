package com.zdt.stage.core.protocol

/**
 * 协议编解码工具类：
 * - Sign-Magnitude 符号数编解码
 * - 指令帧构建与封装（末尾追加 0x6B）
 * - 响应帧定长与校验位验证
 * - 31 字节系统状态帧解析
 */
object ZdtCodec {

    /**
     * 带符号数解码：首字节为符号（1=负，0=正），其余大端无符号整型。
     */
    fun decodeSignMagnitude(bytes: ByteArray, offset: Int, length: Int): Long {
        if (length <= 0 || offset + length > bytes.size) return 0L
        val sign = if (bytes[offset].toInt() == 1) -1L else 1L
        var value = 0L
        for (i in (offset + 1) until (offset + length)) {
            value = (value shl 8) or (bytes[i].toLong() and 0xFFL)
        }
        return sign * value
    }

    /**
     * 将指令载荷包装为完整通信帧（追加 0x6B 校验尾）。
     */
    fun packFrame(body: ByteArray): ByteArray {
        val frame = ByteArray(body.size + 1)
        System.arraycopy(body, 0, frame, 0, body.size)
        frame[body.size] = ZdtConstants.CS
        return frame
    }

    /**
     * 校验响应帧是否合法：长度、目标地址、末尾校验位。
     */
    fun verifyResponse(resp: ByteArray?, expectedLen: Int, expectedAddr: Int): Boolean {
        if (resp == null || resp.size != expectedLen) return false
        val addrMatch = (resp[0].toInt() and 0xFF) == (expectedAddr and 0xFF)
        val csMatch = resp[expectedLen - 1] == ZdtConstants.CS
        return addrMatch && csMatch
    }

    // ==================== 指令帧构建 ====================

    /**
     * 电机使能 / 去使能帧 (0xF3)
     */
    fun buildEnableFrame(addr: Int, enable: Boolean, sync: Int = 0): ByteArray {
        val stateByte: Byte = if (enable) 0x01.toByte() else 0x00.toByte()
        return packFrame(
            byteArrayOf(
                addr.toByte(),
                ZdtConstants.CODE_ENABLE,
                ZdtConstants.PROTO_ENABLE,
                stateByte,
                sync.toByte()
            )
        )
    }

    /**
     * 点动控制帧 (0xF6)
     */
    fun buildJogFrame(addr: Int, cw: Boolean, speedRpm: Int, accel: Int, sync: Int = 0): ByteArray {
        val dirByte: Byte = if (cw) 0x00.toByte() else 0x01.toByte()
        val spd = speedRpm.coerceIn(0, 3000)
        val acc = accel.coerceIn(0, 255)
        return packFrame(
            byteArrayOf(
                addr.toByte(),
                ZdtConstants.CODE_JOG,
                dirByte,
                ((spd shr 8) and 0xFF).toByte(),
                (spd and 0xFF).toByte(),
                acc.toByte(),
                sync.toByte()
            )
        )
    }

    /**
     * 停止点动帧 (即速度为 0、加速度为 0 的 Jog 帧)
     */
    fun buildStopFrame(addr: Int, sync: Int = 0): ByteArray {
        return buildJogFrame(addr, cw = true, speedRpm = 0, accel = 0, sync = sync)
    }

    /**
     * 相对位移运动帧 (0xFD, mode=0x00)
     */
    fun buildMoveRelativeFrame(
        addr: Int,
        cw: Boolean,
        pulses: Long,
        speedRpm: Int,
        accel: Int,
        sync: Int = 0
    ): ByteArray {
        val dirByte: Byte = if (cw) 0x00.toByte() else 0x01.toByte()
        val spd = speedRpm.coerceIn(1, 3000)
        val acc = accel.coerceIn(0, 255)
        val p = pulses.coerceAtLeast(0L) and 0xFFFFFFFFL

        return packFrame(
            byteArrayOf(
                addr.toByte(),
                ZdtConstants.CODE_MOVE,
                dirByte,
                ((spd shr 8) and 0xFF).toByte(),
                (spd and 0xFF).toByte(),
                acc.toByte(),
                ((p shr 24) and 0xFF).toByte(),
                ((p shr 16) and 0xFF).toByte(),
                ((p shr 8) and 0xFF).toByte(),
                (p and 0xFF).toByte(),
                0x00.toByte(), // mode = 0 相对
                sync.toByte()
            )
        )
    }

    /**
     * 绝对位置运动帧 (0xFD, mode=0x01)
     */
    fun buildMoveAbsoluteFrame(
        addr: Int,
        targetCounts: Long,
        speedRpm: Int,
        accel: Int,
        sync: Int = 0
    ): ByteArray {
        val spd = speedRpm.coerceIn(1, 3000)
        val acc = accel.coerceIn(0, 255)
        val p = targetCounts and 0xFFFFFFFFL

        return packFrame(
            byteArrayOf(
                addr.toByte(),
                ZdtConstants.CODE_MOVE,
                0x00.toByte(),
                ((spd shr 8) and 0xFF).toByte(),
                (spd and 0xFF).toByte(),
                acc.toByte(),
                ((p shr 24) and 0xFF).toByte(),
                ((p shr 16) and 0xFF).toByte(),
                ((p shr 8) and 0xFF).toByte(),
                (p and 0xFF).toByte(),
                0x01.toByte(), // mode = 1 绝对
                sync.toByte()
            )
        )
    }

    /**
     * 急停控制帧 (0xFE)
     */
    fun buildEstopFrame(addr: Int, sync: Int = 0): ByteArray {
        return packFrame(
            byteArrayOf(
                addr.toByte(),
                ZdtConstants.CODE_ESTOP,
                ZdtConstants.PROTO_ESTOP,
                sync.toByte()
            )
        )
    }

    /**
     * 机械回零帧 (0x9A)
     */
    fun buildHomeFrame(addr: Int, homingMode: Int = 0, sync: Int = 0): ByteArray {
        return packFrame(
            byteArrayOf(
                addr.toByte(),
                ZdtConstants.CODE_HOME,
                (homingMode and 0xFF).toByte(),
                sync.toByte()
            )
        )
    }

    /**
     * 停止机械回零帧 (0x9C)
     */
    fun buildStopHomeFrame(addr: Int): ByteArray {
        return packFrame(
            byteArrayOf(
                addr.toByte(),
                ZdtConstants.CODE_STOP_HOME,
                ZdtConstants.PROTO_STOP_HOME
            )
        )
    }

    /**
     * 设置电机驱动板零点帧 (0x93)
     */
    fun buildSetHomeFrame(addr: Int, storeToEeprom: Int = 0): ByteArray {
        return packFrame(
            byteArrayOf(
                addr.toByte(),
                ZdtConstants.CODE_SET_HOME,
                ZdtConstants.PROTO_SET_HOME,
                storeToEeprom.toByte()
            )
        )
    }

    /**
     * 查询系统状态帧 (0x43 0x7A)
     */
    fun buildGetSysStatusFrame(addr: Int): ByteArray {
        return packFrame(
            byteArrayOf(
                addr.toByte(),
                ZdtConstants.CODE_GET_SYS_STATUS,
                ZdtConstants.PROTO_GET_SYS_STATUS
            )
        )
    }

    // ==================== 响应帧解析 ====================

    /**
     * 解析 4 字节通用写入响应 [addr, code, status, 0x6B]
     */
    fun parseWriteResult(resp: ByteArray?, expectedAddr: Int, expectedCode: Byte): WriteResult {
        if (!verifyResponse(resp, ZdtConstants.RESP_LEN_WRITE, expectedAddr)) {
            return WriteResult(ok = false, statusCode = 0x00, message = "校验失败或无响应")
        }
        val code = resp!![1]
        val status = resp[2]
        if (code == 0x00.toByte() || code == 0xEE.toByte()) {
            return WriteResult(ok = false, statusCode = status, message = "电机返回错误帧 (0x${Integer.toHexString(code.toInt() and 0xFF)})")
        }
        val ok = status == ZdtConstants.STATUS_OK
        val msg = if (ok) "执行成功" else if (status == ZdtConstants.STATUS_COND_ERR) "条件不满足(未使能或堵转保护)" else "状态码: 0x${Integer.toHexString(status.toInt() and 0xFF)}"
        return WriteResult(ok = ok, statusCode = status, message = msg)
    }

    /**
     * 解析 31 字节系统状态帧
     */
    fun parseSysStatus(resp: ByteArray?, expectedAddr: Int): SysStatusRaw? {
        if (!verifyResponse(resp, ZdtConstants.RESP_LEN_SYS_STATUS, expectedAddr)) {
            return null
        }
        val r = resp!!
        if (r[1] != ZdtConstants.CODE_GET_SYS_STATUS) return null

        // 有效数据在 2..29，共 28 字节
        // d[0..1] 为 0x1F 0x09 协议头
        // d[2..3] 即 r[4..5] -> bus_voltage_mv (无符号大端 16 位)
        val busVoltageMv = ((r[4].toInt() and 0xFF) shl 8) or (r[5].toInt() and 0xFF)

        // d[4..5] 即 r[6..7] -> phase_current_ma (无符号大端 16 位)
        val phaseCurrentMa = ((r[6].toInt() and 0xFF) shl 8) or (r[7].toInt() and 0xFF)

        // d[6..7] 即 r[8..9] -> encoder_value (无符号大端 16 位)
        val encoderVal = ((r[8].toInt() and 0xFF) shl 8) or (r[9].toInt() and 0xFF)

        // d[8..12] 即 r[10..14] -> target_position (5 字节符号数)
        val targetPos = decodeSignMagnitude(r, 10, 5)

        // d[13..15] 即 r[15..17] -> real_time_speed (3 字节符号数)
        val speed = decodeSignMagnitude(r, 15, 3)

        // d[16..20] 即 r[18..22] -> real_time_position (5 字节符号数)
        val realPos = decodeSignMagnitude(r, 18, 5)

        // d[21..25] 即 r[23..27] -> position_error (5 字节符号数)
        val posErr = decodeSignMagnitude(r, 23, 5)

        // d[26] 即 r[28] -> homing_status
        val homingSt = r[28].toInt() and 0xFF

        // d[27] 即 r[29] -> motor_status
        val motorSt = r[29].toInt() and 0xFF

        return SysStatusRaw(
            address = expectedAddr,
            busVoltageMv = busVoltageMv,
            phaseCurrentMa = phaseCurrentMa,
            encoderValue = encoderVal,
            targetPosition = targetPos,
            realTimeSpeed = speed,
            realTimePosition = realPos,
            positionError = posErr,
            homingStatus = homingSt,
            motorStatus = motorSt
        )
    }
}
