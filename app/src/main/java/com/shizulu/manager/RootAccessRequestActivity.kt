package com.shizulu.manager

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.TextView

class RootAccessRequestActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)

        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val packageLabel = intent.getStringExtra(EXTRA_PACKAGE_LABEL).orEmpty().ifBlank { packageName }
        val method = intent.getStringExtra(EXTRA_METHOD).orEmpty()
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()

        if (requestId.isBlank()) {
            finish()
            return
        }

        val message = buildString {
            append(packageLabel).append(" wants Shizulu access to this phone.\n\n")
            append("If allowed, Shizulu will run compatible root-style requests through the selected privileged backend instead of real root.\n\n")
            append("Package: ").append(packageName.ifBlank { "unknown" }).append('\n')
            append("Method: ").append(method.ifBlank { "unknown" })
            if (command.isNotBlank()) {
                append("\n\nCommand preview:\n").append(command)
            }
        }

        val body = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(0xFF111827.toInt())
            setPadding(dp(22), dp(10), dp(22), 0)
        }

        AlertDialog.Builder(this)
            .setTitle("Grant access to device?")
            .setView(body)
            .setPositiveButton("Grant") { _, _ ->
                decide(requestId, SuBridgeProvider.DECISION_ALLOW)
            }
            .setNegativeButton("Deny") { _, _ ->
                decide(requestId, SuBridgeProvider.DECISION_DENY)
            }
            .setOnCancelListener {
                decide(requestId, SuBridgeProvider.DECISION_DENY)
            }
            .show()
    }

    private fun decide(requestId: String, decision: String) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val editor = prefs.edit()
            .putString("${SuBridgeProvider.KEY_ROOT_DECISION_PREFIX}$requestId", decision)
        if (decision == SuBridgeProvider.DECISION_ALLOW && packageName.matches(PACKAGE_NAME_PATTERN)) {
            val allowed = prefs.getStringSet(SuBridgeProvider.KEY_ALLOWED_ROOT_PACKAGES, emptySet()).orEmpty()
            editor.putStringSet(
                SuBridgeProvider.KEY_ALLOWED_ROOT_PACKAGES,
                allowed.toMutableSet().apply { add(packageName) }
            )
        }
        editor.apply()
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_PACKAGE_LABEL = "package_label"
        const val EXTRA_METHOD = "method"
        const val EXTRA_COMMAND = "command"
        private const val PREFS = "shizulu_settings"
        private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
