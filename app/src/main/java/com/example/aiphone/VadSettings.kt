package com.example.aiphone

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 音声検出(VAD)の設定。setup の realtimeInputConfig.automaticActivityDetection に入る。
 * 感度: 0=既定(サーバー任せ) / 1=低 / 2=高
 */
data class VadSettings(
    val startSensitivity: Int = DEFAULT_START,
    val endSensitivity: Int = DEFAULT_END,
    val silenceMs: Int = DEFAULT_SILENCE_MS,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        sensitivityName("START", startSensitivity)?.let { put("startOfSpeechSensitivity", it) }
        sensitivityName("END", endSensitivity)?.let { put("endOfSpeechSensitivity", it) }
        put("silenceDurationMs", silenceMs.coerceIn(MIN_SILENCE_MS, MAX_SILENCE_MS))
    }

    private fun sensitivityName(kind: String, v: Int): String? = when (v) {
        1 -> "${kind}_SENSITIVITY_LOW"
        2 -> "${kind}_SENSITIVITY_HIGH"
        else -> null
    }

    companion object {
        const val DEFAULT_START = 1          // 低: 雑音・機械音声の短い音で割り込まれにくくする
        const val DEFAULT_END = 0            // 既定
        const val DEFAULT_SILENCE_MS = 600   // 推奨 500〜800
        const val MIN_SILENCE_MS = 100
        const val MAX_SILENCE_MS = 3000

        val SENSITIVITY_LABELS = listOf("既定", "低", "高")

        fun load(prefs: SharedPreferences) = VadSettings(
            startSensitivity = prefs.getInt(AiCallService.KEY_VAD_START, DEFAULT_START).coerceIn(0, 2),
            endSensitivity = prefs.getInt(AiCallService.KEY_VAD_END, DEFAULT_END).coerceIn(0, 2),
            silenceMs = prefs.getInt(AiCallService.KEY_VAD_SILENCE_MS, DEFAULT_SILENCE_MS)
                .coerceIn(MIN_SILENCE_MS, MAX_SILENCE_MS),
        )
    }
}
