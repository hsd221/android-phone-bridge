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
import android.widget.Switch
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
    private val toolRows = mutableMapOf<String, ToggleRow>()
    private val legacyToolAliases = mutableMapOf<String, Switch>()
    private lateinit var pauseAll: ToggleRow
    private lateinit var pauseOperations: ToggleRow
    private lateinit var shizukuToggle: ToggleRow
    private lateinit var liveUpdatesToggle: ToggleRow
    private lateinit var liveUpdatesStatus: TextView
    private lateinit var connectionNotificationRow: ActionRow
    private lateinit var connectionState: TextView
    private lateinit var connectionDetail: TextView
    private lateinit var accessState: TextView
    private lateinit var appSummary: ActionRow
    private lateinit var toolSummary: TextView
    private var address: Field? = null
    private var code: Field? = null
    private var pairingError: TextView? = null
    private var pairButton: Button? = null
    private var httpToggle: ToggleRow? = null
    private var draftAddress = ""
    private var allowHttp = false
    private lateinit var pairingRequest: PairingRequest
    private lateinit var updateCheckRequest: UpdateCheckRequest
    private lateinit var updateStatusText: TextView
    private lateinit var updateNotes: TextView
    private lateinit var checkUpdatesButton: Button
    private lateinit var downloadUpdateButton: Button
    private var updateDialog: AlertDialog? = null
    private var updateDialogTag: String? = null
    private var resumed = false
    private var notifyAfterPairing = false
    private var pairing = false
    private var pairingAttempted = false
    private var pairedAtRender = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = BridgePrefs(this); ui = BridgeUi(this)
        val retained = lastNonConfigurationInstance as? RetainedRequests
        pairingRequest = retained?.pairing ?: PairingRequest(applicationContext)
        updateCheckRequest = retained?.updates ?: UpdateCheckRequest()
        pairing = pairingRequest.busy
        tab = savedInstanceState?.getInt("tab", 0) ?: 0
        draftAddress = savedInstanceState?.getString("address", "") ?: ""
        allowHttp = savedInstanceState?.getBoolean("allowHttp", false) ?: false
        render()
        pairingRequest.changed = ::pairingChanged
        updateCheckRequest.changed = ::updatesChanged
        pairingChanged()
    }
    private data class RetainedRequests(val pairing: PairingRequest, val updates: UpdateCheckRequest)
    override fun onRetainNonConfigurationInstance(): Any = RetainedRequests(pairingRequest, updateCheckRequest)
    override fun onResume() {
        super.onResume(); resumed = true; BridgeService.activityVisible = true
        requestNotificationAfterPairing()
        if (prefs.identity != null || prefs.pendingRevocation != null) BridgeService.start(this)
        refresh?.cancel()
        refresh = scope.launch { while (isActive) { updateStatus(); delay(1000) } }
        checkForUpdates((application as BridgeApplication).foregroundGeneration)
        showUpdatePrompt()
    }
    override fun onPause() { resumed = false; BridgeService.activityVisible = false; refresh?.cancel(); super.onPause() }
    override fun onStop() { updateDialog?.dismiss(); super.onStop() }
    override fun onDestroy() {
        pairingRequest.changed = null; updateCheckRequest.changed = null
        updateDialog?.setOnDismissListener(null); updateDialog?.dismiss(); updateDialog = null; updateDialogTag = null
        if (!isChangingConfigurations) updateCheckRequest.close()
        scope.cancel(); super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab); outState.putString("address", address?.input?.text?.toString() ?: draftAddress)
        outState.putBoolean("allowHttp", allowHttp)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        draftAddress = address?.input?.text?.toString() ?: draftAddress
        permissionViews.clear(); toolRows.clear(); legacyToolAliases.clear(); pages.clear(); navigation.clear()
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
        val labels = listOf(getString(R.string.nav_connection), getString(R.string.nav_access), getString(R.string.nav_settings))
        val ids = listOf(R.id.nav_connection, R.id.nav_access, R.id.nav_settings)
        labels.forEachIndexed { index, label ->
            val button = ui.button(label, ButtonKind.QUIET, ids[index]) { selectTab(index) }
            nav.addView(button, LinearLayout.LayoutParams(0, -2, 1f)); navigation.add(button)
        }
        stage.addView(nav, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = ui.dp(20); rightMargin = ui.dp(20); bottomMargin = ui.dp(14)
        })
        ui.attach(this, root)
        selectTab(tab); updateStatus(); setPairingBusy(pairing); renderUpdateStatus()
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
            button.stateDescription = if (i == tab) getString(R.string.current_page) else ""
        }
    }
    private fun connectionPage() = ui.column().apply {
        ui.heading(this, getString(R.string.app_name), getString(R.string.app_subtitle))
        val connection = ui.card(this)
        connection.addView(ui.text(getString(R.string.connection_status), 13f, true, ui.secondary))
        connectionState = ui.text("", 28f, true).apply {
            id = R.id.connection_state; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        connection.addView(connectionState, ui.gap(top = 10, bottom = 8))
        connectionDetail = ui.text("", 14f, tint = ui.secondary)
        connection.addView(connectionDetail, ui.gap(bottom = 6))
        val identity = prefs.identity
        if (identity != null) {
            connection.addView(ui.text(identity.url, 14f).apply { setTextIsSelectable(true) }, ui.gap(top = 8, bottom = 8))
            if (Uri.parse(identity.url).scheme == "http") ui.note(connection, getString(R.string.http_warning), ui.warning)
            ui.addButton(connection, getString(R.string.reconnect), ButtonKind.PRIMARY, R.id.reconnect) { BridgeService.start(this@MainActivity, "reconnect"); updateStatus() }
            ui.addButton(connection, getString(R.string.unpair), ButtonKind.DANGER, R.id.unpair) { confirmUnpair() }
        } else pairingCard(connection)
        val guide = ui.section(this, getString(R.string.after_connection))
        guide.addView(ui.actionRow(getString(R.string.configure_access), getString(R.string.configure_access_detail)) { selectTab(1) })
        ui.divider(guide)
        guide.addView(ui.actionRow(getString(R.string.check_system_permissions), getString(R.string.check_system_permissions_detail)) { selectTab(2) })
        ui.note(this, getString(R.string.connection_persistence))
    }
    private fun accessPage() = ui.column().apply {
        ui.heading(this, getString(R.string.access_control), getString(R.string.access_control_subtitle))
        val controls = ui.section(this, getString(R.string.live_control), getString(R.string.live_control_detail))
        accessState = ui.text("", 14f, true, ui.accent).apply {
            id = R.id.access_state; setPadding(0, ui.dp(12), 0, ui.dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        controls.addView(accessState)
        pauseAll = ui.toggle(getString(R.string.pause_all), getString(R.string.pause_all_detail), R.id.pause_all) { saveSettings { prefs.setFlag("pausedAll", it) } }
        controls.addView(pauseAll); ui.divider(controls)
        pauseOperations = ui.toggle(getString(R.string.pause_operations), getString(R.string.pause_operations_detail), R.id.pause_operations) { saveSettings { prefs.setFlag("pausedOperations", it) } }
        controls.addView(pauseOperations)
        val apps = ui.section(this, getString(R.string.app_scope), getString(R.string.app_scope_detail))
        appSummary = ui.actionRow(getString(R.string.app_permissions_title), "") { startActivity(Intent(this@MainActivity, AppPermissionsActivity::class.java)) }.apply { id = R.id.manage_app_permissions }
        apps.addView(appSummary)
        val tools = ui.section(this, getString(R.string.tools), getString(R.string.tools_detail))
        toolSummary = ui.text("", 13f, true, ui.secondary)
        tools.addView(toolSummary, ui.gap(top = 12, bottom = 4))
        val specs = listOf(
            Triple("phone_device_info", getString(R.string.tool_device_info), getString(R.string.tool_device_info_detail)),
            Triple("phone_apps", getString(R.string.tool_apps), getString(R.string.tool_apps_detail)),
            Triple("phone_notifications", getString(R.string.tool_notifications), getString(R.string.tool_notifications_detail)),
            Triple("phone_clipboard", getString(R.string.tool_clipboard), getString(R.string.tool_clipboard_detail)),
            Triple("phone_set_clipboard", getString(R.string.tool_set_clipboard), getString(R.string.tool_set_clipboard_detail)),
            Triple("phone_media", getString(R.string.tool_media), getString(R.string.tool_media_detail)),
            Triple("phone_location", getString(R.string.tool_location), getString(R.string.tool_location_detail)),
            Triple("phone_ui", getString(R.string.tool_ui), getString(R.string.tool_ui_detail)),
            Triple("phone_screenshot", getString(R.string.tool_screenshot), getString(R.string.tool_screenshot_detail)),
            Triple("phone_open_app", getString(R.string.tool_open_app), getString(R.string.tool_open_app_detail)),
            Triple("phone_tap", getString(R.string.tool_tap), getString(R.string.tool_tap_detail)),
            Triple("phone_swipe", getString(R.string.tool_swipe), getString(R.string.tool_swipe_detail)),
            Triple("phone_input", getString(R.string.tool_input), getString(R.string.tool_input_detail)),
            Triple("phone_back", getString(R.string.tool_back), getString(R.string.tool_back_detail)),
            Triple("phone_unlock", getString(R.string.tool_unlock), getString(R.string.tool_unlock_detail)),
        )
        val ids = listOf(
            R.id.tool_device_info, R.id.tool_apps, R.id.tool_notifications, R.id.tool_clipboard, R.id.tool_set_clipboard, R.id.tool_media,
            R.id.tool_location, R.id.tool_ui, R.id.tool_screenshot, R.id.tool_open_app,
            R.id.tool_tap, R.id.tool_swipe, R.id.tool_input, R.id.tool_back, R.id.tool_unlock,
        )
        specs.forEachIndexed { i, (tool, title, detail) ->
            if (i > 0) ui.divider(tools)
            val row = ui.toggle(title, detail, ids[i]) { enabled ->
                saveSettings {
                    val selected = prefs.policy().tools.toMutableSet()
                    if (enabled) selected.add(tool) else selected.remove(tool)
                    prefs.setPermissions(tools = selected)
                }
            }
            tools.addView(row); toolRows[tool] = row
        }
        // Keep the old capability IDs as non-visible compatibility handles for
        // existing automation while the visible controls use tool IDs.
        mapOf(
            "phone_location" to R.id.cap_location,
            "phone_ui" to R.id.cap_ui,
            "phone_screenshot" to R.id.cap_screenshot,
            "phone_open_app" to R.id.cap_operate,
        ).forEach { (tool, id) ->
            val alias = Switch(this@MainActivity).apply {
                this.id = id; visibility = View.GONE; isSaveEnabled = false
                setOnClickListener { toolRows[tool]?.toggle?.performClick() }
            }
            legacyToolAliases[tool] = alias
            tools.addView(alias, LinearLayout.LayoutParams(0, 0))
        }
        ui.addButton(tools, getString(R.string.enable_all_tools), viewId = R.id.capabilities_enable_all) { saveSettings(getString(R.string.enable_all_tools_success)) { prefs.setTools(true) } }
        ui.addButton(tools, getString(R.string.disable_all_tools), ButtonKind.QUIET, R.id.capabilities_disable_all) { saveSettings(getString(R.string.disable_all_tools_success)) { prefs.setTools(false) } }
        val unlock = ui.section(this, getString(R.string.unlock), getString(R.string.unlock_detail))
        unlock.addView(ui.actionRow(getString(R.string.local_unlock_settings), getString(R.string.local_unlock_settings_detail)) {
            startActivity(Intent(this@MainActivity, UnlockSettingsActivity::class.java))
        }.apply { id = R.id.manage_unlock })
    }
    private fun settingsPage() = ui.column().apply {
        ui.heading(this, getString(R.string.nav_settings), getString(R.string.settings_subtitle))
        BridgeNotificationChannels.ensure(this@MainActivity)
        val notifications = ui.section(this, getString(R.string.call_display_settings), getString(R.string.call_display_detail))
        liveUpdatesToggle = ui.toggle(getString(R.string.live_updates_toggle), getString(R.string.live_updates_toggle_detail), R.id.live_updates_enabled) { enabled ->
            saveSettings {
                if (!enabled || BridgeNotificationChannels.liveUpdateAvailability(this@MainActivity) == LiveUpdateAvailability.AVAILABLE)
                    prefs.liveUpdatesEnabled = enabled
            }
        }.also { notifications.addView(it) }
        liveUpdatesStatus = ui.note(notifications, "").apply { id = R.id.live_updates_status }
        notifications.addView(ui.actionRow(getString(R.string.live_updates_system_settings), getString(R.string.live_updates_system_settings_detail)) {
            val promotion = if (Build.VERSION.SDK_INT >= 36) Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName) else null
            if (promotion?.resolveActivity(packageManager) != null) openSettings(promotion) else openNotificationSettings()
        }.apply { id = R.id.live_updates_system_settings })
        ui.divider(notifications)
        connectionNotificationRow = ui.actionRow(getString(R.string.connection_notification_settings), "") {
            openNotificationChannelSettings(BridgeNotificationChannels.CONNECTION)
        }.also { it.id = R.id.connection_notification_settings; notifications.addView(it) }
        ui.divider(notifications)
        notifications.addView(ui.actionRow(getString(R.string.tool_notification_settings), getString(R.string.tool_notification_settings_detail)) {
            openNotificationChannelSettings(BridgeNotificationChannels.TOOL_CALLS)
        }.apply { id = R.id.tool_notification_settings })
        val updates = ui.section(this, getString(R.string.app_updates))
        updates.addView(ui.text(getString(R.string.current_version, BuildConfig.VERSION_NAME), 16f, true).apply {
            id = R.id.update_current_version
        }, ui.gap(top = 12, bottom = 8))
        updateStatusText = ui.note(updates, "", ui.secondary).apply {
            id = R.id.update_status; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        checkUpdatesButton = ui.addButton(updates, getString(R.string.check_updates), viewId = R.id.check_updates) { updateCheckRequest.start() }
        downloadUpdateButton = ui.addButton(updates, getString(R.string.download), ButtonKind.PRIMARY, R.id.download_update) {
            updateCheckRequest.release?.let { openReleaseLink(it.downloadUrl) }
        }
        updateNotes = ui.note(updates, "").apply { id = R.id.update_notes; visibility = View.GONE }
        updates.addView(ui.actionRow(getString(R.string.view_releases), getString(R.string.view_releases_detail)) {
            openReleaseLink(AppUpdates.RELEASES_URL)
        }.apply { id = R.id.open_releases })
        val essential = ui.section(this, getString(R.string.system_permissions), getString(R.string.system_permissions_detail))
        permissionRow(essential, "accessibility", getString(R.string.permission_accessibility), R.id.system_accessibility_status) { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        permissionRow(essential, "location", getString(R.string.permission_location), R.id.system_location_status) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) openAppSettings()
            else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
        }
        permissionRow(essential, "backgroundLocation", getString(R.string.permission_background_location), R.id.system_background_location_status) { openAppSettings() }
        permissionRow(essential, "locationService", getString(R.string.permission_location_service), R.id.system_location_service_status) { openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        permissionRow(essential, "notifications", getString(R.string.permission_notifications), R.id.system_notification_status) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
            else openNotificationSettings()
        }
        permissionRow(essential, "notificationAccess", getString(R.string.permission_notification_access), R.id.system_notification_access_status) {
            openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        permissionRow(essential, "mediaImages", getString(R.string.permission_media_images), R.id.system_media_images_status) {
            requestMediaPermissions(images = true, videos = false)
        }
        permissionRow(essential, "mediaVideos", getString(R.string.permission_media_videos), R.id.system_media_videos_status, last = true) {
            requestMediaPermissions(images = false, videos = true)
        }
        val background = ui.section(this, getString(R.string.background_operation), getString(R.string.background_operation_detail))
        permissionRow(background, "battery", getString(R.string.battery_optimization), R.id.system_battery_status) { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        permissionRow(background, "background", getString(R.string.system_background), R.id.system_background_status) { openAppSettings() }
        permissionRow(background, "autostart", getString(R.string.vendor_autostart), R.id.system_autostart_status, last = true) { openAppSettings() }
        val enhanced = ui.section(this, getString(R.string.shizuku_optional), getString(R.string.shizuku_optional_detail))
        shizukuToggle = ui.toggle(getString(R.string.shizuku_enhancement), getString(R.string.shizuku_enhancement_detail), R.id.shizuku_enabled) { saveSettings { prefs.shizukuEnabled = it } }
        enhanced.addView(shizukuToggle); ui.divider(enhanced)
        permissionRow(enhanced, "shizuku", "Shizuku", R.id.system_shizuku_status, last = true) {
            runCatching { ShizukuSupport(this@MainActivity).requestPermission() }.onFailure { message(it.message ?: getString(R.string.authorization_failed)) }
        }
        val execution = ui.section(this, getString(R.string.execution_modes), getString(R.string.execution_modes_detail))
        permissionRow(execution, "adb", getString(R.string.permission_adb), R.id.system_adb_status, last = true) {
            runCatching { ShizukuSupport(this@MainActivity).requestPermission() }.onFailure { message(it.message ?: getString(R.string.authorization_failed)) }
        }
        ui.note(this, getString(R.string.app_version_footer, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE))
    }
    private fun renderUpdateStatus() {
        val request = updateCheckRequest
        val release = request.release
        updateStatusText.text = when {
            request.busy -> getString(R.string.checking_updates)
            request.error != null -> request.error
            release != null -> getString(R.string.new_version, release.tag)
            request.checked -> getString(R.string.up_to_date)
            else -> getString(R.string.automatic_update_note)
        }
        updateStatusText.setTextColor(when {
            request.error != null -> ui.warning
            release != null || request.checked -> ui.success
            else -> ui.secondary
        })
        checkUpdatesButton.isEnabled = !request.busy
        checkUpdatesButton.text = when { request.busy -> getString(R.string.checking); request.error != null -> getString(R.string.retry_check); else -> getString(R.string.check_updates) }
        downloadUpdateButton.visibility = if (release == null) View.GONE else View.VISIBLE
        updateNotes.text = release?.notes
        updateNotes.visibility = if (release?.notes.isNullOrBlank()) View.GONE else View.VISIBLE
    }
    private fun updatesChanged() { renderUpdateStatus(); showUpdatePrompt() }
    internal fun checkForUpdates(generation: Int) { updateCheckRequest.onForeground(generation) }
    private fun showUpdatePrompt() {
        if (!resumed || isFinishing) return
        val release = updateCheckRequest.pendingPrompt
        if (updateDialog?.isShowing == true && updateDialogTag == release?.tag) return
        updateDialog?.dismiss()
        val available = release ?: return
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.new_version, available.tag))
            .setMessage(available.notes.ifBlank { getString(R.string.new_version_published) })
            .setNegativeButton(getString(R.string.later)) { _, _ -> updateCheckRequest.dismissPrompt() }
            .setPositiveButton(getString(R.string.download)) { _, _ ->
                updateCheckRequest.dismissPrompt(); openReleaseLink(available.downloadUrl)
            }
            .setOnCancelListener { updateCheckRequest.dismissPrompt() }.create()
        dialog.setOnDismissListener { if (updateDialog === dialog) { updateDialog = null; updateDialogTag = null } }
        updateDialog = ui.showDialog(dialog)
        updateDialogTag = available.tag
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).id = R.id.update_prompt_download
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).id = R.id.update_prompt_later
    }
    private fun openReleaseLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
            .onFailure { message(getString(R.string.open_download_error)) }
    }
    private fun permissionRow(parent: LinearLayout, key: String, label: String, viewId: Int, last: Boolean = false, action: () -> Unit) {
        val row = ui.actionRow(label, getString(R.string.status_loading), action)
        row.detail.id = viewId
        permissionViews[key] = row
        parent.addView(row)
        if (!last) ui.divider(parent)
    }
    private fun pairingCard(parent: LinearLayout) {
        ui.space(parent, 4)
        address = ui.field(getString(R.string.bridge_address), getString(R.string.bridge_url_hint), R.id.pair_address).also {
            it.input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            it.input.imeOptions = EditorInfo.IME_ACTION_NEXT; it.input.setText(draftAddress)
            parent.addView(it)
        }
        code = ui.field(getString(R.string.one_time_code), getString(R.string.pairing_code_hint), R.id.pair_code).also {
            it.input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            it.input.imeOptions = EditorInfo.IME_ACTION_DONE
            it.input.isSaveEnabled = false; it.input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            it.input.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_DONE) { startPairing(); true } else false }
            parent.addView(it)
        }
        httpToggle = ui.toggle(getString(R.string.allow_local_http), getString(R.string.local_http_detail), R.id.pair_allow_http) {
            allowHttp = it
            if (pairingAttempted) validatePairing()
        }.also { it.bind(allowHttp); parent.addView(it) }
        pairingError = ui.text("", 14f, tint = ui.danger).apply {
            id = R.id.pair_error; visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { parent.addView(it, ui.gap(top = 10, bottom = 6)) }
        pairButton = ui.addButton(parent, getString(R.string.pair_connect), ButtonKind.PRIMARY, R.id.pair_submit) { startPairing() }
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
        ui.note(parent, getString(R.string.pairing_note))
    }
    private fun validatePairing(): Boolean {
        val addressError = runCatching { BridgeNetwork.normalizeUrl(address?.input?.text.toString(), allowHttp) }.exceptionOrNull()?.message
        val codeError = if (code?.input?.text.isNullOrBlank()) getString(R.string.pairing_code_required) else null
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
        if (prefs.pendingRevocation != null) { showPairingError(getString(R.string.pending_revocation_detail)); return }
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
            text = when { busy -> getString(R.string.pairing); pending -> getString(R.string.pending_revocation); else -> getString(R.string.pair_connect) }
        }
        address?.input?.isEnabled = !busy; code?.input?.isEnabled = !busy; httpToggle?.toggle?.isEnabled = !busy
    }
    private fun showPairingError(value: String) { pairingError?.apply { text = value; visibility = View.VISIBLE } }
    private fun updateStatus() {
        val identity = prefs.identity
        if ((identity != null) != pairedAtRender) { render(); return }
        val pending = prefs.pendingRevocation != null
        val state = if (identity == null) {
            when { pending -> getString(R.string.pending_revoke_short); BridgeService.linkStatusResource == R.string.connection_identity_invalid -> getString(R.string.needs_repairing); else -> getString(R.string.unpaired) }
        } else BridgeService.linkStatus(this)
        connectionState.text = state
        connectionState.setTextColor(if (state == getString(R.string.connected)) ui.success else ui.ink)
        connectionDetail.visibility = if (identity == null && !pending && !state.contains(getString(R.string.needs_repairing))) View.GONE else View.VISIBLE
        connectionDetail.text = when {
            pending -> getString(R.string.revoke_detail)
            identity == null -> getString(R.string.unpaired_detail)
            state == getString(R.string.connected) -> getString(R.string.connected_detail)
            else -> getString(R.string.reconnecting_detail)
        }
        if (!pairing) pairButton?.apply { isEnabled = !pending; text = if (pending) getString(R.string.pending_revocation) else getString(R.string.pair_connect) }
        val policy = prefs.policy()
        accessState.text = when { policy.pausedAll -> getString(R.string.pause_all_status); policy.pausedOperations -> getString(R.string.paused_operations_access_status); identity == null -> getString(R.string.not_paused_unpaired); else -> getString(R.string.access_open_by_policy) }
        accessState.setTextColor(if (policy.pausedAll || policy.pausedOperations) ui.warning else ui.accent)
        pauseAll.bind(policy.pausedAll); pauseOperations.bind(policy.pausedOperations)
        toolRows.forEach { (tool, row) -> row.bind(tool in policy.tools) }
        legacyToolAliases.forEach { (tool, alias) -> alias.isChecked = tool in policy.tools }
        toolSummary.text = getString(R.string.tool_summary, policy.tools.size, BridgeTools.all.size)
        val apps = policy.apps.filterKeys { it != packageName }.values
        val summary = resources.getQuantityString(R.plurals.app_access_summary_count, apps.size, apps.size, apps.count { it.read }, apps.count { it.operate })
        appSummary.bindDetail(summary)
        shizukuToggle.bind(prefs.shizukuEnabled)
        val liveUpdates = BridgeNotificationChannels.liveUpdateAvailability(this)
        liveUpdatesToggle.toggle.isEnabled = liveUpdates == LiveUpdateAvailability.AVAILABLE
        liveUpdatesToggle.bind(prefs.liveUpdatesEnabled && liveUpdates == LiveUpdateAvailability.AVAILABLE)
        liveUpdatesStatus.text = getString(liveUpdates.messageResource)
        val connectionHidden = getSystemService(android.app.NotificationManager::class.java)
            .getNotificationChannel(BridgeNotificationChannels.CONNECTION)?.importance == android.app.NotificationManager.IMPORTANCE_NONE
        connectionNotificationRow.bindDetail(getString(if (connectionHidden) R.string.connection_notification_hidden else R.string.connection_notification_visible))
        val systemStates = SystemPermissions.read(this)
        permissionViews.forEach { (key, row) ->
            val permission = systemStates.getValue(key)
            row.bindDetail(permission.description, when (permission.allowed) { true -> ui.success; false -> ui.warning; null -> ui.secondary })
        }
    }
    private fun confirmUnpair() {
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.unpair_title))
            .setMessage(getString(R.string.unpair_detail))
            .setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.unpair)) { _, _ ->
                runCatching {
                    val identity = prefs.identity ?: return@runCatching
                    prefs.setFlag("pausedAll", true); prefs.pendingRevocation = identity; prefs.identity = null
                    BridgeService.start(this); render()
                }.onFailure { message(it.message ?: getString(R.string.unpair_error)) }
            }.create()
        ui.showDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.danger)
    }
    private fun saveSettings(success: String? = null, action: () -> Unit) {
        runCatching(action).onSuccess { success?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() } }
            .onFailure { message(it.message ?: getString(R.string.save_error)) }
        updateStatus()
    }
    private fun openAppSettings() { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    private fun openNotificationSettings() { openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
    private fun openNotificationChannelSettings(channel: String) {
        openSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel))
    }
    private fun requestMediaPermissions(images: Boolean, videos: Boolean) {
        val permissions = if (Build.VERSION.SDK_INT >= 33) buildList {
            if (images) add(Manifest.permission.READ_MEDIA_IMAGES)
            if (videos) add(Manifest.permission.READ_MEDIA_VIDEO)
        } else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (permissions.isEmpty()) return
        requestPermissions(permissions.toTypedArray(), 12)
    }
    private fun openSettings(intent: Intent) { runCatching { startActivity(intent) }.onFailure { message(getString(R.string.settings_entry_error)) } }
    private fun message(value: String) { ui.showDialog(AlertDialog.Builder(this).setMessage(value).setPositiveButton(getString(R.string.acknowledge), null).create()) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (prefs.identity != null) BridgeService.start(this)
        updateStatus()
        if (grantResults.isNotEmpty() && grantResults.all { it != PackageManager.PERMISSION_GRANTED }) {
            ui.showDialog(AlertDialog.Builder(this).setMessage(getString(R.string.permission_not_granted))
                .setNegativeButton(getString(R.string.later), null).setPositiveButton(getString(R.string.open_settings)) { _, _ -> if (requestCode == 11) openNotificationSettings() else openAppSettings() }.create())
        }
    }
}
