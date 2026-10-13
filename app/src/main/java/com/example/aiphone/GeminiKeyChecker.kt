package com.example.aiphone

import android.content.SharedPreferences
import androidx.core.content.edit
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Gemini Live に実際に WebSocket 接続し、setup が通る (setupComplete) かで API キーを検証する。
 * 音声は送らないので課金はほぼ発生しない。
 */
object GeminiKeyChecker {
    // 検証対象は通話本番と同じモデル・同じエンドポイント
    private const val MODEL = GeminiLiveSession.MODEL
    private const val WS_BASE = GeminiLiveSession.WS_BASE
    private const val PREF_VERIFIED = "gemini_key_verified"

    /**
     * コピペ由来のゴミ(改行・全角スペース・ゼロ幅文字・「key=」等の付随文字)を除いたキーを返す。
     * Gemini の API キーは半角英数と _ - . のみ（新形式の AQ.xxx は '.' を含む）。
     */
    fun clean(raw: String): String {
        val normalized = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
        return normalized.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' }
    }

    /** ok: true=有効 / false=無効 / null=ネットワーク等で判定不能 */
    data class Result(val ok: Boolean?, val message: String)

    /** 検証済みのキーと現在のキーが一致していれば有効扱い。旧版の平文ハッシュ欄は見つけた時点で移行する。 */
    fun isVerified(prefs: SharedPreferences, key: String): Boolean {
        if (key.isBlank()) return false
        val stored = prefs.getString(PREF_VERIFIED, "").orEmpty()
        if (stored == SecretStore.fingerprint(key)) return true
        if (stored == key) {
            // 旧バージョンはキーそのものを保存していたため、認証済みの判定を保ちつつハッシュへ移行。
            saveVerified(prefs, key)
            return true
        }
        return false
    }

    /** API キーを含む可能性がある例外/サーバー文を UI や Logcat に出す前に除去する。 */
    private fun redactKey(message: String, apiKey: String): String =
        if (apiKey.isBlank()) message else message.replace(apiKey, "[REDACTED_API_KEY]")

    fun saveVerified(prefs: SharedPreferences, key: String?) {
        // キーそのものは残さず、ハッシュだけ保存する
        prefs.edit { putString(PREF_VERIFIED, if (key.isNullOrEmpty()) "" else SecretStore.fingerprint(key)) }
    }

    /**
     * Live API 接続用のリクエスト。
     * 新形式(AQ.xxx)は公式リファレンスどおり x-goog-api-key ヘッダで渡す（?key= との二重指定は避ける）。
     * 従来形式(AIza...)は従来どおり ?key= で渡す。
     */
    fun liveRequest(apiKey: String): Request {
        val b = Request.Builder()
        return if (apiKey.startsWith("AQ.")) {
            b.url(WS_BASE).header("x-goog-api-key", apiKey).build()
        } else {
            b.url("$WS_BASE?key=$apiKey").build()
        }
    }

    /** ブロッキング。必ずバックグラウンドスレッドから呼ぶ。 */
    fun check(apiKey: String, timeoutMs: Long = 12000): Result {
        if (apiKey.isBlank()) return Result(false, "キーが未入力です")
        // 共有クライアントの接続プール/スレッドを使い回す（shutdown はしない）
        val client = Http.client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result>()
        fun finish(r: Result) { if (result.compareAndSet(null, r)) latch.countDown() }

        val ws: WebSocket = try { client.newWebSocket(
            liveRequest(apiKey),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val setup = JSONObject().put("setup", JSONObject().apply {
                        put("model", "models/$MODEL")
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", JSONArray().put("AUDIO"))
                        })
                    })
                    webSocket.send(setup.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when {
                        text.contains("setupComplete") -> finish(Result(true, "接続OK"))
                        text.contains("\"error\"") -> finish(Result(false, "エラー応答: ${redactKey(text.take(300), apiKey)}"))
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // キー不正は 1007/1008 等 + reason で閉じられる
                    finish(Result(false, "拒否されました ($code ${redactKey(reason.take(300), apiKey)})"))
                    webSocket.close(1000, null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    if (code == 400 || code == 401 || code == 403) {
                        finish(Result(false, "キーが無効です (HTTP $code)"))
                    } else {
                        finish(Result(null, "接続できず判定不能: ${t.javaClass.simpleName} ${redactKey(t.message.orEmpty(), apiKey)} ${response?.code ?: ""}".trim()))
                    }
                }
            }
        ) } catch (e: Exception) {
            return Result(false, "接続を開始できません: ${e.javaClass.simpleName} ${redactKey(e.message.orEmpty(), apiKey)}".trim())
        }
        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        try { ws.close(1000, "check done") } catch (_: Throwable) {}
        return if (done) result.get() else Result(null, "タイムアウト（応答なし）")
    }
}
