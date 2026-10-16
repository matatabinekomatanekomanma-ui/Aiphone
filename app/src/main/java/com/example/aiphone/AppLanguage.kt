package com.example.aiphone

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.util.Locale

/** App-wide locale selection and lightweight localization for the existing view-based UI. */
object AppLanguage {
    const val PREF_KEY = "app_ui_language"
    private const val SYSTEM = "system"

    data class Option(val code: String, val label: String)

    private data class Row(
        val ja: String,
        val en: String,
        val zhCN: String,
        val zhTW: String,
        val ko: String,
        val es: String,
    )

    val options: List<Option> = listOf(
        Option(SYSTEM, ""), Option("ja", "日本語"), Option("en", "English"),
        Option("zh-CN", "简体中文"), Option("zh-TW", "繁體中文"),
        Option("ko", "한국어"), Option("es", "Español"),
    )

    fun selectedCode(context: Context): String =
        context.getSharedPreferences(AiCallService.PREFS, Context.MODE_PRIVATE)
            .getString(PREF_KEY, SYSTEM)?.takeIf { value -> options.any { it.code == value } } ?: SYSTEM

    /** Resolves the system setting to a supported UI language; unsupported system languages use English. */
    fun resolvedCode(context: Context): String {
        val selected = selectedCode(context)
        if (selected != SYSTEM) return selected
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return when (locale.language.lowercase(Locale.ROOT)) {
            "ja" -> "ja"
            "zh" -> if (locale.script.equals("Hant", true) || locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            "ko" -> "ko"
            "es" -> "es"
            else -> "en"
        }
    }

    fun wrap(base: Context): Context {
        val code = resolvedCode(base)
        val tag = when (code) {
            "zh-CN" -> "zh-CN"
            "zh-TW" -> "zh-TW"
            else -> code
        }
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        config.setLayoutDirection(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    fun systemOptionLabel(context: Context): String = when (resolvedCode(context)) {
        "ja" -> "システム設定に従う"
        "zh-CN" -> "跟随系统语言"
        "zh-TW" -> "依照系統語言"
        "ko" -> "기기 언어 사용"
        "es" -> "Usar idioma del dispositivo"
        else -> "Use device language"
    }

    /** Exact-only lookup for walking the initial view tree; preserves chat and user content. */
    private fun trStatic(source: String, context: Context): String {
        val code = resolvedCode(context)
        if (code == "ja" || source.isBlank()) return source
        return dictionaries[code]?.get(source) ?: dictionaries["en"]?.get(source) ?: source
    }

    /** Converts Japanese source labels into the currently-selected app language. User-entered text is never passed here. */
    fun tr(source: String, context: Context): String {
        val code = resolvedCode(context)
        if (code == "ja" || source.isBlank()) return source
        val exact = dictionaries[code]?.get(source)
        if (exact != null) return exact
        // Some status strings contain runtime values, so translate their stable Japanese fragments.
        var result = source
        val fragments = commonFragments[code] ?: commonFragments["en"].orEmpty()
        fragments.entries.sortedByDescending { it.key.length }.forEach { (from, to) ->
            result = result.replace(from, to)
        }
        if (result != source) return result
        // English is the safe fallback for screens/messages without a complete translation yet.
        return dictionaries["en"]?.get(source) ?: source
    }

    fun localizeTree(root: View, context: Context) {
        localizeView(root, context)
    }

    private fun localizeView(view: View, context: Context) {
        if (view is TextView) {
            // Never alter typed text, saved call briefs, prompts, or AI conversation content.
            // Only translate exact UI labels in the static view tree. Dynamic chat messages,
            // names and saved user content must not be altered by fragment substitutions.
            if (view !is EditText) view.text = trStatic(view.text?.toString().orEmpty(), context)
            val hint = view.hint?.toString().orEmpty()
            if (hint.isNotEmpty()) view.hint = trStatic(hint, context)
            val description = view.contentDescription?.toString().orEmpty()
            if (description.isNotEmpty()) view.contentDescription = trStatic(description, context)
        }
        if (view is ViewGroup) {
            // Do not replace OnHierarchyChangeListener: Material TabLayout and other widgets
            // use their own listeners internally.
            for (i in 0 until view.childCount) localizeView(view.getChildAt(i), context)
        }
    }

    fun localizeLanguageOption(code: String, context: Context): String = when (code) {
        SYSTEM -> systemOptionLabel(context)
        else -> options.firstOrNull { it.code == code }?.label ?: code
    }

    private val rows = listOf(
        Row("AI通話", "AI call", "AI 通话", "AI 通話", "AI 통화", "Llamada con IA"),
        Row("動作警告", "Operational warning", "运行警告", "運作警告", "작동 경고", "Advertencia de funcionamiento"),
        Row("通話中の AI 支援", "AI assistance during calls", "通话中的 AI 辅助", "通話中的 AI 輔助", "통화 중 AI 지원", "Asistencia de IA durante las llamadas"),
        Row("priv-kit 未接続などで AI 通話が動作しないとき", "When AI calling does not work, such as when priv-kit is disconnected", "priv-kit 未连接等导致 AI 通话无法运行时", "priv-kit 未連線等原因導致 AI 通話無法運作時", "priv-kit 미연결 등으로 AI 통화가 작동하지 않을 때", "Cuando las llamadas con IA no funcionan, por ejemplo, si priv-kit no está conectado"),
        Row("priv-kit 未接続：機能していません", "priv-kit disconnected: not working", "priv-kit 未连接：无法运行", "priv-kit 未連線：無法運作", "priv-kit 연결 안 됨: 작동하지 않음", "priv-kit desconectado: no funciona"),
        Row("AI通話モニター", "AI call monitor", "AI 通话监视器", "AI 通話監視器", "AI 통화 모니터", "Monitor de llamadas con IA"),
        Row("セット中の通話で AI を起動します", "AI starts on calls you have armed", "在已设置待机的通话中启动 AI", "在已設定待機的通話中啟動 AI", "대기 설정한 통화에서 AI가 시작됩니다", "La IA se inicia en las llamadas que ha activado"),
        Row("AIを起動しました", "AI started", "已启动 AI", "已啟動 AI", "AI를 시작했습니다", "IA iniciada"),
        Row("Gemini API キーが未設定です", "Gemini API key is not set", "未设置 Gemini API 密钥", "未設定 Gemini API 金鑰", "Gemini API 키가 설정되지 않았습니다", "No se ha configurado la clave de la API de Gemini"),
        Row("priv-kit が未接続です。アプリを開いて接続を確認してください", "priv-kit is not connected. Open the app and check the connection.", "priv-kit 未连接。请打开应用检查连接。", "priv-kit 未連線。請開啟應用程式確認連線。", "priv-kit이 연결되지 않았습니다. 앱을 열어 연결을 확인하세요.", "priv-kit no está conectado. Abra la aplicación y compruebe la conexión."),
        Row("AI を開始できませんでした", "Could not start AI", "无法启动 AI", "無法啟動 AI", "AI를 시작할 수 없습니다", "No se pudo iniciar la IA"),
        Row("サービスが起動していません。アプリを開いてください", "The service is not running. Open the app.", "服务未运行。请打开应用。", "服務未執行。請開啟應用程式。", "서비스가 실행 중이 아닙니다. 앱을 여세요.", "El servicio no está en ejecución. Abra la aplicación."),
        Row("用件なし", "No call brief", "没有通话事项", "沒有通話事項", "용건 없음", "Sin motivo de llamada"),
        Row("通知を許可してください", "Please allow notifications", "请允许通知", "請允許通知", "알림을 허용하세요", "Permite las notificaciones"),
        Row("ワイヤレスデバッグのペアリングコードは、通知の入力欄に入れます。通知が許可されていないと、設定を進められません。", "Enter the wireless debugging pairing code in the notification's input field. Setup cannot continue unless notifications are allowed.", "无线调试配对码需要输入到通知的输入框中。未允许通知时，无法继续设置。", "無線偵錯配對碼需要輸入到通知的輸入欄。未允許通知時，無法繼續設定。", "무선 디버깅 페어링 코드는 알림 입력란에 입력합니다. 알림을 허용하지 않으면 설정을 진행할 수 없습니다.", "Introduce el código de emparejamiento de depuración inalámbrica en el campo de la notificación. Debes permitir las notificaciones para continuar."),
        Row("ワイヤレスデバッグのペアリングコードは、通知の入力欄に入れます。通知が許可されていないと、設定を進められません。\n\n設定画面で、このアプリの通知をオンにしてから、もう一度ボタンを押してください。", "Enter the wireless debugging pairing code in the notification's input field. Setup cannot continue unless notifications are allowed.\n\nTurn on notifications for this app in Settings, then press the button again.", "无线调试配对码需要输入到通知的输入框中。未允许通知时，无法继续设置。\n\n请在设置中开启此应用的通知，然后再次点击按钮。", "無線偵錯配對碼需要輸入到通知的輸入欄。未允許通知時，無法繼續設定。\n\n請在設定中開啟此應用程式的通知，然後再次點選按鈕。", "무선 디버깅 페어링 코드는 알림 입력란에 입력합니다. 알림을 허용하지 않으면 설정을 진행할 수 없습니다.\n\n설정에서 이 앱의 알림을 켠 다음 버튼을 다시 누르세요.", "Introduce el código de emparejamiento de depuración inalámbrica en el campo de la notificación. Debes permitir las notificaciones para continuar.\n\nActiva las notificaciones de esta aplicación en Ajustes y vuelve a pulsar el botón."),
        Row("キャンセル", "Cancel", "取消", "取消", "취소", "Cancelar"),
        Row("もう一度許可する", "Allow again", "再次允许", "再次允許", "다시 허용", "Permitir de nuevo"),
        Row("通知設定を開く", "Open notification settings", "打开通知设置", "開啟通知設定", "알림 설정 열기", "Abrir ajustes de notificaciones"),
        Row("AI通話開始中...", "Starting AI call...", "正在启动 AI 通话...", "正在啟動 AI 通話...", "AI 통화 시작 중...", "Iniciando llamada con IA..."),
        Row("停止中...", "Stopping...", "正在停止...", "正在停止...", "중지 중...", "Deteniendo..."),
        Row("翻訳開始中...", "Starting translation...", "正在启动翻译...", "正在啟動翻譯...", "번역 시작 중...", "Iniciando traducción..."),
        Row("AI切断(通話は継続中)", "AI disconnected (call continues)", "AI 已断开连接（通话仍在继续）", "AI 已中斷連線（通話仍在繼續）", "AI 연결 끊김(통화는 계속됨)", "IA desconectada (la llamada continúa)"),
        Row("削除する用件がありません", "There is no call brief to delete", "没有可删除的通话事项", "沒有可刪除的通話事項", "삭제할 용건이 없습니다", "No hay ningún motivo de llamada para eliminar"),
        Row("JSON の解析に失敗しました。もう一度まとめてください", "Could not parse the JSON. Please organize it again.", "无法解析 JSON。请重新整理。", "無法解析 JSON。請重新整理。", "JSON을 해석하지 못했습니다. 다시 정리해 주세요.", "No se pudo analizar el JSON. Vuelve a organizarlo."),
        Row("アプリの表示言語", "App language", "应用语言", "應用程式語言", "앱 표시 언어", "Idioma de la aplicación"),
        Row("動作に必要な設定", "Required setup", "运行所需设置", "必要設定", "필수 설정", "Configuración necesaria"),
        Row("priv-kit 接続", "priv-kit connection", "priv-kit 连接", "priv-kit 連線", "priv-kit 연결", "Conexión de priv-kit"),
        Row("設定", "Settings", "设置", "設定", "설정", "Ajustes"),
        Row("設定方法", "How to set up", "设置方法", "設定方法", "설정 방법", "Cómo configurarlo"),
        Row("Gemini API キー", "Gemini API key", "Gemini API 密钥", "Gemini API 金鑰", "Gemini API 키", "Clave de API de Gemini"),
        Row("AQ... または AIza...", "AQ... or AIza...", "AQ... 或 AIza...", "AQ... 或 AIza...", "AQ... 또는 AIza...", "AQ... o AIza..."),
        Row("▶ 個人情報", "▶ Personal information", "▶ 个人信息", "▶ 個人資訊", "▶ 개인정보", "▶ Información personal"),
        Row("▼ 個人情報", "▼ Personal information", "▼ 个人信息", "▼ 個人資訊", "▼ 개인정보", "▼ Información personal"),
        Row("本人の個人情報（AIに渡す）", "Your personal information (shared with AI)", "个人信息（提供给 AI）", "個人資訊（提供給 AI）", "본인 개인정보(AI에 제공)", "Información personal (para la IA)"),
        Row("相手から詳しく聞かれたら、あなたに確認してから答えます。「許可なしで伝えてよい」にした項目は、確認なしですぐ答えます(初期は全項目「確認する」)。読みを入れると、AIは「漢字(読み)」で受け取り、読みどおりに発音します。入力した内容は自動で保存され、端末内で暗号化して保管されます。", "If the other party asks for details, the AI checks with you before answering. Items marked ‘May share without asking’ are answered automatically; all items require confirmation by default. Add a pronunciation guide so the AI can pronounce names as intended. Your entries are saved automatically and encrypted on this device.", "如果对方询问详细信息，AI 会先向您确认再回答。标记为“无需确认即可告知”的项目会自动回答；默认所有项目都需要确认。填写读音后，AI 会按指定读音发音。输入内容会自动保存并加密存储在本机。", "如果對方詢問詳細資訊，AI 會先向您確認再回答。標記為「無需確認即可告知」的項目會自動回答；預設所有項目都需要確認。填寫讀音後，AI 會依指定讀音發音。輸入內容會自動儲存並加密保存在本機。", "상대가 자세히 물으면 AI가 먼저 확인합니다. ‘확인 없이 알려도 됨’으로 설정한 항목은 바로 답하며, 기본값은 모두 확인입니다. 발음 안내를 입력하면 AI가 해당 발음으로 말합니다. 입력 정보는 자동 저장되며 기기 안에서 암호화됩니다.", "Si la otra persona pide detalles, la IA te consultará antes de responder. Los campos autorizados se responderán automáticamente; por defecto todos requieren confirmación. Añade una guía de pronunciación para que la IA diga los nombres correctamente. Los datos se guardan automáticamente y se cifran en el dispositivo."),
        Row("名前（必須・名乗り用）", "Name (required; used to introduce yourself)", "姓名（必填；用于自我介绍）", "姓名（必填；用於自我介紹）", "이름(필수, 본인 소개용)", "Nombre (obligatorio; para presentarte)"),
        Row("例: 山田 太郎", "Example: Taro Yamada", "例如：山田太郎", "例如：山田太郎", "예: 야마다 타로", "Ejemplo: Taro Yamada"),
        Row("名前の読み", "Name pronunciation", "姓名读音", "姓名讀音", "이름 발음", "Pronunciación del nombre"),
        Row("ひらがな（漢字だけだと読み間違えるときに）", "Pronunciation in kana (if kanji may be misread)", "假名读音（汉字可能被读错时填写）", "假名讀音（漢字可能被讀錯時填寫）", "히라가나 발음(한자를 잘못 읽을 수 있을 때)", "Pronunciación (si pueden leer mal los kanji)"),
        Row("許可なしで伝えてよい", "May share without asking", "无需确认即可告知", "無需確認即可告知", "확인 없이 알려도 됨", "Puede compartir sin preguntar"),
        Row("電話番号", "Phone number", "电话号码", "電話號碼", "전화번호", "Número de teléfono"),
        Row("例: 090-0000-0000", "Example: +81 90-0000-0000", "例如：090-0000-0000", "例如：090-0000-0000", "예: 010-0000-0000", "Ejemplo: +34 600 000 000"),
        Row("メールアドレス", "Email address", "电子邮件地址", "電子郵件地址", "이메일 주소", "Correo electrónico"),
        Row("例: name@example.com", "Example: name@example.com", "例如：name@example.com", "例如：name@example.com", "예: name@example.com", "Ejemplo: name@example.com"),
        Row("住所", "Address", "地址", "地址", "주소", "Dirección"),
        Row("例: 東京都〇〇区1-2-3", "Example: 1-2-3, Example City", "例如：某市某区 1-2-3", "例如：某市某區 1-2-3", "예: 서울시 ○○구 1-2-3", "Ejemplo: Calle Mayor 1, Madrid"),
        Row("住所の読み（任意）", "Address pronunciation (optional)", "地址读音（选填）", "地址讀音（選填）", "주소 발음(선택)", "Pronunciación de la dirección (opcional)"),
        Row("ひらがな", "Pronunciation guide", "读音", "讀音", "발음 안내", "Guía de pronunciación"),
        Row("生年月日", "Date of birth", "出生日期", "出生日期", "생년월일", "Fecha de nacimiento"),
        Row("例: 1990年1月1日", "Example: January 1, 1990", "例如：1990年1月1日", "例如：1990年1月1日", "예: 1990년 1월 1일", "Ejemplo: 1 de enero de 1990"),
        Row("勤務先・部署", "Employer / department", "工作单位 / 部门", "工作單位 / 部門", "직장 / 부서", "Empresa / departamento"),
        Row("例: 〇〇株式会社 営業部", "Example: Example Inc., Sales", "例如：某某株式会社 销售部", "例如：某某股份有限公司 業務部", "예: ○○ 주식회사 영업부", "Ejemplo: Empresa Ejemplo, ventas"),
        Row("勤務先の読み（任意）", "Employer pronunciation (optional)", "工作单位读音（选填）", "工作單位讀音（選填）", "직장명 발음(선택)", "Pronunciación de la empresa (opcional)"),
        Row("その他", "Other", "其他", "其他", "기타", "Otros"),
        Row("伝えてよいこと・伝えてはいけないこと", "Information to share or withhold", "可以或不可以透露的信息", "可以或不可以透露的資訊", "알려도 되는 정보 / 알려서는 안 되는 정보", "Información que se puede o no compartir"),
        Row("▶ 準備", "▶ Preparation", "▶ 通话准备", "▶ 通話準備", "▶ 통화 준비", "▶ Preparación"),
        Row("▼ 準備", "▼ Preparation", "▼ 通话准备", "▼ 通話準備", "▼ 통화 준비", "▼ Preparación"),
        Row("Gemma の思考レベル（準備タブ・高いほど丁寧だが遅い。既定は中）", "Gemma reasoning level (Preparation; higher is more thorough but slower; default: medium)", "Gemma 推理级别（准备页；级别越高越仔细但越慢；默认中）", "Gemma 推理等級（準備頁；等級越高越仔細但越慢；預設中）", "Gemma 추론 수준(준비 탭, 높을수록 더 꼼꼼하지만 느림, 기본 중간)", "Nivel de razonamiento de Gemma (Preparación; más alto es más detallado pero lento; predeterminado: medio)"),
        Row("Gemma の最大リクエスト回数（失敗時の自動リトライ含む）", "Gemma maximum request attempts (including retries)", "Gemma 最大请求次数（包含失败后自动重试）", "Gemma 最大請求次數（包含失敗後自動重試）", "Gemma 최대 요청 횟수(실패 시 자동 재시도 포함)", "Intentos máximos de Gemma (incluye reintentos)"),
        Row("Gemma の準備チャット用プロンプト（システム指示）", "Gemma preparation chat prompt (system instructions)", "Gemma 准备对话提示词（系统指令）", "Gemma 準備對話提示詞（系統指令）", "Gemma 준비 채팅 프롬프트(시스템 지침)", "Prompt de chat de preparación de Gemma (instrucciones del sistema)"),
        Row("準備タブでGemmaと会話するときの指示です。編集内容は次の送信から反映されます。個人情報保護の必須ルールは別途付加されます。", "Instructions for chatting with Gemma in the Preparation tab. Changes apply to the next message. Required personal-information protections are added separately.", "这是在准备页与 Gemma 对话时使用的指令。修改会从下一次发送开始生效。个人信息保护的强制规则会另外追加。", "這是在準備頁與 Gemma 對話時使用的指令。修改會從下一次傳送開始生效。個人資訊保護的必要規則會另外附加。", "준비 탭에서 Gemma와 대화할 때 사용하는 지침입니다. 변경 사항은 다음 전송부터 적용됩니다. 필수 개인정보 보호 규칙은 별도로 추가됩니다.", "Instrucciones para conversar con Gemma en la pestaña Preparación. Los cambios se aplican al siguiente envío. Las protecciones obligatorias de datos personales se añaden por separado."),
        Row("Gemmaとの準備チャットの指示", "Instructions for the preparation chat with Gemma", "与 Gemma 进行准备对话的指令", "與 Gemma 進行準備對話的指令", "Gemma 준비 채팅 지침", "Instrucciones para el chat de preparación con Gemma"),
        Row("既定の準備チャット用プロンプトに戻す", "Restore default preparation chat prompt", "恢复默认准备对话提示词", "還原預設準備對話提示詞", "기본 준비 채팅 프롬프트로 복원", "Restaurar el prompt predeterminado del chat de preparación"),
        Row("Gemma の用件まとめ用プロンプト（JSON出力）", "Gemma call-brief summary prompt (JSON output)", "Gemma 通话事项整理提示词（JSON 输出）", "Gemma 通話事項整理提示詞（JSON 輸出）", "Gemma 통화 용건 요약 프롬프트(JSON 출력)", "Prompt de Gemma para resumir la llamada (salida JSON)"),
        Row("「まとめる」ボタンで使う指示です。アプリが読み取るJSON形式とキーは、出力の末尾で必須条件として指定されます。", "Instructions used by the Organize button. The JSON format and keys required by the app are appended as mandatory constraints.", "这是“整理”按钮使用的指令。应用需要的 JSON 格式和键会作为强制条件附加在末尾。", "這是「整理」按鈕使用的指令。應用程式需要的 JSON 格式與鍵會作為必要條件附加在末尾。", "정리 버튼에서 사용하는 지침입니다. 앱이 읽는 JSON 형식과 키는 필수 조건으로 끝에 추가됩니다.", "Instrucciones usadas por el botón Organizar. El formato JSON y las claves que necesita la aplicación se añaden al final como requisitos obligatorios."),
        Row("用件をJSONにまとめる指示", "Instructions for organizing the call brief as JSON", "将通话事项整理为 JSON 的指令", "將通話事項整理為 JSON 的指令", "통화 용건을 JSON으로 정리하는 지침", "Instrucciones para organizar el motivo de la llamada en JSON"),
        Row("既定の用件まとめ用プロンプトに戻す", "Restore default call-brief summary prompt", "恢复默认通话事项整理提示词", "還原預設通話事項整理提示詞", "기본 통화 용건 요약 프롬프트로 복원", "Restaurar el prompt predeterminado para resumir la llamada"),
        Row("1〜10回。混雑時は多めにすると成功しやすいです（既定 5）", "1–10 attempts. More attempts may help when busy (default: 5).", "1–10 次。繁忙时增加次数可能更容易成功（默认 5）", "1–10 次。繁忙時增加次數可能更容易成功（預設 5）", "1~10회. 혼잡할 때 횟수를 늘리면 성공률이 높아질 수 있습니다(기본 5)", "De 1 a 10 intentos. Más intentos pueden ayudar cuando hay congestión (predeterminado: 5)."),
        Row("▶ 通話", "▶ Call", "▶ AI 通话", "▶ AI 通話", "▶ 통화", "▶ Llamada"),
        Row("▼ 通話", "▼ Call", "▼ AI 通话", "▼ AI 通話", "▼ 통화", "▼ Llamada"),
        Row("Gemini Live の話し方", "Gemini Live voice", "Gemini Live 语音", "Gemini Live 語音", "Gemini Live 음성", "Voz de Gemini Live"),
        Row("ボイス（全30種）", "Voice (30 options)", "声音（共 30 种）", "語音（共 30 種）", "음성(총 30종)", "Voz (30 opciones)"),
        Row("音声検出（VAD）", "Voice activity detection (VAD)", "语音活动检测（VAD）", "語音活動偵測（VAD）", "음성 감지(VAD)", "Detección de actividad de voz (VAD)"),
        Row("相手の声・雑音で AI の発話が途切れる場合に調整します。次の通話から反映されます。", "Adjust these settings if the other person's voice or noise interrupts the AI. Changes apply to the next call.", "如果对方声音或噪声打断 AI 发言，请调整此项。设置从下次通话开始生效。", "如果對方聲音或雜音打斷 AI 發言，請調整此項。設定從下次通話開始生效。", "상대 음성이나 잡음 때문에 AI 발화가 끊기면 조정하세요. 다음 통화부터 적용됩니다.", "Ajusta esto si la voz de la otra persona o el ruido interrumpen a la IA. Se aplicará en la próxima llamada."),
        Row("話し始めの感度（低いほど雑音で割り込まれにくい）", "Speech-start sensitivity (lower values reduce noise interruptions)", "开始说话的灵敏度（越低越不容易被噪声打断）", "開始說話的靈敏度（越低越不容易被雜音打斷）", "말하기 시작 감도(낮을수록 잡음으로 끊길 가능성이 낮음)", "Sensibilidad al inicio del habla (un valor menor reduce interrupciones por ruido)"),
        Row("話し終わりの感度（高いほど早く話し終わりと判断）", "Speech-end sensitivity (higher values detect the end sooner)", "结束说话的灵敏度（越高越快判断说话结束）", "結束說話的靈敏度（越高越快判斷說話結束）", "말하기 종료 감도(높을수록 더 빨리 종료로 판단)", "Sensibilidad al final del habla (un valor mayor detecta antes el final)"),
        Row("無音の長さ（ミリ秒、100〜3000。推奨 500〜800）", "Silence duration (ms, 100–3000; recommended 500–800)", "静音时长（毫秒，100–3000；建议 500–800）", "靜音時間（毫秒，100–3000；建議 500–800）", "무음 길이(ms, 100~3000, 권장 500~800)", "Duración del silencio (ms, 100–3000; recomendado 500–800)"),
        Row("プロンプト（システム指示）", "Prompt (system instructions)", "提示词（系统指令）", "提示詞（系統指令）", "프롬프트(시스템 지침)", "Prompt (instrucciones del sistema)"),
        Row("発信代行の追加指示（空なら既定）。個人情報は上の欄へ", "Additional instructions for calls (blank uses defaults). Enter personal information above.", "代拨电话的补充指令（留空使用默认值）。个人信息请填写在上方。", "代撥電話的補充指令（留白使用預設值）。個人資訊請填寫在上方。", "전화 대행 추가 지침(비워 두면 기본값). 개인정보는 위 항목에 입력하세요.", "Instrucciones adicionales para llamadas (vacío = predeterminado). Introduce los datos personales arriba."),
        Row("既定のプロンプトに戻す", "Restore default prompt", "恢复默认提示词", "還原預設提示詞", "기본 프롬프트로 복원", "Restaurar el prompt predeterminado"),
        Row("試聴用：相手の第一声", "Preview: the other party's opening line", "试听：对方的第一句话", "試聽：對方的第一句話", "미리 듣기: 상대방의 첫마디", "Vista previa: primera frase de la otra persona"),
        Row("▶ この設定で話してみる", "▶ Try this voice", "▶ 试听此设置", "▶ 試聽此設定", "▶ 이 설정으로 말하기", "▶ Probar esta voz"),
        Row("停止", "Stop", "停止", "停止", "중지", "Detener"),
        Row("通話終了後の電話待機", "Call standby after the call ends", "通话结束后的电话待机", "通話結束後的電話待機", "통화 종료 후 전화 대기", "Espera telefónica tras finalizar la llamada"),
        Row("通話終了後に電話待機を解除する", "Disable call standby after the call ends", "通话结束后取消电话待机", "通話結束後取消電話待機", "통화 종료 후 전화 대기 해제", "Desactivar la espera telefónica al finalizar la llamada"),
        Row("通話の録音", "Call recording", "通话录音", "通話錄音", "통화 녹음", "Grabación de llamadas"),
        Row("通話内容を録音する", "Record call audio", "录制通话内容", "錄製通話內容", "통화 내용 녹음", "Grabar el audio de la llamada"),
        Row("相手の声と、相手に届く側の声（AI・翻訳の声を含む。AI・翻訳を使わないときは自分の声）を、M4A で「音楽」フォルダ（Music/Aiphone）に保存します。通話の録音には、相手の同意が必要な場合があります。音楽ファイルへのアクセスを許可した他のアプリから、保存した録音が見える場合があります。", "Saves the other party's voice and the voice that reaches them (including AI/translation voice; your own voice when neither is used) as an M4A file in the Music folder (Music/Aiphone). Recording a call may require the other party's consent. Other apps that can access music files may be able to see the saved recordings.", "将对方的声音和传到对方的声音（包括 AI/翻译的声音；不使用 AI/翻译时为您自己的声音）以 M4A 格式保存到“音乐”文件夹（Music/Aiphone）。录音可能需要征得对方同意。已获得音乐文件访问权限的其他应用可能能看到已保存的录音。", "將對方的聲音和傳到對方的聲音（包括 AI／翻譯的聲音；不使用 AI／翻譯時為您自己的聲音）以 M4A 格式儲存到「音樂」資料夾（Music/Aiphone）。錄音可能需要取得對方同意。已取得音樂檔案存取權限的其他應用程式可能看得到已儲存的錄音。", "상대방의 목소리와 상대방에게 전달되는 목소리(AI·번역 음성 포함, 둘 다 쓰지 않을 때는 내 목소리)를 M4A 파일로 음악 폴더(Music/Aiphone)에 저장합니다. 통화 녹음에는 상대방의 동의가 필요할 수 있습니다. 음악 파일 접근 권한이 있는 다른 앱에서 저장된 녹음이 보일 수 있습니다.", "Guarda la voz de la otra persona y la voz que le llega (incluida la voz de IA/traducción; tu propia voz si no se usa ninguna) como archivo M4A en la carpeta Música (Music/Aiphone). Grabar una llamada puede requerir el consentimiento de la otra parte. Otras aplicaciones con acceso a archivos de música podrían ver las grabaciones guardadas."),
        Row("通話終了後にセットした用件を削除する", "Delete the armed call brief after the call", "通话结束后删除已设置的通话事项", "通話結束後刪除已設定的通話事項", "통화 종료 후 설정한 용건 삭제", "Eliminar el motivo de llamada configurado al finalizar"),
        Row("解除のみの場合、用件は一覧に残ります。削除すると一覧からも消えます。", "Disarming keeps the brief in the list. Deleting removes it from the list too.", "仅取消待机时，事项仍保留在列表中；删除后也会从列表移除。", "僅取消待機時，事項仍保留在清單中；刪除後也會從清單移除。", "대기만 해제하면 용건은 목록에 남습니다. 삭제하면 목록에서도 사라집니다.", "Al desactivar la espera, el motivo sigue en la lista. Al eliminarlo, también desaparece de la lista."),
        Row("診断（priv-kit 経由）", "Diagnostics (via priv-kit)", "诊断（通过 priv-kit）", "診斷（透過 priv-kit）", "진단(priv-kit 경유)", "Diagnóstico (mediante priv-kit)"),
        Row("▶ 翻訳", "▶ Translation", "▶ 翻译", "▶ 翻譯", "▶ 번역", "▶ Traducción"),
        Row("▼ 翻訳", "▼ Translation", "▼ 翻译", "▼ 翻譯", "▼ 번역", "▼ Traducción"),
        Row("相手の元の声も一緒に聞こえるようにする", "Also play the other party's original voice", "同时播放对方的原始声音", "同時播放對方的原始聲音", "상대방의 원래 음성도 함께 재생", "Reproducir también la voz original de la otra persona"),
        Row("オフのとき、相手の声は消し、翻訳音声だけを再生します。", "When off, the other party's voice is muted and only the translated audio plays.", "关闭时会消除对方原声，只播放翻译语音。", "關閉時會消除對方原聲，只播放翻譯語音。", "끄면 상대방 원음이 음소거되고 번역 음성만 재생됩니다.", "Si se desactiva, se silencia la voz original y solo se reproduce la traducción."),
        Row("翻訳先言語のエコー（相手がすでに翻訳先の言語で話したときもそのまま流す）", "Echo target language (pass through speech already in the target language)", "目标语言直通（对方已使用目标语言时直接播放）", "目標語言直通（對方已使用目標語言時直接播放）", "번역 대상 언어 에코(상대가 이미 대상 언어로 말하면 그대로 전달)", "Reproducir el idioma de destino tal cual si la otra persona ya lo está usando"),
        Row("オフのとき、翻訳先と同じ言語の発話は無音になります（重複防止）。", "When off, speech already in the target language is muted to prevent duplication.", "关闭时，已使用目标语言的语音会静音（避免重复）。", "關閉時，已使用目標語言的語音會靜音（避免重複）。", "끄면 대상 언어와 같은 발화는 중복 방지를 위해 음소거됩니다.", "Si se desactiva, se silencia el habla que ya está en el idioma de destino para evitar duplicados."),
        Row("未完了の設定があります。「設定」タブを確認してください。", "Some setup steps are incomplete. Check the Settings tab.", "仍有设置未完成。请检查“设置”页。", "仍有設定未完成。請檢查「設定」頁。", "완료되지 않은 설정이 있습니다. 설정 탭을 확인하세요.", "Hay ajustes pendientes. Revisa la pestaña Ajustes."),
        Row("AI通話", "AI call", "AI 通话", "AI 通話", "AI 통화", "Llamada con IA"),
        Row("オフ", "Off", "关闭", "關閉", "꺼짐", "Desactivado"),
        Row("AIが判断を求めています", "The AI needs your decision", "AI 需要您做出决定", "AI 需要您做出決定", "AI가 결정을 요청합니다", "La IA necesita que decidas"),
        Row("返答したい内容がなければ、指示を入力(例: 来週は都合が悪いと伝えて)", "Enter an instruction if none of the suggested replies fit (e.g. say next week doesn't work).", "如果没有合适的回复选项，请输入指示（例如：告诉对方下周不方便）。", "如果沒有合適的回覆選項，請輸入指示（例如：告訴對方下週不方便）。", "제안된 답변이 없으면 지시를 입력하세요(예: 다음 주는 어렵다고 전해 주세요).", "Si ninguna respuesta sugerida encaja, escribe una instrucción (p. ej., di que la próxima semana no te viene bien)."),
        Row("送信", "Send", "发送", "傳送", "보내기", "Enviar"),
        Row("直近のメッセージを再送信", "Resend the last message", "重新发送上一条消息", "重新傳送上一則訊息", "마지막 메시지 다시 보내기", "Reenviar el último mensaje"),
        Row("住所・生年月日・電話番号などの個人情報は入力しないでください（設定に登録した項目名だけが参照されます）", "Do not enter personal data such as an address, date of birth, or phone number. Only field names saved in Settings are referenced.", "请勿输入地址、出生日期或电话号码等个人信息（这里只会引用设置中登记的字段名称）。", "請勿輸入地址、出生日期或電話號碼等個人資訊（這裡只會引用設定中登記的欄位名稱）。", "주소, 생년월일, 전화번호 등의 개인정보를 입력하지 마세요(설정에 등록된 항목명만 참조됩니다).", "No introduzcas datos personales como dirección, fecha de nacimiento o teléfono; solo se consultan los nombres de campos guardados en Ajustes."),
        Row("解除", "Disarm", "取消待机", "取消待機", "대기 해제", "Desactivar"),
        Row("保存した用件（タップで編集）", "Saved call briefs (tap to edit)", "已保存事项（点按编辑）", "已儲存事項（點選編輯）", "저장한 용건(탭하여 편집)", "Motivos de llamada guardados (toca para editar)"),
        Row("Gemma の応答を待っています…", "Waiting for Gemma…", "正在等待 Gemma 回复…", "正在等待 Gemma 回覆…", "Gemma 응답 대기 중…", "Esperando la respuesta de Gemma…"),
        Row("用件・相手・聞きたいことなど", "Purpose, contact, questions, etc.", "事项、联系人、想询问的内容等", "事項、聯絡人、想詢問的內容等", "용건, 상대방, 물어볼 내용 등", "Motivo, contacto, preguntas, etc."),
        Row("まとめる", "Organize", "整理", "整理", "정리", "Organizar"),
        Row("履歴クリア", "Clear history", "清除记录", "清除紀錄", "기록 지우기", "Borrar historial"),
        Row("通話タブの会話履歴を削除しますか？この操作は取り消せません。", "Delete the conversation history shown on the Call tab? This action cannot be undone.", "要删除通话标签页中显示的对话记录吗？此操作无法撤销。", "要刪除通話分頁中顯示的對話紀錄嗎？此操作無法復原。", "통화 탭에 표시된 대화 기록을 삭제할까요? 이 작업은 되돌릴 수 없습니다.", "¿Eliminar el historial de conversación de la pestaña Llamada? Esta acción no se puede deshacer."),
        Row("削除", "Delete", "删除", "刪除", "삭제", "Eliminar"),
        Row("まとめ（編集してからセット）", "Summary (edit before setting)", "摘要（编辑后再设置）", "摘要（編輯後再設定）", "요약(편집한 뒤 설정)", "Resumen (edita antes de configurar)"),
        Row("発信先の名前", "Recipient name", "拨打对象姓名", "撥打對象姓名", "전화 상대 이름", "Nombre del destinatario"),
        Row("用件（1〜2文）", "Purpose (1–2 sentences)", "事项（1–2 句话）", "事項（1–2 句）", "용건(1~2문장)", "Motivo (1–2 frases)"),
        Row("伝えること", "What to say", "需要传达的内容", "需要傳達的內容", "전달할 내용", "Qué comunicar"),
        Row("聞くこと", "Questions to ask", "需要询问的内容", "需要詢問的內容", "물어볼 내용", "Qué preguntar"),
        Row("判断してよい範囲（日時・金額・条件）", "Allowed decision limits (date, time, price, conditions)", "允许判断的范围（日期、时间、金额、条件）", "允許判斷的範圍（日期、時間、金額、條件）", "판단 가능한 범위(날짜, 시간, 금액, 조건)", "Límites de decisión permitidos (fecha, hora, precio, condiciones)"),
        Row("言ってはいけないこと", "Things not to say", "禁止透露的内容", "禁止透露的內容", "말하면 안 되는 내용", "Qué no decir"),
        Row("想定される質問と答え", "Expected questions and answers", "可能的问题与答案", "可能的問題與答案", "예상 질문과 답변", "Preguntas y respuestas previstas"),
        Row("調べて分かった事実（未確認・出典付き）", "Research findings (unverified; include sources)", "查到的事实（未核实，附来源）", "查到的事實（未查證，附來源）", "조사한 사실(미확인, 출처 포함)", "Hechos investigados (sin verificar; con fuentes)"),
        Row("終わりの条件", "When to end the call", "结束条件", "結束條件", "종료 조건", "Condiciones para finalizar"),
        Row("戻る", "Back", "返回", "返回", "뒤로", "Volver"),
        Row("削除", "Delete", "删除", "刪除", "삭제", "Eliminar"),
        Row("保存", "Save", "保存", "儲存", "저장", "Guardar"),
        Row("セット", "Arm", "设置待机", "設定待機", "대기 설정", "Activar espera"),
        Row("リアルタイム翻訳（仮実装）。セットすると、次の通話で自動的に始まり、通話終了で解除されます。", "Real-time translation (experimental). Once armed, it starts with the next call and stops when the call ends.", "实时翻译（试验功能）。设置后会在下一次通话时自动启动，并在通话结束时关闭。", "即時翻譯（實驗功能）。設定後會在下一次通話時自動啟動，並在通話結束時關閉。", "실시간 번역(실험 기능). 설정하면 다음 통화에서 자동 시작되며 통화가 끝나면 해제됩니다.", "Traducción en tiempo real (experimental). Al activarla, comienza en la próxima llamada y se detiene al finalizar."),
        Row("自分の言語（相手の声をこの言語に翻訳して聞く。「翻訳しない」なら相手の声はそのまま）", "My language (translate the other person's voice into this language; choose Do not translate to hear the original)", "我的语言（将对方语音翻译成此语言；选择“不翻译”可听原声）", "我的語言（將對方語音翻譯成此語言；選擇「不翻譯」可聽原聲）", "내 언어(상대 음성을 이 언어로 번역해 듣기. 번역 안 함을 선택하면 원음 재생)", "Mi idioma (traduce la voz de la otra persona a este idioma; elige No traducir para oír la voz original)"),
        Row("相手の言語（自分の声をこの言語に翻訳して相手へ送る。「自動」なら相手の声から判定、「翻訳しない」なら自分の声はそのまま）", "Other party's language (translate your voice into this language; Auto detects it from their speech; Do not translate sends your original voice)", "对方的语言（将您的语音翻译成此语言；“自动”会根据对方语音判断；“不翻译”则发送原声）", "對方的語言（將您的語音翻譯成此語言；「自動」會依對方語音判斷；「不翻譯」則傳送原聲）", "상대방 언어(내 음성을 이 언어로 번역해 전달. 자동은 상대 음성으로 판단, 번역 안 함은 원음 전달)", "Idioma de la otra persona (traduce tu voz a este idioma; Automático lo detecta por su habla; No traducir envía tu voz original)"),
        Row("セット（次の通話で自動開始）", "Arm (start automatically on the next call)", "设置待机（下次通话时自动启动）", "設定待機（下次通話時自動啟動）", "대기 설정(다음 통화에서 자동 시작)", "Activar (iniciar automáticamente en la próxima llamada)"),
        Row("今すぐ開始（通話中のテスト）", "Start now (test during a call)", "立即开始（通话中测试）", "立即開始（通話中測試）", "지금 시작(통화 중 테스트)", "Iniciar ahora (prueba durante una llamada)"),
        Row("翻訳ログ（原文 → 訳）", "Translation log (original → translation)", "翻译日志（原文 → 译文）", "翻譯記錄（原文 → 譯文）", "번역 로그(원문 → 번역)", "Registro de traducción (original → traducción)"),
        Row("電話待機", "Call standby", "电话待机", "電話待機", "전화 대기", "Espera telefónica"),
        Row("困ったとき・注意", "Troubleshooting and notes", "故障排除与注意事项", "疑難排解與注意事項", "문제 해결 및 참고", "Solución de problemas y notas"),
        Row("閉じる", "Close", "关闭", "關閉", "닫기", "Cerrar"),
        Row("翻訳しない", "Do not translate", "不翻译", "不翻譯", "번역 안 함", "No traducir"),
        Row("自動（相手の言語を検出）", "Auto-detect the other party's language", "自动检测对方语言", "自動偵測對方語言", "상대방 언어 자동 감지", "Detectar automáticamente el idioma de la otra persona"),
        Row("相手", "Other party", "对方", "對方", "상대방", "Otra persona"),
        Row("先に Gemini API キーを設定してください", "Set your Gemini API key first", "请先设置 Gemini API 密钥", "請先設定 Gemini API 金鑰", "먼저 Gemini API 키를 설정하세요", "Configura primero tu clave de API de Gemini"),
        Row("AI通話中は試聴できません", "Voice preview is unavailable during an AI call", "AI 通话期间无法试听", "AI 通話期間無法試聽", "AI 통화 중에는 미리 들을 수 없습니다", "No se puede previsualizar durante una llamada con IA"),
        Row("ブラウザが見つかりません。\$URL_API_KEYS を開いてください", "No browser found. Open \$URL_API_KEYS manually.", "未找到浏览器。请手动打开 \$URL_API_KEYS。", "找不到瀏覽器。請手動開啟 \$URL_API_KEYS。", "브라우저를 찾을 수 없습니다. \$URL_API_KEYS 를 여세요.", "No se encontró un navegador. Abre \$URL_API_KEYS manualmente."),
        Row("設定の3項目をすべて ✓ にすると、他のタブを開けます", "Complete all three setup items (✓) to open the other tabs", "完成全部三项设置（✓）后即可打开其他页面", "完成全部三項設定（✓）後即可開啟其他頁面", "필수 설정 3개를 모두 완료(✓)하면 다른 탭을 열 수 있습니다", "Completa los tres ajustes (✓) para abrir las demás pestañas"),
        Row("AI通話を開始できません。設定タブの3項目を確認してください", "Could not start AI calling. Check the three items in Settings.", "无法启动 AI 通话。请检查设置页中的三项内容。", "無法啟動 AI 通話。請檢查設定頁中的三項內容。", "AI 통화를 시작할 수 없습니다. 설정 탭의 세 항목을 확인하세요.", "No se pudo iniciar la llamada con IA. Revisa los tres elementos de Ajustes."),
        Row("確認中…", "Checking…", "正在检查…", "正在檢查…", "확인 중…", "Comprobando…"),
        Row("未設定", "Not configured", "未设置", "未設定", "설정되지 않음", "Sin configurar"),
        Row("未確認: \$keyMsg", "Unverified: \$keyMsg", "未验证：\$keyMsg", "未驗證：\$keyMsg", "확인되지 않음: \$keyMsg", "Sin verificar: \$keyMsg"),
        Row("無効: \$keyMsg", "Invalid: \$keyMsg", "无效：\$keyMsg", "無效：\$keyMsg", "잘못됨: \$keyMsg", "No válida: \$keyMsg"),
        Row("「他のアプリの上に表示」を許可すると、通話中に戻るアイコンが出ます", "Allow display over other apps to show the return icon during calls", "允许“显示在其他应用上层”后，通话时会显示返回图标", "允許「顯示在其他應用程式上層」後，通話時會顯示返回圖示", "다른 앱 위에 표시를 허용하면 통화 중 돌아가기 아이콘이 나타납니다", "Permite mostrar sobre otras aplicaciones para ver el icono de volver durante las llamadas"),
        Row("電話・通知の権限OK", "Phone and notification permissions granted", "电话和通知权限已授予", "電話與通知權限已授予", "전화 및 알림 권한 허용됨", "Permisos de teléfono y notificaciones concedidos"),
        Row("権限が不足しています（通話自動検知に必要）", "Missing permissions (required to detect calls automatically)", "权限不足（自动检测通话所必需）", "權限不足（自動偵測通話所必需）", "권한이 부족합니다(통화 자동 감지에 필요)", "Faltan permisos (necesarios para detectar llamadas automáticamente)"),
        Row("マイクが許可されませんでした", "Microphone permission was denied", "未获准使用麦克风", "未獲准使用麥克風", "마이크 권한이 거부되었습니다", "Se denegó el permiso del micrófono"),
        Row("翻訳が始まると、ここに原文と訳が表示されます", "The original and translation will appear here when translation starts", "开始翻译后，原文和译文会显示在这里", "開始翻譯後，原文和譯文會顯示在這裡", "번역이 시작되면 원문과 번역문이 여기에 표시됩니다", "El original y la traducción aparecerán aquí cuando comience la traducción"),
        Row("準備", "Preparation", "准备", "準備", "준비", "Preparación"),
        Row("通話", "Call", "通话", "通話", "통화", "Llamada"),
        Row("翻訳", "Translation", "翻译", "翻譯", "번역", "Traducción"),
        Row("編集", "Edit", "编辑", "編輯", "편집", "Editar"),
        Row("キャンセル", "Cancel", "取消", "取消", "취소", "Cancelar"),
        Row("用件を削除", "Delete call brief", "删除通话事项", "刪除通話事項", "용건 삭제", "Eliminar motivo de llamada"),
        Row("「\$label」を削除しますか？", "Delete '\$label'?", "确定要删除“\$label”吗？", "確定要刪除「\$label」嗎？", "'\$label' 항목을 삭제할까요?", "¿Eliminar '\$label'?"),
        Row("設定を開く", "Open settings", "打开设置", "開啟設定", "설정 열기", "Abrir ajustes"),
        Row("重ねて表示の許可が必要です", "Permission to display over other apps is required", "需要允许显示在其他应用上层", "需要允許顯示在其他應用程式上層", "다른 앱 위에 표시 권한이 필요합니다", "Se necesita permiso para mostrar sobre otras aplicaciones"),
        Row("返答内容を入力してください", "Enter a reply", "请输入回复内容", "請輸入回覆內容", "답변 내용을 입력하세요", "Introduce una respuesta"),
        Row("AIが個人情報の開示許可を求めています", "The AI is asking permission to disclose personal information", "AI 正在请求您允许披露个人信息", "AI 正在請求您允許揭露個人資訊", "AI가 개인정보 공개 허가를 요청합니다", "La IA solicita permiso para divulgar información personal"),
        Row("(あと", "(waiting: ", "（还需处理", "（還需處理", "(대기: ", "(pendientes: "),
        Row("マイクの権限が許可されていません", "Microphone permission has not been granted", "尚未授予麦克风权限", "尚未授予麥克風權限", "마이크 권한이 허용되지 않았습니다", "No se ha concedido permiso para el micrófono"),
        Row("重ねて表示を自動許可しました", "Automatically granted display-over-apps permission", "已自动授予悬浮显示权限", "已自動授予懸浮顯示權限", "다른 앱 위에 표시 권한을 자동으로 허용했습니다", "Se concedió automáticamente el permiso para mostrarse sobre otras aplicaciones"),
        Row("自動許可の反映を確認できませんでした。設定画面で「他のアプリの上に表示」を許可してください。", "Could not verify the permission change. Please allow display over other apps in Settings.", "无法确认权限是否生效。请在设置中允许显示在其他应用上层。", "無法確認權限是否生效。請在設定中允許顯示在其他應用程式上層。", "권한 변경을 확인할 수 없습니다. 설정에서 다른 앱 위에 표시를 허용하세요.", "No se pudo verificar el cambio. Permite mostrarse sobre otras aplicaciones en Ajustes."),
        Row("重ねて表示を自動許可できませんでした。priv-kit の接続を確認するか、設定画面で許可してください。", "Could not grant the permission automatically. Check the priv-kit connection or allow it in Settings.", "无法自动授予悬浮显示权限。请检查 priv-kit 连接，或在设置中手动允许。", "無法自動授予懸浮顯示權限。請檢查 priv-kit 連線，或在設定中手動允許。", "권한을 자동으로 허용하지 못했습니다. priv-kit 연결을 확인하거나 설정에서 직접 허용하세요.", "No se pudo conceder el permiso automáticamente. Comprueba priv-kit o habilítalo en Ajustes."),
        Row("ブラウザが見つかりません。\$URL_API_KEYS を開いてください", "No browser found. Open \$URL_API_KEYS manually.", "未找到浏览器。请手动打开 \$URL_API_KEYS。", "找不到瀏覽器。請手動開啟 \$URL_API_KEYS。", "브라우저를 찾을 수 없습니다. \$URL_API_KEYS 를 여세요.", "No se encontró un navegador. Abre \$URL_API_KEYS manualmente."),
        Row("priv-kit 未接続のため AI 通話は動作しません。「設定」タブでワイヤレスデバッグを開始してください。", "AI calling will not work because priv-kit is not connected. Start wireless debugging in the Settings tab.", "由于 priv-kit 未连接，AI 通话无法工作。请在设置页启动无线调试。", "由於 priv-kit 未連線，AI 通話無法運作。請在設定頁啟動無線偵錯。", "priv-kit이 연결되지 않아 AI 통화가 작동하지 않습니다. 설정 탭에서 무선 디버깅을 시작하세요.", "Las llamadas con IA no funcionarán porque priv-kit no está conectado. Inicia la depuración inalámbrica en Ajustes."),
        Row("停止中…", "Stopping…", "正在停止…", "正在停止…", "중지 중…", "Deteniendo…"),
        Row("開始中…", "Starting…", "正在启动…", "正在啟動…", "시작 중…", "Iniciando…"),
        Row("停止", "Stop", "停止", "停止", "중지", "Detener"),
        Row("用件が空です。少なくとも用件を入れてください", "The call brief is empty. Enter at least the purpose.", "通话事项为空。请至少填写事项内容。", "通話事項為空。請至少填寫事項內容。", "용건이 비어 있습니다. 최소한 용건을 입력하세요.", "El motivo está vacío. Introduce al menos el propósito."),
        Row("発信先や用件を書いて送信してください。Gemma が質問をしながら整理します。「まとめる」で項目に分け、編集してからセットできます。", "Enter the recipient and purpose, then send. Gemma will ask questions to organize the details. Use Organize to split them into fields, edit them, and then activate the brief.", "填写拨打对象和事项后发送。Gemma 会通过提问整理信息。点击“整理”可将内容拆分成字段，编辑后再设置。", "填寫撥打對象和事項後傳送。Gemma 會透過提問整理資訊。點選「整理」可將內容拆分成欄位，編輯後再設定。", "전화 상대와 용건을 입력해 보내세요. Gemma가 질문하면서 내용을 정리합니다. ‘정리’를 눌러 항목별로 나누고 편집한 뒤 설정할 수 있습니다.", "Introduce el destinatario y el motivo y envíalos. Gemma organizará los detalles mediante preguntas. Usa Organizar para dividirlos en campos, editarlos y activar el motivo."),
        Row("先に設定タブで API キーを保存してください", "Save the API key in Settings first", "请先在设置页保存 API 密钥", "請先在設定頁儲存 API 金鑰", "먼저 설정 탭에서 API 키를 저장하세요", "Guarda primero la clave API en Ajustes"),
        Row("先に用件を送信してください", "Send the call purpose first", "请先发送通话事项", "請先傳送通話事項", "먼저 용건을 보내세요", "Envía primero el motivo de la llamada"),
        Row("内容が空です。少なくとも用件を入れてください", "The content is empty. Enter at least the purpose.", "内容为空。请至少填写事项。", "內容為空。請至少填寫事項。", "내용이 비어 있습니다. 최소한 용건을 입력하세요.", "El contenido está vacío. Introduce al menos el motivo."),
        Row("セットしました: \$label。右端に「電話待機」が出ます。通話アプリで発信してください", "Activated: \$label. ‘Call standby’ appears on the right. Place the call in your phone app.", "已设置：\$label。右侧会显示“电话待机”。请在电话应用中拨出电话。", "已設定：\$label。右側會顯示「電話待機」。請在電話應用程式中撥出電話。", "설정됨: \$label. 오른쪽에 ‘전화 대기’가 표시됩니다. 전화 앱에서 발신하세요.", "Activado: \$label. A la derecha aparecerá ‘Espera telefónica’. Llama desde tu aplicación de teléfono."),
        Row("削除する用件がありません", "There is no call brief to delete", "没有可删除的通话事项", "沒有可刪除的通話事項", "삭제할 용건이 없습니다", "No hay ningún motivo de llamada para eliminar"),
        Row("内容を確認・編集して「保存」または「セット」を押してください", "Review and edit the content, then tap Save or Arm.", "请检查并编辑内容，然后点击“保存”或“设置待机”。", "請檢查並編輯內容，然後點選「儲存」或「設定待機」。", "내용을 확인하고 편집한 다음 저장 또는 대기 설정을 누르세요.", "Revisa y edita el contenido y luego pulsa Guardar o Activar."),
        Row("1. 開発者向けオプションをオンにする", "1. Enable Developer options", "1. 启用开发者选项", "1. 啟用開發人員選項", "1. 개발자 옵션 켜기", "1. Activar las opciones de desarrollador"),
        Row("端末の「設定」→「デバイス情報(端末情報)」を開き、「ビルド番号」を7回続けてタップします。画面ロックの認証を求められたら入力します。すでにオンなら、次へ進んでください。下のボタンで端末情報の画面が開き、「ビルド番号」が強調表示されます。(機種によって項目名や場所が違い、強調されないことがあります。Galaxy などでは「ソフトウェア情報」の中にあります)", "On your device, open Settings → About device, then tap Build number seven times. Authenticate if asked. If Developer options are already enabled, continue. The button below opens the About device screen and highlights Build number. Names and locations vary by device; on some Galaxy phones it is under Software information.", "在设备上打开“设置”→“关于手机”，连续点按“版本号”七次。如要求验证锁屏，请完成验证。如果已启用开发者选项，请继续。点击下方按钮可打开设备信息页面并突出显示版本号。不同机型的名称和位置可能不同；部分 Galaxy 设备中位于“软件信息”。", "裝置上開啟「設定」→「關於手機」，連續點選「版本號碼」七次。如要求驗證螢幕鎖定，請完成驗證。若已啟用開發人員選項，請繼續。下方按鈕會開啟裝置資訊頁並標示版本號碼。不同機型的名稱和位置可能不同；部分 Galaxy 裝置位於「軟體資訊」。", "기기에서 설정 → 기기 정보로 이동한 뒤 빌드 번호를 7번 연속 탭하세요. 화면 잠금 인증이 나오면 입력합니다. 이미 켜져 있다면 다음 단계로 이동하세요. 아래 버튼을 누르면 기기 정보 화면이 열리고 빌드 번호가 강조됩니다. 기종에 따라 메뉴 이름과 위치가 다르며 Galaxy에서는 소프트웨어 정보 안에 있을 수 있습니다.", "En el dispositivo, abre Ajustes → Información del dispositivo y pulsa Número de compilación siete veces. Autentícate si se solicita. Si las opciones de desarrollador ya están activadas, continúa. El botón abre la pantalla de información y resalta el número de compilación. La ubicación varía según el dispositivo; en algunos Galaxy está en Información de software."),
        Row("ビルド番号の画面を開く", "Open Build number screen", "打开版本号页面", "開啟版本號碼頁面", "빌드 번호 화면 열기", "Abrir la pantalla de número de compilación"),
        Row("2. Wi-Fiにつなぐ", "2. Connect to Wi-Fi", "2. 连接 Wi-Fi", "2. 連線 Wi-Fi", "2. Wi-Fi 연결", "2. Conectarse a Wi-Fi"),
        Row("ワイヤレスデバッグは、Wi-Fiにつながっているときだけ使えます。通話に使う回線とは別に、Wi-Fiへ接続しておいてください。", "Wireless debugging works only while connected to Wi-Fi. Connect to Wi-Fi separately from the mobile network used for calls.", "无线调试仅在连接 Wi-Fi 时可用。请在用于通话的移动网络之外连接 Wi-Fi。", "無線偵錯僅在連線 Wi-Fi 時可用。請在用於通話的行動網路之外連線 Wi-Fi。", "무선 디버깅은 Wi-Fi에 연결되어 있을 때만 작동합니다. 통화용 모바일 네트워크와 별도로 Wi-Fi에 연결하세요.", "La depuración inalámbrica solo funciona con Wi-Fi. Conéctate a una red Wi-Fi aparte de la red móvil usada para llamar."),
        Row("3. ワイヤレスデバッグをオンにする", "3. Enable wireless debugging", "3. 启用无线调试", "3. 啟用無線偵錯", "3. 무선 디버깅 켜기", "3. Activar la depuración inalámbrica"),
        Row("下のボタンを押すと、通知の許可を聞かれたら「許可」し、開発者向けオプション(ワイヤレスデバッグ)の画面が開きます。「ワイヤレスデバッグ」のスイッチをオンにします。「このネットワークでのワイヤレスデバッグを常に許可しますか？」と出たら「許可」します。(設定タブの priv-kit 接続の「設定」ボタンと同じ動作です)", "Press the button below and allow notifications if prompted. The Developer options screen opens at Wireless debugging. Turn it on. If asked whether to always allow wireless debugging on this network, choose Allow. This is the same action as the Settings button in the priv-kit connection row.", "点击下方按钮；如出现通知权限请求，请选择“允许”。随后会打开开发者选项中的无线调试页面。开启“无线调试”。如果询问是否始终允许此网络进行无线调试，请选择“允许”。这与设置页 priv-kit 连接行中的“设置”按钮执行相同操作。", "下方按鈕を按下後，如要求通知權限請選擇「允許」。接著會開啟開發人員選項中的無線偵錯頁面。請開啟「無線偵錯」。若詢問是否一律允許此網路進行無線偵錯，請選擇「允許」。這與設定頁 priv-kit 連線列中的「設定」按鈕相同。", "아래 버튼을 누르고 알림 권한 요청이 나오면 허용하세요. 개발자 옵션의 무선 디버깅 화면이 열립니다. 무선 디버깅을 켜고 이 네트워크에서 항상 허용할지 물으면 허용을 선택하세요. 설정 탭의 priv-kit 연결 행에 있는 설정 버튼과 같은 동작입니다.", "Pulsa el botón y permite las notificaciones si se solicita. Se abrirá la pantalla de depuración inalámbrica en Opciones de desarrollador. Actívala. Si pregunta si se permite siempre en esta red, selecciona Permitir. Es la misma acción que el botón Ajustes de la fila de conexión priv-kit."),
        Row("ワイヤレスデバッグの設定を開く", "Open wireless debugging settings", "打开无线调试设置", "開啟無線偵錯設定", "무선 디버깅 설정 열기", "Abrir ajustes de depuración inalámbrica"),
        Row("4. ペア設定コードを表示する", "4. Show the pairing code", "4. 显示配对码", "4. 顯示配對碼", "4. 페어링 코드 표시", "4. Mostrar el código de emparejamiento"),
        Row("「ワイヤレスデバッグ」の項目をタップして詳細を開き、「ペア設定コードによるデバイスのペア設定」をタップします。6桁のコードが表示されます。この画面は閉じずに、そのままにしてください(閉じるとコードが無効になります)。", "Tap Wireless debugging to open its details, then tap Pair device with pairing code. A six-digit code appears. Keep this screen open; closing it invalidates the code.", "点按“无线调试”打开详情，再点按“使用配对码配对设备”。屏幕会显示六位数代码。请保持此页面打开，关闭后代码会失效。", "點選「無線偵錯」開啟詳細資訊，再點選「使用配對碼配對裝置」。畫面會顯示六位數代碼。請保持此頁面開啟，關閉後代碼會失效。", "무선 디버깅 항목을 눌러 상세 화면을 열고 페어링 코드로 기기 페어링을 선택합니다. 6자리 코드가 표시됩니다. 화면을 닫으면 코드가 무효가 되므로 그대로 열어 두세요.", "Pulsa Depuración inalámbrica para abrir los detalles y luego Emparejar dispositivo con código. Aparecerá un código de seis dígitos. Mantén esta pantalla abierta; si la cierras, el código dejará de ser válido."),
        Row("5. 通知にコードを入力する", "5. Enter the code in the notification", "5. 在通知中输入代码", "5. 在通知中輸入代碼", "5. 알림에 코드 입력", "5. Introducir el código en la notificación"),
        Row("画面の上に「ペアリングコードを入力」という通知が出ます。通知の入力欄に6桁のコードを入力して、送信します。通知が出ないときは、通知バーを下に引いて確認してください。それでも出ないときは、このアプリの通知が許可されているか確認します。", "A notification titled Enter pairing code appears. Enter the six-digit code in its input field and submit it. If it does not appear, pull down the notification shade. If still missing, check that notifications are allowed for this app.", "屏幕顶部会出现“输入配对码”通知。在通知输入框中填写六位代码并发送。如果没有出现通知，请下拉通知栏查看；如果仍没有，请确认此应用已获准发送通知。", "螢幕頂端會出現「輸入配對碼」通知。在通知輸入欄填入六位代碼並傳送。若未出現通知，請下拉通知欄查看；若仍沒有，請確認此應用程式已獲准傳送通知。", "화면 상단에 페어링 코드 입력 알림이 나타납니다. 알림 입력란에 6자리 코드를 입력하고 전송하세요. 알림이 보이지 않으면 알림 창을 내려 확인하고, 그래도 없으면 앱 알림이 허용되어 있는지 확인하세요.", "Aparecerá una notificación para introducir el código de emparejamiento. Escribe y envía el código de seis dígitos. Si no aparece, despliega el panel de notificaciones. Si sigue sin aparecer, comprueba que las notificaciones de esta aplicación estén permitidas."),
        Row("6. ✓ になったら完了", "6. Finish when the check mark appears", "6. 出现 ✓ 后即完成", "6. 出現 ✓ 後即完成", "6. 체크 표시가 나타나면 완료", "6. Terminar cuando aparezca la marca de verificación"),
        Row("設定タブの priv-kit 接続が「✓ priv-kit 接続済み」になれば完了です。「ブリッジ未接続(自動接続中…)」と出ているときは、しばらく待ってください。", "You are done when the priv-kit connection row in Settings shows Connected. If it says Bridge disconnected (connecting automatically…), wait a little.", "当设置页的 priv-kit 连接显示“✓ 已连接”时即完成。如果显示“桥接未连接（正在自动连接…）”，请稍等片刻。", "當設定頁的 priv-kit 連線顯示「✓ 已連線」時即完成。若顯示「橋接未連線（正在自動連線…）」，請稍候。", "설정 탭의 priv-kit 연결이 연결됨으로 표시되면 완료입니다. 브리지 연결 안 됨(자동 연결 중…)이라고 표시되면 잠시 기다리세요.", "Termina cuando la conexión priv-kit indique que está conectada. Si aparece Puente desconectado (conectando automáticamente…), espera un momento."),
        Row("端末を再起動したり、Wi-Fiを切り替えたりすると、ワイヤレスデバッグがオフになることがあります。その場合は手順3からやり直してください。", "Restarting the device or switching Wi-Fi may turn wireless debugging off. If so, repeat from step 3.", "重启设备或切换 Wi-Fi 可能会关闭无线调试。如发生这种情况，请从步骤 3 重新操作。", "重新啟動裝置或切換 Wi-Fi 可能會關閉無線偵錯。如發生此情況，請從步驟 3 重新操作。", "기기를 재시작하거나 Wi-Fi를 전환하면 무선 디버깅이 꺼질 수 있습니다. 이 경우 3단계부터 다시 진행하세요.", "Reiniciar el dispositivo o cambiar de Wi-Fi puede desactivar la depuración inalámbrica. Si ocurre, repite desde el paso 3."),
        Row("コードが違う、または時間切れで失敗したときは、手順4でコードを表示し直して、もう一度入力します。", "If the code is incorrect or expires, display a new code at step 4 and enter it again.", "如果代码错误或超时失败，请在步骤 4 重新显示代码并再次输入。", "如果代碼錯誤或逾時失敗，請在步驟 4 重新顯示代碼並再次輸入。", "코드가 틀리거나 시간이 초과되면 4단계에서 새 코드를 표시하고 다시 입력하세요.", "Si el código es incorrecto o caduca, vuelve al paso 4, genera otro y escríbelo de nuevo."),
        Row("「重ねて表示」の設定方法", "How to enable display over other apps", "如何启用悬浮显示", "如何啟用懸浮顯示", "다른 앱 위에 표시 설정 방법", "Cómo permitir mostrar sobre otras aplicaciones"),
        Row("通話中に、このアプリへ戻るためのアイコンを他の画面の上に出すために必要な許可です。", "This permission displays an icon over other screens so you can return to this app during a call.", "此权限用于在通话时于其他屏幕上显示图标，以便返回本应用。", "此權限用於通話時在其他畫面上顯示圖示，以便返回本應用程式。", "통화 중 다른 화면 위에 이 앱으로 돌아오는 아이콘을 표시하는 데 필요한 권한입니다.", "Este permiso muestra un icono sobre otras pantallas para volver a esta aplicación durante una llamada."),
        Row("1. 「設定」ボタンをタップ", "1. Tap the Settings button", "1. 点按“设置”按钮", "1. 點選「設定」按鈕", "1. 설정 버튼 누르기", "1. Pulsa el botón Ajustes"),
        Row("設定タブの「重ねて表示(戻るアイコン)」の行にある「設定」を押します。このアプリの「他のアプリの上に重ねて表示」の画面が開きます。", "Tap Settings in the Display over other apps (return icon) row on the Settings tab. The system page for allowing this app to appear on top opens.", "在设置页的“悬浮显示（返回图标）”一行中点按“设置”。系统会打开允许本应用显示在其他应用上层的页面。", "在設定頁的「懸浮顯示（返回圖示）」列中點選「設定」。系統會開啟允許本應用程式顯示在其他應用程式上層的頁面。", "설정 탭의 다른 앱 위에 표시(돌아가기 아이콘) 행에서 설정을 누릅니다. 이 앱의 다른 앱 위에 표시 권한 화면이 열립니다.", "Pulsa Ajustes en la fila Mostrar sobre otras aplicaciones (icono para volver) de la pestaña Ajustes. Se abrirá la pantalla del sistema para permitir que esta app aparezca encima."),
        Row("2. スイッチをオンにする", "2. Turn on the switch", "2. 打开开关", "2. 開啟開關", "2. 스위치 켜기", "2. Activar el interruptor"),
        Row("「他のアプリの上に重ねて表示を許可」(表記は機種で少し違います)のスイッチをオンにします。", "Turn on the Allow display over other apps switch. The exact wording may vary by device.", "打开“允许显示在其他应用上层”开关。不同设备的文字可能略有不同。", "開啟「允許顯示在其他應用程式上層」開關。不同裝置的文字可能略有不同。", "다른 앱 위에 표시 허용 스위치를 켭니다. 기기에 따라 표시 문구가 다를 수 있습니다.", "Activa el interruptor Permitir mostrar sobre otras aplicaciones. El texto puede variar según el dispositivo."),
        Row("3. アプリに戻って確認する", "3. Return to the app and verify", "3. 返回应用并确认", "3. 返回應用程式並確認", "3. 앱으로 돌아와 확인", "3. Vuelve a la aplicación y comprueba"),
        Row("戻るボタンでこのアプリに戻り、「✓ 許可済み」になっていれば完了です。", "Return to this app. It is complete when the status shows Permission granted.", "返回本应用，状态显示“✓ 已授权”即完成。", "返回本應用程式，狀態顯示「✓ 已授權」即完成。", "앱으로 돌아와 권한 허용됨으로 표시되면 완료입니다.", "Vuelve a esta aplicación. El proceso termina cuando el estado indique Permiso concedido."),
        Row("ボタンから画面が開けないときは、端末の「設定」→「アプリ」→「Aiphone」→「他のアプリの上に重ねて表示」(「特別なアプリアクセス」の中にある場合もあります)から設定できます。", "If the button cannot open the page, go to device Settings → Apps → Aiphone → Display over other apps. It may be under Special app access.", "如果按钮无法打开页面，请前往设备设置 → 应用 → Aiphone → 显示在其他应用上层。有些设备会将其放在“特殊应用权限”中。", "如果按鈕無法開啟頁面，請前往裝置設定 → 應用程式 → Aiphone → 顯示在其他應用程式上層。有些裝置會將其放在「特殊應用程式存取權」中。", "버튼으로 페이지를 열 수 없다면 기기 설정 → 앱 → Aiphone → 다른 앱 위에 표시로 이동하세요. 일부 기기에서는 특별한 앱 접근 권한 아래에 있습니다.", "Si el botón no abre la página, ve a Ajustes → Aplicaciones → Aiphone → Mostrar sobre otras aplicaciones. En algunos dispositivos está en Acceso especial de aplicaciones."),
        Row("Gemini API キーの設定方法", "How to set up a Gemini API key", "如何设置 Gemini API 密钥", "如何設定 Gemini API 金鑰", "Gemini API 키 설정 방법", "Cómo configurar una clave API de Gemini"),
        Row("AIが電話で話すために、Google の Gemini API キーが必要です。キーは Google AI Studio で作成して、このアプリに貼り付けます。", "The app needs a Google Gemini API key so the AI can speak during calls. Create the key in Google AI Studio and paste it into this app.", "AI 需要 Google Gemini API 密钥才能在通话中发言。请在 Google AI Studio 中创建密钥，然后粘贴到本应用。", "AI 需要 Google Gemini API 金鑰才能在通話中發言。請在 Google AI Studio 中建立金鑰，然後貼到本應用程式。", "AI가 통화 중 말하려면 Google Gemini API 키가 필요합니다. Google AI Studio에서 키를 만든 뒤 이 앱에 붙여넣으세요.", "La IA necesita una clave API de Gemini de Google para hablar durante las llamadas. Créala en Google AI Studio y pégala en esta aplicación."),
        Row("設定タブの Gemini API キーの行にある「設定」ボタンです。標準のブラウザで Google AI Studio の API キーのページが開きます。", "Tap the Settings button in the Gemini API key row. Your default browser opens the API keys page in Google AI Studio.", "点按设置页 Gemini API 密钥一行中的“设置”按钮。默认浏览器会打开 Google AI Studio 的 API 密钥页面。", "點選設定頁 Gemini API 金鑰列中的「設定」按鈕。預設瀏覽器會開啟 Google AI Studio 的 API 金鑰頁面。", "설정 탭의 Gemini API 키 행에서 설정을 누르세요. 기본 브라우저에서 Google AI Studio의 API 키 페이지가 열립니다.", "Pulsa Ajustes en la fila de clave API de Gemini. El navegador predeterminado abrirá la página de claves API de Google AI Studio."),
        Row("2. Google アカウントでログイン", "2. Sign in with your Google account", "2. 使用 Google 账号登录", "2. 使用 Google 帳戶登入", "2. Google 계정으로 로그인", "2. Inicia sesión con tu cuenta de Google"),
        Row("ログインを求められたら、使いたい Google アカウントでログインします。利用規約への同意が出たら、内容を確認して進みます。", "If prompted, sign in with the Google account you want to use. Review and accept the terms if requested.", "如果要求登录，请使用想要使用的 Google 账号登录。如出现服务条款，请阅读并同意后继续。", "如果要求登入，請使用想使用的 Google 帳戶登入。如出現服務條款，請閱讀並同意後繼續。", "로그인 요청이 나타나면 사용할 Google 계정으로 로그인하세요. 이용약관 동의가 나오면 확인 후 진행하세요.", "Si se solicita, inicia sesión con la cuenta de Google que quieras usar. Revisa y acepta las condiciones si aparecen."),
        Row("3. APIキーを作成する", "3. Create an API key", "3. 创建 API 密钥", "3. 建立 API 金鑰", "3. API 키 만들기", "3. Crear una clave API"),
        Row("「APIキーを作成」(Create API key)を押します。プロジェクトを聞かれたら、既存のものを選ぶか新しく作ります。", "Select Create API key. If asked to choose a project, select an existing one or create a new one.", "点击“创建 API 密钥”（Create API key）。如果要求选择项目，请选择现有项目或新建项目。", "點選「建立 API 金鑰」（Create API key）。如果要求選擇專案，請選擇現有專案或建立新專案。", "Create API key를 선택하세요. 프로젝트를 선택하라는 메시지가 나오면 기존 프로젝트를 선택하거나 새로 만드세요.", "Selecciona Create API key. Si te pide un proyecto, elige uno existente o crea uno nuevo."),
        Row("4. キーをコピーする", "4. Copy the key", "4. 复制密钥", "4. 複製金鑰", "4. 키 복사", "4. Copiar la clave"),
        Row("作成されたキーの横にあるコピーボタンで、キーをコピーします。キーは「AQ.」または「AIza」から始まる長い文字列です。", "Use the copy button next to the generated key. It is a long string starting with AQ. or AIza.", "点击生成的密钥旁边的复制按钮。密钥是一串以 AQ. 或 AIza 开头的长字符串。", "點選產生的金鑰旁邊的複製按鈕。金鑰是一串以 AQ. 或 AIza 開頭的長字串。", "생성된 키 옆의 복사 버튼을 누르세요. 키는 AQ. 또는 AIza로 시작하는 긴 문자열입니다.", "Pulsa el botón de copiar junto a la clave generada. Es una cadena larga que comienza por AQ. o AIza."),
        Row("5. このアプリに貼り付ける", "5. Paste it into this app", "5. 粘贴到本应用", "5. 貼到本應用程式", "5. 이 앱에 붙여넣기", "5. Pegarla en esta aplicación"),
        Row("このアプリに戻り、Gemini API キーの入力欄にキーを貼り付けます。貼り付けると、自動で接続確認が始まります。", "Return to this app and paste the key into the Gemini API key field. The connection check starts automatically.", "返回本应用，将密钥粘贴到 Gemini API 密钥输入框中。粘贴后会自动开始连接检查。", "返回本應用程式，將金鑰貼到 Gemini API 金鑰輸入欄。貼上後會自動開始連線檢查。", "이 앱으로 돌아와 Gemini API 키 입력란에 키를 붙여넣으세요. 붙여넣으면 연결 확인이 자동으로 시작됩니다.", "Vuelve a esta aplicación y pega la clave en el campo de Gemini API. La comprobación de conexión comenzará automáticamente."),
        Row("「✓ Gemini に接続して確認済み」と出れば完了です。✗ のときは、表示された理由を確認して、キーを貼り直してください。", "Setup is complete when it says Gemini connected and verified. If you see an X, check the reason and paste the key again.", "显示“✓ 已连接并验证 Gemini”即完成。如果显示 ✗，请查看原因并重新粘贴密钥。", "顯示「✓ 已連線並驗證 Gemini」即完成。如果顯示 ✗，請查看原因並重新貼上金鑰。", "Gemini 연결 및 확인 완료라고 표시되면 끝입니다. X가 나타나면 이유를 확인하고 키를 다시 붙여넣으세요.", "La configuración termina cuando indica Gemini conectado y verificado. Si aparece una X, revisa el motivo y vuelve a pegar la clave."),
        Row("キーは他の人に見せたり、ネットに公開したりしないでください。", "Do not show your key to others or publish it online.", "请勿向他人展示密钥，也不要在网上公开。", "請勿向他人展示金鑰，也不要在網路上公開。", "키를 다른 사람에게 보여주거나 인터넷에 공개하지 마세요.", "No muestres la clave a otras personas ni la publiques en Internet."),
        Row("利用できる量や料金は Google 側の設定に従います。心配なときは、Google AI Studio の画面で確認してください。", "Usage limits and charges depend on Google's settings. Check Google AI Studio if you have questions.", "可用额度和费用取决于 Google 的设置。如有疑问，请在 Google AI Studio 中确认。", "可用額度與費用取決於 Google 的設定。如有疑問，請在 Google AI Studio 中確認。", "사용량 한도와 요금은 Google 설정에 따릅니다. 궁금한 점은 Google AI Studio에서 확인하세요.", "Los límites de uso y los cargos dependen de la configuración de Google. Consulta Google AI Studio si tienes dudas."),
        Row("AIの声を通話に流すため、このアプリはワイヤレスデバッグ(priv-kit)を使います。最初の1回だけペアリングが必要です。Wi-Fiにつないだ状態で行ってください。", "This app uses wireless debugging (priv-kit) to route AI audio into phone calls. Pairing is required only once. Connect to Wi-Fi before starting.", "为了将 AI 的声音传入电话通话，本应用使用无线调试（priv-kit）。只需首次配对一次。请先连接 Wi-Fi。", "為了將 AI 的聲音傳入電話通話，本應用程式使用無線偵錯（priv-kit）。只需首次配對一次。請先連線 Wi-Fi。", "AI 음성을 통화로 보내기 위해 이 앱은 무선 디버깅(priv-kit)을 사용합니다. 페어링은 처음 한 번만 필요합니다. 먼저 Wi-Fi에 연결하세요.", "Esta aplicación usa la depuración inalámbrica (priv-kit) para enviar el audio de la IA a las llamadas. El emparejamiento solo es necesario una vez. Conéctate a Wi-Fi antes de empezar."),
        Row("Gemma 応答待ち…", "Waiting for Gemma…", "正在等待 Gemma 回复…", "正在等待 Gemma 回覆…", "Gemma 응답 대기 중…", "Esperando a Gemma…"),
        Row("セットしました。次の通話で翻訳を自動開始します", "Armed. Translation will start automatically on the next call.", "已设置待机。下次通话时将自动开始翻译。", "已設定待機。下次通話時將自動開始翻譯。", "대기 설정됨. 다음 통화에서 번역이 자동으로 시작됩니다.", "Activado. La traducción comenzará automáticamente en la próxima llamada."),
        Row("priv-kit 接続の設定方法", "How to set up the priv-kit connection", "如何设置 priv-kit 连接", "如何設定 priv-kit 連線", "priv-kit 연결 설정 방법", "Cómo configurar la conexión priv-kit"),
        Row("履歴をクリアしました", "History cleared", "已清除记录", "已清除紀錄", "기록을 지웠습니다", "Historial borrado"),
        Row("セットを解除しました（一覧には残っています）", "Disarmed (the item remains in the list)", "已取消待机（项目仍保留在列表中）", "已取消待機（項目仍保留在清單中）", "대기 해제됨(목록에는 유지됨)", "Desactivado (el elemento permanece en la lista)"),
        Row("応答完了", "Response complete", "回复完成", "回覆完成", "응답 완료", "Respuesta completada"),
        Row("まとめ待ち…", "Waiting for summary…", "等待整理…", "等待整理…", "요약 대기 중…", "Esperando el resumen…"),
        Row("用件をまとめています…", "Organizing call details…", "正在整理通话事项…", "正在整理通話事項…", "통화 용건 정리 중…", "Organizando los detalles de la llamada…"),
        Row("先に AI 通話を停止してください", "Stop the AI call first", "请先停止 AI 通话", "請先停止 AI 通話", "먼저 AI 통화를 중지하세요", "Detén primero la llamada con IA"),
        Row("Gemini API キーが未設定です", "Gemini API key is not set", "尚未设置 Gemini API 密钥", "尚未設定 Gemini API 金鑰", "Gemini API 키가 설정되지 않았습니다", "La clave API de Gemini no está configurada"),
        Row("開始できません（priv-kit の接続・楽天リンクのインストールを確認）", "Cannot start. Check the priv-kit connection and that Rakuten Link is installed.", "无法启动。请检查 priv-kit 连接并确认已安装 Rakuten Link。", "無法啟動。請檢查 priv-kit 連線並確認已安裝 Rakuten Link。", "시작할 수 없습니다. priv-kit 연결과 Rakuten Link 설치를 확인하세요.", "No se puede iniciar. Comprueba la conexión priv-kit y que Rakuten Link esté instalado."),
        Row("どちらかの言語を「翻訳しない」以外にしてください", "Choose a language other than Do not translate for at least one direction", "请至少将一个方向设为“不翻译”以外的语言", "請至少將一個方向設為「不翻譯」以外的語言", "한 방향의 언어를 번역 안 함 이외로 설정하세요", "Selecciona un idioma distinto de No traducir en al menos una dirección"),
        Row("自分の言語と相手の言語が同じです。別の言語を選んでください", "Your language and the other party's language are the same. Choose different languages.", "您的语言与对方语言相同。请选择不同的语言。", "您的語言與對方語言相同。請選擇不同的語言。", "내 언어와 상대방 언어가 같습니다. 다른 언어를 선택하세요.", "Tu idioma y el de la otra persona son iguales. Elige idiomas diferentes."),
        Row("翻訳セット", "Translation armed", "已设置翻译待机", "已設定翻譯待機", "번역 대기 설정됨", "Traducción activada"),
        Row("相手→自分:", "Other party → me:", "对方 → 我：", "對方 → 我：", "상대방 → 나:", "Otra persona → yo:"),
        Row("自分→相手:", "Me → other party:", "我 → 对方：", "我 → 對方：", "나 → 상대방:", "Yo → otra persona:"),
        Row("（通話終了で解除・一覧には残る）", "(disarms when the call ends; remains in the list)", "（通话结束后取消待机；仍保留在列表中）", "（通話結束後取消待機；仍保留在清單中）", "(통화 종료 시 해제되며 목록에는 유지됨)", "(se desactiva al terminar la llamada; permanece en la lista)"),
        Row("あなた", "You", "您", "您", "나", "Tú"),
        Row("マイク許可OK（翻訳のアプリ側マイク用）", "Microphone permission granted (for app-side translation input)", "麦克风权限已允许（用于应用侧翻译输入）", "麥克風權限已允許（用於應用程式端翻譯輸入）", "마이크 권한 허용됨(앱 측 번역 입력용)", "Permiso de micrófono concedido (para la entrada de traducción de la aplicación)"),
        Row("保存済み個人情報の一部を安全に読み込み・移行できません。元データを空欄で上書きしないよう、該当欄の再入力まで保存を保留します。", "Some saved personal information could not be safely read or migrated. Saving is paused until you re-enter the affected field, so a blank field does not overwrite the original data.", "部分已保存的个人信息无法安全读取或迁移。为避免空白字段覆盖原始数据，请重新填写受影响的字段后再保存。", "部分已儲存的個人資訊無法安全讀取或遷移。為避免空白欄位覆蓋原始資料，請重新填寫受影響的欄位後再儲存。", "저장된 개인 정보 일부를 안전하게 읽거나 이전하지 못했습니다. 빈 입력란이 원본 데이터를 덮어쓰지 않도록 해당 항목을 다시 입력할 때까지 저장을 보류합니다.", "No se pudieron leer o migrar de forma segura algunos datos personales guardados. El guardado se pausa hasta que vuelva a introducir el campo afectado para evitar que un campo vacío sobrescriba los datos originales."),
        Row("個人情報を安全に保存できませんでした。既存データを保護するため保存を中止しました。", "Personal information could not be stored securely. Saving was cancelled to protect existing data.", "无法安全保存个人信息。为保护现有数据，已取消保存。", "無法安全儲存個人資訊。為保護現有資料，已取消儲存。", "개인 정보를 안전하게 저장하지 못했습니다. 기존 데이터를 보호하기 위해 저장을 취소했습니다.", "No se pudieron guardar los datos personales de forma segura. Se canceló el guardado para proteger los datos existentes."),
        Row("APIキーを安全に暗号化できなかったため保存しませんでした。", "The API key was not saved because it could not be encrypted securely.", "由于无法安全加密 API 密钥，因此未保存。", "由於無法安全加密 API 金鑰，因此未儲存。", "API 키를 안전하게 암호화할 수 없어 저장하지 않았습니다.", "La clave API no se guardó porque no se pudo cifrar de forma segura."),
        Row("旧バージョンのAPIキーを暗号化できませんでした。キーの再保存を試してください。", "The API key from an older version could not be encrypted. Try saving the key again.", "无法加密旧版本中的 API 密钥。请尝试重新保存该密钥。", "無法加密舊版本中的 API 金鑰。請嘗試重新儲存該金鑰。", "이전 버전의 API 키를 암호화하지 못했습니다. 키를 다시 저장해 보세요.", "No se pudo cifrar la clave API de una versión anterior. Intente guardar la clave de nuevo."),
        Row("保存済みAPIキーを復号できません。新しいキーを入力してください", "The saved API key could not be decrypted. Enter the key again.", "无法解密已保存的 API 密钥。请重新输入密钥。", "無法解密已儲存的 API 金鑰。請重新輸入金鑰。", "저장된 API 키를 복호화할 수 없습니다. 키를 다시 입력하세요.", "No se pudo descifrar la clave API guardada. Introduzca la clave de nuevo."),
        Row("保存済みAPIキーを復号できません。既存データを保護するため自動で上書きしません。", "The saved API key could not be decrypted. It will not be overwritten automatically, to protect the existing data.", "无法解密已保存的 API 密钥。为保护现有数据，不会自动覆盖。", "無法解密已儲存的 API 金鑰。為保護既有資料，不會自動覆寫。", "저장된 API 키를 복호화할 수 없습니다. 기존 데이터를 보호하기 위해 자동으로 덮어쓰지 않습니다.", "No se pudo descifrar la clave API guardada. No se sobrescribirá automáticamente para proteger los datos existentes."),
        Row("回答を送れませんでした。接続を確認して再試行してください", "Could not send the answer. Check the connection and try again.", "无法发送回答。请检查连接后重试。", "無法傳送回答。請檢查連線後再試一次。", "답변을 보내지 못했습니다. 연결을 확인한 뒤 다시 시도하세요.", "No se pudo enviar la respuesta. Compruebe la conexión e inténtelo de nuevo."),
    )

    private val dictionaries: Map<String, Map<String, String>> = run {
        val codes = listOf("en", "zh-CN", "zh-TW", "ko", "es")
        val indexes = mapOf("en" to 1, "zh-CN" to 2, "zh-TW" to 3, "ko" to 4, "es" to 5)
        codes.associateWith { code -> rows.associate { row ->
            row.ja to listOf(row.en, row.zhCN, row.zhTW, row.ko, row.es)[indexes.getValue(code) - 1]
        } }
    }

    /** Stable pieces used in dynamically composed status lines. Exact static labels use rows above. */
    private val commonFragments: Map<String, Map<String, String>> = mapOf(
        "en" to linkedMapOf(
            "通話中ですが AI は動作していません。" to "AI is not active during the call. ",
            "AI通話は動作しません。" to "AI calling is not working. ",
            "priv-kit が起動していません。" to "priv-kit is not running. ",
            "AudioBridge に未接続です。" to "Not connected to AudioBridge. ",
            "AI通話中" to "AI call active", "AI再接続中…" to "Reconnecting AI…",
            "AI切断(通話は継続中)" to "AI disconnected (call continues)",
            "翻訳開始中..." to "Starting translation...", "翻訳中（" to "Translating (", "翻訳: " to "Translation: ",
            "。右端に「電話待機」が出ます。通話アプリで発信してください" to ". Call standby appears on the right. Place the call in your phone app.",
            "。右端に「電話待機」が表示されます" to ". Call standby appears on the right.",
            "翻訳中:" to "Translating:", "セット中:" to "Armed:", "次の通話で自動開始" to "Starts on next call",
            "未セット" to "Not armed", "停止中" to "Stopping", "AI通話開始中" to "Starting AI call",
            "通話中（AIが応答します）" to "In call (AI responding)", "セットしました" to "Armed",
            "保存しました" to "Saved", "削除しました" to "Deleted", "編集中:" to "Editing:",
            "発信先や用件を書いて送信してください。" to "Enter the recipient and call purpose, then send it. ",
            "Gemma が質問をしながら整理します。" to "Gemma will ask questions to organize the details. ",
            "「まとめる」で項目に分け、編集してからセットできます。" to "Use Organize to split the details into fields, edit them, and then arm the brief.",
            "設定タブ" to "Settings tab", "用件" to "call brief", "相手" to "other party",
            "ブラウザが見つかりません。" to "No browser found. Open ", "未確認:" to "Unverified:", "無効:" to "Invalid:",
            "先に設定タブで" to "First, go to Settings and ", "API キーを保存" to "save the API key",
            "先に用件を送信してください" to "Send the call purpose first", "削除する用件がありません" to "There is no call brief to delete",
        ),
        "zh-CN" to linkedMapOf(
            "通話中ですが AI は動作していません。" to "通话中 AI 未运行。", "AI通話は動作しません。" to "AI 通话无法运行。",
            "priv-kit が起動していません。" to "priv-kit 未启动。", "AudioBridge に未接続です。" to "未连接 AudioBridge。",
            "AI通話中" to "AI 通话中", "AI再接続中…" to "AI 正在重新连接…", "AI切断(通話は継続中)" to "AI 已断开连接（通话仍在继续）",
            "翻訳中（" to "翻译中（", "翻訳開始中..." to "正在启动翻译...", "翻訳: " to "翻译：",
            "。右端に「電話待機」が出ます。通話アプリで発信してください" to "。右侧会显示电话待机。请在电话应用中拨出电话。",
            "。右端に「電話待機」が表示されます" to "。右侧会显示电话待机。",
            "翻訳中:" to "翻译中：", "セット中:" to "待机中：", "次の通話で自動開始" to "下次通话时自动启动",
            "未セット" to "未设置待机", "停止中" to "正在停止", "AI通話開始中" to "正在启动 AI 通话",
            "通話中（AIが応答します）" to "通话中（AI 正在应答）", "セットしました" to "已设置待机",
            "保存しました" to "已保存", "削除しました" to "已删除", "編集中:" to "正在编辑：",
            "設定タブ" to "设置页", "用件" to "通话事项", "相手" to "对方",
            "ブラウザが見つかりません。" to "未找到浏览器。请手动打开 ", "未確認:" to "未验证：", "無効:" to "无效：",
            "先に設定タブで" to "请先前往设置页并", "API キーを保存" to "保存 API 密钥",
            "先に用件を送信してください" to "请先发送通话事项", "削除する用件がありません" to "没有可删除的通话事项",
        ),
        "zh-TW" to linkedMapOf(
            "通話中ですが AI は動作していません。" to "通話中 AI 未執行。", "AI通話は動作しません。" to "AI 通話無法運作。",
            "priv-kit が起動していません。" to "priv-kit 未啟動。", "AudioBridge に未接続です。" to "未連線 AudioBridge。",
            "AI通話中" to "AI 通話中", "AI再接続中…" to "AI 正在重新連線…", "AI切断(通話は継続中)" to "AI 已中斷連線（通話仍在繼續）",
            "翻訳中（" to "翻譯中（", "翻訳開始中..." to "正在啟動翻譯...", "翻訳: " to "翻譯：",
            "。右端に「電話待機」が出ます。通話アプリで発信してください" to "。右側會顯示電話待機。請在電話應用程式中撥出電話。",
            "。右端に「電話待機」が表示されます" to "。右側會顯示電話待機。",
            "翻訳中:" to "翻譯中：", "セット中:" to "待機中：", "次の通話で自動開始" to "下次通話時自動啟動",
            "未セット" to "未設定待機", "停止中" to "正在停止", "AI通話開始中" to "正在啟動 AI 通話",
            "通話中（AIが応答します）" to "通話中（AI 正在應答）", "セットしました" to "已設定待機",
            "保存しました" to "已儲存", "削除しました" to "已刪除", "編集中:" to "正在編輯：",
            "設定タブ" to "設定頁", "用件" to "通話事項", "相手" to "對方",
            "ブラウザが見つかりません。" to "找不到瀏覽器。請手動開啟 ", "未確認:" to "未驗證：", "無効:" to "無效：",
            "先に設定タブで" to "請先前往設定頁並", "API キーを保存" to "儲存 API 金鑰",
            "先に用件を送信してください" to "請先傳送通話事項", "削除する用件がありません" to "沒有可刪除的通話事項",
        ),
        "ko" to linkedMapOf(
            "通話中ですが AI は動作していません。" to "통화 중 AI가 작동하지 않습니다. ", "AI通話は動作しません。" to "AI 통화가 작동하지 않습니다. ",
            "priv-kit が起動していません。" to "priv-kit이 실행 중이 아닙니다. ", "AudioBridge に未接続です。" to "AudioBridge에 연결되지 않았습니다. ",
            "AI通話中" to "AI 통화 중", "AI再接続中…" to "AI 재연결 중…", "AI切断(通話は継続中)" to "AI 연결 끊김(통화는 계속됨)",
            "翻訳中（" to "번역 중(", "翻訳開始中..." to "번역 시작 중...", "翻訳: " to "번역: ",
            "。右端に「電話待機」が出ます。通話アプリで発信してください" to ". 오른쪽에 전화 대기가 표시됩니다. 전화 앱에서 발신하세요.",
            "。右端に「電話待機」が表示されます" to ". 오른쪽에 전화 대기가 표시됩니다.",
            "翻訳中:" to "번역 중:", "セット中:" to "대기 중:", "次の通話で自動開始" to "다음 통화에서 자동 시작",
            "未セット" to "대기 설정 안 됨", "停止中" to "중지 중", "AI通話開始中" to "AI 통화 시작 중",
            "通話中（AIが応答します）" to "통화 중(AI 응답 중)", "セットしました" to "대기 설정됨",
            "保存しました" to "저장됨", "削除しました" to "삭제됨", "編集中:" to "편집 중:",
            "設定タブ" to "설정 탭", "用件" to "용건", "相手" to "상대방",
            "ブラウザが見つかりません。" to "브라우저를 찾을 수 없습니다. ", "未確認:" to "확인되지 않음:", "無効:" to "잘못됨:",
            "先に設定タブで" to "먼저 설정 탭에서", "API キーを保存" to "API 키를 저장하세요",
            "先に用件を送信してください" to "먼저 용건을 보내세요", "削除する用件がありません" to "삭제할 용건이 없습니다",
        ),
        "es" to linkedMapOf(
            "通話中ですが AI は動作していません。" to "La IA no está activa durante la llamada. ", "AI通話は動作しません。" to "Las llamadas con IA no funcionan. ",
            "priv-kit が起動していません。" to "priv-kit no está ejecutándose. ", "AudioBridge に未接続です。" to "No está conectado a AudioBridge. ",
            "AI通話中" to "Llamada con IA activa", "AI再接続中…" to "Reconectando la IA…", "AI切断(通話は継続中)" to "IA desconectada (la llamada continúa)",
            "翻訳中（" to "Traduciendo (", "翻訳開始中..." to "Iniciando traducción...", "翻訳: " to "Traducción: ",
            "。右端に「電話待機」が出ます。通話アプリで発信してください" to ". La espera aparece a la derecha. Llama desde la aplicación de teléfono.",
            "。右端に「電話待機」が表示されます" to ". La espera aparece a la derecha.",
            "翻訳中:" to "Traduciendo:", "セット中:" to "Activado:", "次の通話で自動開始" to "Se inicia en la próxima llamada",
            "未セット" to "No activado", "停止中" to "Deteniendo", "AI通話開始中" to "Iniciando llamada con IA",
            "通話中（AIが応答します）" to "En llamada (la IA responde)", "セットしました" to "Activado",
            "保存しました" to "Guardado", "削除しました" to "Eliminado", "編集中:" to "Editando:",
            "設定タブ" to "pestaña Ajustes", "用件" to "motivo de llamada", "相手" to "otra persona",
            "ブラウザが見つかりません。" to "No se encontró un navegador. Abre ", "未確認:" to "Sin verificar:", "無効:" to "No válida:",
            "先に設定タブで" to "Primero, ve a Ajustes y ", "API キーを保存" to "guarda la clave API",
            "先に用件を送信してください" to "Envía primero el motivo de la llamada", "削除する用件がありません" to "No hay ningún motivo de llamada para eliminar",
        ),
    )

}
