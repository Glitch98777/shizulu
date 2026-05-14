package com.shizulu.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import java.util.UUID

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
                ensureCallerAllowed(context, METHOD_EXEC, command)?.let { return it }
                val moduleId = extras?.getString(EXTRA_MODULE_ID).orEmpty().ifBlank { DEFAULT_MODULE_ID }
                runCatching { executor.execute(moduleId, command) }
                    .fold(
                        onSuccess = { output -> bridgeBundle(true, "OK", output) },
                        onFailure = { bridgeBundle(false, it.message ?: it.javaClass.simpleName, "") }
                    )
            }
            METHOD_SU, METHOD_SU_C -> {
                if (!enabled) return bridgeBundle(false, "SU Bridge endpoint is disabled in Shizulu.", "")
                val raw = extras?.getString(EXTRA_COMMAND) ?: arg.orEmpty()
                val stdin = extras?.getString(EXTRA_STDIN).orEmpty()
                val command = parseSuCommand(raw, stdin)
                    ?: return bridgeBundle(false, "Use su -c <command>, su 0 -c <command>, or pass stdin.", "")
                ensureCallerAllowed(context, METHOD_SU, command)?.let { return it }
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

    private fun ensureCallerAllowed(context: Context, method: String, command: String): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid == context.applicationInfo.uid || uid == ROOT_UID || uid == SHELL_UID) return null

        val packages = context.packageManager.getPackagesForUid(uid).orEmpty().filter { it.isNotBlank() }
        val packageName = packages.firstOrNull() ?: "uid:$uid"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val allowed = prefs.getStringSet(KEY_ALLOWED_ROOT_PACKAGES, emptySet()).orEmpty()
        if (packages.any { it in allowed }) return null

        val requestId = UUID.randomUUID().toString()
        val decisionKey = "$KEY_ROOT_DECISION_PREFIX$requestId"
        prefs.edit().putString(decisionKey, DECISION_PENDING).apply()

        val label = resolveLabel(context, packageName)
        val launched = runCatching {
            context.startActivity(
                Intent(context, RootAccessRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(RootAccessRequestActivity.EXTRA_REQUEST_ID, requestId)
                    .putExtra(RootAccessRequestActivity.EXTRA_PACKAGE_NAME, packageName)
                    .putExtra(RootAccessRequestActivity.EXTRA_PACKAGE_LABEL, label)
                    .putExtra(RootAccessRequestActivity.EXTRA_METHOD, method)
                    .putExtra(RootAccessRequestActivity.EXTRA_COMMAND, command.take(MAX_COMMAND_PREVIEW))
            )
        }.isSuccess

        if (!launched) {
            prefs.edit().remove(decisionKey).apply()
            return bridgeBundle(false, "Could not show Shizulu root access prompt for $label.", "")
        }

        val deadline = SystemClock.elapsedRealtime() + ACCESS_REQUEST_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (prefs.getString(decisionKey, DECISION_PENDING)) {
                DECISION_ALLOW -> {
                    val updated = allowed.toMutableSet().apply { addAll(packages) }
                    prefs.edit()
                        .putStringSet(KEY_ALLOWED_ROOT_PACKAGES, updated)
                        .remove(decisionKey)
                        .apply()
                    return null
                }
                DECISION_DENY -> {
                    prefs.edit().remove(decisionKey).apply()
                    return bridgeBundle(false, "Root access denied for $label.", "")
                }
            }
            Thread.sleep(REQUEST_POLL_MS)
        }

        prefs.edit().remove(decisionKey).apply()
        return bridgeBundle(false, "Root access prompt timed out for $label.", "")
    }

    private fun resolveLabel(context: Context, packageName: String): String {
        if (packageName.startsWith("uid:")) return packageName
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
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

    private fun parseSuCommand(raw: String, stdin: String = ""): String? {
        val trimmed = raw.trim()
        val afterSu = when {
            trimmed == "su" -> ""
            trimmed.startsWith("su ") -> trimmed.removePrefix("su").trimStart()
            else -> return null
        }
        if (afterSu.isBlank()) return stdin.trim().takeIf { it.isNotBlank() }

        findCommandAfterSwitch(afterSu, "-c")?.let { return it.trim().trimMatchingQuotes().takeIf(String::isNotBlank) }
        findCommandAfterSwitch(afterSu, "--command")?.let { return it.trim().trimMatchingQuotes().takeIf(String::isNotBlank) }

        val tokens = afterSu.shellSplit()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token == "-c" || token == "--command" -> {
                    return tokens.drop(index + 1).joinToString(" ").trim().trimMatchingQuotes().takeIf { it.isNotBlank() }
                }
                token.startsWith("-c") && token.length > 2 -> {
                    return (token.removePrefix("-c") + " " + tokens.drop(index + 1).joinToString(" "))
                        .trim()
                        .trimMatchingQuotes()
                        .takeIf { it.isNotBlank() }
                }
                token == "-s" || token == "--shell" || token == "-Z" || token == "--context" -> index += 2
                token in IGNORED_SU_FLAGS || token.startsWith("-") -> index++
                token == "root" || token == "shell" || token.all { it.isDigit() } -> index++
                else -> return tokens.drop(index).joinToString(" ").trim().trimMatchingQuotes().takeIf { it.isNotBlank() }
            }
        }
        return stdin.trim().takeIf { it.isNotBlank() }
    }

    private fun findCommandAfterSwitch(value: String, switch: String): String? {
        val marker = "$switch "
        val index = value.indexOf(marker)
        if (index < 0) return null
        return value.substring(index + marker.length)
    }

    private fun String.shellSplit(): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        forEach { char ->
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                quote != null && char == quote -> quote = null
                quote != null -> current.append(char)
                char == '\'' || char == '"' -> quote = char
                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }
        if (escaped) current.append('\\')
        if (current.isNotEmpty()) result += current.toString()
        return result
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
        const val METHOD_SU = "su"
        const val METHOD_SU_C = "su-c"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_STDIN = "stdin"
        const val EXTRA_MODULE_ID = "moduleId"
        const val KEY_ALLOWED_ROOT_PACKAGES = "su_bridge_allowed_root_packages"
        const val KEY_ROOT_DECISION_PREFIX = "su_bridge_root_decision_"
        const val DECISION_PENDING = "pending"
        const val DECISION_ALLOW = "allow"
        const val DECISION_DENY = "deny"
        private const val DEFAULT_MODULE_ID = "com.shizulu.external.su"
        private const val PREFS = "shizulu_settings"
        private const val ROOT_UID = 0
        private const val SHELL_UID = 2000
        private const val ACCESS_REQUEST_TIMEOUT_MS = 30_000L
        private const val REQUEST_POLL_MS = 250L
        private const val MAX_COMMAND_PREVIEW = 600
        private val IGNORED_SU_FLAGS = setOf("-p", "-l", "-m", "-mm", "-M", "--mount-master", "--preserve-environment")
    }
}
