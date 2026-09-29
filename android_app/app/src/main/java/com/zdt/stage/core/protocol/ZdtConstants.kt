package com.zdt.stage.core.protocol

/**
 * 张大头 ZDT_X42S 步进电机底层协议常量（经真机严格验证）。
 */
object ZdtConstants {
    // 固定校验尾字节
    const val CS: Byte = 0x6B.toByte()

    // 功能码 (Function Code)
    const val CODE_ENABLE: Byte = 0xF3.toByte()
    const val CODE_JOG: Byte = 0xF6.toByte()
    const val CODE_MOVE: Byte = 0xFD.toByte()
    const val CODE_ESTOP: Byte = 0xFE.toByte()
    const val CODE_SET_HOME: Byte = 0x93.toByte()
    const val CODE_HOME: Byte = 0x9A.toByte()
    const val CODE_STOP_HOME: Byte = 0x9C.toByte()
    const val CODE_GET_POS: Byte = 0x36.toByte()
    const val CODE_GET_TARGET: Byte = 0x33.toByte()
    const val CODE_GET_SYS_STATUS: Byte = 0x43.toByte()

    // 子协议码 (Protocol Sub-Code)
    const val PROTO_ENABLE: Byte = 0xAB.toByte()
    const val PROTO_ESTOP: Byte = 0x98.toByte()
    const val PROTO_SET_HOME: Byte = 0x88.toByte()
    const val PROTO_STOP_HOME: Byte = 0x48.toByte()
    const val PROTO_GET_SYS_STATUS: Byte = 0x7A.toByte()

    // 状态响应码
    const val STATUS_OK: Byte = 0x02.toByte()
    const val STATUS_COND_ERR: Byte = 0xE2.toByte()

    // 响应定长
    const val RESP_LEN_WRITE: Int = 4
    const val RESP_LEN_GET_POS: Int = 8
    const val RESP_LEN_GET_TARGET: Int = 8
    const val RESP_LEN_SYS_STATUS: Int = 31

    // 默认超时与重试
    const val TIMEOUT_MS: Long = 150L
    const val DEFAULT_RETRIES: Int = 3
}
