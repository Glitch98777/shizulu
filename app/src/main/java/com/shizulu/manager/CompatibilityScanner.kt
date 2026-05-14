package com.shizulu.manager

import android.os.Build
import java.util.Locale

enum class CompatibilityLevel {
    COMPATIBLE,
    PROBABLY_COMPATIBLE,
    UNKNOWN,
    PARTIALLY_COMPATIBLE,
    NOT_COMPATIBLE
}

data class CompatibilityReport(
    val level: CompatibilityLevel,
    val reasons: List<String>,
    val warnings: List<String>
) {
    fun label(): String {
        return when (level) {
            CompatibilityLevel.COMPATIBLE -> "Compatible"
            CompatibilityLevel.PROBABLY_COMPATIBLE -> "Probably compatible"
            CompatibilityLevel.UNKNOWN -> "Unknown compatibility"
            CompatibilityLevel.PARTIALLY_COMPATIBLE -> "Partially compatible"
            CompatibilityLevel.NOT_COMPATIBLE -> "Not compatible"
        }
    }
}

object CompatibilityScanner {
    fun scan(
        shizule: Shizule,
        mode: ExecutionMode,
        shizukuReady: Boolean,
        wirelessAdbReady: Boolean
    ): CompatibilityReport {
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var level = CompatibilityLevel.COMPATIBLE
        val compatibility = shizule.compatibility
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)
        val model = Build.MODEL
        val sdk = Build.VERSION.SDK_INT
        val hasDeclaredDeviceLimits = compatibility.worksOn.isNotEmpty() ||
            compatibility.androidMin != null ||
            compatibility.androidMax != null
        val hasDeclaredBackendRequirements = compatibility.requires.isNotEmpty()

        if (compatibility.worksOn.isNotEmpty()) {
            val matches = compatibility.worksOn.any { manufacturer.contains(it.lowercase(Locale.US)) || model.lowercase(Locale.US).contains(it.lowercase(Locale.US)) }
            if (matches) {
                reasons += "Device family matches ${compatibility.worksOn.joinToString(", ")}."
            } else {
                level = CompatibilityLevel.PARTIALLY_COMPATIBLE
                warnings += "Module lists ${compatibility.worksOn.joinToString(", ")} but this device is ${Build.MANUFACTURER} $model."
            }
        } else {
            reasons += "No device-family limit declared; treating it as a generic Android shell module."
        }

        compatibility.androidMin?.let { min ->
            if (sdk < min) {
                level = CompatibilityLevel.NOT_COMPATIBLE
                warnings += "Requires Android API $min or newer; this device reports API $sdk."
            }
        }
        compatibility.androidMax?.let { max ->
            if (sdk > max) {
                level = max(level, CompatibilityLevel.PARTIALLY_COMPATIBLE)
                warnings += "Tested up to Android API $max; this device reports API $sdk."
            }
        }

        if (compatibility.requires.isEmpty()) {
            val inferred = inferBackendRequirements(shizule)
            val backendReady = when (mode) {
                ExecutionMode.SHIZUKU -> shizukuReady
                ExecutionMode.WIRELESS_ADB -> wirelessAdbReady
            }
            if (backendReady) {
                level = max(level, CompatibilityLevel.PROBABLY_COMPATIBLE)
                reasons += "No backend requirement declared; commands look runnable through the selected ${mode.label} shell backend."
            } else {
                level = max(level, CompatibilityLevel.PARTIALLY_COMPATIBLE)
                warnings += "No backend requirement declared, and the selected ${mode.label} backend is not ready."
            }
            if (inferred.isNotEmpty()) {
                reasons += "Inferred command needs: ${inferred.joinToString(", ")}."
            }
        } else {
            val wantsShizuku = "shizuku" in compatibility.requires
            val wantsAdb = compatibility.requires.any { it == "adb" || it == "wireless_adb" }
            val backendOk = (mode == ExecutionMode.SHIZUKU && wantsShizuku && shizukuReady) ||
                (mode == ExecutionMode.WIRELESS_ADB && wantsAdb && wirelessAdbReady)
            if (backendOk) {
                reasons += "Selected backend satisfies declared requirements."
            } else {
                level = max(level, CompatibilityLevel.PARTIALLY_COMPATIBLE)
                warnings += "Selected backend may not satisfy requirements: ${compatibility.requires.joinToString(", ")}."
            }
        }

        if (!hasDeclaredDeviceLimits && !hasDeclaredBackendRequirements && level == CompatibilityLevel.COMPATIBLE) {
            level = CompatibilityLevel.PROBABLY_COMPATIBLE
            reasons += "No compatibility limits were declared, so Shizulu is using command-based compatibility inference."
        } else if (reasons.isEmpty() && warnings.isEmpty()) {
            level = CompatibilityLevel.PROBABLY_COMPATIBLE
            reasons += "No compatibility problems detected."
        }
        return CompatibilityReport(level, reasons, warnings)
    }

    private fun inferBackendRequirements(shizule: Shizule): List<String> {
        val joined = shizule.actions
            .flatMap { it.commands + it.prechecks + it.postchecks + it.restoreCommands }
            .joinToString("\n") { it.exec }
            .lowercase(Locale.US)
        return buildList {
            if ("settings " in joined || "cmd settings" in joined) add("settings service")
            if ("cmd appops" in joined || "appops " in joined) add("appops service")
            if ("pm " in joined || "cmd package" in joined) add("package manager")
            if ("dumpsys" in joined) add("dumpsys")
            if ("device_config" in joined) add("device_config")
            if ("cmd overlay" in joined) add("overlay service")
        }.distinct()
    }

    private fun max(left: CompatibilityLevel, right: CompatibilityLevel): CompatibilityLevel {
        return if (right.ordinal > left.ordinal) right else left
    }
}
