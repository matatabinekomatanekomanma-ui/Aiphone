package com.example.aiphone

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 取得=F / 注入=A + Gemini Live の開始・停止を Activity / Service から共有する。
 */
@SuppressLint("StaticFieldLeak")
object AiSessionManager {
    private const val TAG = "RLAudio"
    private const val CAPTURE_VARIANT = 5
    private const val INJECT_VARIANT = 0
    private const val CAP_RATE = 48000
    private const val PREBUFFER_BYTES = 14400 // AI の声: 発話の頭で 150ms(48kHz s16le mono)ためてから注入を始める
    private const val PREBUFFER_MAX_WAIT_MS = 200L
    private const val GEMINI_IN_RATE = 16000
    @Volatile private var appCtx: Context? = null
    /** イヤホンマイク(Bluetooth / 有線)の着脱に合わせて、再生の出力機器を指定し直す */
    @Volatile private var localRouteWatcher: AudioRoute.Watcher? = null
    val running = AtomicBoolean(false)

    @Volatile private var gemini: GeminiLiveSession? = null

    // ---- 再接続(通話中に Gemini との接続が切れたとき) ----
    private const val MAX_RECONNECT = 3
    private val RECONNECT_DELAYS_SEC = longArrayOf(1, 3, 6)
    private const val CONNECT_TIMEOUT_SEC = 15L
    private const val HANDOVER_MAX_CHARS = 1200
    private const val HANDOVER_MAX_MESSAGES = 14
    @Volatile private var apiKeyInUse = ""
    @Volatile private var baseInstruction = ""
    @Volatile private var languageCodeInUse = "ja"
    @Volatile private var voiceInUse = GeminiVoices.DEFAULT
    @Volatile private var vadInUse = VadSettings()
    @Volatile private var reconnectAttempt = 0
    /** goAway で先に張り替えている新しいセッション(準備ができたら入れ替える) */
    @Volatile private var swapping: GeminiLiveSession? = null
    private val reconnectExec by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ai-reconnect").also { it.isDaemon = true } }
    }
    // 無制限キュー（満杯で古い音声を捨てると音が欠けるため）。割り込み時に clear する。
    private val injectQueue = LinkedBlockingQueue<ByteArray>()
    private val localQueue = LinkedBlockingQueue<ByteArray>()
    private var localThread: Thread? = null
    /** 割り込みのたびに増える。送信スレッドが途中の音声を捨てる判定に使う */
    private val interruptEpoch = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var captureThread: Thread? = null
    @Volatile private var injectThread: Thread? = null
    @Volatile private var levelThread: Thread? = null
    @Volatile private var localTrack: AudioTrack? = null

    @Volatile var lastStatus: String = "停止"
    @Volatile var onStatus: ((String) -> Unit)? = null

    fun targetUid(context: Context): Int? {
        val uid = TargetApps.resolveUid(context)
        if (uid == null) log("対応する通話アプリ（楽天リンク / LINE / WhatsApp 等）が見つかりません")
        else {
            val label = TargetApps.resolveLabel(context, uid)
            log("通話アプリ対象(優先): ${label ?: "uid=$uid"}")
        }
        return uid
    }

    private fun probeCapturePeak(bridge: IAudioBridge, waitMs: Long): Int {
        var maxPeak = 0
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val p = bridge.captureLevel()
                if (p > maxPeak) maxPeak = p
            } catch (_: Throwable) {
            }
            try { Thread.sleep(150) } catch (_: InterruptedException) { break }
        }
        return maxPeak
    }

    @Synchronized
    fun start(context: Context, apiKey: String): Boolean {
        if (running.get()) {
            log("すでに AI 通話中です")
            return true
        }
        val s = BridgeConnector.bridge
        if (s == null) {
            log("priv-kit 未接続のため AI 開始できません")
            return false
        }
        if (apiKey.isBlank()) {
            log("Gemini API キーが未設定です")
            return false
        }
        val uid = targetUid(context) ?: return false
        appCtx = context.applicationContext
        AskUserStore.clear()
        lastToolCallAt = 0L; waitSaidAt = 0L; synchronized(aiTurnText) { aiTurnText.setLength(0) }

        // 録音が自前で持っている取得を手放す(同時に 1 つしか持てないため)
        CallRecorder.releaseForSession()
        log("AI通話開始: 取得=F / 注入=A / model=${GeminiLiveSession.MODEL}")
        setStatus("AI通話開始中...")
        running.set(true)
        injectQueue.clear()

        ensureLocalTrack()
        val sp = context.getSharedPreferences(AiCallService.PREFS, Context.MODE_PRIVATE)
        val languageCode = AppLanguage.resolvedCode(context)
        val prompt = PersonalInfo.buildSystemInstruction(
            sp,
            sp.getString(AiCallService.KEY_PROMPT, null),
            languageCode,
        )
        val voice = sp.getString(AiCallService.KEY_VOICE, null) ?: GeminiVoices.DEFAULT
        ChatStore.addSystem("通話開始  " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.JAPAN).format(java.util.Date()) + "  /  声: $voice")
        apiKeyInUse = apiKey
        baseInstruction = prompt
        languageCodeInUse = languageCode
        voiceInUse = voice
        vadInUse = VadSettings.load(sp)
        reconnectAttempt = 0
        swapping = null
        val session = buildSession(null)
        gemini = session
        session.connect()

        Thread({
            try {
                // 通話アプリは OS の音声状態(通話用の録音/再生をしているアプリ)から特定する。
                // 「音量が出たものを採用」は、相手が無言だと全候補が無音になり、先頭候補(楽天リンク)で続行して外れていた
                val detected = CallAppDetector.resolve(s, context, 6_000L, { running.get() }) { log(it) }
                var candidates = TargetApps.candidateUids(context)
                if (detected != null) {
                    val a = TargetApps.App(detected.packageName ?: "uid:${detected.uid}", detected.label)
                    candidates = listOf(a to detected.uid) + candidates.filter { it.second != detected.uid }
                }
                if (candidates.isEmpty()) {
                    failStop("対応する通話アプリが見つかりません")
                    return@Thread
                }
                var chosenUid = uid
                var pipeCap0: ParcelFileDescriptor? = null
                var picked = false
                var fallbackUid: Int? = null
                for ((app, candUid) in candidates) {
                    val pipeCap = ParcelFileDescriptor.createPipe()
                    val capRes = try {
                        s.startCapture(candUid, CAPTURE_VARIANT, pipeCap[1])
                    } catch (e: Throwable) {
                        "NG(exception): ${e.message}"
                    }
                    try { pipeCap[1].close() } catch (_: Throwable) {}
                    log("${app.label} 取得: $capRes")
                    if (!capRes.startsWith("OK")) {
                        try { pipeCap[0].close() } catch (_: Throwable) {}
                        try { s.stopCapture() } catch (_: Throwable) {}
                        continue
                    }
                    // OS が「通話中」と示しているアプリは、音量確認なしで即採用(最初の一言を逃さない)
                    val authoritative = detected != null && candUid == detected.uid
                    val peak = if (authoritative) 0 else probeCapturePeak(s, 1_200L)
                    if (authoritative || peak > 80) {
                        log(
                            if (authoritative) "取得開始: ${app.label} uid=$candUid（通話アプリとして検出済み）"
                            else "取得成功: ${app.label} uid=$candUid peak=$peak",
                        )
                        pipeCap0 = pipeCap[0]
                        chosenUid = candUid
                        TargetApps.rememberSuccess(candUid)
                        picked = true
                        break
                    }
                    log("${app.label} uid=$candUid: 無音 peak=$peak → 次を試行")
                    try { s.stopCapture() } catch (_: Throwable) {}
                    try { pipeCap[0].close() } catch (_: Throwable) {}
                    if (fallbackUid == null) fallbackUid = candUid
                }
                if (!picked) {
                    val fbUid = fallbackUid ?: candidates.first().second
                    log("全候補無音のため継続 uid=$fbUid")
                    val pipeCap = ParcelFileDescriptor.createPipe()
                    val capRes = s.startCapture(fbUid, CAPTURE_VARIANT, pipeCap[1])
                    try { pipeCap[1].close() } catch (_: Throwable) {}
                    log(capRes)
                    if (!capRes.startsWith("OK")) {
                        try { pipeCap[0].close() } catch (_: Throwable) {}
                        failStop("取得失敗")
                        return@Thread
                    }
                    pipeCap0 = pipeCap[0]
                    chosenUid = fbUid
                }
                val pipeCapRead = pipeCap0!!

                val pipeInj = ParcelFileDescriptor.createPipe()
                // パイプの先読みを最小にする（既定 64KB ≒ 0.7秒分が溜まり、割り込みで止められない）
                try { Os.fcntlInt(pipeInj[1].fileDescriptor, 1031 /* F_SETPIPE_SZ */, 4096) } catch (_: Throwable) {}
                val injRes = s.startInject(chosenUid, INJECT_VARIANT, pipeInj[0])
                if (injRes.startsWith("OK")) TargetApps.rememberSuccess(chosenUid)
                try { pipeInj[0].close() } catch (_: Throwable) {}
                log(injRes)
                if (!injRes.startsWith("OK")) {
                    try { pipeInj[1].close() } catch (_: Throwable) {}
                    try { s.stopCapture() } catch (_: Throwable) {}
                    try { pipeCapRead.close() } catch (_: Throwable) {}
                    failStop("注入失敗")
                    return@Thread
                }

                setStatus("AI通話中")
                AiCallService.refreshBubble()

                captureThread = Thread({
                    val down = Downsampler3x()
                    try {
                        FileInputStream(pipeCapRead.fileDescriptor).use { ins ->
                            val buf = ByteArray(3840)
                            while (running.get()) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                if (n > 0) {
                                    CallRecorder.feedPeer(buf, 0, n)
                                    // 準備前の音声も間引き状態(持ち越し)を進めるため必ず通す
                                    val pcm16 = down.process(buf, n)
                                    gemini?.let { g -> if (g.isReady()) g.sendAudioPcm(pcm16, GEMINI_IN_RATE) }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        if (running.get()) {
                            val msg = e.message.orEmpty()
                            if (msg.contains("EPIPE", true) || msg.contains("Broken pipe", true)) {
                                log("取得スレッド: 通話終了によりパイプが閉じました")
                            } else {
                                log("取得スレッド: $msg")
                            }
                        }
                    } finally {
                        try { pipeCapRead.close() } catch (_: Throwable) {}
                    }
                }, "cap-to-gemini").also { it.start() }

                injectThread = Thread({
                    // 20ms ずつ実時間に合わせて送る。先読みを 40ms 程度に抑えれば、
                    // 割り込み時に「まだ送っていない分」をアプリ側で捨てられる。
                    val chunk = 1920 // 48kHz s16le mono の 20ms
                    val leadNs = 40_000_000L
                    var nextAt = 0L
                    try {
                        FileOutputStream(pipeInj[1].fileDescriptor).use { os ->
                            while (running.get()) {
                                var pcm = injectQueue.poll(200, TimeUnit.MILLISECONDS)
                                if (pcm == null) { nextAt = 0L; continue }
                                val epoch = interruptEpoch.get()
                                if (nextAt == 0L && pcm.size < PREBUFFER_BYTES) {
                                    // 発話の頭: 受信が細切れに届くと、送り出しが追いつかず途切れる。少したまるのを待ってから流し始める
                                    val acc = java.io.ByteArrayOutputStream(PREBUFFER_BYTES * 2)
                                    acc.write(pcm)
                                    val deadline = System.nanoTime() + PREBUFFER_MAX_WAIT_MS * 1_000_000L
                                    while (acc.size() < PREBUFFER_BYTES && running.get() && epoch == interruptEpoch.get()) {
                                        val rem = deadline - System.nanoTime()
                                        if (rem <= 0) break
                                        val more = injectQueue.poll(rem, TimeUnit.NANOSECONDS) ?: break
                                        acc.write(more)
                                    }
                                    pcm = acc.toByteArray()
                                }
                                var off = 0
                                while (off < pcm.size && running.get() && epoch == interruptEpoch.get()) {
                                    val n = minOf(chunk, pcm.size - off)
                                    val now = System.nanoTime()
                                    if (nextAt == 0L || nextAt < now - 100_000_000L) nextAt = now
                                    val waitNs = nextAt - now - leadNs
                                    if (waitNs > 0) Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                                    os.write(pcm, off, n)
                                    CallRecorder.feedMine(pcm, off, n)
                                    nextAt += n / 2L * 1_000_000_000L / CAP_RATE
                                    off += n
                                }
                                if (epoch != interruptEpoch.get()) nextAt = 0L
                            }
                        }
                    } catch (e: InterruptedException) {
                    } catch (e: Throwable) {
                        if (running.get()) {
                            val msg = e.message.orEmpty()
                            if (msg.contains("EPIPE", true) || msg.contains("Broken pipe", true)) {
                                log("注入スレッド: 通話終了によりパイプが閉じました")
                            } else {
                                log("注入スレッド: $msg")
                            }
                        }
                    } finally {
                        try { pipeInj[1].close() } catch (_: Throwable) {}
                    }
                }, "gemini-to-inj").also { it.start() }

                val chosenDetected = detected != null && chosenUid == detected.uid
                levelThread = Thread({
                    var quiet = 0
                    var heard = false
                    var warned = false
                    while (running.get()) {
                        try {
                            val lv = s.captureLevel()
                            if (lv > 80) { heard = true; quiet = 0 } else quiet++
                            if (!heard && !warned && quiet >= 40) { // 500ms × 40 = 20 秒
                                warned = true
                                log(
                                    if (chosenDetected) "⚠ 通話アプリ(検出済み)の音声が 20 秒間まったく取れていません。相手が無言か、このアプリの通話音声は取得できない可能性があります"
                                    else "⚠ 20 秒間、相手の音声を取得できていません。通話アプリの特定が外れている可能性があります(上の「音声クライアント」の行を確認)",
                                )
                            }
                            setStatus("AI通話中  取得レベル: $lv / 32767")
                            Thread.sleep(500)
                        } catch (_: Throwable) { break }
                    }
                }, "level").also { it.start() }
            } catch (e: Throwable) {
                Log.e(TAG, "AI start failed (${e.javaClass.simpleName})")
                log("AI開始失敗 (${e.javaClass.simpleName})")
                failStop("AI開始失敗")
            }
        }, "ai-start").start()
        return true
    }

    @Synchronized
    fun stop() {
        clearAsk()
        // セット中の用件は通話終了で解除のみ（下書き・準備チャットは残す）
        if (CallBriefStore.isActive()) {
            CallBriefStore.clearActive()
            log("通話終了のためセットを解除しました（用件の内容は残しています）")
        }
        try { swapping?.close() } catch (_: Throwable) {}
        swapping = null
        if (!running.getAndSet(false)) {
            try { gemini?.close() } catch (_: Throwable) {}
            gemini = null
            return
        }
        log("AI通話停止中...")
        ChatStore.addSystem("通話終了")
        setStatus("停止中...")
        try { gemini?.close() } catch (_: Throwable) {}
        gemini = null
        injectQueue.clear()
        releaseLocalTrack()
        Thread({
            try { BridgeConnector.bridge?.stopCapture() } catch (_: Throwable) {}
            try { BridgeConnector.bridge?.stopInject() } catch (_: Throwable) {}
            try { captureThread?.join(1000) } catch (_: Throwable) {}
            try { injectThread?.join(1000) } catch (_: Throwable) {}
            try { levelThread?.join(500) } catch (_: Throwable) {}
            captureThread = null
            injectThread = null
            levelThread = null
            log("AI通話停止完了")
            setStatus("停止")
            AiCallService.refreshBubble()
        }, "ai-stop").start()
    }

    private fun failStop(status: String) {
        clearAsk()
        running.set(false)
        try { swapping?.close() } catch (_: Throwable) {}
        swapping = null
        try { gemini?.close() } catch (_: Throwable) {}
        gemini = null
        releaseLocalTrack()
        setStatus(status)
    }

    // ---------- Gemini セッションの生成と再接続 ----------

    /**
     * Gemini のセッションを作る(接続はしない)。handover があれば、システム指示の末尾に「ここまでの会話」を付ける。
     * 再接続のたびに作り直すので、コールバックは「自分が今のセッションか」を確かめてから動く。
     */
    private fun buildSession(handover: String?): GeminiLiveSession {
        lateinit var self: GeminiLiveSession
        self = GeminiLiveSession(
            apiKey = apiKeyInUse,
            systemInstruction = if (handover.isNullOrBlank()) baseInstruction else baseInstruction + handover,
            voiceName = voiceInUse,
            onTranscript = { role, t -> ChatStore.append(role, t); watchTranscript(role, t) },
            onTurnComplete = { ChatStore.closeTurn(); watchTurnComplete() },
            onInterrupted = { handleInterrupt() },
            onAudioOut = { pcm24k ->
                // 張り替え中の新セッションの音声は、入れ替わるまで使わない
                if (self === gemini) {
                    val pcm48 = Upsampler2x.process(pcm24k)
                    // 相手へ: 通話経路へ注入
                    injectQueue.offer(pcm48)
                    // 自分へ: 端末スピーカー/受話口で再生(再生は専用スレッド。WebSocket 受信を止めない)
                    localQueue.offer(pcm48)
                }
            },
            onLog = { msg -> log(msg) },
            enableAskUser = true,
            vad = vadInUse,
            onReady = { handleSessionReady(self) },
            onToolCall = { id, name, args -> if (self === gemini) handleToolCall(id, name, args) },
            onToolCancel = { ids -> handleToolCancel(ids) },
            onError = { msg -> log("ERROR: $msg") },
            onDisconnected = { reason -> handleDisconnected(self, reason) },
            onGoAway = { ms -> handleGoAway(self, ms) },
            languageCode = languageCodeInUse,
        )
        return self
    }

    /** 直近の会話を、再接続したセッションへ渡す短い文にする。会話が無ければ null */
    private fun buildHandover(): String? {
        val lines = ArrayList<String>()
        for (m in ChatStore.messages.toList().takeLast(HANDOVER_MAX_MESSAGES * 3)) {
            val who = when {
                m.role == ChatStore.PEER -> "相手"
                m.role == ChatStore.AI -> "あなた"
                m.role == ChatStore.SYSTEM && (m.text.startsWith("あなたの回答") || m.text.startsWith("あなたの指示")) -> "本人"
                else -> continue
            }
            val t = m.text.replace("\n", " ").trim().let { if (it.length > 120) it.take(120) + "…" else it }
            if (t.isNotEmpty()) lines.add("$who: $t")
        }
        val recent = lines.takeLast(HANDOVER_MAX_MESSAGES)
        if (recent.isEmpty()) return null
        var body = recent.joinToString("\n")
        if (body.length > HANDOVER_MAX_CHARS) body = body.takeLast(HANDOVER_MAX_CHARS)
        return "\n\n【通話の途中経過】\n通信が一時的に切れて、つなぎ直した。通話は続いている。" +
            "これまでの会話の抜粋(古い順。本人=本人が選んだ返答):\n" + body +
            "\n同じことを繰り返さず、相手の次の発言を待つ。個人情報は、つなぎ直す前に許可があっても、改めて request_personal_info で申請する。"
    }

    private fun handleSessionReady(s: GeminiLiveSession) {
        if (!running.get()) return
        if (s === swapping) {
            // goAway で先に張り替えた新セッションが準備できた: 入れ替える
            val old = gemini
            gemini = s
            swapping = null
            reconnectAttempt = 0
            AskUserStore.markAllCancelled()
            try { old?.close() } catch (_: Throwable) {}
            ChatStore.addSystem("AIとの接続を張り替えました")
            setStatus("AI通話中")
        } else if (s === gemini && reconnectAttempt > 0) {
            reconnectAttempt = 0
            ChatStore.addSystem("AIとの接続が回復しました")
            setStatus("AI通話中")
        }
    }

    private fun handleGoAway(s: GeminiLiveSession, timeLeftMs: Long) {
        if (!running.get() || s !== gemini || swapping != null) return
        log("goAway を受信(残り ${timeLeftMs}ms)。先に新しい接続を作ります")
        val ns = buildSession(buildHandover())
        swapping = ns
        ns.connect()
        // 張り替えが終わらないまま古い接続が切れたら、通常の再接続に任せる
        reconnectExec.schedule({
            if (running.get() && swapping === ns && !ns.isReady()) {
                swapping = null
                try { ns.close() } catch (_: Throwable) {}
            }
        }, CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    private fun handleDisconnected(s: GeminiLiveSession, reason: String) {
        if (!running.get()) return
        if (s === swapping) {
            // 張り替え用の新セッションが失敗: 古い方がまだ生きているのでそのまま続ける
            swapping = null
            log("張り替え用の接続に失敗: $reason")
            return
        }
        if (s !== gemini) return
        log("Gemini 切断: $reason")
        scheduleReconnect()
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!running.get()) return
        val attempt = ++reconnectAttempt
        if (attempt > MAX_RECONNECT) {
            giveUpReconnect()
            return
        }
        // 古い音声と、無効になったツール呼び出しを片づける
        handleInterrupt()
        AskUserStore.markAllCancelled()
        setStatus("AI再接続中…($attempt/$MAX_RECONNECT)")
        ChatStore.addSystem("AIとの接続が切れました。再接続します($attempt/$MAX_RECONNECT)")
        val delaySec = RECONNECT_DELAYS_SEC[(attempt - 1).coerceIn(0, RECONNECT_DELAYS_SEC.size - 1)]
        reconnectExec.schedule({
            if (!running.get()) return@schedule
            try { gemini?.close() } catch (_: Throwable) {}
            val ns = buildSession(buildHandover())
            gemini = ns
            ns.connect()
            // 接続も失敗通知も来ないまま止まった場合の保険
            reconnectExec.schedule({
                if (running.get() && gemini === ns && !ns.isReady()) handleDisconnected(ns, "接続待ちがタイムアウト")
            }, CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        }, delaySec, TimeUnit.SECONDS)
    }

    /** 再接続に失敗し続けた: 通話は切らず、本人に知らせる(本人が電話を引き取る) */
    private fun giveUpReconnect() {
        log("再接続を ${MAX_RECONNECT} 回試しましたが戻りませんでした")
        try { gemini?.close() } catch (_: Throwable) {}
        setStatus("AI切断(通話は継続中)")
        ChatStore.addSystem("AIとの接続が戻りません。通話は続いています。ご自身で対応してください")
        appCtx?.let {
            AskNotifier.notifyInfo(it, "AIとの接続が切れました", "再接続に失敗しました。通話は続いています。ご自身で引き取ってください。")
        }
    }

    // ---------- 保険: AI が「少々お待ちください」と言ったまま ask_user を呼ばなかったとき ----------
    // 相手の音声で AI の生成が割り込まれると、関数呼び出しが失われることがある。
    // その場合でも本人に必ず確認が届くよう、アプリ側で確認カードを出す。

    private val WAIT_PHRASES = Regex("少々お待ち|しばらくお待ち|確認いたします|確認します|システムエラー|エラーが発生")
    private val aiTurnText = StringBuilder()
    @Volatile private var lastToolCallAt = 0L
    @Volatile private var waitSaidAt = 0L
    private val watchdog by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ask-watchdog").also { it.isDaemon = true } }
    }

    private fun watchTranscript(role: String, t: String) {
        if (role != ChatStore.AI) return
        synchronized(aiTurnText) {
            aiTurnText.append(t)
            if (waitSaidAt == 0L && WAIT_PHRASES.containsMatchIn(aiTurnText)) waitSaidAt = System.currentTimeMillis()
        }
    }

    private fun watchTurnComplete() {
        val said = waitSaidAt
        synchronized(aiTurnText) { aiTurnText.setLength(0) }
        waitSaidAt = 0L
        if (said == 0L || !running.get()) return
        // 4 秒待って、その間(と直前10秒)に ask_user / request_personal_info が呼ばれなければ、アプリ側で確認を出す
        watchdog.schedule({
            if (!running.get()) return@schedule
            if (lastToolCallAt >= said - 10_000L) return@schedule
            if (AskUserStore.count() > 0) return@schedule
            raiseFallbackAsk()
        }, 4, TimeUnit.SECONDS)
    }

    private fun raiseFallbackAsk() {
        val peer = ChatStore.messages.lastOrNull { it.role == ChatStore.PEER }?.text?.trim().orEmpty()
        val shown = if (peer.length > 80) peer.take(80) + "…" else peer
        val question = if (shown.isEmpty()) "AIが返答できずに止まっています。どう返しますか？"
        else "AIが返答できずに止まっています。相手の直前の発言: 「$shown」"
        val req = AskUserStore.Request(
            id = "fallback-" + System.currentTimeMillis(),
            question = question,
            options = listOf("もう一度ゆっくりお願いしますと伝える", "分からないので、あとで折り返すと伝える"),
            aiContext = if (peer.isEmpty()) "直前の相手の発言" else "相手の直前の発言「$peer」",
        )
        // ツール呼び出しではないので、回答は toolResponse ではなくテキストで AI に送る
        req.cancelled = true
        if (!AskUserStore.add(req)) return
        log("ask_user を AI が呼ばなかったため、アプリ側で本人へ確認依頼を出しました")
        ChatStore.addSystem("AIが判断できずに止まったため、確認を出しました")
        appCtx?.let { AskNotifier.notifyAsk(it, req) }
    }

    // ---------- ask_user: AI だけでは判断できないとき本人に選んでもらう ----------

    private fun clearAsk() {
        AskUserStore.clear()
        appCtx?.let { AskNotifier.cancel(it) }
    }

    /** 個人情報カードの固定ボタン。値の取得可否は index でのみ決まる(AI の出力では決まらない) */
    const val PII_ALLOW = 0
    const val PII_DENY = 1
    const val PII_CALLBACK = 2
    private val PII_OPTIONS = listOf("許可して伝える", "許可しない(お伝えできないと断る)", "あとで折り返すと伝える")

    private fun handleToolCall(id: String, name: String, args: JSONObject) {
        lastToolCallAt = System.currentTimeMillis()
        val g = gemini
        when (name) {
            GeminiLiveSession.TOOL_ASK_USER -> handleAskUser(id, name, args)
            GeminiLiveSession.TOOL_REQUEST_PII -> handleRequestPii(id, name, args)
            else -> g?.sendToolResponse(id, name, JSONObject().put("error", "未対応の関数です"))
        }
    }

    private fun handleAskUser(id: String, name: String, args: JSONObject) {
        val g = gemini
        val question = args.optString("question", "").trim().ifEmpty { "(質問の要点が取得できませんでした)" }
        val opts = ArrayList<String>()
        args.optJSONArray("options")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optString(i, "").trim()
                if (s.isNotEmpty() && opts.size < 3) opts.add(s)
            }
        }
        val req = AskUserStore.Request(id, question, opts)
        if (!AskUserStore.add(req)) {
            // 未回答の確認が残っている、または同じ質問。新しい質問は出さず回答待ちにする
            g?.sendToolResponse(id, name, JSONObject().apply {
                put("result", "already_pending")
                put("how_to_use", "先の確認の回答を待っている。新しい内容を話さず、本人の回答を待つ。")
                put("scheduling", "SILENT")
            })
            return
        }
        // 質問や選択肢には個人情報・通話内容が含まれうるため、debug ビルドでも本文を記録しない。
        log("ask_user: 本人へ確認依頼 (選択肢${opts.size}件)")
        ChatStore.addSystem("AI が判断を求めています: $question")
        appCtx?.let { AskNotifier.notifyAsk(it, req) }
    }

    /**
     * 個人情報の開示申請。値を読むのは次の2つの場合だけ:
     *  ・本人がカードで「許可」を押したとき(answerPii)
     *  ・申請された項目がすべて「許可なしで伝えてよい」と設定されているとき(その場で自動回答。カードは出さない)
     * 許可が要る項目が1つでも混ざっていれば、カードを出し、許可されたときだけ許可不要の項目も一緒に返す。
     */
    private fun handleRequestPii(id: String, name: String, args: JSONObject) {
        val g = gemini
        val keys = ArrayList<String>()
        args.optJSONArray("fields")?.let { a ->
            for (i in 0 until a.length()) {
                val k = a.optString(i, "").trim()
                if (k in PersonalInfo.FIELD_KEYS && k !in keys) keys.add(k)
            }
        }
        if (keys.isEmpty()) {
            g?.sendToolResponse(id, name, JSONObject().put("result", "error")
                .put("how_to_use", "項目が指定されていない。必要な項目を指定して呼び直すか、ask_user で本人に確認する。"))
            return
        }
        val reason = args.optString("reason", "").trim()
        val labels = keys.joinToString("・") { PersonalInfo.label(it) }

        // 「許可なしで伝えてよい」項目の仕分け(設定は項目ごと。値はここでは読まない)
        val sp = appCtx?.getSharedPreferences(AiCallService.PREFS, Context.MODE_PRIVATE)
        val info = if (sp == null) null else PersonalInfo.load(sp)
        val auto = if (info == null) emptyList() else keys.filter { PersonalInfo.isAutoAllowed(info, it) }
        val need = keys.filter { it !in auto }
        if (need.isEmpty()) {
            val a = buildApproval(keys, "")
            a.response.put("scheduling", "WHEN_IDLE")
            val ok = g != null && g.isReady() && g.sendToolResponse(id, name, a.response)
            ChatStore.addSystem(if (ok) "許可不要の設定のため自動で回答: $labels" else "回答を送れませんでした(AI が接続されていません)")
            log("request_personal_info: 許可不要の項目を自動回答 ok=$ok (項目: $labels)")
            return
        }
        val needLabels = need.joinToString("・") { PersonalInfo.label(it) }
        val autoNote = if (auto.isEmpty()) "" else "\n(許可すると ${auto.joinToString("・") { PersonalInfo.label(it) }} も一緒に伝えます)"
        val question = "相手が個人情報を求めています: $needLabels" + autoNote + if (reason.isNotEmpty()) "\n(${reason})" else ""
        val req = AskUserStore.Request(id, question, PII_OPTIONS, piiFields = need, autoFields = auto)
        if (!AskUserStore.add(req)) {
            g?.sendToolResponse(id, name, JSONObject().apply {
                put("result", "already_pending")
                put("how_to_use", "先の確認の回答を待っている。新しい申請を重ねず、本人の回答を待つ。")
                put("scheduling", "SILENT")
            })
            return
        }
        log("request_personal_info: 本人へ許可申請 (項目: $labels)")
        ChatStore.addSystem("AI が個人情報の開示許可を求めています: $labels")
        appCtx?.let { AskNotifier.notifyAsk(it, req) }
    }

    private fun handleToolCancel(ids: List<String>) {
        // 待機中の AI が割り込みで呼び出しを取り消されても、カードは消さない(本人の判断は必要なまま)。
        // 回答時は toolResponse の代わりにテキストで伝える。
        ids.forEach { AskUserStore.markCancelled(it) }
        log("ask_user の呼び出しが取り消されました: $ids")
    }

    /**
     * 本人の回答を AI に渡す。isFree=true は自由入力(指示)、false は候補ボタン(optionIndex 番目)。
     * 個人情報カードでは、optionIndex==PII_ALLOW のときだけ値を読んで返す。
     */
    fun answerAskUser(id: String, text: String, isFree: Boolean, optionIndex: Int = -1): Boolean {
        // Gemini への送信が成功するまで確認依頼をキューに残す。失敗時も再試行できる。
        val req = AskUserStore.beginAnswer(id) ?: return false
        val g = gemini
        val piiOption = req.isPii && !isFree
        val ok = try {
            if (piiOption) {
                answerPii(req, g, optionIndex)
            } else {
                when {
                    g == null || !g.isReady() -> false
                    req.cancelled -> {
                        val ctx = req.aiContext ?: "相手の質問「${req.question}」"
                        g.sendText(
                            if (isFree) "本人の指示です。${ctx}について、「$text」。この内容を自然な話し言葉で相手に伝えてください。"
                            else "本人の返答です。${ctx}について、「$text」。この内容を自然な話し言葉で相手に伝えてください。"
                        )
                    }
                    else -> g.sendToolResponse(id, req.toolName(), JSONObject().apply {
                        put("result", "ok")
                        if (isFree) {
                            put("user_instruction", text)
                            put("how_to_use", "本人の指示です。この内容を自然な話し言葉で相手に伝える。")
                        } else {
                            put("reply_to_say", text)
                            put("how_to_use", "本人が選んだ返答です。この内容を自然な話し言葉で相手に伝える。")
                        }
                        put("scheduling", "WHEN_IDLE")
                    })
                }
            }
        } catch (e: Exception) {
            log("ask_user 回答送信に失敗 (${e.javaClass.simpleName})")
            false
        }
        AskUserStore.finishAnswer(id, ok)
        if (ok && AskUserStore.count() == 0) appCtx?.let { AskNotifier.cancel(it) }
        if (piiOption) return ok // answerPii は画面表示用の結果も記録する。

        ChatStore.addSystem(
            when {
                !ok -> "回答を送れませんでした。接続を確認してもう一度送信してください。確認依頼は保持されています。"
                isFree -> "あなたの指示: $text"
                else -> "あなたの回答: $text"
            }
        )
        // 自由入力には個人情報が含まれる可能性があるため、本文は Logcat に出さない。
        log("ask_user 回答送信 ok=$ok free=$isFree")
        return ok
    }

    private fun AskUserStore.Request.toolName(): String =
        if (isPii) GeminiLiveSession.TOOL_REQUEST_PII else GeminiLiveSession.TOOL_ASK_USER

    private class PiiApproval(val response: JSONObject, val text: String, val memo: String)

    /** 許可済み(または許可不要)の項目の値を読み、AI へ返す内容を作る。呼ぶのは許可後 / 許可不要の自動回答のみ */
    private fun buildApproval(keys: List<String>, question: String): PiiApproval {
        val code = PromptLocalization.normalizeCode(languageCodeInUse)
        val labels = keys.joinToString("・") { PromptLocalization.personalFieldLabel(code, PersonalInfo.label(it)) }
        val sp = appCtx?.getSharedPreferences(AiCallService.PREFS, Context.MODE_PRIVATE)
        val values = if (sp == null) emptyMap() else PersonalInfo.valuesFor(PersonalInfo.load(sp), keys)
        val response = JSONObject()
        if (values.isEmpty()) {
            val howToUse = when (code) {
                "ja" -> "その項目は登録されていない。お伝えできないと断るか、あとで折り返すと伝える。"
                "zh-CN" -> "该字段未登记。请礼貌地说明无法提供，或表示稍后回电。"
                "zh-TW" -> "該欄位未登記。請有禮貌地表示無法提供，或稍後回電。"
                "ko" -> "해당 항목은 등록되어 있지 않습니다. 알려드릴 수 없다고 정중히 말하거나 나중에 다시 전화하겠다고 전합니다."
                "es" -> "Ese campo no está registrado. Indica amablemente que no puedes facilitarlo o que devolverás la llamada más tarde."
                else -> "That field is not registered. Politely say you cannot provide it or that you will call back later."
            }
            val spoken = when (code) {
                "ja" -> "本人の返答です。相手の質問「$question」について、その情報はお伝えできないと自然に断ってください。"
                "zh-CN" -> "用户的答复：针对对方的问题“$question”，请自然地说明无法提供该信息。"
                "zh-TW" -> "使用者的答覆：針對對方的問題「$question」，請自然地表示無法提供該資訊。"
                "ko" -> "사용자의 답변입니다. 상대방의 질문 '$question'에 대해 해당 정보를 제공할 수 없다고 자연스럽게 말하세요."
                "es" -> "Respuesta del usuario: respecto a la pregunta «$question», indica con naturalidad que no puedes facilitar esa información."
                else -> "The user's answer regarding $question: naturally explain that you cannot provide that information."
            }
            response.put("result", "not_registered").put("how_to_use", howToUse)
            return PiiApproval(response, spoken, "許可しましたが、その項目は未登録でした")
        }
        val obj = JSONObject()
        values.forEach { (k, v) -> obj.put(PromptLocalization.personalFieldLabel(code, PersonalInfo.label(k)), v) }
        val howToUse = when (code) {
            "ja" -> "本人が許可した項目だけ。これ以外の個人情報は言わない。自然な話し言葉で、聞かれた項目だけ伝える。"
            "zh-CN" -> "只传达用户已授权的字段，不要透露其他个人信息。使用自然口语，只回答对方询问的字段。"
            "zh-TW" -> "只傳達使用者已授權的欄位，不要透露其他個人資訊。使用自然口語，只回答對方詢問的欄位。"
            "ko" -> "사용자가 허용한 항목만 전달하고 다른 개인정보는 공개하지 않습니다. 자연스러운 구어체로 질문받은 항목만 답합니다."
            "es" -> "Comunica solo los campos autorizados por el usuario y no reveles otros datos personales. Habla de forma natural y responde únicamente a los campos preguntados."
            else -> "Communicate only fields authorized by the user and do not disclose other personal data. Speak naturally and provide only the fields asked about."
        } + " " + PersonalInfo.readingRule(code)
        val textLead = when (code) {
            "ja" -> "本人が個人情報の開示を許可しました。次の値を、聞かれた項目だけ自然な話し言葉で相手に伝えてください。"
            "zh-CN" -> "用户已授权披露个人信息。请用自然口语向对方传达以下值中被询问的字段。"
            "zh-TW" -> "使用者已授權揭露個人資訊。請用自然口語向對方傳達以下值中被詢問的欄位。"
            "ko" -> "사용자가 개인정보 공개를 허용했습니다. 다음 값 중 질문받은 항목만 자연스러운 구어체로 상대방에게 전달하세요."
            "es" -> "El usuario ha autorizado la divulgación de datos personales. Comunica de forma natural solo los campos preguntados de los siguientes valores."
            else -> "The user authorized disclosure of personal information. Naturally communicate only the requested fields from these values."
        }
        val valuesText = values.entries.joinToString(if (code == "ja") "、" else ", ") {
            "${PromptLocalization.personalFieldLabel(code, PersonalInfo.label(it.key))}: ${it.value}"
        }
        response.put("result", "approved").put("values", obj).put("how_to_use", howToUse)
        return PiiApproval(response, "$textLead ${PersonalInfo.readingRule(code)} $valuesText", "許可: $labels")
    }

    private fun answerPii(req: AskUserStore.Request, g: GeminiLiveSession?, optionIndex: Int): Boolean {
        // 許可されたとき返すのは、カードに出した項目 + 同じ申請の「許可不要」項目
        val keys = (req.piiFields ?: emptyList()) + req.autoFields
        val labels = keys.joinToString("・") { PromptLocalization.personalFieldLabel(languageCodeInUse, PersonalInfo.label(it)) }
        var response = JSONObject()
        var memo: String
        var text: String // cancelled 時にテキストで送る内容
        when (optionIndex) {
            PII_ALLOW -> {
                // ★ 値を読むのはここ(本人が「許可」を押した場合)と、handleRequestPii の許可不要の自動回答だけ
                val a = buildApproval(keys, req.question)
                response = a.response; text = a.text; memo = a.memo
            }
            PII_CALLBACK -> {
                val howToUse = when (PromptLocalization.normalizeCode(languageCodeInUse)) {
                    "ja" -> "個人情報は伝えず、あとで本人から折り返すと自然に伝える。"
                    "zh-CN" -> "不要透露个人信息，自然地表示用户稍后会回电。"
                    "zh-TW" -> "不要透露個人資訊，自然地表示使用者稍後會回電。"
                    "ko" -> "개인정보를 공개하지 말고 사용자가 나중에 다시 전화하겠다고 자연스럽게 전합니다."
                    "es" -> "No reveles datos personales e indica con naturalidad que el usuario devolverá la llamada más tarde."
                    else -> "Do not disclose personal information; naturally say the user will call back later."
                }
                response.put("result", "denied").put("how_to_use", howToUse)
                text = when (PromptLocalization.normalizeCode(languageCodeInUse)) {
                    "ja" -> "本人の返答です。相手の質問「${req.question}」について、個人情報は伝えず、あとで折り返すと自然に伝えてください。"
                    "zh-CN" -> "用户的答复：针对对方的问题“${req.question}”，不要提供个人信息，请自然地表示稍后会回电。"
                    "zh-TW" -> "使用者的答覆：針對對方的問題「${req.question}」，不要提供個人資訊，請自然地表示稍後會回電。"
                    "ko" -> "사용자의 답변입니다. 상대방의 질문 '${req.question}'에 대해 개인정보를 제공하지 말고 나중에 다시 전화하겠다고 자연스럽게 말하세요."
                    "es" -> "Respuesta del usuario: respecto a «${req.question}», no facilites datos personales e indica con naturalidad que devolverá la llamada más tarde."
                    else -> "The user's answer to '${req.question}': do not disclose personal information; naturally say the user will call back later."
                }
                memo = "折り返しにする: $labels"
            }
            else -> {
                val howToUse = when (PromptLocalization.normalizeCode(languageCodeInUse)) {
                    "ja" -> "個人情報は伝えない。お伝えできないと自然に断る。聞かれても値を推測して言わない。"
                    "zh-CN" -> "不要透露个人信息。请自然、礼貌地拒绝；即使对方追问，也不要猜测或提供任何值。"
                    "zh-TW" -> "不要透露個人資訊。請自然、有禮貌地拒絕；即使對方追問，也不要猜測或提供任何值。"
                    "ko" -> "개인정보를 공개하지 말고 자연스럽고 정중하게 거절합니다. 상대방이 다시 물어도 값을 추측하거나 제공하지 않습니다."
                    "es" -> "No reveles datos personales. Rechaza la petición con naturalidad y cortesía; no adivines ni proporciones valores aunque insistan."
                    else -> "Do not disclose personal information. Decline naturally and politely; do not guess or provide values even if asked again."
                }
                response.put("result", "denied").put("how_to_use", howToUse)
                text = when (PromptLocalization.normalizeCode(languageCodeInUse)) {
                    "ja" -> "本人の返答です。相手の質問「${req.question}」について、個人情報はお伝えできないと自然に断ってください。"
                    "zh-CN" -> "用户的答复：针对对方的问题“${req.question}”，请自然地说明无法提供个人信息。"
                    "zh-TW" -> "使用者的答覆：針對對方的問題「${req.question}」，請自然地表示無法提供個人資訊。"
                    "ko" -> "사용자의 답변입니다. 상대방의 질문 '${req.question}'에 대해 개인정보를 제공할 수 없다고 자연스럽게 말하세요."
                    "es" -> "Respuesta del usuario: respecto a «${req.question}», indica con naturalidad que no puedes facilitar datos personales."
                    else -> "The user's answer to '${req.question}': naturally explain that you cannot provide personal information."
                }
                memo = "許可しない: $labels"
            }
        }
        response.put("scheduling", "WHEN_IDLE")
        val ok = when {
            g == null || !g.isReady() -> false
            req.cancelled -> g.sendText(text)
            else -> g.sendToolResponse(req.id, GeminiLiveSession.TOOL_REQUEST_PII, response)
        }
        ChatStore.addSystem(if (ok) "あなたの回答: $memo" else "回答を送れませんでした(AI が接続されていません)")
        log("request_personal_info 回答 ok=$ok choice=$optionIndex")
        return ok
    }

    /** 通話中でも聞こえるよう VOICE_COMMUNICATION 経路でローカル再生 */
    private fun ensureLocalTrack() {
        if (localTrack != null) return
        try {
            val rate = CAP_RATE
            val minBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val fmt = AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, rate * 2 / 5))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            appCtx?.getSystemService(AudioManager::class.java)?.let { am ->
                AudioRoute.applyOutput(track, am, "ローカル再生") { log(it) }
                localRouteWatcher?.stop()
                localRouteWatcher = AudioRoute.Watcher(am) {
                    AudioRoute.applyOutput(track, am, "ローカル再生(機器変更)") { m -> log(m) }
                }.start()
            }
            track.play()
            localTrack = track
            localQueue.clear()
            localThread = Thread({
                try {
                    while (true) {
                        val b = localQueue.take()
                        var off = 0
                        while (off < b.size) {
                            val w = track.write(b, off, b.size - off)
                            if (w <= 0) break
                            off += w
                        }
                    }
                } catch (_: InterruptedException) {
                } catch (_: Throwable) {}
            }, "ai-local-play").also { it.start() }
            log("ローカル再生開始 (VOICE_COMMUNICATION / ${rate}Hz)")
        } catch (e: Throwable) {
            Log.e(TAG, "local AudioTrack failed", e)
            log("ローカル再生の初期化に失敗: ${e.message}")
        }
    }

    /** 割り込み: 溜まっている AI 音声（送信待ち・ローカル再生待ち・再生バッファ）を捨てる */
    private fun handleInterrupt() {
        interruptEpoch.incrementAndGet()
        injectQueue.clear()
        localQueue.clear()
        try {
            localTrack?.let { it.pause(); it.flush(); it.play() }
        } catch (_: Throwable) {}
    }

    private fun releaseLocalTrack() {
        try { localRouteWatcher?.stop() } catch (_: Throwable) {}
        localRouteWatcher = null
        try { localThread?.interrupt() } catch (_: Throwable) {}
        localThread = null
        localQueue.clear()
        try {
            localTrack?.stop()
            localTrack?.release()
        } catch (_: Throwable) {}
        localTrack = null
    }

    private fun log(msg: String) {
        Log.i(TAG, "[ai] $msg")
    }

    private fun setStatus(s: String) {
        lastStatus = s
        onStatus?.invoke(s)
    }
}
