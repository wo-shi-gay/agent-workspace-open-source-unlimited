package com.agentpad.workspace.ai

import java.io.File

/** Executes AI shell commands in the persistent, user-visible PRoot terminal. */
class SandboxRunner(
    private val sandboxDir: File,
    private val rootfs: File,
    private val nativeDir: File,
    private val initScript: File,
    private val projectDir: File,
    private val monitorSessionId: String = "ai-terminal"
) : TerminalBackend {
    companion object {
        @Volatile private var current: Process? = null

        fun abortCurrent() {
            runCatching { current?.destroy() }
            runCatching { current?.destroyForcibly() }
        }

        fun abortSession(sessionId: String): Boolean {
            abortCurrent()
            return TerminalService.interrupt(sessionId)
        }

        /** One-shot command helper used by plugin host.shellExec. */
        fun runOnceBlocking(context: android.content.Context, command: String, workspace: File): String? {
            return runCatching {
                val runner = SandboxRunner(
                    sandboxDir = SandboxInstaller.sandboxDir(context),
                    rootfs = SandboxInstaller.rootfsDir(context),
                    nativeDir = SandboxInstaller.nativeDir(context),
                    initScript = SandboxInstaller.initScript(context),
                    projectDir = workspace,
                    monitorSessionId = "plugin-shell",
                )
                val out = runner.run(TerminalRequest(command = command, cwd = workspace.absolutePath, timeoutMs = 120_000L))
                (out.stdout + out.stderr).trim().ifBlank { "(无输出，exit=${out.exitCode})" }
            }.getOrNull()
        }
    }

    override fun run(request: TerminalRequest): TerminalOutput {
        if (!sandboxDir.isDirectory) return TerminalOutput(-1, "", "sandbox missing: ${sandboxDir.absolutePath}")
        if (!rootfs.isDirectory) return TerminalOutput(-1, "", "rootfs missing: ${rootfs.absolutePath}")
        if (!nativeDir.isDirectory) return TerminalOutput(-1, "", "native dir missing: ${nativeDir.absolutePath}")
        if (!initScript.isFile) return TerminalOutput(-1, "", "init-sandbox.sh missing: ${initScript.absolutePath}")
        val command = request.command.trim()
        if (command.isEmpty()) return TerminalOutput(-1, "", "empty command")

        // Match the reference: send the raw command to the persistent shell.
        // The shell retains its cwd, including any cd issued by the user or AI.
        return TerminalService.executeOnSharedPty(
            monitorSessionId, "AI 终端", sandboxDir, rootfs, nativeDir, initScript,
            projectDir, command, (request.timeoutMs ?: 120_000L).coerceIn(1_000L, 600_000L)
        )
    }
}