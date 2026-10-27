# 手机协议 v1

Bridge HTTP 根地址由用户配置。手机以 `POST /pair` 交换一次性配对码，之后主动以 WebSocket 连接 `/device`。远程使用 HTTPS/WSS；标准系统证书校验必须保留，不跳过证书验证。开发明文需要 Bridge 和 APK 两端明确开启。

## 身份和配对

`POST /admin/pair` 需要 Harness Bearer 身份，产生 64 bit 随机十六进制配对码，有效期五分钟，重新生成使前码失效。Bridge 每分钟最多接受 30 次配对尝试。`POST /pair` 的 JSON：

```json
{"code":"ONE_TIME_CODE","deviceId":"a UUID","label":"phone label"}
```

成功返回 `deviceId`、`protocol: 1` 和 256 bit `token`。只配一个设备；已配对后必须先解绑。Bridge 将令牌 SHA-256 散列写入权限受限的本机状态文件，手机将身份用 Android Keystore AES-GCM 加密保存在私有存储，禁用备份。

WebSocket 请求头为 `Authorization: Bearer <phone-token>`，不将令牌放进 URL。手机先发送：

```json
{"type":"hello","protocol":1,"deviceId":"a UUID"}
```

十秒内没有 hello 会断开。Bridge 核对后发送 `{"type":"ready","protocol":1,"deviceId":"设备 UUID"}`，手机收到后才显示已连接。Bridge 和手机分别发送 WebSocket ping；无响应后重连。同一设备的新连接替换旧连接并使旧连接上的未完成请求失败。

`DELETE /device/revoke` 使用手机 Bearer；`POST /admin/revoke` 使用 Harness Bearer。成功撤销后旧身份不能再次连接，并关闭当前会话。手机离线解绑时先停止本地访问并删除活动身份，再保留加密撤销请求重试；**网络恢复前 Bridge 上的散列无法立即删除**，可在 Bridge 本地运行 `revoke` 立即撤销。待撤销期间 APK 不允许重新配对。

## 请求

```json
{
  "type": "request",
  "protocol": 1,
  "deviceId": "a UUID",
  "id": "a unique UUID",
  "issuedAt": 1793140000000,
  "expiresAt": 1793140030000,
  "tool": "phone_tap",
  "args": {"packageName":"com.example.test","x":100,"y":200}
}
```

时间为 Unix 毫秒。默认期限 30 秒，最长 60 秒；手机核对身份、协议、ID、时钟和期限，在执行前再次核对期限和手机端权限。手机对近期 ID 去重，最多八个待处理请求且串行执行。每次 UI 读取返回新快照的 `nodeId`，发生无障碍窗口事件、读取新快照、操作或重连后旧 ID 失效。

工具及完整参数以 `bridge/src/protocol.ts` 的 schema 为准：`phone_status`、`phone_location`、`phone_ui`、`phone_screenshot`、`phone_open_app`、`phone_tap`、`phone_swipe`、`phone_input`、`phone_back`。屏幕坐标是默认显示器的实际像素；截图可以缩放，MCP 同时返回图片和尺寸元信息。图片中的 `(x, y)` 应转换为 `(x × displayWidth / width, y × displayHeight / height)` 后用于坐标操作。

## 结果和取消

```json
{"type":"result","id":"request UUID","deviceId":"device UUID","result":{"ok":true,"data":{"accepted":true}}}
```

```json
{"type":"result","id":"request UUID","deviceId":"device UUID","result":{"ok":false,"error":{"code":"APP_DENIED","message":"未授权访问应用"}}}
```

Bridge 接受的结果必须来自当前会话且请求 ID 与设备匹配。错误通过 MCP `isError: true` 和结构化文本返回；截图以 MCP image 内容返回。`accepted` 只代表 Android 接受动作，不保证业务结果，Harness 应再次调用 UI 或截图观察。

取消消息为 `{"type":"cancel","id":"request UUID"}`。手机权限变化、暂停、解除配对、连接变化均取消排队任务。已经派发的系统操作不保证可撤回，结果不确定时错误携带 `outcomeUnknown: true`。Bridge 离线、断线、超时与重连均**不重放请求**；过期或迟到结果丢弃。暂停后 status 仍可返回不含界面、位置或截图的权限诊断。

主要错误：`PHONE_OFFLINE`、`BRIDGE_UNAVAILABLE`、`TIMEOUT`、`CANCELLED`、`EXPIRED`、`REPLAY_REJECTED`、`BUSY`、`PAUSED`、`OPERATIONS_PAUSED`、`CAPABILITY_DENIED`、`APP_DENIED`、`APP_CHANGED`、`STALE_NODE`、`PERMISSION_REQUIRED`、`BACKGROUND_LOCATION_REQUIRED`、`ACCESSIBILITY_UNAVAILABLE`、`DEVICE_LOCKED`、`UI_UNAVAILABLE`、`WINDOW_UNAVAILABLE`、`SCREENSHOT_UNAVAILABLE`、`LOCATION_UNAVAILABLE`。

## Harness 接口

- `POST /mcp`：官方 SDK 实现的无状态 MCP Streamable HTTP，使用独立 Harness Bearer。GET/DELETE 不提供 SSE 会话，返回 405。
- `phone-bridge mcp --config <file>`：stdio MCP，通过同一配置的受认证 HTTP 端点转发至运行中的 Bridge。
- `POST /api/call`：stdio 适配的内部入口，同样需要 Harness Bearer，参数为 `{"tool":"phone_status","args":{}}`。
- `GET /admin/status`：Harness Bearer，返回连接和配对元信息；不返回手机原始内容或令牌。
- `GET /health`：仅返回协议及服务存活状态，不包含设备信息。

非信任 Origin 和 Host 被拒绝。Bridge 不记录工具内容，不提供 Shell、历史采集、任意网络代理或应用私有数据库接口。
