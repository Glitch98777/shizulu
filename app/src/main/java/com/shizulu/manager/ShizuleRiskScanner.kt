package com.shizulu.manager

import java.util.Locale

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    fun atLeast(other: RiskLevel): Boolean = ordinal >= other.ordinal

    fun label(): String {
        return when (this) {
            LOW -> "low risk"
            MEDIUM -> "medium risk"
            HIGH -> "high risk"
            CRITICAL -> "critical risk"
        }
    }

    fun sentenceLabel(): String {
        return label().replaceFirstChar { it.uppercase() }
    }
}

data class CommandRisk(
    val command: String,
    val index: Int,
    val level: RiskLevel,
    val reasons: List<String>,
    val impact: String,
    val blocked: Boolean
)

data class ShizuleRiskReport(
    val level: RiskLevel,
    val commandRisks: List<CommandRisk>,
    val restoreAvailable: Boolean,
    val restorePartial: Boolean,
    val summary: String
) {
    val blocked: Boolean get() = commandRisks.any { it.blocked }
}

object ShizuleRiskScanner {
    private val corePackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.shell",
        "com.android.providers.settings",
        "com.android.providers.downloads"
    )

    fun scan(shizule: Shizule): ShizuleRiskReport {
        val commands = shizule.actions.flatMap { action -> action.commands }
        val commandRisks = commands.mapIndexed { index, command -> scanCommand(command.exec, index + 1) }
        val level = commandRisks.maxByOrNull { it.level.ordinal }?.level ?: RiskLevel.LOW
        val hasRestoreCommands = shizule.restore.commands.isNotEmpty() ||
            shizule.actions.any { it.restoreCommands.isNotEmpty() || it.id.contains("restore", true) || it.label.contains("restore", true) }
        val snapshotPossible = commands.any { RestorePlanner.canSnapshot(it.exec) }
        val restoreAvailable = hasRestoreCommands || snapshotPossible || shizule.restore.level != RestoreSupport.NONE
        val restorePartial = restoreAvailable && shizule.restore.level != RestoreSupport.FULL
        return ShizuleRiskReport(
            level = level,
            commandRisks = commandRisks,
            restoreAvailable = restoreAvailable,
            restorePartial = restorePartial,
            summary = when (level) {
                RiskLevel.LOW -> "Low risk: mostly read-only or reversible shell-accessible checks."
                RiskLevel.MEDIUM -> "Medium risk: changes settings or app state that should be reviewed first."
                RiskLevel.HIGH -> "High risk: can disable packages, change AppOps, reset data, or alter system behavior."
                RiskLevel.CRITICAL -> "Critical risk: blocked or heavily restricted command pattern detected."
            }
        )
    }

    fun scanCommand(command: String, index: Int = 1): CommandRisk {
        val normalized = command.trim()
        val lower = normalized.lowercase(Locale.US)
        val reasons = mutableListOf<String>()
        var level = RiskLevel.LOW
        var blocked = false

        fun mark(newLevel: RiskLevel, reason: String, shouldBlock: Boolean = false) {
            if (newLevel.ordinal > level.ordinal) level = newLevel
            reasons += reason
            blocked = blocked || shouldBlock
        }

        when {
            lower.startsWith("settings get ") -> mark(RiskLevel.LOW, "Reads Android settings.")
            lower.startsWith("settings put ") || lower.startsWith("cmd settings put ") -> mark(RiskLevel.MEDIUM, "Changes Android settings.")
            lower.startsWith("cmd appops get ") || lower.startsWith("appops get ") -> mark(RiskLevel.LOW, "Reads AppOps state.")
            lower.startsWith("cmd appops set ") || lower.startsWith("appops set ") -> mark(RiskLevel.HIGH, "Changes AppOps permissions.")
            lower.startsWith("cmd appops reset ") -> mark(RiskLevel.HIGH, "Resets AppOps state for a package.")
            lower.startsWith("pm grant ") || lower.startsWith("pm revoke ") -> mark(RiskLevel.MEDIUM, "Changes app permissions through package manager.")
            lower.startsWith("pm disable") || lower.startsWith("cmd package disable") -> mark(RiskLevel.HIGH, "Disables an installed package.")
            lower.startsWith("pm enable") || lower.startsWith("cmd package enable") -> mark(RiskLevel.MEDIUM, "Enables an installed package.")
            lower.startsWith("pm clear ") || lower.startsWith("cmd package clear ") -> mark(RiskLevel.HIGH, "Clears app data.")
            lower.startsWith("cmd package ") -> mark(RiskLevel.HIGH, "Runs advanced package manager command.")
            lower.startsWith("cmd overlay ") -> mark(RiskLevel.MEDIUM, "Changes or inspects runtime resource overlays.")
            lower.startsWith("device_config put ") || lower.startsWith("cmd device_config put ") -> mark(RiskLevel.HIGH, "Changes DeviceConfig flags.")
            lower.startsWith("device_config get ") || lower.startsWith("cmd device_config get ") -> mark(RiskLevel.LOW, "Reads DeviceConfig flags.")
            lower.startsWith("dumpsys ") || lower.startsWith("getprop") || lower.startsWith("wm size") || lower.startsWith("wm density") -> mark(RiskLevel.LOW, "Reads device diagnostic information.")
            lower.startsWith("am force-stop ") -> mark(RiskLevel.MEDIUM, "Force-stops an app.")
            lower.startsWith("am ") -> mark(RiskLevel.MEDIUM, "Runs Activity Manager command.")
        }

        if (Regex("""(^|[;&|]\s*)rm\s+(-[^\n ]*r[^\n ]*f|-rf|-fr)\s+(/|/data|/system|/vendor|/product|/sdcard)(\s|$)""").containsMatchIn(lower)) {
            mark(RiskLevel.CRITICAL, "Destructive recursive delete against a broad path.", shouldBlock = true)
        }
        if (Regex("""(^|[;&|]\s*)(dd|mkfs|reboot|setenforce|mount|iptables|ip6tables)\b""").containsMatchIn(lower)) {
            mark(RiskLevel.CRITICAL, "Attempts a root-only or device-disruptive system operation.", shouldBlock = true)
        }
        if (Regex("""(curl|wget|nc|ncat|socat)\b.*(\|\s*sh|\|\s*bash|/system/bin/sh|sh\s+-c)""").containsMatchIn(lower)) {
            mark(RiskLevel.CRITICAL, "Downloads or receives a script and executes it.", shouldBlock = true)
        }
        if (Regex("""\b(su|magisk|resetprop|zygisk)\b""").containsMatchIn(lower)) {
            mark(RiskLevel.CRITICAL, "Requests root/Magisk-only behavior. Shizulu is rootless.", shouldBlock = true)
        }
        if (looksLikeSecurityBypass(lower)) {
            mark(RiskLevel.CRITICAL, "Looks like a bypass or security-evasion attempt, which Shizulu will not support.", shouldBlock = true)
        }
        val disabledCore = if (lower.startsWith("pm disable") || lower.startsWith("cmd package disable")) {
            commandPackageTokens(lower).firstOrNull { it in corePackages }
        } else {
            null
        }
        if (disabledCore != null) {
            mark(RiskLevel.CRITICAL, "Attempts to disable core system package $disabledCore.", shouldBlock = true)
        }

        if (reasons.isEmpty()) reasons += "Generic shell command. Review before running."
        return CommandRisk(
            command = normalized,
            index = index,
            level = level,
            reasons = reasons.distinct(),
            impact = impactFor(level),
            blocked = blocked
        )
    }

    private fun impactFor(level: RiskLevel): String {
        return when (level) {
            RiskLevel.LOW -> "Usually read-only or easy to reverse."
            RiskLevel.MEDIUM -> "May change app or device behavior."
            RiskLevel.HIGH -> "Can break an app, reset state, or make device behavior worse until restored."
            RiskLevel.CRITICAL -> "Blocked because it could be destructive, root-only, or a security bypass attempt."
        }
    }

    private fun looksLikeSecurityBypass(command: String): Boolean {
        val target = Regex("""\b(play\s*integrity|playintegrity|safetynet|banking|drm|anti[-_ ]?cheat)\b""")
        val bypassVerb = Regex("""\b(bypass|spoof|hide|evade|defeat|pass|workaround)\b""")
        return target.containsMatchIn(command) && bypassVerb.containsMatchIn(command)
    }

    private fun commandPackageTokens(command: String): List<String> {
        return command.split(Regex("\\s+"))
            .map { it.trim('\'', '"', ';', '&', '|') }
            .filter { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) }
    }
}
