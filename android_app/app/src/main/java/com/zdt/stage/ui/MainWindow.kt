package com.zdt.stage.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zdt.stage.core.serial.UsbSerialManager
import com.zdt.stage.ui.panels.*
import com.zdt.stage.ui.theme.*
import com.zdt.stage.ui.view3d.Stage3DView
import com.zdt.stage.viewmodel.StageViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainWindow(
    viewModel: StageViewModel,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config by viewModel.config.collectAsState()
    val connState by viewModel.connectionState.collectAsState()
    val snapshots by viewModel.snapshots.collectAsState()
    val scriptProgress by viewModel.scriptProgress.collectAsState()
    val availableDevices by viewModel.availableDevices.collectAsState()
    val selectedDeviceIndex by viewModel.selectedDeviceIndex.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val currentTab by viewModel.currentTab.collectAsState()

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || configuration.screenWidthDp >= 840

    val tabs = listOf("连接", "点动", "定位", "回零", "示教", "脚本", "标定", "状态")

    // 计算三轴当前坐标 (用于 3D 监视)
    val curX = snapshots["x"]?.mm(config.axes["x"]?.calibration ?: return) ?: 0.0
    val curY = snapshots["y"]?.mm(config.axes["y"]?.calibration ?: return) ?: 0.0
    val curZ = snapshots["z"]?.mm(config.axes["z"]?.calibration ?: return) ?: 0.0

    val travelX = config.axes["x"]?.travel_mm ?: 600.0
    val travelY = config.axes["y"]?.travel_mm ?: 400.0
    val travelZ = config.axes["z"]?.travel_mm ?: 300.0

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZdtBg)
    ) {
        // 顶部导航与快捷操作条
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ZDT 三维移动滑台",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    val onList = listOf("x", "y", "z")
                        .filter { connState.onlineMap[it] == true }
                        .map { it.uppercase() }
                    val statusText = if (connState.connected) {
                        "已连接 · 在线: ${if (onList.isNotEmpty()) onList.joinToString(",") else "无"}"
                    } else {
                        "未连接"
                    }
                    val statusColor = if (connState.connected) Color(0xFF3AD06C) else Color(0xFF888888)

                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = statusColor,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(Color(0xFF1E232B), RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFF333B48), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            },
            actions = {
                OutlinedButton(
                    onClick = onImportClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text("导入配置", fontSize = 12.sp, color = Color(0xFFD0D0D0))
                }

                OutlinedButton(
                    onClick = onExportClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("导出配置", fontSize = 12.sp, color = Color(0xFFD0D0D0))
                }

                // 快捷急停按钮
                Button(
                    onClick = { viewModel.estop() },
                    colors = ButtonDefaults.buttonColors(containerColor = ZdtEstopRed),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("急 停", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161A22))
        )

        // 主体视口区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isLandscape) {
                // 平板横屏：水平双栏布局（左 3D 监视，右 8 大功能面板）
                Row(modifier = Modifier.fillMaxSize()) {
                    Stage3DView(
                        online = connState.connected,
                        currentX = curX,
                        currentY = curY,
                        currentZ = curZ,
                        travelX = travelX,
                        travelY = travelY,
                        travelZ = travelZ,
                        onEnableAll = { viewModel.enableAll() },
                        onDisableAll = { viewModel.disableAll() },
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxHeight()
                    )

                    VerticalDivider(color = ZdtBorder)

                    // 右侧 Tab 页
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        TabHeader(tabs = tabs, currentTab = currentTab, onTabSelect = { viewModel.setTab(it) })

                        Box(modifier = Modifier.fillMaxSize()) {
                            TabContent(
                                currentTab = currentTab,
                                viewModel = viewModel,
                                config = config,
                                connState = connState,
                                snapshots = snapshots,
                                scriptProgress = scriptProgress,
                                availableDevices = availableDevices,
                                selectedDeviceIndex = selectedDeviceIndex
                            )
                        }
                    }
                }
            } else {
                // 手机竖屏：自适应上下折叠布局
                Column(modifier = Modifier.fillMaxSize()) {
                    Stage3DView(
                        online = connState.connected,
                        currentX = curX,
                        currentY = curY,
                        currentZ = curZ,
                        travelX = travelX,
                        travelY = travelY,
                        travelZ = travelZ,
                        onEnableAll = { viewModel.enableAll() },
                        onDisableAll = { viewModel.disableAll() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    )

                    HorizontalDivider(color = ZdtBorder)

                    TabHeader(tabs = tabs, currentTab = currentTab, onTabSelect = { viewModel.setTab(it) })

                    Box(modifier = Modifier.fillMaxSize()) {
                        TabContent(
                            currentTab = currentTab,
                            viewModel = viewModel,
                            config = config,
                            connState = connState,
                            snapshots = snapshots,
                            scriptProgress = scriptProgress,
                            availableDevices = availableDevices,
                            selectedDeviceIndex = selectedDeviceIndex
                        )
                    }
                }
            }
        }

        // 底部状态栏（显示最近一条命令执行结果或警报信息）
        Surface(
            color = Color(0xFF11151C),
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 0.5.dp, color = ZdtBorder)
        ) {
            Text(
                text = statusMessage,
                color = Color(0xFFD0D0D0),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun TabHeader(
    tabs: List<String>,
    currentTab: Int,
    onTabSelect: (Int) -> Unit
) {
    ScrollableTabRow(
        selectedTabIndex = currentTab,
        containerColor = Color(0xFF222730),
        contentColor = Color(0xFFFFE066),
        edgePadding = 4.dp
    ) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = currentTab == index,
                onClick = { onTabSelect(index) },
                text = {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = if (currentTab == index) FontWeight.Bold else FontWeight.Normal,
                        color = if (currentTab == index) Color(0xFFFFE066) else Color(0xFFE0E0E0)
                    )
                }
            )
        }
    }
}

@Composable
private fun TabContent(
    currentTab: Int,
    viewModel: StageViewModel,
    config: com.zdt.stage.data.model.AppConfig,
    connState: com.zdt.stage.core.serial.ConnectionState,
    snapshots: Map<String, com.zdt.stage.data.model.AxisSnapshot>,
    scriptProgress: com.zdt.stage.core.motion.ScriptProgress,
    availableDevices: List<UsbSerialManager.DeviceItem>,
    selectedDeviceIndex: Int
) {
    when (currentTab) {
        0 -> ConnectionPanel(
            config = config,
            connectionState = connState,
            availableDevices = availableDevices,
            selectedDeviceIndex = selectedDeviceIndex,
            onSelectDevice = { viewModel.selectDevice(it) },
            onRefreshDevices = { viewModel.scanDevices() },
            onConnect = { baud, addrs, mock -> viewModel.connect(baud, addrs, mock) },
            onDisconnect = { viewModel.disconnect() },
            onUpdateAddresses = { viewModel.updateAddresses(it) }
        )
        1 -> JogPanel(
            config = config,
            connectionState = connState,
            onJogStart = { axis, isPlus -> viewModel.jogStart(axis, isPlus) },
            onJogStop = { axis -> viewModel.jogStop(axis) },
            onEstop = { viewModel.estop() },
            onUpdateMotion = { axis, spd, acc -> viewModel.updateMotion(axis, jogSpeed = spd, jogAccel = acc) }
        )
        2 -> GotoPanel(
            config = config,
            connectionState = connState,
            snapshots = snapshots,
            onGotoSingle = { axis, target -> viewModel.gotoSingle(axis, target) },
            onGotoParallel = { x, y, z -> viewModel.gotoParallel(x, y, z) },
            onGotoOrigin = { viewModel.gotoOrigin() },
            onUpdateMotion = { axis, spd, acc -> viewModel.updateMotion(axis, moveSpeed = spd, moveAccel = acc) }
        )
        3 -> HomePanel(
            config = config,
            connectionState = connState,
            snapshots = snapshots,
            onHome = { viewModel.home(it) },
            onStopHome = { viewModel.stopHome(it) },
            onSetHome = { viewModel.setHome(it) },
            onSetOriginHere = { viewModel.setOriginHere(it) },
            onGotoOrigin = { viewModel.gotoOrigin() }
        )
        4 -> TeachPanel(
            config = config,
            connectionState = connState,
            snapshots = snapshots,
            onAddPreset = { name, x, y, z -> viewModel.addPreset(name, x, y, z) },
            onUpdatePreset = { idx, p -> viewModel.updatePreset(idx, p) },
            onDeletePreset = { viewModel.deletePreset(it) },
            onClearPresets = { viewModel.clearPresets() },
            onGotoPoint = { x, y, z -> viewModel.gotoParallel(x, y, z) }
        )
        5 -> ScriptPanel(
            config = config,
            connectionState = connState,
            snapshots = snapshots,
            scriptProgress = scriptProgress,
            onAddScriptPoint = { x, y, z, dwell -> viewModel.addScriptPoint(x, y, z, dwell) },
            onUpdateScriptPoint = { idx, pt -> viewModel.updateScriptPoint(idx, pt) },
            onDeleteScriptPoint = { viewModel.deleteScriptPoint(it) },
            onMoveScriptPoint = { idx, up -> viewModel.moveScriptPoint(idx, up) },
            onClearScriptPoints = { viewModel.clearScriptPoints() },
            onStartScript = { loops, spd, acc -> viewModel.startScript(loops, spd, acc) },
            onStopScript = { viewModel.stopScript() }
        )
        6 -> CalibratePanel(
            config = config,
            connectionState = connState,
            snapshots = snapshots,
            onCalibrateMove = { axis, p -> viewModel.calibrateMove(axis, p) },
            onApplyCalibration = { axis, cal -> viewModel.applyCalibration(axis, cal) },
            onSetInvert = { axis, inv -> viewModel.setInvert(axis, inv) }
        )
        7 -> StatusPanel(
            config = config,
            connectionState = connState,
            snapshots = snapshots
        )
    }
}
