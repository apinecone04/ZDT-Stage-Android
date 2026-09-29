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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zdt.stage.core.serial.ConnectionState
import com.zdt.stage.data.model.AXES
import com.zdt.stage.data.model.AppConfig
import com.zdt.stage.data.model.AxisSnapshot
import com.zdt.stage.ui.theme.ZdtBorder
import com.zdt.stage.ui.theme.ZdtCard

@Composable
fun HomePanel(
    config: AppConfig,
    connectionState: ConnectionState,
    snapshots: Map<String, AxisSnapshot>,
    onHome: (axis: String) -> Unit,
    onStopHome: (axis: String) -> Unit,
    onSetHome: (axis: String) -> Unit,
    onSetOriginHere: (axis: String) -> Unit,
    onGotoOrigin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOnline = connectionState.connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C2028))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 轴回零与原点操作卡片
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
                    text = "三轴回零与原点配置 (机械回零后自动同步显示原点)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE0E0E0)
                )

                AXES.forEach { axis ->
                    val snap = snapshots[axis]
                    val axOnline = isOnline && (connectionState.onlineMap[axis] == true)

                    val axisColor = when (axis) {
                        "x" -> Color(0xFFFF7A4A)
                        "y" -> Color(0xFF5AD0FF)
                        else -> Color(0xFF9AFF5A)
                    }

                    val statusText: String
                    val statusColor: Color
                    when {
                        !axOnline -> {
                            statusText = "离线"
                            statusColor = Color(0xFF888888)
                        }
                        snap?.homing == true -> {
                            statusText = "回零中…"
                            statusColor = Color(0xFFD0A020)
                        }
                        snap?.homing_failed == true -> {
                            statusText = "回零失败"
                            statusColor = Color(0xFFFF4040)
                        }
                        else -> {
                            statusText = "就绪"
                            statusColor = Color(0xFF3AD06C)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E232B), RoundedCornerShape(6.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${axis.uppercase()} 轴",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = axisColor,
                                modifier = Modifier.width(50.dp)
                            )

                            Text(
                                text = "状态: $statusText",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 机械回零
                            Button(
                                onClick = { onHome(axis) },
                                enabled = axOnline,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("机械回零", fontSize = 12.sp, color = Color.White)
                            }

                            // 停止回零
                            Button(
                                onClick = { onStopHome(axis) },
                                enabled = axOnline,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF552222)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.weight(0.8f)
                            ) {
                                Text("停", fontSize = 12.sp, color = Color.White)
                            }

                            // 设电机零点 (写驱动板)
                            Button(
                                onClick = { onSetHome(axis) },
                                enabled = axOnline,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1.2f)
                            ) {
                                Text("设电机零点", fontSize = 12.sp, color = Color(0xFFB0D0FF))
                            }

                            // 设显示原点 (上位机)
                            Button(
                                onClick = { onSetOriginHere(axis) },
                                enabled = axOnline,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1.2f)
                            ) {
                                Text("设显示原点", fontSize = 12.sp, color = Color(0xFFFFE066))
                            }
                        }
                    }
                }
            }
        }

        // 回到显示原点按钮
        Button(
            onClick = onGotoOrigin,
            enabled = isOnline,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F6D2A)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("回到显示原点 (0, 0, 0)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        // 原理说明卡片
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E232B)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF333B48), RoundedCornerShape(8.dp))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("📌 回零与原点概念说明：", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFE066))
                Spacer(modifier = Modifier.height(4.dp))
                Text("• 机械回零：触发驱动板执行碰撞/限位归零动作。完成时上位机会自动把当前位置登记为显示原点 (0.00 mm)。", fontSize = 12.sp, color = Color(0xFFA0A8B4))
                Text("• 设电机零点：通过 0x93 将驱动板内部的累积位置寄存器清零。", fontSize = 12.sp, color = Color(0xFFA0A8B4))
                Text("• 设显示原点：仅在上位机记录当前位置为 0.00 mm 基准，不改变驱动器硬件状态。", fontSize = 12.sp, color = Color(0xFFA0A8B4))
            }
        }
    }
}
