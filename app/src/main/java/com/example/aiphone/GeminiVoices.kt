package com.example.aiphone

/** Gemini Live のプリセット音声（全30種）。name は API の voiceName。 */
object GeminiVoices {
    data class Voice(val name: String, val trait: String) {
        val label: String get() = "$name — $trait"
    }

    const val DEFAULT = "Leda"

    val all: List<Voice> = listOf(
        Voice("Zephyr", "明るい"), Voice("Puck", "アップビート"), Voice("Charon", "情報的"),
        Voice("Kore", "しっかり"), Voice("Fenrir", "興奮気味"), Voice("Leda", "若々しい"),
        Voice("Orus", "しっかり"), Voice("Aoede", "軽やか"), Voice("Callirrhoe", "おおらか"),
        Voice("Autonoe", "明るい"), Voice("Enceladus", "息づかい"), Voice("Iapetus", "クリア"),
        Voice("Umbriel", "おおらか"), Voice("Algieba", "なめらか"), Voice("Despina", "なめらか"),
        Voice("Erinome", "クリア"), Voice("Algenib", "ハスキー"), Voice("Rasalgethi", "情報的"),
        Voice("Laomedeia", "アップビート"), Voice("Achernar", "柔らかい"), Voice("Alnilam", "しっかり"),
        Voice("Schedar", "均一"), Voice("Gacrux", "大人びた"), Voice("Pulcherrima", "前向き"),
        Voice("Achird", "フレンドリー"), Voice("Zubenelgenubi", "カジュアル"), Voice("Vindemiatrix", "穏やか"),
        Voice("Sadachbia", "活発"), Voice("Sadaltager", "博識"), Voice("Sulafat", "温かい")
    )

    fun indexOf(name: String): Int = all.indexOfFirst { it.name == name }.coerceAtLeast(0)
}
