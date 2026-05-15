package com.shizulu.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

class WirelessAdbKeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val verifying = AtomicBoolean(false)
    private var aggressiveUntil = 0L
    private val heartbeat = object : Runnable {
        override fun run() {
            verifyWirelessAdb()
            handler.postDelayed(this, nextHeartbeatDelay())
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Wireless ADB keep-alive is running"))
        scheduleImmediateHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Wireless ADB keep-alive is running"))
        if (intent?.getBooleanExtra(EXTRA_AGGRESSIVE, false) == true) {
            aggressiveUntil = SystemClock.elapsedRealtime() + AGGRESSIVE_WINDOW_MS
            updateNotification("Boot reconnect active")
        }
        scheduleImmediateHeartbeat()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun verifyWirelessAdb() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_KEEP_ALIVE, false)) {
            stopSelf()
            return
        }

        val shizukuReady = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val pairingCode = prefs.getString(KEY_ADB_PAIRING_CODE, "").orEmpty()
        val port = prefs.getInt(KEY_ADB_PAIR_PORT, 0)
        if (pairingCode.isBlank() || port <= 0) {
            updateNotification(if (shizukuReady) "ShizGuru (Shizuku) is visible; pair Wireless ADB for standalone mode" else "Pair Wireless ADB in Shizulu to enable keep-alive")
            return
        }

        Thread {
            if (!verifying.compareAndSet(false, true)) return@Thread
            val status = runCatching {
                WirelessAdbRunner(applicationContext).test(pairingCode, port)
                if (shizukuReady) "Wireless ADB and ShizGuru (Shizuku) are visible" else "Wireless ADB is connected"
            }.getOrElse {
                if (shizukuReady) "ShizGuru (Shizuku) visible; Wireless ADB reconnecting" else "Wireless ADB keep-alive reconnecting"
            }.also {
                verifying.set(false)
            }
            Handler(Looper.getMainLooper()).post { updateNotification(status) }
        }.start()
    }

    private fun scheduleImmediateHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    private fun nextHeartbeatDelay(): Long {
        return if (SystemClock.elapsedRealtime() < aggressiveUntil) FAST_HEARTBEAT_MS else HEARTBEAT_MS
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Shizulu")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wireless ADB keep-alive",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Shizulu available for Wireless ADB modules."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "wireless_adb_keep_alive"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_AGGRESSIVE = "aggressive"
        private const val FAST_HEARTBEAT_MS = 30 * 1000L
        private const val AGGRESSIVE_WINDOW_MS = 10 * 60 * 1000L
        private const val HEARTBEAT_MS = 15 * 60 * 1000L
        private const val PREFS = "shizulu_settings"
        private const val KEY_KEEP_ALIVE = "wireless_adb_keep_alive"
        private const val KEY_ADB_PAIRING_CODE = "adb_pairing_code"
        private const val KEY_ADB_PAIR_PORT = "adb_pair_port"

        fun start(context: Context, aggressive: Boolean = false) {
            val intent = Intent(context, WirelessAdbKeepAliveService::class.java)
                .putExtra(EXTRA_AGGRESSIVE, aggressive)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WirelessAdbKeepAliveService::class.java))
        }
    }
}
