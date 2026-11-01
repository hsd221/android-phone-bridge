package io.github.hsd221.phonebridge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import io.github.hsd221.phonebridge.ui.BridgeUi
import io.github.hsd221.phonebridge.ui.ButtonKind
import io.github.hsd221.phonebridge.ui.WidthBoundFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

class AppPermissionsActivity : Activity() {
    private data class AppEntry(val packageName: String, val label: String)
    private data class AppRow(val selection: CheckBox, val name: TextView, val pkg: TextView, val access: TextView, val configure: Button)
    private lateinit var ui: BridgeUi
    private lateinit var prefs: BridgePrefs
    private lateinit var search: EditText
    private lateinit var summary: TextView
    private lateinit var bulkButton: Button
    private lateinit var allButton: Button
    private lateinit var selectButton: Button
    private lateinit var clearButton: Button
    private lateinit var scopeNote: TextView
    private lateinit var emptyState: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val selected = mutableSetOf<String>()
    private var apps = emptyList<AppEntry>()
    private var filtered = emptyList<AppEntry>()
    private var accesses = emptyMap<String, AppAccess>()
    private var loaded = false
    private val adapter = object : BaseAdapter() {
        override fun getCount() = filtered.size
        override fun getItem(position: Int) = filtered[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: ui.row().apply {
                minimumHeight = ui.dp(106); setPadding(ui.dp(8), ui.dp(12), ui.dp(12), ui.dp(12))
                val check = CheckBox(context).apply {
                    minimumHeight = ui.dp(48); minimumWidth = ui.dp(48)
                    buttonTintList = ColorStateList.valueOf(ui.accent)
                }
                addView(check, LinearLayout.LayoutParams(ui.dp(48), -2))
                val labels = ui.column().apply {
                    setOnClickListener { check.performClick() }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    background = ui.pressSurface(android.graphics.Color.TRANSPARENT, 12)
                }
                val name = ui.text("", 16f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
                val pkg = ui.text("", 12f, tint = ui.secondary).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
                val access = ui.text("", 13f)
                labels.addView(name); labels.addView(pkg, ui.gap(top = 5)); labels.addView(access, ui.gap(top = 6))
                addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                val configure = ui.button(getString(R.string.app_permission), ButtonKind.SECONDARY) { }
                addView(configure, LinearLayout.LayoutParams(-2, -2))
                tag = AppRow(check, name, pkg, access, configure)
            }
            val holder = row.tag as AppRow
            val app = getItem(position)
            val access = accesses[app.packageName] ?: AppAccess()
            holder.name.text = app.label; holder.pkg.text = app.packageName
            holder.access.text = accessDescription(access)
            holder.access.setTextColor(if (access.read || access.operate) ui.success else ui.secondary)
            holder.selection.apply {
                setOnCheckedChangeListener(null); contentDescription = getString(R.string.select_app, app.label, app.packageName, accessDescription(access))
                isChecked = app.packageName in selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                    updateSelection()
                }
            }
            holder.configure.contentDescription = getString(R.string.configure_app, app.label, accessDescription(access))
            holder.configure.setOnClickListener { editApp(app.packageName) }
            return row
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = BridgeUi(this); prefs = BridgePrefs(this)
        selected.addAll(savedInstanceState?.getStringArrayList("selectedApps") ?: emptyList())
        val root = FrameLayout(this)
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(if (ui.compact) 4 else 12), ui.dp(20), ui.dp(10)) }
        val stage = WidthBoundFrame(this, 760)
        stage.addView(body, FrameLayout.LayoutParams(-1, -1))
        root.addView(stage, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        val enlargedText = resources.configuration.fontScale >= 1.3f
        val heading = ui.row()
        val back = ui.button(if (enlargedText) "‹" else getString(R.string.back_with_symbol), ButtonKind.QUIET) { finish() }.apply { contentDescription = getString(R.string.back) }
        heading.addView(back, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(4) })
        heading.addView(ui.text(getString(R.string.app_permissions_title), if (ui.compact || enlargedText) 20f else 24f, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ui.button(if (enlargedText) "+" else getString(R.string.add_package), ButtonKind.QUIET, R.id.add_app_package) { manualApp() }.apply { contentDescription = getString(R.string.add_package) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        body.addView(heading, ui.gap(bottom = if (ui.compact) 2 else 14))
        search = EditText(this).apply {
            id = R.id.app_permission_search; hint = getString(R.string.search_apps); contentDescription = getString(R.string.search_apps); setSingleLine()
            textSize = 16f; setTextColor(ui.ink); setHintTextColor(ui.secondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = ui.shape(ui.surface, 15, ui.stroke)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); minHeight = ui.dp(52)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0); true
                } else false
            }
        }
        val searchRow = ui.row()
        searchRow.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(searchRow, ui.gap())
        // Keep one native scrolling surface. Controls flow with the list so text size and IME
        // cannot squeeze the catalog to zero height; only the common batch action stays docked.
        val listArea = FrameLayout(this).apply { background = ui.shape(ui.surface); clipToOutline = true }
        val list = ListView(this).apply {
            id = R.id.app_permission_list
            divider = android.graphics.drawable.ColorDrawable(ui.stroke); dividerHeight = ui.dp(1)
            isVerticalScrollBarEnabled = false; setHeaderDividersEnabled(false)
        }
        val controls = ui.column().apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8)) }
        val selectionRow = ui.row()
        selectButton = ui.button(getString(R.string.select_filtered), ButtonKind.QUIET, R.id.select_filtered_apps) {
            selected.addAll(filtered.map { it.packageName }); adapter.notifyDataSetChanged(); updateSelection()
        }
        clearButton = ui.button(getString(R.string.clear_selection), ButtonKind.QUIET, R.id.clear_selected_apps) {
            selected.clear(); adapter.notifyDataSetChanged(); updateSelection()
        }
        selectionRow.addView(selectButton, LinearLayout.LayoutParams(0, -2, 1f))
        selectionRow.addView(clearButton, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(selectionRow)
        summary = ui.text(getString(R.string.loading_apps), 13f, tint = ui.secondary).apply {
            id = R.id.app_permission_summary; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        controls.addView(summary, ui.gap(top = 7, bottom = 9, side = 2))
        bulkButton = ui.button(getString(R.string.batch_settings), ButtonKind.PRIMARY, R.id.batch_app_permissions) { batchActions() }
        allButton = ui.button(getString(R.string.enable_all_apps), ButtonKind.SECONDARY, R.id.grant_everything) {
            save(resources.getQuantityString(R.plurals.enable_all_apps_success_count, apps.size, apps.size)) {
                prefs.setPermissions(apps.associate { it.packageName to AppAccess(read = true, operate = true) }, Capability.entries.toSet())
                selected.clear(); selected.addAll(apps.map { it.packageName })
            }
        }
        if (enlargedText) controls.addView(bulkButton, ui.gap(bottom = 8))
        controls.addView(allButton, ui.gap())
        scopeNote = ui.text("", 12f, tint = ui.secondary)
        controls.addView(scopeNote, ui.gap(top = 8, bottom = 12, side = 2))
        emptyState = ui.text(getString(R.string.loading_apps), 16f, tint = ui.secondary).apply {
            id = R.id.app_permission_empty
            gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(24), ui.dp(12), ui.dp(24))
        }
        controls.addView(emptyState, ui.gap())
        list.addHeaderView(controls, null, false)
        list.adapter = adapter
        listArea.addView(list, FrameLayout.LayoutParams(-1, -1))
        body.addView(listArea, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) })
        if (!enlargedText) body.addView(bulkButton, ui.gap(top = 10))
        ui.attach(this, root)
        search.setText(savedInstanceState?.getString("query") ?: "")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterApps() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateSelection(); loadCatalog()
    }
    private fun loadCatalog() {
        scope.launch {
            try {
                apps = withContext(Dispatchers.IO) { loadApps() }
                selected.retainAll(apps.map { it.packageName }.toSet())
                accesses = prefs.policy().apps; loaded = true; filterApps()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { summary.text = getString(R.string.catalog_error); emptyState.setText(R.string.catalog_load_error) }
        }
    }
    override fun onResume() {
        super.onResume(); BridgeService.activityVisible = true
        if (prefs.identity != null || prefs.pendingRevocation != null) BridgeService.start(this)
        accesses = prefs.policy().apps; adapter.notifyDataSetChanged()
    }
    override fun onPause() { BridgeService.activityVisible = false; super.onPause() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selectedApps", ArrayList(selected)); outState.putString("query", search.text.toString())
        super.onSaveInstanceState(outState)
    }
    // Android 11+ may hide non-launcher packages; keep configured packages and state this scope.
    @SuppressLint("QueryPermissionsNeeded")
    private fun loadApps(): List<AppEntry> {
        val entries = packageManager.getInstalledApplications(0).associate { info ->
            info.packageName to AppEntry(info.packageName, info.loadLabel(packageManager).toString())
        }.toMutableMap()
        prefs.policy().apps.keys.forEach { pkg -> entries.putIfAbsent(pkg, appEntry(pkg)) }
        entries.remove(packageName); return sorted(entries.values)
    }
    private fun appEntry(pkg: String): AppEntry {
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
        catch (_: PackageManager.NameNotFoundException) { pkg }
        return AppEntry(pkg, label)
    }
    private fun sorted(entries: Collection<AppEntry>): List<AppEntry> {
        val collator = Collator.getInstance()
        return entries.sortedWith { a, b -> collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName) }
    }
    private fun filterApps() {
        val query = search.text.toString().trim()
        filtered = apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
        emptyState.text = if (query.isBlank()) getString(R.string.empty_apps) else getString(R.string.no_matching_apps)
        emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged(); updateSelection()
    }
    private fun updateSelection() {
        if (loaded) {
            val hidden = selected.size - filtered.count { it.packageName in selected }
            summary.text = resources.getQuantityString(R.plurals.app_permission_summary_count, filtered.size, filtered.size, apps.size, selected.size,
                if (hidden > 0) getString(R.string.app_permission_hidden, hidden) else "")
        }
        bulkButton.text = getString(R.string.app_permission_bulk, selected.size)
        bulkButton.isEnabled = loaded && selected.isNotEmpty()
        selectButton.isEnabled = loaded && filtered.isNotEmpty(); clearButton.isEnabled = selected.isNotEmpty(); allButton.isEnabled = loaded
        scopeNote.text = if (ui.compact) resources.getQuantityString(R.plurals.app_permission_scope_compact_count, apps.size, apps.size)
        else resources.getQuantityString(R.plurals.app_permission_scope_count, apps.size, apps.size)
    }
    private fun batchActions() {
        val targets = selected.toSet()
        ui.showDialog(AlertDialog.Builder(this).setTitle(resources.getQuantityString(R.plurals.batch_title_count, targets.size, targets.size))
            .setItems(arrayOf(getString(R.string.allow_read_keep_operate), getString(R.string.allow_operate_keep_read), getString(R.string.allow_both), getString(R.string.deny_both))) { _, action ->
                save(resources.getQuantityString(R.plurals.apps_updated_count, targets.size, targets.size)) {
                    val current = prefs.policy().apps
                    prefs.setApps(targets.associateWith { pkg ->
                        val access = current[pkg] ?: AppAccess()
                        when (action) { 0 -> access.copy(read = true); 1 -> access.copy(operate = true); 2 -> AppAccess(true, true); else -> AppAccess() }
                    })
                }
            }.setNegativeButton(getString(R.string.cancel), null).create())
    }
    private fun manualApp() {
        val body = ui.modalBody()
        val field = ui.field(getString(R.string.package_name), getString(R.string.package_name_hint), R.id.manual_package)
        field.input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        body.addView(field)
        ui.note(body, getString(R.string.add_package_note))
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.add_package)).setView(body)
            .setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.configure), null).create()
        ui.showDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pkg = field.input.text.toString().trim()
            if (!pkg.matches(Regex("[A-Za-z][\\w]*(\\.[\\w]+)+")) || pkg == packageName) { field.error(getString(R.string.invalid_package)); field.input.requestFocus() }
            else { dialog.dismiss(); editApp(pkg, showInSearch = true) }
        }
    }
    private fun editApp(pkg: String, showInSearch: Boolean = false) {
        val app = appEntry(pkg)
        val access = prefs.policy().apps[pkg] ?: AppAccess()
        val body = ui.modalBody()
        body.addView(ui.text(pkg, 13f, tint = ui.secondary))
        val read = ui.toggle(getString(R.string.allow_read_screenshot), getString(R.string.read_screenshot_note), R.id.app_read) { }
        val operate = ui.toggle(getString(R.string.allow_open_operate), getString(R.string.operate_note), R.id.app_operate) { }
        read.bind(access.read); operate.bind(access.operate)
        body.addView(read); ui.divider(body); body.addView(operate)
        val dialog = AlertDialog.Builder(this).setTitle(app.label).setView(ui.scroll(body, bottom = 8).apply { isFillViewport = false })
            .setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.save), null).create()
        ui.showDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (save(getString(R.string.app_saved, app.label)) {
                prefs.setApp(pkg, AppAccess(read.toggle.isChecked, operate.toggle.isChecked))
                if (apps.none { it.packageName == pkg }) apps = sorted(apps + app)
                if (showInSearch) search.setText(pkg)
            }) dialog.dismiss()
        }
    }
    private fun save(success: String, action: () -> Unit): Boolean {
        return try {
            action(); accesses = prefs.policy().apps; filterApps()
            Toast.makeText(this, success, Toast.LENGTH_SHORT).show(); true
        } catch (error: Exception) {
            ui.showDialog(AlertDialog.Builder(this).setMessage(error.message ?: getString(R.string.save_error)).setPositiveButton(getString(R.string.acknowledge), null).create()); false
        }
    }
    private fun accessDescription(access: AppAccess) = when {
        access.read && access.operate -> getString(R.string.allow_read_operate)
        access.read -> getString(R.string.allow_read_deny_operate)
        access.operate -> getString(R.string.deny_read_allow_operate)
        else -> getString(R.string.deny_read_operate)
    }
}
