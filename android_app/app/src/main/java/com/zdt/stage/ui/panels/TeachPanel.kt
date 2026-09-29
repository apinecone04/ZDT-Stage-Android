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
import com.zdt.stage.core.serial.ConnectionState
import com.zdt.stage.data.model.AppConfig
import com.zdt.stage.data.model.AxisSnapshot
import com.zdt.stage.data.model.Preset
import com.zdt.stage.ui.theme.ZdtBorder
import com.zdt.stage.ui.theme.ZdtCard
import java.util.Locale

@Composable
fun TeachPanel(
    config: AppConfig,
    connectionState: ConnectionState,
    snapshots: Map<String, AxisSnapshot>,
    onAddPreset: (name: String, x: Double, y: Double, z: Double) -> Unit,
    onUpdatePreset: (index: Int, preset: Preset) -> Unit,
    onDeletePreset: (index: Int) -> Unit,
    onClearPresets: () -> Unit,
    onGotoPoint: (x: Double, y: Double, z: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    val isOnline = connectionState.connected

    // 当前三轴实时坐标
    val currentX = snapshots["x"]?.mm(config.axes["x"]?.calibration ?: return) ?: 0.0
    val currentY = snapshots["y"]?.mm(config.axes["y"]?.calibration ?: return) ?: 0.0
    val currentZ = snapshots["z"]?.mm(config.axes["z"]?.calibration ?: return) ?: 0.0

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C2028))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 操作按钮工具栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    newPresetName = "点位 ${config.presets.size + 1}"
                    showAddDialog = true
                },
                enabled = isOnline,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F6D2A)),
                modifier = Modifier.weight(1f)
            ) {
                Text("添加当前点", fontSize = 13.sp, color = Color.White)
            }

            Button(
                onClick = {
                    selectedIndex?.let { idx ->
                        if (idx in config.presets.indices) {
                            val p = config.presets[idx]
                            onGotoPoint(p.x, p.y, p.z)
                        }
                    }
                },
                enabled = isOnline && selectedIndex != null && selectedIndex!! in config.presets.indices,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
                modifier = Modifier.weight(1.2f)
            ) {
                Text("调出选中 → Goto", fontSize = 13.sp, color = Color(0xFFFFE066))
            }

            Button(
                onClick = {
                    selectedIndex?.let { idx ->
                        if (idx in config.presets.indices) {
                            val old = config.presets[idx]
                            onUpdatePreset(idx, old.copy(x = currentX, y = currentY, z = currentZ))
                        }
                    }
                },
                enabled = isOnline && selectedIndex != null && selectedIndex!! in config.presets.indices,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3340)),
                modifier = Modifier.weight(1.3f)
            ) {
                Text("用当前坐标更新", fontSize = 13.sp, color = Color(0xFF5AD0FF))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    selectedIndex?.let { idx ->
                        onDeletePreset(idx)
                        selectedIndex = null
                    }
                },
                enabled = selectedIndex != null && selectedIndex!! in config.presets.indices,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF552222)),
                modifier = Modifier.weight(1f)
            ) {
                Text("删除选中", fontSize = 13.sp, color = Color.White)
            }

            Button(
                onClick = {
                    onClearPresets()
                    selectedIndex = null
                },
                enabled = config.presets.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                modifier = Modifier.weight(1f)
            ) {
                Text("清空列表", fontSize = 13.sp, color = Color(0xFFA0A8B4))
            }
        }

        // 点位列表表头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2C3340), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("名称", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1.2f))
            Text("X (mm)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF7A4A), modifier = Modifier.weight(1f))
            Text("Y (mm)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5AD0FF), modifier = Modifier.weight(1f))
            Text("Z (mm)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9AFF5A), modifier = Modifier.weight(1f))
        }

        // 点位列表体
        Card(
            colors = CardDefaults.cardColors(containerColor = ZdtCard),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, ZdtBorder, RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
        ) {
            if (config.presets.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无预设示教点位，点击上方“添加当前点”录入", color = Color(0xFF777777), fontSize = 14.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(config.presets) { index, preset ->
                        val isSelected = selectedIndex == index
                        val rowBg = if (isSelected) Color(0xFF3A4250) else if (index % 2 == 0) Color(0xFF222730) else Color(0xFF262B34)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg)
                                .clickable { selectedIndex = index }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = preset.name,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color(0xFFFFE066) else Color.White,
                                modifier = Modifier.weight(1.2f)
                            )
                            Text(
                                text = String.format(Locale.US, "%.2f", preset.x),
                                fontSize = 13.sp,
                                color = Color(0xFFFF7A4A),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = String.format(Locale.US, "%.2f", preset.y),
                                fontSize = 13.sp,
                                color = Color(0xFF5AD0FF),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = String.format(Locale.US, "%.2f", preset.z),
                                fontSize = 13.sp,
                                color = Color(0xFF9AFF5A),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    // 添加点位对话框
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("记录当前坐标为示教点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        label = { Text("点位名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = String.format(Locale.US, "当前坐标: X=%.2f, Y=%.2f, Z=%.2f mm", currentX, currentY, currentZ),
                        fontSize = 12.sp,
                        color = Color(0xFFFFE066)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newPresetName.trim().ifEmpty { "P${config.presets.size + 1}" }
                        onAddPreset(name, currentX, currentY, currentZ)
                        showAddDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F6D2A))
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
