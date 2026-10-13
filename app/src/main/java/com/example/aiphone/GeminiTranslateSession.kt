package com.example.aiphone

import android.util.Base64
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live Translate (gemini-3.5-live-translate-preview) の WebSocket クライアント。
 * https://ai.google.dev/gemini-api/docs/live-api/live-translate
 *
 * - 入力: PCM s16le mono 16kHz のみ(テキスト入力・システム指示・ツール・VAD 設定は使えない)
 * - 出力: PCM s16le mono 24kHz(翻訳音声) + 入出力の文字起こし
 * - 1 セッション = 翻訳先 1 言語。双方向なら 2 セッション必要。
 */
class GeminiTranslateSession(
    private val apiKey: String,
    private val targetLanguageCode: String,
    private val label: String = "",
    /** true: すでに翻訳先の言語で話された音声もそのまま復唱する。false: 無音にする(重複防止) */
    private val echoTargetLanguage: Boolean = false,
    private val onAudioOut: (ByteArray) -> Unit,
    private val onInputText: (String) -> Unit = {},
    /** 入力音声の言語(モデルが判定したコード)。文字起こしと一緒に届く */
    private val onInputLang: (String) -> Unit = {},
    private val onOutputText: (String) -> Unit = {},
    private val onReady: () -> Unit = {},
    private val onLog: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    /** 自分から close() していないのに接続が切れた(1 セッションにつき 1 回だけ)。引数は理由 */
    private val onDisconnected: (String) -> Unit = {},
) {
    companion object {
        const val MODEL = "gemini-3.5-live-translate-preview"
        /**
         * 各フィールドを generationConfig 配下(true)/ setup 直下(false)のどちらに置くか。
         * 公式ドキュメントの WebSocket 例と、実サーバーの要求が食い違っていたため(実機で 1007 になった)、
         * サーバーが「Unknown name "X" at '...'」で拒否したら、自動で反対側へ入れ替えて再接続する。
         */
        private val inGenConfig = java.util.concurrent.ConcurrentHashMap<String, Boolean>().apply {
            // 実機ログ(2026-10-12): 文字起こし2つを generationConfig 配下に置くとサーバーが 1007 で拒否した
            // (Unknown name "inputAudioTranscription" at 'setup.generation_config')。
            // setup 直下なら受理される。translationConfig だけ generationConfig 配下。
            put("inputAudioTranscription", false)
            put("outputAudioTranscription", false)
            put("translationConfig", true)
        }
        private val adapted = AtomicBoolean(false)
        private val UNKNOWN_FIELD = Regex("""Unknown name "(\w+)" at '([\w.]+)'""")

        /** 直前の切断が「配置の入れ替え」によるものなら true(1 回読むと false に戻る) */
        fun consumeSetupAdapted(): Boolean = adapted.getAndSet(false)

        private fun adaptFrom(reason: String): Boolean {
            var changed = false
            for (m in UNKNOWN_FIELD.findAll(reason)) {
                val name = m.groupValues[1]
                val at = m.groupValues[2]
                if (!inGenConfig.containsKey(name)) continue
                val newValue = when (at) {
                    "setup" -> true                    // setup 直下で拒否 → generationConfig へ
                    "setup.generation_config" -> false // generationConfig 配下で拒否 → setup 直下へ
                    else -> continue
                }
                if (inGenConfig[name] != newValue) {
                    inGenConfig[name] = newValue
                    changed = true
                }
            }
            return changed
        }
    }

    private var ws: WebSocket? = null
    private val open = AtomicBoolean(false)
    private val setupDone = AtomicBoolean(false)
    @Volatile private var closedByUser = false
    private val disconnectNotified = AtomicBoolean(false)
    /** 一度でも見た「メッセージの形(キーの並び)」。初めて見る形だけログに出し、想定外の返答を見つけられるようにする */
    private val seenShapes = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var inputTranscriptCount = 0
    @Volatile private var outputTranscriptCount = 0
    @Volatile private var audioInBytes = 0L
    @Volatile private var audioOutBytes = 0L
    @Volatile private var lastStatMs = 0L

    fun connect() {
        if (apiKey.isBlank()) {
            onError("APIキーが空です")
            return
        }
        val req = GeminiKeyChecker.liveRequest(apiKey)
        onLog("Translate 接続中... model=$MODEL target=$targetLanguageCode")
        ws = Http.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.set(true)
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleMessage(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // 生の close reason はログに出さず、setup 配置の自動修正に必要なフィールド名だけ残す。
                val safeReason = safeSchemaReason(reason)
                onLog("closing: code=$code${if (safeReason.isNotEmpty()) " $safeReason" else ""}")
                open.set(false)
                setupDone.set(false)
                webSocket.close(1000, null)
                notifyDisconnected("closing $code $safeReason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val safeReason = safeSchemaReason(reason)
                onLog("closed: code=$code${if (safeReason.isNotEmpty()) " $safeReason" else ""}")
                open.set(false)
                setupDone.set(false)
                notifyDisconnected("closed $code $safeReason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError("WebSocket失敗: ${t.javaClass.simpleName}")
                open.set(false)
                setupDone.set(false)
                notifyDisconnected("failure ${t.javaClass.simpleName}")
            }
        })
    }

    /** サーバーの close reason から、既知のスキーマ拒否パターンのみ抽出する。任意の本文は伝播させない。 */
    private fun safeSchemaReason(reason: String): String = UNKNOWN_FIELD.find(reason)?.let {
        "Unknown name \"${it.groupValues[1]}\" at '${it.groupValues[2]}'"
    }.orEmpty()

    private fun notifyDisconnected(reason: String) {
        if (closedByUser) return
        if (disconnectNotified.compareAndSet(false, true)) {
            if (adaptFrom(reason)) {
                adapted.set(true)
                onLog("setup のフィールド配置をサーバーの要求に合わせて入れ替えました: $inGenConfig")
            }
            onDisconnected(reason)
        }
    }

    private fun sendSetup(webSocket: WebSocket) {
        val gen = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
        val setup = JSONObject().put("model", "models/$MODEL")
        fun place(name: String, value: JSONObject) {
            if (inGenConfig[name] == true) gen.put(name, value) else setup.put(name, value)
        }
        place("inputAudioTranscription", JSONObject())
        place("outputAudioTranscription", JSONObject())
        place(
            "translationConfig",
            JSONObject()
                .put("targetLanguageCode", targetLanguageCode)
                .put("echoTargetLanguage", echoTargetLanguage),
        )
        setup.put("generationConfig", gen)
        onLog("setup 送信: model=$MODEL target=$targetLanguageCode echo=$echoTargetLanguage 配置(genConfig=true)=$inGenConfig")
        webSocket.send(JSONObject().put("setup", setup).toString())
    }

    /** 初めて見るメッセージの形だけログに出す(音声データ本体は含めない)。原文・訳文が来ない原因の切り分け用 */
    private fun logShape(root: JSONObject) {
        val sc = root.optJSONObject("serverContent")
        val shape = root.keys().asSequence().sorted().joinToString(",") +
            (if (sc != null) " / serverContent:" + sc.keys().asSequence().sorted().joinToString(",") else "")
        if (!seenShapes.add(shape)) return
        // serverContent 全体には文字起こしや翻訳文が含まれることがあるため、値は記録しない。
        onLog("受信の形(初回): $shape")
    }

    private fun handleMessage(text: String) {
        try {
            val root = JSONObject(text)
            logShape(root)
            if (root.has("setupComplete")) {
                setupDone.set(true)
                onLog("setupComplete")
                onReady()
                return
            }
            if (root.has("goAway")) {
                onLog("goAway: ${root.optJSONObject("goAway")?.optString("timeLeft", "")}")
                return
            }
            if (root.has("error")) {
                onError("Translate error response received")
                return
            }
            val sc = root.optJSONObject("serverContent") ?: return
            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    val b64 = inline.optString("data", "")
                    if (b64.isEmpty()) continue
                    val pcm = Base64.decode(b64, Base64.DEFAULT)
                    if (pcm.isNotEmpty()) {
                        audioOutBytes += pcm.size
                        onAudioOut(pcm)
                        maybeStat()
                    }
                }
            }
            sc.optJSONObject("inputTranscription")?.let { o ->
                o.optString("text", "").takeIf { it.isNotEmpty() }?.let {
                    inputTranscriptCount++
                    // 文字起こし本文は個人情報を含む可能性があるため、ログには言語コードと文字数だけ残す。
                    onLog("input transcription: lang=${o.optString("languageCode", "?")} chars=${it.length}")
                    onInputText(it)
                    o.optString("languageCode", "").takeIf { c -> c.isNotEmpty() }?.let { c -> onInputLang(c) }
                }
            }
            sc.optJSONObject("outputTranscription")?.let { o ->
                o.optString("text", "").takeIf { it.isNotEmpty() }?.let {
                    outputTranscriptCount++
                    onLog("output transcription: lang=${o.optString("languageCode", "?")} chars=${it.length}")
                    onOutputText(it)
                }
            }
        } catch (e: Exception) {
            onLog("msg parse: ${e.javaClass.simpleName}")
        }
    }

    /** PCM s16le mono を送信。翻訳モデルは 16kHz のみ想定。100ms 前後のチャンクが推奨。 */
    fun sendAudioPcm(pcm: ByteArray, rateHz: Int = 16000) {
        if (!open.get() || !setupDone.get()) return
        val w = ws ?: return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        w.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=$rateHz\"}}}")
        audioInBytes += pcm.size
        maybeStat()
    }

    private fun maybeStat() {
        val now = System.currentTimeMillis()
        if (now - lastStatMs > 5000) {
            lastStatMs = now
            onLog("I/O: sent=${audioInBytes}B recv=${audioOutBytes}B 原=${inputTranscriptCount}件 訳=${outputTranscriptCount}件 ready=${setupDone.get()}")
        }
    }

    fun isReady(): Boolean = open.get() && setupDone.get()

    fun close() {
        closedByUser = true
        try { ws?.close(1000, "bye") } catch (_: Throwable) {}
        ws = null
        open.set(false)
        setupDone.set(false)
    }
}
