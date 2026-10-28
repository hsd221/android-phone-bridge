package io.github.hsd221.phonebridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private lateinit var prefs: BridgePrefs
    private lateinit var body: LinearLayout
    private var statusText: TextView? = null
    private var appSummary: TextView? = null
    private val permissionViews = mutableMapOf<String, Pair<String, TextView>>()
    private val capabilitySwitches = mutableMapOf<Capability, Switch>()
    private var syncingCapabilities = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refresh: Job? = null
    private val ink = Color.rgb(30, 48, 44)
    private val green = Color.rgb(23, 102, 91)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        prefs = BridgePrefs(this)
        window.setDecorFitsSystemWindows(false)
        render()
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(lightBars, lightBars)
    }
    override fun onResume() {
        super.onResume(); BridgeService.activityVisible = true
        if (prefs.identity != null || prefs.pendingRevocation != null) BridgeService.start(this)
        refresh = scope.launch { while (isActive) { updateStatus(); delay(1000) } }
    }
    override fun onPause() { BridgeService.activityVisible = false; refresh?.cancel(); super.onPause() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(ink); setPadding(0, dp(5), 0, dp(7))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun card(title: String): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(16))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() }
        }
        body.addView(layout, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        layout.addView(text(title, 18f, true))
        return layout
    }
    private fun LinearLayout.button(label: String, action: () -> Unit): Button = Button(this@MainActivity).apply {
        text = label; isAllCaps = false; setTextColor(green); minHeight = dp(48)
        setOnClickListener { action() }; this@button.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun LinearLayout.toggle(label: String, value: Boolean, action: (Boolean) -> Unit): Switch =
        Switch(this@MainActivity).apply {
            text = label; textSize = 15f; isChecked = value; minHeight = dp(52); setTextColor(ink)
            setOnCheckedChangeListener { _, checked -> action(checked) }
            this@toggle.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    private fun permissionRow(parent: LinearLayout, key: String, label: String, viewId: Int, buttonLabel: String, action: () -> Unit) {
        val status = text("", 14f).apply { id = viewId }
        permissionViews[key] = label to status
        parent.addView(status)
        parent.button(buttonLabel, action)
    }
    private fun render() {
        permissionViews.clear(); capabilitySwitches.clear()
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(243, 246, 243)); isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(32)) }
        scroll.addView(body); setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        body.addView(text("手机工具桥", 30f, true))
        body.addView(text("将手机能力连接到你自己的 AI 工具。\n连接和授权始终由你掌控。"))
        val connection = card("连接")
        statusText = text("").also(connection::addView)
        val identity = prefs.identity
        if (identity == null) pairingCard(connection)
        else {
            connection.addView(text(identity.url, 13f))
            connection.button("重新连接") { BridgeService.start(this, "reconnect") }
            connection.button("解除配对") { confirmUnpair() }
        }
        val controls = card("随时暂停")
        val policy = prefs.policy()
        controls.toggle("暂停全部访问", policy.pausedAll) { prefs.setFlag("pausedAll", it) }
        controls.toggle("暂停操作，保留已授权读取", policy.pausedOperations) { prefs.setFlag("pausedOperations", it) }
        controls.addView(text("暂停会取消待执行请求。已经交给 Android 或应用的点击、输入和提交可能已经生效。", 13f))
        val capabilities = card("能力授权")
        mapOf(Capability.LOCATION to "按次获取当前位置", Capability.UI to "读取可见文字和控件", Capability.SCREENSHOT to "获取系统允许的截图", Capability.OPERATE to "打开应用、点击、滑动和输入")
            .forEach { (capability, label) ->
                capabilitySwitches[capability] = capabilities.toggle(label, capability in policy.capabilities) {
                    if (!syncingCapabilities) saveSettings { prefs.setFlag("cap_${capability.name}", it) }
                }
            }
        capabilities.button("全部开启四项能力") { saveSettings { prefs.setCapabilities(true) } }.id = R.id.capabilities_enable_all
        capabilities.button("全部关闭四项能力") { saveSettings { prefs.setCapabilities(false) } }.id = R.id.capabilities_disable_all
        capabilities.addView(text("应用通用操作可能触发发送、支付等业务按钮。请只授权你信任 Harness 操作的应用。", 13f))
        val apps = card("按应用授权")
        apps.addView(text("新应用默认禁止。读取与操作分别配置，手机工具桥自身设置始终禁止远程访问。", 13f))
        appSummary = text("", 14f).also(apps::addView)
        apps.button("搜索与批量管理应用权限") { startActivity(Intent(this, AppPermissionsActivity::class.java)) }
        apps.addView(text("可按应用名或包名搜索、多选、全选，批量允许读取/操作或禁止访问；也可一键开启列表中全部应用及四项能力。", 13f))
        apps.addView(text("截图和交互会检查可见窗口；键盘、分屏、系统弹窗或覆盖层未授权时将拒绝请求。授权不构成应用私有数据沙箱。", 13f))
        val system = card("系统与后台")
        system.addView(text("以下状态由系统实时读取，从设置返回后自动更新。应用内开启能力不会替代系统授权。", 13f))
        permissionRow(system, "accessibility", "无障碍", R.id.system_accessibility_status, "设置无障碍服务") { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        permissionRow(system, "location", "定位权限", R.id.system_location_status, "设置定位权限") {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) openAppSettings()
            else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
        }
        permissionRow(system, "backgroundLocation", "后台定位", R.id.system_background_location_status, "设置“始终允许”定位") { openAppSettings() }
        permissionRow(system, "locationService", "系统定位开关", R.id.system_location_service_status, "系统定位设置") { openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        permissionRow(system, "notifications", "运行通知", R.id.system_notification_status, "设置通知权限") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
            else openNotificationSettings()
        }
        permissionRow(system, "battery", "电池优化", R.id.system_battery_status, "设置电池优化") { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        permissionRow(system, "background", "系统后台运行", R.id.system_background_status, "应用后台运行设置") { openAppSettings() }
        permissionRow(system, "autostart", "厂商自启动", R.id.system_autostart_status, "应用详情与厂商设置") { openAppSettings() }
        system.addView(text("首次配对后可关闭此页面，连接通过前台服务保持。请按厂商要求允许自启动、后台运行，并关闭此应用的省电限制。重启后会在系统允许时恢复连接；强行停止后需要手动打开 APK。后台定位建议授予“始终允许”。", 13f))
        val enhanced = card("可选 Shizuku")
        enhanced.toggle("可用时用 Shizuku 增强应用启动", prefs.shizukuEnabled) { prefs.shizukuEnabled = it }
        permissionRow(enhanced, "shizuku", "Shizuku", R.id.system_shizuku_status, "申请 Shizuku 权限") { runCatching { ShizukuSupport(this).requestPermission() }.onFailure { message(it.message ?: "授权失败") } }
        enhanced.addView(text("基础工具不依赖 Shizuku。仅支持非 Root 模式和固定的应用启动能力；手机重启后通常需重新启动 Shizuku。", 13f))
        updateStatus()
    }
    private fun pairingCard(parent: LinearLayout) {
        val address = EditText(this).apply { hint = "https://bridge.example.com"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; setSingleLine() }
        val code = EditText(this).apply { hint = "一次性配对码"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD; setSingleLine() }
        parent.addView(address); parent.addView(code)
        var insecure = false
        parent.toggle("仅本地开发：允许 HTTP 明文", false) { insecure = it }
        parent.addView(text("在电脑运行 Bridge 并生成配对码。远程连接使用 HTTPS，手机会验证服务器证书。", 13f))
        lateinit var pairButton: Button
        pairButton = parent.button("配对并连接") {
            if (prefs.pendingRevocation != null) { message("旧身份仍在等待 Bridge 撤销，恢复网络后再配对；也可在 Bridge 命令行 revoke。"); return@button }
            pairButton.isEnabled = false
            scope.launch {
                try {
                    prefs.identity = BridgeNetwork.pair(address.text.toString(), code.text.toString(), prefs, insecure)
                    BridgeService.start(this@MainActivity)
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
                    render()
                } catch (error: Exception) { message(error.message ?: "配对失败"); pairButton.isEnabled = true }
            }
        }
    }
    private fun updateStatus() {
        val state = if (prefs.identity == null) { if (prefs.pendingRevocation != null) "已本机解绑，等待 Bridge 撤销" else "未配对" } else BridgeService.linkStatus
        val location = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val shizuku = ShizukuSupport(this).status()
        statusText?.text = getString(R.string.connection_status, state, if (PhoneAccessibilityService.instance != null) "已启用" else "未启用", if (location) "已授权" else "未授权", if (shizuku.optBoolean("available")) "可用" else if (shizuku.optBoolean("running")) "未授权或不支持的模式" else "未启动")
        val systemStates = SystemPermissions.read(this)
        permissionViews.forEach { (key, entry) ->
            val (label, view) = entry
            val permission = systemStates.getValue(key)
            view.text = getString(R.string.system_permission_state, label, permission.description)
            view.setTextColor(when (permission.allowed) { true -> green; false -> Color.rgb(158, 74, 28); null -> ink })
        }
        val policy = prefs.policy()
        val apps = policy.apps.filterKeys { it != packageName }.values
        appSummary?.text = getString(R.string.app_access_summary, apps.size, apps.count { it.read }, apps.count { it.operate })
        syncingCapabilities = true
        try { capabilitySwitches.forEach { (capability, toggle) -> toggle.isChecked = capability in policy.capabilities } }
        finally { syncingCapabilities = false }
    }
    private fun confirmUnpair() {
        AlertDialog.Builder(this).setTitle("解除配对？").setMessage("立即停止本机连接和访问。离线时会保留加密的撤销请求，恢复网络后使 Bridge 上的旧身份失效。")
            .setNegativeButton("取消", null).setPositiveButton("解除配对") { _, _ ->
                val identity = prefs.identity ?: return@setPositiveButton
                prefs.setFlag("pausedAll", true)
                prefs.pendingRevocation = identity
                prefs.identity = null
                BridgeService.start(this)
                render()
            }.show()
    }
    private fun saveSettings(action: () -> Unit) {
        runCatching(action).onFailure { message(it.message ?: "保存失败，请重试") }
        updateStatus()
    }
    private fun openAppSettings() { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    private fun openNotificationSettings() { openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
    private fun openSettings(intent: Intent) { runCatching { startActivity(intent) }.onFailure { message("此设备没有对应设置入口，请到系统设置中手动配置") } }
    private fun message(value: String) { AlertDialog.Builder(this).setMessage(value).setPositiveButton("知道了", null).show() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (prefs.identity != null) BridgeService.start(this)
        updateStatus()
        if (grantResults.isNotEmpty() && grantResults.all { it != PackageManager.PERMISSION_GRANTED }) {
            AlertDialog.Builder(this).setMessage("权限尚未允许，可在系统设置中修改授权。")
                .setNegativeButton("稍后", null).setPositiveButton("打开设置") { _, _ -> if (requestCode == 11) openNotificationSettings() else openAppSettings() }.show()
        }
    }
}
