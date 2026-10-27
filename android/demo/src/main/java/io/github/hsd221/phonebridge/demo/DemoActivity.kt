package io.github.hsd221.phonebridge.demo

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Separate package: testing cannot grant remote access to the bridge's own settings. */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 72, 32, 32) }
        val status = TextView(this).apply { text = "尚未点击"; textSize = 22f }
        body.addView(TextView(this).apply { text = "手机工具桥测试页"; textSize = 28f })
        val input = EditText(this).apply { hint = "输入测试文字"; contentDescription = "测试输入框"; setSingleLine() }
        body.addView(input)
        body.addView(Button(this).apply { text = "确认测试"; setOnClickListener { status.text = "已确认：${input.text}"; input.clearFocus() } })
        body.addView(status)
        repeat(30) { body.addView(TextView(this).apply { text = "滚动测试行 ${it + 1}"; textSize = 18f; setPadding(0, 24, 0, 24) }) }
        setContentView(ScrollView(this).apply { addView(body) })
    }
}
