# XiaoZhi AI (小智 AI) v2.0

<div align="center">

**基于 Client-Server 架构的智能助手系统，支持多 Agent、可插拔 Skills、LangGraph 编排**

[![GitHub Stars](https://img.shields.io/github/stars/jieyou-io/phone-agent-xiaozhi?style=social)](https://github.com/jieyou-io/phone-agent-xiaozhi)
[![GitHub Forks](https://img.shields.io/github/forks/jieyou-io/phone-agent-xiaozhi?style=social)](https://github.com/jieyou-io/phone-agent-xiaozhi)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

[功能特性](#功能特性) • [快速开始](#快速开始) • [技术栈](#技术栈) • [常见问题](#常见问题) • [贡献指南](#贡献指南)

</div>

---

## 📖 项目简介

XiaoZhi AI 是一个创新的智能助手系统，采用 Client-Server 分离架构，将 AI 决策能力部署在服务端，通过 WebSocket 实时通信控制 Android 设备。项目支持多 Agent 协作、可插拔技能系统，并使用 LangGraph 进行智能编排。

**灵感来源**：本项目深受 [AutoGLM](https://github.com/zai-org/Open-AutoGLM) 启发，是其在移动端的创新实现。

## 🏗️ 项目架构

```
┌─────────────────────────┐                    ┌─────────────────────────┐
│   Android 客户端         │                    │   Python 后端            │
├─────────────────────────┤                    ├─────────────────────────┤
│                         │    WebSocket       │                         │
│  🎯 Agent 选择器         │ ←────────────────→  │  🚀 FastAPI             │
│  📱 任务执行界面         │                    │  🧠 LangGraph           │
│  🌐 翻译悬浮窗           │                    │  🤖 OpenAI Client       │
│  🛡️ 反诈警报             │                    │  💾 MySQL               │
│  📷 拍照构图助手         │                    │                         │
│  🎮 斗地主助手           │                    │  🔧 AI 决策引擎          │
│                         │                    │  📊 技能管理             │
│  📲 设备控制 (Shizuku)   │                    │  ⚙️ 模型配置             │
└─────────────────────────┘                    └─────────────────────────┘
```

## ✨ 功能特性

### 🎯 多 Agent 系统

| Agent | 功能描述 | 使用场景 |
|-------|---------|---------|
| 🤖 **通用模式** | 智能理解用户意图，执行各类操作 | 打开应用、发送消息、查询信息 |
| 🌐 **翻译大师** | 实时翻译屏幕内容，支持多语言 | 外语应用、文档翻译、学习辅助 |
| 🛡️ **反诈专家** | 识别诈骗信息，实时风险提醒 | 短信检测、通知分析、转账警告 |
| 🎮 **斗地主大师** | 分析牌局，提供出牌建议 | 游戏辅助、策略分析 |
| 📷 **拍照构图助手** | 三分法构图指导，自动对焦 | 摄影辅助、构图优化 |

### 🔧 核心能力

- **🔌 可插拔技能系统**：支持用户自定义技能，动态加载
- **🎛️ 三级模型配置**：全局默认 → 设备专用 → 技能专用
- **🔄 实时双向通信**：WebSocket 长连接，心跳保活，断线重连
- **📊 LangGraph 编排**：智能 Agent 状态管理，支持复杂工作流
- **🗄️ 数据持久化**：MySQL 存储配置，Redis 缓存会话
- **🌐 Web 管理后台**：Vue 3 构建，可视化配置所有参数

### 🎨 技术亮点

- ✅ **无需 Root**：基于 Shizuku 实现系统级操作
- ✅ **多设备管理**：一个后端支持多台 Android 设备
- ✅ **模型灵活切换**：支持 OpenAI、Claude、本地模型等
- ✅ **技能热更新**：修改提示词无需重启服务
- ✅ **安全认证**：JWT Token 认证，保护 API 安全

## 🚀 快速开始

### 前置要求

#### 1️⃣ **Shizuku（必须）**
本项目依赖 Shizuku 进行系统级操作（截图、点击、输入等）。

- 📥 下载地址：[https://shizuku.rikka.app/](https://shizuku.rikka.app/)
- 📖 使用方法：
  1. 安装 Shizuku App
  2. 启动 Shizuku 服务（Root 设备直接授权，非 Root 设备通过无线调试）
  3. 在 Shizuku 中授权给小智 AI

#### 2️⃣ **ADB Keyboard（用于文本输入）**
- 📥 下载：[ADBKeyboard.apk](https://github.com/senzhk/ADBKeyBoard/blob/master/ADBKeyboard.apk)
- ⚙️ 启用：安装后在 `设置 → 输入法` 中启用 `ADB Keyboard`
- 💡 命令启用：`adb shell ime enable com.android.adbkeyboard/.AdbIME`

#### 3️⃣ **开发环境**
- **Backend**: Python 3.9+, MySQL 5.7+
- **Admin Web**: Node.js 16+, npm/yarn
- **Android**: Android Studio, JDK 17+

### 安装步骤

#### 🐍 1. 启动 Python 后端

```bash
cd backend

# 安装依赖
pip install -r requirements.txt

# 配置环境变量
cp .env.example .env
# 编辑 .env 文件，配置以下内容：
# - OPENAI_API_KEY: 你的 OpenAI API Key
# - DATABASE_URL: MySQL 连接字符串
# - REDIS_URL: Redis 连接字符串（可选）

# 初始化数据库
mysql -u root -p xiaozhi < migrations/schema.sql

# 启动服务
python main.py
# 或后台运行: ./run_bg.sh
```

后端将在 `http://localhost:8000` 启动。

#### 🌐 2. 启动管理后台（可选）

```bash
cd admin-web

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

管理后台将在 `http://localhost:5173` 启动。

#### 📱 3. 编译 Android 客户端

```bash
cd android

# 编译并安装
./gradlew installDebug

# 或使用 Android Studio 打开项目，点击 Run
```

### 使用流程

1. **📲 启动 App**：打开小智 AI，首次启动会自动注册设备
2. **🔐 登录认证**：输入后端地址和凭证（默认 admin/admin123）
3. **🔌 连接后端**：App 自动通过 WebSocket 连接后端
4. **🎯 选择技能**：在技能页面选择需要的 Agent（如"翻译大师"）
5. **💬 发送任务**：输入任务描述（如"翻译这个页面"）
6. **🤖 AI 执行**：后端分析并返回动作指令，Android 自动执行



## 🛠️ 技术栈

### Backend (Python)
- **框架**: FastAPI - 高性能异步 Web 框架
- **AI 编排**: LangGraph - Agent 状态管理
- **数据库**: MySQL - 持久化存储
- **缓存**: Redis - 会话管理（可选）
- **WebSocket**: FastAPI WebSocket - 实时通信
- **认证**: JWT - Token 认证

### Admin Web (前端)
- **框架**: Vue 3 + TypeScript
- **构建工具**: Vite
- **UI 组件**: Element Plus
- **路由**: Vue Router
- **HTTP 客户端**: Axios

### Android (客户端)
- **语言**: Kotlin
- **架构**: MVVM + Fragment
- **网络**: OkHttp + WebSocket
- **权限管理**: Shizuku
- **UI**: Material Design 3

## 📂 目录结构

```
xiaozhi-ai/
├── android/              # Android 客户端
│   ├── app/
│   │   ├── src/main/java/com/xiaozhi/phoneagent/
│   │   │   ├── ui/           # UI 组件（Fragment、Activity）
│   │   │   ├── service/      # 后台服务（WebSocket、Automation）
│   │   │   ├── device/       # 设备控制（Shizuku）
│   │   │   ├── effects/      # 视觉效果（闪烁、震动）
│   │   │   └── utils/        # 工具类
│   │   └── build.gradle.kts
│   └── gradlew
├── backend/              # Python 后端
│   ├── agents/           # LangGraph Agent 实现
│   ├── skills/           # 技能系统
│   ├── api/              # REST API 接口
│   ├── websocket/        # WebSocket 服务
│   ├── db/               # 数据库模型
│   ├── config/           # 配置和提示词
│   ├── migrations/       # 数据库迁移
│   └── main.py           # 入口文件
├── admin-web/            # Vue 3 管理后台
│   ├── src/
│   │   ├── views/        # 页面组件
│   │   ├── api/          # API 客户端
│   │   └── router/       # 路由配置
│   └── package.json
└── lib-apps/             # 依赖应用（ADB Keyboard、Shizuku）
```

## 📸 项目截图

> 📝 **待添加**：欢迎贡献项目截图和演示视频！

<!-- 
示例：
![主界面](docs/images/home.jpg)
![技能选择](docs/images/skills.jpg)
![翻译悬浮窗](docs/images/translation.jpg)
-->

## ❓ 常见问题

<details>
<summary><b>Q1: 如何配置 API Key？</b></summary>

**方式 1：环境变量（推荐）**
```bash
# backend/.env
OPENAI_API_KEY=sk-your-api-key-here
```

**方式 2：管理后台**
访问 `http://localhost:5173/model-configs`，添加全局模型配置。

**方式 3：Android 设置**
在 App 的设置页面配置设备默认模型。
</details>

<details>
<summary><b>Q2: 如何添加自定义技能？</b></summary>

1. 在 `backend/skills/` 创建新的技能文件（如 `my_skill.py`）
2. 继承 `Skill` 基类，实现 `analyze()` 方法
3. 在数据库 `skills` 表中添加技能定义
4. 重启后端服务，技能自动加载

详见：[技能开发指南](backend/config/SKILL_PROMPTS_GUIDE.md)
</details>

<details>
<summary><b>Q3: WebSocket 连接失败怎么办？</b></summary>

**检查清单**：
- ✅ 后端服务是否正常运行（`http://localhost:8000/health`）
- ✅ Android 设备与后端网络是否互通
- ✅ 防火墙是否允许 8000 端口
- ✅ App 设置中的后端地址是否正确

**调试方法**：
```bash
# 查看后端日志
tail -f backend/nohup.out

# 测试 WebSocket 连接
wscat -c ws://localhost:8000/ws/device-id
```
</details>

<details>
<summary><b>Q4: Shizuku 授权失败？</b></summary>

1. 确保 Shizuku 服务已启动（通知栏有图标）
2. 在 Shizuku App 中手动授权给小智 AI
3. 如果仍失败，尝试重启 Shizuku 服务
4. 非 Root 设备需通过无线调试启动 Shizuku
</details>

<details>
<summary><b>Q5: 支持哪些 AI 模型？</b></summary>

**官方支持**：
- OpenAI (GPT-4, GPT-4o, GPT-3.5)
- Anthropic Claude
- 任何兼容 OpenAI API 的模型

**配置方法**：
在模型配置中设置 `base_url` 和 `api_key` 即可切换。
</details>

## 🗺️ Roadmap

- [ ] 📱 支持 iOS 客户端（基于 WebDriverAgent）
- [ ] 🌐 支持鸿蒙系统（基于 HDC）
- [ ] 🎨 优化管理后台 UI/UX
- [ ] 📊 添加使用统计和分析
- [ ] 🔐 支持多用户和权限管理
- [ ] 🌍 国际化支持（英文界面）
- [ ] 📦 Docker 一键部署
- [ ] 🤖 更多内置技能（日程管理、邮件助手等）

## 🤝 贡献指南

我们欢迎所有形式的贡献！无论是新功能、Bug 修复、文档改进还是问题反馈。

### 如何贡献

1. **🍴 Fork 本仓库**
2. **🌿 创建特性分支** (`git checkout -b feature/AmazingFeature`)
3. **💾 提交更改** (`git commit -m 'Add some AmazingFeature'`)
4. **📤 推送到分支** (`git push origin feature/AmazingFeature`)
5. **🔀 提交 Pull Request**

### 代码规范

- **Python**: 遵循 PEP 8，使用 `black` 格式化
- **Kotlin**: 遵循 Kotlin 官方代码风格
- **TypeScript**: 使用 ESLint + Prettier

### 提交 Issue

- 🐛 **Bug 报告**：请提供复现步骤、错误日志、环境信息
- 💡 **功能建议**：请描述使用场景、预期效果
- ❓ **使用问题**：请先查看 FAQ 和文档

## 📄 许可证

本项目采用 [MIT License](LICENSE) 开源协议。

## 🙏 致谢

- 特别感谢 [AutoGLM](https://github.com/zai-org/Open-AutoGLM) 项目提供的灵感与基础工作
- 感谢 [Shizuku](https://github.com/RikkaApps/Shizuku) 提供的强大设备控制能力
- 感谢所有贡献者和支持者 ❤️

## 📞 联系方式

- **GitHub Issues**: [提交问题](https://github.com/jieyou-io/phone-agent-xiaozhi/issues)
- **Discussions**: [参与讨论](https://github.com/jieyou-io/phone-agent-xiaozhi/discussions)
- **Email**: wbkasd7@gmail.com

---

<div align="center">

**如果这个项目对你有帮助，请给我们一个 ⭐ Star！**

Made with ❤️ by [jieyou-io](https://github.com/jieyou-io)

</div>
