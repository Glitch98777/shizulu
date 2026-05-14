package com.shizulu.manager

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RestoreProbe(
    val command: String,
    val restoreTemplate: String,
    val label: String
)

data class RestoreEntry(
    val moduleId: String,
    val actionId: String,
    val timestamp: Long,
    val probes: List<RestoreProbe>
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("moduleId", moduleId)
            put("actionId", actionId)
            put("timestamp", timestamp)
            put("probes", JSONArray().apply {
                probes.forEach { probe ->
                    put(JSONObject().apply {
                        put("command", probe.command)
                        put("restoreTemplate", probe.restoreTemplate)
                        put("label", probe.label)
                    })
                }
            })
        }
    }

    fun summary(): String {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
        return "$time: ${probes.size} restorable value(s)"
    }

    companion object {
        fun fromJson(obj: JSONObject): RestoreEntry {
            val probes = obj.optJSONArray("probes") ?: JSONArray()
            return RestoreEntry(
                moduleId = obj.optString("moduleId"),
                actionId = obj.optString("actionId"),
                timestamp = obj.optLong("timestamp"),
                probes = buildList {
                    for (index in 0 until probes.length()) {
                        val probe = probes.getJSONObject(index)
                        add(
                            RestoreProbe(
                                command = probe.optString("command"),
                                restoreTemplate = probe.optString("restoreTemplate"),
                                label = probe.optString("label")
                            )
                        )
                    }
                }
            )
        }
    }
}

object RestorePlanner {
    private val settingsPut = Regex("""^\s*settings\s+put\s+(system|secure|global)\s+([A-Za-z0-9_.:-]+)\s+(.+?)\s*$""")
    private val deviceConfigPut = Regex("""^\s*(?:cmd\s+)?device_config\s+put\s+([A-Za-z0-9_.:-]+)\s+([A-Za-z0-9_.:-]+)\s+(.+?)\s*$""")

    fun canSnapshot(command: String): Boolean = probeFor(command) != null

    fun probesFor(commands: List<ShizuleCommand>): List<RestoreProbe> {
        return commands.mapNotNull { probeFor(it.exec) }.distinctBy { it.command }
    }

    fun probeFor(command: String): RestoreProbe? {
        settingsPut.matchEntire(command)?.let { match ->
            val namespace = match.groupValues[1]
            val key = match.groupValues[2]
            return RestoreProbe(
                command = "settings get $namespace $key",
                restoreTemplate = "settings put $namespace $key {{value}}",
                label = "settings/$namespace/$key"
            )
        }
        deviceConfigPut.matchEntire(command)?.let { match ->
            val namespace = match.groupValues[1]
            val key = match.groupValues[2]
            return RestoreProbe(
                command = "device_config get $namespace $key",
                restoreTemplate = "device_config put $namespace $key {{value}}",
                label = "device_config/$namespace/$key"
            )
        }
        return null
    }

    fun restoreCommand(probe: RestoreProbe, rawOutput: String): String? {
        val value = rawOutput.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("exit=") }
            ?: return null
        if (value == "null") return probe.restoreTemplate.replace("{{value}}", "null")
        return probe.restoreTemplate.replace("{{value}}", value.shellQuote())
    }

    private fun String.shellQuote(): String {
        return "'${replace("'", "'\"'\"'")}'"
    }
}
