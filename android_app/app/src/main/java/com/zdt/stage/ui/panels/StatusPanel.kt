package com.zdt.stage.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.zdt.stage.ui.components.LedChip
import com.zdt.stage.ui.theme.*
import java.util.Locale

@Composable
fun StatusPanel(
    config: AppConfig,
    connectionState: ConnectionState,
    snapshots: Map<String, AxisSnapshot>,
    modifier: Modifier = Modifier
) {
    val isOnline = connectionState.connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C2028))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AXES.forEach { axis ->
            val axCfg = config.axes[axis]
            val cal = axCfg?.calibration
            val snap = snapshots[axis]
            val axOnline = isOnline && (connectionState.onlineMap[axis] == true)

            val axisColor = when (axis) {
                "x" -> Color(0xFFFF7A4A)
                "y" -> Color(0xFF5AD0FF)
                else -> Color(0xFF9AFF5A)
            }

            val isCalibrated = cal?.valid == true && cal.angle_per_mm > 0.0
            val curMm = if (snap != null && cal != null) snap.mm(cal) else 0.0
            val tgtMm = if (snap != null && cal != null) snap.targetMm(cal) else 0.0

            Card(
                colors = CardDefaults.cardColors(containerColor = ZdtCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ZdtBorder, RoundedCornerShape(8.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 轴名与大字坐标显示
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${axis.uppercase()} 轴",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = axisColor
                        )

                        val displayCoord = when {
                            !axOnline -> "离线"
                            !isCalibrated -> "未标定"
                            else -> String.format(Locale.US, "%.2f mm", curMm)
                        }
                        val coordColor = when {
                            !axOnline -> Color(0xFF888888)
                            !isCalibrated -> Color(0xFFFF5050)
                            else -> Color(0xFFFFE066)
                        }

                        Text(
                            text = displayCoord,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = coordColor,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // 参数网格：目标、转速、电压、电流
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E232B), RoundedCornerShape(6.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("目标位置", fontSize = 11.sp, color = Color(0xFFA0A8B4))
                            Text(
                                text = if (axOnline && isCalibrated) String.format(Locale.US, "%.2f mm", tgtMm) else "--",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Column {
                            Text("实时转速", fontSize = 11.sp, color = Color(0xFFA0A8B4))
                            Text(
                                text = if (axOnline && snap != null) String.format(Locale.US, "%.0f RPM", snap.speed_rpm) else "--",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF5AD0FF),
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Column {
                            Text("母线电压", fontSize = 11.sp, color = Color(0xFFA0A8B4))
                            Text(
                                text = if (axOnline && snap != null) String.format(Locale.US, "%.2f V", snap.bus_voltage) else "--",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF9AFF5A),
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Column {
                            Text("相电流", fontSize = 11.sp, color = Color(0xFFA0A8B4))
                            Text(
                                text = if (axOnline && snap != null) String.format(Locale.US, "%.3f A", snap.phase_current) else "--",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF7A4A),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // 5 个状态 LED 指示灯
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LedChip(
                            label = "使能",
                            active = axOnline && snap?.enabled == true,
                            activeColor = LedGreen,
                            modifier = Modifier.weight(1f)
                        )
                        LedChip(
                            label = "到位",
                            active = axOnline && snap?.in_position == true,
                            activeColor = LedGreen,
                            modifier = Modifier.weight(1f)
                        )
                        LedChip(
                            label = "失步",
                            active = axOnline && snap?.stalled == true,
                            activeColor = LedRed,
                            modifier = Modifier.weight(1f)
                        )
                        LedChip(
                            label = "保护",
                            active = axOnline && snap?.stall_protect == true,
                            activeColor = LedRed,
                            modifier = Modifier.weight(1f)
                        )
                        LedChip(
                            label = "回零中",
                            active = axOnline && snap?.homing == true,
                            activeColor = LedOrange,
                            modifier = Modifier.weight(1.1f)
                        )
                    }
                }
            }
        }
    }
}
