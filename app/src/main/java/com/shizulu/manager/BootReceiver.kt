package com.shizulu.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_ALIVE, false)
        if (enabled) WirelessAdbKeepAliveService.start(context)
    }

    companion object {
        private const val PREFS = "shizulu_settings"
        private const val KEY_KEEP_ALIVE = "wireless_adb_keep_alive"
    }
}
