# Android 界面与组件

手机端使用原生 Kotlin Views，支持 Android 11+。界面分为“连接”“访问”“设置”，应用权限管理是独立页面。

- **连接**：输入 Bridge 根地址与一次性配对码，查看实际连接状态、重连或解绑。HTTP 需要主动开启本地开发选项。
- **访问**：管理两级暂停、应用读取/操作范围和四项能力。开启能力不等于系统已授权，连接成功也不代表远程请求一定获准。
- **设置**：查看 Android 实际授权、后台限制与可选 Shizuku 状态。厂商自启动无法统一查询，始终提示人工确认。
- **应用权限**：搜索、跨筛选多选、批量授权和手动包名。一键开启覆盖目录内全部应用及已配置包名，不受搜索结果限制；保留暂停状态，不修改系统授权，未列出与新装应用仍默认禁止。

## 组件接口

实现位于 `android/app/src/main/java/io/github/hsd221/phonebridge/ui/`，不依赖 Activity 的业务状态，也不依赖 Bridge 服务：

| 组件 | 接口与职责 |
| --- | --- |
| `BridgeUi` | 根据当前资源主题提供颜色、文字、分组卡片、布局、按钮、弹窗与窗口边距 |
| `Field` | 带持续标签的 `EditText` 和内联错误；通过 `input` 访问输入，通过 `error(String?)` 更新校验 |
| `ToggleRow` | 标题、说明、原生 `Switch`；`bind(Boolean)` 仅同步状态，不触发业务回调 |
| `ActionRow` | 标题、实时说明、完整触控区域和导航提示；`bindDetail(String, Int?)` 同步可见说明与无障碍描述 |
| `WidthBoundFrame` | 保持手机、横屏、多窗口及大屏内容宽度，并尊重父布局和屏幕缺口 |
| `BridgeIconDrawable` | 共用 24 单位网格的矢量图标，按主题着色 |

```kotlin
val ui = BridgeUi(activity)
val content = ui.column()
val section = ui.section(content, "访问范围")
val toggle = ui.toggle("允许读取", "仅访问已经授权的应用。") { enabled ->
    // 先完成保存；失败时显示错误并重新 bind 已保存的值。
    saveReadPermission(enabled)
}
section.addView(toggle)
toggle.bind(savedReadPermission)
ui.attach(activity, ui.scroll(content))
```

`BridgeUi.attach` 负责安全窗口、系统栏主题和系统栏/输入法边距；必须先附着视图再配置 `InsetsController`。组件不保存凭据、不请求系统权限、不触发导航。稳定资源 ID 由调用方提供，便于测试和后续扩展。

## 呈现与交互约定

- 应用目录使用一个原生 `ListView`：选择、全开和范围说明在可滚动头部；默认字号保留底部批量入口，大字号时该入口一起滚动。键盘展开后仍能滚动访问所有操作。`ListView.adapter.count` 包含控件头部，应用数量需扣除 `headerViewsCount`。
- 颜色以 `res/values/colors.xml` 与 `res/values-night/colors.xml` 为来源。系统无衬线字体以 sp 缩放；说明文字可以换行，页面内容可以滚动。
- 圆角与间距集中在 `BridgeUi`。浮动导航采用高不透明度的浅色/深色材质与边缘描线；高对比文字设置下改为实色，避免多层透光影响阅读。
- 按钮触摸按下即给出原生高亮和 0.97 倍缩放，松开或移出时恢复。按下 100ms、恢复 160ms，使用快速起步的缓出曲线；重定向从当前显示值继续。
- 高频切换页面和键盘触发操作立即响应。Android“移除动画”开启时关闭缩放，保留颜色反馈。滚动、惯性与开关由 Android 原生组件处理。
- 开关提供原生可勾选语义，图标配文字标签，状态不只靠颜色区分。更改状态后重新读取已保存配置，避免保存失败时界面虚假成功。
- 一次性码不进入 Activity 保存状态或自动填充；应用窗口保留 `FLAG_SECURE`。

模拟器验证范围与限制记录在 [TESTING.md](TESTING.md)。
