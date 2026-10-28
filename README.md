# Android Phone Bridge · 首版 Demo

用原生 APK 将安卓手机的按次定位、可见界面、截图和基本操作连接到你自己的 Harness。手机主动连接自部署 Bridge，Bridge 提供 MCP。项目工作名仍未确定，当前仓库私有，许可与公开发布另行决定。

范围见 [DEMO-SPEC.md](DEMO-SPEC.md)，实现选择见 [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md)，实际验证状态见 [docs/TESTING.md](docs/TESTING.md)。

## 运行 Bridge

需要 Node.js 22+。Ubuntu 和 Windows 使用同一命令，Windows 可在 PowerShell 中执行。

```sh
npm ci
npm run build
node dist/cli.js init --config bridge.config.json
node dist/cli.js serve --config bridge.config.json
```

`init` 生成独立的随机 Harness 令牌，不覆盖现有文件。保持此窗口运行，在另一个终端生成配对码：

```sh
node dist/cli.js pair --config bridge.config.json
node dist/cli.js status --config bridge.config.json
```

默认只监听 `127.0.0.1:8787`，此地址不能直接用于另一台手机。配对前按 [部署说明](docs/DEPLOYMENT.md) 设置稳定且手机可达的 HTTPS 地址、配置 `publicUrl` 并重启 Bridge。手机填写 HTTPS 根地址和配对码；不要把 Harness 令牌输入手机。配对码有效五分钟且只能使用一次。

## 构建与配置手机

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0，设置 `ANDROID_HOME`。首次构建会下载固定版本 Gradle 和依赖。

```sh
cd android
./gradlew :app:assembleDebug :demo:assembleDebug
# Windows PowerShell：.\gradlew.bat :app:assembleDebug :demo:assembleDebug
```

- 主 APK：`android/app/build/outputs/apk/debug/app-debug.apk`
- 独立测试应用：`android/demo/build/outputs/apk/debug/demo-debug.apk`
- 最低 Android 11（API 30），无需 Root。

安装主 APK，完成配对；在手机中开放所需能力、授予系统定位和无障碍权限，再给具体应用配置读取/操作权限。所有能力和 App 默认禁止。Android 安装来源可能要求在应用详情中先允许“受限制的设置”，再开启无障碍。

在“按应用授权 → 搜索与批量管理应用权限”中，按应用名或包名搜索，勾选多个应用后点“批量设置”，可允许读取、允许操作、全部允许或全部禁止。单独允许读取或操作会保留另一项原有设置。搜索时保留已选项，“全选搜索结果”只增加当前结果，“清空选择”清除全部选择。每行“配置”仍可单独调整；“添加包名”可配置没有启动入口或系统无法列出的键盘、系统窗口等包名。

“一键全部开启”会一次保存**列表内全部应用**的读取/操作与四项桥接能力，不受搜索条件影响；它不改变暂停状态，也不自动授权将来安装或未列出的应用。“能力授权”另有四项能力全开/全关。系统无障碍、精确/大概定位、后台定位、定位开关、通知（含连接通知类别）、电池优化、后台限制和 Shizuku 的当前状态会显示在各自设置入口旁，在页面可见时及从设置返回后自动刷新。厂商自启动没有统一查询接口，会标明“需手动确认”。Android 系统权限需经系统授权流程开启。

截图使用系统无障碍截图授权，不额外申请屏幕录制会话。安全窗口、锁屏、应用限制、未授权的键盘/系统弹窗/分屏/覆盖层会导致拒绝。主 APK 的设置始终禁止被远程读取或操作。

首次授予权限后启动连接，再关闭 APK 页面。前台通知显示连接和暂停入口。按厂商要求允许后台、自启动并关闭省电限制。后台定位建议允许“始终允许”；非 Root Shizuku 重启后需重新启动。系统休眠、强停和后台限制仍可能中断服务。

## 接入 Harness

Streamable HTTP 配置（令牌来自本地 `bridge.config.json`，不提交到仓库）：

```json
{
  "mcpServers": {
    "phone": {
      "url": "https://phone-bridge.example.com/mcp",
      "headers": { "Authorization": "Bearer <harnessToken>" }
    }
  }
}
```

只支持 stdio 的 Harness 在 Bridge 电脑上使用：

```json
{
  "mcpServers": {
    "phone": {
      "command": "node",
      "args": ["/absolute/path/android-phone-bridge/dist/cli.js", "mcp", "--config", "/absolute/path/bridge.config.json"]
    }
  }
}
```

Windows 将绝对路径替换为例如 `C:/Users/you/android-phone-bridge/dist/cli.js`。本地 Bridge 必须已启动；配置文件和状态文件相对路径基于配置文件所在目录。

提供九个工具：状态、定位、读界面、截图、打开 App、点击、滑动、输入和返回。先读 UI 再选择控件；操作后再读 UI 判断结果。输入工具设置文字，不自动提交。断线和超时不重放操作，`outcomeUnknown` 表示结果不确定，Harness 不能直接重试提交。

## 验证与解绑

```sh
npm run check
npm test
npm run build
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :demo:assembleDebug
```

在手机中可暂停操作、暂停全部或解除配对。离线解绑会立即停用本机访问，恢复网络后撤销 Bridge 旧身份；要立刻从 Bridge 端撤销：

```sh
node dist/cli.js revoke --config bridge.config.json
```

完整验收步骤、设备记录和已知边界见 [docs/TESTING.md](docs/TESTING.md)。协议与错误状态见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。
