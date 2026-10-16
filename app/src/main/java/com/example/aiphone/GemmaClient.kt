package com.example.aiphone

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemma 4（Gemini API generateContent）クライアント。
 * 500 / 503 / 429 / 通信失敗は [maxAttempts] 回まで自動リトライする（設定で変更可）。
 */
object GemmaClient {
    private const val TAG = "RLAudio"
    const val MODEL = "gemma-4-31b-it"
    private const val BASE =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    const val DEFAULT_MAX_ATTEMPTS = 5
    const val MIN_MAX_ATTEMPTS = 1
    const val MAX_MAX_ATTEMPTS = 10

    /** 設定で選べる思考レベル(表示名と API 値は同じ並び) */
    val THINKING_LEVELS = listOf("low", "medium", "high")
    val THINKING_LABELS = listOf("低", "中", "高")
    const val DEFAULT_THINKING = "medium"

    fun thinkingFromPrefs(prefs: android.content.SharedPreferences): String =
        prefs.getString(AiCallService.KEY_GEMMA_THINKING, DEFAULT_THINKING)
            ?.takeIf { it in THINKING_LEVELS } ?: DEFAULT_THINKING

    fun maxAttemptsFromPrefs(prefs: android.content.SharedPreferences): Int {
        val v = prefs.getInt(AiCallService.KEY_GEMMA_MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS)
        return v.coerceIn(MIN_MAX_ATTEMPTS, MAX_MAX_ATTEMPTS)
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val http by lazy {
        Http.client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
    }

    data class Result(
        val ok: Boolean,
        val text: String = "",
        val error: String = "",
        val sources: List<Pair<String, String>> = emptyList(),
        val httpCode: Int = 0,
        val attempts: Int = 1,
    )

    fun generate(
        apiKey: String,
        systemInstruction: String,
        contents: JSONArray,
        enableSearch: Boolean,
        jsonMode: Boolean = false,
        thinkingLevel: String = DEFAULT_THINKING,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): Result {
        if (apiKey.isBlank()) return Result(false, error = "APIキーが未設定です")
        if (contents.length() == 0) return Result(false, error = "会話が空です")

        val attemptsLimit = maxAttempts.coerceIn(MIN_MAX_ATTEMPTS, MAX_MAX_ATTEMPTS)
        var last = Result(false, error = "不明なエラー")
        // thinkingLevel を受け付けない場合は、思考レベルなしでやり直す
        var level: String? = thinkingLevel.takeIf { it in THINKING_LEVELS } ?: DEFAULT_THINKING
        for (attempt in 1..attemptsLimit) {
            last = generateOnce(apiKey, systemInstruction, contents, enableSearch, jsonMode, attempt, level)
            if (last.ok) return last.copy(attempts = attempt)
            if (last.httpCode == 400 && level != null && last.error.contains("think", ignoreCase = true)) {
                Log.w(TAG, "Gemma: thinkingLevel=$level が使えないため、指定なしで再試行")
                level = null
                last = generateOnce(apiKey, systemInstruction, contents, enableSearch, jsonMode, attempt, null)
                if (last.ok) return last.copy(attempts = attempt)
            }
            if (!isRetryable(last.httpCode) || attempt == attemptsLimit) {
                return last.copy(
                    attempts = attempt,
                    error = friendlyError(last.httpCode, last.error, attempt),
                )
            }
            val waitMs = retryDelayMs(attempt)
            Log.i(TAG, "Gemma リトライ ${attempt}/${attemptsLimit} → ${waitMs}ms 待機 (HTTP ${last.httpCode})")
            try {
                Thread.sleep(waitMs)
            } catch (_: InterruptedException) {
                return last.copy(
                    attempts = attempt,
                    error = friendlyError(last.httpCode, last.error, attempt),
                )
            }
        }
        return last
    }

    private fun isRetryable(code: Int): Boolean =
        code == 500 || code == 503 || code == 429 || code == 0

    private fun retryDelayMs(failedAttempt: Int): Long = when (failedAttempt) {
        1 -> 1500L
        2 -> 3000L
        3 -> 5000L
        else -> 8000L
    }

    private fun friendlyError(code: Int, raw: String, attempts: Int): String {
        val base = when (code) {
            503 -> "Gemma が混み合っています（サーバー混雑 503）。\nしばらく待ってから、もう一度「送信」または「まとめる」を押してください。"
            500 -> "Gemma 側で一時的なエラーが起きました（内部エラー 500）。\n時間をおいて再試行してください。"
            429 -> "リクエストが多すぎます（レート制限 429）。\nしばらく待ってから再試行してください。"
            0 -> if (raw.isNotBlank()) raw else "通信に失敗しました。ネットワークを確認してください。"
            else -> if (raw.isNotBlank()) {
                if (raw.startsWith("Gemma") || raw.contains("エラー")) raw
                else "Gemmaエラー(HTTP $code): $raw"
            } else "Gemmaエラー(HTTP $code)"
        }
        return if (attempts > 1) "$base\n（${attempts} 回試しました）" else base
    }

    private fun generateOnce(
        apiKey: String,
        systemInstruction: String,
        contents: JSONArray,
        enableSearch: Boolean,
        jsonMode: Boolean,
        attempt: Int,
        thinkingLevel: String?,
    ): Result {
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", systemInstruction))
            ))
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 1.0)
                put("topP", 0.95)
                put("topK", 64)
                put("maxOutputTokens", if (jsonMode) 4096 else 8192)
                if (thinkingLevel != null) put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))
                if (jsonMode) put("responseMimeType", "application/json")
            })
            if (enableSearch && !jsonMode) {
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
        }

        val reqBuilder = Request.Builder()
            .url(BASE)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)

        return try {
            http.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message")
                    } catch (_: Throwable) { null }
                    Log.w(TAG, "Gemma HTTP ${resp.code} (試行$attempt): ${raw.take(400)}")
                    return Result(
                        ok = false,
                        error = msg ?: raw.take(160),
                        httpCode = resp.code,
                        attempts = attempt,
                    )
                }
                parseResponse(raw).copy(httpCode = 200, attempts = attempt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemma request failed (試行$attempt)", e)
            Result(
                ok = false,
                error = "通信失敗: ${e.javaClass.simpleName}: ${e.message}",
                httpCode = 0,
                attempts = attempt,
            )
        }
    }

    private fun parseResponse(raw: String): Result {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val block = root.optJSONObject("promptFeedback")?.optString("blockReason")
            return Result(false, error = "応答がありません" + if (!block.isNullOrBlank()) " ($block)" else "")
        }
        val cand = candidates.getJSONObject(0)
        val content = cand.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.has("thought") && p.optBoolean("thought", false)) continue
                val t = p.optString("text", "")
                if (t.isNotEmpty()) sb.append(t)
            }
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) {
            val reason = cand.optString("finishReason", "")
            return Result(false, error = "空の応答 (finishReason=$reason)")
        }

        val sources = ArrayList<Pair<String, String>>()
        val gm = cand.optJSONObject("groundingMetadata")
        gm?.optJSONArray("groundingChunks")?.let { chunks ->
            for (i in 0 until chunks.length()) {
                val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                val title = web.optString("title", "").ifBlank { "出典" }
                val uri = web.optString("uri", "")
                if (uri.isNotBlank()) sources.add(title to uri)
            }
        }
        return Result(true, text = text, sources = sources)
    }

    fun defaultChatPrompt(languageCode: String): String = PromptLocalization.gemmaChatDefault(languageCode)

    fun defaultSummaryPrompt(languageCode: String): String = PromptLocalization.gemmaSummaryDefault(languageCode)

    fun isDefaultChatPrompt(prompt: String): Boolean {
        val candidate = prompt.trim()
        return listOf("ja", "en", "zh-CN", "zh-TW", "ko", "es")
            .any { candidate == defaultChatPrompt(it).trim() }
    }

    fun isDefaultSummaryPrompt(prompt: String): Boolean {
        val candidate = prompt.trim()
        return listOf("ja", "en", "zh-CN", "zh-TW", "ko", "es")
            .any { candidate == defaultSummaryPrompt(it).trim() } ||
            PromptLocalization.isLegacyGemmaSummaryDefault(candidate)
    }

    /**
     * Editable base prompt + app-generated privacy guard. The guard is kept separate so
     * editing the prompt cannot accidentally remove the field-values protection.
     */
    fun chatSystemInstruction(
        registeredPiiLabels: List<String>,
        customPrompt: String?,
        languageCode: String,
    ): String {
        val base = customPrompt?.takeIf { it.isNotBlank() && !isDefaultChatPrompt(it) } ?: defaultChatPrompt(languageCode)
        val localizedLabels = PromptLocalization.personalInfoLabels(languageCode, registeredPiiLabels)
        return PromptLocalization.gemmaChatLanguageRule(languageCode) + base.trim() +
            PromptLocalization.mandatoryGemmaPrivacyRule(localizedLabels, languageCode)
    }

    /**
     * Editable summary instructions + mandatory JSON schema constraints needed by the app parser.
     */
    fun summarizeSystemInstruction(customPrompt: String?, languageCode: String): String {
        val base = customPrompt?.takeIf { it.isNotBlank() && !isDefaultSummaryPrompt(it) } ?: defaultSummaryPrompt(languageCode)
        return base.trim() + PromptLocalization.mandatorySummaryFormatRule(languageCode)
    }

}
