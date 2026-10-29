package io.github.hsd221.phonebridge

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/** Only in the test APK, whose package is distinct from the bridge application. */
class UnlockOverlayFixture : AccessibilityService() {
    private var overlay: TextView? = null
    override fun onServiceConnected() {
        val manager = getSystemService(WindowManager::class.java)
        overlay = TextView(this).apply {
            text = "Synthetic unlock obstruction"
            gravity = Gravity.CENTER
            setBackgroundColor(0xffeeeeee.toInt())
            setTextColor(0xff111111.toInt())
        }.also { view ->
            manager.addView(view, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE,
            ))
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
        super.onDestroy()
    }
}
