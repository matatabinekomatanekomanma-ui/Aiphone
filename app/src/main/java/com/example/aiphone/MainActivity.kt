package com.example.aiphone

import android.Manifest
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import android.view.View
import android.view.ViewGroup
import android.provider.Settings
import android.util.Log
import android.text.TextWatcher
import android.text.Editable
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    companion object {
        private const val TAG = "RLAudio"
        const val EXTRA_FROM_BUBBLE = "from_bubble"
        private const val REQ_PHONE = 2001
        const val EXTRA_OPEN_CALL_TAB = "open_call_tab"
        private const val PAGE_SETTINGS = 0
        private const val PAGE_PREP = 1
        private const val PAGE_CALL = 2
        private const val PAGE_TRANSLATE = 3
        private const val KEY_MIN_LENGTH = 20
        private const val KEY_VERIFY_DELAY_MS = 800L
        private const val URL_API_KEYS = "https://aistudio.google.com/api-keys"
        private const val REQ_MIC = 2003
        private const val STATE_CURRENT_PAGE = "state_current_page"
        private const val STATE_SETTINGS_SCROLL_Y = "state_settings_scroll_y"
    }

    private lateinit var aiSwitch: SwitchMaterial
    private lateinit var aiRow: View
    private lateinit var tvAiState: TextView
    /** コードからスイッチを動かしているときは、切り替え処理を走らせない */
    private var aiSwitchSyncing = false
    private lateinit var etApiKey: EditText
    private lateinit var cbClearArmOnEnd: CheckBox
    private lateinit var cbDeleteBriefOnEnd: CheckBox
    private lateinit var prefs: SharedPreferences
    /** 暗号化済み API キーを復号できなかった場合、空欄のまま保存して既存値を消さない。 */
    private var apiKeyReadFailed = false
    private var piiStorageWarningShown = false

    // 話し方設定・試聴・チャット
    private lateinit var spVoice: Spinner
    private lateinit var spVadStart: Spinner
    private lateinit var spVadEnd: Spinner
    private lateinit var etVadSilence: EditText
    private lateinit var spGemmaThinking: Spinner
    private lateinit var spGemmaMaxAttempts: Spinner
    private lateinit var etPrompt: EditText
    private lateinit var etGemmaChatPrompt: EditText
    private lateinit var etGemmaSummaryPrompt: EditText
    private lateinit var etPiiName: EditText
    private lateinit var etPiiKana: EditText
    private lateinit var etPiiPhone: EditText
    private lateinit var etPiiEmail: EditText
    private lateinit var etPiiAddress: EditText
    private lateinit var etPiiBirthday: EditText
    private lateinit var etPiiCompany: EditText
    private lateinit var etPiiNotes: EditText
    private lateinit var etPiiAddressKana: EditText
    private lateinit var etPiiCompanyKana: EditText
    /** 項目キー → 「許可なしで伝えてよい」チェックボックス */
    private val cbAutoPii = LinkedHashMap<String, CheckBox>()
    private lateinit var etTestLine: EditText
    private lateinit var tvVoiceState: TextView
    private lateinit var tvVoiceText: TextView
    private lateinit var btnVoiceTest: Button
    private lateinit var chatView: ChatView

    // 設定タブの状態表示
    private lateinit var tabs: TabLayout
    private lateinit var pageSettings: View
    private lateinit var pageCall: View
    private lateinit var pagePrep: View
    private lateinit var pageTranslate: View
    private lateinit var translateTab: TranslateTabController
    private lateinit var tvWarn: TextView
    private lateinit var markShizuku: TextView
    private lateinit var subShizuku: TextView
    private lateinit var markGemini: TextView
    private lateinit var subGemini: TextView

    private enum class KeyState { UNSET, CHECKING, OK, NG, UNKNOWN }
    @Volatile private var keyState = KeyState.UNSET
    @Volatile private var keyMsg = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            // キーボード表示時も入力欄が隠れないよう IME 分の余白を足す
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        prefs = getSharedPreferences(AiCallService.PREFS, MODE_PRIVATE)
        CallBriefStore.init(this)
        TranslateStore.init(prefs)
        aiSwitch = findViewById(R.id.switchAi)
        aiRow = findViewById(R.id.aiSwitchRow)
        tvAiState = findViewById(R.id.tvAiState)
        etApiKey = findViewById(R.id.etApiKey)
        cbClearArmOnEnd = findViewById(R.id.cbClearArmOnEnd)
        cbDeleteBriefOnEnd = findViewById(R.id.cbDeleteBriefOnEnd)
        tabs = findViewById(R.id.tabs)
        spVoice = findViewById(R.id.spVoice)
        spVadStart = findViewById(R.id.spVadStart)
        spVadEnd = findViewById(R.id.spVadEnd)
        etVadSilence = findViewById(R.id.etVadSilence)
        spGemmaThinking = findViewById(R.id.spGemmaThinking)
        spGemmaMaxAttempts = findViewById(R.id.spGemmaMaxAttempts)
        etPrompt = findViewById(R.id.etPrompt)
        etPiiName = findViewById(R.id.etPiiName)
        etPiiKana = findViewById(R.id.etPiiKana)
        etPiiPhone = findViewById(R.id.etPiiPhone)
        etPiiEmail = findViewById(R.id.etPiiEmail)
        etPiiAddress = findViewById(R.id.etPiiAddress)
        etPiiBirthday = findViewById(R.id.etPiiBirthday)
        etPiiCompany = findViewById(R.id.etPiiCompany)
        etPiiNotes = findViewById(R.id.etPiiNotes)
        etPiiAddressKana = findViewById(R.id.etPiiAddressKana)
        etPiiCompanyKana = findViewById(R.id.etPiiCompanyKana)
        cbAutoPii["name"] = findViewById(R.id.cbAutoName)
        cbAutoPii["phone"] = findViewById(R.id.cbAutoPhone)
        cbAutoPii["email"] = findViewById(R.id.cbAutoEmail)
        cbAutoPii["address"] = findViewById(R.id.cbAutoAddress)
        cbAutoPii["birthday"] = findViewById(R.id.cbAutoBirthday)
        cbAutoPii["company"] = findViewById(R.id.cbAutoCompany)
        cbAutoPii["notes"] = findViewById(R.id.cbAutoNotes)
        etTestLine = findViewById(R.id.etTestLine)
        tvVoiceState = findViewById(R.id.tvVoiceState)
        tvVoiceText = findViewById(R.id.tvVoiceText)
        btnVoiceTest = findViewById(R.id.btnVoiceTest)
        chatView = ChatView(this, findViewById(R.id.chatScroll), findViewById<LinearLayout>(R.id.chatContainer))
        askCard = AskCardController(this, REQ_MIC).also { it.setup() }

        spVoice.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, GeminiVoices.all.map { it.label }
        )
        spVoice.setSelection(GeminiVoices.indexOf(prefs.getString(AiCallService.KEY_VOICE, GeminiVoices.DEFAULT) ?: GeminiVoices.DEFAULT))
        spVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit { putString(AiCallService.KEY_VOICE, GeminiVoices.all[pos].name) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        setupVadControls()
        setupGemmaThinking()
        setupGemmaMaxAttempts()
        setupGemmaPromptEditors()
        val appLanguageCode = AppLanguage.resolvedCode(this)
        etPrompt.setText(prefs.getString(AiCallService.KEY_PROMPT, null)
            ?.takeIf { it.isNotBlank() && !GeminiLiveSession.isLegacyDefault(it) }
            ?: GeminiLiveSession.defaultPrompt(appLanguageCode))
        run {
            val pii = PersonalInfo.load(prefs)
            if (PersonalInfo.lastLoadHadError && !piiStorageWarningShown) {
                piiStorageWarningShown = true
                Toast.makeText(this, AppLanguage.tr("保存済み個人情報の一部を安全に読み込み・移行できません。元データを空欄で上書きしないよう、該当欄の再入力まで保存を保留します。", this), Toast.LENGTH_LONG).show()
            }
            etPiiName.setText(pii.name)
            etPiiKana.setText(pii.kana)
            etPiiPhone.setText(pii.phone)
            etPiiEmail.setText(pii.email)
            etPiiAddress.setText(pii.address)
            etPiiBirthday.setText(pii.birthday)
            etPiiCompany.setText(pii.company)
            etPiiNotes.setText(pii.notes)
            etPiiAddressKana.setText(pii.addressKana)
            etPiiCompanyKana.setText(pii.companyKana)
            cbAutoPii.forEach { (k, cb) -> cb.isChecked = k in pii.autoAllow }
        }
        etTestLine.setText(prefs.getString(AiCallService.KEY_TEST_LINE, null)?.takeIf { it.isNotBlank() }
            ?: "もしもし、お電話ありがとうございます。ご用件をお伺いします。")
        setupPiiAutoSave()
        findViewById<Button>(R.id.btnPromptReset).setOnClickListener {
            etPrompt.setText(GeminiLiveSession.defaultPrompt(AppLanguage.resolvedCode(this)))
        }
        btnVoiceTest.setOnClickListener { startVoiceTest() }
        findViewById<Button>(R.id.btnVoiceStop).setOnClickListener { VoiceTester.stop() }
        pageSettings = findViewById(R.id.pageSettings)
        pageCall = findViewById(R.id.pageCall)
        pagePrep = findViewById(R.id.pagePrep)
        pageTranslate = findViewById(R.id.pageTranslate)
        tvWarn = findViewById(R.id.tvWarn)
        markShizuku = findViewById(R.id.markShizuku)
        subShizuku = findViewById(R.id.subShizuku)
        markGemini = findViewById(R.id.markGemini)
        subGemini = findViewById(R.id.subGemini)

        tabs.addTab(tabs.newTab().setText(AppLanguage.tr("設定", this)))
        tabs.addTab(tabs.newTab().setText(AppLanguage.tr("準備", this)))
        tabs.addTab(tabs.newTab().setText(AppLanguage.tr("通話", this)))
        tabs.addTab(tabs.newTab().setText(AppLanguage.tr("翻訳", this)))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                // 設定の3項目がすべて ✓ になるまで、設定以外のタブは開けない
                if (tab.position != PAGE_SETTINGS && !allReady()) {
                    Toast.makeText(this@MainActivity, AppLanguage.tr("設定の3項目をすべて ✓ にすると、他のタブを開けます", this@MainActivity), Toast.LENGTH_SHORT).show()
                    tabs.getTabAt(PAGE_SETTINGS)?.select()
                    return
                }
                showPage(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        prepTab = PrepTabController(
            activity = this,
            prefs = prefs,
            apiKey = { GeminiKeyChecker.clean(etApiKey.text.toString()) },
            openOverlaySettings = { openOverlaySettings() },
            log = { log(it) },
        ).also { it.setup() }
        translateTab = TranslateTabController(
            activity = this,
            prefs = prefs,
            apiKey = { GeminiKeyChecker.clean(etApiKey.text.toString()) },
            log = { log(it) },
        ).also { it.setup() }
        when (val apiKeyResult = SecretStore.readApiKey(prefs)) {
            is SecretStore.ReadResult.Success -> {
                etApiKey.setText(apiKeyResult.value)
                if (apiKeyResult.migrationFailed) {
                    Toast.makeText(this, AppLanguage.tr("旧バージョンのAPIキーを暗号化できませんでした。キーの再保存を試してください。", this), Toast.LENGTH_LONG).show()
                }
            }
            is SecretStore.ReadResult.Failure -> {
                apiKeyReadFailed = true
                etApiKey.setText("")
                etApiKey.hint = AppLanguage.tr("保存済みAPIキーを復号できません。新しいキーを入力してください", this)
                Toast.makeText(this, AppLanguage.tr("保存済みAPIキーを復号できません。既存データを保護するため自動で上書きしません。", this), Toast.LENGTH_LONG).show()
            }
        }
        cbClearArmOnEnd.isChecked = prefs.getBoolean(AiCallService.KEY_CLEAR_ARM_ON_END, true)
        cbDeleteBriefOnEnd.isChecked = prefs.getBoolean(AiCallService.KEY_DELETE_BRIEF_ON_END, false)

        AiSessionManager.onStatus = { s -> setStatus(s) }

        findViewById<Button>(R.id.btnConnect).setOnClickListener { onStartPairingClicked() }
        findViewById<Button>(R.id.btnDiag).setOnClickListener {
            val s = BridgeConnector.bridge ?: return@setOnClickListener log("先に priv-kit 接続を完了してください")
            val uid = AiSessionManager.targetUid(this) ?: return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) { log(s.diagnose(uid)) }
        }
        aiSwitch.setOnCheckedChangeListener { _, checked ->
            if (aiSwitchSyncing) return@setOnCheckedChangeListener
            if (checked) startAiFromSwitch() else AiSessionManager.stop()
            updateAiSwitch()
        }
        // 行のどこをタップしても切り替わる
        aiRow.setOnClickListener { if (aiSwitch.isEnabled) aiSwitch.toggle() }
        findViewById<Button>(R.id.btnGetKey).setOnClickListener { openApiKeyPage() }
        findViewById<Button>(R.id.btnGuidePriv).setOnClickListener { SetupGuideActivity.start(this, SetupGuideContent.PRIV) }
        findViewById<Button>(R.id.btnGuideGemini).setOnClickListener { SetupGuideActivity.start(this, SetupGuideContent.GEMINI) }
        cbClearArmOnEnd.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean(AiCallService.KEY_CLEAR_ARM_ON_END, checked) }
        }
        cbDeleteBriefOnEnd.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean(AiCallService.KEY_DELETE_BRIEF_ON_END, checked) }
        }

        setupSettingsSections()
        val cbHear = findViewById<CheckBox>(R.id.cbTrHearOriginal)
        val cbEcho = findViewById<CheckBox>(R.id.cbTrEchoTarget)
        cbHear.isChecked = TranslateStore.hearOriginal(prefs)
        cbEcho.isChecked = TranslateStore.echoTarget(prefs)
        cbHear.setOnCheckedChangeListener { _, checked ->
            prefs.edit {
                putBoolean(TranslateStore.KEY_HEAR_ORIGINAL, checked)
                putBoolean(TranslateStore.KEY_MUTE_ORIGINAL, !checked)
            }
        }
        cbEcho.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean(TranslateStore.KEY_ECHO_TARGET, checked) }
        }

        log("起動 build=${AudioBridgeService.BUILD}")
        log("1) ワイヤレスデバッグ(priv-kit)  2) APIキー  3) 電話権限・オーバーレイ許可  4) セット中の通話でAI起動")
        ensureRuntimePermissions()
        // 特権ブリッジの接続は AiCallService が保持する（Activity が閉じても通話中の AI を止めない）
        AiCallService.start(this)
        observeConnection()

        // 保存済みキーが未検証なら起動時に一度だけ確認する
        val saved = GeminiKeyChecker.clean(etApiKey.text.toString())
        if (saved.isBlank()) {
            keyState = KeyState.UNSET
        } else if (GeminiKeyChecker.isVerified(prefs, saved)) {
            keyState = KeyState.OK
        } else {
            verifyKey()
        }
        // 以降、キー欄が編集されたら自動で確認する(確認ボタンは無い)
        setupApiKeyAutoVerify()
        // 通常起動時は準備状況に応じたタブを開き、言語変更などの再生成時は現在のタブを維持する。
        val restoredPage = savedInstanceState?.getInt(STATE_CURRENT_PAGE, -1)
            ?.takeIf { it in PAGE_SETTINGS..PAGE_TRANSLATE }
        val startPage = restoredPage ?: if (allReady()) PAGE_CALL else PAGE_SETTINGS
        showPage(startPage)
        tabs.getTabAt(startPage)?.select()
        handleIntent(intent)
        setupLanguageSelector()
        AppLanguage.localizeTree(findViewById(R.id.main), this)

        // 言語設定が個人情報セクション内に移動したため、切替後も同じ位置に戻す。
        val scrollY = savedInstanceState?.getInt(STATE_SETTINGS_SCROLL_Y, 0) ?: 0
        if (scrollY > 0) pageSettings.post { pageSettings.scrollTo(0, scrollY) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::tabs.isInitialized) {
            outState.putInt(STATE_CURRENT_PAGE, tabs.selectedTabPosition.coerceAtLeast(PAGE_SETTINGS))
        }
        if (::pageSettings.isInitialized) {
            outState.putInt(STATE_SETTINGS_SCROLL_Y, pageSettings.scrollY)
        }
        super.onSaveInstanceState(outState)
    }

    private fun setupLanguageSelector() {
        val spinner = findViewById<Spinner>(R.id.spinnerLanguage)
        val languageOptions = AppLanguage.options
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languageOptions.map { AppLanguage.localizeLanguageOption(it.code, this) },
        )
        val selected = AppLanguage.selectedCode(this)
        spinner.setSelection(languageOptions.indexOfFirst { it.code == selected }.coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = languageOptions.getOrNull(position)?.code ?: return
                if (chosen != AppLanguage.selectedCode(this@MainActivity)) {
                    // 言語切替で Activity を再生成する前に、編集中のプロンプト等を保存する。
                    savePrefs()
                    prefs.edit { putString(AppLanguage.PREF_KEY, chosen) }
                    recreate()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 通知 / 通話中オーバーレイ（Link+）タップ時は通話タブを開く */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val openCall = intent.getBooleanExtra(EXTRA_OPEN_CALL_TAB, false) ||
            intent.getBooleanExtra(EXTRA_FROM_BUBBLE, false)
        if (openCall && allReady() && ::tabs.isInitialized) {
            showPage(PAGE_CALL)
            tabs.getTabAt(PAGE_CALL)?.select()
        }
    }

    private fun startVoiceTest() {
        savePrefs()
        val key = GeminiKeyChecker.clean(etApiKey.text.toString())
        when {
            key.isBlank() -> { tvVoiceState.text = AppLanguage.tr("先に Gemini API キーを設定してください", this); return }
            AiSessionManager.running.get() -> { tvVoiceState.text = AppLanguage.tr("AI通話中は試聴できません", this); return }
            VoiceTester.running.get() -> return
        }
        val voice = GeminiVoices.all[spVoice.selectedItemPosition].name
        val prompt = PersonalInfo.fillName(prefs, etPrompt.text.toString().ifBlank { GeminiLiveSession.defaultPrompt(AppLanguage.resolvedCode(this)) })
        val line = etTestLine.text.toString().ifBlank { "もしもし" }
        tvVoiceText.text = ""
        tvVoiceText.visibility = View.GONE
        btnVoiceTest.isEnabled = false
        VoiceTester.start(
            apiKey = key, prompt = prompt, voice = voice, testLine = line,
            onText = { t ->
                runOnUiThread {
                    tvVoiceText.visibility = View.VISIBLE
                    tvVoiceText.append(t)
                }
            },
            onState = { st -> runOnUiThread { tvVoiceState.text = AppLanguage.tr(st, this) } },
            onFinished = { runOnUiThread { btnVoiceTest.isEnabled = true } },
            languageCode = AppLanguage.resolvedCode(this)
        )
    }

    private fun showPage(pos: Int) {
        pageSettings.visibility = if (pos == PAGE_SETTINGS) View.VISIBLE else View.GONE
        pagePrep.visibility = if (pos == PAGE_PREP) View.VISIBLE else View.GONE
        pageCall.visibility = if (pos == PAGE_CALL) View.VISIBLE else View.GONE
        pageTranslate.visibility = if (pos == PAGE_TRANSLATE) View.VISIBLE else View.GONE
        if (pos == PAGE_TRANSLATE) translateTab.refresh()
        if (pos == PAGE_PREP) prepTab.refreshPrepActiveBar()
    }

    private fun privReady(): Pair<Boolean, String> {
        if (Privilege.serverState.value == null) {
            return false to "ワイヤレスデバッグ未起動（「開始」でペアリング）"
        }
        if (!BridgeConnector.isAlive()) return false to "特権サーバー起動済み — ブリッジ未接続（自動接続中…）"
        return true to "priv-kit 接続済み (shell/ADB)"
    }

    private fun allReady(): Boolean =
        privReady().first && keyState == KeyState.OK

    private fun setMark(mark: TextView, sub: TextView, ok: Boolean?, text: String) {
        when (ok) {
            true -> { mark.text = "✓"; mark.setTextColor("#2E7D32".toColorInt()) }
            false -> { mark.text = "✗"; mark.setTextColor("#D32F2F".toColorInt()) }
            null -> { mark.text = "…"; mark.setTextColor("#F9A825".toColorInt()) }
        }
        sub.text = AppLanguage.tr(text, this)
    }

    /** 3項目のチェック表示とタブの警告を更新 */
    private fun refreshStatus() {
        if (!::tabs.isInitialized) return
        val (shOk, shText) = privReady()
        setMark(markShizuku, subShizuku, shOk, shText)

        when (keyState) {
            KeyState.OK -> setMark(markGemini, subGemini, true, "Gemini に接続して確認済み")
            KeyState.CHECKING -> setMark(markGemini, subGemini, null, "確認中…")
            KeyState.UNSET -> setMark(markGemini, subGemini, false, "未設定")
            KeyState.NG -> setMark(markGemini, subGemini, false, "無効: $keyMsg")
            KeyState.UNKNOWN -> setMark(markGemini, subGemini, false, "未確認: $keyMsg")
        }
        val ready = shOk && keyState == KeyState.OK
        tabs.getTabAt(PAGE_SETTINGS)?.text = if (ready) AppLanguage.tr("設定", this) else AppLanguage.tr("設定", this) + " ⚠"
        applyTabLock(ready)
        tvWarn.visibility = if (ready) View.GONE else View.VISIBLE
        if (!shOk) tvWarn.text = AppLanguage.tr("priv-kit 未接続のため AI 通話は動作しません。「設定」タブでワイヤレスデバッグを開始してください。", this)
        else tvWarn.text = AppLanguage.tr("未完了の設定があります。「設定」タブを確認してください。", this)
    }

    /** 3項目が揃うまで、設定以外のタブを押せなくする(見た目も薄くする) */
    private fun applyTabLock(ready: Boolean) {
        val strip = tabs.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until strip.childCount) {
            if (i == PAGE_SETTINGS) continue
            strip.getChildAt(i).apply {
                isEnabled = ready
                alpha = if (ready) 1f else 0.4f
            }
        }
    }

    private lateinit var prepTab: PrepTabController
    private lateinit var askCard: AskCardController
    private var keyVerifyJob: Job? = null

    /**
     * キー欄の入力が止まって一定時間たったら、自動で接続確認する。
     * 編集した瞬間に ✓ は外れる(確認が済むまで他のタブは開けない)。
     */
    private fun setupApiKeyAutoVerify() {
        etApiKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                keyVerifyJob?.cancel()
                val key = GeminiKeyChecker.clean(s?.toString().orEmpty())
                when {
                    key.isBlank() -> {
                        keyState = KeyState.UNSET
                        GeminiKeyChecker.saveVerified(prefs, null)
                        savePrefs()
                        refreshStatus()
                    }
                    GeminiKeyChecker.isVerified(prefs, key) -> {
                        // 確認済みと同じキーなら通信しない
                        savePrefs()
                        keyState = KeyState.OK
                        refreshStatus()
                    }
                    key.length < KEY_MIN_LENGTH -> {
                        // 明らかに短い間は通信しない(入力途中)
                        keyMsg = "キーが短すぎます"
                        keyState = KeyState.NG
                        GeminiKeyChecker.saveVerified(prefs, null)
                        refreshStatus()
                    }
                    else -> {
                        // 先に保存しておく(確認中だった前のキーの結果を verifyKey が捨てられるように)
                        savePrefs()
                        keyState = KeyState.CHECKING
                        refreshStatus()
                        keyVerifyJob = lifecycleScope.launch {
                            delay(KEY_VERIFY_DELAY_MS)
                            verifyKey()
                        }
                    }
                }
            }
        })
    }

    /** API キー取得ページを標準ブラウザで開く */
    private fun openApiKeyPage() {
        val uri = URL_API_KEYS.toUri()
        // 標準ブラウザを直接指定する(リンクを処理する別アプリに取られないようにする)
        val viaBrowser = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        }
        try {
            startActivity(viaBrowser)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, AppLanguage.tr("ブラウザが見つかりません。$URL_API_KEYS を開いてください", this), Toast.LENGTH_LONG).show()
                log("ブラウザを開けませんでした: ${e2.message}")
            }
        }
        log("API キー取得ページをブラウザで開きました")
    }

    /** API キーを保存し、Gemini Live へ実際に接続して検証 */
    private fun verifyKey() {
        val key = GeminiKeyChecker.clean(etApiKey.text.toString())
        savePrefs()
        if (key.isBlank()) {
            keyState = KeyState.UNSET
            GeminiKeyChecker.saveVerified(prefs, null)
            refreshStatus()
            return
        }
        keyState = KeyState.CHECKING
        refreshStatus()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GeminiKeyChecker.check(key) }
            // 確認中にキーが書き換わっていたら結果を捨てる
            val cur = (SecretStore.readApiKey(prefs) as? SecretStore.ReadResult.Success)?.value
            if (cur == key) {
                keyMsg = r.message
                keyState = when (r.ok) {
                    true -> { GeminiKeyChecker.saveVerified(prefs, key); KeyState.OK }
                    false -> { GeminiKeyChecker.saveVerified(prefs, null); KeyState.NG }
                    null -> KeyState.UNKNOWN
                }
            }
            log("Gemini キー確認: ${r.message}")
            refreshStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        AiCallService.setForeground(true)
        // オーバーレイ権限など、他画面で変わる状態はここで再評価（定期ポーリングはしない）
        refreshStatus()
        chatView.renderAll()
        ChatStore.listener = { m, isNew -> runOnUiThread { chatView.onMessage(m, isNew) } }
        ChatStore.onCleared = { runOnUiThread { chatView.clear() } }
        AskUserStore.listener = { runOnUiThread { askCard.render() } }
        askCard.render()
        updateAiSwitch()
    }

    override fun onPause() {
        ChatStore.listener = null
        ChatStore.onCleared = null
        AskUserStore.listener = null
        askCard.stopMic()
        savePrefs()
        savePersonalInfo()
        AiCallService.setForeground(false)
        super.onPause()
    }

    /** 音声検出(VAD)の設定。変更するとすぐ保存し、次の通話から反映される */
    private fun setupVadControls() {
        val vad = VadSettings.load(prefs)
        fun bind(sp: Spinner, key: String, current: Int) {
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, VadSettings.SENSITIVITY_LABELS.map { AppLanguage.tr(it, this) })
            sp.setSelection(current)
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit { putInt(key, pos) }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        bind(spVadStart, AiCallService.KEY_VAD_START, vad.startSensitivity)
        bind(spVadEnd, AiCallService.KEY_VAD_END, vad.endSensitivity)
        etVadSilence.setText(vad.silenceMs.toString())
        etVadSilence.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { saveVadSilence() }
        })
        etVadSilence.setOnFocusChangeListener { _, focused ->
            if (!focused) {
                // 範囲外・空欄は有効な値に直して表示する
                saveVadSilence()
                etVadSilence.setText(VadSettings.load(prefs).silenceMs.toString())
            }
        }
    }

    /** Gemma(準備タブ)の思考レベル。変更するとすぐ保存し、次の送信・まとめから反映される */
    private fun setupGemmaThinking() {
        spGemmaThinking.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, GemmaClient.THINKING_LABELS.map { AppLanguage.tr(it, this) })
        spGemmaThinking.setSelection(GemmaClient.THINKING_LEVELS.indexOf(GemmaClient.thinkingFromPrefs(prefs)).coerceAtLeast(0))
        spGemmaThinking.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit { putString(AiCallService.KEY_GEMMA_THINKING, GemmaClient.THINKING_LEVELS[pos]) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupGemmaMaxAttempts() {
        val labels = (GemmaClient.MIN_MAX_ATTEMPTS..GemmaClient.MAX_MAX_ATTEMPTS).map { n -> when (AppLanguage.resolvedCode(this)) { "en" -> "$n attempts"; "zh-CN" -> "$n 次"; "zh-TW" -> "$n 次"; "ko" -> "${n}회"; "es" -> "$n intentos"; else -> "${n}回" } }
        spGemmaMaxAttempts.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val cur = GemmaClient.maxAttemptsFromPrefs(prefs)
        spGemmaMaxAttempts.setSelection((cur - GemmaClient.MIN_MAX_ATTEMPTS).coerceIn(0, labels.size - 1))
        spGemmaMaxAttempts.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val n = pos + GemmaClient.MIN_MAX_ATTEMPTS
                prefs.edit { putInt(AiCallService.KEY_GEMMA_MAX_ATTEMPTS, n) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }


    /** Gemma 用の2種類のプロンプトを設定画面で編集し、入力中も保存する。 */
    private fun setupGemmaPromptEditors() {
        etGemmaChatPrompt = findViewById(R.id.etGemmaChatPrompt)
        etGemmaSummaryPrompt = findViewById(R.id.etGemmaSummaryPrompt)
        val languageCode = AppLanguage.resolvedCode(this)

        val savedChat = prefs.getString(AiCallService.KEY_GEMMA_CHAT_PROMPT, null)
        etGemmaChatPrompt.setText(
            savedChat?.takeIf { it.isNotBlank() && !GemmaClient.isDefaultChatPrompt(it) }
                ?: GemmaClient.defaultChatPrompt(languageCode)
        )
        val savedSummary = prefs.getString(AiCallService.KEY_GEMMA_SUMMARY_PROMPT, null)
        etGemmaSummaryPrompt.setText(
            savedSummary?.takeIf { it.isNotBlank() && !GemmaClient.isDefaultSummaryPrompt(it) }
                ?: GemmaClient.defaultSummaryPrompt(languageCode)
        )

        findViewById<Button>(R.id.btnGemmaChatPromptReset).setOnClickListener {
            etGemmaChatPrompt.setText(GemmaClient.defaultChatPrompt(AppLanguage.resolvedCode(this)))
        }
        findViewById<Button>(R.id.btnGemmaSummaryPromptReset).setOnClickListener {
            etGemmaSummaryPrompt.setText(GemmaClient.defaultSummaryPrompt(AppLanguage.resolvedCode(this)))
        }

        etGemmaChatPrompt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString().orEmpty()
                prefs.edit {
                    putString(AiCallService.KEY_GEMMA_CHAT_PROMPT,
                        if (value.isBlank() || GemmaClient.isDefaultChatPrompt(value)) "" else value)
                }
            }
        })
        etGemmaSummaryPrompt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString().orEmpty()
                prefs.edit {
                    putString(AiCallService.KEY_GEMMA_SUMMARY_PROMPT,
                        if (value.isBlank() || GemmaClient.isDefaultSummaryPrompt(value)) "" else value)
                }
            }
        })
    }

    private fun saveVadSilence() {
        val n = etVadSilence.text.toString().trim().toIntOrNull() ?: return
        prefs.edit {
            putInt(
                AiCallService.KEY_VAD_SILENCE_MS,
                n.coerceIn(VadSettings.MIN_SILENCE_MS, VadSettings.MAX_SILENCE_MS)
            )
        }
    }

    private fun savePrefs() {
        val editedApiKey = GeminiKeyChecker.clean(etApiKey.text.toString())
        // 復号失敗後、入力欄が空のままなら保存済みの暗号文を消さない。
        if (!apiKeyReadFailed || editedApiKey.isNotBlank()) {
            val stored = SecretStore.putApiKey(prefs, editedApiKey)
            if (stored) {
                apiKeyReadFailed = false
            } else {
                Toast.makeText(this, AppLanguage.tr("APIキーを安全に暗号化できなかったため保存しませんでした。", this), Toast.LENGTH_LONG).show()
            }
        }
        prefs.edit {
            putBoolean(AiCallService.KEY_CLEAR_ARM_ON_END, cbClearArmOnEnd.isChecked)
            putBoolean(AiCallService.KEY_DELETE_BRIEF_ON_END, cbDeleteBriefOnEnd.isChecked)
            // 既定のままなら空で保存し、将来の既定プロンプト更新に追従させる
            putString(AiCallService.KEY_PROMPT, etPrompt.text.toString().let {
                if (GeminiLiveSession.isLegacyDefault(it) || it.isBlank()) "" else it
            })
            if (::etGemmaChatPrompt.isInitialized) {
                val value = etGemmaChatPrompt.text.toString()
                putString(AiCallService.KEY_GEMMA_CHAT_PROMPT,
                    if (value.isBlank() || GemmaClient.isDefaultChatPrompt(value)) "" else value)
            }
            if (::etGemmaSummaryPrompt.isInitialized) {
                val value = etGemmaSummaryPrompt.text.toString()
                putString(AiCallService.KEY_GEMMA_SUMMARY_PROMPT,
                    if (value.isBlank() || GemmaClient.isDefaultSummaryPrompt(value)) "" else value)
            }
            // 個人情報は入力のたびに自動保存する(savePersonalInfo)
            putString(AiCallService.KEY_VOICE, GeminiVoices.all[spVoice.selectedItemPosition].name)
            putString(AiCallService.KEY_TEST_LINE, etTestLine.text.toString())
        }
    }

    /** 通知が使えることを確かめてから開始(ペアリングコードは通知の入力欄に入れるため必須) */
    private val notificationGate = NotificationGate(this) {
        startPairingService()
        openWirelessDebuggingSettings()
        log("ワイヤレスデバッグ設定を開きました。ペアリングコードを通知から入力してください。")
    }

    private fun onStartPairingClicked() = notificationGate.run()

    private fun startPairingService() {
        PrivSetupActions.startPairingService(this)?.let { log(it) }
    }

    private fun openWirelessDebuggingSettings() {
        PrivSetupActions.openWirelessDebugging(this)?.let { log("開発者向けオプション: $it") }
    }

    /**
     * 接続状態の変化で画面を更新する（以前の 1 秒ポーリングの代わり）。
     * priv-kit サーバーとブリッジの接続状態は Flow で届く。
     */
    private fun observeConnection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Privilege.serverState.collect { refreshStatus() } }
                launch {
                    BridgeConnector.state.collect { b ->
                        refreshStatus()
                    }
                }
            }
        }
    }

    private fun ensureRuntimePermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.READ_PHONE_STATE
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_PHONE)
        }
        if (!Settings.canDrawOverlays(this)) {
            log("「他のアプリの上に表示」を許可すると、通話中に戻るアイコンが出ます")
        }
    }

    private fun openOverlaySettings() {
        val i = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        startActivity(i)
        log("設定画面で「表示」を許可してください")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHONE) {
            val ok = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            log(if (ok) "電話・通知の権限OK" else "権限が不足しています（通話自動検知に必要）")
        }
        if (requestCode == TranslateTabController.REQ_MIC_FALLBACK) {
            val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            log(if (ok) "マイク許可OK（翻訳のアプリ側マイク用）" else "マイクが許可されませんでした")
            // 許可後に前面サービスへ microphone タイプを付け直す(付けないとバックグラウンドで無音化される)
            if (ok) AiCallService.refreshForegroundType()
        }
        if (requestCode == REQ_MIC) {
            askCard.onMicPermissionResult(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        }
    }


    /** 個人情報は入力のたびに自動保存する(入力が止まって 0.6 秒後。画面を離れるときは即保存) */
    private val piiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val piiSaveRunnable = Runnable { savePersonalInfo() }

    private fun setupPiiAutoSave() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                piiHandler.removeCallbacks(piiSaveRunnable)
                piiHandler.postDelayed(piiSaveRunnable, 600)
            }
        }
        listOf(
            etPiiName, etPiiKana, etPiiPhone, etPiiEmail, etPiiAddress, etPiiBirthday,
            etPiiCompany, etPiiNotes, etPiiAddressKana, etPiiCompanyKana,
        ).forEach { it.addTextChangedListener(watcher) }
        cbAutoPii.values.forEach { cb -> cb.setOnCheckedChangeListener { _, _ -> savePersonalInfo() } }
    }

    private fun savePersonalInfo() {
        piiHandler.removeCallbacks(piiSaveRunnable)
        val saved = PersonalInfo.save(
            prefs,
            PersonalInfo.Fields(
                name = etPiiName.text.toString().trim(),
                kana = etPiiKana.text.toString().trim(),
                phone = etPiiPhone.text.toString().trim(),
                email = etPiiEmail.text.toString().trim(),
                address = etPiiAddress.text.toString().trim(),
                birthday = etPiiBirthday.text.toString().trim(),
                company = etPiiCompany.text.toString().trim(),
                notes = etPiiNotes.text.toString().trim(),
                addressKana = etPiiAddressKana.text.toString().trim(),
                companyKana = etPiiCompanyKana.text.toString().trim(),
                autoAllow = cbAutoPii.filterValues { it.isChecked }.keys.toSet(),
            ),
        )
        if (saved) {
            piiStorageWarningShown = false
        } else if (!piiStorageWarningShown) {
            piiStorageWarningShown = true
            Toast.makeText(this, AppLanguage.tr("個人情報を安全に保存できませんでした。既存データを保護するため保存を中止しました。", this), Toast.LENGTH_LONG).show()
        }
    }

    private fun log(msg: String) {
        msg.chunked(3000).forEach { Log.i(TAG, "[app] $it") }
    }

    /** AI 通話の状態が変わった(どのスレッドからでも呼ばれる) */
    @Suppress("UNUSED_PARAMETER")
    private fun setStatus(s: String) {
        runOnUiThread { updateAiSwitch() }
    }

    /** スイッチをオンにした: 設定を保存して AI 通話を開始。開始できなければスイッチを戻す */
    private fun startAiFromSwitch() {
        savePrefs()
        ensureRuntimePermissions()
        val ok = AiSessionManager.start(this, GeminiKeyChecker.clean(etApiKey.text.toString()))
        if (!ok) {
            Toast.makeText(this, AppLanguage.tr("AI通話を開始できません。設定タブの3項目を確認してください", this), Toast.LENGTH_LONG).show()
        }
    }

    /** スイッチ・状態文字・行の色を、AI 通話の実際の状態に合わせる */
    private fun updateAiSwitch() {
        if (!::aiSwitch.isInitialized) return
        val running = AiSessionManager.running.get()
        val st = AiSessionManager.lastStatus
        val stopping = !running && st.startsWith("停止中")
        val label = when {
            running && st.startsWith("AI通話開始中") -> "開始中…"
            running -> "通話中（AIが応答します）"
            stopping -> "停止中…"
            st == "停止" || st.startsWith("priv-kit") -> "オフ"
            else -> st  // Gemini エラーなど
        }
        aiSwitchSyncing = true
        aiSwitch.isChecked = running
        aiSwitch.isEnabled = !stopping
        aiSwitchSyncing = false
        tvAiState.text = AppLanguage.tr(label, this)
        aiRow.setBackgroundResource(if (running) R.drawable.bg_ai_row_on else R.drawable.bg_ai_row_off)
        aiRow.alpha = if (stopping) 0.6f else 1f
    }
    private fun setupSettingsSections() {
        fun bind(headerId: Int, sectionId: Int, title: String, prefKey: String) {
            val header = findViewById<TextView>(headerId)
            val section = findViewById<View>(sectionId)
            val open = prefs.getBoolean(prefKey, false)
            section.visibility = if (open) View.VISIBLE else View.GONE
            header.text = AppLanguage.tr((if (open) "▼ " else "▶ ") + title, this)
            header.setOnClickListener {
                val nowOpen = section.visibility != View.VISIBLE
                section.visibility = if (nowOpen) View.VISIBLE else View.GONE
                header.text = AppLanguage.tr((if (nowOpen) "▼ " else "▶ ") + title, this)
                prefs.edit { putBoolean(prefKey, nowOpen) }
            }
        }
        bind(R.id.headerSectionPersonal, R.id.sectionPersonal, "個人情報", "settings_section_personal")
        bind(R.id.headerSectionPrep, R.id.sectionPrep, "準備", "settings_section_prep")
        bind(R.id.headerSectionCall, R.id.sectionCall, "通話", "settings_section_call")
        bind(R.id.headerSectionTranslate, R.id.sectionTranslate, "翻訳", "settings_section_translate")
    }


}
