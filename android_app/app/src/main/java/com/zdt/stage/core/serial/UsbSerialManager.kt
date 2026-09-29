package com.zdt.stage.core.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

/**
 * Android USB Host 串口管理器：
 * - 扫描所有支持的 USB 串口硬件 (FTDI, CP210x, CH340, PL2303, CDC)
 * - 兼容 Android 11+ 的 PendingIntent.FLAG_IMMUTABLE 权限申请
 * - 监听 USB 插拔热事件
 */
class UsbSerialManager(
    private val context: Context,
    private val onDeviceAttached: ((UsbDevice) -> Unit)? = null,
    private val onDeviceDetached: ((UsbDevice) -> Unit)? = null
) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.zdt.stage.USB_PERMISSION"
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    data class DeviceItem(
        val device: UsbDevice,
        val port: UsbSerialPort,
        val displayName: String
    )

    private var permissionCallback: ((Boolean, UsbDevice) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        permissionCallback?.invoke(granted, device)
                        permissionCallback = null
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { onDeviceAttached?.invoke(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { onDeviceDetached?.invoke(it) }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun release() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }

    /**
     * 扫描系统中连接的所有有效 USB 串口设备
     */
    fun findAvailablePorts(): List<DeviceItem> {
        val prober = UsbSerialProber.getDefaultProber()
        val drivers = prober.findAllDrivers(usbManager)
        val result = mutableListOf<DeviceItem>()

        for (driver in drivers) {
            val device = driver.device
            for (port in driver.ports) {
                val vendor = device.manufacturerName ?: "VID:${device.vendorId}"
                val product = device.productName ?: "PID:${device.productId}"
                val name = "$vendor $product (${device.deviceName} #p${port.portNumber})"
                result.add(DeviceItem(device, port, name))
            }
        }
        return result
    }

    /**
     * 检查是否有 USB 权限
     */
    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * 请求 USB 权限（处理 Android 11+ FLAG_MUTABLE / FLAG_IMMUTABLE 规范）
     */
    fun requestPermission(device: UsbDevice, onResult: (Boolean, UsbDevice) -> Unit) {
        if (hasPermission(device)) {
            onResult(true, device)
            return
        }
        permissionCallback = onResult
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * 打开指定的端口并包装为 ISerialPort
     */
    @Throws(IOException::class)
    fun openPort(port: UsbSerialPort, baudRate: Int): ISerialPort {
        val connection = usbManager.openDevice(port.device)
            ?: throw IOException("无法打开 USB 设备连接，可能缺少 USB 权限")
        val adapter = UsbSerialPortAdapter(port, connection)
        adapter.open(baudRate)
        return adapter
    }
}
