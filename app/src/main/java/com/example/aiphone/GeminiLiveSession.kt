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
 * Gemini 3.8 Live (BidiGenerateContent) WebSocket クライアント。
 * 入力: PCM s16le mono (任意レート → mimeType で通知。推奨 16kHz)
 * 出力: PCM s16le mono 24kHz
 */
class GeminiLiveSession(
    private val apiKey: String,
    private val onAudioOut: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val systemInstruction: String = "",
    private val voiceName: String = GeminiVoices.DEFAULT,
    /** role = ChatStore.AI / ChatStore.PEER、text は逐次の断片 */
    private val onTranscript: (String, String) -> Unit = { _, _ -> },
    private val onTurnComplete: () -> Unit = {},
    /** 相手/こちらの発話で AI の発話が中断された（再生中・送信待ちの音声を破棄すること） */
    private val onInterrupted: () -> Unit = {},
    private val onReady: () -> Unit = {},
    /** AI がツール(ask_user など)を呼んだ: (id, name, args) */
    private val onToolCall: (String, String, JSONObject) -> Unit = { _, _, _ -> },
    /** AI 側が取り消したツール呼び出しの ID */
    private val onToolCancel: (List<String>) -> Unit = {},
    /** true のときだけ ask_user ツールを宣言する(通話本番用。試聴では false) */
    private val enableAskUser: Boolean = false,
    /** 音声検出(VAD)の設定。設定画面で調整できる */
    private val vad: VadSettings = VadSettings(),
    /** 自分から close() していないのに接続が切れた(1セッションにつき1回だけ呼ぶ)。引数は理由 */
    private val onDisconnected: (String) -> Unit = {},
    /** サーバーからの終了予告(goAway)。引数は残り時間(ミリ秒、不明なら -1) */
    private val onGoAway: (Long) -> Unit = {},
    /** アプリの表示言語。既定の話す言語にも使い、ユーザーの明示的なプロンプト指定は優先する */
    private val languageCode: String = "ja"
) {
    companion object {
        const val TOOL_ASK_USER = "ask_user"
        /** 通話本番・APIキー検証で共通のモデル */
        const val MODEL = "gemini-3.8-live"
        const val TOOL_REQUEST_PII = "request_personal_info"

        /** 直前の既定(「もしもし」「相手が話し終えてから」を含む版)。保存されていたら新しい既定へ置き換える */
        private const val V3_DEFAULT_PROMPT =
            "あなたは本人の代わりに電話をかけているアシスタントです。声だけの会話です。\n" +
            "・話し言葉で、1回に1〜2文だけ短く話す。\n" +
            "・相手が出たら、最初に「もしもし」とだけ言って、相手の返事を待つ。\n" +
            "・相手が話し終えてから話す。相手の言葉をくり返さない。\n" +
            "・用件が指示に書かれているときだけ、それを伝える。無いときは自分で作らない。\n" +
            "・分からない・知らない・迷うときは、推測で答えず本人に確認する(下記ルールに従う)。\n" +
            "・用件が済んだらお礼を言って終わる。"

        /** 直前まで使っていた既定プロンプト（名前入り）。保存されていたら新しい既定へ置き換える */
        private val PREVIOUS_DEFAULT_PROMPT =
            "あなたは【本人の名前】の代わりに電話をかけているアシスタントです。声だけの会話です。\n" +
            "・話し言葉で、1回に1〜2文だけ短く話す。\n" +
            "・相手が話し終えてから話す。相手の言葉をくり返さない。\n" +
            "・最初に「【本人の名前】の代理でお電話しています」と名乗り、用件を伝える。\n" +
            "・聞き取れなかったら聞き返す。\n" +
            "・知らないことは作らず、「確認します」と言う。\n" +
            "・用件が済んだらお礼を言って終わる。"

        /** 1つ前の既定(個人情報は言わない版)。保存されていたら新しい既定へ置き換える */
        private val V2_DEFAULT_PROMPT =
            "あなたは本人の代わりに電話をかけているアシスタントです。声だけの会話です。\n" +
            "・話し言葉で、1回に1〜2文だけ短く話す。\n" +
            "・相手が出たら、最初に「もしもし」とだけ言って、相手の返事を待つ。\n" +
            "・名前を含む個人情報は、本人の確認が取れるまで自分から言わない。\n" +
            "・相手が話し終えてから話す。相手の言葉をくり返さない。\n" +
            "・聞き取れなかったら聞き返す。\n" +
            "・知らないことは作らず、「確認します」と言う。\n" +
            "・用件が済んだらお礼を言って終わる。"

        /** 旧バージョンの既定プロンプト（設定に保存されていたら新しい既定へ置き換える） */
        private val LEGACY_DEFAULT_PROMPT =
            "あなたは【本人の名前】の電話代行AIです。本人の代理として、こちらからかけた電話に出ています。\n" +
            "相手は会社・店・知人などです。短く丁寧に話してください。\n\n" +
            "【開始】\n" +
            "相手が出たら「【本人の名前】の代理でお電話しました」と名乗り、用件を伝える。" +
            "用件がプロンプトに無いときは、相手の部署や担当を確認する。\n\n" +
            "【話し方】\n" +
            "・1〜2文ずつ。相手の発言を復唱しない。\n" +
            "・聞き取れなければ「恐れ入ります、もう一度お願いできますか」と一度だけ聞き返す。\n" +
            "・知らないこと・判断できないことは作らない。本人確認が必要なら ask_user を使う。\n" +
            "・関数は一度に1つだけ呼ぶ。複数を同時に呼ばない。両方必要なときは、判断に迷う方(ask_user)を先に呼び、結果を受けてから次を呼ぶ。\n\n" +
            "【終了】\n" +
            "用件が済んだら「お電話ありがとうございました。失礼いたします」で終える。"

        fun defaultPrompt(languageCode: String): String = PromptLocalization.phoneDefaultPrompt(languageCode)

        fun isLegacyDefault(prompt: String): Boolean {
            val normalized = prompt.trim()
            return normalized == LEGACY_DEFAULT_PROMPT.trim() || normalized == PREVIOUS_DEFAULT_PROMPT.trim() ||
                normalized == V2_DEFAULT_PROMPT.trim() || normalized == V3_DEFAULT_PROMPT.trim() ||
                listOf("ja", "en", "zh-CN", "zh-TW", "ko", "es").any {
                    normalized == PromptLocalization.phoneDefaultPrompt(it).trim()
                }
        }

        const val WS_BASE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** 文字起こし受信の診断イベントだけを debug ビルドで記録する(本文は記録しない)。 */
        private val VERBOSE = BuildConfig.DEBUG
    }

    private var ws: WebSocket? = null
    private val open = AtomicBoolean(false)
    private val setupDone = AtomicBoolean(false)
    /** close() を自分で呼んだ。以降の切断通知は出さない */
    @Volatile private var closedByUser = false
    private val disconnectNotified = AtomicBoolean(false)
    @Volatile private var audioInBytes = 0L
    @Volatile private var audioOutBytes = 0L
    @Volatile private var lastStatMs = 0L

    fun connect() {
        if (apiKey.isBlank()) {
            onError("APIキーが空です")
            return
        }
        val req = GeminiKeyChecker.liveRequest(apiKey)
        onLog("Gemini Live 接続中... model=$MODEL")
        ws = Http.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onLog("Gemini WebSocket open")
                open.set(true)
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onLog("Gemini closing: code=$code")
                open.set(false)
                setupDone.set(false)
                webSocket.close(1000, null)
                notifyDisconnected("closing $code")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onLog("Gemini closed: code=$code")
                open.set(false)
                setupDone.set(false)
                notifyDisconnected("closed $code")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError("Gemini WebSocket失敗: ${t.javaClass.simpleName}")
                open.set(false)
                setupDone.set(false)
                notifyDisconnected("failure ${t.javaClass.simpleName}")
            }
        })
    }

    /** 自分で閉じたとき以外の切断を、1回だけ通知する */
    private fun notifyDisconnected(reason: String) {
        if (closedByUser) return
        if (disconnectNotified.compareAndSet(false, true)) onDisconnected(reason)
    }

    private fun sendSetup(webSocket: WebSocket) {
        val configuredInstruction = systemInstruction.ifBlank { PromptLocalization.phoneDefaultPrompt(languageCode) }
        // 公式 API: responseModalities は generationConfig 配下
        // https://ai.google.dev/api/live#BidiGenerateContentSetup
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/$MODEL")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName))))
                })
                put("systemInstruction", JSONObject().apply {
                    val body = if (enableAskUser) configuredInstruction + PromptLocalization.askUserGuide(languageCode) else configuredInstruction
                    val full = PromptLocalization.speakingLanguageRule(languageCode) + body
                    put("parts", JSONArray().put(JSONObject().put("text", full)))
                })
                // 音声検出(VAD): 設定画面の値
                put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", vad.toJson()))
                // コンテキストウィンドウ圧縮(スライディングウィンドウ): 音声のみ15分の制限を超えて通話を続ける
                put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
                // 文字起こし（ログ用）
                put("outputAudioTranscription", JSONObject())
                put("inputAudioTranscription", JSONObject())
                if (enableAskUser) put("tools", JSONArray().put(toolDeclarations(languageCode)))
            })
        }
        val payload = setup.toString()
        // システム指示には本人の個人情報が入るため、内容はログに出さない
        onLog("Gemini setup 送信: model=$MODEL voice=$voiceName instruction=${configuredInstruction.length}文字 askUser=$enableAskUser vad=${vad.toJson()}")
        webSocket.send(payload)
    }

    private fun handleMessage(text: String) {
        try {
            val root = JSONObject(text)
            if (root.has("setupComplete")) {
                setupDone.set(true)
                onLog("Gemini setupComplete")
                onReady()
                return
            }
            if (root.has("goAway")) {
                val left = root.optJSONObject("goAway")?.optString("timeLeft", "").orEmpty()
                val ms = left.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() } ?: -1L
                onLog("Gemini goAway: timeLeft=$left")
                onGoAway(ms)
                return
            }
            if (root.has("toolCall")) {
                val calls = root.getJSONObject("toolCall").optJSONArray("functionCalls")
                if (calls != null) {
                    for (i in 0 until calls.length()) {
                        val c = calls.getJSONObject(i)
                        val args = c.optJSONObject("args") ?: JSONObject()
                        // Function 引数には氏名・用件などが含まれる可能性があるため、値はログに出さない。
                        onLog("Gemini toolCall: name=${c.optString("name")} argKeys=${args.keys().asSequence().toList()}")
                        onToolCall(c.optString("id", ""), c.optString("name", ""), args)
                    }
                }
                return
            }
            if (root.has("toolCallCancellation")) {
                val ids = root.getJSONObject("toolCallCancellation").optJSONArray("ids")
                val list = ArrayList<String>()
                if (ids != null) for (i in 0 until ids.length()) list.add(ids.optString(i))
                onLog("Gemini toolCallCancellation: $list")
                onToolCancel(list)
                return
            }
            // interactionStatus は root または serverContent に付くことがある
            val interactionStatus = root.optString("interactionStatus", "").ifEmpty { null }
                ?: root.optJSONObject("serverContent")?.optString("interactionStatus", "")?.ifEmpty { null }
            if (!interactionStatus.isNullOrEmpty()) {
                onLog("Gemini interactionStatus=$interactionStatus")
            }
            if (root.has("serverContent")) {
                val sc = root.getJSONObject("serverContent")
                if (sc.has("modelTurn")) {
                    val parts = sc.getJSONObject("modelTurn").optJSONArray("parts") ?: return
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.has("inlineData")) {
                            val id = part.getJSONObject("inlineData")
                            val b64 = id.optString("data", "")
                            if (b64.isNotEmpty()) {
                                val pcm = Base64.decode(b64, Base64.DEFAULT)
                                if (pcm.isNotEmpty()) {
                                    audioOutBytes += pcm.size
                                    onAudioOut(pcm)
                                    maybeStat()
                                }
                            }
                        }
                    }
                }
                if (sc.has("outputTranscription")) {
                    val t = sc.getJSONObject("outputTranscription").optString("text", "")
                    if (t.isNotEmpty()) {
                        if (VERBOSE) onLog("AI transcript received: chars=${t.length}")
                        onTranscript("ai", t)
                    }
                }
                if (sc.has("inputTranscription")) {
                    val t = sc.getJSONObject("inputTranscription").optString("text", "")
                    if (t.isNotEmpty()) {
                        if (VERBOSE) onLog("peer transcript received: chars=${t.length}")
                        onTranscript("peer", t)
                    }
                }
                if (sc.optBoolean("interrupted", false)) {
                    onLog("Gemini interrupted (割り込み検知)")
                    onInterrupted()
                    onTurnComplete()
                } else if (sc.optBoolean("turnComplete", false)) {
                    onTurnComplete()
                }
            }
            if (root.has("error")) {
                onError("Gemini error response received")
            }
        } catch (e: Exception) {
            onLog("Gemini msg parse: ${e.javaClass.simpleName}")
        }
    }

    /** ask_user / request_personal_info の説明も表示言語に合わせる。 */
    private fun toolDeclarations(languageCode: String): JSONObject {
        val text = when (PromptLocalization.normalizeCode(languageCode)) {
            "ja" -> listOf(
                "相手の話が分からない・用件とかみ合わない・指示に無いことを聞かれた・本人の判断が要る・想定外の状況のときに呼ぶ。推測で答える代わりに必ずこれを呼ぶ。声を出す前に先に呼び、本人が選んだ返答を待つ。",
                "相手が何を言ったか、何が分からない／決められないかを短く1〜2文で説明する。",
                "相手にそのまま伝えられる短い返答候補を、内容が異なる3つ用意する。",
                "本人の個人情報を相手に伝える必要がある場合だけ呼ぶ。本人が許可した項目の値だけが返る。許可されなければ値は返らず、許可なしに個人情報を言ってはいけない。",
                "必要な項目だけを指定し、多めに申請しない。",
                "相手が何のために何を求めているかを短い1文で説明する。"
            )
            "zh-CN" -> listOf(
                "当你听不懂对方、对话与既定事项不符、被问及指令中没有的信息、需要用户作出判断或遇到意外情况时调用。不要猜测；先调用此工具，再开口，并等待用户选择的答复。",
                "用一到两句简短说明对方说了什么，以及具体哪里不清楚或无法决定。",
                "提供三个简短且内容不同、可以直接对对方说的答复选项。",
                "仅在需要向对方提供用户个人信息时调用。只会返回用户授权字段的值；未经授权不会返回值，也不得披露个人信息。",
                "只申请确实需要的字段，不要申请过多字段。",
                "用一句简短的话说明对方为了什么目的要求什么信息。"
            )
            "zh-TW" -> listOf(
                "當你聽不懂對方、對話與既定事項不符、被問及指示中沒有的資訊、需要使用者作出判斷或遇到意外情況時呼叫。不要猜測；先呼叫此工具再開口，並等待使用者選擇的答覆。",
                "用一到兩句簡短說明對方說了什麼，以及具體哪裡不清楚或無法決定。",
                "提供三個簡短且內容不同、可以直接對對方說的答覆選項。",
                "僅在需要向對方提供使用者個人資訊時呼叫。只會回傳使用者授權欄位的值；未經授權不會回傳值，也不得揭露個人資訊。",
                "只申請確實需要的欄位，不要申請過多欄位。",
                "用一句簡短的話說明對方為了什麼目的要求什麼資訊。"
            )
            "ko" -> listOf(
                "상대방의 말을 이해하지 못했거나 용건과 맞지 않거나 지시에 없는 질문을 받았거나 사용자의 판단이 필요하거나 예상치 못한 상황일 때 호출합니다. 추측하지 말고 말하기 전에 먼저 호출한 뒤 사용자가 선택한 답변을 기다립니다.",
                "상대방이 무엇을 말했는지와 무엇이 불분명하거나 결정할 수 없는지 한두 문장으로 설명합니다.",
                "상대방에게 그대로 말할 수 있는 짧고 서로 다른 답변 선택지 세 개를 제공합니다.",
                "상대방에게 사용자의 개인정보를 전달해야 할 때만 호출합니다. 사용자가 허용한 항목의 값만 반환되며, 허용되지 않은 값은 반환되지 않고 개인정보를 공개해서도 안 됩니다.",
                "필요한 항목만 요청하고 너무 많은 항목을 요청하지 않습니다.",
                "상대방이 어떤 목적으로 무엇을 요청했는지 한 문장으로 설명합니다."
            )
            "es" -> listOf(
                "Llama a esta herramienta si no entiendes a la otra persona, la conversación no coincide con el motivo indicado, preguntan algo no contemplado, se necesita una decisión del usuario o surge una situación inesperada. No adivines: llama primero, antes de hablar, y espera la respuesta elegida por el usuario.",
                "Explica en una o dos frases breves qué ha dicho la otra persona y qué no está claro o no se puede decidir.",
                "Ofrece tres respuestas breves y distintas que se puedan decir directamente a la otra persona.",
                "Llama solo si necesitas comunicar datos personales del usuario a la otra persona. Solo se devuelven los valores autorizados; sin autorización no se devuelve ningún valor y no debes revelar datos personales.",
                "Solicita solo los campos necesarios; no pidas demasiados.",
                "Explica en una frase breve para qué propósito solicita algo la otra persona."
            )
            else -> listOf(
                "Call this tool if you do not understand the other person, the conversation does not match the stated purpose, they ask about something not in the instructions, a user decision is needed, or an unexpected situation occurs. Do not guess. Call it before speaking and wait for the user's selected response.",
                "Briefly explain in one or two sentences what the other person said and what is unclear or cannot be decided.",
                "Provide three short, distinct response options that can be said directly to the other person.",
                "Call only when personal information must be communicated to the other person. Only values for user-authorized fields are returned; no value is returned without authorization, and you must not disclose personal information without permission.",
                "Request only the fields that are necessary; do not request too many.",
                "Briefly explain in one sentence what the other person is requesting and for what purpose."
            )
        }
        return JSONObject().put("functionDeclarations", JSONArray()
            .put(JSONObject().apply {
                put("name", TOOL_ASK_USER)
                put("description", text[0])
                put("behavior", "NON_BLOCKING")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("question", JSONObject().put("type", "STRING").put("description", text[1]))
                        put("options", JSONObject().put("type", "ARRAY").put("description", text[2])
                            .put("items", JSONObject().put("type", "STRING")))
                    })
                    put("required", JSONArray().put("question").put("options"))
                })
            })
            .put(JSONObject().apply {
                put("name", TOOL_REQUEST_PII)
                put("description", text[3])
                put("behavior", "NON_BLOCKING")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("fields", JSONObject().put("type", "ARRAY").put("description", text[4])
                            .put("items", JSONObject().put("type", "STRING").put("enum", JSONArray(PersonalInfo.FIELD_KEYS))))
                        put("reason", JSONObject().put("type", "STRING").put("description", text[5]))
                    })
                    put("required", JSONArray().put("fields").put("reason"))
                })
            }))
    }

    /** ツール結果を返す。scheduling は response 内に入れる(WHEN_IDLE=話し終えてから反応) */
    fun sendToolResponse(id: String, name: String, response: JSONObject): Boolean {
        if (!open.get() || !setupDone.get()) return false
        val w = ws ?: return false
        val msg = JSONObject().put("toolResponse", JSONObject().put("functionResponses",
            JSONArray().put(JSONObject().put("id", id).put("name", name).put("response", response))))
        // toolResponse は個人情報を含むことがある。接続状態だけを記録し、ペイロードは記録しない。
        onLog("Gemini toolResponse queued: id=$id name=$name")
        return w.send(msg.toString())
    }

    /** テキスト入力（試聴用: 「相手の第一声」として送る） */
    fun sendText(text: String): Boolean {
        if (!open.get() || !setupDone.get()) return false
        val w = ws ?: return false
        val msg = JSONObject().put("realtimeInput", JSONObject().put("text", text))
        return w.send(msg.toString())
    }

    /** PCM s16le mono を送信。rateHz は実際のサンプルレート (例: 16000) */
    fun sendAudioPcm(pcm: ByteArray, rateHz: Int = 16000) {
        if (!open.get() || !setupDone.get()) return
        val w = ws ?: return
        // 20〜25 回/秒呼ばれるため JSONObject は組まず文字列で直接作る（Base64 NO_WRAP は JSON エスケープ不要）
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        w.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=$rateHz\"}}}")
        audioInBytes += pcm.size
        maybeStat()
    }

    private fun maybeStat() {
        val now = System.currentTimeMillis()
        if (now - lastStatMs > 5000) {
            lastStatMs = now
            onLog("Gemini I/O: sent=${audioInBytes}B recv=${audioOutBytes}B ready=${setupDone.get()}")
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

/** s16le mono の 1 サンプルを符号付きで読む（上位バイトは符号拡張されるのでそのまま -32768..32767） */
private fun ByteArray.s16(i: Int): Int = (this[i].toInt() and 0xff) or (this[i + 1].toInt() shl 8)

private fun ByteArray.putS16(i: Int, v: Int) {
    this[i] = (v and 0xff).toByte()
    this[i + 1] = ((v shr 8) and 0xff).toByte()
}

/**
 * 48kHz → 16kHz（Gemini への送信用）。3 サンプル平均（簡易ローパス付き）で間引く。
 * 読み取りサイズが 3 サンプルの倍数でなくても、余りを次回へ持ち越すので波形が欠けない。
 * 1 セッション(1 スレッド)で 1 インスタンスを使うこと。
 */
class Downsampler3x {
    private val pending = IntArray(2)
    private var pendingN = 0

    fun process(input: ByteArray, len: Int = input.size): ByteArray {
        val n = len / 2
        val total = pendingN + n
        val outN = total / 3
        val out = ByteArray(outN * 2)
        // 仮想列: [持ち越し pendingN 個] + [input の n 個]
        fun at(j: Int): Int = if (j < pendingN) pending[j] else input.s16((j - pendingN) * 2)
        for (o in 0 until outN) {
            out.putS16(o * 2, (at(o * 3) + at(o * 3 + 1) + at(o * 3 + 2)) / 3)
        }
        // 余り(0..2 個)を次回へ
        val used = outN * 3
        val rest = total - used
        val r0 = if (rest > 0) at(used) else 0
        val r1 = if (rest > 1) at(used + 1) else 0
        pending[0] = r0
        pending[1] = r1
        pendingN = rest
        return out
    }
}

/**
 * 24kHz → 48kHz（Gemini の出力を通話経路へ入れる用）。整数倍なので 2 倍線形補間。
 * 各サンプルの直後に「次のサンプルとの中間値」を入れる（末尾は同値を保持）。
 */
object Upsampler2x {
    fun process(input: ByteArray): ByteArray {
        val n = input.size / 2
        if (n == 0) return input
        val out = ByteArray(n * 4)
        var cur = input.s16(0)
        for (i in 0 until n) {
            val next = if (i + 1 < n) input.s16((i + 1) * 2) else cur
            out.putS16(i * 4, cur)
            out.putS16(i * 4 + 2, (cur + next) shr 1)
            cur = next
        }
        return out
    }
}
