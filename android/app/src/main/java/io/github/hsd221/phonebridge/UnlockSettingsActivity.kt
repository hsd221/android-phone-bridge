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
        ui.addButton(body, getString(R.string.back_with_symbol), ButtonKind.QUIET) { finish() }
        ui.heading(body, getString(R.string.local_unlock_title), getString(R.string.local_unlock_subtitle))
        val access = ui.section(body, getString(R.string.separate_authorization), getString(R.string.separate_authorization_detail))
        enabled = ui.toggle(getString(R.string.allow_remote_unlock), getString(R.string.allow_remote_unlock_detail), R.id.unlock_enabled) { value ->
            save {
                requireLocalUser()
                prefs.unlockEnabled = value
            }
        }
        access.addView(enabled)
        status = ui.text("", 14f, true).apply { id = R.id.unlock_status }
        access.addView(status, ui.gap(bottom = 10))
        val credential = ui.section(body, getString(R.string.configure_credentials), getString(R.string.configure_credentials_detail))
        val modes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        UnlockKind.entries.forEach { mode ->
            modes.addView(RadioButton(this).apply {
                id = when (mode) { UnlockKind.PIN -> R.id.unlock_kind_pin; UnlockKind.PASSWORD -> R.id.unlock_kind_password; UnlockKind.PATTERN -> R.id.unlock_kind_pattern }
                text = getString(mode.labelRes); minHeight = ui.dp(48); setTextColor(ui.ink)
                isSaveEnabled = false
                setOnCheckedChangeListener { _, checked -> if (checked) { kind = mode; updateKind() } }
            })
        }
        credential.addView(modes)
        field = ui.field(getString(R.string.pin_password), getString(R.string.credential_hint), R.id.unlock_credential)
        field.input.apply {
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        credential.addView(field)
        patternGrid = ui.column()
        patternGrid.addView(ui.text(getString(R.string.pattern_note), 14f))
        for (row in 0..2) {
            val line = ui.row()
            for (column in 0..2) {
                val index = row * 3 + column + 1
                val point = ui.button(index.toString(), ButtonKind.SECONDARY) {
                    sequence = PatternSequence.normalize(sequence + index)
                    bindPattern()
                }.apply {
                    contentDescription = getString(R.string.pattern_node, index)
                    isSaveEnabled = false
                }
                points.add(point)
                line.addView(point, LinearLayout.LayoutParams(0, ui.dp(60), 1f).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
            }
            patternGrid.addView(line)
        }
        patternState = ui.text("", 14f).apply { id = R.id.unlock_pattern_state }
        patternGrid.addView(patternState, ui.gap(top = 8))
        ui.addButton(patternGrid, getString(R.string.clear_pattern), ButtonKind.QUIET, R.id.unlock_pattern_clear) { sequence = ""; bindPattern() }
        credential.addView(patternGrid)
        ui.addButton(credential, getString(R.string.save_local), ButtonKind.PRIMARY, R.id.unlock_save) {
            save {
                requireLocalUser()
                val value = UnlockCredential(kind, if (kind == UnlockKind.PATTERN) sequence else field.input.text.toString())
                store.save(value)
                clearInput()
                Toast.makeText(this, getString(R.string.credential_saved), Toast.LENGTH_LONG).show()
            }
        }
        val recovery = ui.section(body, getString(R.string.recovery), getString(R.string.recovery_detail))
        ui.addButton(recovery, getString(R.string.reset_attempt), viewId = R.id.unlock_reset_attempt) {
            save { requireLocalUser(); store.allowNextAttempt() }
        }
        ui.addButton(recovery, getString(R.string.delete_credentials), ButtonKind.DANGER, R.id.unlock_delete) {
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
        check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked && getSystemService(UserManager::class.java).isUserUnlocked) { getString(R.string.unlock_user_required) }
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
        patternState.text = resources.getQuantityString(R.plurals.unlock_pattern_points, sequence.length, sequence.length)
    }
    private fun refresh() {
        enabled.bind(prefs.unlockEnabled)
        status.text = when {
            store.retryBlocked -> getString(R.string.unlock_blocked)
            store.configured -> getString(R.string.unlock_saved)
            else -> getString(R.string.unlock_not_saved)
        }
    }
    private fun save(action: () -> Unit) {
        runCatching(action).onFailure {
            // Never reflect crypto/input exceptions, which could include sensitive data.
            val message = if (it is IllegalArgumentException) getString(R.string.credential_format_error) else getString(R.string.unlock_save_error)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        refresh()
    }
}
