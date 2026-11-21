package com.agentpad.workspace.ai

import java.io.File

class SandboxBackend(
    private val sandboxDir: File,
    private val rootfs: File,
    private val prootBinary: File,
    private val entryScript: File? = null
) : TerminalBackend {
    override fun run(request: TerminalRequest): TerminalOutput {
        if (!sandboxDir.exists()) return TerminalOutput(-1, "", "sandbox missing: ${sandboxDir.absolutePath}")
        if (!rootfs.exists()) return TerminalOutput(-1, "", "rootfs missing: ${rootfs.absolutePath}")
        if (!prootBinary.exists()) return TerminalOutput(-1, "", "proot missing: ${prootBinary.absolutePath}")
        val command = request.command.trim()
        if (command.isEmpty()) return TerminalOutput(-1, "", "empty command")
        return TerminalOutput(-1, "", "sandbox command staged: $command")
    }
}