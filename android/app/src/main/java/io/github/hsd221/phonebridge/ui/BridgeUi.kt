package io.github.hsd221.phonebridge.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.hsd221.phonebridge.R

/** Native view components. Owns presentation only; callers own state, persistence and navigation. */
class BridgeUi(val context: Context) {
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val contrast = Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
    val background = context.getColor(R.color.bridge_background)
    val surface = context.getColor(R.color.bridge_surface)
    val ink = context.getColor(R.color.bridge_ink)
    val secondary = context.getColor(R.color.bridge_secondary)
    val accent = context.getColor(R.color.bridge_accent)
    val accentSurface = context.getColor(R.color.bridge_accent_surface)
    val onAccent = context.getColor(R.color.bridge_on_accent)
    val success = context.getColor(R.color.bridge_success)
    val warning = context.getColor(R.color.bridge_warning)
    val danger = context.getColor(R.color.bridge_danger)
    val stroke = context.getColor(R.color.bridge_stroke)
    val mutedSurface = context.getColor(R.color.bridge_muted_surface)
    val compact get() = context.resources.configuration.screenHeightDp < 480
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun shape(fill: Int, radius: Int = 22, border: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }
    fun pressSurface(fill: Int, radius: Int = 20) = RippleDrawable(ColorStateList.valueOf(if (dark) 0x22FFFFFF else 0x18000000), shape(fill, radius), shape(Color.WHITE, radius))
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun text(value: String, size: Float = 16f, bold: Boolean = false, tint: Int = ink) = TextView(context).apply {
        text = value; textSize = size; setTextColor(tint)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false; setLineSpacing(dp(if (size >= 28) 2 else 4).toFloat(), 1f)
        if (size >= 28) letterSpacing = -.025f
        else if (size <= 13) letterSpacing = .01f
    }
    fun heading(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(text(title, if (compact) 26f else 32f, true))
        subtitle?.let { parent.addView(text(it, 15f, tint = secondary), gap(top = 10, bottom = 4)) }
        space(parent, if (compact) 16 else 24)
    }
    fun section(parent: LinearLayout, title: String, note: String? = null): LinearLayout {
        parent.addView(text(title, 13f, true, secondary), gap(top = 6, bottom = 9, side = 6))
        val card = column().apply {
            setPadding(dp(18), dp(6), dp(18), dp(6)); background = shape(surface, border = if (contrast) stroke else null)
        }
        parent.addView(card, gap(bottom = 12))
        note?.let { parent.addView(text(it, 13f, tint = secondary), gap(bottom = 22, side = 6)) }
        return card
    }
    fun card(parent: LinearLayout, padding: Int = 20): LinearLayout = column().apply {
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); background = shape(surface, border = if (contrast) stroke else null)
        parent.addView(this, gap(bottom = 18))
    }
    fun space(parent: LinearLayout, height: Int) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }
    fun gap(top: Int = 0, bottom: Int = 0, side: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top); bottomMargin = dp(bottom); leftMargin = dp(side); rightMargin = dp(side)
    }
    fun divider(parent: LinearLayout) { parent.addView(View(context).apply { setBackgroundColor(stroke) }, LinearLayout.LayoutParams(-1, dp(1))) }
    fun note(parent: LinearLayout, value: String, tint: Int = secondary) = text(value, 13f, tint = tint).also { parent.addView(it, gap(top = 8, bottom = 10)) }
    fun scroll(content: LinearLayout, bottom: Int = 24) = ScrollView(context).apply {
        isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false
        setPadding(dp(20), dp(if (compact) 12 else 24), dp(20), dp(bottom))
        addView(content, ViewGroup.LayoutParams(-1, -2))
    }
    fun button(label: String, kind: ButtonKind = ButtonKind.SECONDARY, viewId: Int = View.NO_ID, action: () -> Unit): Button = PressButton(context).apply {
        id = viewId; text = label; isAllCaps = false; textSize = 15f
        typeface = Typeface.create("sans-serif", Typeface.BOLD); gravity = Gravity.CENTER
        minimumHeight = dp(50); minHeight = dp(50); minWidth = dp(48); minimumWidth = dp(48)
        setPadding(dp(14), dp(12), dp(14), dp(12)); stateListAnimator = null
        val fill = when (kind) { ButtonKind.PRIMARY -> accent; ButtonKind.SECONDARY -> accentSurface; ButtonKind.QUIET, ButtonKind.DANGER -> Color.TRANSPARENT }
        val tint = when (kind) { ButtonKind.PRIMARY -> onAccent; ButtonKind.DANGER -> danger; else -> accent }
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(secondary, tint)))
        background = pressSurface(fill, 15)
        setOnClickListener { action() }
    }
    fun addButton(parent: LinearLayout, label: String, kind: ButtonKind = ButtonKind.SECONDARY, viewId: Int = View.NO_ID, action: () -> Unit) =
        button(label, kind, viewId, action).also { parent.addView(it, gap(top = 6, bottom = 6)) }

    fun field(label: String, hintText: String, viewId: Int = View.NO_ID): Field = Field(this, label, hintText, viewId)
    fun toggle(title: String, detail: String, viewId: Int = View.NO_ID, onChange: (Boolean) -> Unit) = ToggleRow(this, title, detail, viewId, onChange)
    fun actionRow(title: String, detail: String, action: () -> Unit): ActionRow = ActionRow(this, title, detail, action)

    /** Attach before configuring InsetsController (Android 15 requires an attached decor view). */
    fun attach(activity: Activity, root: View) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.window.setDecorFitsSystemWindows(false)
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        activity.setContentView(root)
        root.setBackgroundColor(background)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        activity.window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        root.requestApplyInsets()
    }
    fun showDialog(dialog: AlertDialog): AlertDialog {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(shape(surface, 26, if (contrast) stroke else null))
            setLayout(minOf(context.resources.displayMetrics.widthPixels - dp(32), dp(560)), -2)
            setDimAmount(.35f)
        }
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { id ->
            dialog.getButton(id)?.apply { setTextColor(accent); isAllCaps = false; minimumHeight = dp(48) }
        }
        return dialog
    }
    fun modalBody() = column().apply { setPadding(dp(24), dp(10), dp(24), dp(10)) }
    fun chrome() = shape(if (contrast) surface else (surface and 0x00FFFFFF) or 0xF5000000.toInt(), 26, stroke)
}

enum class ButtonKind { PRIMARY, SECONDARY, QUIET, DANGER }

/** Feedback starts on touch-down, cancels when dragged away, and never delays the click. */
private class PressButton(context: Context) : Button(context) {
    private var touching = false
    override fun performClick(): Boolean = super.performClick()
    // super.onTouchEvent owns performClick, including accessibility and drag-away cancellation.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        touching = event.actionMasked != MotionEvent.ACTION_CANCEL && event.actionMasked != MotionEvent.ACTION_UP
        // Scroll containers defer native pressed state; show feedback immediately, leaving click/cancel to Android.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isEnabled) isPressed = true
        val result = super.onTouchEvent(event)
        if (!touching) settle(1f)
        return result
    }
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (touching) settle(if (pressed && isEnabled) .97f else 1f)
    }
    private fun settle(target: Float) {
        animate().cancel()
        // Respect Android's Remove animations setting. Keyboard/accessibility clicks stay immediate.
        if (!ValueAnimator.areAnimatorsEnabled()) { scaleX = 1f; scaleY = 1f; return }
        animate().scaleX(target).scaleY(target).setDuration(if (target < 1f) 100 else 160)
            .setInterpolator(PathInterpolator(.23f, 1f, .32f, 1f)).start()
    }
}

// Constructed programmatically with required design tokens and callbacks.
@SuppressLint("ViewConstructor")
class Field internal constructor(private val ui: BridgeUi, label: String, hintText: String, viewId: Int) : LinearLayout(ui.context) {
    val input = EditText(context).apply {
        id = if (viewId == View.NO_ID) View.generateViewId() else viewId
        hint = hintText; setSingleLine(); textSize = 16f
        setTextColor(ui.ink); setHintTextColor(ui.secondary); background = ui.shape(ui.mutedSurface, 14, ui.stroke)
        setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)); minHeight = ui.dp(54)
    }
    private var validationError: String? = null
    private val error = ui.text("", 13f, tint = ui.danger).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    init {
        orientation = VERTICAL
        addView(ui.text(label, 14f, true).apply { labelFor = input.id }, ui.gap(bottom = 8))
        input.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isContentInvalid = validationError != null; info.error = validationError
            }
        }
        addView(input, ui.gap()); addView(error, ui.gap(top = 6, bottom = 4))
        setPadding(0, 0, 0, ui.dp(14))
    }
    fun error(value: String?) {
        validationError = value
        error.text = value; error.visibility = if (value == null) View.GONE else View.VISIBLE
        input.background = ui.shape(ui.mutedSurface, 14, if (value == null) ui.stroke else ui.danger)
    }
}

/** Labels remain a single accessible Switch target with native checkable semantics. */
// Constructed programmatically with required design tokens and callbacks.
@SuppressLint("ViewConstructor")
class ToggleRow internal constructor(ui: BridgeUi, title: String, detail: String, viewId: Int, onChange: (Boolean) -> Unit) : LinearLayout(ui.context) {
    val toggle = Switch(context).apply {
        id = viewId; text = ""; contentDescription = "$title。$detail"
        // Domain state is rebound by the caller; framework restoration must not write stale grants.
        isSaveEnabled = false
        thumbTintList = ColorStateList.valueOf(ui.surface)
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ui.accent, ui.secondary))
        minimumHeight = ui.dp(48); minimumWidth = ui.dp(52)
    }
    private var binding = false
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(78)
        setPadding(0, ui.dp(13), 0, ui.dp(13))
        val labels = ui.column().apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(ui.text(title, 16f, true)); addView(ui.text(detail, 13f, tint = ui.secondary), ui.gap(top = 5))
        }
        addView(labels, LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(16) })
        addView(toggle, LayoutParams(-2, -2))
        setOnClickListener { if (toggle.isEnabled) toggle.performClick() }
        isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        toggle.setOnCheckedChangeListener { _, checked -> if (!binding) onChange(checked) }
    }
    fun bind(checked: Boolean) { binding = true; try { toggle.isChecked = checked } finally { binding = false } }
}

// Constructed programmatically with required design tokens and callbacks.
@SuppressLint("ViewConstructor")
class ActionRow internal constructor(ui: BridgeUi, private val title: String, description: String, action: () -> Unit) : LinearLayout(ui.context) {
    val detail = ui.text(description, 13f, tint = ui.secondary)
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(76)
        setPadding(0, ui.dp(14), 0, ui.dp(14))
        val labels = ui.column().apply {
            addView(ui.text(title, 16f, true)); addView(this@ActionRow.detail, ui.gap(top = 5))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        addView(labels, LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(16) })
        addView(ui.text("›", 24f, tint = ui.secondary).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO })
        contentDescription = "$title。$description"; isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(0x16000000), null, ui.shape(Color.WHITE, 12))
        setOnClickListener { action() }
    }
    fun bindDetail(value: String, tint: Int? = null) {
        detail.text = value
        tint?.let(detail::setTextColor)
        contentDescription = "$title。$value"
    }
}

/** Centers content without overflowing cutouts or narrow multi-window bounds. */
// Constructed programmatically with required design tokens and callbacks.
@SuppressLint("ViewConstructor")
class WidthBoundFrame(context: Context, private val maxWidthDp: Int = 720) : android.widget.FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bound = (maxWidthDp * resources.displayMetrics.density).toInt()
        super.onMeasure(MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(widthMeasureSpec), bound), MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}
