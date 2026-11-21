package com.agentpad.workspace.floating

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.agentpad.workspace.data.ChatStore
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

/** A small view of the existing chat, never a second inference engine or history store. */
object FloatingSessionHub {
    interface Host {
        fun floatingBusy(): Boolean
        fun floatingSend(text: String): Boolean
        fun floatingStop()
    }
    data class Snapshot(val sessionId: String = "", val title: String = "对话小窗",
        val content: String = "", val busy: Boolean = false, val connected: Boolean = false, val messages: List<ChatStore.Message> = emptyList(),
        val mode: String = "chat", val model: String = "", val reasoning: String = "AUTO", val hasOlder: Boolean = false)

    val state = MutableStateFlow(Snapshot())
    @Volatile var selectedId = ""
        private set
    private val hosts = ConcurrentHashMap<String, WeakReference<Host>>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val scheduled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var windowCount = 32
    @Volatile var showing = false
    fun loadOlder(context: Context) { windowCount += 32; refresh(context, selectedId, true) }
    private val lastUpdate = ConcurrentHashMap<String, Long>()

    fun attach(context: Context, id: String, host: Host) {
        hosts[id] = WeakReference(host)
        select(context, id)
    }
    fun detach(context: Context, id: String, host: Host) {
        if (hosts[id]?.get() === host) hosts.remove(id)
        refresh(context, id, true)
    }
    fun select(context: Context, id: String) {
        if (selectedId != id) windowCount = 32
        selectedId = id
        refresh(context, id, true)
    }
    fun connected(id: String) = hosts[id]?.get() != null
    fun isBusy(id: String): Boolean = hosts[id]?.get()?.floatingBusy() == true

    fun refresh(context: Context, id: String, force: Boolean = false) {
        if (id != selectedId || id.isBlank()) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - (lastUpdate[id] ?: 0L) < 300) return
        if (!scheduled.add(id)) return
        lastUpdate[id] = now
        val app = context.applicationContext
        io.execute {
            try {
                val session = ChatStore.loadSession(app, id) ?: return@execute
                val recent = session.messages.takeLast(windowCount).map { message ->
                    message.copy(parts = message.parts.map { part ->
                        if (part.type == "tool" && part.toolName != "ask_user") part.copy(toolArguments = "", toolResult = "") else part
                    }, alternatives = emptyList())
                }
                val model = ChatStore.api(app).third
                val reasoning = ChatStore.reasoningLevel(app)
                main.post {
                    if (id == selectedId) {
                        val host = hosts[id]?.get()
                        state.value = Snapshot(id, session.title, "", host?.floatingBusy() == true, host != null, recent, session.mode, model, reasoning, session.messages.size > recent.size)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("FloatingChat", "snapshot failed", e)
            } finally { scheduled.remove(id) }
        }
    }

    fun send(id: String, text: String): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return hosts[id]?.get()?.floatingSend(text) == true
    }
    fun stop(id: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) hosts[id]?.get()?.floatingStop()
        else main.post { hosts[id]?.get()?.floatingStop() }
    }
}