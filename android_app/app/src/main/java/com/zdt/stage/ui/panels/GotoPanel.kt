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
import com.zdt.stage.core.serial.ConnectionState
import com.zdt.stage.data.model.AXES
import com.zdt.stage.data.model.AppConfig
import com.zdt.stage.data.model.AxisSnapshot
import com.zdt.stage.ui.components.NumberStepper
import com.zdt.stage.ui.theme.ZdtBorder
import com.zdt.stage.ui.theme.ZdtCard
import com.zdt.stage.ui.theme.ZdtGotoGreen
import java.util.Locale

@Composable
fun GotoPanel(
    config: AppConfig,
    connectionState: ConnectionState,
    snapshots: Map<String, AxisSnapshot>,
    onGotoSingle: (axis: String, targetMm: Double) -> Unit,
    onGotoParallel: (targetX: Double, targetY: Double, targetZ: Double) -> Unit,
    onGotoOrigin: () -> Unit,
    onUpdateMotion: (axis: String, moveSpeed: Int, moveAccel: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isOnline = connectionState.connected

    var targetX by remember { mutableStateOf(0.0) }
    var targetY by remember { mutableStateOf(0.0) }
    var targetZ by remember { mutableStateOf(0.0) }

    var moveSpeed by remember(config.axes["x"]?.move_speed) { mutableStateOf(config.axes["x"]?.move_speed ?: 120) }
    var moveAccel by remember(config.axes["x"]?.move_accel) { mutableStateOf(config.axes["x"]?.move_accel ?: 150) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C2028))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 定位控制网格卡片
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
                    text = "三轴绝对坐标定位 (带软限位保护)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE0E0E0)
                )

                // 轴行列表
                AXES.forEach { axis ->
                    val axCfg = config.axes[axis]
                    val cal = axCfg?.calibration
                    val snap = snapshots[axis]
                    val axOnline = isOnline && (connectionState.onlineMap[axis] == true)
                    val travelMax = axCfg?.travel_mm ?: 500.0
                    val isCalibrated = cal?.valid == true && cal.pulses_per_mm > 0

                    val axisColor = when (axis) {
                        "x" -> Color(0xFFFF7A4A)
                        "y" -> Color(0xFF5AD0FF)
                        else -> Color(0xFF9AFF5A)
                    }

                    var targetVal = when (axis) {
                        "x" -> targetX
                        "y" -> targetY
                        else -> targetZ
                    }

                    val curMm = if (snap != null && cal != null) snap.mm(cal) else 0.0

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E232B), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${axis.uppercase()} 轴",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = axisColor,
                            modifier = Modifier.width(44.dp)
                        )

                        // 目标输入
                        NumberStepper(
                            value = targetVal,
                            onValueChange = {
                                when (axis) {
                                    "x" -> targetX = it
                                    "y" -> targetY = it
                                    "z" -> targetZ = it
                                }
                            },
                            min = 0.0,
                            max = travelMax,
                            step = 1.0,
                            decimals = 2,
                            suffix = "mm",
                            enabled = axOnline && isCalibrated,
                            modifier = Modifier.weight(1.3f)
                        )

                        // 当前位置读数
                        Column(
                            modifier = Modifier.weight(0.9f),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.2f mm", curMm),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (axOnline) Color(0xFFFFE066) else Color(0xFF777777),
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = if (isCalibrated) "已标定" else "未标定",
                                fontSize = 10.sp,
                                color = if (isCalibrated) Color(0xFF3AD06C) else Color(0xFFFF5050)
                            )
                        }

                        // 单轴 Goto 按钮
                        Button(
                            onClick = { onGotoSingle(axis, targetVal) },
                            enabled = axOnline && isCalibrated,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2C3340),
                                disabledContainerColor = Color(0xFF222831)
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Goto", fontSize = 13.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        // 定位速度与加速度参数
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
                    text = "定位运动参数",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE0E0E0)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("定位速度 (RPM)", fontSize = 12.sp, color = Color(0xFFA0A8B4))
                        NumberStepper(
                            value = moveSpeed.toDouble(),
                            onValueChange = {
                                moveSpeed = it.toInt()
                                onUpdateMotion("x", moveSpeed, moveAccel)
                                onUpdateMotion("y", moveSpeed, moveAccel)
                                onUpdateMotion("z", moveSpeed, moveAccel)
                            },
                            min = 1.0,
                            max = 3000.0,
                            step = 10.0,
                            decimals = 0,
                            suffix = "RPM"
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text("定位加速度 (0~255)", fontSize = 12.sp, color = Color(0xFFA0A8B4))
                        NumberStepper(
                            value = moveAccel.toDouble(),
                            onValueChange = {
                                moveAccel = it.toInt()
                                onUpdateMotion("x", moveSpeed, moveAccel)
                                onUpdateMotion("y", moveSpeed, moveAccel)
                                onUpdateMotion("z", moveSpeed, moveAccel)
                            },
                            min = 0.0,
                            max = 255.0,
                            step = 5.0,
                            decimals = 0
                        )
                    }
                }
            }
        }

        // 底部动作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 三轴并行 Goto
            Button(
                onClick = { onGotoParallel(targetX, targetY, targetZ) },
                enabled = isOnline,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ZdtGotoGreen,
                    disabledContainerColor = Color(0xFF222831)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1.2f)
                    .height(50.dp)
            ) {
                Text("Goto (三轴并行)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            // 回到原点 (0,0,0)
            Button(
                onClick = onGotoOrigin,
                enabled = isOnline,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2C3340),
                    disabledContainerColor = Color(0xFF222831)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(0.8f)
                    .height(50.dp)
            ) {
                Text("回到原点 (0,0,0)", fontSize = 14.sp, color = Color(0xFFFFE066))
            }
        }
    }
}
