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
    val actions: List<ShizuleAction>
) {
    val isSigned: Boolean
        get() = signature != null

    companion object {
        private val idPattern = Regex("^[a-zA-Z0-9_.-]{3,80}$")

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
    }
}

data class ShizuleAction(
    val id: String,
    val label: String,
    val commands: List<ShizuleCommand>
)

data class ShizuleCommand(
    val exec: String
)

data class ShizuleSignature(
    val author: String,
    val sha256: String
)
