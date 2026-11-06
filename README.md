# NikonSync - 尼康微单专属相机伴侣

这是一个基于现代 Android 架构（Kotlin + 协程 + Material 3）打造的尼康 Z 系列微单（如 Nikon Z30、Z50、Zfc）专属无线图传与遥控应用。

---

## 一、 功能亮点

1. **底层通信引擎**
   - 采用标准 **PTP/IP 协议栈**（ISO 15740）与尼康厂商扩展指令集。
   - 内置了提取自原厂驱动并经过深度净化的 `libzrelay_core.so`（支持 ARM64、ARMv7、x86_64）与 `NativePtpCore` JNI 原生桥接。
2. **拍完秒传到手机相册**
   - 后台前台常驻服务（`PhotoSyncService`），保持与相机的长连接。
   - 自动监听相机快门事件（`0x4002` ObjectAdded），一旦相机按下快门成像，即刻通过 Wi-Fi 将 RAW/JPEG 照片静默拉取并写入手机系统相册（`Pictures/NikonSync/`）。
3. **手机远程按快门**
   - 在手机端提供一键远程触发快门指令（`0x100E` InitiateCapture），支持延迟拍摄与遥控打卡。
4. **实时连接状态与运行日志**
   - 界面实时展示连接指示灯、相机响应状态及通信日志控制台。

---

## 二、 目录结构

```text
NikonSync/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml              # 声明 Wi-Fi、前台服务与通知权限
│   │   ├── java/
│   │   │   ├── com/kw/ztransfer/
│   │   │   │   └── NativePtpCore.java       # 与底层 .so 动态库严格绑定的 JNI 桥
│   │   │   └── com/nikonsync/app/
│   │   │       ├── ptp/
│   │   │       │   └── NikonPtpClient.kt    # 纯净 Kotlin 封装的 PTP/IP 通信客户端
│   │   │       ├── service/
│   │   │       │   └── PhotoSyncService.kt  # Android 前台长连接服务 (监听快门 & 存相册)
│   │   │       └── ui/
│   │   │           └── MainActivity.kt      # 控制台与相册预览主界面
│   │   ├── jniLibs/
│   │   │   ├── arm64-v8a/libzrelay_core.so  # 64 位 ARM 原生通信库
│   │   │   ├── armeabi-v7a/libzrelay_core.so
│   │   │   └── x86_64/libzrelay_core.so
│   │   └── res/                             # Material 主题与界面布局
│   ├── build.gradle.kts                     # 模块构建配置
│   └── proguard-rules.pro                   # 保护 JNI 绑定的混淆规则
├── gradle/
│   ├── libs.versions.toml                   # 现代化依赖版本目录
│   └── wrapper/gradle-wrapper.properties
├── build.gradle.kts                         # 顶层构建脚本
└── settings.gradle.kts
```

---

## 三、 如何使用

1. 打开 **Android Studio**，选择 `Open` 并选中本工程根目录：
   `d:\DeskBox\z30相机\NikonSync`
2. 等待 Gradle 同步完成后，点击右上角的绿色运行按钮 ▶（Run），即可直接安装到你的安卓手机上。
3. **连接 Z30 相机**：
   - 打开尼康相机菜单 -> 进入 `网络菜单` -> 开启 `连接至智能设备`（或开启相机的 Wi-Fi 热点）。
   - 手机 Wi-Fi 搜索并连接相机的热点（如 `Z30_xxxx`）。
   - 打开 `NikonSync` App，保持默认 IP（`192.168.0.1`），点击 **“开始连接”**。
   - 连接成功后，指示灯变为绿色 `● 已连接`，即可体验远程遥控或拍照秒传功能！
