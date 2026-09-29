package com.zdt.stage.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zdt.stage.core.motion.ScriptProgress
import com.zdt.stage.core.motion.ScriptRunState
import com.zdt.stage.core.serial.ConnectionState
import com.zdt.stage.data.model.AppConfig
import com.zdt.stage.data.model.AxisSnapshot
import com.zdt.stage.data.model.ScriptPoint
import com.zdt.stage.ui.components.NumberStepper
import com.zdt.stage.ui.theme.ZdtBorder
import com.zdt.stage.ui.theme.ZdtCard
import java.util.Locale

@Composable
fun ScriptPanel(
    config: AppConfig,
    connectionState: ConnectionState,
    snapshots: Map<String, AxisSnapshot>,
    scriptProgress: ScriptProgress,
    onAddScriptPoint: (x: Double, y: Double, z: Double, dwell: Double) -> Unit,
    onUpdateScriptPoint: (index: Int, point: ScriptPoint) -> Unit,
    onDeleteScriptPoint: (index: Int) -> Unit,
    onMoveScriptPoint: (index: Int, up: Boolean) -> Unit,
    onClearScriptPoints: () -> Unit,
    onStartScript: (loops: Int, speedOverride: Int, accelOverride: Int) -> Unit,
    onStopScript: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var targetLoops by remember { mutableStateOf(1) }
    var speedOverride by remember { mutableStateOf(0) }
    var accelOverride by remember { mutableStateOf(0) }

    val isOnline = connectionState.connected
    val isRunning = scriptProgress.state == ScriptRunState.MOVING || scriptProgress.state == ScriptRunState.DWELLING

    // 当前三轴实时坐标
    val currentX = snapshots["x"]?.mm(config.axes["x"]?.calibration ?: return) ?: 0.0
    val currentY = snapshots["y"]?.mm(config.axes["y"]?.calibration ?: return) ?: 0.0
    val currentZ = snapshots["z"]?.mm(config.axes["z"]?.calibration ?: return) ?: 0.0

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C2028))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶部工具栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { onAddScriptPoint(currentX, currentY, currentZ, 1.0) },
                enabled = isOnline && !isRunning,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F6D2A)),
                modifier = Modifier.weight(1f)
            ) {
                Text("添加当前点", fontSize = 12.sp, color = Color.White)
            }

            Button(
                onClick = { onAddScriptPoint(0.0, 0.0, 0.0, 1.0) },
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
                modifier = Modifier.weight(1f)
            ) {
                Text("新增空行", fontSize = 12.sp, color = Color.White)
            }

            Button(
                onClick = {
                    selectedIndex?.let { idx ->
                        onMoveScriptPoint(idx, true)
                        if (idx > 0) selectedIndex = idx - 1
                    }
                },
                enabled = !isRunning && selectedIndex != null && selectedIndex!! > 0,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
                modifier = Modifier.weight(0.7f)
            ) {
                Text("上移", fontSize = 12.sp, color = Color.White)
            }

            Button(
                onClick = {
                    selectedIndex?.let { idx ->
                        onMoveScriptPoint(idx, false)
                        if (idx < config.script.size - 1) selectedIndex = idx + 1
                    }
                },
                enabled = !isRunning && selectedIndex != null && selectedIndex!! < config.script.size - 1,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
                modifier = Modifier.weight(0.7f)
            ) {
                Text("下移", fontSize = 12.sp, color = Color.White)
            }

            Button(
                onClick = {
                    selectedIndex?.let { idx ->
                        onDeleteScriptPoint(idx)
                        selectedIndex = null
                    }
                },
                enabled = !isRunning && selectedIndex != null && selectedIndex!! in config.script.indices,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF552222)),
                modifier = Modifier.weight(0.8f)
            ) {
                Text("删除", fontSize = 12.sp, color = Color.White)
            }

            Button(
                onClick = {
                    onClearScriptPoints()
                    selectedIndex = null
                },
                enabled = !isRunning && config.script.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                modifier = Modifier.weight(0.8f)
            ) {
                Text("清空", fontSize = 12.sp, color = Color(0xFFA0A8B4))
            }
        }

        // 表格头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2C3340), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("#", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA0A8B4), modifier = Modifier.width(28.dp))
            Text("X (mm)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF7A4A), modifier = Modifier.weight(1f))
            Text("Y (mm)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5AD0FF), modifier = Modifier.weight(1f))
            Text("Z (mm)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9AFF5A), modifier = Modifier.weight(1f))
            Text("停留 (s)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFE066), modifier = Modifier.weight(0.9f))
        }

        // 表格内容
        Card(
            colors = CardDefaults.cardColors(containerColor = ZdtCard),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, ZdtBorder, RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
        ) {
            if (config.script.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("路径列表为空，点击上方“添加当前点”或“新增空行”编辑路径", color = Color(0xFF777777), fontSize = 13.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(config.script) { index, pt ->
                        val isSelected = selectedIndex == index
                        val isCurrentStep = isRunning && (scriptProgress.currentStep - 1 == index)
                        val rowBg = when {
                            isCurrentStep -> Color(0xFF2F4A2F)
                            isSelected -> Color(0xFF3A4250)
                            index % 2 == 0 -> Color(0xFF222730)
                            else -> Color(0xFF262B34)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg)
                                .clickable(enabled = !isRunning) { selectedIndex = index }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isCurrentStep) Color(0xFFFFE066) else Color(0xFFA0A8B4),
                                modifier = Modifier.width(28.dp)
                            )
                            Text(
                                text = String.format(Locale.US, "%.2f", pt.x),
                                fontSize = 13.sp,
                                color = Color(0xFFFF7A4A),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = String.format(Locale.US, "%.2f", pt.y),
                                fontSize = 13.sp,
                                color = Color(0xFF5AD0FF),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = String.format(Locale.US, "%.2f", pt.z),
                                fontSize = 13.sp,
                                color = Color(0xFF9AFF5A),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f", pt.dwell),
                                fontSize = 13.sp,
                                color = Color(0xFFFFE066),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(0.9f)
                            )
                        }
                    }
                }
            }
        }

        // 参数控制区：循环次数、速度覆盖、加速度覆盖
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (targetLoops == 0) "循环次数 (0=无限)" else "循环次数: $targetLoops 次",
                    fontSize = 11.sp,
                    color = Color(0xFFA0A8B4)
                )
                NumberStepper(
                    value = targetLoops.toDouble(),
                    onValueChange = { targetLoops = it.toInt() },
                    min = 0.0,
                    max = 100000.0,
                    step = 1.0,
                    decimals = 0,
                    enabled = !isRunning
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text("速度覆盖 (0=默认)", fontSize = 11.sp, color = Color(0xFFA0A8B4))
                NumberStepper(
                    value = speedOverride.toDouble(),
                    onValueChange = { speedOverride = it.toInt() },
                    min = 0.0,
                    max = 3000.0,
                    step = 10.0,
                    decimals = 0,
                    suffix = "RPM",
                    enabled = !isRunning
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text("加速度覆盖 (0=默认)", fontSize = 11.sp, color = Color(0xFFA0A8B4))
                NumberStepper(
                    value = accelOverride.toDouble(),
                    onValueChange = { accelOverride = it.toInt() },
                    min = 0.0,
                    max = 255.0,
                    step = 5.0,
                    decimals = 0,
                    enabled = !isRunning
                )
            }
        }

        // 运行进度提示条
        val progressText = scriptProgress.message
        val progressColor = when (scriptProgress.state) {
            ScriptRunState.MOVING, ScriptRunState.DWELLING -> Color(0xFF3AD06C)
            ScriptRunState.ERROR -> Color(0xFFFF4040)
            ScriptRunState.COMPLETED -> Color(0xFFFFE066)
            else -> Color(0xFFA0A8B4)
        }

        Text(
            text = "状态: $progressText",
            color = progressColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )

        // 运行与停止大按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    onStartScript(targetLoops, speedOverride, accelOverride)
                },
                enabled = isOnline && !isRunning && config.script.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2F6D2A),
                    disabledContainerColor = Color(0xFF222831)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1.2f)
                    .height(48.dp)
            ) {
                Text("▶  运 行", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Button(
                onClick = onStopScript,
                enabled = isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB02020),
                    disabledContainerColor = Color(0xFF222831)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(0.8f)
                    .height(48.dp)
            ) {
                Text("■  停 止", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}
