package com.shizulu.sutester

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF111827.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFFF7F9FC.toInt())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                addView(label("SU Bridge Tester", 22f, true))
                addView(label("Tests Shizulu's opt-in SU Bridge provider and compares it with normal su calls.", 14f, false))
                addView(button("Request Bridge Permission") { requestBridgePermission() }, spaced(top = 16))
                addView(button("Bridge Status") { callBridge("status", null, null) }, spaced())
                addView(button("Bridge exec: id") { callBridge("exec", null, mapOf("command" to "id; echo bridge_exec=ok")) }, spaced())
                addView(button("Bridge su 0 -c id") { callBridge("su", "su 0 -c 'id; echo bridge_su_standard=ok'", null) }, spaced())
                addView(button("Bridge su -c id") { callBridge("su-c", "su -c 'id; echo bridge_su_c=ok'", null) }, spaced())
                addView(button("Custom path /data/local/tmp/su") { runCustomPathSu() }, spaced())
                addView(button("Custom path su 0 -c") { runCustomPathSuUserZero() }, spaced())
                addView(button("Traditional su -c id") { runTraditionalSu() }, spaced())
                addView(output, spaced(top = 16))
            })
        })
        write("Ready.\nPermission: ${permissionState()}\nProvider: content://com.shizulu.manager.su")
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun requestBridgePermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(PERMISSION), 100)
        } else {
            write("Runtime permissions are not needed on this Android version.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        write("Permission result: ${permissionState()}")
    }

    private fun callBridge(method: String, arg: String?, extrasMap: Map<String, String>?) {
        executor.execute {
            val result = runCatching {
                val extras = android.os.Bundle().apply {
                    extrasMap?.forEach { (key, value) -> putString(key, value) }
                    putString("moduleId", "com.shizulu.su_bridge_tester")
                }
                val bundle = contentResolver.call(BRIDGE_URI, method, arg, extras)
                    ?: error("Bridge returned no Bundle.")
                buildString {
                    append("method=").append(method).append('\n')
                    append("success=").append(bundle.getBoolean("success")).append('\n')
                    append("message=").append(bundle.getString("message").orEmpty()).append('\n')
                    append("output:\n").append(bundle.getString("output").orEmpty())
                }
            }.getOrElse {
                "Bridge call failed: ${it.javaClass.simpleName}: ${it.message}\n\nPermission: ${permissionState()}\nInstall Shizulu build 40+ and enable SU Bridge in Tools."
            }
            runOnUiThread { write(result) }
        }
    }

    private fun runTraditionalSu() {
        executor.execute {
            val result = runCatching {
                val process = ProcessBuilder("su", "-c", "id; echo traditional_su=ok")
                    .redirectErrorStream(true)
                    .start()
                val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                val code = process.waitFor()
                "traditional su exit=$code\n$text"
            }.getOrElse {
                "traditional su failed: ${it.javaClass.simpleName}: ${it.message}"
            }
            runOnUiThread { write(result) }
        }
    }

    private fun runCustomPathSu() {
        executor.execute {
            val result = runCatching {
                val process = ProcessBuilder("/data/local/tmp/su", "-c", "id; echo custom_path_su=ok")
                    .redirectErrorStream(true)
                    .start()
                val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                val code = process.waitFor()
                "/data/local/tmp/su exit=$code\n$text"
            }.getOrElse {
                "/data/local/tmp/su failed: ${it.javaClass.simpleName}: ${it.message}\nInstall the Shizulu SU Bridge script first."
            }
            runOnUiThread { write(result) }
        }
    }

    private fun runCustomPathSuUserZero() {
        executor.execute {
            val result = runCatching {
                val process = ProcessBuilder("/data/local/tmp/su", "0", "-c", "id; echo custom_path_su_0=ok")
                    .redirectErrorStream(true)
                    .start()
                val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                val code = process.waitFor()
                "/data/local/tmp/su 0 -c exit=$code\n$text"
            }.getOrElse {
                "/data/local/tmp/su 0 -c failed: ${it.javaClass.simpleName}: ${it.message}\nInstall the Shizulu SU Bridge script first."
            }
            runOnUiThread { write(result) }
        }
    }

    private fun permissionState(): String {
        return if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            "granted"
        } else {
            "not granted"
        }
    }

    private fun write(text: String) {
        output.text = "${timestamp()}\n$text"
    }

    private fun label(textValue: String, size: Float, bold: Boolean): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(0xFF111827.toInt())
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun button(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2563EB.toInt())
            setPadding(dp(14), 0, dp(14), 0)
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun spaced(top: Int = 10): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val PERMISSION = "com.shizulu.manager.permission.SU_BRIDGE"
        private val BRIDGE_URI = Uri.parse("content://com.shizulu.manager.su")
    }
}
