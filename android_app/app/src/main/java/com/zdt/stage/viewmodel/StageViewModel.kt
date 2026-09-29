package com.zdt.stage.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zdt.stage.core.motion.ScriptProgress
import com.zdt.stage.core.serial.ConnectionState
import com.zdt.stage.core.serial.MockSerialPort
import com.zdt.stage.core.serial.UsbSerialManager
import com.zdt.stage.data.model.AppConfig
import com.zdt.stage.data.model.AxisSnapshot
import com.zdt.stage.data.model.Calibration
import com.zdt.stage.data.model.Preset
import com.zdt.stage.data.model.ScriptPoint
import com.zdt.stage.data.repository.AppConfigRepository
import com.zdt.stage.data.repository.StageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

class StageViewModel(application: Application) : AndroidViewModel(application) {

    private val configRepo = AppConfigRepository(application)
    val stageRepo = StageRepository(configRepo)

    private val usbSerialManager = UsbSerialManager(
        context = application,
        onDeviceAttached = { scanDevices() },
        onDeviceDetached = { scanDevices() }
    )

    val config: StateFlow<AppConfig> = stageRepo.config
    val connectionState: StateFlow<ConnectionState> = stageRepo.connectionState
    val snapshots: StateFlow<Map<String, AxisSnapshot>> = stageRepo.snapshots
    val scriptProgress: StateFlow<ScriptProgress> = stageRepo.scriptProgress

    private val _availableDevices = MutableStateFlow<List<UsbSerialManager.DeviceItem>>(emptyList())
    val availableDevices: StateFlow<List<UsbSerialManager.DeviceItem>> = _availableDevices.asStateFlow()

    private val _selectedDeviceIndex = MutableStateFlow(-1) // -1 代表 Mock 仿真模式
    val selectedDeviceIndex: StateFlow<Int> = _selectedDeviceIndex.asStateFlow()

    private val _statusMessage = MutableStateFlow("系统就绪")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    init {
        scanDevices()

        // 监听命令与错误消息广播
        viewModelScope.launch {
            stageRepo.commandEvents.collect { cmd ->
                _statusMessage.value = "${cmd.axis.uppercase()}: ${cmd.message} ${if (cmd.ok) "✓" else "✗"}"
            }
        }
        viewModelScope.launch {
            stageRepo.errorEvents.collect { err ->
                _statusMessage.value = err
            }
        }
    }

    fun setTab(index: Int) {
        _currentTab.value = index
    }

    fun scanDevices() {
        val list = usbSerialManager.findAvailablePorts()
        _availableDevices.value = list
        if (list.isNotEmpty() && _selectedDeviceIndex.value == -1) {
            _selectedDeviceIndex.value = 0
        }
    }

    fun selectDevice(index: Int) {
        _selectedDeviceIndex.value = index
    }

    fun connect(baud: Int, addresses: Map<String, Int>, mock: Boolean) {
        viewModelScope.launch {
            stageRepo.updateAddresses(addresses)

            if (mock || _selectedDeviceIndex.value == -1 || _availableDevices.value.isEmpty()) {
                val mockPort = MockSerialPort()
                val ok = stageRepo.connect(mockPort, baud)
                if (ok) {
                    _statusMessage.value = "已连接虚拟仿真串口 (Mock 模式)"
                    _currentTab.value = 1 // 自动切换至“点动”Tab
                }
            } else {
                val item = _availableDevices.value.getOrNull(_selectedDeviceIndex.value)
                if (item == null) {
                    _statusMessage.value = "未找到选择的 USB 设备"
                    return@launch
                }

                if (!usbSerialManager.hasPermission(item.device)) {
                    usbSerialManager.requestPermission(item.device) { granted, _ ->
                        if (granted) {
                            connectWithPort(item, baud)
                        } else {
                            _statusMessage.value = "用户拒绝了 USB 串口授权"
                        }
                    }
                } else {
                    connectWithPort(item, baud)
                }
            }
        }
    }

    private fun connectWithPort(item: UsbSerialManager.DeviceItem, baud: Int) {
        viewModelScope.launch {
            try {
                val port = usbSerialManager.openPort(item.port, baud)
                val ok = stageRepo.connect(port, baud)
                if (ok) {
                    _statusMessage.value = "USB 串口已连接: ${item.displayName}"
                    _currentTab.value = 1 // 自动切到点动页
                }
            } catch (e: Exception) {
                _statusMessage.value = "连接打开失败: ${e.message}"
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            stageRepo.disconnect()
            _statusMessage.value = "串口已断开"
        }
    }

    // ==================== 运动操作 ====================

    fun jogStart(axis: String, isPlus: Boolean) {
        viewModelScope.launch { stageRepo.jog(axis, isPlus) }
    }

    fun jogStop(axis: String) {
        viewModelScope.launch { stageRepo.stopJog(axis) }
    }

    fun gotoSingle(axis: String, targetMm: Double) {
        viewModelScope.launch {
            val ok = stageRepo.gotoMm(axis, targetMm)
            if (!ok) _statusMessage.value = "${axis.uppercase()} 轴未标定或离线，无法定位"
        }
    }

    fun gotoParallel(targetX: Double, targetY: Double, targetZ: Double) {
        viewModelScope.launch { stageRepo.gotoParallel(targetX, targetY, targetZ) }
    }

    fun gotoOrigin() {
        viewModelScope.launch { stageRepo.gotoOrigin() }
    }

    fun estop() {
        viewModelScope.launch { stageRepo.estop() }
    }

    fun home(axis: String) {
        viewModelScope.launch { stageRepo.home(axis) }
    }

    fun stopHome(axis: String) {
        viewModelScope.launch { stageRepo.stopHome(axis) }
    }

    fun setHome(axis: String) {
        viewModelScope.launch { stageRepo.setHome(axis) }
    }

    fun setOriginHere(axis: String) {
        stageRepo.setOriginHere(axis)
        _statusMessage.value = "已将 ${axis.uppercase()} 轴当前位置设为显示原点"
    }

    fun enableAll() {
        viewModelScope.launch { stageRepo.enableAll() }
    }

    fun disableAll() {
        viewModelScope.launch { stageRepo.disableAll() }
    }

    fun updateMotion(axis: String, jogSpeed: Int? = null, jogAccel: Int? = null, moveSpeed: Int? = null, moveAccel: Int? = null) {
        stageRepo.updateMotionParams(axis, jogSpeed, jogAccel, moveSpeed, moveAccel)
    }

    fun updateAddresses(addresses: Map<String, Int>) {
        stageRepo.updateAddresses(addresses)
    }

    // ==================== 示教 ====================

    fun addPreset(name: String, x: Double, y: Double, z: Double) {
        stageRepo.addPreset(name, x, y, z)
    }

    fun updatePreset(index: Int, preset: Preset) {
        stageRepo.updatePreset(index, preset)
    }

    fun deletePreset(index: Int) {
        stageRepo.deletePreset(index)
    }

    fun clearPresets() {
        stageRepo.clearPresets()
    }

    // ==================== 脚本 ====================

    fun addScriptPoint(x: Double, y: Double, z: Double, dwell: Double) {
        stageRepo.addScriptPoint(x, y, z, dwell)
    }

    fun updateScriptPoint(index: Int, point: ScriptPoint) {
        stageRepo.updateScriptPoint(index, point)
    }

    fun deleteScriptPoint(index: Int) {
        stageRepo.deleteScriptPoint(index)
    }

    fun moveScriptPoint(index: Int, up: Boolean) {
        stageRepo.moveScriptPoint(index, up)
    }

    fun clearScriptPoints() {
        stageRepo.clearScriptPoints()
    }

    fun startScript(loops: Int, speedOverride: Int, accelOverride: Int) {
        val pts = config.value.script
        stageRepo.scriptExecutor.start(pts, loops, speedOverride, accelOverride)
    }

    fun stopScript() {
        stageRepo.scriptExecutor.stop()
    }

    // ==================== 标定 ====================

    suspend fun calibrateMove(axis: String, pulses: Long): Boolean {
        return stageRepo.calibrateMove(axis, pulses)
    }

    fun applyCalibration(axis: String, cal: Calibration) {
        stageRepo.applyCalibration(axis, cal)
    }

    fun setInvert(axis: String, invert: Boolean) {
        stageRepo.setInvert(axis, invert)
    }

    // ==================== 配置导入与导出 (SAF) ====================

    fun exportConfig(out: OutputStream) {
        viewModelScope.launch {
            val ok = configRepo.exportToStream(out)
            _statusMessage.value = if (ok) "配置导出成功" else "配置导出失败"
        }
    }

    fun importConfig(input: InputStream) {
        viewModelScope.launch {
            val ok = configRepo.importFromStream(input)
            _statusMessage.value = if (ok) "配置导入成功，已刷新参数" else "配置导入解析失败"
        }
    }

    override fun onCleared() {
        super.onCleared()
        stageRepo.release()
        usbSerialManager.release()
    }
}
