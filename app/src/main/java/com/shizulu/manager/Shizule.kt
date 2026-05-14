package com.shizulu.manager

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class Shizule(
    val schema: Int,
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val description: String,
    val author: ShizuleAuthor,
    val tags: List<String>,
    val categories: List<String>,
    val screenshots: List<String>,
    val updateUrl: String,
    val changelog: List<String>,
    val safety: ShizuleSafety,
    val restore: ShizuleRestoreInfo,
    val knownIssues: List<String>,
    val rawSha256: String,
    val signature: ShizuleSignature?,
    val compatibility: ShizuleCompatibility,
    val permissions: List<String>,
    val tier: String,
    val variables: List<ShizuleVariable>,
    val actions: List<ShizuleAction>
) {
    val isSigned: Boolean
        get() = signature != null

    companion object {
        private val idPattern = Regex("^[a-zA-Z0-9_.-]{3,80}$")
        private val variableNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$")

        fun fromJson(raw: String): Shizule {
            val obj = JSONObject(raw)
            val schema = obj.optInt("schema", 1)
            require(schema in 1..2) { "Unsupported shizule schema: $schema. Shizulu supports schema 1 and 2." }

            val id = obj.getString("id").trim()
            require(idPattern.matches(id)) { "Invalid shizule id. Use 3-80 letters, numbers, dots, dashes, or underscores." }

            val actions = parseActions(obj.getJSONArray("actions"))
            require(actions.isNotEmpty()) { "A shizule needs at least one action." }
            require(actions.size <= 48) { "A shizule can include at most 48 actions." }

            val versionName = obj.optString("version", "1.0.0").trim().take(32).ifBlank { "1.0.0" }
            return Shizule(
                schema = schema,
                id = id,
                name = obj.optString("name", id).trim().take(80).ifBlank { id },
                version = versionName,
                versionCode = obj.optInt("versionCode", versionCodeFromName(versionName)).coerceAtLeast(1),
                description = obj.optString("description", "").trim().take(240),
                author = parseAuthor(obj.optJSONObject("author"), obj.optJSONObject("signature")),
                tags = parseStringArray(obj.optJSONArray("tags"), 32),
                categories = parseStringArray(obj.optJSONArray("categories"), 32),
                screenshots = parseStringArray(obj.optJSONArray("screenshots"), 240),
                updateUrl = obj.optString("updateUrl", "").trim().take(240),
                changelog = parseStringArray(obj.optJSONArray("changelog"), 200),
                safety = parseSafety(obj.optJSONObject("safety")),
                restore = parseRestore(obj.optJSONObject("restore"), actions),
                knownIssues = parseStringArray(obj.optJSONArray("knownIssues"), 200),
                rawSha256 = sha256(raw),
                signature = parseSignature(obj.optJSONObject("signature")),
                compatibility = parseCompatibility(obj.optJSONObject("compatibility")),
                permissions = parseStringArray(obj.optJSONArray("permissions"), 64),
                tier = obj.optString("tier", "").trim().take(48),
                variables = parseVariables(obj.optJSONArray("variables")),
                actions = actions
            )
        }

        fun validate(raw: String): ShizuleValidationResult {
            return runCatching { fromJson(raw) }
                .fold(
                    onSuccess = { shizule ->
                        val warnings = buildList {
                            if (shizule.description.isBlank()) add("Add a short description so users know what the module changes.")
                            if (shizule.restore.level == RestoreSupport.NONE) add("No restore metadata was declared. Shizulu will only restore values it can snapshot automatically.")
                            if (shizule.compatibility.requires.isEmpty()) add("Declare whether this module works with Shizuku, Wireless ADB, or both.")
                            val declaredVariables = (shizule.variables + shizule.actions.flatMap { it.variables }).map { it.name }.toSet()
                            val referenced = shizule.actions.flatMap { action ->
                                (action.commands + action.restoreCommands + action.prechecks + action.postchecks)
                                    .flatMap { command -> VARIABLE_REFERENCE.findAll(command.exec).map { it.groupValues[1] }.toList() }
                            }.toSet()
                            val missing = referenced - declaredVariables
                            if (missing.isNotEmpty()) add("Undefined variable(s): ${missing.joinToString(", ")}.")
                        }
                        ShizuleValidationResult(shizule = shizule, errors = emptyList(), warnings = warnings)
                    },
                    onFailure = { error ->
                        ShizuleValidationResult(
                            shizule = null,
                            errors = listOf(error.message ?: "Invalid shizule JSON."),
                            warnings = emptyList()
                        )
                    }
                )
        }

        private fun parseAuthor(obj: JSONObject?, signature: JSONObject?): ShizuleAuthor {
            if (obj == null) {
                val signedAuthor = signature?.optString("author").orEmpty().trim()
                return ShizuleAuthor(name = signedAuthor.ifBlank { "Unknown author" })
            }
            return ShizuleAuthor(
                name = obj.optString("name", signature?.optString("author") ?: "Unknown author").trim().take(80).ifBlank { "Unknown author" },
                url = obj.optString("url", "").trim().take(240),
                email = obj.optString("email", "").trim().take(120),
                verified = obj.optBoolean("verified", false)
            )
        }

        private fun parseSignature(obj: JSONObject?): ShizuleSignature? {
            if (obj == null) return null
            val author = obj.optString("author").trim().take(80)
            val digest = obj.optString("sha256").trim().take(96)
            if (author.isBlank() && digest.isBlank()) return null
            return ShizuleSignature(author = author.ifBlank { "Unknown author" }, sha256 = digest)
        }

        private fun parseSafety(obj: JSONObject?): ShizuleSafety {
            if (obj == null) return ShizuleSafety()
            return ShizuleSafety(
                risk = obj.optString("risk", "").trim().take(32),
                notes = obj.optString("notes", "").trim().take(400),
                reversible = obj.optBoolean("reversible", false),
                requiresReview = obj.optBoolean("requiresReview", true)
            )
        }

        private fun parseRestore(obj: JSONObject?, actions: List<ShizuleAction>): ShizuleRestoreInfo {
            if (obj == null) {
                val hasRestoreAction = actions.any { it.id.contains("restore", true) || it.label.contains("restore", true) || it.restoreCommands.isNotEmpty() }
                return ShizuleRestoreInfo(
                    level = if (hasRestoreAction) RestoreSupport.MANUAL else RestoreSupport.NONE,
                    notes = if (hasRestoreAction) "Restore action declared." else "",
                    snapshotBeforeRun = true
                )
            }
            return ShizuleRestoreInfo(
                level = RestoreSupport.from(obj.optString("level", "")),
                notes = obj.optString("notes", "").trim().take(400),
                snapshotBeforeRun = obj.optBoolean("snapshotBeforeRun", true),
                commands = parseCommands(obj.optJSONArray("commands"))
            )
        }

        private fun parseActions(array: JSONArray): List<ShizuleAction> {
            return buildList {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    val commands = obj.getJSONArray("commands")
                    add(
                        ShizuleAction(
                            id = obj.optString("id", "action_$index").trim().take(64).ifBlank { "action_$index" },
                            label = obj.optString("label", "Run").trim().take(64).ifBlank { "Run" },
                            variables = parseVariables(obj.optJSONArray("variables")),
                            commands = parseCommands(commands),
                            restoreCommands = parseCommands(obj.optJSONArray("restoreCommands")),
                            prechecks = parseCommands(obj.optJSONArray("prechecks")),
                            postchecks = parseCommands(obj.optJSONArray("postchecks")),
                            stopOnError = obj.optBoolean("stopOnError", true)
                        )
                    )
                }
            }
        }

        private fun parseCommands(array: JSONArray?): List<ShizuleCommand> {
            if (array == null) return emptyList()
            require(array.length() <= 128) { "A command list can include at most 128 commands." }
            return buildList {
                for (commandIndex in 0 until array.length()) {
                    val commandObj = array.getJSONObject(commandIndex)
                    val exec = commandObj.getString("exec").trim()
                    require(exec.length in 1..4096) { "Command length must be 1-4096 characters." }
                    require('\u0000' !in exec) { "Commands cannot contain null characters." }
                    add(
                        ShizuleCommand(
                            exec = exec,
                            explanation = commandObj.optString("explanation", "").trim().take(240),
                            mutates = if (commandObj.has("mutates")) commandObj.optBoolean("mutates") else null
                        )
                    )
                }
            }
        }

        private fun parseCompatibility(obj: JSONObject?): ShizuleCompatibility {
            if (obj == null) return ShizuleCompatibility()
            val min = if (obj.has("androidMin")) obj.optInt("androidMin") else null
            val max = if (obj.has("androidMax")) obj.optInt("androidMax") else null
            return ShizuleCompatibility(
                worksOn = parseStringArray(obj.optJSONArray("worksOn"), 32).map { it.lowercase(Locale.US) },
                androidMin = min?.takeIf { it > 0 },
                androidMax = max?.takeIf { it > 0 },
                requires = parseStringArray(obj.optJSONArray("requires"), 32).map { it.lowercase(Locale.US) }
            )
        }

        private fun parseStringArray(array: JSONArray?, maxLength: Int): List<String> {
            if (array == null) return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim().take(maxLength)
                    if (value.isNotBlank()) add(value)
                }
            }
        }

        private fun parseVariables(array: JSONArray?): List<ShizuleVariable> {
            if (array == null) return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    val name = obj.getString("name").trim()
                    require(variableNamePattern.matches(name)) { "Invalid variable name: $name" }
                    add(
                        ShizuleVariable(
                            name = name,
                            label = obj.optString("label", name).trim().take(80).ifBlank { name },
                            type = ShizuleVariableType.from(obj.optString("type", "text")),
                            defaultValue = obj.optString("default", "").take(240),
                            required = obj.optBoolean("required", true)
                        )
                    )
                }
            }
        }

        private fun versionCodeFromName(version: String): Int {
            return version.split(Regex("[^0-9]+"))
                .filter { it.isNotBlank() }
                .take(3)
                .fold(0) { acc, part -> (acc * 1000) + (part.toIntOrNull() ?: 0) }
                .coerceAtLeast(1)
        }

        private fun sha256(raw: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private val VARIABLE_REFERENCE = Regex("\\{\\{([A-Za-z][A-Za-z0-9_]*)\\}\\}")
    }
}

data class ShizuleAction(
    val id: String,
    val label: String,
    val variables: List<ShizuleVariable>,
    val commands: List<ShizuleCommand>,
    val restoreCommands: List<ShizuleCommand> = emptyList(),
    val prechecks: List<ShizuleCommand> = emptyList(),
    val postchecks: List<ShizuleCommand> = emptyList(),
    val stopOnError: Boolean = true
)

data class ShizuleCommand(
    val exec: String,
    val explanation: String = "",
    val mutates: Boolean? = null
)

data class ShizuleCompatibility(
    val worksOn: List<String> = emptyList(),
    val androidMin: Int? = null,
    val androidMax: Int? = null,
    val requires: List<String> = emptyList()
)

data class ShizuleAuthor(
    val name: String = "Unknown author",
    val url: String = "",
    val email: String = "",
    val verified: Boolean = false
)

data class ShizuleSafety(
    val risk: String = "",
    val notes: String = "",
    val reversible: Boolean = false,
    val requiresReview: Boolean = true
)

data class ShizuleRestoreInfo(
    val level: RestoreSupport = RestoreSupport.NONE,
    val notes: String = "",
    val snapshotBeforeRun: Boolean = true,
    val commands: List<ShizuleCommand> = emptyList()
)

enum class RestoreSupport {
    NONE,
    PARTIAL,
    SNAPSHOT,
    MANUAL,
    FULL;

    companion object {
        fun from(value: String): RestoreSupport {
            return when (value.trim().lowercase(Locale.US)) {
                "full" -> FULL
                "manual" -> MANUAL
                "snapshot" -> SNAPSHOT
                "partial" -> PARTIAL
                else -> NONE
            }
        }
    }
}

data class ShizuleVariable(
    val name: String,
    val label: String,
    val type: ShizuleVariableType,
    val defaultValue: String,
    val required: Boolean
)

enum class ShizuleVariableType {
    TEXT,
    PACKAGE,
    NUMBER;

    companion object {
        fun from(value: String): ShizuleVariableType {
            return when (value.trim().lowercase(Locale.US)) {
                "app", "target_app", "package", "packagename", "package_name", "pkg" -> PACKAGE
                "number", "int", "integer" -> NUMBER
                else -> TEXT
            }
        }
    }
}

data class ShizuleValidationResult(
    val shizule: Shizule?,
    val errors: List<String>,
    val warnings: List<String>
) {
    val isValid: Boolean get() = shizule != null && errors.isEmpty()
}

data class ShizuleSignature(
    val author: String,
    val sha256: String
)
