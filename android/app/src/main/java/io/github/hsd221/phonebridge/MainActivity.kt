package io.github.hsd221.phonebridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.hsd221.phonebridge.ui.ActionRow
import io.github.hsd221.phonebridge.ui.BridgeIcon
import io.github.hsd221.phonebridge.ui.BridgeIconDrawable
import io.github.hsd221.phonebridge.ui.BridgeUi
import io.github.hsd221.phonebridge.ui.ButtonKind
import io.github.hsd221.phonebridge.ui.Field
import io.github.hsd221.phonebridge.ui.ToggleRow
import io.github.hsd221.phonebridge.ui.WidthBoundFrame
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
    private lateinit var ui: BridgeUi
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refresh: Job? = null
    private var tab = 0
    private val pages = mutableListOf<View>()
    private val navigation = mutableListOf<Button>()
    private val permissionViews = mutableMapOf<String, ActionRow>()
    private val capabilityRows = mutableMapOf<Capability, ToggleRow>()
    private lateinit var pauseAll: ToggleRow
    private lateinit var pauseOperations: ToggleRow
    private lateinit var shizukuToggle: ToggleRow
    private lateinit var connectionState: TextView
    private lateinit var connectionDetail: TextView
    private lateinit var accessState: TextView
    private lateinit var appSummary: ActionRow
    private lateinit var capabilitySummary: TextView
    private var address: Field? = null
    private var code: Field? = null
    private var pairingError: TextView? = null
    private var pairButton: Button? = null
    private var httpToggle: ToggleRow? = null
    private var draftAddress = ""
    private var allowHttp = false
    private lateinit var pairingRequest: PairingRequest
    private var resumed = false
    private var notifyAfterPairing = false
    private var pairing = false
    private var pairingAttempted = false
    private var pairedAtRender = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = BridgePrefs(this); ui = BridgeUi(this)
        pairingRequest = lastNonConfigurationInstance as? PairingRequest ?: PairingRequest(applicationContext)
        pairing = pairingRequest.busy
        tab = savedInstanceState?.getInt("tab", 0) ?: 0
        draftAddress = savedInstanceState?.getString("address", "") ?: ""
        allowHttp = savedInstanceState?.getBoolean("allowHttp", false) ?: false
        render()
        pairingRequest.changed = ::pairingChanged
        pairingChanged()
    }
    override fun onRetainNonConfigurationInstance(): Any = pairingRequest
    override fun onResume() {
        super.onResume(); resumed = true; BridgeService.activityVisible = true
        requestNotificationAfterPairing()
        if (prefs.identity != null || prefs.pendingRevocation != null) BridgeService.start(this)
        refresh?.cancel()
        refresh = scope.launch { while (isActive) { updateStatus(); delay(1000) } }
    }
    override fun onPause() { resumed = false; BridgeService.activityVisible = false; refresh?.cancel(); super.onPause() }
    override fun onDestroy() { pairingRequest.changed = null; scope.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab); outState.putString("address", address?.input?.text?.toString() ?: draftAddress)
        outState.putBoolean("allowHttp", allowHttp)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        draftAddress = address?.input?.text?.toString() ?: draftAddress
        permissionViews.clear(); capabilityRows.clear(); pages.clear(); navigation.clear()
        address = null; code = null; pairingError = null; pairButton = null; httpToggle = null
        pairedAtRender = prefs.identity != null
        val root = FrameLayout(this)
        val stage = WidthBoundFrame(this)
        root.addView(stage, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        val contents = listOf(connectionPage(), accessPage(), settingsPage())
        contents.forEach { content ->
            val page = ui.scroll(content, bottom = 124)
            stage.addView(page, FrameLayout.LayoutParams(-1, -1)); pages.add(page)
        }
        val nav = ui.row().apply {
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.chrome()
            elevation = ui.dp(8).toFloat()
        }
        val labels = listOf("连接", "访问", "设置")
        val ids = listOf(R.id.nav_connection, R.id.nav_access, R.id.nav_settings)
        labels.forEachIndexed { index, label ->
            val button = ui.button(label, ButtonKind.QUIET, ids[index]) { selectTab(index) }
            nav.addView(button, LinearLayout.LayoutParams(0, -2, 1f)); navigation.add(button)
        }
        stage.addView(nav, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = ui.dp(20); rightMargin = ui.dp(20); bottomMargin = ui.dp(14)
        })
        ui.attach(this, root)
        selectTab(tab); updateStatus(); setPairingBusy(pairing)
    }
    private fun selectTab(index: Int) {
        tab = index.coerceIn(0, 2)
        pages.forEachIndexed { i, page -> page.visibility = if (i == tab) View.VISIBLE else View.GONE }
        navigation.forEachIndexed { i, button ->
            button.isSelected = i == tab
            button.background = ui.pressSurface(if (i == tab) ui.accentSurface else android.graphics.Color.TRANSPARENT, 20)
            button.setTextColor(if (i == tab) ui.accent else ui.secondary)
            button.typeface = Typeface.create("sans-serif", if (i == tab) Typeface.BOLD else Typeface.NORMAL)
            button.textSize = 12f
            button.compoundDrawablePadding = ui.dp(5)
            button.setCompoundDrawablesWithIntrinsicBounds(null, BridgeIconDrawable(BridgeIcon.entries[i], if (i == tab) ui.accent else ui.secondary, ui.dp(22)), null, null)
            button.stateDescription = if (i == tab) "当前页面" else ""
        }
    }
    private fun connectionPage() = ui.column().apply {
        ui.heading(this, "手机工具桥", "将手机连接到你自己的 AI 工具。")
        val connection = ui.card(this)
        connection.addView(ui.text("连接状态", 13f, true, ui.secondary))
        connectionState = ui.text("", 28f, true).apply {
            id = R.id.connection_state; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        connection.addView(connectionState, ui.gap(top = 10, bottom = 8))
        connectionDetail = ui.text("", 14f, tint = ui.secondary)
        connection.addView(connectionDetail, ui.gap(bottom = 6))
        val identity = prefs.identity
        if (identity != null) {
            connection.addView(ui.text(identity.url, 14f).apply { setTextIsSelectable(true) }, ui.gap(top = 8, bottom = 8))
            if (Uri.parse(identity.url).scheme == "http") ui.note(connection, "此连接使用 HTTP 明文，仅适用于可信的本地网络。", ui.warning)
            ui.addButton(connection, "重新连接", ButtonKind.PRIMARY, R.id.reconnect) { BridgeService.start(this@MainActivity, "reconnect"); updateStatus() }
            ui.addButton(connection, "解除配对", ButtonKind.DANGER, R.id.unpair) { confirmUnpair() }
        } else pairingCard(connection)
        val guide = ui.section(this, "连接之后")
        guide.addView(ui.actionRow("配置访问权限", "选择可读取、可操作的应用与能力。") { selectTab(1) })
        ui.divider(guide)
        guide.addView(ui.actionRow("检查系统授权", "无障碍、定位与后台运行状态。") { selectTab(2) })
        ui.note(this, "连接由前台服务保持。关闭页面后仍可连接；后台运行受 Android 和厂商设置约束。")
    }
    private fun accessPage() = ui.column().apply {
        ui.heading(this, "访问控制", "选择开放的范围，随时收回控制。")
        val controls = ui.section(this, "即时控制", "暂停会取消待执行请求；已交给 Android 或应用的操作可能已经生效。基础状态查询仍可用。")
        accessState = ui.text("", 14f, true, ui.accent).apply {
            id = R.id.access_state; setPadding(0, ui.dp(12), 0, ui.dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        controls.addView(accessState)
        pauseAll = ui.toggle("暂停全部访问", "暂停定位、读屏、截图与操作。", R.id.pause_all) { saveSettings { prefs.setFlag("pausedAll", it) } }
        controls.addView(pauseAll); ui.divider(controls)
        pauseOperations = ui.toggle("暂停操作", "保留读取设置；全部访问暂停时，读取也不可用。", R.id.pause_operations) { saveSettings { prefs.setFlag("pausedOperations", it) } }
        controls.addView(pauseOperations)
        val apps = ui.section(this, "应用范围", "新应用默认禁止。读取与操作分别配置；本应用的设置始终禁止远程访问。键盘、系统弹窗及覆盖层也需要单独授权。")
        appSummary = ui.actionRow("应用权限", "") { startActivity(Intent(this@MainActivity, AppPermissionsActivity::class.java)) }.apply { id = R.id.manage_app_permissions }
        apps.addView(appSummary)
        val capabilities = ui.section(this, "能力开关", "能力开关、应用授权和系统权限需要同时满足。通用操作可能触发发送、支付等按钮，请按需要开放。")
        capabilitySummary = ui.text("", 13f, true, ui.secondary)
        capabilities.addView(capabilitySummary, ui.gap(top = 12, bottom = 4))
        val specs = listOf(
            Triple(Capability.LOCATION, "当前位置", "按次获取位置，需要系统定位权限。"),
            Triple(Capability.UI, "读取界面", "读取已授权应用的可见文字与控件。"),
            Triple(Capability.SCREENSHOT, "屏幕截图", "仅获取系统允许且窗口已授权的截图。"),
            Triple(Capability.OPERATE, "操作应用", "打开应用、点击、滑动、输入与返回。"),
        )
        val ids = listOf(R.id.cap_location, R.id.cap_ui, R.id.cap_screenshot, R.id.cap_operate)
        specs.forEachIndexed { i, (capability, title, detail) ->
            if (i > 0) ui.divider(capabilities)
            val row = ui.toggle(title, detail, ids[i]) { saveSettings { prefs.setFlag("cap_${capability.name}", it) } }
            capabilities.addView(row); capabilityRows[capability] = row
        }
        ui.addButton(capabilities, "开启四项能力", viewId = R.id.capabilities_enable_all) { saveSettings("已开启四项能力；应用和系统授权需分别设置") { prefs.setCapabilities(true) } }
        ui.addButton(capabilities, "关闭四项能力", ButtonKind.QUIET, R.id.capabilities_disable_all) { saveSettings("已关闭四项能力") { prefs.setCapabilities(false) } }
    }
    private fun settingsPage() = ui.column().apply {
        ui.heading(this, "系统设置", "从系统设置返回后，状态自动更新。")
        val essential = ui.section(this, "系统授权", "这里显示 Android 的实际授权。应用内开启能力不会替代系统授权。")
        permissionRow(essential, "accessibility", "无障碍", R.id.system_accessibility_status) { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        permissionRow(essential, "location", "定位权限", R.id.system_location_status) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) openAppSettings()
            else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
        }
        permissionRow(essential, "backgroundLocation", "后台定位", R.id.system_background_location_status) { openAppSettings() }
        permissionRow(essential, "locationService", "系统定位开关", R.id.system_location_service_status) { openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        permissionRow(essential, "notifications", "运行通知", R.id.system_notification_status, last = true) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
            else openNotificationSettings()
        }
        val background = ui.section(this, "后台运行", "建议允许自启动、后台运行并豁免电池优化。后台定位需在系统中选择“始终允许”。强行停止后需要手动打开应用。")
        permissionRow(background, "battery", "电池优化", R.id.system_battery_status) { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        permissionRow(background, "background", "系统后台运行", R.id.system_background_status) { openAppSettings() }
        permissionRow(background, "autostart", "厂商自启动", R.id.system_autostart_status, last = true) { openAppSettings() }
        val enhanced = ui.section(this, "SHIZUKU · 可选增强", "基础工具不依赖 Shizuku。仅支持非 Root 模式下的应用启动；重启手机后通常需重新启动 Shizuku。")
        shizukuToggle = ui.toggle("增强应用启动", "可用时使用 Shizuku 启动已授权应用。", R.id.shizuku_enabled) { saveSettings { prefs.shizukuEnabled = it } }
        enhanced.addView(shizukuToggle); ui.divider(enhanced)
        permissionRow(enhanced, "shizuku", "Shizuku", R.id.system_shizuku_status, last = true) {
            runCatching { ShizukuSupport(this@MainActivity).requestPermission() }.onFailure { message(it.message ?: "授权失败") }
        }
        ui.note(this, "手机工具桥 ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}")
    }
    private fun permissionRow(parent: LinearLayout, key: String, label: String, viewId: Int, last: Boolean = false, action: () -> Unit) {
        val row = ui.actionRow(label, "读取状态中…", action)
        row.detail.id = viewId
        permissionViews[key] = row
        parent.addView(row)
        if (!last) ui.divider(parent)
    }
    private fun pairingCard(parent: LinearLayout) {
        ui.space(parent, 4)
        address = ui.field("Bridge 地址", "https://bridge.example.com", R.id.pair_address).also {
            it.input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            it.input.imeOptions = EditorInfo.IME_ACTION_NEXT; it.input.setText(draftAddress)
            parent.addView(it)
        }
        code = ui.field("一次性配对码", "输入电脑上生成的配对码", R.id.pair_code).also {
            it.input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            it.input.imeOptions = EditorInfo.IME_ACTION_DONE
            it.input.isSaveEnabled = false; it.input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            it.input.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_DONE) { startPairing(); true } else false }
            parent.addView(it)
        }
        httpToggle = ui.toggle("允许本地 HTTP 连接", "明文传输，仅用于可信的本地开发网络。", R.id.pair_allow_http) {
            allowHttp = it
            if (pairingAttempted) validatePairing()
        }.also { it.bind(allowHttp); parent.addView(it) }
        pairingError = ui.text("", 14f, tint = ui.danger).apply {
            id = R.id.pair_error; visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { parent.addView(it, ui.gap(top = 10, bottom = 6)) }
        pairButton = ui.addButton(parent, "配对并连接", ButtonKind.PRIMARY, R.id.pair_submit) { startPairing() }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (pairingAttempted) validatePairing()
                pairingError?.visibility = View.GONE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        address?.input?.addTextChangedListener(watcher); code?.input?.addTextChangedListener(watcher)
        address?.input?.setOnFocusChangeListener { _, focused ->
            if (!focused && !address?.input?.text.isNullOrBlank()) {
                pairingAttempted = true
                address?.error(runCatching { BridgeNetwork.normalizeUrl(address?.input?.text.toString(), allowHttp) }.exceptionOrNull()?.message)
            }
        }
        ui.note(parent, "先在电脑上启动 Bridge 并生成配对码。远程连接使用 HTTPS，手机会验证服务器证书。")
    }
    private fun validatePairing(): Boolean {
        val addressError = runCatching { BridgeNetwork.normalizeUrl(address?.input?.text.toString(), allowHttp) }.exceptionOrNull()?.message
        val codeError = if (code?.input?.text.isNullOrBlank()) "请输入一次性配对码" else null
        address?.error(addressError); code?.error(codeError)
        return addressError == null && codeError == null
    }
    private fun startPairing() {
        if (pairing) return
        pairingAttempted = true
        if (!validatePairing()) {
            if (runCatching { BridgeNetwork.normalizeUrl(address?.input?.text.toString(), allowHttp) }.isFailure) address?.input?.requestFocus()
            else code?.input?.requestFocus()
            return
        }
        if (prefs.pendingRevocation != null) { showPairingError("旧身份正在等待 Bridge 撤销。恢复网络后重试，或先在电脑端撤销旧身份。"); return }
        val url = address!!.input.text.toString(); val pairCode = code!!.input.text.toString()
        pairingRequest.start(url, pairCode, allowHttp)
    }
    private fun pairingChanged() {
        pairing = pairingRequest.busy
        if (pairingRequest.completed) {
            pairingRequest.completed = false
            code?.input?.text?.clear()
            notifyAfterPairing = true
            render()
            requestNotificationAfterPairing()
        }
        setPairingBusy(pairing)
        pairingRequest.error?.let(::showPairingError)
    }
    private fun requestNotificationAfterPairing() {
        if (!resumed || !notifyAfterPairing) return
        notifyAfterPairing = false
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }
    }
    private fun setPairingBusy(busy: Boolean) {
        pairButton?.apply {
            val pending = prefs.pendingRevocation != null
            isEnabled = !busy && !pending
            text = when { busy -> "正在配对…"; pending -> "等待旧身份撤销"; else -> "配对并连接" }
        }
        address?.input?.isEnabled = !busy; code?.input?.isEnabled = !busy; httpToggle?.toggle?.isEnabled = !busy
    }
    private fun showPairingError(value: String) { pairingError?.apply { text = value; visibility = View.VISIBLE } }
    private fun updateStatus() {
        val identity = prefs.identity
        if ((identity != null) != pairedAtRender) { render(); return }
        val pending = prefs.pendingRevocation != null
        val state = if (identity == null) {
            when { pending -> "等待撤销旧身份"; BridgeService.linkStatus.contains("身份无效") -> "需要重新配对"; else -> "未配对" }
        } else BridgeService.linkStatus
        connectionState.text = state
        connectionState.setTextColor(if (state == "已连接") ui.success else ui.ink)
        connectionDetail.visibility = if (identity == null && !pending && !state.contains("重新配对")) View.GONE else View.VISIBLE
        connectionDetail.text = when {
            pending -> "本机已停止访问。联网后会重试撤销 Bridge 上的旧身份，完成后可重新配对。"
            identity == null -> "填写 Bridge 地址和配对码，建立这台手机的连接。"
            state == "已连接" -> "手机已连接 Bridge。可用能力取决于访问控制和系统授权。"
            else -> "访问权限保存在本机，连接恢复后仍按当前授权执行。"
        }
        if (!pairing) pairButton?.apply { isEnabled = !pending; text = if (pending) "等待旧身份撤销" else "配对并连接" }
        val policy = prefs.policy()
        accessState.text = when { policy.pausedAll -> "全部访问已暂停"; policy.pausedOperations -> "操作已暂停 · 读取设置保留"; identity == null -> "未暂停 · 连接后按授权开放"; else -> "按当前授权开放访问" }
        accessState.setTextColor(if (policy.pausedAll || policy.pausedOperations) ui.warning else ui.accent)
        pauseAll.bind(policy.pausedAll); pauseOperations.bind(policy.pausedOperations)
        capabilityRows.forEach { (capability, row) -> row.bind(capability in policy.capabilities) }
        capabilitySummary.text = getString(R.string.capability_summary, policy.capabilities.size)
        val apps = policy.apps.filterKeys { it != packageName }.values
        val summary = getString(R.string.app_access_summary, apps.size, apps.count { it.read }, apps.count { it.operate })
        appSummary.bindDetail(summary)
        shizukuToggle.bind(prefs.shizukuEnabled)
        val systemStates = SystemPermissions.read(this)
        permissionViews.forEach { (key, row) ->
            val permission = systemStates.getValue(key)
            row.bindDetail(permission.description, when (permission.allowed) { true -> ui.success; false -> ui.warning; null -> ui.secondary })
        }
    }
    private fun confirmUnpair() {
        val dialog = AlertDialog.Builder(this).setTitle("解除这台手机的配对？")
            .setMessage("立即停止本机连接与访问。离线时会保留加密的撤销请求，联网后撤销 Bridge 上的旧身份。重新配对不会自动解除暂停。")
            .setNegativeButton("取消", null).setPositiveButton("解除配对") { _, _ ->
                runCatching {
                    val identity = prefs.identity ?: return@runCatching
                    prefs.setFlag("pausedAll", true); prefs.pendingRevocation = identity; prefs.identity = null
                    BridgeService.start(this); render()
                }.onFailure { message(it.message ?: "无法解除配对，请重试") }
            }.create()
        ui.showDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.danger)
    }
    private fun saveSettings(success: String? = null, action: () -> Unit) {
        runCatching(action).onSuccess { success?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() } }
            .onFailure { message(it.message ?: "保存失败，请重试") }
        updateStatus()
    }
    private fun openAppSettings() { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    private fun openNotificationSettings() { openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
    private fun openSettings(intent: Intent) { runCatching { startActivity(intent) }.onFailure { message("此设备没有对应设置入口，请到系统设置中手动配置") } }
    private fun message(value: String) { ui.showDialog(AlertDialog.Builder(this).setMessage(value).setPositiveButton("知道了", null).create()) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (prefs.identity != null) BridgeService.start(this)
        updateStatus()
        if (grantResults.isNotEmpty() && grantResults.all { it != PackageManager.PERMISSION_GRANTED }) {
            ui.showDialog(AlertDialog.Builder(this).setMessage("权限尚未允许，可在系统设置中修改授权。")
                .setNegativeButton("稍后", null).setPositiveButton("打开设置") { _, _ -> if (requestCode == 11) openNotificationSettings() else openAppSettings() }.create())
        }
    }
}
