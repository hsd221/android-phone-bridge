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

### 2. 扫码输入格式

CLI 的二维码承载 UTF-8 JSON，与 WebSocket 协议版本独立：

```json
{
  "type": "phone-bridge-pair",
  "version": 1,
  "url": "https://phone-bridge.example.com",
  "code": "0123456789ABCDEF",
  "expiresAt": 1794430000000
}
```

`expiresAt` 是毫秒级 Unix 时间戳；示例仅说明结构，实际内容须由正在运行的 Bridge 生成。二维码最多 4096 个字符，只接受这些字段与对应类型。`url` 必须是无凭据、路径、查询参数或 fragment 的 HTTP(S) origin，包含手机可访问的域名或 IP；`code` 是 16 位大写十六进制配对码。

手机先检查类型、版本、地址、过期时间和配对码格式，再让用户核对并填入表单。扫码不触发网络请求，不携带 Harness 令牌或长期设备令牌，不自动开启 HTTP、工具或应用权限。最终 `/pair` 交换由用户明确点击，配对码的过期、续期和一次性使用仍以服务端为准。

### 3. 交换设备身份（手机端）
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

协议目前支持 19 项核心工具（具体参数详见 `bridge/src/protocol.ts`）：

- `phone_status`：查询设备连接状态、Android 系统版本、两级暂停标志、逐工具权限、按应用授权清单及 Shizuku 运行状态。`tools` 是以 `phone_*` 为键的逐项授权结果；`capabilities` 作为旧客户端兼容字段保留。`appVersion` 与 `appVersionCode` 标识手机 APK 版本，便于确认升级；`screen` 包含亮屏、键盘锁、设备锁与首解状态；`unlock` 包含独立授权、是否配置本地凭据、是否阻止重试及固定的最近尝试状态，不包含凭据、节点或屏幕内容。
- `phone_device_info`：返回型号、Android 版本、安全补丁、电量、充电状态、屏幕尺寸、密度、语言与时区。
- `phone_apps`：列出可启动应用及版本；`includeSystem` 为 `true` 时包含系统应用。仅返回系统可见的启动入口，不读取应用私有数据。
- `phone_notifications`：读取活动通知标题和正文摘要。必须启用通知访问服务，并且通知所属应用已开放读取权限。
- `phone_clipboard`：空参数 `{}`，要求设备未锁屏并开放该工具。用户启用并授权非 Root Shizuku 时，优先使用 shell UID 2000 的受限服务读取本用户剪贴板，否则尝试原生 Android API。读到剪贴板时返回 `available: true`、`hasText`、`text`、`label`、`truncated`、`path`（`android` 或 `shizuku`）及 `capturedAt`。只取首项已有文本，最多 10000 个 UTF-16 字符，标签最多 200 个字符；非文本内容返回 `hasText: false, text: null`，不读取 URI 或 Intent 所指向的内容。两条路径均返回空值时报 `CLIPBOARD_UNAVAILABLE`，不能将 Android 的空返回解释为“剪贴板没有内容”，因为后台拒绝读取也返回空值。
- `phone_set_clipboard`：替换文本剪贴板，受独立工具和操作授权控制；Android 可能显示正常的剪贴板隐私提示。
- `phone_media`：按 `images`、`videos` 或 `all` 列出共享媒体元数据；必须授予对应 Android 媒体读取权限，不返回媒体内容。
- `phone_media_read`：参数 `{uri, maxBytes?}`。只接受 `phone_media` 所用的规范条目 URI：`content://media/external/images/media/<id>` 或 `content://media/external/video/media/<id>`；`id` 为无前导零的正 64 位有符号整数，不接受其他 provider、卷名、查询参数、片段或转义路径。需要独立工具授权及对应 Android 图片或视频权限；旧媒体列表或能力组授权不能自动开启内容读取。`maxBytes` 为 1–4194304 的整数，默认 1048576。先按元数据拒绝超限，再在流读取时限制实际字节数；超限报错，不返回截断内容。MIME 必须是与集合相符的 `image/*` 或 `video/*`。成功返回 `{uri, mimeType, name, sizeBytes, base64, capturedAt}`，`sizeBytes` 是实际读取长度，`name` 最多 200 字符或为 `null`。MCP 把 PNG、JPEG、WebP、GIF 转为图像内容，其他图片及视频转为含 `uri`、`mimeType`、`blob` 的嵌入资源，并附不含 Base64 的文本元数据。此工具属于读取请求，暂停操作不阻止读取；暂停全部、撤销授权或取消请求会停止返回内容。
- `phone_unlock`：空参数 `{}`，唤醒并使用手机本地配置请求解锁。需要 `phone_unlock` 工具授权及独立解锁授权，受两级暂停控制。开启全部工具不会开启此授权。PIN、密码与图案不属于协议字段，任何额外参数都会被拒绝。成功返回 `unlocked: true`、`path` 与屏幕状态；`path` 区分 `already_unlocked`、`system`、`local_credential` 和 `shizuku`。Shizuku 未连接或输入前不可用时可回退无障碍，已开始凭据输入则不切换路径重试；需要人工或系统不支持时明确返回错误，不能盲目重试。
- `phone_lock`：仅接受空参数 `{}`，通过无障碍 `GLOBAL_ACTION_LOCK_SCREEN` 请求锁屏。工具开关默认关闭，不继承旧操作能力组的授权；受两级暂停控制，不依赖解锁授权或本地凭据。未锁屏时校验实际前台及所有可见窗口的操作授权，桥接设置仍不可远程操作。系统不提供该动作返回 `ACTION_UNAVAILABLE`，系统拒绝派发返回 `ACTION_REJECTED`，无障碍未开启返回 `ACCESSIBILITY_UNAVAILABLE`。正常返回 `accepted: true, alreadyLocked: false, path: "accessibility"`，只确认请求已接受，须另用 `phone_status.screen` 观察实际锁定与亮屏状态；已处于键盘锁时返回 `accepted: true, alreadyLocked: true, path: "none"`，仍检查工具授权和暂停状态，不唤醒或再次派发。它属于操作类请求，断线或结果不明时不得重放。
- `phone_location`：获取设备当前定位（经纬度、精度及时间戳）。
- `phone_ui`：获取当前可见窗口的无障碍节点树与文本内容。不可见父容器不会截断其可见后代；返回节点包含 `clickable` 和 `longClickable`，隐藏节点不进入结果。遍历仍受节点与访问数量上限约束，并校验窗口和可见控件的所属应用授权。
- `phone_screenshot`：获取屏幕截屏（返回图片 Base64 与物理分辨率元数据）。
- `phone_open_app`：启动指定包名的应用。
- `phone_tap`：指定最新 UI 的 `nodeId` 或屏幕物理坐标 `x`、`y`（两者互斥），可选 `durationMs` 为 50–2000 毫秒，默认 50。坐标模式按持续时间派发手势，ADB 路径用同点滑动实现长按；节点模式在持续时间至少 500 毫秒时请求 `ACTION_LONG_CLICK`，其余请求 `ACTION_CLICK`。节点动作由应用处理，不保证精确持续时间；不支持对应动作的节点返回 `ACTION_UNAVAILABLE`。
- `phone_swipe`：指定起始与终点坐标模拟滑动。
- `phone_input`：向当前聚焦的文本框输入字符。
- `phone_back`：触发系统物理返回键。
- `phone_key`：`key` 仅允许 `HOME`、`BACK`、`ENTER`、`TAB`；可选 `packageName` 用于断言预期前台应用。省略包名时按实际前台执行，适合已授权的系统弹窗，不受原应用包名变化影响。所有可见窗口仍须获得操作授权，锁屏和桥接设置仍不可操作，并且必须启用无障碍服务以核验窗口。Shizuku 可用时发送固定按键；无障碍回退使用全局 Home/Back、非密码可编辑焦点的 IME Enter、或向前移动输入焦点，缺少适用焦点时明确拒绝。ENTER 可能提交表单；调用后重新观察 UI。

坐标点击与长按、滑动、无节点文本输入、返回和通用按键会先使用受限的 ADB 等效输入通道（非 Root Shizuku shell UID 2000）；通道在动作开始前不可用时才回退到无障碍。节点操作、无法安全编码的多行输入和已开始但结果不明的动作不会换路重放。`phone_status.permissions` 与设置页显示 `adbInput`、`notificationAccess`、`mediaImages` 和 `mediaVideos` 的实际状态。

`phone_status` 的响应还包含 `bridge: {version, protocol, supportedTools}`，分别标识运行中服务端的版本、协议和工具名。注册不代表手机已支持或授权工具，仍须结合 APK 版本及手机 `tools` 检查。手机离线时，状态查询仍成功返回 `connected: false` 与 `bridge`，省略不可取得的手机状态字段。

`unlock.lastAttempt` 为 `null` 或包含以下六个固定状态字段的对象：`stage`（`checking`、`waking`、`waiting_for_bouncer`、`waiting_for_keyguard`、`revealing`、`preflight`、`entering_credential`、`verifying`）、`outcome`（`RUNNING`、`SUCCESS` 或解锁/权限/取消错误码）、`systemDismiss`（`not_requested`、`wake_only`、`system`）、`revealAttempted`、`credentialAttempted` 与 `reason`。`reason` 为 `none`、`unrecognized`、`container_unavailable`、`entry_unavailable`、`entry_not_protected`、`entry_not_editable`、`entry_not_empty`、`entry_empty_state_unknown`、`digit_control_unavailable`、`confirm_control_unavailable`、`auto_confirm_unverified`、`password_action_unavailable` 、`pattern_bounds_invalid`、`back_control_missing`、`back_control_hidden`、`back_control_disabled`、`back_control_ambiguous` 或 `back_control_not_actionable`。它只存在手机进程内存中，关闭解锁授权或解除配对时清空；不包含错误正文、节点内容、输入长度与凭据。

> **坐标换算说明**：  
> 触摸与手势操作均基于手机主屏幕的物理像素坐标。调用 `phone_screenshot` 时，为了节省带宽，返回的截图可能经过等比缩放。MCP 会在返回图像的同时附带 `displayWidth`、`displayHeight` 以及图片的实际 `width`、`height`。  
> 响应还提供 `scaleX = width / displayWidth`、`scaleY = height / displayHeight`，按最终图片尺寸计算，包含缩放后的整数取整结果。
>
> 换算公式为：  
> `实际 X = round(x / scaleX)`
>
> `实际 Y = round(y / scaleY)`
>
> 点击坐标必须位于屏幕及目标窗口范围内；像素索引从 0 开始。

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
| `TOOL_NOT_SUPPORTED_BY_BRIDGE` | 运行中的 Bridge 未注册此工具，需更新构建并重启常驻服务。兼容识别旧 Bridge 的 HTTP 400 `Unknown tool`。 |
| `BRIDGE_AUTH_FAILED` | Bridge 拒绝 Harness 凭证（HTTP 401）。 |
| `BRIDGE_ACCESS_DENIED` | Bridge 拒绝 Host、Origin 或访问策略（HTTP 403）。 |
| `BRIDGE_ENDPOINT_NOT_FOUND` | Bridge API 路径不存在（HTTP 404），应检查地址与服务版本。 |
| `BRIDGE_PROTOCOL_ERROR` | Bridge 的成功响应不符合工具结果格式。若动作结果未知，不可自动重试。 |
| `INVALID_ARGUMENT` | 参数格式、范围或组合无效，派发前已拒绝。 |
| `ACTION_UNAVAILABLE` | 当前节点或焦点不支持所请求的动作。 |
| `TIMEOUT` | 请求在指定超时时间内未收到响应。 |
| `CANCELLED` | 请求在排队期间被显式取消或被用户事件打断。 |
| `EXPIRED` | 请求到达手机端时已超出过期时间戳。 |
| `REPLAY_REJECTED` | 检测到重复的请求 ID，拒绝执行。 |
| `BUSY` | 手机端请求队列已满（超出 8 个）。 |
| `PAUSED` | 用户在手机端开启了“暂停全部访问”。 |
| `OPERATIONS_PAUSED` | 用户在手机端开启了“暂停操作”（读取类工具仍可能可用）。 |
| `CAPABILITY_DENIED` | 请求对应的工具权限（如 `phone_location`、`phone_screenshot`）未开启。 |
| `APP_DENIED` | 目标应用包名未加入用户的读取或操作白名单。 |
| `APP_CHANGED` | 预期操作的前台应用在动作触发瞬间发生了切换。 |
| `STALE_NODE` | 目标 `nodeId` 已失效，UI 树已发生变化。 |
| `PERMISSION_REQUIRED` | 缺少对应的 Android 系统权限（如普通定位权限未授予）。 |
| `BACKGROUND_LOCATION_REQUIRED`| 缺少系统“始终允许”后台定位权限。 |
| `CLIPBOARD_UNAVAILABLE` | 剪贴板为空或系统拒绝访问，无法确认内容；后台读取可在手机上启用并授权 Shizuku。 |
| `MEDIA_PERMISSION_REQUIRED` | 缺少或已撤销对应 Android 图片或视频读取权限。 |
| `MEDIA_NOT_FOUND` | 规范 URI 所指的媒体条目不存在或已删除。 |
| `MEDIA_TYPE_UNSUPPORTED` | 媒体 MIME 无效，或与图片、视频集合不符。 |
| `MEDIA_TOO_LARGE` | 声明大小或实际内容超过本次 `maxBytes` 限制，不返回部分内容。 |
| `MEDIA_UNAVAILABLE` | 媒体 provider 或内容暂不可读取。 |
| `ACCESSIBILITY_UNAVAILABLE` | 手机无障碍服务未启用或被系统强行终止。 |
| `DEVICE_LOCKED` | 设备处于锁屏状态，常规界面工具拒绝访问。 |
| `USER_UNLOCK_REQUIRED` | 需用户完成解锁，例如重启首解、生物识别、尚未配置本地凭据或锁屏已有输入。 |
| `LOCAL_CREDENTIAL_UNAVAILABLE` | 本地凭据无法解密或格式失效，需在手机重新配置。 |
| `UNLOCK_UNSUPPORTED` | 系统锁屏方式、节点或窗口状态无法安全识别，需手动解锁。 |
| `UNLOCK_UI_UNAVAILABLE` | 手机已唤醒并尝试显示验证界面，但系统解锁键盘仍不可识别，尚未输入凭据。 |
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
   - 返回当前设备的在线状态、已配对设备元信息及 `bridge: {version, protocol, supportedTools}`，不泄露设备令牌。
5. **`GET /health`**：
   - 基础存活探针，返回 `{ok, protocol, version}`。

> **安全过滤**：Bridge 会严格校验 HTTP 请求中的 `Host` 与 `Origin`，直接拒绝非预期的跨站请求或未信任域名调用。
