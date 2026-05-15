package com.shizulu.manager

import android.content.Context
import android.os.SystemClock

data class CommandResult(
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false
) {
    val success: Boolean get() = exitCode == 0 && !timedOut && !cancelled
}

interface CommandRunner {
    val name: String
    fun run(moduleId: String, command: ShizuleCommand, timeoutMs: Long = DEFAULT_TIMEOUT_MS): CommandResult

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

class MockCommandRunner : CommandRunner {
    override val name: String = "Dry run"

    override fun run(moduleId: String, command: ShizuleCommand, timeoutMs: Long): CommandResult {
        return CommandResult(
            command = command.exec,
            stdout = "Dry run only. Command was not executed.",
            stderr = "",
            exitCode = 0,
            durationMs = 0
        )
    }
}

class ShizukuCommandRunner(private val service: IShizuluService) : CommandRunner {
    override val name: String = "ShizGuru (Shizuku)"

    override fun run(moduleId: String, command: ShizuleCommand, timeoutMs: Long): CommandResult {
        val start = SystemClock.elapsedRealtime()
        val output = service.runShizuleCommand(moduleId, command.exec)
        val exit = output.lineSequence()
            .firstOrNull { it.startsWith("exit=") }
            ?.substringAfter("exit=")
            ?.trim()
            ?.toIntOrNull()
            ?: -1
        return CommandResult(
            command = command.exec,
            stdout = output,
            stderr = "",
            exitCode = exit,
            durationMs = SystemClock.elapsedRealtime() - start
        )
    }
}

class WirelessAdbCommandRunner(
    private val context: Context,
    private val pairingCode: String,
    private val pairingPort: Int
) : CommandRunner {
    override val name: String = "Wireless ADB"

    override fun run(moduleId: String, command: ShizuleCommand, timeoutMs: Long): CommandResult {
        val start = SystemClock.elapsedRealtime()
        val output = WirelessAdbRunner(context).runCommand(moduleId, command.exec, pairingCode, pairingPort).output
        val exit = output.lineSequence()
            .firstOrNull { it.startsWith("exit=") }
            ?.substringAfter("exit=")
            ?.trim()
            ?.toIntOrNull()
            ?: -1
        return CommandResult(
            command = command.exec,
            stdout = output,
            stderr = "",
            exitCode = exit,
            durationMs = SystemClock.elapsedRealtime() - start
        )
    }
}
