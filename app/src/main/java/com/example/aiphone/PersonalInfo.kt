package com.example.aiphone

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 設定画面の個人情報を読み取り、システム指示用のテキストにまとめる。
 */
object PersonalInfo {
    data class Fields(
        val name: String = "",
        val kana: String = "",
        val phone: String = "",
        val email: String = "",
        val address: String = "",
        val birthday: String = "",
        val company: String = "",
        val notes: String = "",
        /** 住所・勤務先の読み(任意)。名前の読みは kana */
        val addressKana: String = "",
        val companyKana: String = "",
        /** 「許可なしで伝えてよい」項目のキー。既定は空(すべて許可が必要) */
        val autoAllow: Set<String> = emptySet(),
    ) {
        fun isEmpty(): Boolean =
            listOf(name, kana, phone, email, address, birthday, company, notes)
                .all { it.isBlank() }
    }

    /** 個人情報の読み込みで暗号文を復号できなかった場合に true。値を空として上書きしないための診断用。 */
    @Volatile var lastLoadHadError: Boolean = false
        private set

    private val encryptedFieldKeys = listOf(
        AiCallService.KEY_PII_NAME, AiCallService.KEY_PII_KANA, AiCallService.KEY_PII_PHONE,
        AiCallService.KEY_PII_EMAIL, AiCallService.KEY_PII_ADDRESS, AiCallService.KEY_PII_BIRTHDAY,
        AiCallService.KEY_PII_COMPANY, AiCallService.KEY_PII_NOTES, AiCallService.KEY_PII_ADDRESS_KANA,
        AiCallService.KEY_PII_COMPANY_KANA,
    )

    /** 個人情報を読む。旧版の平文は暗号化できた場合だけ移行する。復号失敗時は保存し直さない。 */
    fun load(prefs: SharedPreferences): Fields {
        var hadError = false
        var hasLegacyPlaintext = false
        fun text(key: String): String {
            val stored = prefs.getString(key, "").orEmpty()
            return when (val decoded = SecretStore.decodeStored(stored)) {
                is SecretStore.ReadResult.Success -> {
                    if (decoded.wasLegacyPlaintext && decoded.value.isNotEmpty()) hasLegacyPlaintext = true
                    decoded.value.trim()
                }
                is SecretStore.ReadResult.Failure -> {
                    hadError = true
                    ""
                }
            }
        }
        val f = Fields(
            name = text(AiCallService.KEY_PII_NAME),
            kana = text(AiCallService.KEY_PII_KANA),
            phone = text(AiCallService.KEY_PII_PHONE),
            email = text(AiCallService.KEY_PII_EMAIL),
            address = text(AiCallService.KEY_PII_ADDRESS),
            birthday = text(AiCallService.KEY_PII_BIRTHDAY),
            company = text(AiCallService.KEY_PII_COMPANY),
            notes = text(AiCallService.KEY_PII_NOTES),
            addressKana = text(AiCallService.KEY_PII_ADDRESS_KANA),
            companyKana = text(AiCallService.KEY_PII_COMPANY_KANA),
            autoAllow = AUTO_KEYS.filter { prefs.getBoolean(AiCallService.KEY_PII_AUTO_PREFIX + it, false) }.toSet(),
        )
        lastLoadHadError = hadError
        if (!hadError && hasLegacyPlaintext && !save(prefs, f)) lastLoadHadError = true
        return f
    }

    /** 個人情報を暗号化して保存する。1項目でも暗号化できなければ何も書き込まない。 */
    fun save(prefs: SharedPreferences, f: Fields): Boolean {
        val values = linkedMapOf(
            AiCallService.KEY_PII_NAME to f.name,
            AiCallService.KEY_PII_KANA to f.kana,
            AiCallService.KEY_PII_PHONE to f.phone,
            AiCallService.KEY_PII_EMAIL to f.email,
            AiCallService.KEY_PII_ADDRESS to f.address,
            AiCallService.KEY_PII_BIRTHDAY to f.birthday,
            AiCallService.KEY_PII_COMPANY to f.company,
            AiCallService.KEY_PII_NOTES to f.notes,
            AiCallService.KEY_PII_ADDRESS_KANA to f.addressKana,
            AiCallService.KEY_PII_COMPANY_KANA to f.companyKana,
        )
        // 復号できない既存値を、画面の空欄で上書きしない。
        // ただし、ユーザーが該当欄を再入力した場合は明示的な置き換えとして許可する。
        for (key in encryptedFieldKeys) {
            val stored = prefs.getString(key, "").orEmpty()
            if (stored.isNotEmpty() && SecretStore.decodeStored(stored) is SecretStore.ReadResult.Failure &&
                values[key].isNullOrBlank()
            ) {
                return false
            }
        }
        val encrypted = linkedMapOf<String, String>()
        for ((key, value) in values) {
            if (value.isEmpty()) {
                encrypted[key] = ""
            } else {
                encrypted[key] = SecretStore.encrypt(value) ?: return false
            }
        }
        prefs.edit {
            encrypted.forEach { (key, value) -> putString(key, value) }
            AUTO_KEYS.forEach { putBoolean(AiCallService.KEY_PII_AUTO_PREFIX + it, it in f.autoAllow) }
        }
        lastLoadHadError = false
        return true
    }

    /** 「許可なしで伝えてよい」を設定できる項目(よみがなは名前に従う) */
    val AUTO_KEYS = listOf("name", "phone", "email", "address", "birthday", "company", "notes")

    /** 許可なしで伝えてよい項目か。よみがな(kana)は名前の設定に従う */
    fun isAutoAllowed(f: Fields, key: String): Boolean =
        (if (key == "kana") "name" else key) in f.autoAllow

    /** 読み方の注記が付いた値を音声で伝える方法。 */
    fun readingRule(languageCode: String): String = when (PromptLocalization.normalizeCode(languageCode)) {
        "ja" -> "値に読み方の注記が付いているときは、その読み方で発音し、注記自体は読み上げない。"
        "zh-CN" -> "如果值附带读音注释，请按该读音发音，不要读出注释本身。"
        "zh-TW" -> "如果值附帶讀音註記，請依該讀音發音，不要唸出註記本身。"
        "ko" -> "값에 발음 안내가 있으면 그 발음대로 읽고 안내 문구 자체는 읽지 않습니다."
        "es" -> "Si un valor incluye una nota de pronunciación, pronúncialo según esa nota sin leer la nota en voz alta."
        else -> "If a value includes a pronunciation note, use that pronunciation without reading the note itself aloud."
    }

    /** request_personal_info で申請できる項目キー */
    val FIELD_KEYS = listOf("name", "kana", "phone", "email", "address", "birthday", "company", "notes")

    fun label(key: String): String = when (key) {
        "name" -> "名前"; "kana" -> "よみがな"; "phone" -> "電話番号"; "email" -> "メール"
        "address" -> "住所"; "birthday" -> "生年月日"; "company" -> "勤務先"; "notes" -> "メモ"
        else -> key
    }

    private fun Fields.get(key: String): String = when (key) {
        "name" -> name; "kana" -> kana; "phone" -> phone; "email" -> email
        "address" -> address; "birthday" -> birthday; "company" -> company; "notes" -> notes
        else -> ""
    }

    /** 登録済みの項目キー(値は含めない) */
    fun registeredKeys(f: Fields): List<String> = FIELD_KEYS.filter { f.get(it).isNotBlank() }

    private fun withReading(value: String, reading: String): String =
        if (value.isBlank() || reading.isBlank()) value else "$value(読み: $reading)"

    /** 値に読みを添える。名前←kana、住所←addressKana、勤務先←companyKana */
    private fun Fields.spoken(key: String): String = when (key) {
        "name" -> withReading(name, kana)
        "address" -> withReading(address, addressKana)
        "company" -> withReading(company, companyKana)
        else -> get(key)
    }

    /**
     * 許可された項目の値だけを返す。**本人が許可した項目、または「許可なしで伝えてよい」と設定した項目にだけ**呼ぶこと
     * (AiSessionManager の answerPii / 自動回答のみ)。値が空の項目は含めない。
     * 名前・住所・勤務先は読みを「(読み: …)」で添える。名前と一緒に申請された kana は名前に含まれるため単独では返さない。
     */
    fun valuesFor(f: Fields, keys: Collection<String>): Map<String, String> {
        val ks = keys.filter { it in FIELD_KEYS }
        val dropKana = "name" in ks && f.name.isNotBlank() && f.kana.isNotBlank()
        return ks.filter { !(dropKana && it == "kana") }
            .associateWith { f.spoken(it) }.filterValues { it.isNotBlank() }
    }

    /**
     * System-instruction block listing only registered field names, never their values.
     * The text is localized to the current app language.
     */
    fun toSystemBlock(f: Fields, languageCode: String = "ja"): String {
        val code = PromptLocalization.normalizeCode(languageCode)
        val keys = registeredKeys(f)
        val labels = keys.map { PromptLocalization.personalFieldLabel(code, label(it)) }
        val body = if (keys.isEmpty()) {
            when (code) {
                "ja" -> "本人は個人情報を登録していない。個人情報を聞かれたら ask_user で本人に確認する。"
                "zh-CN" -> "用户未登记个人信息。若对方询问个人信息，请通过 ask_user 向用户确认。"
                "zh-TW" -> "使用者未登記個人資訊。若對方詢問個人資訊，請透過 ask_user 向使用者確認。"
                "ko" -> "사용자가 개인정보를 등록하지 않았습니다. 개인정보를 물으면 ask_user로 사용자에게 확인합니다."
                "es" -> "El usuario no ha registrado datos personales. Si preguntan por ellos, consulta al usuario mediante ask_user."
                else -> "The user has not registered personal information. If asked for it, check with the user through ask_user."
            }
        } else {
            when (code) {
                "ja" -> "本人が登録している項目: " + labels.joinToString("、") + "。値は知らされていない。必要なときだけ request_personal_info で申請する。"
                "zh-CN" -> "用户已登记的字段：" + labels.joinToString("、") + "。你不知道这些字段的具体值。仅在需要时通过 request_personal_info 申请。"
                "zh-TW" -> "使用者已登記的欄位：" + labels.joinToString("、") + "。你不知道這些欄位的具體值。僅在需要時透過 request_personal_info 申請。"
                "ko" -> "사용자가 등록한 항목: " + labels.joinToString(", ") + ". 실제 값은 알 수 없습니다. 필요할 때만 request_personal_info로 요청합니다."
                "es" -> "Campos registrados por el usuario: " + labels.joinToString(", ") + ". No conoces sus valores concretos. Solicítalos mediante request_personal_info solo cuando sea necesario."
                else -> "Fields registered by the user: " + labels.joinToString(", ") + ". You do not know their actual values. Request them through request_personal_info only when necessary."
            }
        }
        val auto = keys.filter { isAutoAllowed(f, it) && it != "kana" }
        val autoNote = if (auto.isEmpty()) "" else {
            val autoLabels = auto.map { PromptLocalization.personalFieldLabel(code, label(it)) }
            when (code) {
                "ja" -> "\n本人の許可を待たずに値が返る項目: " + autoLabels.joinToString("、") + "。これらだけを申請した場合は待機の言葉を言わず、返ってきた値をそのまま伝える。"
                "zh-CN" -> "\n以下字段的值会在无需等待用户批准时返回：" + autoLabels.joinToString("、") + "。如果只申请这些字段，不要说等待用语，直接传达返回的值。"
                "zh-TW" -> "\n以下欄位的值會在無需等待使用者批准時回傳：" + autoLabels.joinToString("、") + "。如果只申請這些欄位，不要說等候用語，直接傳達回傳的值。"
                "ko" -> "\n사용자 승인 없이 값이 반환되는 항목: " + autoLabels.joinToString(", ") + ". 이 항목만 요청했다면 기다려 달라는 말을 하지 말고 반환된 값을 그대로 전달합니다."
                "es" -> "\nCampos cuyos valores se devuelven sin esperar autorización: " + autoLabels.joinToString(", ") + ". Si solicitas solo estos campos, no digas una frase de espera; comunica directamente los valores devueltos."
                else -> "\nFields whose values are returned without waiting for approval: " + autoLabels.joinToString(", ") + ". If requesting only these fields, do not say a holding phrase; relay the returned values directly."
            }
        }
        val heading = when (code) {
            "ja" -> "【本人の情報】"
            "zh-CN" -> "【用户信息】"
            "zh-TW" -> "【使用者資訊】"
            "ko" -> "【사용자 정보】"
            "es" -> "【Información del usuario】"
            else -> "【User information】"
        }
        return "\n\n$heading\n$body$autoNote"
    }

    /**
     * 自分で書いた古いプロンプトに【本人の名前】が残っていても、名前は入れずに「本人」にする。
     * 名前は本人の確認(ask_user)が取れるまで相手に言わない方針。
     */
    @Suppress("UNUSED_PARAMETER")
    fun fillName(prefs: SharedPreferences, prompt: String): String =
        prompt.replace("【本人の名前】", "本人")

    /** User prompt (or a localized default), followed by localized privacy and active-call context. */
    fun buildSystemInstruction(
        prefs: SharedPreferences,
        userPrompt: String?,
        languageCode: String = "ja",
    ): String {
        val base = userPrompt
            ?.takeIf { it.isNotBlank() && !GeminiLiveSession.isLegacyDefault(it) }
            ?: GeminiLiveSession.defaultPrompt(languageCode)
        val briefBlock = CallBriefStore.active?.toSystemBlock(languageCode).orEmpty()
        return fillName(prefs, base) + toSystemBlock(load(prefs), languageCode) + briefBlock
    }

    /** Gemma preparation chat: field names only, localized to the current app language. */
    fun registeredLabels(prefs: SharedPreferences, languageCode: String = "ja"): List<String> =
        registeredKeys(load(prefs)).map { PromptLocalization.personalFieldLabel(languageCode, label(it)) }
}
