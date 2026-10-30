# 部署与使用指南

本文档介绍如何完整部署 Android Phone Bridge，包括 Bridge 桥接服务的启动、手机端 APK 的配置与配对，以及如何将服务接入主流 AI Agent（如 Claude Desktop 或各类自建 Harness）。

---

## 整体拓扑

系统的整体调用关系如下：

```
[ AI Agent / Harness ]
         │ (MCP 协议：Streamable HTTP 或 stdio)
         ▼
[ Bridge 桥接服务 ] (运行在电脑或服务器上，Node.js 22+)
         ▲
         │ (安全 WebSocket：HTTPS/WSS，手机主动出站)
[ Android 手机 (APK) ] (Android 11+，无障碍服务 + 前台服务保活)
```

- **Bridge**：负责管理一次性配对码、保存设备身份散列，并将手机能力包装成标准的 MCP 工具接口。
- **Android APK**：主动向 Bridge 建立 WebSocket 长连接，执行来自 Agent 的请求，并负责本地的权限过滤与随时暂停。

---

## 第一步：运行 Bridge 桥接服务

### 1. 环境准备
- **Node.js**：22.0.0 或更高版本。
- 操作系统：Ubuntu Linux（推荐 24.04+）或 Windows 11。

### 2. 编译与初始化
克隆本仓库后，在项目根目录下安装依赖并执行编译：

```sh
npm ci
npm run build
```

生成初始配置文件：

```sh
node dist/cli.js init --config bridge.config.json
```

> **说明**：`init` 命令会自动生成一个随机的高强度 Harness Bearer 令牌并写入 `bridge.config.json`。若该文件已存在，不会被覆盖。

### 3. 启动服务
在终端中启动 Bridge 服务：

```sh
node dist/cli.js serve --config bridge.config.json
```

默认情况下，服务会监听在 `127.0.0.1:8787`。请保持该进程持续运行。

### 4. 生成配对码
打开另一个终端窗口，执行以下命令为手机生成一次性配对码：

```sh
node dist/cli.js pair --config bridge.config.json
```

终端会输出一段 16 位字符（64-bit 熵）的大写十六进制配对码。
- 配对码**有效期为 5 分钟**，且**仅供单次使用**。
- 若超时或重新执行了 `pair` 命令，先前的配对码会自动失效。

你可以随时通过以下命令查看当前 Bridge 的连接状态：

```sh
node dist/cli.js status --config bridge.config.json
```

---

## 第二步：构建与配置手机端应用

### 1. 获取 APK
你可以直接从本仓库的 [GitHub Releases](https://github.com/hsd221/android-phone-bridge/releases) 下载预构建的测试安装包，或者自行编译：

**自行编译环境要求**：
- JDK 17
- Android SDK Platform 35 及 Build Tools 35.0.0（配置好环境变量 `ANDROID_HOME`）

**编译命令**：
```sh
cd android
./gradlew :app:assembleDebug :demo:assembleDebug
# Windows PowerShell 环境请使用：
# .\gradlew.bat :app:assembleDebug :demo:assembleDebug
```

编译产物位于：
- 主应用 APK：`android/app/build/outputs/apk/debug/app-debug.apk`
- 独立测试应用 APK：`android/demo/build/outputs/apk/debug/demo-debug.apk`

### 2. 安装与初次配对
1. 将 `app-debug.apk` 安装至手机（要求 Android 11 / API 30 及以上版本，无需 Root）。
2. 打开应用的“连接”页，输入 Bridge 服务的对外访问地址（例如 `https://phone-bridge.example.com` 或局域网调试地址）以及前述生成的一次性配对码，点击确认配对。
3. 配对成功后，手机端会使用系统 Keystore 中的密钥加密身份凭据后保存在本机。只要网络连通，后续均会在后台自动重连，无需重复配对。

> **注意**：请勿将 `bridge.config.json` 中的 Harness 令牌输入手机，手机端使用独立的配对流程与设备凭证。

### 3. 权限与授权配置
为了保障设备安全，所有能力开关和应用访问在首次安装时**默认处于关闭状态**，需按需开启：

- **系统核心权限**（“设置”页查看状态并打开对应系统入口）：
  - **无障碍服务**：用于读取屏幕层级、定位控件、模拟点击和手势滑动。某些定制 ROM 可能需要先在“应用详情”中开启“允许受限制的设置”。
  - **位置权限**：若需要 Agent 获取设备定位，请授予精确位置权限，并在后台定位设置中选择“始终允许”。
  - **前台通知与后台保活**：允许应用常驻前台通知。建议在系统的电池优化中将本应用设为“不设限制”或“允许自启动”，避免被系统后台策略中止。
- **按应用授权**：
  - 进入“访问”页的“应用权限”，可检索应用名或包名，批量选择多个应用并统一配置“允许读取”、“允许操作”或“全部允许”。
  - 亦可直接点击“一键全部开启”，快速对当前目录内的全部应用（包括筛选外）及四项桥接能力进行授权；不修改系统权限或暂停状态，未列出和新装应用仍默认禁止。
  - 针对输入法键盘、系统桌面、状态栏等无启动图标的系统组件，可通过“添加包名”手动输入包名纳入授权。

#### 可选：手机本地解锁

1. 在手机上进入“访问 → 本地解锁设置”，选择与系统一致的数字 PIN、文字密码或图案，点击“保存到本机”。图案通过依次点选九宫格配置，跨过的中点会自动加入。此功能不会修改 Android 的锁屏方式。
2. 开启“允许远程请求解锁”，并开启“操作应用”能力和系统无障碍服务。一键全开与“开启四项能力”不会开启解锁授权；保存或更换凭据会保留当前解锁授权状态；初始关闭时仍需手动开启。
3. Agent 可先调用 `phone_status` 查看 `screen` 状态，再以空参数 `{}` 调用 `phone_unlock`。**不要在聊天、MCP 参数或 Bridge 配置中填写锁屏凭据**。凭据在手机本地由 Android Keystore 加密保存，不参与网络协议，也不进入日志或系统备份。
4. 无密码锁屏或系统已信任的设备可不保存凭据，工具请求 Android 唤醒并消锁。配置了本地凭据时，手机直接唤醒并识别系统验证界面；没有显示验证键盘时，在确认的系统锁屏窗口内上滑一次。已有键盘或出现相交覆盖窗口时不会上滑，未识别键盘就不会输入凭据。唤醒窗口上的返回键和“取消本次解锁”可以停止本次请求。指纹、人脸、生物识别强制验证及未识别的厂商锁屏需要用户在手机上处理，不会尝试绕过。
5. 若一次本地输入未确认成功、被取消或连接中断，手机会持久阻止后续自动输入。手动解锁后检查配置，再在本地点击“检查后重新允许尝试”。不要让 Agent 自动重试。重启后的首次解锁需要手动完成。

解锁后，读屏、截图和操作仍按原有应用授权执行。锁屏界面的内容不会向远端开放；删除本地凭据或解除配对会同时关闭解锁授权。不同厂商的锁屏可能隐藏无障碍节点，此时工具会提示手动解锁。

如需 Shizuku 增强，在“设置”页启动并授权非 Root Shizuku，再开启“Shizuku 增强”。可用时，本地解锁优先使用手机内的 shell 服务；未连接或输入前连接不可用时回退无障碍。开始凭据输入后若连接断开，立即停止并保留重试阻止，不会换一条路径重复输入。此模式仍需要系统无障碍服务负责唤醒和显示验证界面。

数字 PIN 既支持已识别的确认键，也支持符合已知 MIUI 自动确认布局的键盘。后者必须核对完整数字键及空输入状态，缺少确认键本身不足以允许输入；无法确认时仍提示手动解锁。

失败时可通过 `phone_status.unlock.lastAttempt` 核对停止阶段、结果码、固定拒绝原因和是否已开始本地输入；这里只返回固定状态，不返回凭据或屏幕内容。记录在应用进程重启、关闭独立解锁授权或解除配对后清空。

### 4. 手机端控制权保护
- **即时暂停控制**：“访问”页提供两级暂停开关，手机常驻通知栏也提供了快捷控制入口，随时可点击“暂停操作”或“暂停全部访问”。暂停生效期间，来自外部 Agent 的对应请求将被立即拒绝。
- **敏感窗口防护**：主 APK 本身启用了系统安全窗口保护（`FLAG_SECURE`），远程工具无法窥探或操作自身设置界面。若屏幕呈现密码输入框、锁屏界面或未授权系统浮层，相关感知与操作请求将自动中止。

---

## 第三步：接入 AI Agent / Harness

Bridge 支持通过两种标准方式接入任意 MCP 客户端。

### 方式一：Streamable HTTP（推荐）
适用于支持 HTTP/SSE 协议的 MCP 客户端（如各类远程或本地智能体框架）：

```json
{
  "mcpServers": {
    "phone": {
      "url": "https://phone-bridge.example.com/mcp",
      "headers": {
        "Authorization": "Bearer <harnessToken>"
      }
    }
  }
}
```

> **说明**：将 `<harnessToken>` 替换为你本地 `bridge.config.json` 中的实际令牌。

### 方式二：本地 stdio 模式
适用于仅支持本地命令行子进程的客户端（例如 Claude Desktop）：

```json
{
  "mcpServers": {
    "phone": {
      "command": "node",
      "args": [
        "/absolute/path/to/android-phone-bridge/dist/cli.js",
        "mcp",
        "--config",
        "/absolute/path/to/bridge.config.json"
      ]
    }
  }
}
```

- 请确保填写的脚本与配置文件路径为绝对路径。
- 在 Windows 环境下路径应形如 `C:/Users/YourName/android-phone-bridge/dist/cli.js`。
- stdio 模式属于轻量本地代理，底层的 Bridge 服务进程本身必须已经处于运行状态。

---

## 生产环境与网络配置

若手机与 Bridge 不在同一局域网，或需长期稳定运行服务，建议使用反向代理终结 TLS。

### 1. Ubuntu 环境：Caddy 反向代理（推荐）
在具备公网 IP 或配置了动态域名的主机上，使用 Caddy 提供 HTTPS 访问。Bridge 依然监听本地回环地址（`127.0.0.1:8787`）。

**`bridge.config.json` 示例**：
```json
{
  "host": "127.0.0.1",
  "port": 8787,
  "publicUrl": "https://phone-bridge.example.com",
  "harnessToken": "<init 自动生成的随机令牌>",
  "stateFile": "bridge-state.json",
  "requestTimeoutMs": 30000,
  "allowInsecureLan": false
}
```

**最小 `Caddyfile`**：
```caddyfile
phone-bridge.example.com {
    reverse_proxy 127.0.0.1:8787
}
```
Caddy 会自动申请并续期证书，且天然支持 WebSocket 协议升级。

### 2. 使用 systemd 实现服务守护（Ubuntu）
创建服务文件 `/etc/systemd/system/phone-bridge.service`：

```ini
[Unit]
Description=Android Phone Bridge Service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=YOUR_USER
WorkingDirectory=/home/YOUR_USER/android-phone-bridge
ExecStart=/usr/bin/node /home/YOUR_USER/android-phone-bridge/dist/cli.js serve --config /home/YOUR_USER/android-phone-bridge/bridge.config.json
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

替换其中的用户名和 Node 实际绝对路径（可通过 `command -v node` 查询），保存后执行：
```sh
sudo systemctl daemon-reload
sudo systemctl enable --now phone-bridge
```

### 3. Windows 11 环境部署
在 Windows 系统中，可直接在 PowerShell 中运行。若需开机后台自启：
1. 可在 Windows 任务计划程序中添加任务，在用户登录后以后台方式执行 `node.exe dist/cli.js serve --config bridge.config.json`；
2. 配置任务失败后自动重启，工作目录设定为项目根目录；
3. 配合同机运行的 Caddy 提供 HTTPS 反代，或配合局域网穿透工具。注意 Windows 防火墙仅开放必要端口。

### 4. Bridge 原生 TLS 方案
若不希望部署外部反向代理，Bridge 也支持直接挂载证书文件：

```json
{
  "host": "0.0.0.0",
  "port": 8787,
  "publicUrl": "https://phone-bridge.example.com:8787",
  "tls": {
    "certFile": "certs/fullchain.pem",
    "keyFile": "certs/privkey.pem"
  }
}
```
证书必须匹配访问域名，且必须受 Android 系统的根证书信任库认可。切勿将证书私钥提交至公共代码仓库。

---

## 本地局域网测试模式（允许明文 HTTP）

在受信任的家庭或实验室局域网中进行本地调试时，可免去证书配置：
1. 将 `bridge.config.json` 中的 `host` 设置为 `0.0.0.0`，`publicUrl` 设置为电脑局域网 IP（例如 `http://192.168.1.100:8787`）；
2. 在配置中显式声明 `"allowInsecureLan": true`；
3. 手机端在输入配对地址时，**必须勾选“仅本地开发：允许 HTTP 明文”**选项。

> **安全警示**：明文传输不具备加密保护，切勿在公网或不受信的网络环境中使用此模式。

---

## 运维管理与凭证撤销

- **凭据文件安全**：`bridge.config.json` 包含 Harness 管理令牌，`bridge-state.json` 包含已绑定设备的散列凭据，系统默认以严格权限保存，切勿外泄。
- **正常解绑**：在手机端界面点击“解除配对”即可，手机将切断连接并通知 Bridge 注销设备身份。
- **紧急撤销**：若手机设备离线或丢失，可直接在 Bridge 端执行撤销命令，彻底使当前设备凭证作废：
  ```sh
  node dist/cli.js revoke --config bridge.config.json
  ```
  执行后旧设备将无法再次接入，后续连接需重新生成配对码。
