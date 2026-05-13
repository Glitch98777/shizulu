package com.shizulu.manager

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SuBridgeExecutor(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun execute(moduleId: String, command: String): String {
        require(command.isNotBlank()) { "Command is required." }
        require(command.length <= MAX_COMMAND_LENGTH) { "Command too long." }

        return when (ExecutionMode.from(prefs.getString(KEY_EXECUTION_MODE, null))) {
            ExecutionMode.WIRELESS_ADB -> executeWirelessAdb(moduleId, command)
            ExecutionMode.SHIZUKU -> executeShizuku(moduleId, command)
        }
    }

    fun status(): String {
        val mode = ExecutionMode.from(prefs.getString(KEY_EXECUTION_MODE, null))
        return buildString {
            append("SU Bridge: ")
            append(if (prefs.getBoolean(KEY_SU_BRIDGE_ENABLED, false)) "enabled" else "disabled")
            append('\n')
            append("Backend: ").append(mode.label).append('\n')
            append("ADB elevation: use Max ADB Elevation in Tools to grant shell-accessible permissions and appops.\n")
            when (mode) {
                ExecutionMode.WIRELESS_ADB -> {
                    append("Wireless ADB configured: ")
                    append(prefs.getString(KEY_ADB_PAIRING_CODE, "").orEmpty().isNotBlank() && prefs.getInt(KEY_ADB_PAIR_PORT, 0) > 0)
                }
                ExecutionMode.SHIZUKU -> {
                    append("Shizuku visible: ").append(runCatching { Shizuku.pingBinder() }.getOrDefault(false)).append('\n')
                    append("Shizuku permission: ").append(hasShizukuPermission())
                }
            }
        }
    }

    private fun executeWirelessAdb(moduleId: String, command: String): String {
        val pairingCode = prefs.getString(KEY_ADB_PAIRING_CODE, "").orEmpty()
        val port = prefs.getInt(KEY_ADB_PAIR_PORT, 0)
        require(pairingCode.isNotBlank() && port > 0) { "Wireless ADB is not configured." }
        return WirelessAdbRunner(appContext).runCommand(moduleId, command, pairingCode, port).output
    }

    private fun executeShizuku(moduleId: String, command: String): String {
        require(runCatching { Shizuku.pingBinder() }.getOrDefault(false)) { "Shizuku is not connected." }
        require(hasShizukuPermission()) { "Shizuku permission is not granted." }

        val latch = CountDownLatch(1)
        val serviceRef = AtomicReference<IShizuluService?>()
        val errorRef = AtomicReference<Throwable?>()
        val args = Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, ShizuluUserService::class.java.name)
        )
            .daemon(false)
            .debuggable(BuildConfig.DEBUG)
            .processNameSuffix("su_bridge")
            .version(1)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                serviceRef.set(IShizuluService.Stub.asInterface(binder))
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                serviceRef.set(null)
            }

            override fun onBindingDied(name: ComponentName) {
                errorRef.set(IllegalStateException("Shizulu service binding died."))
                latch.countDown()
            }
        }

        Shizuku.bindUserService(args, connection)
        try {
            if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                error("Timed out binding Shizulu service.")
            }
            errorRef.get()?.let { throw it }
            val service = serviceRef.get() ?: error("Shizulu service did not connect.")
            return service.runShizuleCommand(moduleId, command)
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    private fun hasShizukuPermission(): Boolean {
        return runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    companion object {
        const val KEY_SU_BRIDGE_ENABLED = "su_bridge_enabled"
        private const val PREFS = "shizulu_settings"
        private const val KEY_EXECUTION_MODE = "execution_mode"
        private const val KEY_ADB_PAIRING_CODE = "adb_pairing_code"
        private const val KEY_ADB_PAIR_PORT = "adb_pair_port"
        private const val MAX_COMMAND_LENGTH = 4096
        private const val BIND_TIMEOUT_MS = 12_000L
    }
}
