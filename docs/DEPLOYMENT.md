# Bridge 部署

## Ubuntu：HTTPS 反向代理

适用于已有稳定域名、手机可以访问的电脑或服务器。Bridge 仍监听 loopback，Caddy/Nginx 对外提供 TLS 和 WebSocket 转发。没有网络入口的内网电脑需先配置可信组网或反向入口；项目不提供公共中转。

```json
{
  "host": "127.0.0.1",
  "port": 8787,
  "publicUrl": "https://phone-bridge.example.com",
  "harnessToken": "<init 自动生成的随机值>",
  "stateFile": "bridge-state.json",
  "requestTimeoutMs": 30000,
  "allowInsecureLan": false
}
```

最小 Caddyfile（域名需指向你的机器，并允许 HTTPS 入口）：

```caddyfile
phone-bridge.example.com {
    reverse_proxy 127.0.0.1:8787
}
```

保留原始 Host；Caddy 自动支持 WebSocket 升级。不要为 `/pair`、`/device` 或 `/mcp` 添加重试规则。所有远程流量使用 HTTPS/WSS，手机通过系统信任库验证证书。

可用 systemd 以普通用户运行：

```ini
[Unit]
Description=Android Phone Bridge
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=YOUR_USER
WorkingDirectory=/home/YOUR_USER/android-phone-bridge
ExecStart=/ABSOLUTE/NODE/PATH /home/YOUR_USER/android-phone-bridge/dist/cli.js serve --config /home/YOUR_USER/android-phone-bridge/bridge.config.json
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

将 Node 路径替换为 `command -v node` 的实际输出，尤其使用 fnm/nvm 时；先以普通命令验证后再启用服务。权限与数据保留在部署者自己的机器。

## Windows 11

安装 Node.js 22 LTS 与 JDK 17；Android SDK 可通过 Android Studio 安装。PowerShell：

```powershell
npm ci
npm run build
node dist/cli.js init --config bridge.config.json
node dist/cli.js serve --config bridge.config.json
# 另一个终端
node dist/cli.js pair --config bridge.config.json
```

可在同机运行 Caddy 提供 HTTPS，再按上文设置 `publicUrl`。需要常驻时通过任务计划程序以当前用户在登录后启动 `node.exe`，参数使用 Bridge 与配置文件绝对路径，工作目录设置为项目目录，并配置失败后重试。不要将配对码或令牌放到任务名称、公开日志或截图中。Windows 防火墙仅开放需要的 HTTPS 入口。

## Bridge 自身 TLS

如果不使用反向代理，配置可加：

```json
"host": "0.0.0.0",
"publicUrl": "https://phone-bridge.example.com:8787",
"tls": { "certFile": "certs/fullchain.pem", "keyFile": "certs/privkey.pem" }
```

证书需匹配域名并被手机和 Node 信任；配置路径相对配置文件。CLI 在非 loopback 绑定时通过 `publicUrl` 访问。不要将证书私钥提交到仓库。

## 本地开发

仅在自有测试网络或模拟器使用：把 `host` 设置为 `0.0.0.0`、`publicUrl` 设置为手机可访问的 `http://电脑内网地址:8787`，并显式设置 `allowInsecureLan: true`；APK 配对时也勾选“仅本地开发：允许 HTTP 明文”。明文不提供传输保护，不用于互联网部署。

Android 官方模拟器可用 `http://10.0.2.2:8787` 访问主机，或 `adb reverse tcp:8787 tcp:8787` 后使用 `http://127.0.0.1:8787`。手机不需要日常连接无线 ADB；ADB 仅用于安装、测试或启动非 Root Shizuku。

`bridge.config.json` 含 Harness 令牌，`bridge-state.json` 含设备身份散列；备份时限制读取权限。更换 Harness 令牌后重启 Bridge。手机令牌遗失时用 `revoke` 清除配对并重新生成配对码。
