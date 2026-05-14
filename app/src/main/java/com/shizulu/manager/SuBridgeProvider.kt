package com.shizulu.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
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

        val label = resolveLabel(context, packageName)
        return bridgeBundle(
            false,
            "Spoof root is not granted for $label. Open Shizulu > Tools > Rootless Power Tools > Spoof Root Apps, grant ${packages.joinToString()}, then retry.",
            ""
        )
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
        private const val DEFAULT_MODULE_ID = "com.shizulu.external.su"
        private const val PREFS = "shizulu_settings"
        private const val ROOT_UID = 0
        private const val SHELL_UID = 2000
        private val IGNORED_SU_FLAGS = setOf("-p", "-l", "-m", "-mm", "-M", "--mount-master", "--preserve-environment")
    }
}
