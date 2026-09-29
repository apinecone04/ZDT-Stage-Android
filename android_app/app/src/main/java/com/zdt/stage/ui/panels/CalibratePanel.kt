package com.zdt.stage.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zdt.stage.core.motion.MotionUnits
import com.zdt.stage.core.serial.ConnectionState
import com.zdt.stage.data.model.AXES
import com.zdt.stage.data.model.AppConfig
import com.zdt.stage.data.model.AxisSnapshot
import com.zdt.stage.data.model.Calibration
import com.zdt.stage.ui.components.NumberStepper
import com.zdt.stage.ui.theme.ZdtBorder
import com.zdt.stage.ui.theme.ZdtCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibratePanel(
    config: AppConfig,
    connectionState: ConnectionState,
    snapshots: Map<String, AxisSnapshot>,
    onCalibrateMove: suspend (axis: String, pulses: Long) -> Boolean,
    onApplyCalibration: (axis: String, cal: Calibration) -> Unit,
    onSetInvert: (axis: String, invert: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedAxis by remember { mutableStateOf("x") }
    var axisExpanded by remember { mutableStateOf(false) }

    var pulseCount by remember { mutableStateOf(1000L) }
    var measuredMm by remember { mutableStateOf(10.0) }

    var a0 by remember { mutableStateOf(0L) }
    var a1 by remember { mutableStateOf(0L) }
    var isMoving by remember { mutableStateOf(false) }
    var hasMoved by remember { mutableStateOf(false) }

    var logText by remember { mutableStateOf("标定向导就绪。请确认滑台处于安全位置，远离物理限位。\n") }

    val coroutineScope = rememberCoroutineScope()
    val isOnline = connectionState.connected && (connectionState.onlineMap[selectedAxis] == true)

    val currentCal = config.axes[selectedAxis]?.calibration ?: Calibration()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C2028))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 标定轴选择与反转开关
        Card(
            colors = CardDefaults.cardColors(containerColor = ZdtCard),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ZdtBorder, RoundedCornerShape(8.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "单轴几何标定向导",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE0E0E0)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExposedDropdownMenuBox(
                        expanded = axisExpanded && !isMoving,
                        onExpandedChange = { if (!isMoving) axisExpanded = !axisExpanded },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = "${selectedAxis.uppercase()} 轴",
                            onValueChange = {},
                            readOnly = true,
                            enabled = !isMoving,
                            label = { Text("标定目标轴") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = axisExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF262B34),
                                unfocusedContainerColor = Color(0xFF262B34),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = axisExpanded && !isMoving,
                            onDismissRequest = { axisExpanded = false }
                        ) {
                            AXES.forEach { a ->
                                DropdownMenuItem(
                                    text = { Text("${a.uppercase()} 轴") },
                                    onClick = {
                                        selectedAxis = a
                                        axisExpanded = false
                                        hasMoved = false
                                    }
                                )
                            }
                        }
                    }

                    // Invert 反转 Checkbox
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Checkbox(
                            checked = currentCal.invert,
                            onCheckedChange = { onSetInvert(selectedAxis, it) },
                            enabled = !isMoving
                        )
                        Text("方向反转 (CW 实为 -mm)", fontSize = 12.sp, color = Color(0xFFE0E0E0))
                    }
                }

                // 标定状态指标
                Text(
                    text = if (currentCal.valid) {
                        String.format(
                            Locale.US,
                            "当前标定有效: %.3f 脉冲/mm · %.1f 角度/mm · invert=%s",
                            currentCal.pulses_per_mm,
                            currentCal.angle_per_mm,
                            currentCal.invert
                        )
                    } else {
                        "当前轴状态: 未标定 (无法执行绝对定位)"
                    },
                    color = if (currentCal.valid) Color(0xFF3AD06C) else Color(0xFFFF5050),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 两步法向导卡片
        Card(
            colors = CardDefaults.cardColors(containerColor = ZdtCard),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ZdtBorder, RoundedCornerShape(8.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "两步测量法流程",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFE066)
                )

                // 步骤 1
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("第 ① 步: 设定移动脉冲数并触发测试位移", fontSize = 13.sp, color = Color(0xFFE0E0E0))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NumberStepper(
                            value = pulseCount.toDouble(),
                            onValueChange = { pulseCount = it.toLong() },
                            min = 100.0,
                            max = 100000.0,
                            step = 500.0,
                            decimals = 0,
                            suffix = "p",
                            enabled = isOnline && !isMoving,
                            modifier = Modifier.weight(1.2f)
                        )

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isMoving = true
                                    val startPos = snapshots[selectedAxis]?.angle_position ?: 0L
                                    a0 = startPos
                                    logText += "[${selectedAxis.uppercase()}] 记录起始编码器角度 a0 = $a0\n"
                                    logText += "[${selectedAxis.uppercase()}] 发送相对位移指令 $pulseCount 脉冲...\n"

                                    val ok = onCalibrateMove(selectedAxis, pulseCount)
                                    if (!ok) {
                                        logText += "[${selectedAxis.uppercase()}] 发送移动失败！\n"
                                        isMoving = false
                                        return@launch
                                    }

                                    // 等待到位
                                    delay(500)
                                    while (true) {
                                        delay(150)
                                        val snap = snapshots[selectedAxis]
                                        if (snap == null || !snap.online) {
                                            logText += "[${selectedAxis.uppercase()}] 电机离线，标定中止！\n"
                                            break
                                        }
                                        if (snap.stalled || snap.stall_protect) {
                                            logText += "[${selectedAxis.uppercase()}] 警告：检测到堵转或过载保护，立即中止！\n"
                                            break
                                        }
                                        if (snap.in_position) {
                                            a1 = snap.angle_position
                                            val da = a1 - a0
                                            logText += "[${selectedAxis.uppercase()}] 移动完成！到达编码器角度 a1 = $a1 (Δangle = $da)\n"
                                            logText += "请用卡尺量测滑台实际移动的物理距离 (mm)，填入下方输入框。\n"
                                            hasMoved = true
                                            break
                                        }
                                    }
                                    isMoving = false
                                }
                            },
                            enabled = isOnline && !isMoving,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            Text(if (isMoving) "移动中..." else "① 开始 (发移动并记录)", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF3A4250))

                // 步骤 2
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("第 ② 步: 输入卡尺量取的实际位移并完成标定", fontSize = 13.sp, color = Color(0xFFE0E0E0))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NumberStepper(
                            value = measuredMm,
                            onValueChange = { measuredMm = it },
                            min = 0.01,
                            max = 1000.0,
                            step = 1.0,
                            decimals = 2,
                            suffix = "mm",
                            enabled = hasMoved && !isMoving,
                            modifier = Modifier.weight(1.2f)
                        )

                        Button(
                            onClick = {
                                val pPerMm = MotionUnits.calcPulsesPerMm(pulseCount, measuredMm)
                                val aPerMm = MotionUnits.calcAnglePerMm(a1 - a0, measuredMm)
                                val newCal = Calibration(
                                    pulses_per_mm = pPerMm,
                                    angle_per_mm = aPerMm,
                                    origin_angle = a0,
                                    valid = true,
                                    invert = currentCal.invert
                                )
                                onApplyCalibration(selectedAxis, newCal)
                                logText += "[${selectedAxis.uppercase()}] 标定成功！pulses_per_mm = ${String.format(Locale.US, "%.3f", pPerMm)}, angle_per_mm = ${String.format(Locale.US, "%.3f", aPerMm)}\n"
                                logText += "原点 origin_angle 设为 a0 = $a0\n"
                                hasMoved = false
                            },
                            enabled = hasMoved && !isMoving && measuredMm > 0.0,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F6D2A)),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            Text("② 完成标定并应用", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        // 标定日志文本框
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF11151C)),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, Color(0xFF333B48), RoundedCornerShape(8.dp))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("标定日志输出:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA0A8B4))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = logText,
                    fontSize = 11.sp,
                    color = Color(0xFFD0D0D0),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
