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
    private fun LinearLayout.toggle(label: String, value: Boolean, action: (Boolean) -> Unit) {
        addView(Switch(this@MainActivity).apply {
            text = label; textSize = 15f; isChecked = value; minHeight = dp(52); setTextColor(ink)
            setOnCheckedChangeListener { _, checked -> action(checked) }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun render() {
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
            .forEach { (capability, label) -> capabilities.toggle(label, capability in policy.capabilities) { prefs.setFlag("cap_${capability.name}", it) } }
        capabilities.addView(text("应用通用操作可能触发发送、支付等业务按钮。请只授权你信任 Harness 操作的应用。", 13f))
        val apps = card("按应用授权")
        apps.addView(text("新应用默认禁止。读取与操作分别配置，手机工具桥自身设置始终禁止远程访问。", 13f))
        policy.apps.toSortedMap().filterKeys { it != packageName }.forEach { (pkg, access) ->
            val mode = when { access.read && access.operate -> "读取 · 操作"; access.read -> "仅读取"; access.operate -> "仅操作"; else -> "禁止" }
            apps.button("$pkg\n$mode") { editApp(pkg) }
        }
        apps.button("选择已安装应用") { chooseApp() }
        apps.button("手动添加包名（含键盘或系统窗口）") { manualApp() }
        apps.addView(text("截图和交互会检查可见窗口；键盘、分屏、系统弹窗或覆盖层未授权时将拒绝请求。授权不构成应用私有数据沙箱。", 13f))
        val system = card("系统与后台")
        system.button("启用无障碍服务") { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        system.button("授予定位权限") { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10) }
        system.button("应用权限与“始终允许”定位") { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        if (Build.VERSION.SDK_INT >= 33) system.button("授予运行通知权限") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11) }
        system.button("电池与后台运行设置") { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        system.addView(text("首次配对后可关闭此页面，连接通过前台服务保持。请按厂商要求允许自启动、后台运行，并关闭此应用的省电限制。重启后会在系统允许时恢复连接；强行停止后需要手动打开 APK。后台定位建议授予“始终允许”。", 13f))
        val enhanced = card("可选 Shizuku")
        enhanced.toggle("可用时用 Shizuku 增强应用启动", prefs.shizukuEnabled) { prefs.shizukuEnabled = it }
        enhanced.button("申请 Shizuku 权限") { runCatching { ShizukuSupport(this).requestPermission() }.onFailure { message(it.message ?: "授权失败") } }
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
    private fun chooseApp() {
        val entries = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }.distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString() }
        AlertDialog.Builder(this).setTitle("选择要授权的应用").setItems(entries.map { "${it.loadLabel(packageManager)}\n${it.activityInfo.packageName}" }.toTypedArray()) { _, index -> editApp(entries[index].activityInfo.packageName) }.setNegativeButton("取消", null).show()
    }
    private fun manualApp() {
        val input = EditText(this).apply { hint = "com.example.app"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("应用包名").setView(input).setNegativeButton("取消", null).setPositiveButton("配置") { _, _ ->
            val pkg = input.text.toString().trim()
            if (!pkg.matches(Regex("[A-Za-z][\\w]*(\\.[\\w]+)+")) || pkg == packageName) message("请输入其他应用的有效包名") else editApp(pkg)
        }.show()
    }
    private fun editApp(pkg: String) {
        val access = prefs.policy().apps[pkg] ?: AppAccess()
        val selected = booleanArrayOf(access.read, access.operate)
        AlertDialog.Builder(this).setTitle(pkg).setMultiChoiceItems(arrayOf("允许读取与截图", "允许打开与操作"), selected) { _, index, checked -> selected[index] = checked }
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ -> prefs.setApp(pkg, AppAccess(selected[0], selected[1])); render() }.show()
    }
    private fun openSettings(intent: Intent) { runCatching { startActivity(intent) }.onFailure { message("此设备没有对应设置入口，请到系统设置中手动配置") } }
    private fun message(value: String) { AlertDialog.Builder(this).setMessage(value).setPositiveButton("知道了", null).show() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (prefs.identity != null) BridgeService.start(this)
        updateStatus()
    }
}
