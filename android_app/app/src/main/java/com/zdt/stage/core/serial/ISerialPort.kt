package com.zdt.stage.core.serial

import java.io.IOException

/**
 * 串口底层抽象接口，统一真实 USB 串口与虚拟 Mock 串口。
 */
interface ISerialPort {
    val isOpen: Boolean
    val portName: String

    @Throws(IOException::class)
    fun open(baudRate: Int)

    @Throws(IOException::class)
    fun close()

    @Throws(IOException::class)
    fun purgeHwBuffers(purgeRead: Boolean, purgeWrite: Boolean)

    @Throws(IOException::class)
    fun write(data: ByteArray, timeoutMs: Int)

    @Throws(IOException::class)
    fun read(dest: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
}
