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

        if (compatibility.worksOn.isNotEmpty()) {
            val matches = compatibility.worksOn.any { manufacturer.contains(it.lowercase(Locale.US)) || model.lowercase(Locale.US).contains(it.lowercase(Locale.US)) }
            if (matches) {
                reasons += "Device family matches ${compatibility.worksOn.joinToString(", ")}."
            } else {
                level = CompatibilityLevel.PARTIALLY_COMPATIBLE
                warnings += "Module lists ${compatibility.worksOn.joinToString(", ")} but this device is ${Build.MANUFACTURER} $model."
            }
        } else {
            level = max(level, CompatibilityLevel.UNKNOWN)
            warnings += "Module does not declare supported device families."
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
            level = max(level, CompatibilityLevel.UNKNOWN)
            warnings += "Module does not say whether it needs Shizuku, Wireless ADB, or both."
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

        if (reasons.isEmpty() && warnings.isEmpty()) {
            level = CompatibilityLevel.PROBABLY_COMPATIBLE
            reasons += "No compatibility limits were declared."
        }
        return CompatibilityReport(level, reasons, warnings)
    }

    private fun max(left: CompatibilityLevel, right: CompatibilityLevel): CompatibilityLevel {
        return if (right.ordinal > left.ordinal) right else left
    }
}
