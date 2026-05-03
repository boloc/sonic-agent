# Sonic Agent

Sonic 云真机测试平台的 Agent 端，负责连接和控制真实设备（Android/iOS）。
[文档地址](https://soniccloudorg.github.io/deploy/agent-deploy.html)

## 📋 环境要求

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 17+ | 必须使用 Java 17 或更高版本 |
| Maven | 3.6+ | 用于构建项目 |
| ADB | 最新版 | Android 设备调试（Android 必需） |
| Xcode | 最新版 | iOS 设备调试（仅 macOS，iOS 必需） |

## 🔧 配置文件

配置文件位于 `config/application-sonic-agent.yml`：

```yaml
sonic:
  agent:
    # Agent 机器的 IP 地址（必须是其他机器可访问的 IP）
    host: 192.168.1.100
    # Agent 服务端口
    port: 7777
    # 在 Sonic 前端"新增 Agent"时生成的 Key
    key: your-agent-key-here
  server:
    # Sonic Server 的地址
    host: your-server-ip
    # Sonic Server 的端口（前端访问端口）
    port: 3000

modules:
  android:
    scrcpy:
      # (可选) 远程查看时降低带宽/延迟：限制分辨率/帧率
      # max-size: 1280   # 0 表示不限制
      # max-fps: 30      # 建议 15/20/30
      # video-codec: h264 # 或 h265（依赖设备支持）
  ios:
    # WebDriverAgent 的 Bundle ID
    wda-bundle-id: com.sonic.WebDriverAgentRunner
    # (仅 macOS) WDA 的 Xcode 项目路径
    wda-xcode-project-path: WebdriverAgent/WebDriverAgent.xcodeproj
```

## 🏗️ 构建方式

### 支持的平台

在 `pom.xml` 中修改 `<platform>` 属性：

| 平台值 | 说明 |
|-------|------|
| `windows-x86` | Windows 32位 |
| `windows-x86_64` | Windows 64位 |
| `macosx-arm64` | macOS ARM (M1/M2/M3) |
| `macosx-x86_64` | macOS Intel |
| `linux-arm64` | Linux ARM64 |
| `linux-x86` | Linux 32位 |
| `linux-x86_64` | Linux 64位 |

### 构建命令

```bash
# 1. 进入项目目录
cd sonic-agent

# 2. 修改 pom.xml 中的 platform 为目标平台（可选）
# 默认为 linux-arm64

# 3. 构建项目
mvn clean package -DskipTests

# 4. 构建产物位于
# target/sonic-agent-{platform}.jar
```

### 指定平台构建（无需修改 pom.xml）

```bash
# linux-arm64
java -Dfile.encoding=utf-8 -jar sonic-agent-linux-arm64.jar

# Windows 64位
mvn clean package -DskipTests -Dplatform=windows-x86_64

# macOS ARM (M1/M2/M3)
mvn clean package -DskipTests -Dplatform=macosx-arm64

# Linux 64位
mvn clean package -DskipTests -Dplatform=linux-x86_64
```

## 🚀 启动方式

### 启动命令

```bash
# 进入 JAR 所在目录，确保 config/application-sonic-agent.yml 配置正确
java -Dfile.encoding=utf-8 -jar sonic-agent-{platform}.jar
```

> ⚠️ **注意**：`-Dfile.encoding=utf-8` 必须放在 `-jar` 之前，否则不会生效！

### 后台运行（Linux/macOS）

```bash
# 后台启动
# nohup java -Dfile.encoding=utf-8 -jar sonic-agent-{platform}.jar > logs/sonic-agent.log 2>&1 &
nohup java -Dfile.encoding=utf-8 -jar sonic-agent-linux-arm64.jar > logs/sonic-agent.log 2>&1 &
# 查看日志
tail -f logs/sonic-agent.log

# 查看进程
ps aux | grep sonic-agent

# 停止服务
kill $(pgrep -f sonic-agent)
```

### Windows 后台运行

```batch
:: 创建启动脚本 start.bat
start /b java -Dfile.encoding=utf-8 -jar sonic-agent-windows-x86_64.jar > logs\sonic-agent.log 2>&1
```

## 📁 目录结构

```text
sonic-agent/
├── config/
│   └── application-sonic-agent.yml  # 配置文件
├── mini/                            # minicap 相关文件（Android 截图）
├── plugins/                         # 插件目录
│   ├── adb                          # ADB 可执行文件
│   ├── sonic-android-apk.apk        # Sonic Android APK
│   ├── sonic-android-scrcpy.jar     # Scrcpy 服务
│   └── ...
├── logs/                            # 日志目录（运行时生成）
├── src/                             # 源代码
├── pom.xml                          # Maven 配置
└── README.md
```

## 🔍 验证启动

1. **查看日志**：确认没有报错
2. **访问 Agent**：浏览器打开 `http://Agent_IP:7777`
3. **检查 Sonic 前端**：在设备中心查看 Agent 是否在线

## ⚠️ 常见问题

### Q: Agent 无法连接 Server？

- 检查 `sonic.server.host` 是否正确
- 确保 Server 端口可访问
- 检查 `sonic.agent.key` 是否正确

### Q: 设备无法识别？

- Android：确保 ADB 已安装且设备已开启 USB 调试
- iOS：确保 Xcode 已安装且设备已信任电脑

### Q: Android 11+ 无线调试（WiFi ADB）连接后，一打开 Agent 就立刻 offline/掉线？

- **常见原因**：主机上同时存在多个不同版本的 ADB（例如 Android Studio 的 platform-tools 与 Agent 自带 `plugins/adb`），它们会反复触发
  `adb server is out of date... killing...`，导致 **无线调试的临时端口连接被重置**，设备立刻变 `offline`，旧端口也随即失效（这是无线调试的正常表现）。
- **解决思路**：确保 *所有* `adb` 操作（包括你手动执行的 `adb connect` 和 Agent 内部使用的 adb）使用 **同一个 adb 版本/路径**：
  - **推荐**：设置 `ANDROID_HOME` 指向你安装的最新 `platform-tools`，并保证 `PATH` 里使用的也是同一套 `adb`
  - 或者：用 Agent 自带的 `plugins/adb` 来执行 `adb connect`
  - 或者：把最新的 `adb` 替换到 `plugins/adb`，避免版本不一致

### Q: 提示 Java 版本不兼容？

- 确保使用 Java 17 或更高版本
- 运行 `java -version` 检查版本


```bash
settings put global hidden_api_policy 1
settings put global hidden_api_policy_pre_p_apps 1
settings put global hidden_api_policy_p_apps 1

cmd deviceidle whitelist +io.appium.uiautomator2.server
cmd deviceidle whitelist +io.appium.uiautomator2.server.test
cmd deviceidle whitelist +org.cloud.sonic.android

cmd appops set io.appium.uiautomator2.server RUN_ANY_IN_BACKGROUND allow
cmd appops set io.appium.uiautomator2.server.test RUN_ANY_IN_BACKGROUND allow
cmd appops set org.cloud.sonic.android RUN_IN_BACKGROUND allow
cmd appops set org.cloud.sonic.android RUN_ANY_IN_BACKGROUND allow

am force-stop io.appium.uiautomator2.server
am force-stop io.appium.uiautomator2.server.test


```