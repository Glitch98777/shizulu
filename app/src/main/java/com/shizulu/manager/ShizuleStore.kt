package com.shizulu.manager

import android.content.Context
import java.io.File

class ShizuleStore(private val context: Context) {
    private val dir: File = File(context.filesDir, "shizules")

    fun install(raw: String): Shizule {
        val shizule = Shizule.fromJson(raw)
        if (!dir.exists()) dir.mkdirs()
        File(dir, "${shizule.id}.json").writeText(raw, Charsets.UTF_8)
        return shizule
    }

    fun list(): List<Shizule> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> runCatching { Shizule.fromJson(file.readText(Charsets.UTF_8)) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun listRaw(): List<String> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> runCatching { file.readText(Charsets.UTF_8) }.getOrNull() }
            ?: emptyList()
    }

    fun installAll(rawShizules: List<String>): Int {
        var installed = 0
        rawShizules.forEach { raw ->
            runCatching {
                install(raw)
                installed++
            }
        }
        return installed
    }

    fun delete(shizule: Shizule) {
        File(dir, "${shizule.id}.json").delete()
    }
}
