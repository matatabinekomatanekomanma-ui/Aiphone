package com.example.aiphone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 双方向のリアルタイム翻訳(仮実装)。
 *
 *  相手→自分: 通話アプリの出力を取得(48k) → 16k → Translate(翻訳先=自分の言語) → 24k → 自分の端末で再生
 *  自分→相手: 本物のマイク(16k) → Translate(翻訳先=相手の言語) → 24k→48k → 通話アプリのマイク入力へ注入
 *
 * 通話アプリの取得・注入は AiSessionManager と同じ AudioBridge(priv-kit)を使う。
 * AI 代行(AiSessionManager)とは同時に動かさない。
 */
object TranslateManager {
    private const val TAG = "RLAudio"

    private const val CAPTURE_KEEP = 5   // 元の声を残して取得(従来どおり)
    private const val CAPTURE_MUTE = 6   // 元の声を消して取得(LOOP_BACK のみ・実験)
    private const val INJECT_VARIANT = 0
    private const val CAP_RATE = 48000
    private const val IN_RATE = 16000
    /** 自動検出の前に使う、自分→相手 の仮の翻訳先 */
    private const val FALLBACK_LANG = "en"
    /** 「相手→自分」を翻訳しないときの、言語検出専用セッションの翻訳先(音声は捨てる) */
    private const val DETECT_LANG = "en"
    private const val MAX_RECONNECT = 3
    private val RECONNECT_DELAYS_SEC = longArrayOf(1, 3, 6)

    val running = AtomicBoolean(false)
    @Volatile var lastStatus: String = "停止"
    @Volatile var onStatus: ((String) -> Unit)? = null

    /** 翻訳セッション 1 本分(方向ごと) */
    private class Channel(
        val key: String,
        val label: String,
        @Volatile var lang: String,
        /** 翻訳先と同じ言語の入力も復唱するか(元の声を消している間は、聞こえなくならないよう true) */
        val echo: Boolean,
        val onAudio: (ByteArray) -> Unit,
    ) {
        @Volatile var session: GeminiTranslateSession? = null
        @Volatile var attempt = 0
        /** setup の配置入れ替えによる即時再接続の回数(無限ループ防止) */
        @Volatile var adaptCount = 0
    }

    @Volatile private var apiKeyInUse = ""
    @Volatile private var chIn: Channel? = null   // 相手→自分
    @Volatile private var chOut: Channel? = null  // 自分→相手

    private val injectQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var player: LocalPcmPlayer? = null
    /** 元の声を消している間、翻訳が未準備のときだけ原音(48k)を流す安全網 */
    @Volatile private var passPlayer: LocalPcmPlayer? = null
    @Volatile private var appMic: AudioRecord? = null
    @Volatile private var appMicWatcher: AudioRoute.Watcher? = null
    /** Bluetooth / 有線のイヤホンマイクの着脱・通話機器の切り替えを見張る */
    @Volatile private var routeWatcher: AudioRoute.Watcher? = null
    /** 外付け機器を使っているときの表示用(本体のみなら空) */
    @Volatile private var routeText = ""
    /** マイク読み取りスレッドの世代。作り直したあとに古いスレッドが自動再開しないための印 */
    @Volatile private var micGen = 0
    @Volatile private var micEofRestarts = 0
    @Volatile private var captureThread: Thread? = null
    @Volatile private var injectThread: Thread? = null
    @Volatile private var micThread: Thread? = null
    @Volatile private var levelThread: Thread? = null
    @Volatile private var micPeak = 0
    /** 診断ログ用: 直近 5 秒のマイク最大値(micPeak は 0.5 秒ごとに画面表示用にリセットされるため別に持つ) */
    @Volatile private var micPeakWindow = 0
    @Volatile private var micRotate = 0
    @Volatile private var micZeroWindows = 0
    @Volatile private var diagPeak = 0
    @Volatile private var appMicRestarts = 0
    @Volatile private var injectedBytes = 0L
    @Volatile private var micMode = "なし"
    @Volatile private var autoOut = false
    @Volatile private var detectedLang: String? = null
    @Volatile private var detectCand: String? = null
    @Volatile private var detectCount = 0
    private val detectLock = Any()
    /** 開始処理(別スレッド)と停止処理が交差しないようにする */
    private val pathLock = Any()
    private val reconnectExec by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tr-reconnect").also { it.isDaemon = true } }
    }

    @Synchronized
    fun start(
        context: Context,
        apiKey: String,
        myLang: String,
        theirLang: String,
        muteOriginalPref: Boolean,
        monitorOut: Boolean,
        echoTarget: Boolean = false,
    ): Boolean {
        val incoming = myLang != TranslateLangs.OFF
        val outgoing = theirLang != TranslateLangs.OFF
        val auto = outgoing && theirLang == TranslateLangs.AUTO
        // 自動検出には相手の声が要るので、「相手→自分」を翻訳しなくても聞き取り用に取得・接続する
        val needIn = incoming || auto
        val muteOriginal = incoming && muteOriginalPref
        if (running.get()) {
            log("すでに翻訳中です")
            return true
        }
        if (!incoming && !outgoing) {
            log("翻訳する方向が選ばれていません")
            return false
        }
        val bridge = BridgeConnector.bridge
        if (bridge == null) {
            log("priv-kit 未接続のため翻訳を開始できません")
            return false
        }
        if (apiKey.isBlank()) {
            log("Gemini API キーが未設定です")
            return false
        }
        if (AiSessionManager.running.get()) {
            log("AI通話が動作中のため翻訳を開始できません")
            return false
        }
        val uid = AiSessionManager.targetUid(context) ?: return false
        val appCtx = context.applicationContext
        val monitor = outgoing && monitorOut

        log(
            "翻訳開始: 相手→自分=${if (incoming) myLang else "オフ"} / 自分→相手=${if (outgoing) theirLang else "オフ"}${if (auto) "(検出前は$FALLBACK_LANG)" else ""} / " +
                "元の声を消す=${incoming && muteOriginal} / echo=$echoTarget / 自分にもモニター=$monitor / model=${GeminiTranslateSession.MODEL}",
        )
        TranslateStore.clear()
        TranslateStore.add(
            "— 翻訳開始（" + listOfNotNull(
                if (incoming) "相手→自分: ${TranslateLangs.label(myLang)}" else null,
                if (auto) "自分→相手: 自動（相手の言語を検出）" else if (outgoing) "自分→相手: ${TranslateLangs.label(theirLang)}" else null,
            ).joinToString(" / ") + "）—",
        )
        setStatus("翻訳開始中...")
        running.set(true)
        injectQueue.clear()
        micPeak = 0; appMicRestarts = 0; micRotate = 0; micZeroWindows = 0; micEofRestarts = 0
        autoOut = auto; detectedLang = null; detectCand = null; detectCount = 0
        injectedBytes = 0L
        micMode = "なし"
        apiKeyInUse = apiKey

        // イヤホンマイク(Bluetooth / 有線 / USB)を使っているか確認し、着脱も見張る
        val routeAm = appCtx.getSystemService(AudioManager::class.java)
        routeText = AudioRoute.summary(routeAm)
        if (routeText.isNotEmpty()) {
            log("音声機器: $routeText")
            TranslateStore.add("🎧 $routeText")
        }
        routeWatcher?.stop()
        routeWatcher = AudioRoute.Watcher(routeAm) {
            val now = AudioRoute.summary(routeAm)
            if (now != routeText) {
                routeText = now
                log("音声機器が変わりました: ${now.ifEmpty { "本体" }}")
                TranslateStore.add("🎧 音声機器が変わりました: ${now.ifEmpty { "本体のマイク・スピーカー" }}")
            }
        }.start()

        // 翻訳音声(24kHz)の再生: 相手→自分 のとき、または自分→相手 をモニターするとき
        if (incoming || monitor) {
            val p = LocalPcmPlayer(24000, appCtx.getSystemService(AudioManager::class.java))
            player = if (p.start()) p else null
        }

        val inCh = if (needIn) {
            // 元の声を消しているときはエコーを強制し、翻訳先言語の発話が完全に無音にならないようにする
            val echoIn = echoTarget || muteOriginal
            Channel(
                "in", if (incoming) "相手→自分" else "相手の言語検出",
                if (incoming) myLang else DETECT_LANG, echoIn,
            ) { pcm24 -> if (incoming) player?.offer(pcm24) }
        } else {
            null
        }
        val outCh = if (outgoing) {
            // 自動のとき(や自分と同じ言語のとき)は、すでに翻訳先の言語で話された音声もそのまま通す
            // (注入は通話アプリのマイク入力を置き換えるので、無音にすると相手に何も聞こえなくなる)
            val echoOut = echoTarget || auto || theirLang == myLang
            Channel("out", "自分→相手", if (auto) FALLBACK_LANG else theirLang, echoOut) { pcm24 ->
                injectQueue.offer(Upsampler2x.process(pcm24))
                if (monitor) player?.offer(pcm24)
            }
        } else {
            null
        }
        chIn = inCh
        chOut = outCh
        if (inCh != null) connectChannel(inCh)
        if (outCh != null) connectChannel(outCh)

        Thread({ startAudioPaths(bridge, appCtx, muteOriginal, needIn, outgoing) }, "tr-start").start()
        return true
    }

    @Synchronized
    fun stop() = shutdown("停止")

    private fun failStop(status: String) = shutdown(status)

    @Synchronized
    private fun shutdown(finalStatus: String) {
        if (!running.getAndSet(false)) return
        log("翻訳停止中... ($finalStatus)")
        setStatus("停止中...")
        TranslateStore.add("— 翻訳終了 —")
        try { chIn?.session?.close() } catch (_: Throwable) {}
        try { chOut?.session?.close() } catch (_: Throwable) {}
        chIn = null
        chOut = null
        injectQueue.clear()
        player?.release()
        player = null
        passPlayer?.release()
        passPlayer = null
        try { routeWatcher?.stop() } catch (_: Throwable) {}
        routeWatcher = null
        routeText = ""
        try { appMicWatcher?.stop() } catch (_: Throwable) {}
        appMicWatcher = null
        try { appMic?.stop() } catch (_: Throwable) {}
        try { appMic?.release() } catch (_: Throwable) {}
        appMic = null
        Thread({
            synchronized(pathLock) {
                val b = BridgeConnector.bridge
                try { b?.stopCapture() } catch (_: Throwable) {}
                try { b?.stopInject() } catch (_: Throwable) {}
                try { b?.stopMicCapture() } catch (_: Throwable) {}
                for (t in listOf(captureThread, injectThread, micThread, levelThread)) {
                    try { t?.join(1000) } catch (_: Throwable) {}
                }
                captureThread = null
                injectThread = null
                micThread = null
                levelThread = null
            }
            log("翻訳停止完了")
            setStatus(finalStatus)
            AiCallService.refreshBubble()
        }, "tr-stop").start()
    }

    // ---------- Gemini セッション ----------

    private fun connectChannel(ch: Channel) {
        lateinit var self: GeminiTranslateSession
        self = GeminiTranslateSession(
            apiKey = apiKeyInUse,
            targetLanguageCode = ch.lang,
            label = ch.label,
            echoTargetLanguage = ch.echo,
            onAudioOut = { pcm -> if (ch.session === self) ch.onAudio(pcm) },
            onInputText = { t -> if (ch.key != "in" || chIn?.label == "相手→自分") TranslateStore.fragment("${ch.key}-src", "${ch.label}［原］ ", t) },
            onInputLang = { c -> if (ch.key == "in") onDetected(c) },
            onOutputText = { t -> if (ch.key != "in" || chIn?.label == "相手→自分") TranslateStore.fragment("${ch.key}-dst", "${ch.label}［訳］ ", t) },
            onReady = { ch.attempt = 0; log("[${ch.label}] 準備完了") },
            onLog = { log("[${ch.label}] $it") },
            onError = { log("[${ch.label}] ERROR: $it") },
            onDisconnected = { reason -> handleDisconnected(ch, self, reason) },
        )
        ch.session = self
        self.connect()
    }

    // ---------- 相手の言語の自動検出 ----------

    /** 相手の声の言語を 2 回続けて同じ判定になったら、自分→相手 の翻訳先をその言語へ切り替える */
    private fun onDetected(raw: String) {
        if (!autoOut || !running.get()) return
        val code = TranslateLangs.normalize(raw) ?: return
        synchronized(detectLock) {
            if (code == detectCand) detectCount++ else { detectCand = code; detectCount = 1 }
            if (detectCount < 2) return
        }
        detectedLang = code
        val ch = chOut ?: return
        if (ch.lang == code) return
        val old = ch.session
        ch.lang = code
        ch.attempt = 0
        ch.adaptCount = 0
        // 新しいセッションを先に登録してから古い方を閉じる(古い方の切断で再接続されないように)
        connectChannel(ch)
        try { old?.close() } catch (_: Throwable) {}
        val name = TranslateLangs.label(code)
        log("相手の言語を検出: $code → 自分→相手の翻訳先を切り替え")
        TranslateStore.add("🌐 相手の言語を検出: $name → 自分の声を${name}に翻訳します")
    }

    private fun handleDisconnected(ch: Channel, who: GeminiTranslateSession, reason: String) {
        if (!running.get() || ch.session !== who) return
        log("[${ch.label}] 切断: $reason")
        // サーバーが setup のフィールド配置を拒否した場合は、配置を入れ替えてすぐ再接続する
        if (GeminiTranslateSession.consumeSetupAdapted() && ch.adaptCount < 4) {
            ch.adaptCount++
            reconnectExec.schedule({
                if (running.get() && ch.session === who) connectChannel(ch)
            }, 300, TimeUnit.MILLISECONDS)
            return
        }
        if (ch.attempt >= MAX_RECONNECT) {
            TranslateStore.add("⚠ ${ch.label}: 再接続できませんでした")
            setStatus("翻訳: ${ch.label} の再接続に失敗")
            return
        }
        val delaySec = RECONNECT_DELAYS_SEC[ch.attempt]
        ch.attempt++
        reconnectExec.schedule({
            if (running.get() && ch.session === who) connectChannel(ch)
        }, delaySec, TimeUnit.SECONDS)
    }

    // ---------- 音声経路(取得 / 注入 / マイク) ----------

    private fun startAudioPaths(
        bridge: IAudioBridge,
        ctx: Context,
        muteOriginal: Boolean,
        incoming: Boolean,
        outgoing: Boolean,
    ) {
        synchronized(pathLock) {
            try {
                // 通話アプリは OS の音声状態から特定する(音量で試す方式は、相手が無言だと外れる)
                val detected = CallAppDetector.resolve(bridge, ctx, 6_000L, { running.get() }) { log(it) }
                var candidates = TargetApps.candidateUids(ctx)
                if (detected != null) {
                    val a = TargetApps.App(detected.packageName ?: "uid:${detected.uid}", detected.label)
                    candidates = listOf(a to detected.uid) + candidates.filter { it.second != detected.uid }
                }
                if (candidates.isEmpty()) {
                    failStop("対応する通話アプリが見つかりません")
                    return
                }
                // 1) 相手音声の取得 — 候補 UID を peak で順に試す（LINE 通話で楽天 UID を掴む事故を防ぐ）
                var capRead: ParcelFileDescriptor? = null
                var chosenUid = candidates.first().second
                var usedVariant = if (muteOriginal) CAPTURE_MUTE else CAPTURE_KEEP
                if (incoming) {
                    var picked = false
                    var fallbackUid: Int? = null
                    var fallbackVariant = usedVariant
                    for ((app, candUid) in candidates) {
                        var variant = if (muteOriginal) CAPTURE_MUTE else CAPTURE_KEEP
                        var fd = tryCapture(bridge, candUid, variant)
                        if (fd == null && muteOriginal) {
                            log("${app.label}: 元の声を消す取得に失敗 → 残す方式で再試行")
                            variant = CAPTURE_KEEP
                            fd = tryCapture(bridge, candUid, variant)
                        }
                        if (fd == null) {
                            log("${app.label} uid=$candUid: 取得開始失敗")
                            continue
                        }
                        val authoritative = detected != null && candUid == detected.uid
                        val peak = if (authoritative) 0 else probeCapturePeak(bridge, 1_200L)
                        if (authoritative || peak > 80) {
                            log(
                                if (authoritative) "取得開始: ${app.label} uid=$candUid variant=$variant（通話アプリとして検出済み）"
                                else "取得成功: ${app.label} uid=$candUid peak=$peak variant=$variant",
                            )
                            TranslateStore.add("通話アプリ: ${app.label}")
                            capRead = fd
                            chosenUid = candUid
                            usedVariant = variant
                            TargetApps.rememberSuccess(candUid)
                            picked = true
                            break
                        }
                        log("${app.label} uid=$candUid: 取得OKだが無音 peak=$peak → 次を試行")
                        try { bridge.stopCapture() } catch (_: Throwable) {}
                        try { fd.close() } catch (_: Throwable) {}
                        if (fallbackUid == null) {
                            fallbackUid = candUid
                            fallbackVariant = variant
                        }
                    }
                    if (!picked) {
                        val u = fallbackUid
                        if (u == null) {
                            failStop("取得失敗（全候補）")
                            return
                        }
                        log("全候補が無音のため最優先候補で継続 uid=$u（相手がまだ話していない可能性）")
                        val label = TargetApps.resolveLabel(ctx, u) ?: "uid=$u"
                        TranslateStore.add("通話アプリ: $label（無音のため仮選択）")
                        usedVariant = fallbackVariant
                        capRead = tryCapture(bridge, u, usedVariant)
                        if (capRead == null) {
                            failStop("取得失敗")
                            return
                        }
                        chosenUid = u
                    }
                    if (usedVariant == CAPTURE_MUTE) {
                        // 元の声は通常の出力へ流れない。翻訳が未準備の間は無音になるので、原音をこちらで再生する
                        val pp = LocalPcmPlayer(CAP_RATE, ctx.getSystemService(AudioManager::class.java))
                        if (pp.start()) passPlayer = pp
                        log("元の声: 消す（翻訳が準備できるまでは原音をこちらで再生）")
                    } else {
                        log("元の声: 残す")
                    }
                } else {
                    log("相手→自分は翻訳しないため、通話音声の取得は行いません（相手の声はそのまま聞こえます）")
                }
                val capFd = capRead

                // 2) 通話アプリのマイク入力へ注入(自分の声の翻訳音声。自分→相手 を翻訳するときだけ)
                var pipe: Array<ParcelFileDescriptor>? = null
                if (outgoing) {
                    val p = ParcelFileDescriptor.createPipe()
                    // パイプの先読みを最小にする(既定 64KB ≒ 0.7 秒分が溜まるのを防ぐ)
                    try { Os.fcntlInt(p[1].fileDescriptor, 1031 /* F_SETPIPE_SZ */, 4096) } catch (_: Throwable) {}
                    val injRes = bridge.startInject(chosenUid, INJECT_VARIANT, p[0])
                    if (injRes.startsWith("OK")) TargetApps.rememberSuccess(chosenUid)
                    try { p[0].close() } catch (_: Throwable) {}
                    log(injRes)
                    if (!injRes.startsWith("OK")) {
                        try { p[1].close() } catch (_: Throwable) {}
                        if (incoming) { try { bridge.stopCapture() } catch (_: Throwable) {} }
                        try { capFd?.close() } catch (_: Throwable) {}
                        failStop("注入失敗")
                        return
                    }
                    pipe = p
                } else {
                    log("自分→相手は翻訳しないため、注入は行いません（通話アプリのマイク入力はそのまま）")
                }
                val injPipe = pipe

                // 3) 本物のマイク(特権 → だめならアプリ側)
                var micRead: ParcelFileDescriptor? = null
                if (outgoing) {
                    micRead = startShellMic(bridge)
                    val appMicOk = if (micRead == null) startAppMic(ctx) else false
                    if (micRead == null && !appMicOk) {
                        micMode = "取得不可"
                        log("マイクを取得できませんでした。自分→相手 は動作しません")
                        TranslateStore.add("⚠ マイクを取得できませんでした（自分→相手 は動作しません）")
                        if (!incoming) {
                            failStop("マイク取得失敗")
                            return
                        }
                    }
                    log("マイク経路: $micMode")
                }
                val micFd = micRead

                setStatus("翻訳中")
                AiCallService.refreshBubble()

                if (capFd != null) {
                    captureThread = Thread({
                        val down = Downsampler3x()
                        val align = Pcm16Aligner()
                        try {
                            FileInputStream(capFd.fileDescriptor).use { ins ->
                                val buf = ByteArray(3840)
                                while (running.get()) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    if (n > 0) {
                                        val even = align.feed(buf, n)
                                        if (even.isEmpty()) continue
                                        val g = chIn?.session
                                        if (g != null && g.isReady()) {
                                            val pcm16 = down.process(even, even.size)
                                            g.sendAudioPcm(pcm16, IN_RATE)
                                        } else {
                                            // 翻訳が未準備の間は、(元の声を消している場合に限り)原音を流して無音を避ける
                                            passPlayer?.offer(even)
                                        }
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            if (running.get()) log("取得スレッド: ${e.message}")
                        } finally {
                            try { capFd.close() } catch (_: Throwable) {}
                        }
                    }, "tr-cap").also { it.start() }
                }

                if (injPipe != null) {
                    injectThread = Thread({
                        // 20ms ずつ実時間に合わせて通話経路へ送る(AiSessionManager と同じ)
                        val chunk = 1920 // 48kHz s16le mono の 20ms
                        val leadNs = 40_000_000L
                        var nextAt = 0L
                        try {
                            FileOutputStream(injPipe[1].fileDescriptor).use { os ->
                                while (running.get()) {
                                    val pcm = injectQueue.poll(200, TimeUnit.MILLISECONDS)
                                    if (pcm == null) { nextAt = 0L; continue }
                                    var off = 0
                                    while (off < pcm.size && running.get()) {
                                        val n = minOf(chunk, pcm.size - off)
                                        val now = System.nanoTime()
                                        if (nextAt == 0L || nextAt < now - 100_000_000L) nextAt = now
                                        val waitNs = nextAt - now - leadNs
                                        if (waitNs > 0) Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                                        os.write(pcm, off, n)
                                        injectedBytes += n
                                        nextAt += n / 2L * 1_000_000_000L / CAP_RATE
                                        off += n
                                    }
                                }
                            }
                        } catch (_: InterruptedException) {
                        } catch (e: Throwable) {
                            if (running.get()) log("注入スレッド: ${e.message}")
                        } finally {
                            try { injPipe[1].close() } catch (_: Throwable) {}
                        }
                    }, "tr-inject").also { it.start() }
                }

                if (micFd != null) startMicReader(micFd)

                val dir = when {
                    incoming && outgoing -> "双方向"
                    incoming -> "相手→自分のみ"
                    else -> "自分→相手のみ"
                }
                levelThread = Thread({
                    var lastDiagMs = 0L
                    while (running.get()) {
                        try {
                            // 5 秒ごとに診断を logcat へ(マイクに声が入っているか / 注入まで進んでいるか)
                            val nowMs = System.currentTimeMillis()
                            if (outgoing && nowMs - lastDiagMs >= 5000) {
                                lastDiagMs = nowMs
                                log("診断: マイクpeak(5秒)=$micPeakWindow/32767 [$micMode] 注入済み=${injectedBytes / 1000}KB 注入待ち=${injectQueue.size}チャンク")
                                if (micPeakWindow == 0 && micMode.startsWith("特権")) micZeroWindows++ else micZeroWindows = 0
                                micPeakWindow = 0
                                // 完全な 0 が 2 窓(約 5〜10 秒)続いたら無音化とみなして取り直す(最大 6 回)
                                if (micZeroWindows >= 2 && micRotate < 6) {
                                    micZeroWindows = 0
                                    rotateShellMic(bridge)
                                }
                            }
                            val parts = ArrayList<String>()
                            if (incoming) parts.add("相手音声: ${bridge.captureLevel()}/32767")
                            if (outgoing) {
                                val mp = micPeak
                                micPeak = 0
                                parts.add("マイク: $mp/32767 [$micMode]")
                                if (routeText.isNotEmpty()) parts.add("🎧$routeText")
                                parts.add("注入: ${injectedBytes / 1000}KB")
                                if (autoOut) parts.add("相手の言語: " + (detectedLang?.let { TranslateLangs.label(it) } ?: "検出中（仮: ${TranslateLangs.label(FALLBACK_LANG)}）"))
                            }
                            setStatus("翻訳中（$dir）  " + parts.joinToString("  "))
                            Thread.sleep(500)
                        } catch (_: Throwable) { break }
                    }
                }, "tr-level").also { it.start() }
            } catch (e: Throwable) {
                Log.e(TAG, "translate start failed (${e.javaClass.simpleName})")
                log("翻訳開始失敗: ${e.message}")
                failStop("翻訳開始失敗")
            }
        }
    }

    private fun tryCapture(bridge: IAudioBridge, uid: Int, variant: Int): ParcelFileDescriptor? {
        val p = ParcelFileDescriptor.createPipe()
        val res = try { bridge.startCapture(uid, variant, p[1]) } catch (e: Throwable) { "NG(exception): ${e.message}" }
        try { p[1].close() } catch (_: Throwable) {}
        log("取得(uid=$uid variant=$variant): $res")
        if (res.startsWith("OK")) return p[0]
        try { p[0].close() } catch (_: Throwable) {}
        try { bridge.stopCapture() } catch (_: Throwable) {}
        return null
    }

    /** 取得開始直後の peak を見て、本当に相手音声が来ているか判定する */
    private fun probeCapturePeak(bridge: IAudioBridge, waitMs: Long): Int {
        var maxPeak = 0
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val p = bridge.captureLevel()
                if (p > maxPeak) maxPeak = p
            } catch (_: Throwable) {
            }
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                break
            }
        }
        return maxPeak
    }

    /** 特権マイクのパイプを読んで「自分→相手」へ送るスレッドを起動する(作り直し時にも使う) */
    private fun startMicReader(micFd: ParcelFileDescriptor) {
        val gen = ++micGen
        micThread = Thread({
            val align = Pcm16Aligner()
            try {
                FileInputStream(micFd.fileDescriptor).use { ins ->
                    val buf = ByteArray(3200)
                    while (running.get()) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            val even = align.feed(buf, n)
                            if (even.isNotEmpty()) onMicPcm(even)
                        }
                    }
                }
            } catch (e: Throwable) {
                if (running.get()) log("マイクスレッド: ${e.message}")
            } finally {
                try { micFd.close() } catch (_: Throwable) {}
                // 通話中に入力が途切れた(イヤホンマイクの抜き差し・Bluetooth の切り替えなど)ら、取り直す。
                // 作り直しの最中に古いスレッドが終わっただけの場合(世代が違う)は何もしない
                if (running.get() && gen == micGen) restartMicAfterDrop()
            }
        }, "tr-mic").also { it.start() }
    }

    /** 特権マイクが途切れたときに取り直す(最大 5 回)。取り直す機器は shell 側が通話機器に合わせて選ぶ */
    private fun restartMicAfterDrop() {
        if (!running.get() || micEofRestarts >= 5) return
        micEofRestarts++
        val n = micEofRestarts
        Thread({
            try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
            val b = BridgeConnector.bridge ?: return@Thread
            synchronized(pathLock) {
                if (!running.get()) return@Thread
                log("マイクの入力が途切れたため取り直します #$n（イヤホンマイクの着脱・通話機器の切り替えなど）")
                micGen++
                try { b.stopMicCapture() } catch (_: Throwable) {}
                val fd = startShellMic(b)
                if (fd != null) startMicReader(fd) else log("マイクの取り直しに失敗")
            }
        }, "tr-mic-restart").start()
    }

    /**
     * 特権マイクが OS に無音化された(peak が完全に 0 のまま)場合に、音源の順番を変えて取り直す。
     * 実機では録音が一度 dead になり、作り直された録音が無音(peak=0)のままだった。
     */
    private fun rotateShellMic(bridge: IAudioBridge) {
        val all = intArrayOf(101, 7, 107, 106, 1, 6)
        micRotate++
        val order = IntArray(all.size) { all[(it + micRotate) % all.size] }
        log("⚠ マイクが無音のまま(peak=0)。音源を変えて取り直します #$micRotate 順=${order.joinToString(",")}")
        micGen++ // 古い読み取りスレッドが「途切れた」と誤って取り直さないよう、世代を進める
        try { bridge.stopMicCapture() } catch (_: Throwable) {}
        val fd = startShellMic(bridge, order)
        if (fd != null) startMicReader(fd) else log("マイクの取り直しに失敗")
    }

    /**
     * 特権(shell)側でマイクを録る。音源 × privacySensitive の組み合わせを順に試す。
     * (+100 = privacySensitive=false。通話アプリの VOICE_COMMUNICATION 録音中でも通る組み合わせがあるかを探る)
     */
    private fun startShellMic(bridge: IAudioBridge, order: IntArray = intArrayOf(101, 7, 107, 106)): ParcelFileDescriptor? {
        for (src in order) {
            val p = ParcelFileDescriptor.createPipe()
            val res = try { bridge.startMicCapture(src, p[1]) } catch (e: Throwable) { "NG(exception): ${e.message}" }
            try { p[1].close() } catch (_: Throwable) {}
            log("マイク(特権 source=$src): $res")
            if (res.startsWith("OK")) {
                micMode = if (src >= 100) "特権 src=${src - 100}(非sensitive)" else "特権 src=$src"
                return p[0]
            }
            try { p[0].close() } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * アプリ側の AudioRecord(RECORD_AUDIO が必要)。バックグラウンドでは OS に無音化されるため、
     * AiCallService を microphone タイプの前面サービスにしてある。録音が実際に始まったかを必ず確認する。
     */
    @SuppressLint("MissingPermission")
    private fun startAppMic(ctx: Context): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("マイク(アプリ側): RECORD_AUDIO が未許可（翻訳タブの「セット」で許可を求めます）")
            return false
        }
        // (音源, privacySensitive=false にするか)
        val tries = listOf(6 to true, 1 to true, 7 to false)
        for ((src, nonSensitive) in tries) {
            var created: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val builder = AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(IN_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(min, IN_RATE))
                if (nonSensitive) builder.setPrivacySensitive(false)
                val rec = builder.build()
                created = rec
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    log("マイク(アプリ側 src=$src): 初期化失敗 state=${rec.state}")
                    rec.release()
                    continue
                }
                val routeAm = ctx.getSystemService(AudioManager::class.java)
                AudioRoute.applyInput(rec, routeAm, "マイク(アプリ側)") { log(it) }
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    log("マイク(アプリ側 src=$src 非sensitive=$nonSensitive): startRecording が拒否された recordingState=${rec.recordingState}")
                    rec.release()
                    continue
                }
                appMic = rec
                appMicWatcher?.stop()
                appMicWatcher = AudioRoute.Watcher(routeAm) {
                    AudioRoute.applyInput(rec, routeAm, "マイク(アプリ側・機器変更)") { log(it) }
                }.start()
                micMode = "アプリ側 src=$src" + if (nonSensitive) "(非sensitive)" else ""
                log("マイク(アプリ側 src=$src 非sensitive=$nonSensitive): 録音開始")
                micThread = Thread({
                    val buf = ByteArray(3200) // 100ms
                    var lastDiag = 0L
                    try {
                        while (running.get()) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n > 0) diagPeak = maxOf(diagPeak, peak16(buf.copyOf(n)))
                            if (n < 0) {
                                log("マイク(アプリ側): read エラー $n")
                                // 経路の切り替わり等で録音が死んだら、作り直して続ける(最大 20 回)
                                if (running.get() && appMicRestarts++ < 20) {
                                    try { rec.release() } catch (_: Throwable) {}
                                    Thread.sleep(300)
                                    if (running.get()) { log("マイク(アプリ側): 再取得 #$appMicRestarts"); startAppMic(ctx) }
                                }
                                break
                            }
                            if (n > 0) onMicPcm(if (n == buf.size) buf else buf.copyOf(n))
                            val now = System.currentTimeMillis()
                            if (now - lastDiag > 5000) {
                                lastDiag = now
                                val silenced = appMicSilenced(ctx)
                                log("マイク(アプリ側)診断: peak=$diagPeak silenced=$silenced")
                                if (silenced == true) {
                                    micMode = "アプリ側(OSに無音化されている)"
                                    log("⚠ 通話アプリの録音が優先され、このアプリのマイクは OS に無音化されています。特権側マイクの取得が必要です")
                                }
                                diagPeak = 0
                            }
                        }
                    } catch (e: Throwable) {
                        if (running.get()) log("マイク(アプリ側)スレッド: ${e.message}")
                    }
                }, "tr-mic-app").also { it.start() }
                return true
            } catch (e: Throwable) {
                log("マイク(アプリ側 src=$src) 失敗: ${e.javaClass.simpleName}: ${e.message}")
                try { created?.release() } catch (_: Throwable) {}
            }
        }
        return false
    }

    /** このアプリの録音が OS に無音化されているか(取れなければ null) */
    private fun appMicSilenced(ctx: Context): Boolean? = try {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val mine = am.activeRecordingConfigurations
        mine.firstOrNull { it.clientAudioSource == 1 || it.clientAudioSource == 6 || it.clientAudioSource == 7 }?.isClientSilenced
    } catch (_: Throwable) { null }

    /** マイクの 16kHz PCM を「自分→相手」セッションへ送る */
    private fun onMicPcm(pcm16: ByteArray) {
        val pk = peak16(pcm16)
        micPeak = maxOf(micPeak, pk)
        micPeakWindow = maxOf(micPeakWindow, pk)
        chOut?.session?.let { g -> if (g.isReady()) g.sendAudioPcm(pcm16, IN_RATE) }
    }

    // ---------- util ----------

    private fun setStatus(s: String) {
        lastStatus = s
        onStatus?.invoke(s)
    }

    private fun log(msg: String) {
        msg.chunked(3000).forEach { Log.i(TAG, "[translate] $it") }
    }

    private fun peak16(b: ByteArray): Int {
        var m = 0
        var i = 0
        while (i + 1 < b.size) {
            val v = ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toInt()
            m = maxOf(m, abs(v))
            i += 2
        }
        return m
    }

    /** パイプ読み取りで奇数バイトになっても、s16le のサンプル境界がずれないようにする */
    private class Pcm16Aligner {
        private var carry: Byte = 0
        private var hasCarry = false

        fun feed(buf: ByteArray, n: Int): ByteArray {
            val all = if (hasCarry) byteArrayOf(carry) + buf.copyOf(n) else buf.copyOf(n)
            val even = all.size and 1.inv()
            if (all.size > even) {
                carry = all[all.size - 1]
                hasCarry = true
            } else {
                hasCarry = false
            }
            return all.copyOf(even)
        }
    }

    /** 翻訳音声(24kHz)を自分の端末で再生する */
    private class LocalPcmPlayer(private val rate: Int, private val am: AudioManager? = null) {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var watcher: AudioRoute.Watcher? = null
        private var track: AudioTrack? = null
        private var thread: Thread? = null

        fun start(): Boolean = try {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val fmt = AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min, rate * 2 / 5))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            // 通話の機器が Bluetooth / 有線だと分かるときは、再生もその機器へ向ける。着脱にも追従する
            if (am != null) {
                AudioRoute.applyOutput(t, am, "翻訳音声の再生") { Log.i(TAG, "[translate] $it") }
                watcher = AudioRoute.Watcher(am) {
                    AudioRoute.applyOutput(t, am, "翻訳音声の再生(機器変更)") { m -> Log.i(TAG, "[translate] $m") }
                }.start()
            }
            t.play()
            track = t
            thread = Thread({
                try {
                    while (true) {
                        val b = queue.take()
                        var off = 0
                        while (off < b.size) {
                            val w = t.write(b, off, b.size - off)
                            if (w <= 0) break
                            off += w
                        }
                    }
                } catch (_: InterruptedException) {
                } catch (_: Throwable) {}
            }, "tr-local-play").also { it.start() }
            Log.i(TAG, "[translate] ローカル再生開始 (VOICE_COMMUNICATION / ${rate}Hz)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "[translate] local AudioTrack failed (${e.javaClass.simpleName})")
            false
        }

        fun offer(b: ByteArray) {
            queue.offer(b)
        }

        fun release() {
            try { watcher?.stop() } catch (_: Throwable) {}
            watcher = null
            thread?.interrupt()
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
            track = null
            queue.clear()
        }
    }
}
