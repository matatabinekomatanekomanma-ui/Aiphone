package com.example.aiphone

import java.util.concurrent.CopyOnWriteArrayList

/** 準備タブ（Gemma）の対話ログ。通話終了では消さない。 */
object PrepChatStore {
    const val USER = "user"
    const val MODEL = "model"
    const val SYSTEM = "system"

    class Msg(val role: String, var text: String, val time: Long = System.currentTimeMillis())

    private const val MAX = 80
    val messages = CopyOnWriteArrayList<Msg>()

    @Volatile var listener: (() -> Unit)? = null

    @Synchronized
    fun add(role: String, text: String) {
        if (text.isBlank()) return
        messages.add(Msg(role, text.trim()))
        while (messages.size > MAX) messages.removeAt(0)
        listener?.invoke()
    }

    @Synchronized
    fun clear() {
        messages.clear()
        listener?.invoke()
    }

    /** Gemma API の contents 配列用（system は除外） */
    fun toApiContents(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (m in messages) {
            if (m.role == SYSTEM) continue
            val role = if (m.role == USER) "user" else "model"
            arr.put(org.json.JSONObject().apply {
                put("role", role)
                put("parts", org.json.JSONArray().put(org.json.JSONObject().put("text", m.text)))
            })
        }
        return arr
    }
}
