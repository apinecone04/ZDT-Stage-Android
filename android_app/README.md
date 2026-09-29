# ZDT 三维移动滑台 Android 11 上位机系统

本项目是 **ZDT 三维移动滑台系统** 专为 **Android 11 (API Level 30)** 工业平板与移动终端量身打造的原生上位机应用程序。  
基于 **Kotlin + Jetpack Compose** 现代化技术栈构建，严格遵循张大头 ZDT_X42S 第二代闭环步进驱动器的半双工 RS485 协议规范，**100% 完整复现了原有 Windows (PyQt5) 上位机的所有功能与交互体验**。

---

## 🌟 核心特性与功能对齐表

| 功能模块 | Windows 原版 (PyQt5) | Android 11 原版 (Jetpack Compose) | 说明 |
| :--- | :--- | :--- | :--- |
| **通信框架** | pyserial 后台 QThread | `usb-serial-for-android` + 单线程 Coroutine 调度器 | 纯用户态 USB Host 驱动，半双工严格排队防丢包 |
| **热插拔与权限**| 重新扫描 COM 口 | 自动拦截 `ACTION_USB_DEVICE_ATTACHED` 广播 | 兼容 Android 11 `FLAG_IMMUTABLE` 动态授权 |
| **点动控制 (Jog)**| 六键点动、按住即动、松手即停 | `JogHoldButton` (触摸 Down 启动 / Up 停止) | 零延迟无缝手感，UI 的 `+` 始终对应 `+mm` 方向 |
| **方向反转 (Invert)**| 每轴可反转，Y 轴默认 True | 算法对齐 `MotionUnits.deltaMmToPulses` | 点动与绝对定位透明取反，保证坐标系一致 |
| **绝对定位 (Goto)**| 单轴 Goto / 三轴并行 Goto | 单轴 Goto / 三轴并行 Goto / 回原点 | 软限位 `[0, travel_mm]` 硬性截断，未标定拦截 |
| **回零与原点 (Home)**| 机械回零、设电机零点、设显示原点 | 机械回零、设电机零点、设显示原点 | **机械归零完成后自动将当前位置设为显示原点** |
| **预设示教 (Teach)**| 示教点位列表增删改查、调出 Goto | 预设点位列表增删改查、读取当前坐标录入 | 即改即存，支持一键调出定位 |
| **自动化路径脚本** | 多点路径、到点停留、循环次数、速度覆盖 | 协程驱动自动化状态机 (Move → InPosition → Dwell) | 实时进度显示，**检测到离线/堵转立即急停中止** |
| **单轴向导标定** | 两步法（读 a0 → 走 N 脉冲 → 量 d → 算比例）| 两步向导式测量卡片 + 滚动日志输出 | 自动计算 `pulses_per_mm` 与 `angle_per_mm` 并保存 |
| **状态监视看板** | 20pt 大字坐标、电压/电流/速度、5 盏 LED | 24pt 等宽大字坐标、电气参数网格、5 盏 LED | 100ms 周期查询 `0x43 0x7A`，毫秒级更新 |
| **3D 空间监视** | `pyqtgraph.opengl` 行程线框、末端点、轨迹 | **OpenGL ES 2.0 (GLSurfaceView)** | 600x400x300 线框、地板网格、橙色末端、垂直吊线、3000 点黄色轨迹 |
| **2D 三视图回退** | `View3DFallback` (XY/XZ/YZ) | `Stage3DCanvasFallback` Compose 2D 绘图 | 一键在 3D 视口与 2D 三视图之间无缝切换 |
| **Z 轴防坠安全** | 断开与退出不发 disable | 断开与退出**绝不下发 disable 指令** | 垂直 Z 轴保持通电自锁力矩，避免重力自由坠落砸伤机械 |
| **配置导入导出** | JSON 文件导入导出 | **Android 11 Storage Access Framework (SAF)** | 系统文件选择器跨应用存储，免敏感外部存储权限 |
| **离线仿真测试** | 无独立虚拟器 | **内置虚拟仿真串口 (Mock 模式)** | 无物理硬件或模拟器上即可 100% 完整运行演练 |

---

## 🏗️ 架构与源码结构

```
android_app/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml              // USB Host 权限、特性过滤与防重启配置
│   │   │   ├── res/xml/device_filter.xml        // 兼容 CH340, FTDI, CP2102, PL2303 等芯片
│   │   │   └── java/com/zdt/stage/
│   │   │       ├── MainActivity.kt              // 主活动入口与 SAF 导入导出桥接
│   │   │       ├── core/
│   │   │       │   ├── protocol/                // 底层通信协议
│   │   │       │   │   ├── ZdtConstants.kt      // 0x6B 校验尾、指令码、定长常量
│   │   │       │   │   ├── ZdtCodec.kt          // Sign-Magnitude 符号转换、帧封装与校验
│   │   │       │   │   └── ZdtFrame.kt          // 协议帧与状态实体
│   │   │       │   ├── serial/                  // 硬件通信与单线程调度
│   │   │       │   │   ├── ISerialPort.kt       // 串口抽象接口
│   │   │       │   │   ├── UsbSerialPortAdapter.kt // usb-serial-for-android 适配器
│   │   │       │   │   ├── UsbSerialManager.kt  // 动态权限申请与设备热插拔扫描
│   │   │       │   │   ├── Rs485SerialDispatcher.kt // 半双工单线程串行调度器 (150ms 超时/3次重试)
│   │   │       │   │   └── MockSerialPort.kt    // 虚拟滑台硬件仿真器
│   │   │       │   └── motion/                  // 运动学与状态机
│   │   │       │       ├── MotionUnits.kt       // 脉冲 ↔ mm、角度 ↔ mm 换算与 Invert 反转
│   │   │       │       └── ScriptExecutor.kt    // 路径多点循环状态机与安全联锁
│   │   │       ├── data/
│   │   │       │   ├── model/                   // 数据模型 (AppConfig, AxisConfig, Calibration...)
│   │   │       │   └── repository/              // AppConfigRepository (JSON持久化), StageRepository
│   │   │       ├── viewmodel/
│   │   │       │   └── StageViewModel.kt        // 全局状态管理与业务分发
│   │   │       └── ui/
│   │   │           ├── MainWindow.kt            // 自适应响应式主布局 (平板横屏双栏 / 手机竖屏上下)
│   │   │           ├── theme/                   // #1c2028 工控暗黑主题配色
│   │   │           ├── components/              // JogHoldButton, LedChip, NumberStepper, CoordOverlay
│   │   │           ├── view3d/                  // Stage3DView, Stage3DGlRenderer, Stage3DCanvasFallback
│   │   │           └── panels/                  // 8 大控制面板实现
│   │   └── test/java/com/zdt/stage/             // 完整的协议与单位换算单元测试
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🔌 硬件接线与准备工作

1. **Android 设备准备**：
   - 系统版本：Android 8.0 ~ Android 14（核心面向 Android 11）。
   - 接口：支持 USB OTG 功能的 Type-C 或 Micro-USB 接口。
2. **连接拓扑**：
   ```
   [Android 平板/手机]
          │
      (USB OTG 线)
          ↓
   [USB 转 RS485 适配器] (CH340 / FTDI / CP2102)
     ├── A+ 差分信号线 ────── 并接 ─── X轴 A+ ── Y轴 A+ ── Z轴 A+
     └── B- 差分信号线 ────── 并接 ─── X轴 B- ── Y轴 B- ── Z轴 B-
   ```
3. **电机出厂配置确认**：
   - 驱动器型号：张大头 ZDT_X42S 第二代闭环步进驱动板。
   - 站号设定：X 轴 = 1, Y 轴 = 2, Z 轴 = 3。
   - 波特率：115200 bps（上位机支持 9600 ~ 921600 切换）。
   - 校验位：固定校验（FIXED，帧尾 `0x6B`）。

---

## 🚀 编译构建与安装步骤

### 方式一：GitHub Actions 云端自动构建并下载 APK（无需本地配置 Android 环境）
本项目已配置好 GitHub Actions 自动化编译工作流（`.github/workflows/build-apk.yml`）：
1. 提交或推送代码至 GitHub 仓库：
   ```bash
   git add .
   git commit -m "feat: Android 11 上位机应用与自动构建工作流"
   git push origin master
   ```
2. 进入 GitHub 仓库网页，点击顶部的 **Actions** 选项卡。
3. 在左侧列表中点击 **Build Android APK**，或点击右侧 **Run workflow** 手动触发构建。
4. 约 2~3 分钟构建完成后，点击进入该次运行详情页面，在最下方的 **Artifacts（产物）** 列表中点击下载 **`ZDT-Stage-Android-v1.0.0-Debug`** 压缩包。
5. 解压后即可得到 **`app-debug.apk`** 安装包，发送至 Android 平板/手机即可直接点击安装！

### 方式二：使用本地 Android Studio 打开构建
1. 打开 **Android Studio** (Flamingo / Giraffe / Hedgehog 或更高版本)。
2. 点击 **File -> Open...**，选择本项目中的 `android_app` 目录。
3. 等待 Gradle 自动完成依赖同步（Gradle 8.4，AGP 8.2.2，Kotlin 1.9.22）。
4. 连接 Android 11 设备或启动 Android 模拟器，点击顶部绿色的 **Run 'app' (Shift + F10)** 即可自动编译安装。

### 方式三：本地命令行 Gradle 打包
在已安装 JDK 17 与 Android SDK 的开发机上执行：
```bash
cd android_app
./gradlew assembleDebug
```
编译成功后，生成的 APK 文件位于：
```
android_app/app/build/outputs/apk/debug/app-debug.apk
```
通过 ADB 安装到已连接设备：
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🎮 使用与操作指南

### 1. 启动与设备连接
- 将 USB-RS485 转接器插入 Android 平板 OTG 口，系统会自动弹出 USB 授权提示，点击“允许”。
- 打开应用，进入【连接】选项卡：
  - 若已连接物理硬件：下拉框选择已识别的 USB 设备（如 `WCH CH340`），确认波特率（115200）与站号（1, 2, 3），点击 **“连接”**。
  - 若无物理硬件（演练体验）：下拉框选择 **“内置虚拟仿真滑台 (Mock 测试模式)”**，点击 **“连接”** 即可立即体验全部控制功能。
- 连接成功后，应用会自动平滑切换到【点动】面板。

### 2. 点动控制 (Jog)
- 按住 `X-` / `X+`、`Y-` / `Y+`、`Z-` / `Z+`：滑块立即以设定速度转动。
- 松开手指：电机毫秒级平滑停止。
- 遇紧急情况随时点击底部醒目的 **“急 停 全 部”** 大红按钮锁定全部轴。

### 3. 绝对定位 (Goto) 与机械回零 (Home)
- 【回零】：在【回零】面板点击各轴“机械回零”，滑台自动寻零。归零完毕后，上位机**自动同步当前编码器物理坐标为显示原点 (0.00 mm)**。
- 【定位】：在【定位】面板输入目标 mm 坐标，支持单轴 Goto 或点击 **“Goto (三轴并行)”** 协同移动，内置软限位防撞保护。

### 4. 示教点位与自动化路径脚本
- 【示教】：在【示教】面板，滑台移动到特定工位后，点击 **“添加当前点”** 并命名，可随时一键调出快速定位。
- 【脚本】：在【脚本】面板编排多点路径与到点停留时间，设定循环次数（0 为无限循环），点击 **“▶ 运行”** 开始全自动循迹，遇到异常堵转会自动安全中止。

### 5. 3D 空间监视器交互
- 单指拖拽：360° 旋转视角（俯仰角与方位角）。
- 双指捏合：平滑缩放视图距离。
- 点击 **“清除轨迹”**：清空当前黄色运动轨迹线。
- 点击 **“2D 三视图”**：一键切换为俯视/正视/侧视工程三视图。

### 6. 配置备份与迁移
- 点击标题栏右侧的 **“导出配置”**：调用 Android SAF 系统选择器，将当前的标定系数、速度参数、预设示教点与脚本路径保存为 `.json` 文件。
- 点击 **“导入配置”**：随时从本地或 U 盘载入历史配置文件。

---

## 🛡️ 安全特别警示

> ⚠️ **垂直 Z 轴防重力坠落安全联锁**：  
> 垂直 Z 轴为竖直布置，在步进电机失能断电（`disable`）时将彻底失去锁相力矩，滑块与负载会因重力急速坠落！  
> 本上位机在架构层面严格保证：**在 USB 拔出、网络断开、App 退出或点击断开连接时，严禁主动下发 disable 指令！** 电机将持续保持抱闸自锁。仅在用户明确需要检修并在 3D 视图中经过二次弹窗确认后，才允许手动断开。
