package com.zdt.stage.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zdt.stage.core.serial.ConnectionState
import com.zdt.stage.core.serial.UsbSerialManager
import com.zdt.stage.data.model.AppConfig
import com.zdt.stage.ui.components.NumberStepper
import com.zdt.stage.ui.theme.ZdtBorder
import com.zdt.stage.ui.theme.ZdtCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionPanel(
    config: AppConfig,
    connectionState: ConnectionState,
    availableDevices: List<UsbSerialManager.DeviceItem>,
    selectedDeviceIndex: Int,
    onSelectDevice: (Int) -> Unit,
    onRefreshDevices: () -> Unit,
    onConnect: (baud: Int, addresses: Map<String, Int>, mock: Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onUpdateAddresses: (Map<String, Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    var baudRate by remember(config.baud) { mutableStateOf(config.baud) }
    var addrX by remember(config.addresses["x"]) { mutableStateOf(config.addresses["x"] ?: 1) }
    var addrY by remember(config.addresses["y"]) { mutableStateOf(config.addresses["y"] ?: 2) }
    var addrZ by remember(config.addresses["z"]) { mutableStateOf(config.addresses["z"] ?: 3) }

    val baudOptions = listOf(9600, 19200, 38400, 57600, 115200, 256000, 512000, 921600)
    var baudExpanded by remember { mutableStateOf(false) }
    var deviceExpanded by remember { mutableStateOf(false) }

    val isConnected = connectionState.connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C2028))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 串口连接卡片
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
                    text = "串口通讯参数设置",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE0E0E0)
                )

                // 串口选择
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ExposedDropdownMenuBox(
                        expanded = deviceExpanded && !isConnected,
                        onExpandedChange = { if (!isConnected) deviceExpanded = !deviceExpanded },
                        modifier = Modifier.weight(1f)
                    ) {
                        val deviceText = when {
                            selectedDeviceIndex == -1 -> "内置虚拟仿真滑台 (Mock 测试)"
                            selectedDeviceIndex in availableDevices.indices -> availableDevices[selectedDeviceIndex].displayName
                            else -> "未检测到 USB 串口设备"
                        }

                        OutlinedTextField(
                            value = deviceText,
                            onValueChange = {},
                            readOnly = true,
                            enabled = !isConnected,
                            label = { Text("USB 串口设备") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceExpanded) },
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
                            expanded = deviceExpanded && !isConnected,
                            onDismissRequest = { deviceExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("内置虚拟仿真滑台 (Mock 测试模式)") },
                                onClick = {
                                    onSelectDevice(-1)
                                    deviceExpanded = false
                                }
                            )
                            availableDevices.forEachIndexed { idx, dev ->
                                DropdownMenuItem(
                                    text = { Text(dev.displayName) },
                                    onClick = {
                                        onSelectDevice(idx)
                                        deviceExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onRefreshDevices,
                        enabled = !isConnected,
                        modifier = Modifier
                            .background(Color(0xFF2C3340), RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFF445060), RoundedCornerShape(4.dp))
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Color.White)
                    }
                }

                // 波特率选择
                ExposedDropdownMenuBox(
                    expanded = baudExpanded && !isConnected,
                    onExpandedChange = { if (!isConnected) baudExpanded = !baudExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = baudRate.toString(),
                        onValueChange = {},
                        readOnly = true,
                        enabled = !isConnected,
                        label = { Text("波特率 (Baud Rate)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = baudExpanded) },
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
                        expanded = baudExpanded && !isConnected,
                        onDismissRequest = { baudExpanded = false }
                    ) {
                        baudOptions.forEach { b ->
                            DropdownMenuItem(
                                text = { Text(b.toString()) },
                                onClick = {
                                    baudRate = b
                                    baudExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // 轴站号设置卡片
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
                    text = "三轴 RS485 站号寻址设置",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE0E0E0)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("X 轴站号", fontSize = 12.sp, color = Color(0xFFA0A8B4))
                        NumberStepper(
                            value = addrX.toDouble(),
                            onValueChange = {
                                addrX = it.toInt()
                                onUpdateAddresses(mapOf("x" to addrX, "y" to addrY, "z" to addrZ))
                            },
                            min = 1.0,
                            max = 255.0,
                            decimals = 0,
                            enabled = !isConnected
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text("Y 轴站号", fontSize = 12.sp, color = Color(0xFFA0A8B4))
                        NumberStepper(
                            value = addrY.toDouble(),
                            onValueChange = {
                                addrY = it.toInt()
                                onUpdateAddresses(mapOf("x" to addrX, "y" to addrY, "z" to addrZ))
                            },
                            min = 1.0,
                            max = 255.0,
                            decimals = 0,
                            enabled = !isConnected
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text("Z 轴站号", fontSize = 12.sp, color = Color(0xFFA0A8B4))
                        NumberStepper(
                            value = addrZ.toDouble(),
                            onValueChange = {
                                addrZ = it.toInt()
                                onUpdateAddresses(mapOf("x" to addrX, "y" to addrY, "z" to addrZ))
                            },
                            min = 1.0,
                            max = 255.0,
                            decimals = 0,
                            enabled = !isConnected
                        )
                    }
                }
            }
        }

        // 连接 / 断开 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    onConnect(baudRate, mapOf("x" to addrX, "y" to addrY, "z" to addrZ), selectedDeviceIndex == -1)
                },
                enabled = !isConnected,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2F6D2A),
                    disabledContainerColor = Color(0xFF222831)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("连 接", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Button(
                onClick = onDisconnect,
                enabled = isConnected,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF882222),
                    disabledContainerColor = Color(0xFF222831)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("断 开", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        // 状态提示标签
        val statusText = if (isConnected) {
            val onList = listOf("x", "y", "z").filter { connectionState.onlineMap[it] == true }.map { it.uppercase() }
            "已连接 · 在线: ${if (onList.isNotEmpty()) onList.joinToString(", ") else "无"}"
        } else {
            "未连接"
        }
        val statusColor = if (isConnected) Color(0xFF3AD06C) else Color(0xFF888888)

        Text(
            text = statusText,
            color = statusColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
