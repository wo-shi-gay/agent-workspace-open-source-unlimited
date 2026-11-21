package com.agentpad.workspace.ai

import com.agentpad.workspace.terminal.AnsiScreen
import java.util.concurrent.CopyOnWriteArrayList

/** Application-level terminal event hub shared by AI tools and terminal UI. */
object TerminalMonitor {
    data class Session(
        val id: String,
        val title: String,
        val cwd: String,
        val command: String = "",
        val output: String = "",
        val running: Boolean = false,
        val exitCode: Int? = null,
        /** Live emulator for this session's UI; null only before start(). */
        val screen: AnsiScreen? = null
    )

    private val listeners = CopyOnWriteArrayList<(Session) -> Unit>()
    private val sessions = LinkedHashMap<String, Session>()

    @Synchronized
    fun update(session: Session) {
        sessions[session.id] = session
        listeners.forEach { it(session) }
    }

    @Synchronized
    fun snapshot(): List<Session> = sessions.values.toList()

    fun observe(listener: (Session) -> Unit): () -> Unit {
        listeners += listener
        snapshot().forEach(listener)
        return { listeners -= listener }
    }
}
