package com.example.aiphone

import java.util.concurrent.CopyOnWriteArrayList

/** 通話中の会話ログ（相手 / AI）。Activity が閉じていても保持される。 */
object ChatStore {
    const val PEER = "peer"
    const val AI = "ai"
    const val SYSTEM = "system"

    class Msg(val role: String, var text: String, val time: Long = System.currentTimeMillis()) {
        @Volatile var open: Boolean = true
    }

    private const val MAX = 300
    val messages = CopyOnWriteArrayList<Msg>()

    /** (msg, isNew) */
    @Volatile var listener: ((Msg, Boolean) -> Unit)? = null
    @Volatile var onCleared: (() -> Unit)? = null

    @Synchronized
    fun append(role: String, chunk: String) {
        if (chunk.isEmpty()) return
        val last = messages.lastOrNull()
        if (last != null && last.open && last.role == role) {
            last.text += chunk
            listener?.invoke(last, false)
            return
        }
        last?.open = false
        val m = Msg(role, chunk)
        messages.add(m)
        while (messages.size > MAX) messages.removeAt(0)
        listener?.invoke(m, true)
    }

    @Synchronized
    fun closeTurn() {
        messages.lastOrNull()?.open = false
    }

    @Synchronized
    fun addSystem(text: String) {
        messages.lastOrNull()?.open = false
        val m = Msg(SYSTEM, text).also { it.open = false }
        messages.add(m)
        listener?.invoke(m, true)
    }

    @Synchronized
    fun clear() {
        messages.clear()
        onCleared?.invoke()
    }
}
