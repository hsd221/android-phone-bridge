# 首版实现说明

本文件介绍当前模块职责与已经实现的架构选择，实际验证范围见 [测试记录](TESTING.md)。

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| `bridge` | 配对、身份撤销、手机会话、请求生命周期、MCP 和命令行 | Node.js 22，MCP SDK，WebSocket |
| `android` | 后台连接、手机端授权、系统工具、可选 Shizuku、设置界面 | Android 11+，Kotlin，OkHttp |
| `protocol` | 手机与 Bridge 的消息和错误约定 | JSON over WebSocket |

## 关键选择

- 手机主动建立 WebSocket。远程使用 HTTPS/WSS；HTTP/WS 只允许显式启用的本地开发。Bridge 支持自身 TLS，也支持通过反向代理终结 TLS。
- 一次性配对码短时有效；手机保存长期随机身份。Bridge 只持有手机令牌的散列。MCP 使用独立 Bearer 令牌，不能用手机身份调用 MCP。
- MCP 支持 Streamable HTTP 和命令行 stdio 适配。一个 Bridge 只配一部手机。
- Android 11（API 30）起提供无障碍截图 API，使用该系统授权路径，避免维持额外的 MediaProjection 会话。系统安全窗口截图失败会明确报告。
- 所有能力默认关闭；每个 App 默认禁止，手机用户分别允许读取和操作。截图、坐标和手势还检查当前可见窗口，存在未授权窗口时拒绝。
- 手机串行执行请求，每个请求包含设备标识、唯一 ID 和截止时间。暂停、权限变化、断线会取消尚未执行的请求；不缓存重连后的操作。
- Shizuku 只提供固定的应用启动增强（通过受限 UserService），基础无障碍工具独立可用。没有通用 Shell 入口。
- 原始界面、截图、位置和输入文字不写入 Bridge 日志或持久化状态。

## 验证原则

Bridge 使用真实 HTTP、WebSocket 和 MCP 客户端测试配对、鉴权、生命周期及异常；Android 的授权与请求检查通过 JVM 单元测试验证。APK 必须构建并通过 lint。设备和操作系统结果在 `docs/TESTING.md` 单独记录；模拟手机通过不等于真机通过。
