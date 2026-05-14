package com.shizulu.manager

import org.json.JSONArray
import org.json.JSONObject

data class Shizule(
    val schema: Int,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val signature: ShizuleSignature?,
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
            require(schema == 1) { "Unsupported shizule schema: $schema" }

            val id = obj.getString("id").trim()
            require(idPattern.matches(id)) { "Invalid shizule id. Use 3-80 letters, numbers, dots, dashes, or underscores." }

            val actions = parseActions(obj.getJSONArray("actions"))
            require(actions.isNotEmpty()) { "A shizule needs at least one action." }

            return Shizule(
                schema = schema,
                id = id,
                name = obj.optString("name", id).take(80),
                version = obj.optString("version", "1.0.0").take(32),
                description = obj.optString("description", "").take(240),
                signature = parseSignature(obj.optJSONObject("signature")),
                variables = parseVariables(obj.optJSONArray("variables")),
                actions = actions
            )
        }

        private fun parseSignature(obj: JSONObject?): ShizuleSignature? {
            if (obj == null) return null
            val author = obj.optString("author").trim().take(80)
            val digest = obj.optString("sha256").trim().take(96)
            if (author.isBlank() && digest.isBlank()) return null
            return ShizuleSignature(author = author.ifBlank { "Unknown author" }, sha256 = digest)
        }

        private fun parseActions(array: JSONArray): List<ShizuleAction> {
            return buildList {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    val commands = obj.getJSONArray("commands")
                    add(
                        ShizuleAction(
                            id = obj.optString("id", "action_$index").take(64),
                            label = obj.optString("label", "Run").take(64),
                            variables = parseVariables(obj.optJSONArray("variables")),
                            commands = buildList {
                                for (commandIndex in 0 until commands.length()) {
                                    val commandObj = commands.getJSONObject(commandIndex)
                                    val exec = commandObj.getString("exec").trim()
                                    require(exec.length in 1..4096) { "Command length must be 1-4096 characters." }
                                    add(ShizuleCommand(exec = exec))
                                }
                            }
                        )
                    )
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
    }
}

data class ShizuleAction(
    val id: String,
    val label: String,
    val variables: List<ShizuleVariable>,
    val commands: List<ShizuleCommand>
)

data class ShizuleCommand(
    val exec: String
)

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
            return when (value.trim().lowercase()) {
                "app", "target_app", "package", "packagename", "package_name", "pkg" -> PACKAGE
                "number", "int", "integer" -> NUMBER
                else -> TEXT
            }
        }
    }
}

data class ShizuleSignature(
    val author: String,
    val sha256: String
)
