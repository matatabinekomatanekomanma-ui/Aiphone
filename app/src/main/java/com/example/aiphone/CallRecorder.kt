package com.example.aiphone

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.math.roundToInt

/**
 * 通話の録音(M4A / AAC-LC・モノラル・48kHz・128kbps ≒ 1MB/分)。共有の「音楽」フォルダ(Music/Aiphone)へ保存する。
 *
 * 録る音は 2 つを 1 本に混ぜたもの:
 *   相手の声   … 通話アプリの再生音の取得(特権)
 *   自分の側   … 相手に届く声。AI・翻訳の声(通話マイクへの注入)。AI も翻訳もないときは自分の生の声(特権マイク)
 *
 * AI 代行(AiSessionManager)と翻訳(TranslateManager)は、自分たちが取得・注入している音を feedPeer / feedMine で渡す。
 * どちらも取得やマイクを使っていないあいだは、この録音が自分で取得・マイクを動かす(監視スレッドが 1 秒ごとに判断)。
 */

object CallRecorder {
    private const val TAG = "RLAudio"
    private const val RATE = 48000
    private const val FRAME_BYTES = 1920 // 48kHz s16le mono の 20ms
    private const val BITRATE = 128000
    private const val MIN_SECONDS = 3
    private const val RELATIVE_DIR = "Music/Aiphone"
    private const val CAPTURE_VARIANT = 5 // 元の声を残して取得(AI / 翻訳と同じ)

    private val active = AtomicBoolean(false)
    @SuppressLint("StaticFieldLeak")
    @Volatile private var appCtx: Context? = null
    @Volatile private var session: Session? = null
    @Volatile private var mixThread: Thread? = null
    @Volatile private var superviseThread: Thread? = null
    @Volatile private var startWallMs = 0L

    /** 録音の音量(%。100 が元の大きさ)。録音の開始時に設定から入れる */
    @Volatile var gainPercent = DEFAULT_GAIN
    const val MIN_GAIN = 50
    const val MAX_GAIN = 200
    const val DEFAULT_GAIN = 100

    // 混ぜ方の調整値(バイト数は 48kHz s16le mono)
    private const val PRIME_PEER = 19200   // 相手の声: 開始時に 200ms ためてから流し始める(取得の到着のゆらぎを吸収)
    private const val PRIME_MINE = 19200   // 自分側(AI・翻訳の声): 200ms。実時間で少しずつ届くため、短いと到着のゆらぎで途切れる
    private const val REPRIME_PEER = 5760  // いったん足りなくなったあと、60ms たまったら再開
    private const val REPRIME_MINE = 9600  // 自分側は 100ms たまったら再開(短く切れた間が長く伸びないように)
    private const val FLUSH_IDLE_MS = 100L // 先読みに届かない短い発話は、到着が止まって 100ms でそのまま流す
    private const val MAX_LATENCY = 48000  // 500ms を超えてたまったら
    private const val TRIM_TO = 9600       // 100ms まで古い方を捨てる(遅延が増え続けるのを防ぐ)
    // 合成後の過大なピークを抑えるソフトニー・コンプレッサーとソフトリミッター。
    private const val COMPRESS_THRESHOLD = 24500
    private const val COMPRESS_KNEE_WIDTH = 6000
    private const val COMPRESS_RATIO = 4.0
    private const val LIMITER_START = 28000
    private const val OUTPUT_CEILING = 30000

    private val peer = PcmRing(cap = RATE * 2 * 2)
    private val mine = PcmRing(cap = RATE * 2 * 2)

    // ---- 自前の取得・マイク(AI も翻訳も使っていないとき) ----
    @Volatile private var capRunning = false
    @Volatile private var capStarting = false
    @Volatile private var capFd: ParcelFileDescriptor? = null
    @Volatile private var capRetryAt = 0L
    @Volatile private var micRunning = false
    @Volatile private var micStarting = false
    @Volatile private var micFd: ParcelFileDescriptor? = null
    @Volatile private var micRetryAt = 0L

    private fun log(msg: String) {
        Log.i(TAG, "[rec] $msg")
    }

    // ================= 外から呼ぶ =================

    /** 通話開始時に呼ぶ(設定で ON のときだけ)。すでに録音中なら何もしない */
    @Synchronized
    fun start(context: Context) {
        if (active.get()) return
        val app = context.applicationContext
        val s = try {
            Session.create(app)
        } catch (e: Throwable) {
            Log.w(TAG, "[rec] 録音を開始できません: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        appCtx = app
        peer.clear(); mine.clear()
        startWallMs = System.currentTimeMillis()
        capRetryAt = 0L; micRetryAt = 0L
        session = s
        active.set(true)
        mixThread = Thread({ mixLoop(s) }, "rec-mix").also { it.start() }
        superviseThread = Thread({ superviseLoop() }, "rec-supervise").also { it.start() }
        log("通話の録音を開始: ${RELATIVE_DIR}/${s.displayName} (音量=${gainPercent}%)")
    }

    /** 通話終了時に呼ぶ。ファイルの仕上げは別スレッドで行う */
    @Synchronized
    fun stop() {
        if (!active.getAndSet(false)) return
        val s = session
        val mt = mixThread
        val sv = superviseThread
        session = null; mixThread = null; superviseThread = null
        Thread({
            try { sv?.interrupt() } catch (_: Throwable) {}
            stopStandalone(callBridgeStop = true)
            try { mt?.join(2000) } catch (_: Throwable) {}
            try { s?.finish() } catch (e: Throwable) { Log.w(TAG, "[rec] 仕上げに失敗: ${e.message}") }
        }, "rec-stop").start()
    }

    /**
     * AI / 翻訳が取得やマイクを始める直前に呼ぶ。自前の取得・マイクを止めて、特権側の取得を譲る
     * (取得・マイクは同時に 1 つしか持てないため)。
     */
    @Synchronized
    fun releaseForSession() {
        if (!active.get()) return
        stopStandalone(callBridgeStop = true)
        val until = System.currentTimeMillis() + 8000
        capRetryAt = until; micRetryAt = until
    }

    /** 相手の声(48kHz s16le mono)を渡す */
    fun feedPeer(b: ByteArray, off: Int, len: Int) {
        if (active.get()) peer.write(b, off, len)
    }

    /** 相手に届く側の声(48kHz s16le mono)を渡す */
    fun feedMine(b: ByteArray, off: Int, len: Int) {
        if (active.get()) mine.write(b, off, len)
    }

    // ================= 2 本を混ぜて AAC にする =================

    /**
     * 1 本ぶんの読み出し状態。先読みがたまるまでは無音を出し、足りなくなったら無音にして再びためる。
     * 先読み(= ゆらぎを吸収する余裕)が大きいほど、受け取りが一瞬遅れても途切れない。
     * 発話が短くて先読みに届かないまま到着が止まったときは、少し待って残りを流す。
     */
    private class Lane(val ring: PcmRing, val firstNeed: Int, val reprimeNeed: Int) {
        var primed = false
        var everPrimed = false

        fun fill(dst: ByteArray) {
            val avail = ring.available()
            if (!primed) {
                val need = if (everPrimed) reprimeNeed else firstNeed
                val flush = avail > 0 && ring.idleMs() >= FLUSH_IDLE_MS
                if (avail >= need || flush) { primed = true; everPrimed = true }
            }
            var got = 0
            if (primed) {
                if (avail < FRAME_BYTES) {
                    // 足りない: 残りを出し切って、再びためる
                    primed = false
                    got = ring.read(dst, FRAME_BYTES)
                } else {
                    if (avail > MAX_LATENCY) ring.trimTo(TRIM_TO)
                    got = ring.read(dst, FRAME_BYTES)
                }
            }
            java.util.Arrays.fill(dst, got, FRAME_BYTES, 0)
        }
    }

    private fun mixLoop(s: Session) {
        val pb = ByteArray(FRAME_BYTES)
        val mb = ByteArray(FRAME_BYTES)
        val out = ByteArray(FRAME_BYTES)
        val lp = Lane(peer, PRIME_PEER, REPRIME_PEER)
        val lm = Lane(mine, PRIME_MINE, REPRIME_MINE)
        var nextAt = System.nanoTime()
        val gain = gainPercent.coerceIn(MIN_GAIN, MAX_GAIN)
        try {
            while (active.get()) {
                lp.fill(pb)
                lm.fill(mb)
                var i = 0
                while (i < FRAME_BYTES) {
                    val a = ((pb[i + 1].toInt() shl 8) or (pb[i].toInt() and 0xff)).toShort().toInt()
                    val b = ((mb[i + 1].toInt() shl 8) or (mb[i].toInt() and 0xff)).toShort().toInt()
                    // 2 系統を足して、設定の音量(100% が元の大きさ)を掛ける。過大なピークはソフトに圧縮する。
                    val mixed = ((a.toDouble() + b.toDouble()) * gain / 100.0).roundToInt()
                    val v = compressAndLimit(mixed)
                    out[i] = (v and 0xff).toByte()
                    out[i + 1] = ((v shr 8) and 0xff).toByte()
                    i += 2
                }
                s.feed(out, FRAME_BYTES)
                nextAt += 20_000_000L
                val wait = nextAt - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
                else if (wait < -200_000_000L) { log("混ぜ処理が ${-wait / 1_000_000L}ms 遅れました(端末の負荷)"); nextAt = System.nanoTime() }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            Log.w(TAG, "[rec] 録音の処理で例外: ${e.javaClass.simpleName}: ${e.message}")
            active.set(false) // finish は stop() 側が呼べないので、ここで仕上げる
            try { s.finish() } catch (_: Throwable) {}
            session = null
        }
    }

    /** 目立つ歪みを避けるためのソフトニー・コンプレッサーとソフトリミッター。 */
    private fun compressAndLimit(input: Int): Int {
        val negative = input < 0
        val x = abs(input).toDouble()
        val halfKnee = COMPRESS_KNEE_WIDTH / 2.0
        val kneeStart = COMPRESS_THRESHOLD - halfKnee
        val kneeEnd = COMPRESS_THRESHOLD + halfKnee
        val compressed = when {
            x <= kneeStart -> x
            x >= kneeEnd -> COMPRESS_THRESHOLD + (x - COMPRESS_THRESHOLD) / COMPRESS_RATIO
            else -> {
                val d = x - kneeStart
                x + (1.0 / COMPRESS_RATIO - 1.0) * d * d / (2.0 * COMPRESS_KNEE_WIDTH)
            }
        }
        // 最後の 2,000 counts を滑らかに飽和させ、急なハードクリップを避ける。
        val limited = if (compressed <= LIMITER_START) compressed else {
            val range = (OUTPUT_CEILING - LIMITER_START).toDouble()
            LIMITER_START + range * tanh((compressed - LIMITER_START) / range)
        }
        val sample = limited.roundToInt().coerceIn(0, OUTPUT_CEILING)
        return if (negative) -sample else sample
    }

    // ================= 自前の取得・マイクの監視 =================

    private fun superviseLoop() {
        try {
            Thread.sleep(1500) // AI / 翻訳が開始する時間を待つ
            while (active.get()) {
                try { superviseTick() } catch (e: Throwable) { log("監視: ${e.message}") }
                Thread.sleep(1000)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun superviseTick() {
        val ctx = appCtx ?: return
        val bridge = BridgeConnector.bridge ?: return
        val ai = AiSessionManager.running.get()
        val tr = TranslateManager.running.get()
        // AI は取得と注入を使う(マイクは使わないが、相手に届くのは AI の声だけなので生の声は録らない)
        val capOwned = ai || (tr && TranslateManager.usesCapture)
        val micOwned = ai || (tr && TranslateManager.usesMic)
        val now = System.currentTimeMillis()
        if (capOwned) {
            if (capRunning) stopStandaloneCapture(callBridgeStop = false)
        } else if (!capRunning && !capStarting && now >= capRetryAt) {
            startStandaloneCapture(bridge, ctx)
        }
        if (micOwned) {
            if (micRunning) stopStandaloneMic(callBridgeStop = false)
        } else if (!micRunning && !micStarting && now >= micRetryAt) {
            startStandaloneMic(bridge)
        }
    }

    private fun startStandaloneCapture(bridge: IAudioBridge, ctx: Context) {
        capStarting = true
        capRetryAt = System.currentTimeMillis() + 4000 // 失敗しても 4 秒は再試行しない
        Thread({
            try {
                val detected = CallAppDetector.resolve(bridge, ctx, 2_500L, { active.get() }) { log(it) }
                var candidates = TargetApps.candidateUids(ctx)
                if (detected != null) {
                    val a = TargetApps.App(detected.packageName ?: "uid:${detected.uid}", detected.label)
                    candidates = listOf(a to detected.uid) + candidates.filter { it.second != detected.uid }
                }
                for ((app, uid) in candidates) {
                    if (!active.get()) return@Thread
                    val p = ParcelFileDescriptor.createPipe()
                    val res = try { bridge.startCapture(uid, CAPTURE_VARIANT, p[1]) } catch (e: Throwable) { "NG(exception): ${e.message}" }
                    try { p[1].close() } catch (_: Throwable) {}
                    if (res.startsWith("OK")) {
                        if (!active.get()) {
                            try { bridge.stopCapture() } catch (_: Throwable) {}
                            try { p[0].close() } catch (_: Throwable) {}
                            return@Thread
                        }
                        log("相手の声の取得を開始: ${app.label} uid=$uid")
                        capFd = p[0]
                        capRunning = true
                        TargetApps.rememberSuccess(uid)
                        readCapture(p[0])
                        return@Thread
                    }
                    try { p[0].close() } catch (_: Throwable) {}
                    try { bridge.stopCapture() } catch (_: Throwable) {}
                }
                log("相手の声を取得できませんでした(通話アプリが見つからない、または取得に失敗)")
            } catch (e: Throwable) {
                log("取得の開始で例外: ${e.message}")
            } finally {
                capStarting = false
            }
        }, "rec-cap-start").start()
    }

    private fun readCapture(fd: ParcelFileDescriptor) {
        Thread({
            try {
                FileInputStream(fd.fileDescriptor).use { ins ->
                    val buf = ByteArray(3840)
                    while (active.get()) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) peer.write(buf, 0, n)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { fd.close() } catch (_: Throwable) {}
                if (capFd === fd) { capFd = null; capRunning = false }
            }
        }, "rec-cap").start()
    }

    private fun startStandaloneMic(bridge: IAudioBridge) {
        micStarting = true
        micRetryAt = System.currentTimeMillis() + 4000
        Thread({
            try {
                // 音源の順は翻訳と同じ(+100 は privacySensitive=false。通話中でも通る組み合わせを先に試す)
                for (src in intArrayOf(101, 7, 107, 106)) {
                    if (!active.get()) return@Thread
                    val p = ParcelFileDescriptor.createPipe()
                    val res = try { bridge.startMicCapture(src, p[1]) } catch (e: Throwable) { "NG(exception): ${e.message}" }
                    try { p[1].close() } catch (_: Throwable) {}
                    if (res.startsWith("OK")) {
                        if (!active.get()) {
                            try { bridge.stopMicCapture() } catch (_: Throwable) {}
                            try { p[0].close() } catch (_: Throwable) {}
                            return@Thread
                        }
                        log("自分のマイクの録音を開始 (source=$src)")
                        micFd = p[0]
                        micRunning = true
                        readMic(p[0])
                        return@Thread
                    }
                    try { p[0].close() } catch (_: Throwable) {}
                }
                log("自分のマイクを録音できませんでした")
            } catch (e: Throwable) {
                log("マイクの開始で例外: ${e.message}")
            } finally {
                micStarting = false
            }
        }, "rec-mic-start").start()
    }

    /** 特権マイク(16kHz s16le mono)を 48kHz にして渡す */
    private fun readMic(fd: ParcelFileDescriptor) {
        Thread({
            val up = Up3()
            try {
                FileInputStream(fd.fileDescriptor).use { ins ->
                    val buf = ByteArray(3200)
                    while (active.get()) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            val pcm48 = up.process(buf, n)
                            mine.write(pcm48, 0, pcm48.size)
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { fd.close() } catch (_: Throwable) {}
                if (micFd === fd) { micFd = null; micRunning = false }
            }
        }, "rec-mic").start()
    }

    private fun stopStandalone(callBridgeStop: Boolean) {
        stopStandaloneCapture(callBridgeStop)
        stopStandaloneMic(callBridgeStop)
    }

    /**
     * @param callBridgeStop false のときは自分の読み取りを閉じるだけにする
     *   (AI / 翻訳がすでに特権側の取得を引き継いでいるので、bridge の取得を止めると相手の取得まで止めてしまう)
     */
    private fun stopStandaloneCapture(callBridgeStop: Boolean) {
        val fd = capFd
        val wasRunning = capRunning
        capFd = null; capRunning = false
        if (callBridgeStop && wasRunning) try { BridgeConnector.bridge?.stopCapture() } catch (_: Throwable) {}
        try { fd?.close() } catch (_: Throwable) {}
    }

    private fun stopStandaloneMic(callBridgeStop: Boolean) {
        val fd = micFd
        val wasRunning = micRunning
        micFd = null; micRunning = false
        if (callBridgeStop && wasRunning) try { BridgeConnector.bridge?.stopMicCapture() } catch (_: Throwable) {}
        try { fd?.close() } catch (_: Throwable) {}
    }

    // ================= 部品 =================

    /** 書き込みと読み出しが別スレッドの、固定長のリングバッファ(2 秒ぶん。あふれたら古い方を捨てる)。常に偶数バイトで扱う */
    private class PcmRing(private val cap: Int = RATE * 2 * 2) {
        private val buf = ByteArray(cap)
        private var head = 0
        private var size = 0
        private var odd = -1

        private var lastWriteNs = 0L

        @Synchronized
        fun write(src: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            lastWriteNs = System.nanoTime()
            var o = off
            var n = len
            if (odd >= 0) {
                val two = byteArrayOf(odd.toByte(), src[o])
                put(two, 0, 2)
                odd = -1
                o++; n--
            }
            if (n and 1 == 1) { odd = src[o + n - 1].toInt() and 0xff; n-- }
            if (n > 0) put(src, o, n)
        }

        private fun put(src: ByteArray, off: Int, len: Int) {
            var o = off
            var n = len
            if (n > cap) { o += n - cap; n = cap }
            val over = size + n - cap
            if (over > 0) { head = (head + over) % cap; size -= over }
            var tail = (head + size) % cap
            var left = n
            while (left > 0) {
                val c = minOf(left, cap - tail)
                System.arraycopy(src, o, buf, tail, c)
                tail = (tail + c) % cap
                o += c; left -= c
            }
            size += n
        }

        @Synchronized fun available(): Int = size

        /** dst に最大 len バイトを読む。読めたバイト数を返す(足りない分は呼び出し側が無音で埋める) */
        @Synchronized
        fun read(dst: ByteArray, len: Int): Int {
            val n = minOf(len, size)
            var h = head
            var d = 0
            while (d < n) {
                val c = minOf(n - d, cap - h)
                System.arraycopy(buf, h, dst, d, c)
                h = (h + c) % cap
                d += c
            }
            head = h; size -= n
            return n
        }

        /** たまりすぎたとき、新しい keep バイトだけ残して古い方を捨てる。捨てたバイト数を返す */
        @Synchronized
        fun trimTo(keep: Int): Long {
            if (size <= keep) return 0
            val drop = size - keep
            head = (head + drop) % cap; size = keep
            return drop.toLong()
        }

        @Synchronized
        fun clear() { head = 0; size = 0; odd = -1; lastWriteNs = 0 }

        /** 最後に書き込まれてからの経過(ms)。まだ書き込みがなければ 0 */
        @Synchronized
        fun idleMs(): Long = if (lastWriteNs == 0L) 0L else (System.nanoTime() - lastWriteNs) / 1_000_000L
    }

    /** 16kHz → 48kHz。窓付き sinc FIR の 3 倍補間で、直線補間より折り返し成分を抑える。 */
    private class Up3 {
        private var odd = -1
        private val history = DoubleArray(FILTER_TAPS)
        private val coeffs = makeCoefficients()

        fun process(src: ByteArray, len: Int): ByteArray {
            if (len <= 0) return ByteArray(0)
            val data = if (odd >= 0) {
                ByteArray(len + 1).also {
                    it[0] = odd.toByte()
                    System.arraycopy(src, 0, it, 1, len)
                }.also { odd = -1 }
            } else src.copyOf(len)

            val evenLength = data.size and -2
            if (evenLength < data.size) odd = data[data.lastIndex].toInt() and 0xff
            val samples = evenLength / 2
            val out = ByteArray(samples * 6)
            var outOffset = 0
            for (i in 0 until samples) {
                val cur = ((data[2 * i + 1].toInt() shl 8) or (data[2 * i].toInt() and 0xff)).toShort().toInt()
                // 3 倍アップサンプリングは入力サンプルの間に 2 個のゼロを挿入し、FIRで補間する。
                for (phase in 0..2) {
                    val filtered = filter(if (phase == 0) cur.toDouble() else 0.0)
                    val sample = filtered.roundToInt().coerceIn(-32768, 32767)
                    out[outOffset++] = (sample and 0xff).toByte()
                    out[outOffset++] = ((sample shr 8) and 0xff).toByte()
                }
            }
            return out
        }

        private fun filter(value: Double): Double {
            for (i in history.lastIndex downTo 1) history[i] = history[i - 1]
            history[0] = value
            var sum = 0.0
            for (i in history.indices) sum += coeffs[i] * history[i]
            return sum
        }

        companion object {
            private const val FILTER_TAPS = 31 // 3相の中心と対称性を確保

            private fun makeCoefficients(): DoubleArray {
                val center = (FILTER_TAPS - 1) / 2.0
                val coeffs = DoubleArray(FILTER_TAPS) { i ->
                    val x = (i - center) / 3.0
                    val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                    val phase = 2.0 * PI * i / (FILTER_TAPS - 1)
                    val blackman = 0.42 - 0.5 * cos(phase) + 0.08 * cos(2.0 * phase)
                    sinc * blackman
                }
                // 各ポリフェーズの DC ゲインを1に正規化し、一定レベルで音量が揺れないようにする。
                for (phase in 0..2) {
                    var total = 0.0
                    for (i in phase until FILTER_TAPS step 3) total += coeffs[i]
                    if (abs(total) > 1e-9) {
                        for (i in phase until FILTER_TAPS step 3) coeffs[i] /= total
                    }
                }
                return coeffs
            }
        }
    }

    /** 1 回の通話ぶんの出力(MediaStore のファイル + AAC エンコーダ + MP4 マルチプレクサ) */
    private class Session private constructor(
        val resolver: android.content.ContentResolver,
        val uri: Uri,
        val displayName: String,
        val pfd: ParcelFileDescriptor,
        val codec: MediaCodec,
        val muxer: MediaMuxer,
    ) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var muxerStarted = false
        private var samples = 0L
        private var finished = false
        val startMs = System.currentTimeMillis()
        @Volatile var feedDrops = 0
        private var encBytes = 0L

        fun feed(pcm: ByteArray, len: Int) {
            var idx = codec.dequeueInputBuffer(10_000)
            var tries = 0
            while (idx < 0 && tries < 5) { // 空きが出るまで少し待つ(捨てると音が途切れる)
                drain(false)
                idx = codec.dequeueInputBuffer(20_000)
                tries++
            }
            if (idx >= 0) {
                val ib = codec.getInputBuffer(idx) ?: return
                ib.clear()
                ib.put(pcm, 0, len)
                val pts = samples * 1_000_000L / RATE
                codec.queueInputBuffer(idx, 0, len, pts, 0)
                samples += len / 2
            } else {
                feedDrops++
            }
            drain(false)
        }

        private fun drain(untilEos: Boolean) {
            val deadline = System.currentTimeMillis() + 3000
            while (true) {
                val i = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!untilEos || System.currentTimeMillis() > deadline) return
                    }
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    i >= 0 -> {
                        val ob = codec.getOutputBuffer(i)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (ob != null && info.size > 0 && muxerStarted) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                            encBytes += info.size
                        }
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        @Synchronized
        fun finish(): Boolean {
            if (finished) return false
            finished = true
            try {
                val idx = codec.dequeueInputBuffer(50_000)
                if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, samples * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            } catch (e: Throwable) {
                Log.w(TAG, "[rec] 終了処理(エンコーダ): ${e.message}")
            }
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
            try { if (muxerStarted) muxer.stop() } catch (_: Throwable) {}
            try { muxer.release() } catch (_: Throwable) {}
            try { pfd.close() } catch (_: Throwable) {}
            val seconds = samples / RATE
            val wall = (System.currentTimeMillis() - startMs) / 1000.0
            Log.i(TAG, "[rec] 完了: 実時間=${"%.1f".format(Locale.US, wall)}秒 録音の長さ=${"%.1f".format(Locale.US, samples.toDouble() / RATE)}秒 " +
                "エンコーダ入力の取りこぼし=${feedDrops}回 AAC=${encBytes}B(${if (wall > 0) (encBytes * 8 / wall / 1000).toInt() else 0}kbps)")
            if (seconds < MIN_SECONDS || !muxerStarted) {
                // 短すぎる(または何も書けなかった)録音は残さない
                try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                Log.i(TAG, "[rec] ${seconds}秒の録音は短いため保存しませんでした")
                return false
            } else {
                try {
                    val v = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                    resolver.update(uri, v, null, null)
                    Log.i(TAG, "[rec] 保存しました: $RELATIVE_DIR/$displayName (${seconds}秒)")
                    return true
                } catch (e: Throwable) {
                    Log.w(TAG, "[rec] 保存の確定に失敗: ${e.message}")
                }
            }
            return false
        }

        companion object {
            fun create(ctx: Context): Session {
                val resolver = ctx.contentResolver
                val name = "call_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.TITLE, name.removeSuffix(".m4a"))
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR)
                    put(MediaStore.Audio.Media.IS_PENDING, 1) // 書き終えるまで他のアプリには見せない
                }
                val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore に作成できません")
                var pfd: ParcelFileDescriptor? = null
                var codec: MediaCodec? = null
                var muxer: MediaMuxer? = null
                try {
                    pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("ファイルを開けません")
                    val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    }
                    codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                    muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    return Session(resolver, uri, name, pfd, codec, muxer)
                } catch (e: Throwable) {
                    try { codec?.release() } catch (_: Throwable) {}
                    try { muxer?.release() } catch (_: Throwable) {}
                    try { pfd?.close() } catch (_: Throwable) {}
                    try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                    throw e
                }
            }
        }
    }
}
