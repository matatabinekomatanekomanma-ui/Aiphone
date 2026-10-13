package com.example.aiphone

import androidx.annotation.DrawableRes

/**
 * 設定方法ページの中身。いまは文字だけ。
 * 写真を使うときは、手順ごとに [Step.imageRes] へ drawable を指定するだけで表示される
 * (SetupGuideActivity が手順の本文の下に画像を出す)。
 */
object SetupGuideContent {
    const val PRIV = "priv"
    const val OVERLAY = "overlay"
    const val GEMINI = "gemini"

    /** 手順の下に出すボタンの動作 */
    enum class Action { OPEN_BUILD_NUMBER, START_PRIV_SETUP }

    data class Step(
        val title: String,
        val body: String,
        /** 手順にボタンを付けるとき(label はボタンの文字) */
        val action: Action? = null,
        val actionLabel: String = "",
        /** 後で写真を入れるときに指定する(未指定なら文字だけ) */
        @DrawableRes val imageRes: Int? = null,
    )

    data class Guide(
        val title: String,
        val summary: String,
        val steps: List<Step>,
        val notes: List<String> = emptyList(),
    )

    fun get(type: String): Guide = when (type) {
        OVERLAY -> overlay
        GEMINI -> gemini
        else -> priv
    }

    private val priv = Guide(
        title = "priv-kit 接続の設定方法",
        summary = "AIの声を通話に流すため、このアプリはワイヤレスデバッグ(priv-kit)を使います。" +
            "最初の1回だけペアリングが必要です。Wi-Fiにつないだ状態で行ってください。",
        steps = listOf(
            Step(
                "1. 開発者向けオプションをオンにする",
                "端末の「設定」→「デバイス情報(端末情報)」を開き、「ビルド番号」を7回続けてタップします。" +
                    "画面ロックの認証を求められたら入力します。すでにオンなら、次へ進んでください。" +
                    "下のボタンで端末情報の画面が開き、「ビルド番号」が強調表示されます。" +
                    "(機種によって項目名や場所が違い、強調されないことがあります。Galaxy などでは「ソフトウェア情報」の中にあります)",
                Action.OPEN_BUILD_NUMBER, "ビルド番号の画面を開く"
            ),
            Step(
                "2. Wi-Fiにつなぐ",
                "ワイヤレスデバッグは、Wi-Fiにつながっているときだけ使えます。通話に使う回線とは別に、Wi-Fiへ接続しておいてください。"
            ),
            Step(
                "3. ワイヤレスデバッグをオンにする",
                "下のボタンを押すと、通知の許可を聞かれたら「許可」し、開発者向けオプション(ワイヤレスデバッグ)の画面が開きます。" +
                    "「ワイヤレスデバッグ」のスイッチをオンにします。「このネットワークでのワイヤレスデバッグを常に許可しますか？」と出たら「許可」します。" +
                    "(設定タブの priv-kit 接続の「設定」ボタンと同じ動作です)",
                Action.START_PRIV_SETUP, "ワイヤレスデバッグの設定を開く"
            ),
            Step(
                "4. ペア設定コードを表示する",
                "「ワイヤレスデバッグ」の項目をタップして詳細を開き、「ペア設定コードによるデバイスのペア設定」をタップします。" +
                    "6桁のコードが表示されます。この画面は閉じずに、そのままにしてください(閉じるとコードが無効になります)。"
            ),
            Step(
                "5. 通知にコードを入力する",
                "画面の上に「ペアリングコードを入力」という通知が出ます。通知の入力欄に6桁のコードを入力して、送信します。" +
                    "通知が出ないときは、通知バーを下に引いて確認してください。それでも出ないときは、このアプリの通知が許可されているか確認します。"
            ),
            Step(
                "6. ✓ になったら完了",
                "設定タブの priv-kit 接続が「✓ priv-kit 接続済み」になれば完了です。" +
                    "「ブリッジ未接続(自動接続中…)」と出ているときは、しばらく待ってください。"
            ),
        ),
        notes = listOf(
            "端末を再起動したり、Wi-Fiを切り替えたりすると、ワイヤレスデバッグがオフになることがあります。その場合は手順3からやり直してください。",
            "コードが違う、または時間切れで失敗したときは、手順4でコードを表示し直して、もう一度入力します。",
        ),
    )

    private val overlay = Guide(
        title = "「重ねて表示」の設定方法",
        summary = "通話中に、このアプリへ戻るためのアイコンを他の画面の上に出すために必要な許可です。",
        steps = listOf(
            Step(
                "1. 「設定」ボタンをタップ",
                "設定タブの「重ねて表示(戻るアイコン)」の行にある「設定」を押します。" +
                    "このアプリの「他のアプリの上に重ねて表示」の画面が開きます。"
            ),
            Step(
                "2. スイッチをオンにする",
                "「他のアプリの上に重ねて表示を許可」(表記は機種で少し違います)のスイッチをオンにします。"
            ),
            Step(
                "3. アプリに戻って確認する",
                "戻るボタンでこのアプリに戻り、「✓ 許可済み」になっていれば完了です。"
            ),
        ),
        notes = listOf(
            "ボタンから画面が開けないときは、端末の「設定」→「アプリ」→「Aiphone」→「他のアプリの上に重ねて表示」(「特別なアプリアクセス」の中にある場合もあります)から設定できます。",
        ),
    )

    private val gemini = Guide(
        title = "Gemini API キーの設定方法",
        summary = "AIが電話で話すために、Google の Gemini API キーが必要です。" +
            "キーは Google AI Studio で作成して、このアプリに貼り付けます。",
        steps = listOf(
            Step(
                "1. 「設定」ボタンをタップ",
                "設定タブの Gemini API キーの行にある「設定」ボタンです。標準のブラウザで Google AI Studio の API キーのページが開きます。"
            ),
            Step(
                "2. Google アカウントでログイン",
                "ログインを求められたら、使いたい Google アカウントでログインします。利用規約への同意が出たら、内容を確認して進みます。"
            ),
            Step(
                "3. APIキーを作成する",
                "「APIキーを作成」(Create API key)を押します。プロジェクトを聞かれたら、既存のものを選ぶか新しく作ります。"
            ),
            Step(
                "4. キーをコピーする",
                "作成されたキーの横にあるコピーボタンで、キーをコピーします。キーは「AQ.」または「AIza」から始まる長い文字列です。"
            ),
            Step(
                "5. このアプリに貼り付ける",
                "このアプリに戻り、Gemini API キーの入力欄にキーを貼り付けます。貼り付けると、自動で接続確認が始まります。"
            ),
            Step(
                "6. ✓ になったら完了",
                "「✓ Gemini に接続して確認済み」と出れば完了です。✗ のときは、表示された理由を確認して、キーを貼り直してください。"
            ),
        ),
        notes = listOf(
            "キーは他の人に見せたり、ネットに公開したりしないでください。",
            "利用できる量や料金は Google 側の設定に従います。心配なときは、Google AI Studio の画面で確認してください。",
        ),
    )
}
