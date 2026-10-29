package io.github.hsd221.phonebridge

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.UserManager
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import io.github.hsd221.phonebridge.ui.BridgeUi
import io.github.hsd221.phonebridge.ui.ButtonKind
import io.github.hsd221.phonebridge.ui.Field
import io.github.hsd221.phonebridge.ui.ToggleRow
import io.github.hsd221.phonebridge.ui.WidthBoundFrame

/** Non-exported, secure, local-only configuration. Existing secrets are never filled back in. */
class UnlockSettingsActivity : Activity() {
    private lateinit var ui: BridgeUi
    private lateinit var prefs: BridgePrefs
    private lateinit var store: LocalUnlockStore
    private lateinit var enabled: ToggleRow
    private lateinit var field: Field
    private lateinit var patternGrid: LinearLayout
    private lateinit var patternState: android.widget.TextView
    private lateinit var status: android.widget.TextView
    private val points = mutableListOf<Button>()
    private var sequence = ""
    private var kind = UnlockKind.PIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        prefs = BridgePrefs(this); store = LocalUnlockStore(this); ui = BridgeUi(this)
        val body = ui.column()
        body.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        ui.addButton(body, "‹ 返回", ButtonKind.QUIET) { finish() }
        ui.heading(body, "本地解锁", "凭据只保存在这台手机，不发送给 Bridge 或 AI。")
        val access = ui.section(body, "单独授权", "同时需要“操作应用”能力与无障碍服务。暂停操作或暂停全部访问会停止解锁；一键全开不会开启此授权。")
        enabled = ui.toggle("允许远程请求解锁", "允许已配对的 AI 唤醒并使用本地配置解锁手机。", R.id.unlock_enabled) { value ->
            save {
                requireLocalUser()
                prefs.unlockEnabled = value
            }
        }
        access.addView(enabled)
        status = ui.text("", 14f, true).apply { id = R.id.unlock_status }
        access.addView(status, ui.gap(bottom = 10))
        val credential = ui.section(body, "在手机上配置凭据", "选择与系统锁屏相同的方式。此设置不会修改系统锁屏。无密码或受信任设备可不保存凭据；指纹、人脸仍由系统处理，需要时请在手机上完成。")
        val modes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        UnlockKind.entries.forEach { mode ->
            modes.addView(RadioButton(this).apply {
                id = when (mode) { UnlockKind.PIN -> R.id.unlock_kind_pin; UnlockKind.PASSWORD -> R.id.unlock_kind_password; UnlockKind.PATTERN -> R.id.unlock_kind_pattern }
                text = mode.label; minHeight = ui.dp(48); setTextColor(ui.ink)
                isSaveEnabled = false
                setOnCheckedChangeListener { _, checked -> if (checked) { kind = mode; updateKind() } }
            })
        }
        credential.addView(modes)
        field = ui.field("PIN / 密码", "输入当前锁屏凭据", R.id.unlock_credential)
        field.input.apply {
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        credential.addView(field)
        patternGrid = ui.column()
        patternGrid.addView(ui.text("按解锁顺序依次点选；跨过的中点会按 Android 规则自动加入。", 14f))
        for (row in 0..2) {
            val line = ui.row()
            for (column in 0..2) {
                val index = row * 3 + column + 1
                val point = ui.button(index.toString(), ButtonKind.SECONDARY) {
                    sequence = PatternSequence.normalize(sequence + index)
                    bindPattern()
                }.apply {
                    contentDescription = "图案节点 $index"
                    isSaveEnabled = false
                }
                points.add(point)
                line.addView(point, LinearLayout.LayoutParams(0, ui.dp(60), 1f).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
            }
            patternGrid.addView(line)
        }
        patternState = ui.text("", 14f).apply { id = R.id.unlock_pattern_state }
        patternGrid.addView(patternState, ui.gap(top = 8))
        ui.addButton(patternGrid, "重新选择图案", ButtonKind.QUIET, R.id.unlock_pattern_clear) { sequence = ""; bindPattern() }
        credential.addView(patternGrid)
        ui.addButton(credential, "保存到本机", ButtonKind.PRIMARY, R.id.unlock_save) {
            save {
                requireLocalUser()
                val value = UnlockCredential(kind, if (kind == UnlockKind.PATTERN) sequence else field.input.text.toString())
                // A changed credential always needs an explicit fresh opt-in.
                prefs.unlockEnabled = false
                store.save(value)
                clearInput()
                Toast.makeText(this, "已加密保存在本机，请按需要开启解锁授权", Toast.LENGTH_LONG).show()
            }
        }
        val recovery = ui.section(body, "失败与恢复", "仅识别支持的系统锁屏。输入失败、中断或结果不明后会禁止继续自动输入，请手动解锁并在这里检查配置。重启后的首次解锁需手动完成。")
        ui.addButton(recovery, "检查后重新允许尝试", viewId = R.id.unlock_reset_attempt) {
            save { requireLocalUser(); store.allowNextAttempt() }
        }
        ui.addButton(recovery, "删除本地凭据并关闭授权", ButtonKind.DANGER, R.id.unlock_delete) {
            save { requireLocalUser(); prefs.unlockEnabled = false; store.clear(); clearInput() }
        }
        val root = FrameLayout(this)
        root.addView(WidthBoundFrame(this).apply { addView(ui.scroll(body)) }, FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER_HORIZONTAL))
        ui.attach(this, root)
        modes.check(R.id.unlock_kind_pin)
        updateKind(); refresh()
    }
    override fun onResume() {
        super.onResume()
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) { finish(); return }
        refresh()
    }
    override fun onPause() { clearInput(); super.onPause() }
    private fun requireLocalUser() {
        check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked && getSystemService(UserManager::class.java).isUserUnlocked) { "请先在手机上解锁后配置" }
    }
    private fun clearInput() { field.input.text.clear(); sequence = ""; bindPattern() }
    private fun updateKind() {
        clearInput()
        field.visibility = if (kind == UnlockKind.PATTERN) View.GONE else View.VISIBLE
        patternGrid.visibility = if (kind == UnlockKind.PATTERN) View.VISIBLE else View.GONE
        field.input.inputType = if (kind == UnlockKind.PIN) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    private fun bindPattern() {
        points.forEachIndexed { index, button ->
            val order = sequence.indexOf(('1'.code + index).toChar())
            button.text = if (order >= 0) "${index + 1} · ${order + 1}" else (index + 1).toString()
            button.isEnabled = order < 0
        }
        patternState.text = getString(R.string.unlock_pattern_count, sequence.length)
    }
    private fun refresh() {
        enabled.bind(prefs.unlockEnabled)
        status.text = when {
            store.retryBlocked -> "自动输入已阻止 · 请检查配置后重新允许尝试"
            store.configured -> "已保存本地加密凭据"
            else -> "未保存凭据 · 可唤醒并请求系统解锁"
        }
    }
    private fun save(action: () -> Unit) {
        runCatching(action).onFailure {
            // Never reflect crypto/input exceptions, which could include sensitive data.
            val message = if (it is IllegalArgumentException) "凭据格式无效：PIN 为 4–16 位数字；密码为 4–64 个字符；图案至少 4 个点" else "无法保存，请确认手机已解锁后重试"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        refresh()
    }
}
