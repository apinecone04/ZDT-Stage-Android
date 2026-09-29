package com.zdt.stage.data.repository

import com.zdt.stage.core.motion.MotionUnits
import com.zdt.stage.core.motion.ScriptExecutor
import com.zdt.stage.core.motion.ScriptProgress
import com.zdt.stage.core.serial.CommandEvent
import com.zdt.stage.core.serial.ConnectionState
import com.zdt.stage.core.serial.ISerialPort
import com.zdt.stage.core.serial.Rs485SerialDispatcher
import com.zdt.stage.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 上位机核心业务逻辑仓储（对标 Python 版本的 Backend）：
 * - 协调 RS485 串行调度器与配置持久化
 * - 处理带符号脉冲定位换算、Invert 方向反转
 * - 机械回零完成后自动设显示原点
 * - 自动化脚本执行与安全联锁
 * - Z 轴重力防坠落安全保障
 */
class StageRepository(
    val configRepo: AppConfigRepository,
    val dispatcher: Rs485SerialDispatcher = Rs485SerialDispatcher()
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val config: StateFlow<AppConfig> get() = configRepo.config
    val connectionState: StateFlow<ConnectionState> get() = dispatcher.connectionState
    val snapshots: StateFlow<Map<String, AxisSnapshot>> get() = dispatcher.snapshots
    val commandEvents: SharedFlow<CommandEvent> get() = dispatcher.commandEvents
    val errorEvents: SharedFlow<String> get() = dispatcher.errorEvents

    val scriptExecutor = ScriptExecutor(
        gotoAction = { axis, targetMm, speedOverride, accelOverride ->
            gotoMm(axis, targetMm, speedOverride, accelOverride)
        },
        estopAction = {
            estop(null)
        },
        getSnapshot = { axis ->
            snapshots.value[axis]
        }
    )

    val scriptProgress: StateFlow<ScriptProgress> get() = scriptExecutor.progress

    // ==================== 连接管理 ====================

    suspend fun connect(port: ISerialPort, baud: Int): Boolean {
        val cfg = config.value
        return dispatcher.connect(
            port = port,
            baudRate = baud,
            addresses = cfg.addresses,
            pollIntervalSeconds = cfg.poll_interval
        )
    }

    suspend fun disconnect() {
        scriptExecutor.stop()
        // 断开时不主动发送任何 disable 指令，保持 Z 轴通电自锁！
        dispatcher.disconnect()
    }

    // ==================== 点动 (Jog) ====================

    suspend fun jog(axis: String, cw: Boolean): Boolean {
        val cfg = config.value.axes[axis] ?: return false
        val cal = cfg.calibration
        // 方向反转：invert 轴点击“+”时实际发 CCW，确保“+”永远对应“+mm”增大
        val actualCw = if (cal.invert) !cw else cw
        return dispatcher.jog(axis, actualCw, cfg.jog_speed, cfg.jog_accel)
    }

    suspend fun stopJog(axis: String): Boolean {
        return dispatcher.stop(axis)
    }

    // ==================== 定位 (Goto) ====================

    suspend fun gotoMm(
        axis: String,
        targetMm: Double,
        speedOverride: Int? = null,
        accelOverride: Int? = null
    ): Boolean {
        val snap = snapshots.value[axis] ?: return false
        if (!snap.online) return false

        val cfg = config.value.axes[axis] ?: return false
        val cal = cfg.calibration
        if (!cal.valid || cal.pulses_per_mm == 0.0) {
            // 未标定拦截
            return false
        }

        // 软限位截断 [0.0, travel_mm]
        val safeTarget = targetMm.coerceIn(0.0, cfg.travel_mm)
        val curMm = snap.mm(cal)
        val delta = safeTarget - curMm
        val pulses = MotionUnits.deltaMmToPulses(delta, cal)

        if (pulses == 0L) {
            return true
        }

        val speed = speedOverride ?: cfg.move_speed
        val accel = accelOverride ?: cfg.move_accel
        val isCw = pulses > 0

        return dispatcher.moveRelative(
            axis = axis,
            cw = isCw,
            pulses = kotlin.math.abs(pulses),
            speedRpm = speed,
            accel = accel
        )
    }

    suspend fun gotoParallel(targetX: Double, targetY: Double, targetZ: Double) = coroutineScope {
        launch { gotoMm("x", targetX) }
        launch { gotoMm("y", targetY) }
        launch { gotoMm("z", targetZ) }
    }

    suspend fun gotoOrigin() {
        gotoParallel(0.0, 0.0, 0.0)
    }

    suspend fun estop(axis: String? = null) {
        scriptExecutor.stop()
        dispatcher.estop(axis)
    }

    // ==================== 回零与原点 (Home) ====================

    suspend fun home(axis: String, mode: Int = 0): Boolean {
        val ok = dispatcher.home(axis, mode)
        if (ok) {
            // 启动协程监视回零状态，回零完成后自动将当前位置记录为原点 (origin_angle)
            scope.launch {
                delay(300)
                var wasHoming = false
                while (isActive) {
                    val s = snapshots.value[axis]
                    if (s == null || !s.online) break

                    if (s.homing) {
                        wasHoming = true
                    } else if (wasHoming) {
                        // 回零动作结束
                        if (!s.homing_failed) {
                            // 机械回零成功，自动设当前为显示原点
                            setOriginHere(axis)
                        }
                        break
                    }
                    delay(150)
                }
            }
        }
        return ok
    }

    suspend fun stopHome(axis: String): Boolean {
        return dispatcher.stopHome(axis)
    }

    suspend fun setHome(axis: String, store: Int = 0): Boolean {
        val ok = dispatcher.setHome(axis, store)
        if (ok) {
            configRepo.updateConfig { cfg ->
                val axCfg = cfg.axes[axis] ?: return@updateConfig cfg
                val newCal = axCfg.calibration.copy(origin_angle = 0L)
                cfg.copy(axes = cfg.axes + (axis to axCfg.copy(calibration = newCal)))
            }
        }
        return ok
    }

    fun setOriginHere(axis: String) {
        val snap = snapshots.value[axis] ?: return
        configRepo.updateConfig { cfg ->
            val axCfg = cfg.axes[axis] ?: return@updateConfig cfg
            val newCal = axCfg.calibration.copy(origin_angle = snap.angle_position)
            cfg.copy(axes = cfg.axes + (axis to axCfg.copy(calibration = newCal)))
        }
    }

    // ==================== 使能与去使能 ====================

    suspend fun enable(axis: String): Boolean = dispatcher.enable(axis)

    suspend fun disable(axis: String): Boolean = dispatcher.disable(axis)

    suspend fun enableAll() = dispatcher.enableAll()

    suspend fun disableAll() = dispatcher.disableAll()

    // ==================== 标定业务 ====================

    suspend fun calibrateMove(axis: String, pulses: Long): Boolean {
        val cfg = config.value.axes[axis] ?: return false
        return dispatcher.moveRelative(
            axis = axis,
            cw = true,
            pulses = pulses,
            speedRpm = cfg.move_speed,
            accel = cfg.move_accel
        )
    }

    fun applyCalibration(axis: String, cal: Calibration) {
        configRepo.updateConfig { cfg ->
            val axCfg = cfg.axes[axis] ?: return@updateConfig cfg
            cfg.copy(axes = cfg.axes + (axis to axCfg.copy(calibration = cal)))
        }
    }

    fun setInvert(axis: String, invert: Boolean) {
        configRepo.updateConfig { cfg ->
            val axCfg = cfg.axes[axis] ?: return@updateConfig cfg
            val newCal = axCfg.calibration.copy(invert = invert)
            cfg.copy(axes = cfg.axes + (axis to axCfg.copy(calibration = newCal)))
        }
    }

    fun updateMotionParams(
        axis: String,
        jogSpeed: Int? = null,
        jogAccel: Int? = null,
        moveSpeed: Int? = null,
        moveAccel: Int? = null
    ) {
        configRepo.updateConfig { cfg ->
            val axCfg = cfg.axes[axis] ?: return@updateConfig cfg
            val updated = axCfg.copy(
                jog_speed = jogSpeed ?: axCfg.jog_speed,
                jog_accel = jogAccel ?: axCfg.jog_accel,
                move_speed = moveSpeed ?: axCfg.move_speed,
                move_accel = moveAccel ?: axCfg.move_accel
            )
            cfg.copy(axes = cfg.axes + (axis to updated))
        }
    }

    fun updateAddresses(addresses: Map<String, Int>) {
        configRepo.updateConfig { it.copy(addresses = addresses) }
    }

    // ==================== 示教预设点 (Presets) ====================

    fun addPreset(name: String, x: Double, y: Double, z: Double) {
        configRepo.updateConfig { cfg ->
            val list = cfg.presets.toMutableList()
            list.add(Preset(name, x, y, z))
            cfg.copy(presets = list)
        }
    }

    fun updatePreset(index: Int, preset: Preset) {
        configRepo.updateConfig { cfg ->
            if (index in cfg.presets.indices) {
                val list = cfg.presets.toMutableList()
                list[index] = preset
                cfg.copy(presets = list)
            } else cfg
        }
    }

    fun deletePreset(index: Int) {
        configRepo.updateConfig { cfg ->
            if (index in cfg.presets.indices) {
                val list = cfg.presets.toMutableList()
                list.removeAt(index)
                cfg.copy(presets = list)
            } else cfg
        }
    }

    fun clearPresets() {
        configRepo.updateConfig { it.copy(presets = emptyList()) }
    }

    // ==================== 脚本路径 (Script) ====================

    fun addScriptPoint(x: Double, y: Double, z: Double, dwell: Double) {
        configRepo.updateConfig { cfg ->
            val list = cfg.script.toMutableList()
            list.add(ScriptPoint(x, y, z, dwell))
            cfg.copy(script = list)
        }
    }

    fun updateScriptPoint(index: Int, point: ScriptPoint) {
        configRepo.updateConfig { cfg ->
            if (index in cfg.script.indices) {
                val list = cfg.script.toMutableList()
                list[index] = point
                cfg.copy(script = list)
            } else cfg
        }
    }

    fun deleteScriptPoint(index: Int) {
        configRepo.updateConfig { cfg ->
            if (index in cfg.script.indices) {
                val list = cfg.script.toMutableList()
                list.removeAt(index)
                cfg.copy(script = list)
            } else cfg
        }
    }

    fun moveScriptPoint(index: Int, up: Boolean) {
        configRepo.updateConfig { cfg ->
            val targetIndex = if (up) index - 1 else index + 1
            if (index in cfg.script.indices && targetIndex in cfg.script.indices) {
                val list = cfg.script.toMutableList()
                val item = list.removeAt(index)
                list.add(targetIndex, item)
                cfg.copy(script = list)
            } else cfg
        }
    }

    fun clearScriptPoints() {
        configRepo.updateConfig { it.copy(script = emptyList()) }
    }

    fun release() {
        scriptExecutor.release()
        dispatcher.release()
        scope.cancel()
    }
}
