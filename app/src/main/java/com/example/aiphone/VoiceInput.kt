package com.example.aiphone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 通話タブの確認カード用の音声入力(日本語の音声認識)。メインスレッドから使うこと。
 * 認識結果は onText に渡す(途中経過は isFinal=false)。
 */
class VoiceInput(
    private val context: Context,
    private val onText: (text: String, isFinal: Boolean) -> Unit,
    private val onState: (String) -> Unit,
    /** 成功・失敗・中止のどれでも、聞き取りが終わったら呼ぶ */
    private val onEnd: () -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var priv: PrivMicTranscriber? = null
    private var stdListening = false
    val listening: Boolean get() = stdListening || priv?.listening == true

    /** 通話中(通話アプリがマイクを使っている間)か。このときは通常の音声認識が無音化される */
    private fun inCall(): Boolean = try {
        val m = context.getSystemService(android.media.AudioManager::class.java).mode
        m == android.media.AudioManager.MODE_IN_COMMUNICATION || m == android.media.AudioManager.MODE_IN_CALL
    } catch (_: Throwable) { false }

    /** 特権マイクで録る方式を始める。使えない(ブリッジ未接続)なら false */
    private fun startPriv(): Boolean {
        val b = BridgeConnector.bridge?.takeIf { it.asBinder().isBinderAlive } ?: return false
        priv = PrivMicTranscriber(context, onText, onState, onEnd).also { it.start(b) }
        return true
    }

    fun start() {
        if (listening) return
        // 通話中は、通話アプリのマイク使用で通常の音声認識が無音化されるため、最初から特権マイクを使う
        if (inCall() && startPriv()) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onState("この端末では音声認識を使えません")
            onEnd()
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { onState("🎤 聞き取り中… 話してください") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { onState("認識中…") }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                firstText(partialResults)?.let { onText(it, false) }
            }

            override fun onResults(results: Bundle?) {
                val t = firstText(results)
                finish()
                if (t != null) {
                    onText(t, true)
                    onState("認識しました。内容を確認して「送信」を押してください")
                } else {
                    onState("認識できませんでした。もう一度お試しください")
                }
            }

            override fun onError(error: Int) {
                finish(notifyEnd = false)
                // マイクが取れない/無音のときは、特権マイクの方式でやり直す
                if ((error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && startPriv()) {
                    onState("通常のマイクが使えないため、通話中でも使えるマイクで聞き取ります…")
                    return
                }
                onEnd()
                onState(errorText(error))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        stdListening = true
        r.startListening(intent)
    }

    /** 話し終えたことにして、ここまでの認識結果を返させる */
    fun stop() {
        try { recognizer?.stopListening() } catch (_: Throwable) {}
        priv?.stop()
    }

    /** 結果を捨てて中止 */
    fun cancel() {
        priv?.cancel()
        priv = null
        finish()
    }

    private fun finish(notifyEnd: Boolean = true) {
        val r = recognizer
        recognizer = null
        val was = stdListening
        stdListening = false
        try { r?.destroy() } catch (_: Throwable) {}
        if (was && notifyEnd) onEnd()
    }

    private fun firstText(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "聞き取れませんでした。もう一度お試しください"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "音声が検出されませんでした(通話中は、通話アプリがマイクを使っていて音が届かないことがあります)"
        SpeechRecognizer.ERROR_AUDIO -> "マイクを使えません(通話アプリが使用中の可能性があります)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイクの権限がありません"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークエラーです"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "音声認識が使用中です。少し待ってからお試しください"
        else -> "音声認識エラー($code)"
    }
}
