package io.github.hsd221.phonebridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** A foreign, unrecognized show-when-locked window, confined to the test APK. */
class UnlockUnknownWindowFixture : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("close", false)) { finish(); return }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(TextView(this).apply {
            text = "Synthetic unknown lock window"
            gravity = Gravity.CENTER
            setBackgroundColor(0xffeeeeee.toInt())
            setTextColor(0xff111111.toInt())
        })
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("close", false)) finish()
    }
}
