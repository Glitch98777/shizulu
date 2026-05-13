package com.shizulu.manager

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

class ShizuluUserService : IShizuluService.Stub() {
    override fun getUid(): Int = android.os.Process.myUid()

    override fun runCommand(command: String): String = runShizuleCommand("", command)

    override fun runShizuleCommand(moduleId: String, command: String): String {
        require(command.length <= 4096) { "Command too long." }

        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment()["SHIZULU"] = "1"
                builder.environment()["SHIZULU_API_VERSION"] = "1"
                if (moduleId.isNotBlank()) builder.environment()["SHIZULU_MODULE_ID"] = moduleId
            }
            .start()

        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (output.length < 32_000) {
                    output.append(line).append('\n')
                }
                line = reader.readLine()
            }
        }

        val exitCode = process.waitFor()
        return "exit=$exitCode\n$output".trim()
    }

    override fun destroy() {
        exitProcess(0)
    }
}
