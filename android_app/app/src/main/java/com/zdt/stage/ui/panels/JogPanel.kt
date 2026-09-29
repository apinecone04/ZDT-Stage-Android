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
import com.zdt.stage.data.model.AppConfig
import com.zdt.stage.ui.components.JogHoldButton
import com.zdt.stage.ui.components.NumberStepper
import com.zdt.stage.ui.theme.ZdtBorder
import com.zdt.stage.ui.theme.ZdtCard
import com.zdt.stage.ui.theme.ZdtEstopRed

@Composable
fun JogPanel(
    config: AppConfig,
    connectionState: ConnectionState,
    onJogStart: (axis: String, isPlus: Boolean) -> Unit,
    onJogStop: (axis: String) -> Unit,
    onEstop: () -> Unit,
    onUpdateMotion: (axis: String, jogSpeed: Int, jogAccel: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isOnline = connectionState.connected
    val xOnline = isOnline && connectionState.onlineMap["x"] == true
    val yOnline = isOnline && connectionState.onlineMap["y"] == true
    val zOnline = isOnline && connectionState.onlineMap["z"] == true

    var xJogSpeed by remember(config.axes["x"]?.jog_speed) { mutableStateOf(config.axes["x"]?.jog_speed ?: 60) }
    var xJogAccel by remember(config.axes["x"]?.jog_accel) { mutableStateOf(config.axes["x"]?.jog_accel ?: 100) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C2028))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 六键点动矩阵卡片
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
                    text = "三轴点动控制 (按住即动 · 松手即停)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE0E0E0)
                )

                // X 轴点动
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("X 轴", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF7A4A), modifier = Modifier.width(42.dp))
                    JogHoldButton(
                        text = "X -",
                        enabled = xOnline,
                        onStart = { onJogStart("x", false) },
                        onStop = { onJogStop("x") },
                        modifier = Modifier.weight(1f)
                    )
                    JogHoldButton(
                        text = "X +",
                        enabled = xOnline,
                        onStart = { onJogStart("x", true) },
                        onStop = { onJogStop("x") },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Y 轴点动
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Y 轴", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5AD0FF), modifier = Modifier.width(42.dp))
                    JogHoldButton(
                        text = "Y -",
                        enabled = yOnline,
                        onStart = { onJogStart("y", false) },
                        onStop = { onJogStop("y") },
                        modifier = Modifier.weight(1f)
                    )
                    JogHoldButton(
                        text = "Y +",
                        enabled = yOnline,
                        onStart = { onJogStart("y", true) },
                        onStop = { onJogStop("y") },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Z 轴点动
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Z 轴", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9AFF5A), modifier = Modifier.width(42.dp))
                    JogHoldButton(
                        text = "Z -",
                        enabled = zOnline,
                        onStart = { onJogStart("z", false) },
                        onStop = { onJogStop("z") },
                        modifier = Modifier.weight(1f)
                    )
                    JogHoldButton(
                        text = "Z +",
                        enabled = zOnline,
                        onStart = { onJogStart("z", true) },
                        onStop = { onJogStop("z") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 点动参数调节
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
                    text = "点动运动参数",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE0E0E0)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("点动速度 (RPM)", fontSize = 12.sp, color = Color(0xFFA0A8B4))
                        NumberStepper(
                            value = xJogSpeed.toDouble(),
                            onValueChange = {
                                xJogSpeed = it.toInt()
                                onUpdateMotion("x", xJogSpeed, xJogAccel)
                                onUpdateMotion("y", xJogSpeed, xJogAccel)
                                onUpdateMotion("z", xJogSpeed, xJogAccel)
                            },
                            min = 1.0,
                            max = 3000.0,
                            step = 10.0,
                            decimals = 0,
                            suffix = "RPM"
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text("点动加速度 (0~255)", fontSize = 12.sp, color = Color(0xFFA0A8B4))
                        NumberStepper(
                            value = xJogAccel.toDouble(),
                            onValueChange = {
                                xJogAccel = it.toInt()
                                onUpdateMotion("x", xJogSpeed, xJogAccel)
                                onUpdateMotion("y", xJogSpeed, xJogAccel)
                                onUpdateMotion("z", xJogSpeed, xJogAccel)
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

        // 急 停 全 部 大按钮
        Button(
            onClick = onEstop,
            colors = ButtonDefaults.buttonColors(containerColor = ZdtEstopRed),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = "急  停  全  部",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
