package com.example.aiphone

import android.content.Context
import java.util.Locale

data class TranslateLang(val code: String, val label: String)

/** Gemini Live Translate が対応する言語(BCP-47)。よく使うものを先頭に並べる。 */
object TranslateLangs {
    /** 「翻訳しない」(その方向は何もしない) */
    const val OFF = "off"
    /** 「自動」(相手の言語を通話音声から検出して、自分の声の翻訳先にする) */
    const val AUTO = "auto"

    private const val RAW = """
ja|日本語
en|英語
zh-Hans|中国語（簡体）
zh-Hant|中国語（繁体）
ko|韓国語
es|スペイン語
fr|フランス語
de|ドイツ語
it|イタリア語
pt-BR|ポルトガル語（ブラジル）
pt-PT|ポルトガル語（ポルトガル）
ru|ロシア語
vi|ベトナム語
th|タイ語
id|インドネシア語
fil|フィリピン語
hi|ヒンディー語
ar|アラビア語
af|アフリカーンス語
ak|Akan
sq|アルバニア語
am|アムハラ語
hy|アルメニア語
az|アゼルバイジャン語
eu|バスク語
be|ベラルーシ語
bn|ベンガル語
bg|ブルガリア語
my|ビルマ語（ミャンマー）
ca|カタルーニャ語
hr|クロアチア語
cs|チェコ語
da|デンマーク語
nl|オランダ語
et|エストニア語
fi|フィンランド語
gl|ガリシア語
ka|ジョージア語
el|ギリシャ語
gu|グジャラート語
ha|ハウサ語
he|ヘブライ語
hu|ハンガリー語
is|アイスランド語
jv|ジャワ語
kn|カンナダ語
kk|カザフ語
km|クメール語
rw|キニヤルワンダ語
lo|ラオ語
lv|ラトビア語
lt|リトアニア語
mk|マケドニア語
ms|マレー語
ml|マラヤーラム語
mr|マラーティー語
mn|モンゴル語
ne|ネパール語
no|ノルウェー語
fa|ペルシャ語
pl|ポーランド語
pa|パンジャブ語
ro|ルーマニア語
sr|セルビア語
sd|シンド語
si|シンハラ語
sk|スロバキア語
sl|スロベニア語
su|スンダ語
sw|スワヒリ語
sv|スウェーデン語
ta|タミル語
te|テルグ語
tr|トルコ語
uk|ウクライナ語
ur|ウルドゥー語
uz|ウズベク語
zu|ズールー語
"""

    val all: List<TranslateLang> = RAW.trim().lines().mapNotNull { line ->
        val i = line.indexOf('|')
        if (i <= 0) null else TranslateLang(line.substring(0, i).trim(), line.substring(i + 1).trim())
    }

    private val offItem = TranslateLang(OFF, "翻訳しない")
    private val autoItem = TranslateLang(AUTO, "自動（相手の言語を検出）")

    /** 「自分の言語」用の選択肢(先頭に「翻訳しない」) */
    val forMy: List<TranslateLang> = listOf(offItem) + all

    /** 「相手の言語」用の選択肢(先頭に「翻訳しない」「自動」) */
    val forTheir: List<TranslateLang> = listOf(offItem, autoItem) + all

    fun indexIn(list: List<TranslateLang>, code: String): Int = list.indexOfFirst { it.code == code }.let { if (it < 0) 0 else it }

    fun label(code: String): String = when (code) {
        OFF -> offItem.label
        AUTO -> autoItem.label
        else -> all.firstOrNull { it.code == code }?.label ?: code
    }

    /** Language names shown in the currently selected app language. */
    fun displayLabel(item: TranslateLang, context: Context): String = displayLabel(item.code, context)

    fun displayLabel(code: String, context: Context): String {
        if (code == OFF) return AppLanguage.tr(offItem.label, context)
        if (code == AUTO) return AppLanguage.tr(autoItem.label, context)
        val tag = when (code) {
            "zh-Hans" -> "zh-CN"
            "zh-Hant" -> "zh-TW"
            else -> code
        }
        val displayLocale = Locale.forLanguageTag(AppLanguage.resolvedCode(context))
        val locale = Locale.forLanguageTag(tag)
        return when (code) {
            "pt-BR" -> if (AppLanguage.resolvedCode(context) == "ja") "ポルトガル語（ブラジル）" else "${locale.getDisplayLanguage(displayLocale)} (${Locale.forLanguageTag("pt-BR").country})"
            "pt-PT" -> if (AppLanguage.resolvedCode(context) == "ja") "ポルトガル語（ポルトガル）" else "${locale.getDisplayLanguage(displayLocale)} (${Locale.forLanguageTag("pt-PT").country})"
            else -> locale.getDisplayName(displayLocale).ifBlank { all.firstOrNull { it.code == code }?.label ?: code }
        }
    }

    /** バブル表示用の短い表記 */
    fun short(code: String): String = when (code) {
        OFF -> "－"
        AUTO -> "自動"
        else -> code
    }

    /** サーバーが返した言語コードを、対応言語のコードへ寄せる(対応外は null) */
    fun normalize(raw: String): String? {
        val c = raw.trim()
        if (c.isEmpty()) return null
        all.firstOrNull { it.code.equals(c, ignoreCase = true) }?.let { return it.code }
        val low = c.lowercase()
        if (low.startsWith("zh")) {
            return if (low.contains("hant") || low.endsWith("tw") || low.endsWith("hk") || low.endsWith("mo")) "zh-Hant" else "zh-Hans"
        }
        if (low == "pt" || low.startsWith("pt-")) return if (low.endsWith("pt")) "pt-PT" else "pt-BR"
        val base = low.substringBefore('-').substringBefore('_')
        return all.firstOrNull { it.code.equals(base, ignoreCase = true) }?.code
    }
}
