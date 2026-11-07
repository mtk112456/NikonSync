# NikonSync - 尼康微单专属相机伴侣

这是一个基于现代 Android 架构（Kotlin + 协程 + Material 3）打造的尼康 Z 系列微单（如 Nikon Z30、Z50、Zfc）专属无线图传与遥控应用。

---

## 一、 功能亮点

1. **底层通信引擎**
   - 采用标准 **PTP/IP 协议栈**（ISO 15740）与尼康厂商扩展指令集。
   - 内置经过深度净化的 `libzrelay_core.so`（支持 ARM64、ARMv7、x86_64）与 `NativePtpCore` JNI 原生桥接。
2. **拍完秒传到手机相册 (即拍即传)**
   - 后台前台常驻长连接服务（`PhotoSyncService`），自动监听相机快门事件（`0x4002` ObjectAdded）。
   - 格式策略灵活可控：支持**仅同步 JPG**、**全部 (JPG+RAW)** 或 **仅同步 RAW (NEF)**。
3. **相机卡内画廊回看与批量下载**
   - 深入读取相机 SD 卡文件索引，九宫格网格缩略图浏览，支持大图全屏预览及无损下载至手机相册。
4. **低功耗蓝牙 (BLE) GPS 定位实时中继**
   - 手机后台运行 `BluetoothGpsRelayService`，自动将手机的高精度经纬度、海拔和 UTC 时间实时同步给相机，照片自动拥有 GPS EXIF 信息。
5. **Picture Control 胶片调色预设**
   - 内置富士 Classic Chrome、柯达胶片、黑白电影等多种调色色彩曲线，支持导出 `.NP3` 格式直接导入相机。
6. **内置配对指南与操作说明**
   - 详细的相机热点配对步骤、手机防断网保活指南，新手上手零门槛。
7. **应用内远程在线更新 (OTA)**
   - 每次启动时静默检查更新，支持手动点击一键检查。
   - 采用国内 CDN 镜像加速，一键下载并直接调起系统安装升级。
8. **手机远程快门**
   - 手机端一键触发相机快门（`0x100E` InitiateCapture），支持远程延迟打卡。

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
