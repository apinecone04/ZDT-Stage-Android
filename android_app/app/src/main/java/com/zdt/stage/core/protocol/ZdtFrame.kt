package com.zdt.stage.core.protocol

/**
 * 协议原始帧与解析后的数据封装。
 */
data class SysStatusRaw(
    val address: Int,
    val busVoltageMv: Int,
    val phaseCurrentMa: Int,
    val encoderValue: Int,
    val targetPosition: Long,
    val realTimeSpeed: Long,
    val realTimePosition: Long,
    val positionError: Long,
    val homingStatus: Int,
    val motorStatus: Int
) {
    val busVoltageV: Float get() = busVoltageMv / 1000.0f
    val phaseCurrentA: Float get() = phaseCurrentMa / 1000.0f

    // homingStatus 位掩码
    val isHoming: Boolean get() = (homingStatus and 0x04) != 0
    val isHomingFailed: Boolean get() = (homingStatus and 0x08) != 0

    // motorStatus 位掩码
    val isEnabled: Boolean get() = (motorStatus and 0x01) != 0
    val isInPosition: Boolean get() = (motorStatus and 0x02) != 0
    val isStalled: Boolean get() = (motorStatus and 0x04) != 0
    val isStallProtected: Boolean get() = (motorStatus and 0x08) != 0
}

data class WriteResult(
    val ok: Boolean,
    val statusCode: Byte,
    val message: String
)
