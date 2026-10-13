package com.example.aiphone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 設定したプロンプト・ボイスで Gemini Live がどう話すかを、通話なしで端末スピーカーから試聴する。
 * 「相手の第一声」として testLine を送り、AI の返答（音声＋文字）を再生・表示する。
 */
object VoiceTester {
    private const val TAG = "RLAudio"
    private const val RATE = 24000

    val running = AtomicBoolean(false)
    private var session: GeminiLiveSession? = null
    private var track: AudioTrack? = null
    private var playThread: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val END = ByteArray(0)

    /**
     * @param onText   AI の返答テキスト（逐次）
     * @param onState  状態メッセージ（接続中/再生中/完了/エラー）
     */
    @Synchronized
    fun start(
        apiKey: String, prompt: String, voice: String, testLine: String,
        onText: (String) -> Unit, onState: (String) -> Unit, onFinished: () -> Unit,
        languageCode: String = "ja"
    ) {
        if (running.get()) return
        running.set(true)
        queue.clear()
        onState("接続中…")

        val tr = try {
            val bufMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(maxOf(bufMin, RATE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().also { it.play() }
        } catch (e: Throwable) {
            running.set(false)
            onState("再生の初期化に失敗: ${e.message}")
            onFinished()
            return
        }
        track = tr

        playThread = Thread({
            try {
                while (true) {
                    val b = queue.take()
                    if (b === END) break
                    var off = 0
                    while (off < b.size) {
                        val w = tr.write(b, off, b.size - off)
                        if (w <= 0) break
                        off += w
                    }
                }
                // 再生残りを待ってから止める
                Thread.sleep(600)
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                Log.w(TAG, "voice test play", e)
            } finally {
                try { tr.stop(); tr.release() } catch (_: Throwable) {}
            }
        }, "voice-test-play").also { it.start() }

        var got = false
        val finish = AtomicBoolean(false)
        fun end(msg: String) {
            if (!finish.compareAndSet(false, true)) return
            queue.offer(END)
            Thread {
                try { Thread.sleep(300) } catch (_: Throwable) {}
                synchronized(this) {
                    try { session?.close() } catch (_: Throwable) {}
                    session = null
                    running.set(false)
                }
                onState(msg)
                onFinished()
            }.start()
        }

        lateinit var s: GeminiLiveSession
        s = GeminiLiveSession(
            apiKey = apiKey,
            onAudioOut = { pcm ->
                if (!got) { got = true; onState("再生中…") }
                queue.offer(pcm)
            },
            onLog = { Log.i(TAG, "[voice-test] $it") },
            onError = { end("エラー: $it") },
            systemInstruction = prompt,
            voiceName = voice,
            languageCode = languageCode,
            onTranscript = { role, t -> if (role == ChatStore.AI) onText(t) },
            onTurnComplete = { end("完了（$voice）") },
            onReady = {
                onState("相手の第一声を送信: $testLine")
                s.sendText(testLine)
            }
        )
        session = s
        s.connect()

        // 保険: 30 秒で打ち切り
        Thread({
            try { Thread.sleep(30000) } catch (_: Throwable) {}
            if (running.get()) end(if (got) "完了（時間切れ）" else "応答がありませんでした（時間切れ）")
        }, "voice-test-timeout").start()
    }

    @Synchronized
    fun stop() {
        if (!running.get()) return
        queue.offer(END)
        try { session?.close() } catch (_: Throwable) {}
        session = null
        running.set(false)
    }
}
