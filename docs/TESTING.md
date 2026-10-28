# 验证与设备验收

记录日期：2026-10-27～28。实现与设备验收分开记录，不能把模拟器或 CI 通过视作所有手机均已通过。

## 自动化命令

```sh
npm ci
npm run check
npm test
npm run build
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :demo:assembleDebug
```

Bridge 测试直接启动本地 HTTP/WebSocket 服务，使用官方 MCP 客户端通过 Streamable HTTP 与 stdio 调用，覆盖一次性配对、身份持久化与撤销、分离的 Harness/手机身份、Origin/Host 拒绝、参数校验、超时取消、迟到结果和断线不重放。配置测试覆盖随机身份初始化、不覆盖已有配置及远程 TLS 要求。

Android JVM 测试覆盖默认拒绝、读取/操作分权、两类暂停、状态例外、设备身份、请求去重、有效期和时钟异常。

`test:android` 还会先运行三组 `PermissionManagementTest`：批量授权提交后的完整状态与暂停保留；应用搜索、跨筛选多选、页面重建和横竖屏切换、四种批量操作及一键全开范围；从系统设置返回后的定位/通知授权状态刷新与通知类别被关闭的识别。测试需由脚本显式传入 `permissionUiTest=true`，只接受专用模拟器，并拒绝替换已配对身份。随后清除测试应用状态，再进行原有 MCP 闭环。

## 可重复的 Android 模拟器闭环

先构建主 APK、测试应用与 instrumentation APK，启动 Android 15 / API 35 默认 x86_64 模拟器：

```sh
npm run test:android -- emulator-5554
npm run test:android -- emulator-5554 --shizuku
npm run test:android:boot -- emulator-5554
```

**仅限专用模拟器。** 脚本要求显式的 `emulator-*` 序列号，会安装并清除工具桥和测试应用的数据、授予测试系统权限、临时配置无障碍、注入模拟定位并切断/恢复测试网络。结束时恢复此前的无障碍和网络设置，撤销临时 Bridge 身份并关闭服务。它不接受个人手机序列号。`--shizuku` 需要先安装并以 UID 2000 启动 Shizuku，测试会在其系统弹窗中授予工具桥权限。

脚本创建临时 Bridge，通过模拟器的 `10.0.2.2` 主动出站连接主机，不使用 ADB 转发维持日常工具链。instrumentation 作为 Harness，实际通过 `/mcp` 发起工具请求。具体断言见 `DeviceSmokeTest.kt`，包括基本工具、默认拒绝和被禁止 App、操作暂停、排队任务取消、全部暂停、页面关闭后的状态调用、重新连接和解绑。

`test:android:boot` 另行安装并清除主 APK 数据，配置临时身份和测试权限，再重启整个模拟器。`BootProvisionTest.kt` 仅负责重启前的配对；instrumentation 退出后，由主机上的官方 MCP SDK 客户端验证自动连接、无障碍、定位前台服务、后台打开测试应用、读 UI 和定位。重启后不打开主 APK，只模拟用户解锁无 PIN 的测试设备。测试要求已授予后台定位，不能据此推断有 PIN 的真机在首次解锁前可用。连接恢复以手机实际返回的 MCP 响应为准；不以重启前尚未过期的 TCP 连接判断在线，也不重试操作。

## 本次记录

| 项目 | 环境 | 结果 |
| --- | --- | --- |
| Bridge 类型检查、构建和 MCP 集成测试 | Ubuntu 24.04.5 / Node 22.23.2 | 8 项通过 |
| Android JVM 授权与请求测试 | JDK 17 / Gradle 8.11.1 | 3 组通过 |
| Android Debug 构建及 lint | compile/target API 35 | 已通过，lint 无问题 |
| 0.1.1 权限管理与系统状态 | API 35 专用模拟器 | 三组通过，覆盖搜索、多选、批量授权、全开范围、横竖屏和系统状态刷新 |
| 主 APK、测试应用、instrumentation APK | 最低 API 30 | 已生成 |
| Android 15 模拟器 MCP 完整闭环 | API 35 / 默认 x86_64 镜像 | 已通过，`OK (1 test)`，21.387 秒 |
| 可选 Shizuku 增强及完整基础流程 | Shizuku 13.6.0 / 非 Root / UID 2000 | 已通过，`OK (1 test)`，24.436 秒 |
| 实际网络中断与自动恢复 | API 35 模拟器，关闭 Wi-Fi/数据后恢复 | 已通过，无需重新打开 APK |
| 完整重启后的后台恢复 | API 35 模拟器，后台定位已授权、无 PIN | 已通过；主机独立 MCP Harness 验证连接、无障碍、定位前台服务、后台启动 App、UI 和定位 |
| Windows / Ubuntu Bridge CI | GitHub `windows-latest` / `ubuntu-latest` | 类型检查、8 项测试及构建均通过；CI 系统不等于 Windows 11 实机 |
| Android CI | GitHub Ubuntu runner / API 35 模拟器 | 构建、JVM 测试、lint、完整 MCP 闭环均通过 |
| Windows 11 本机部署 | 无本机环境 | 待设备测试 |
| 真实手机切网、厂商省电、休眠和重启 | 当前无连接的真实手机 | 待设备测试 |

已通过的完整 CI：[Demo checks #40538594832](https://github.com/hsd221/android-phone-bridge/actions/runs/40538594832)。Android SDK 安装显式指定 `platform-tools`、`platforms;android-35` 和 `build-tools;35.0.0`，避免安装已经下架的旧 `tools` 包。每次推送自动运行上述四个任务，并提供主 APK、测试应用 APK 和 lint 报告的私有构建产物。

2026-10-28 的 0.1.1 权限管理修改已在本地通过 Debug/测试 APK 构建、三组 JVM 测试、lint（无问题）、三组权限模拟器检查及原有 MCP 模拟器闭环；横屏布局调整后再次通过权限模拟器检查。此次按用户要求暂停真机联调；更细的权限粒度仅记录于 [TODO.md](../TODO.md)。

## 人工验收步骤

1. 按部署说明启动 Bridge，把 MCP 接入 Harness。生成配对码，安装 APK 并在手机完成配对。
2. 先保持默认关闭的能力和禁止的 App，确认工具返回拒绝。逐项开放定位、界面、截图、操作，并配置测试应用读取/操作权限。系统状态栏和导航栏也是可见窗口；按错误中报告的包名决定是否开放。API 35 AOSP 测试需要显式配置 `com.android.systemui` 和 `com.android.launcher3`，不同厂商的归属可能不同。
3. 授予无障碍、定位及必要的后台定位/通知权限。关闭 APK 页面，在 Harness 中读取 status、位置、界面及截图。对系统安全窗口记录截图拒绝状态。
4. 安装 `demo-debug.apk`，允许 `io.github.hsd221.phonebridge.demo` 的读取和操作。打开应用、读取节点，向“测试输入框”写文字，点击“确认测试”，再次读取并核对 `已确认：…`。再验证坐标点击、滑动和返回。
5. 在被禁止 App、键盘、通知栏、系统弹窗、分屏及覆盖层可见时分别验证读屏、截图和操作拒绝。授权应用私有数据库不属于测试范围。
6. 手机暂停操作，确认读取仍按授权可用、操作被拒绝。暂停全部，确认除不含原始内容的状态诊断外全部拒绝；长手势期间暂停，确认排队请求不执行。
7. 手机断网及 Wi-Fi/移动网络切换。未完成操作应明确失败或标记结果未知，恢复连接后无自动重放，后续 status/UI 调用恢复。记录耗时、设备型号和 Android/厂商系统版本。
8. 在线解除配对，确认连接被关闭且原令牌无法连接。离线解绑时先确认本机访问立即停止，再恢复网络检查撤销；也可在 Bridge 端运行 revoke。
9. 记录普通权限路径。另安装并以非 Root 方式启动 Shizuku，授权工具桥并开启可选增强，确认 status 报告 UID 2000、打开应用结果中的 `path: shizuku`。停止 Shizuku 后基础工具仍可使用；重启后显示未启动而非假定仍可用。
10. 分别在 Windows 11 和 Ubuntu 记录以上结果。手机休眠和重启恢复按型号、版本、电池/自启动设置及前后台定位授权记录，不承诺永久在线。

## 当前边界

- 不提供 Root、通用 Shell、历史采集、通知/媒体/文件、多设备、多 Harness 协调或具体业务按钮审查。
- UI 只反映应用通过无障碍暴露的可见内容；安全窗口、锁屏、节点失效和系统限制会明确失败。
- 截图与交互采取保守的可见窗口检查。未授权键盘或系统窗口可使整个请求拒绝；手机端可手动配置相应包名。
- Android 系统不提供“按 App 完全隔离”的无障碍事务。前台可能在检查后变化，已派发的系统动作不能保证撤回；应使用专用测试 App 验收后再开放业务应用。
- 断网解绑无法立即修改不可达 Bridge 的状态，因此保留加密撤销请求；设备侧访问立即停用，Bridge 侧可用 revoke 主动撤销。
- 真机长期后台、厂商自启动和实际网络切换的结果需要持有设备的人执行；实现与模拟器验证不能替代这些记录。
