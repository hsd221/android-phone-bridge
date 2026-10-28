package io.github.hsd221.phonebridge

import android.app.Activity
import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

class AppPermissionsActivity : Activity() {
    private data class AppEntry(val packageName: String, val label: String)
    private data class AppRow(val selection: CheckBox, val configure: Button)

    private lateinit var prefs: BridgePrefs
    private lateinit var search: EditText
    private lateinit var summary: TextView
    private lateinit var bulkButton: Button
    private lateinit var allButton: Button
    private lateinit var selectButton: Button
    private lateinit var scopeNote: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val selected = mutableSetOf<String>()
    private var apps = emptyList<AppEntry>()
    private var filtered = emptyList<AppEntry>()
    private var accesses = emptyMap<String, AppAccess>()
    private var loaded = false
    private val ink = Color.rgb(30, 48, 44)
    private val green = Color.rgb(23, 102, 91)
    private val adapter = object : BaseAdapter() {
        override fun getCount() = filtered.size
        override fun getItem(position: Int) = filtered[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@AppPermissionsActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                val check = CheckBox(context).apply { textSize = 14f; setTextColor(ink); minHeight = dp(88) }
                addView(check, LinearLayout.LayoutParams(0, -2, 1f))
                val configure = Button(context).apply { text = "配置"; isAllCaps = false; setTextColor(green); minWidth = dp(64); minHeight = dp(48) }
                addView(configure, LinearLayout.LayoutParams(dp(72), -2))
                tag = AppRow(check, configure)
            }
            val holder = row.tag as AppRow
            val app = getItem(position)
            holder.selection.apply {
                setOnCheckedChangeListener(null)
                text = getString(R.string.app_permission_row, app.label, app.packageName, accessDescription(accesses[app.packageName] ?: AppAccess()))
                isChecked = app.packageName in selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                    updateSelection()
                }
            }
            holder.configure.contentDescription = "配置 ${app.label} 的权限"
            holder.configure.setOnClickListener { editApp(app.packageName) }
            return row
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setDecorFitsSystemWindows(false)
        prefs = BridgePrefs(this)
        selected.addAll(savedInstanceState?.getStringArrayList("selectedApps") ?: emptyList())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(12))
            setBackgroundColor(Color.rgb(243, 246, 243))
        }
        val heading = row(root)
        heading.addView(text("应用权限", 23f, true), LinearLayout.LayoutParams(0, -2, 1f))
        button(heading, "添加包名", R.id.add_app_package) { manualApp() }
        button(heading, "返回") { finish() }
        search = EditText(this).apply {
            id = R.id.app_permission_search; hint = "搜索应用名或包名"; setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val wideSearchRow = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) row(root) else null
        if (wideSearchRow != null) wideSearchRow.addView(search, LinearLayout.LayoutParams(0, dp(52), 1f))
        else root.addView(search, LinearLayout.LayoutParams(-1, dp(52)))
        summary = text("正在读取应用列表…", 13f).apply { id = R.id.app_permission_summary }
        root.addView(summary)
        val selectionRow = wideSearchRow ?: row(root)
        selectButton = button(selectionRow, "全选搜索结果", R.id.select_filtered_apps, wideSearchRow == null) {
            selected.addAll(filtered.map { it.packageName }); adapter.notifyDataSetChanged(); updateSelection()
        }
        button(selectionRow, "清空选择", R.id.clear_selected_apps, wideSearchRow == null) {
            selected.clear(); adapter.notifyDataSetChanged(); updateSelection()
        }
        val list = ListView(this).apply {
            id = R.id.app_permission_list; adapter = this@AppPermissionsActivity.adapter
            setBackgroundColor(Color.WHITE); dividerHeight = dp(1)
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = row(root)
        bulkButton = button(actions, "批量设置", R.id.batch_app_permissions, true) { batchActions() }
        allButton = button(actions, "一键全部开启", R.id.grant_everything, true) {
            save("已开启全部 ${apps.size} 个应用的读取、操作及四项能力") {
                prefs.setPermissions(apps.associate { it.packageName to AppAccess(read = true, operate = true) }, Capability.entries.toSet())
                selected.clear(); selected.addAll(apps.map { it.packageName })
            }
        }
        scopeNote = text("", 12f)
        root.addView(scopeNote)
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(12) + bars.bottom); insets
        }
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(lightBars, lightBars)
        search.setText(savedInstanceState?.getString("query") ?: "")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterApps() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateSelection()
        scope.launch {
            try {
                apps = withContext(Dispatchers.IO) { loadApps() }
                selected.retainAll(apps.map { it.packageName }.toSet())
                accesses = prefs.policy().apps
                loaded = true; filterApps()
            } catch (_: Exception) {
                summary.text = "应用列表读取失败，请重新打开页面"
            }
        }
    }

    override fun onResume() {
        super.onResume(); BridgeService.activityVisible = true
        if (prefs.identity != null || prefs.pendingRevocation != null) BridgeService.start(this)
        accesses = prefs.policy().apps
        adapter.notifyDataSetChanged()
    }
    override fun onPause() { BridgeService.activityVisible = false; super.onPause() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selectedApps", ArrayList(selected))
        outState.putString("query", search.text.toString())
        super.onSaveInstanceState(outState)
    }

    // The manifest declares launcher/home/keyboard/System UI queries. Other packages may remain
    // invisible on Android 11+; the UI states this scope and preserves manually configured names.
    @SuppressLint("QueryPermissionsNeeded")
    private fun loadApps(): List<AppEntry> {
        val entries = packageManager.getInstalledApplications(0).associate { info ->
            info.packageName to AppEntry(info.packageName, info.loadLabel(packageManager).toString())
        }.toMutableMap()
        // Keep manually configured keyboards/system packages even when Android cannot list them.
        prefs.policy().apps.keys.forEach { pkg -> entries.putIfAbsent(pkg, appEntry(pkg)) }
        entries.remove(packageName)
        return sorted(entries.values)
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
        filtered = apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        adapter.notifyDataSetChanged(); updateSelection()
    }
    private fun updateSelection() {
        if (loaded) {
            val hidden = selected.size - filtered.count { it.packageName in selected }
            summary.text = getString(R.string.app_permission_summary, filtered.size, apps.size, selected.size,
                if (hidden > 0) getString(R.string.app_permission_hidden, hidden) else "")
        }
        bulkButton.text = getString(R.string.app_permission_bulk, selected.size)
        bulkButton.isEnabled = loaded && selected.isNotEmpty()
        selectButton.isEnabled = loaded && filtered.isNotEmpty()
        allButton.isEnabled = loaded
        scopeNote.text = getString(R.string.app_permission_scope, apps.size)
    }
    private fun batchActions() {
        val targets = selected.toSet()
        AlertDialog.Builder(this).setTitle("批量设置 ${targets.size} 个应用")
            .setItems(arrayOf("允许读取（保留操作设置）", "允许操作（保留读取设置）", "读取与操作全部允许", "读取与操作全部禁止")) { _, action ->
                save("已更新 ${targets.size} 个应用") {
                    val current = prefs.policy().apps
                    prefs.setApps(targets.associateWith { pkg ->
                        val access = current[pkg] ?: AppAccess()
                        when (action) {
                            0 -> access.copy(read = true)
                            1 -> access.copy(operate = true)
                            2 -> AppAccess(read = true, operate = true)
                            else -> AppAccess()
                        }
                    })
                }
            }.setNegativeButton("取消", null).show()
    }
    private fun manualApp() {
        val input = EditText(this).apply { hint = "com.example.app"; setSingleLine() }
        val dialog = AlertDialog.Builder(this).setTitle("添加应用、键盘或系统包名").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("配置", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pkg = input.text.toString().trim()
                if (!pkg.matches(Regex("[A-Za-z][\\w]*(\\.[\\w]+)+")) || pkg == packageName) input.error = "请输入其他应用的有效包名"
                else { dialog.dismiss(); editApp(pkg, showInSearch = true) }
            }
        }
        dialog.show()
    }
    private fun editApp(pkg: String, showInSearch: Boolean = false) {
        val app = appEntry(pkg)
        val access = prefs.policy().apps[pkg] ?: AppAccess()
        val checked = booleanArrayOf(access.read, access.operate)
        AlertDialog.Builder(this).setTitle("${app.label}\n$pkg")
            .setMultiChoiceItems(arrayOf("允许读取与截图", "允许打开与操作"), checked) { _, index, value -> checked[index] = value }
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                save("已保存 ${app.label} 的权限") {
                    prefs.setApp(pkg, AppAccess(checked[0], checked[1]))
                    if (apps.none { it.packageName == pkg }) apps = sorted(apps + app)
                    if (showInSearch) search.setText(pkg)
                }
            }.show()
    }
    private fun save(success: String, action: () -> Unit) {
        try {
            action(); accesses = prefs.policy().apps; filterApps()
            Toast.makeText(this, success, Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            AlertDialog.Builder(this).setMessage(error.message ?: "保存失败，请重试").setPositiveButton("知道了", null).show()
        }
    }
    private fun accessDescription(access: AppAccess) = when {
        access.read && access.operate -> "允许读取 · 允许操作"
        access.read -> "允许读取 · 禁止操作"
        access.operate -> "禁止读取 · 允许操作"
        else -> "禁止读取 · 禁止操作"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(ink); setPadding(0, dp(4), 0, dp(4))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun row(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(parent: LinearLayout, label: String, viewId: Int = 0, expand: Boolean = false, action: () -> Unit) = Button(this).apply {
        if (viewId != 0) id = viewId
        text = label; isAllCaps = false; textSize = 14f; setTextColor(green); minHeight = dp(48)
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(if (expand) 0 else -2, -2, if (expand) 1f else 0f))
    }
}
