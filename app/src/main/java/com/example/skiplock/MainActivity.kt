package com.example.skiplock

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var lockInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 16f }
        lockInput = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(Button(this@MainActivity).apply {
                text = "Open accessibility settings"
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
            addView(TextView(this@MainActivity).apply {
                text = "Lock duration (minutes)"
                setPadding(0, pad, 0, 0)
            })
            addView(lockInput)
            addView(Switch(this@MainActivity).apply {
                text = "Always show countdown timer"
                isChecked = alwaysShowTimer
                setPadding(0, pad, 0, 0)
                setOnCheckedChangeListener { _, checked -> alwaysShowTimer = checked }
            })
            addView(Switch(this@MainActivity).apply {
                text = "Block subscribe button"
                isChecked = blockSubscribe
                setPadding(0, pad, 0, 0)
                setOnCheckedChangeListener { _, checked -> blockSubscribe = checked }
            })
        })
    }

    override fun onResume() {
        super.onResume()
        lockInput.setText(lockMinutes.toString())
        val serviceOn = getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == SkipLockService::class.java.name }
        status.text = "Accessibility service: ${if (serviceOn) "ON" else "OFF"}\n\n" +
            "Turn on \"Skip Lock\" under Installed apps."
    }

    override fun onPause() {
        super.onPause()
        lockMinutes = lockInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: lockMinutes
    }
}
