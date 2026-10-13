package com.example.aiphone

/**
 * AI が「本人の判断が必要」と呼んだ ask_user の待ち行列。
 * Activity が閉じていても保持され、通話終了時に clear される。
 */
object AskUserStore {
    class Request(
        val id: String,
        val question: String,
        val options: List<String>,
        /** 個人情報の開示申請(request_personal_info)のとき、申請された項目キー。通常の ask_user は null */
        val piiFields: List<String>? = null,
        /** 同じ申請に含まれる「許可不要」項目。カードで許可されたときだけ一緒に返す */
        val autoFields: List<String> = emptyList(),
        /** テキストで AI に返すとき(cancelled / フォールバック)に質問の代わりに使う説明 */
        val aiContext: String? = null,
    ) {
        val isPii: Boolean get() = piiFields != null
        val time: Long = System.currentTimeMillis()
        /** AI 側がツール呼び出しを取り消した(回答は toolResponse ではなくテキストで送る) */
        @Volatile var cancelled: Boolean = false
        /** 送信処理中の二重送信を防ぐ。失敗した場合は false に戻して再試行可能にする。 */
        @Volatile var responding: Boolean = false
    }

    private val queue = ArrayList<Request>()
    @Volatile var listener: (() -> Unit)? = null

    /**
     * 未回答の確認が1件でもあるうちは新しい質問を積まない(同じ質問の重複も、これで拒否される)。
     * @return 追加できたとき true
     */
    @Synchronized
    fun add(r: Request): Boolean {
        if (queue.isNotEmpty()) return false
        queue.add(r)
        listener?.invoke()
        return true
    }

    @Synchronized fun current(): Request? = queue.firstOrNull()
    @Synchronized fun count(): Int = queue.size

    /** 送信開始時に呼び出す。成功するまで依頼自体はキューに残す。 */
    @Synchronized
    fun beginAnswer(id: String): Request? {
        val r = queue.firstOrNull { it.id == id } ?: return null
        if (r.responding) return null
        r.responding = true
        listener?.invoke()
        return r
    }

    /** 送信成功時のみ依頼を削除する。失敗時は再回答できるように保持する。 */
    @Synchronized
    fun finishAnswer(id: String, succeeded: Boolean): Boolean {
        val r = queue.firstOrNull { it.id == id } ?: return false
        if (succeeded) queue.remove(r) else r.responding = false
        listener?.invoke()
        return true
    }

    @Synchronized
    fun remove(id: String): Request? {
        val r = queue.firstOrNull { it.id == id } ?: return null
        queue.remove(r)
        listener?.invoke()
        return r
    }

    @Synchronized
    fun markCancelled(id: String) {
        queue.firstOrNull { it.id == id }?.cancelled = true
    }

    /** 再接続などでツール呼び出しの ID が無効になったとき、回答をテキストで送るように切り替える */
    @Synchronized
    fun markAllCancelled() {
        queue.forEach { it.cancelled = true }
    }

    @Synchronized
    fun clear() {
        if (queue.isEmpty()) return
        queue.clear()
        listener?.invoke()
    }
}
