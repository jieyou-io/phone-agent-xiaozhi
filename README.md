# XiaoZhi AI (小智 AI)

这是一个基于 Android 的智能助手项目，旨在为移动设备提供先进的 AI 能力。
本项目深受 [AutoGLM](https://github.com/zai-org/Open-AutoGLM) 的启发，是其在 Android 端的一种探索与实现。

## 致谢
特别感谢 [AutoGLM](https://github.com/zai-org/Open-AutoGLM) 项目提供的灵感与基础工作。
- Python 项目地址: [https://github.com/zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)

## 功能特性
- **智能对话**: 通过自然语言与 AI 助手交互。
- **移动端优化**: 专为 Android 平台设计。

## 目录结构
- `android/`: Android 应用程序源代码。
- `lib-apps/`: 依赖的应用程序。

## 快速开始

### 前置要求
1. **Shizuku (必须)**
   - 本项目依赖 Shizuku 进行系统层面的操作（如模拟点击、截图等）。
   - 下载地址: [https://shizuku.rikka.app/](https://shizuku.rikka.app/)
   - **使用方法**:
     1. 安装 Shizuku app。
     2. 按照 Shizuku app 内的指引启动服务（已 root 设备直接授权，未 root 设备可通过无线调试启动）。
     3. 启动小智 AI 后，请在 Shizuku 中授权给小智 AI。

2. **ADB Keyboard (用于文本输入)**
   - 下载 [安装包](https://github.com/senzhk/ADBKeyBoard/blob/master/ADBKeyboard.apk) 并在对应的安卓设备中进行安装。
   - 注意，安装完成后还需要到 `设置-输入法` 或者 `设置-键盘列表` 中启用 `ADB Keyboard` 才能生效(或使用命令`adb shell ime enable com.android.adbkeyboard/.AdbIME` [How-to-use](https://github.com/senzhk/ADBKeyBoard/blob/master/README.md#how-to-use))

3. **Android Studio**
4. **JDK 17+**

### 编译与运行
1. 使用 Android Studio 打开项目。
2. 同步 Gradle。
3. 在设备或模拟器上运行应用程序。
