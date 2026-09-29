package com.zdt.stage.core.motion

import com.zdt.stage.data.model.AXES
import com.zdt.stage.data.model.AxisSnapshot
import com.zdt.stage.data.model.ScriptPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScriptRunState {
    IDLE,
    MOVING,
    DWELLING,
    STOPPED,
    COMPLETED,
    ERROR
}

data class ScriptProgress(
    val state: ScriptRunState = ScriptRunState.IDLE,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val currentLoop: Int = 0,
    val targetLoops: Int = 0, // 0 = 无限循环
    val dwellRemainingSeconds: Double = 0.0,
    val message: String = "未运行"
)

/**
 * 路径脚本执行器状态机（100% 对齐 Python 版本 ScriptPanel 逻辑）：
 * - 校验三轴在线与标定有效
 * - 执行阶段：定位移动 -> 等待到位 -> 停留倒计时 -> 下一点
 * - 异常安全联锁：检测到掉线或堵转立即急停中止
 */
class ScriptExecutor(
    private val gotoAction: suspend (axis: String, targetMm: Double, speedOverride: Int?, accelOverride: Int?) -> Boolean,
    private val estopAction: suspend () -> Unit,
    private val getSnapshot: (axis: String) -> AxisSnapshot?
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var runJob: Job? = null

    private val _progress = MutableStateFlow(ScriptProgress())
    val progress: StateFlow<ScriptProgress> = _progress.asStateFlow()

    fun start(
        points: List<ScriptPoint>,
        loopsTarget: Int = 1,
        speedOverride: Int = 0,
        accelOverride: Int = 0
    ) {
        stop()
        if (points.isEmpty()) {
            _progress.value = ScriptProgress(state = ScriptRunState.ERROR, message = "路径列表为空")
            return
        }

        runJob = scope.launch {
            _progress.value = ScriptProgress(
                state = ScriptRunState.MOVING,
                currentStep = 0,
                totalSteps = points.size,
                currentLoop = 1,
                targetLoops = loopsTarget,
                message = "正在启动路径运行..."
            )

            var loop = 1
            try {
                while (isActive) {
                    if (loopsTarget in 1..<loop) {
                        _progress.value = ScriptProgress(
                            state = ScriptRunState.COMPLETED,
                            currentStep = points.size,
                            totalSteps = points.size,
                            currentLoop = loopsTarget,
                            targetLoops = loopsTarget,
                            message = "已完成全部路径，共 $loopsTarget 轮"
                        )
                        break
                    }

                    for (stepIndex in points.indices) {
                        if (!isActive) break
                        val pt = points[stepIndex]

                        // 1. 移动阶段 (MOVING)
                        _progress.value = ScriptProgress(
                            state = ScriptRunState.MOVING,
                            currentStep = stepIndex + 1,
                            totalSteps = points.size,
                            currentLoop = loop,
                            targetLoops = loopsTarget,
                            message = "第 ${stepIndex + 1}/${points.size} 点 · 第 $loop 轮 · 移动中"
                        )

                        // 触发三轴并行定位
                        val spd = if (speedOverride > 0) speedOverride else null
                        val acc = if (accelOverride > 0) accelOverride else null

                        gotoAction("x", pt.x, spd, acc)
                        gotoAction("y", pt.y, spd, acc)
                        gotoAction("z", pt.z, spd, acc)

                        // 等待到位
                        var arrived = false
                        while (!arrived && isActive) {
                            delay(100)
                            // 检查各轴状态
                            for (a in AXES) {
                                val s = getSnapshot(a)
                                if (s == null || !s.online) {
                                    estopAction()
                                    _progress.value = ScriptProgress(
                                        state = ScriptRunState.ERROR,
                                        message = "${a.uppercase()} 轴离线，脚本安全中止"
                                    )
                                    return@launch
                                }
                                if (s.stalled || s.stall_protect) {
                                    estopAction()
                                    _progress.value = ScriptProgress(
                                        state = ScriptRunState.ERROR,
                                        message = "${a.uppercase()} 轴堵转/过载保护，脚本安全中止"
                                    )
                                    return@launch
                                }
                            }

                            // 到位判定：三轴全部到位
                            val xOk = getSnapshot("x")?.in_position == true
                            val yOk = getSnapshot("y")?.in_position == true
                            val zOk = getSnapshot("z")?.in_position == true
                            arrived = xOk && yOk && zOk
                        }

                        if (!isActive) break

                        // 2. 停留阶段 (DWELLING)
                        if (pt.dwell > 0.0) {
                            var remain = pt.dwell
                            while (remain > 0.0 && isActive) {
                                _progress.value = ScriptProgress(
                                    state = ScriptRunState.DWELLING,
                                    currentStep = stepIndex + 1,
                                    totalSteps = points.size,
                                    currentLoop = loop,
                                    targetLoops = loopsTarget,
                                    dwellRemainingSeconds = remain,
                                    message = "第 ${stepIndex + 1}/${points.size} 点 · 停留中 (${String.format("%.1f", remain)}s)"
                                )
                                delay(100)
                                remain -= 0.1
                            }
                        }
                    }

                    loop++
                }
            } catch (e: CancellationException) {
                _progress.value = ScriptProgress(state = ScriptRunState.STOPPED, message = "用户已停止")
            } catch (e: Exception) {
                _progress.value = ScriptProgress(state = ScriptRunState.ERROR, message = "脚本执行异常: ${e.message}")
            }
        }
    }

    fun stop() {
        if (runJob?.isActive == true) {
            runJob?.cancel()
            runJob = null
            scope.launch { estopAction() }
            _progress.value = ScriptProgress(state = ScriptRunState.STOPPED, message = "已停止")
        }
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
