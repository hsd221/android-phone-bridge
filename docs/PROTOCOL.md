# 手机桥接协议规范 (Protocol v1)

本文档记录 Bridge 服务与 Android 手机客户端之间的底层通信协议，包括配对握手、WebSocket 会话管理、消息格式以及 MCP 工具映射。

---

## 一、连接基线

1. **传输安全**：
   - 生产环境中，所有通信必须通过 TLS 加密（HTTPS 与 WSS）。
   - 手机端强制执行系统默认证书链校验，不接受任意自签名或不安全证书。
   - 仅在本地开发调试模式下，且 Bridge 与 APK 两端均显式开启明文开关时，才允许使用明文 HTTP/WS。
2. **连接方向**：
   - 手机端始终作为客户端，主动向 Bridge 提供的 `/device` 路径建立出站 WebSocket 长连接。

---

## 二、身份认证与设备配对

Bridge 采用“一次性临时配对码 ➔ 换取长期设备令牌 ➔ 散列化保存”的流程。系统目前只支持一对一绑定，绑定新手机前必须先解除旧设备。

### 1. 生成配对码（Bridge 端）
管理端或 CLI 调用 Bridge 生成配对码：
- **接口**：`POST /admin/pair`
- **鉴权**：Header 携带 `Authorization: Bearer <harnessToken>`
- **规则**：
  - 生成一个 16 位字符（64-bit 熵）的大写十六进制字符串，有效期为 5 分钟。
  - 每次重新生成配对码，之前的配对码立即失效。

### 2. 交换设备身份（手机端）
手机端连通网络后，向 Bridge 发起配对：
- **接口**：`POST /pair`
- **频率限制**：Bridge 实例每分钟全局最多接受 30 次配对交换尝试。
- **请求负载 (JSON)**：
  ```json
  {
    "code": "ONE_TIME_CODE",
    "deviceId": "00000000-0000-0000-0000-000000000000",
    "label": "My Pixel Phone"
  }
  ```
- **成功响应**：
  ```json
  {
    "protocol": 1,
    "deviceId": "00000000-0000-0000-0000-000000000000",
    "token": "256-bit-random-secret-token"
  }
  ```
- **凭证保存方式**：
  - **Bridge 端**：计算设备令牌的 SHA-256 哈希值，写入受权限保护的本地文件（`bridge-state.json`）。Bridge 本身绝不落盘明文令牌。
  - **手机端**：将返回的长期令牌通过 Android Keystore（AES-GCM）加密保存在私有目录，且禁止系统云备份（`allowBackup="false"`）。

---

## 三、WebSocket 会话管理

配对成功后，手机端向 Bridge 的 `/device` 路径建立长连接。

### 1. 握手流程
建立 WebSocket 时，手机端需在 HTTP 请求头中附加设备令牌（严禁将令牌作为 URL 参数传递）：
```http
Authorization: Bearer <phone-token>
```

连接建立后的交互：
1. **手机端发送 Hello**（连接后 10 秒内必须发送，超时会被切断连接）：
   ```json
   {
     "type": "hello",
     "protocol": 1,
     "deviceId": "00000000-0000-0000-0000-000000000000"
   }
   ```
2. **Bridge 验证并回复 Ready**：
   ```json
   {
     "type": "ready",
     "protocol": 1,
     "deviceId": "00000000-0000-0000-0000-000000000000"
   }
   ```
   收到 `ready` 消息后，手机端界面才会更新为“已连接”。

### 2. 心跳与重连
- 双方按周期发送 WebSocket Ping/Pong；若连续超时未收到响应则切断连接。
- 当同一设备重新发起连接时，Bridge 会主动切断旧连接，并将原连接上尚未处理完毕的请求标记为失败。

### 3. 解除配对与凭证撤销
- **在线撤销**：
  - 手机端发起：`DELETE /device/revoke`（使用手机 Bearer 令牌）。
  - 管理端发起：`POST /admin/revoke`（使用 Harness Bearer 令牌）。
  - 撤销成功后，Bridge 立即删除哈希记录并切断当前长连接。
- **离线解绑处理**：
  - 若手机在无网状态下被用户手动解绑，APK 会立即销毁本地加密身份，拒绝一切外部调用。
  - 同时手机端会把待撤销凭证加密暂存，在网络恢复后自动通知 Bridge。
  - 在 Bridge 侧，管理员也可以随时执行 `node dist/cli.js revoke` 直接单方面撤销旧设备。

---

## 四、请求调度与执行规范

Bridge 将来自 MCP 的工具调用封装为 JSON 请求包发送给手机端。

### 1. 请求数据结构
```json
{
  "type": "request",
  "protocol": 1,
  "deviceId": "00000000-0000-0000-0000-000000000000",
  "id": "e2a1b3c4-d5e6-7890-abcd-ef0123456789",
  "issuedAt": 1793140000000,
  "expiresAt": 1793140030000,
  "tool": "phone_tap",
  "args": {
    "packageName": "com.example.app",
    "x": 360,
    "y": 720
  }
}
```

- **时间戳与超时**：使用毫秒级 Unix 时间戳。默认超时为 30 秒（最长不超过 60 秒）。
- **调度语义**：
  - **严格串行**：手机端最多排队 8 个请求，所有动作按顺序串行执行。
  - **去重与校验**：手机端维护近期请求 ID 列表防重放；在真正执行前，会再次核验权限、暂停状态以及请求是否已超时（`expiresAt`）。
  - **UI 快照有效性**：`phone_ui` 返回的节点编号（`nodeId`）仅对当前快照有效。一旦发生界面跳变、手势操作或网络重连，旧 `nodeId` 立即失效。

### 2. 工具列表与坐标体系

协议目前支持 10 项核心工具（具体参数详见 `bridge/src/protocol.ts`）：
- `phone_status`：查询设备连接状态、Android 系统版本、两级暂停标志、能力开关、按应用授权清单及 Shizuku 运行状态。`screen` 包含亮屏、键盘锁、设备锁与首解状态；`unlock` 仅包含独立授权、是否配置本地凭据与是否阻止重试，不包含凭据或图案。
- `phone_unlock`：空参数 `{}`，唤醒并使用手机本地配置请求解锁。需要操作能力及独立解锁授权，受两级暂停控制。一键全开不会开启此授权。PIN、密码与图案不属于协议字段，任何额外参数都会被拒绝。成功返回 `unlocked: true` 与屏幕状态；需要人工或系统不支持时明确返回错误，不能盲目重试。
- `phone_location`：获取设备当前定位（经纬度、精度及时间戳）。
- `phone_ui`：获取当前可见窗口的无障碍节点树与文本内容。
- `phone_screenshot`：获取屏幕截屏（返回图片 Base64 与物理分辨率元数据）。
- `phone_open_app`：启动指定包名的应用。
- `phone_tap`：根据屏幕物理坐标模拟轻触。
- `phone_swipe`：指定起始与终点坐标模拟滑动。
- `phone_input`：向当前聚焦的文本框输入字符。
- `phone_back`：触发系统物理返回键。

> **坐标换算说明**：  
> 触摸与手势操作均基于手机主屏幕的物理像素坐标。调用 `phone_screenshot` 时，为了节省带宽，返回的截图可能经过等比缩放。MCP 会在返回图像的同时附带 `displayWidth`、`displayHeight` 以及图片的实际 `width`、`height`。  
> 换算公式为：  
> `实际 X = x * displayWidth / width`  
> `实际 Y = y * displayHeight / height`

---

## 五、响应、取消与错误体系

### 1. 结果消息格式
**成功响应**：
```json
{
  "type": "result",
  "id": "e2a1b3c4-d5e6-7890-abcd-ef0123456789",
  "deviceId": "00000000-0000-0000-0000-000000000000",
  "result": {
    "ok": true,
    "data": {
      "accepted": true
    }
  }
}
```
> **注意**：`accepted: true` 仅代表系统无障碍服务已成功派发该手势指令，并不保证界面业务逻辑产生预期变化。Agent 应在操作后主动调用 `phone_ui` 或 `phone_screenshot` 进行确认。

**失败响应**：
```json
{
  "type": "result",
  "id": "e2a1b3c4-d5e6-7890-abcd-ef0123456789",
  "deviceId": "00000000-0000-0000-0000-000000000000",
  "result": {
    "ok": false,
    "error": {
      "code": "APP_DENIED",
      "message": "未授权访问目标应用：com.example.app"
    }
  }
}
```

### 2. 取消与防重放保证
Bridge 可主动发送取消消息：
```json
{
  "type": "cancel",
  "id": "e2a1b3c4-d5e6-7890-abcd-ef0123456789"
}
```
- 当用户在手机端点击暂停、修改权限、解除配对或网络中断时，手机端会立即丢弃队列中尚未执行的所有排队请求。
- **不可撤销操作的保护**：若手势指令已经提交给 Android 底层，其结果无法保证撤回。在网络中断导致确认丢失时，错误信息会标记 `"outcomeUnknown": true`。
- **严禁重放**：无论是 Bridge 还是手机端，**在网络重连后绝不会重新发送或执行先前的任何变动指令**。

### 3. 标准错误码速查表

| 错误码 | 说明 |
| :--- | :--- |
| `PHONE_OFFLINE` | 手机当前未连接到 Bridge。 |
| `BRIDGE_UNAVAILABLE` | Bridge 内部服务异常或请求转发失败。 |
| `TIMEOUT` | 请求在指定超时时间内未收到响应。 |
| `CANCELLED` | 请求在排队期间被显式取消或被用户事件打断。 |
| `EXPIRED` | 请求到达手机端时已超出过期时间戳。 |
| `REPLAY_REJECTED` | 检测到重复的请求 ID，拒绝执行。 |
| `BUSY` | 手机端请求队列已满（超出 8 个）。 |
| `PAUSED` | 用户在手机端开启了“暂停全部访问”。 |
| `OPERATIONS_PAUSED` | 用户在手机端开启了“暂停操作”（读取类能力仍可能可用）。 |
| `CAPABILITY_DENIED` | 请求对应的能力开关（如定位、截屏）未开启。 |
| `APP_DENIED` | 目标应用包名未加入用户的读取或操作白名单。 |
| `APP_CHANGED` | 预期操作的前台应用在动作触发瞬间发生了切换。 |
| `STALE_NODE` | 目标 `nodeId` 已失效，UI 树已发生变化。 |
| `PERMISSION_REQUIRED` | 缺少对应的 Android 系统权限（如普通定位权限未授予）。 |
| `BACKGROUND_LOCATION_REQUIRED`| 缺少系统“始终允许”后台定位权限。 |
| `ACCESSIBILITY_UNAVAILABLE` | 手机无障碍服务未启用或被系统强行终止。 |
| `DEVICE_LOCKED` | 设备处于锁屏状态，常规界面工具拒绝访问。 |
| `USER_UNLOCK_REQUIRED` | 需用户完成解锁，例如重启首解、生物识别、尚未配置本地凭据或锁屏已有输入。 |
| `LOCAL_CREDENTIAL_UNAVAILABLE` | 本地凭据无法解密或格式失效，需在手机重新配置。 |
| `UNLOCK_UNSUPPORTED` | 系统锁屏方式、节点或窗口状态无法安全识别，需手动解锁。 |
| `UNLOCK_INTERRUPTED` | 本地输入被系统拒绝或锁屏发生变化，已停止输入。 |
| `UNLOCK_FAILED` | 一次本地输入后未确认成功，已阻止后续自动输入。 |
| `UNLOCK_RETRY_BLOCKED` | 上次输入未确认成功，需用户在手机本地检查并重新允许尝试。 |
| `UI_UNAVAILABLE` | 无法从无障碍服务获取当前界面的节点信息。 |
| `WINDOW_UNAVAILABLE` | 检测到安全窗口、密码键盘或未授权的系统覆盖层。 |
| `SCREENSHOT_UNAVAILABLE` | 系统拒绝了截图请求（如处于受安全保护的界面）。 |
| `LOCATION_UNAVAILABLE` | 定位服务不可用或长时间无法获取位置。 |

---

## 六、Harness / Agent 接口

Bridge 对外提供以下 HTTP / stdio 接口供外部 Agent 接入：

1. **`POST /mcp`**：
   - 基于官方 MCP SDK 实现的 Streamable HTTP 接口；
   - Header 需提供 `Authorization: Bearer <harnessToken>`；
   - 不支持无状态长轮询 SSE 会话，GET/DELETE 请求将返回 405 Method Not Allowed。
2. **`phone-bridge mcp --config <file>`**：
   - 本地 stdio 适配器入口，供仅支持标准输入输出子进程的 MCP 客户端调用；
   - 内部通过 HTTP 本地回环将命令转发给正在运行的 Bridge 主进程。
3. **`POST /api/call`**：
   - 用于调试的直接调用入口，同样需要 Harness 鉴权，请求体为 `{"tool":"phone_status","args":{}}`。
4. **`GET /admin/status`**：
   - 返回当前设备的在线状态与已配对设备元信息（设备 ID 与标签，不泄露设备令牌与私有数据；协议版本请通过 `GET /health` 获取）。
5. **`GET /health`**：
   - 基础存活探针，返回服务就绪状态。

> **安全过滤**：Bridge 会严格校验 HTTP 请求中的 `Host` 与 `Origin`，直接拒绝非预期的跨站请求或未信任域名调用。
