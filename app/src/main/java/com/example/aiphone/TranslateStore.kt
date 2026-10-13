package com.example.aiphone

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 翻訳機能の設定(言語・元の声)、セット状態、画面に出す文字起こしログ。
 * セット中 = 「次の通話で翻訳を自動開始する」状態。通話終了で解除される(準備タブと同じ)。
 */
object TranslateStore {
    const val KEY_MY = "translate_my_lang"
    const val KEY_THEIR = "translate_their_lang"
    const val KEY_MUTE_ORIGINAL = "translate_mute_original"
    /** 相手の元の声も一緒に聞こえる（既定 false → 消す） */
    const val KEY_HEAR_ORIGINAL = "translate_hear_original"
    /** 翻訳先言語のエコー（既定 false） */
    const val KEY_ECHO_TARGET = "translate_echo_target"
    const val KEY_ARMED = "translate_armed"
    const val KEY_INCOMING = "translate_incoming"
    const val KEY_OUTGOING = "translate_outgoing"
    const val KEY_MONITOR_OUT = "translate_monitor_out"
    const val DEFAULT_MY = "ja"
    const val DEFAULT_THEIR = TranslateLangs.AUTO
    private const val KEY_MIGRATED = "translate_lang_off_migrated"
    private const val KEY_ARM_EXPIRES_AT = "translate_arm_expires_at"
    private const val ARM_TIMEOUT_MS = 5 * 60 * 1000L

    @Volatile private var prefs: SharedPreferences? = null

    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed

    fun init(sp: SharedPreferences) {
        prefs = sp
        migrate(sp)
        val wasArmed = sp.getBoolean(KEY_ARMED, false)
        val expires = sp.getLong(KEY_ARM_EXPIRES_AT, 0L)
        _armed.value = wasArmed && expires > System.currentTimeMillis()
        if (wasArmed && !_armed.value) sp.edit { putBoolean(KEY_ARMED, false); remove(KEY_ARM_EXPIRES_AT) }
        if (_armed.value) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (_armed.value && (prefs?.getLong(KEY_ARM_EXPIRES_AT, 0L) ?: 0L) <= System.currentTimeMillis()) setArmed(false)
            }, (expires - System.currentTimeMillis()).coerceAtLeast(0L))
        }
    }

    fun isArmed(): Boolean = _armed.value

    fun setArmed(v: Boolean) {
        if (v) {
            // 翻訳タブをセットしたら、準備タブの通話用件セットを解除する。
            CallBriefStore.clearActive()
            val expires = System.currentTimeMillis() + ARM_TIMEOUT_MS
            prefs?.edit { putBoolean(KEY_ARMED, true); putLong(KEY_ARM_EXPIRES_AT, expires) }
            _armed.value = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val current = prefs?.getLong(KEY_ARM_EXPIRES_AT, 0L) ?: 0L
                if (_armed.value && current == expires && System.currentTimeMillis() >= current) setArmed(false)
            }, ARM_TIMEOUT_MS)
        } else {
            prefs?.edit { putBoolean(KEY_ARMED, false); remove(KEY_ARM_EXPIRES_AT) }
            _armed.value = false
        }
        AiCallService.refreshBubble()
    }

    /**
     * 旧版は「方向」をチェックボックス(KEY_INCOMING / KEY_OUTGOING)で持っていた。
     * 今は言語の選択肢「翻訳しない」で表すので、一度だけ変換する。
     */
    private fun migrate(sp: SharedPreferences) {
        if (sp.getBoolean(KEY_MIGRATED, false)) return
        val existing = sp.contains(KEY_MY) || sp.contains(KEY_THEIR) || sp.contains(KEY_INCOMING) || sp.contains(KEY_OUTGOING)
        sp.edit {
            putBoolean(KEY_MIGRATED, true)
            if (existing) {
                if (!sp.getBoolean(KEY_INCOMING, true)) putString(KEY_MY, TranslateLangs.OFF)
                if (!sp.getBoolean(KEY_OUTGOING, false)) putString(KEY_THEIR, TranslateLangs.OFF)
            }
            remove(KEY_INCOMING)
            remove(KEY_OUTGOING)
        }
    }

    /** 相手の声を翻訳して「自分が聞く」言語。「翻訳しない」なら OFF */
    fun myLang(sp: SharedPreferences): String {
        val v = sp.getString(KEY_MY, DEFAULT_MY) ?: DEFAULT_MY
        return if (v == TranslateLangs.AUTO) DEFAULT_MY else v
    }

    /** 自分の声を翻訳して「相手へ送る」言語。「翻訳しない」なら OFF、「自動」なら AUTO */
    fun theirLang(sp: SharedPreferences): String = sp.getString(KEY_THEIR, DEFAULT_THEIR) ?: DEFAULT_THEIR

    /** 相手の元の声を消して翻訳だけ聞く(既定 ON・実験) */
    /** 元の声を消す。hearOriginal が優先。旧 KEY_MUTE_ORIGINAL も読む */
    fun muteOriginal(sp: SharedPreferences): Boolean {
        if (sp.contains(KEY_HEAR_ORIGINAL)) return !sp.getBoolean(KEY_HEAR_ORIGINAL, false)
        return sp.getBoolean(KEY_MUTE_ORIGINAL, true)
    }

    fun hearOriginal(sp: SharedPreferences): Boolean = !muteOriginal(sp)

    fun echoTarget(sp: SharedPreferences): Boolean = sp.getBoolean(KEY_ECHO_TARGET, false)

    /** 相手→自分 を翻訳するか(自分の言語が「翻訳しない」以外) */
    fun incoming(sp: SharedPreferences): Boolean = myLang(sp) != TranslateLangs.OFF

    /** 自分→相手 を翻訳するか(相手の言語が「翻訳しない」以外) */
    fun outgoing(sp: SharedPreferences): Boolean = theirLang(sp) != TranslateLangs.OFF

    /** バブルに出す短い表記 */
    fun brief(sp: SharedPreferences): String = "翻訳 ${TranslateLangs.short(myLang(sp))}⇄${TranslateLangs.short(theirLang(sp))}"

    /** 自分→相手 の翻訳音声を、自分にも聞こえるようにする(デバッグ用。スピーカーだと回り込む) */
    fun monitorOut(sp: SharedPreferences): Boolean = sp.getBoolean(KEY_MONITOR_OUT, false)

    // ---------- 画面用ログ(文字起こし。個人情報を含みうるので logcat には出さない) ----------
    private const val MAX_LINES = 300
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    /** key → 「まだ続いている行」の index */
    private val open = HashMap<String, Int>()

    @Synchronized
    fun clear() {
        open.clear()
        _lines.value = emptyList()
    }

    @Synchronized
    fun add(line: String) {
        append(_lines.value.toMutableList(), line)
    }

    /** 文字起こしの断片を、同じ key の行に連結していく(句点か 80 文字で行を閉じる) */
    @Synchronized
    fun fragment(key: String, prefix: String, text: String) {
        if (text.isEmpty()) return
        val list = _lines.value.toMutableList()
        val idx = open[key]
        val i: Int
        if (idx != null && idx < list.size && list[idx].startsWith(prefix)) {
            list[idx] = list[idx] + text
            i = idx
        } else {
            list.add(prefix + text)
            i = list.size - 1
            open[key] = i
        }
        val closeLine = list[i].length > 80 || text.trimEnd().lastOrNull()?.let { it in "。.!?！？" } == true
        if (closeLine) open.remove(key)
        trimAndPublish(list)
    }

    private fun append(list: MutableList<String>, line: String) {
        list.add(line)
        trimAndPublish(list)
    }

    private fun trimAndPublish(list: MutableList<String>) {
        if (list.size > MAX_LINES) {
            val drop = list.size - MAX_LINES + 50
            repeat(drop) { list.removeAt(0) }
            open.clear() // index がずれるので、続き行は閉じる
        }
        _lines.value = list
    }
}
