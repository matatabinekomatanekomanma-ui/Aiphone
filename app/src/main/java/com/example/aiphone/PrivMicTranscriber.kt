package com.example.aiphone

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.FileInputStream
import kotlin.math.abs

/**
 * 通話中でも使える音声入力(端末の音声認識を使う。Gemini などの外部 API は使わない)。
 * 通話アプリがマイクを使っていると、SpeechRecognizer が自分でマイクを開いても OS に無音化される。
 * そこで priv-kit(shell)側のマイクで録音し、その PCM を SpeechRecognizer の音声入力(EXTRA_AUDIO_SOURCE、Android 13+)へ
 * パイプで流し込む。認識は端末の音声認識サービスが行う。メインスレッドから使うこと。
 */
class PrivMicTranscriber(
    private val context: Context,
    private val onText: (text: String, isFinal: Boolean) -> Unit,
    private val onState: (String) -> Unit,
    private val onEnd: () -> Unit,
) {
    companion object {
        private const val TAG = "RLAudio"
        private const val RATE = 16000
        private const val MAX_BYTES = RATE * 2 * 30
        private const val SPEECH_PEAK = 1200
        private const val QUIET_PEAK = 500
        private const val QUIET_END_MS = 1500L
        private const val NO_SPEECH_MS = 8000L
        private const val RESULT_WAIT_MS = 12000L
        /** 通話中は VOICE_COMMUNICATION(7) が無音化される端末があるため、通常マイク(101 = MIC・非sensitive)を先に試す */
        private val SOURCES = intArrayOf(101, 7, 107, 106)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recogPipe: Array<ParcelFileDescriptor>? = null

    @Volatile var listening = false
        private set
    @Volatile private var stopRequested = false
    @Volatile private var finished = false
    private var gotResult = false

    fun start(bridge: IAudioBridge) {
        if (listening) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onState("この端末(Android 12 以前)では、通話中の音声入力を使えません")
            onEnd()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onState("この端末では音声認識を使えません")
            onEnd()
            return
        }
        listening = true
        stopRequested = false
        finished = false
        gotResult = false
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: Throwable) {
            Log.e(TAG, "[voice] パイプ作成失敗", e)
            finish("音声入力を開始できませんでした")
            return
        }
        recogPipe = pipe
        startRecognizer(pipe[0])
        Thread({ pump(bridge, pipe[1]) }, "priv-mic-pump").start()
    }

    /** 話し終えたことにして、ここまでの録音を認識させる */
    fun stop() { stopRequested = true }

    /** 結果を捨てて中止 */
    fun cancel() {
        stopRequested = true
        finished = true
        main.post { cleanup() }
        listening = false
    }

    // ---------- 認識(メインスレッド) ----------

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startRecognizer(readEnd: ParcelFileDescriptor) {
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (!finished) onState("認識中…") }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (finished) return
                firstText(partialResults)?.let { onText(it, false) }
            }

            override fun onResults(results: Bundle?) {
                if (finished) return
                gotResult = true
                val t = firstText(results)
                if (t != null) {
                    onText(t, true)
                    finish("認識しました。内容を確認して「送信」を押してください")
                } else {
                    finish("認識できませんでした。もう一度お試しください")
                }
            }

            override fun onError(error: Int) {
                if (finished) return
                Log.w(TAG, "[voice] 認識エラー code=$error")
                finish(errorText(error))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // マイクを開かず、こちらが渡す PCM を認識させる(Android 13+)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
        }
        r.startListening(intent)
        onState("🎤 聞き取り中… 話してください(通話中でも使えるマイクです)")
    }

    private fun finish(message: String?) {
        if (finished) return
        finished = true
        if (message != null) onState(message)
        cleanup()
        listening = false
        onEnd()
    }

    private fun cleanup() {
        stopRequested = true
        val r = recognizer
        recognizer = null
        try { r?.cancel() } catch (_: Throwable) {}
        try { r?.destroy() } catch (_: Throwable) {}
        recogPipe?.forEach { try { it.close() } catch (_: Throwable) {} }
        recogPipe = null
    }

    // ---------- 録音(別スレッド): 特権マイク → パイプ ----------

    /** SOURCES の from 番目以降で、録音を開始できた最初の音源を開く。(音源の添字, 読み取り側) */
    private fun openSource(bridge: IAudioBridge, from: Int): Pair<Int, ParcelFileDescriptor>? {
        for (idx in from until SOURCES.size) {
            val src = SOURCES[idx]
            val p = ParcelFileDescriptor.createPipe()
            val res = try { bridge.startMicCapture(src, p[1]) } catch (e: Throwable) { "NG(exception): ${e.message}" }
            try { p[1].close() } catch (_: Throwable) {}
            Log.i(TAG, "[voice] 特権マイク source=$src: ${res.take(160)}")
            if (res.startsWith("OK")) return idx to p[0]
            try { p[0].close() } catch (_: Throwable) {}
        }
        return null
    }

    private fun pump(bridge: IAudioBridge, sink: ParcelFileDescriptor) {
        var micFd: ParcelFileDescriptor? = null
        var out: ParcelFileDescriptor.AutoCloseOutputStream? = null
        var failure: String? = null
        try {
            var opened = openSource(bridge, 0)
            if (opened == null) {
                failure = "マイクを使えません(特権マイクも取得できませんでした)"
                return
            }
            var srcIdx = opened.first
            micFd = opened.second
            var ins = FileInputStream(micFd.fileDescriptor)
            out = ParcelFileDescriptor.AutoCloseOutputStream(sink)

            val buf = ByteArray(3200) // 100ms
            var total = 0
            var heard = false
            var quietMs = 0L
            val t0 = System.currentTimeMillis()
            var openedAt = t0
            var peakAll = 0
            var peakSinceOpen = 0
            while (!stopRequested && !finished && total < MAX_BYTES) {
                val n = ins.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val pk = peak(buf, n)
                peakAll = maxOf(peakAll, pk)
                peakSinceOpen = maxOf(peakSinceOpen, pk)
                val now = System.currentTimeMillis()
                // 開いてから 2.5 秒たっても完全な無音なら、無音化されている音源とみなして次の音源へ切り替える
                if (!heard && peakSinceOpen <= 10 && now - openedAt > 2500 && srcIdx + 1 < SOURCES.size) {
                    Log.i(TAG, "[voice] source=${SOURCES[srcIdx]} は無音(peak=$peakSinceOpen)。次の音源へ切り替えます")
                    try { micFd?.close() } catch (_: Throwable) {}
                    try { bridge.stopMicCapture() } catch (_: Throwable) {}
                    opened = openSource(bridge, srcIdx + 1)
                    if (opened == null) { micFd = null; failure = "マイクを使えません(使える音源がありません)"; break }
                    srcIdx = opened.first
                    val next = opened.second
                    micFd = next
                    ins = FileInputStream(next.fileDescriptor)
                    peakSinceOpen = 0
                    openedAt = System.currentTimeMillis()
                    continue
                }
                out.write(buf, 0, n)
                total += n
                val chunkMs = n / 2L * 1000L / RATE
                if (pk >= SPEECH_PEAK) { heard = true; quietMs = 0 }
                else if (heard && pk < QUIET_PEAK) { quietMs += chunkMs; if (quietMs >= QUIET_END_MS) break }
                if (!heard && now - t0 > NO_SPEECH_MS) break
            }
            Log.i(TAG, "[voice] 録音終了 source=${SOURCES.getOrNull(srcIdx)} bytes=$total peak=$peakAll heard=$heard")
            if (failure == null && !finished && peakAll < 300) {
                failure = "音声が検出されませんでした(マイクの音量: $peakAll)。Bluetooth イヤホン接続中は、イヤホンのマイクに向かって話してください"
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[voice] 録音中に例外", e)
            if (!finished) failure = "音声入力エラー: ${e.javaClass.simpleName}"
        } finally {
            try { micFd?.close() } catch (_: Throwable) {}
            try { bridge.stopMicCapture() } catch (_: Throwable) {}
            // 書き込み側を閉じる = 音声の終わり。認識サービスが最終結果を返す
            try { out?.close() } catch (_: Throwable) { try { sink.close() } catch (_: Throwable) {} }
            if (out == null) try { sink.close() } catch (_: Throwable) {}
            val f = failure
            if (f != null) {
                main.post { finish(f) }
            } else {
                // 認識結果が返らないときの保険
                main.postDelayed({ if (!finished && !gotResult) finish("認識できませんでした。もう一度お試しください") }, RESULT_WAIT_MS)
            }
        }
    }

    private fun firstText(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "聞き取れませんでした。もう一度お試しください"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声が検出されませんでした。もう一度お試しください"
        SpeechRecognizer.ERROR_AUDIO -> "音声認識がマイク入力を受け付けませんでした(この端末の音声認識は外部の音声入力に未対応の可能性があります)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイクの権限がありません"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークエラーです(音声認識がオンライン認識を使っています)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "音声認識が使用中です。少し待ってからお試しください"
        else -> "音声認識エラー($code)"
    }

    private fun peak(b: ByteArray, n: Int): Int {
        var m = 0
        var i = 0
        while (i + 1 < n) {
            m = maxOf(m, abs(((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toInt()))
            i += 2
        }
        return m
    }
}
