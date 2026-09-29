package com.zdt.stage.core.serial

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * 将 usb-serial-for-android 的 UsbSerialPort 适配为 ISerialPort 接口。
 */
class UsbSerialPortAdapter(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection
) : ISerialPort {

    override val isOpen: Boolean
        get() = port.isOpen

    override val portName: String
        get() = "USB:${port.device.deviceName}:p${port.portNumber}"

    @Throws(IOException::class)
    override fun open(baudRate: Int) {
        port.open(connection)
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        try {
            port.dtr = true
            port.rts = true
        } catch (_: Exception) {
            // 部分芯片可能不支持 DTR/RTS，忽略
        }
    }

    @Throws(IOException::class)
    override fun close() {
        if (port.isOpen) {
            try {
                port.close()
            } catch (_: Exception) {}
        }
        try {
            connection.close()
        } catch (_: Exception) {}
    }

    @Throws(IOException::class)
    override fun purgeHwBuffers(purgeRead: Boolean, purgeWrite: Boolean) {
        try {
            port.purgeHwBuffers(purgeRead, purgeWrite)
        } catch (_: Exception) {}
    }

    @Throws(IOException::class)
    override fun write(data: ByteArray, timeoutMs: Int) {
        port.write(data, timeoutMs)
    }

    @Throws(IOException::class)
    override fun read(dest: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val temp = ByteArray(length)
        val n = port.read(temp, timeoutMs)
        if (n > 0) {
            System.arraycopy(temp, 0, dest, offset, n)
        }
        return n
    }
}
