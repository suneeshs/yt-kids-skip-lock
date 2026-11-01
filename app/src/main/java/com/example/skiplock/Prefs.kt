package com.example.skiplock

import android.content.Context

private const val DEFAULT_LOCK_MINUTES = 3

private fun Context.prefs() = getSharedPreferences("skiplock", Context.MODE_PRIVATE)

var Context.lockMinutes: Int
    get() = prefs().getInt("lock_minutes", DEFAULT_LOCK_MINUTES)
    set(value) = prefs().edit().putInt("lock_minutes", value).apply()

// false: the countdown only flashes when a blocked area is tapped.
var Context.alwaysShowTimer: Boolean
    get() = prefs().getBoolean("always_show_timer", false)
    set(value) = prefs().edit().putBoolean("always_show_timer", value).apply()

var Context.blockSubscribe: Boolean
    get() = prefs().getBoolean("block_subscribe", false)
    set(value) = prefs().edit().putBoolean("block_subscribe", value).apply()
