package com.shizulu.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

class SuBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: return bridgeBundle(false, "Provider context is unavailable.", "")
        val enabled = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(SuBridgeExecutor.KEY_SU_BRIDGE_ENABLED, false)
        val executor = SuBridgeExecutor(context)

        return when (method) {
            METHOD_STATUS -> bridgeBundle(true, executor.status(), "")
            METHOD_EXEC -> {
                if (!enabled) return bridgeBundle(false, "SU Bridge endpoint is disabled in Shizulu.", "")
                val command = extras?.getString(EXTRA_COMMAND) ?: arg.orEmpty()
                val moduleId = extras?.getString(EXTRA_MODULE_ID).orEmpty().ifBlank { DEFAULT_MODULE_ID }
                runCatching { executor.execute(moduleId, command) }
                    .fold(
                        onSuccess = { output -> bridgeBundle(true, "OK", output) },
                        onFailure = { bridgeBundle(false, it.message ?: it.javaClass.simpleName, "") }
                    )
            }
            METHOD_SU_C -> {
                if (!enabled) return bridgeBundle(false, "SU Bridge endpoint is disabled in Shizulu.", "")
                val raw = extras?.getString(EXTRA_COMMAND) ?: arg.orEmpty()
                val command = parseSuCommand(raw)
                    ?: return bridgeBundle(false, "Use su -c <command>.", "")
                val moduleId = extras?.getString(EXTRA_MODULE_ID).orEmpty().ifBlank { DEFAULT_MODULE_ID }
                runCatching { executor.execute(moduleId, command) }
                    .fold(
                        onSuccess = { output -> bridgeBundle(true, "OK", output) },
                        onFailure = { bridgeBundle(false, it.message ?: it.javaClass.simpleName, "") }
                    )
            }
            else -> bridgeBundle(false, "Unknown SU Bridge method: $method", "")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun bridgeBundle(success: Boolean, message: String, output: String): Bundle {
        return Bundle().apply {
            putBoolean("success", success)
            putString("message", message)
            putString("output", output)
        }
    }

    private fun parseSuCommand(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("su")) return null
        val afterSu = trimmed.removePrefix("su").trimStart()
        val command = when {
            afterSu.startsWith("-c ") -> afterSu.removePrefix("-c")
            afterSu == "-c" -> ""
            afterSu.startsWith("--command ") -> afterSu.removePrefix("--command")
            else -> return null
        }
        return command.trim().trimMatchingQuotes().takeIf { it.isNotBlank() }
    }

    private fun String.trimMatchingQuotes(): String {
        if (length >= 2 && ((first() == '\'' && last() == '\'') || (first() == '"' && last() == '"'))) {
            return substring(1, length - 1)
        }
        return this
    }

    companion object {
        const val METHOD_STATUS = "status"
        const val METHOD_EXEC = "exec"
        const val METHOD_SU_C = "su-c"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_MODULE_ID = "moduleId"
        private const val DEFAULT_MODULE_ID = "com.shizulu.external.su"
        private const val PREFS = "shizulu_settings"
    }
}
