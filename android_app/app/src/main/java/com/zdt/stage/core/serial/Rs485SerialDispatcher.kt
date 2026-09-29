package com.zdt.stage.core.serial

import com.zdt.stage.core.protocol.SysStatusRaw
import com.zdt.stage.core.protocol.ZdtCodec
import com.zdt.stage.core.protocol.ZdtConstants
import com.zdt.stage.data.model.AXES
import com.zdt.stage.data.model.AxisSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.Executors

data class ConnectionState(
    val connected: Boolean = false,
    val portName: String = "",
    val baudRate: Int = 115200,
    val onlineMap: Map<String, Boolean> = mapOf("x" to false, "y" to false, "z" to false)
)

data class CommandEvent(
    val axis: String,
    val ok: Boolean,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * RS485 半双工总线串行通信调度器：
 * - 强制所有 I/O 运行在单一后台工作线程，杜绝多协程并发打乱半双工时序
 * - 严格遵循：“清缓冲 -> 发一帧 -> 定长等待应答 (150ms) -> 校验地址与 0x6B -> 失败重试 3 次”
 * - 周期性 100ms 轮询各轴系统状态，并将快照广播至 StateFlow
 */
class Rs485SerialDispatcher {

    private val singleThreadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RS485-Worker").apply { isDaemon = true }
    }
    private val dispatcher = singleThreadExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private var serialPort: ISerialPort? = null
    private var pollJob: Job? = null

    // 状态流
    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _snapshots = MutableStateFlow<Map<String, AxisSnapshot>>(
        AXES.associateWith { AxisSnapshot(axis = it, online = false) }
    )
    val snapshots: StateFlow<Map<String, AxisSnapshot>> = _snapshots.asStateFlow()

    private val _commandEvents = MutableSharedFlow<CommandEvent>(extraBufferCapacity = 64)
    val commandEvents: SharedFlow<CommandEvent> = _commandEvents.asSharedFlow()

    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    // 轴地址映射
    private var addressMap = mapOf("x" to 1, "y" to 2, "z" to 3)
    private var pollIntervalMs = 100L

    /**
     * 连接串口并探测各轴在线状态
     */
    suspend fun connect(
        port: ISerialPort,
        baudRate: Int,
        addresses: Map<String, Int>,
        pollIntervalSeconds: Double = 0.1
    ): Boolean = withContext(dispatcher) {
        disconnect()
        addressMap = addresses
        pollIntervalMs = (pollIntervalSeconds * 1000).toLong().coerceIn(20L, 1000L)

        try {
            if (!port.isOpen) {
                port.open(baudRate)
            }
            serialPort = port
        } catch (e: Exception) {
            _errorEvents.emit("打开串口失败: ${e.message}")
            _connectionState.value = ConnectionState(connected = false)
            return@withContext false
        }

        delay(100) // 等待串口电气稳定

        val online = mutableMapOf<String, Boolean>()
        val initialSnaps = _snapshots.value.toMutableMap()

        // 探测三轴在线状态
        for (axis in AXES) {
            val addr = addressMap[axis] ?: (AXES.indexOf(axis) + 1)
            val st = querySysStatusInternal(addr)
            if (st != null) {
                online[axis] = true
                initialSnaps[axis] = buildSnapshot(axis, st)
                // 默认使能在线轴
                try {
                    val enableFrame = ZdtCodec.buildEnableFrame(addr, true)
                    executeTxn(addr, enableFrame, ZdtConstants.RESP_LEN_WRITE)
                } catch (_: Exception) {}
            } else {
                online[axis] = false
                initialSnaps[axis] = AxisSnapshot(axis = axis, online = false)
                _errorEvents.emit("${axis.uppercase()} (站号 $addr) 未应答，标为离线")
            }
        }

        _snapshots.value = initialSnaps
        _connectionState.value = ConnectionState(
            connected = true,
            portName = port.portName,
            baudRate = baudRate,
            onlineMap = online
        )

        val onList = AXES.filter { online[it] == true }.map { it.uppercase() }
        _errorEvents.emit("已连接 ${port.portName}@$baudRate，在线: ${if (onList.isNotEmpty()) onList.joinToString(",") else "无"}")

        startPolling()
        return@withContext true
    }

    /**
     * 断开串口连接。
     * 【安全核心策略】：断开时严禁向电机下发任何 disable 指令！
     * 步进电机保持锁相自锁力矩，避免垂直 Z 轴因重力急坠。
     */
    suspend fun disconnect() = withContext(dispatcher) {
        stopPolling()
        serialPort?.let {
            try {
                it.close()
            } catch (_: Exception) {}
        }
        serialPort = null
        _connectionState.value = ConnectionState(connected = false)
        _snapshots.value = AXES.associateWith { AxisSnapshot(axis = it, online = false) }
    }

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                val state = _connectionState.value
                if (state.connected && state.onlineMap.values.any { it }) {
                    val snaps = _snapshots.value.toMutableMap()
                    var anyOfflineChanged = false
                    val onlineMap = state.onlineMap.toMutableMap()

                    for (axis in AXES) {
                        if (onlineMap[axis] != true) continue
                        val addr = addressMap[axis] ?: (AXES.indexOf(axis) + 1)
                        val st = querySysStatusInternal(addr)
                        if (st != null) {
                            snaps[axis] = buildSnapshot(axis, st)
                        } else {
                            // 轮询无应答标离线
                            val old = snaps[axis] ?: AxisSnapshot(axis = axis, online = false)
                            snaps[axis] = old.copy(online = false)
                            onlineMap[axis] = false
                            anyOfflineChanged = true
                            _errorEvents.emit("${axis.uppercase()} 轴通信超时，标记离线")
                        }
                    }

                    _snapshots.value = snaps
                    if (anyOfflineChanged) {
                        _connectionState.value = state.copy(onlineMap = onlineMap)
                    }
                }
                delay(pollIntervalMs)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ==================== 底层事务与指令实现 ====================

    /**
     * 发送一帧、定长读取响应并校验（带 3 次重试）
     */
    private fun executeTxn(address: Int, frame: ByteArray, expectedRespLen: Int): ByteArray? {
        val port = serialPort ?: return null
        if (!port.isOpen) return null

        for (attempt in 1..ZdtConstants.DEFAULT_RETRIES) {
            try {
                // 1. 发送前清空输入缓冲区
                port.purgeHwBuffers(purgeRead = true, purgeWrite = false)

                // 2. 发送完整数据帧
                port.write(frame, ZdtConstants.TIMEOUT_MS.toInt())

                // 3. 阻塞循环读取指定长度响应
                val resp = ByteArray(expectedRespLen)
                var bytesRead = 0
                val startTime = System.currentTimeMillis()

                while (bytesRead < expectedRespLen && (System.currentTimeMillis() - startTime) < ZdtConstants.TIMEOUT_MS) {
                    val n = port.read(resp, bytesRead, expectedRespLen - bytesRead, 50)
                    if (n > 0) bytesRead += n
                }

                // 4. 校验响应合法性
                if (bytesRead == expectedRespLen &&
                    (resp[0].toInt() and 0xFF) == (address and 0xFF) &&
                    resp[expectedRespLen - 1] == ZdtConstants.CS
                ) {
                    return resp
                }
            } catch (_: Exception) {
                // 重试
            }
        }
        return null
    }

    private fun querySysStatusInternal(address: Int): SysStatusRaw? {
        val frame = ZdtCodec.buildGetSysStatusFrame(address)
        val resp = executeTxn(address, frame, ZdtConstants.RESP_LEN_SYS_STATUS) ?: return null
        return ZdtCodec.parseSysStatus(resp, address)
    }

    private fun executeWrite(axis: String, address: Int, frame: ByteArray, code: Byte, desc: String): Boolean {
        val resp = executeTxn(address, frame, ZdtConstants.RESP_LEN_WRITE)
        val res = ZdtCodec.parseWriteResult(resp, address, code)
        scope.launch {
            _commandEvents.emit(CommandEvent(axis = axis, ok = res.ok, message = "$desc: ${res.message}"))
        }
        return res.ok
    }

    // ==================== 对外控制 API ====================

    suspend fun jog(axis: String, cw: Boolean, speedRpm: Int, accel: Int): Boolean = withContext(dispatcher) {
        val addr = addressMap[axis] ?: return@withContext false
        val frame = ZdtCodec.buildJogFrame(addr, cw, speedRpm, accel)
        executeWrite(axis, addr, frame, ZdtConstants.CODE_JOG, "Jog $speedRpm RPM ${if (cw) "CW" else "CCW"}")
    }

    suspend fun stop(axis: String): Boolean = withContext(dispatcher) {
        val addr = addressMap[axis] ?: return@withContext false
        val frame = ZdtCodec.buildStopFrame(addr)
        executeWrite(axis, addr, frame, ZdtConstants.CODE_JOG, "Stop")
    }

    suspend fun moveRelative(
        axis: String,
        cw: Boolean,
        pulses: Long,
        speedRpm: Int,
        accel: Int
    ): Boolean = withContext(dispatcher) {
        val addr = addressMap[axis] ?: return@withContext false
        val frame = ZdtCodec.buildMoveRelativeFrame(addr, cw, pulses, speedRpm, accel)
        executeWrite(axis, addr, frame, ZdtConstants.CODE_MOVE, "MoveRel ${pulses}p ${if (cw) "CW" else "CCW"}")
    }

    suspend fun estop(axis: String?): Unit = withContext(dispatcher) {
        val axes = if (axis == null) AXES else listOf(axis)
        for (a in axes) {
            val addr = addressMap[a] ?: continue
            val frame = ZdtCodec.buildEstopFrame(addr)
            executeWrite(a, addr, frame, ZdtConstants.CODE_ESTOP, "急停 (E-Stop)")
        }
    }

    suspend fun home(axis: String, mode: Int = 0): Boolean = withContext(dispatcher) {
        val addr = addressMap[axis] ?: return@withContext false
        val frame = ZdtCodec.buildHomeFrame(addr, mode)
        executeWrite(axis, addr, frame, ZdtConstants.CODE_HOME, "机械回零 (mode=$mode)")
    }

    suspend fun stopHome(axis: String): Boolean = withContext(dispatcher) {
        val addr = addressMap[axis] ?: return@withContext false
        val frame = ZdtCodec.buildStopHomeFrame(addr)
        executeWrite(axis, addr, frame, ZdtConstants.CODE_STOP_HOME, "停止回零")
    }

    suspend fun setHome(axis: String, store: Int = 0): Boolean = withContext(dispatcher) {
        val addr = addressMap[axis] ?: return@withContext false
        val frame = ZdtCodec.buildSetHomeFrame(addr, store)
        executeWrite(axis, addr, frame, ZdtConstants.CODE_SET_HOME, "设置驱动板零点")
    }

    suspend fun enable(axis: String): Boolean = withContext(dispatcher) {
        val addr = addressMap[axis] ?: return@withContext false
        val frame = ZdtCodec.buildEnableFrame(addr, true)
        executeWrite(axis, addr, frame, ZdtConstants.CODE_ENABLE, "使能")
    }

    suspend fun disable(axis: String): Boolean = withContext(dispatcher) {
        val addr = addressMap[axis] ?: return@withContext false
        val frame = ZdtCodec.buildEnableFrame(addr, false)
        executeWrite(axis, addr, frame, ZdtConstants.CODE_ENABLE, "去使能")
    }

    suspend fun enableAll(): Unit = withContext(dispatcher) {
        for (axis in AXES) {
            val addr = addressMap[axis] ?: continue
            val frame = ZdtCodec.buildEnableFrame(addr, true)
            executeWrite(axis, addr, frame, ZdtConstants.CODE_ENABLE, "使能")
        }
    }

    suspend fun disableAll(): Unit = withContext(dispatcher) {
        for (axis in AXES) {
            val addr = addressMap[axis] ?: continue
            val frame = ZdtCodec.buildEnableFrame(addr, false)
            executeWrite(axis, addr, frame, ZdtConstants.CODE_ENABLE, "去使能")
        }
    }

    fun release() {
        scope.cancel()
        singleThreadExecutor.shutdownNow()
    }

    private fun buildSnapshot(axis: String, st: SysStatusRaw): AxisSnapshot {
        return AxisSnapshot(
            axis = axis,
            online = true,
            angle_position = st.realTimePosition,
            target_position = st.targetPosition,
            speed_rpm = st.realTimeSpeed.toFloat(),
            bus_voltage = st.busVoltageV,
            phase_current = st.phaseCurrentA,
            enabled = st.isEnabled,
            in_position = st.isInPosition,
            stalled = st.isStalled,
            stall_protect = st.isStallProtected,
            homing = st.isHoming,
            homing_failed = st.isHomingFailed
        )
    }
}
