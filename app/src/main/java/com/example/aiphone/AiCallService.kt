package com.example.aiphone

import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import priv.kit.core.Privilege

/**
 * 通話検知・セット中の通話でのAI起動・戻るアイコン表示を担当するフォアグラウンドサービス。
 * priv-kit UserService(AudioBridge) への接続もここで保持する（Activity が閉じても通話中の AI が止まらない）。
 */
class AiCallService : Service() {

    companion object {
        private const val TAG = "RLAudio"
        private const val CH_ID = "ai_call"
        private const val NOTI_ID = 42
        private const val CH_WARN = "ai_warn"
        private const val NOTI_WARN = 43
        private const val WARN_FIRST_DELAY_MS = 8000L
        private const val WARN_INTERVAL_MS = 15000L
        const val PREFS = "rlaudio"
        const val KEY_API = "gemini_api_key"
        /** 通話終了後に電話待機（セット）を解除する（既定 true） */
        const val KEY_CLEAR_ARM_ON_END = "clear_arm_on_call_end"
        /** 通話終了後にセットした用件を一覧から削除する（既定 false） */
        const val KEY_DELETE_BRIEF_ON_END = "delete_brief_on_call_end"
        const val KEY_PROMPT = "gemini_prompt"
        const val KEY_VOICE = "gemini_voice"
        const val KEY_TEST_LINE = "gemini_test_line"
        const val KEY_VAD_START = "vad_start_sensitivity"
        const val KEY_VAD_END = "vad_end_sensitivity"
        const val KEY_VAD_SILENCE_MS = "vad_silence_ms"
        const val KEY_GEMMA_THINKING = "gemma_thinking_level"
        /** Gemma 準備チャット用の編集可能なシステム指示 */
        const val KEY_GEMMA_CHAT_PROMPT = "gemma_prep_chat_prompt"
        /** Gemma 用件まとめ用の編集可能なシステム指示 */
        const val KEY_GEMMA_SUMMARY_PROMPT = "gemma_prep_summary_prompt"
        /** Gemma リクエストの最大試行回数（1〜10、既定 5） */
        const val KEY_GEMMA_MAX_ATTEMPTS = "gemma_max_attempts"
        // 本人の個人情報（AI に渡す。相手への開示は ask_user で確認）
        const val KEY_PII_NAME = "pii_name"
        const val KEY_PII_KANA = "pii_kana"
        const val KEY_PII_PHONE = "pii_phone"
        const val KEY_PII_EMAIL = "pii_email"
        const val KEY_PII_ADDRESS = "pii_address"
        const val KEY_PII_BIRTHDAY = "pii_birthday"
        const val KEY_PII_COMPANY = "pii_company"
        const val KEY_PII_NOTES = "pii_notes"
        /** 住所・勤務先の読み(任意) */
        const val KEY_PII_ADDRESS_KANA = "pii_address_kana"
        const val KEY_PII_COMPANY_KANA = "pii_company_kana"
        /** 項目ごとの「許可なしで伝えてよい」フラグ。キー名は pii_auto_<項目キー> */
        const val KEY_PII_AUTO_PREFIX = "pii_auto_"

        @Volatile var appInForeground = false
        @Volatile private var instance: AiCallService? = null

        fun start(context: Context) {
            val i = Intent(context, AiCallService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AiCallService::class.java))
        }

        /** RECORD_AUDIO が許可された直後などに、前面サービスの種別(microphone の有無)を付け直す */
        fun refreshForegroundType() {
            instance?.startAsForeground()
        }

        /** 戻るアイコンの表示を再評価する（サービス未起動なら何もしない。バックグラウンドからも安全） */
        fun refreshBubble() {
            val svc = instance ?: return
            svc.handler.post { svc.updateBubble() }
        }

        /** 重ねて表示の「AI ON」から呼ぶ。開始できなければ理由の文を返す(サービス未起動も含む) */
        fun startAiNow(): String? {
            val svc = instance ?: return "サービスが起動していません。アプリを開いてください"
            return svc.startAiNow() // 成功は null(エルビス演算子で「サービスなし」と混同しないこと)
        }

        fun setForeground(fg: Boolean) {
            appInForeground = fg
            refreshBubble()
        }
    }

    private var callMonitor: CallStateMonitor? = null
    private var bubble: ReturnBubbleController? = null
    private var callActive = false
    /** この通話で翻訳を起動した(通話終了でセット解除する)。着信などで使わなかった通話では解除しない */
    private var translateUsed = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastWarnText: String? = null

    private val warnRunnable = object : Runnable {
        override fun run() {
            checkPrivilege()
            handler.postDelayed(this, WARN_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        TranslateStore.init(getSharedPreferences(PREFS, MODE_PRIVATE))
        ensureChannel()
        startAsForeground()
        BridgeConnector.start(scope)
        bubble = ReturnBubbleController(this)
        callMonitor = CallStateMonitor(
            this,
            onActive = { handler.post { onCallActive() } },
            onIdle = { handler.post { onCallIdle() } },
        ).also { it.start() }
        handler.postDelayed(warnRunnable, WARN_FIRST_DELAY_MS)
        Log.i(TAG, "AiCallService created (priv-kit)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(warnRunnable)
        BridgeConnector.stop()
        scope.cancel()
        callMonitor?.stop()
        callMonitor = null
        bubble?.hide()
        bubble = null
        Log.i(TAG, "AiCallService destroyed")
        super.onDestroy()
    }

    /**
     * 通話が始まった(着信・発信は区別しない)。
     *  - 翻訳または用件をセットしてあれば、AI(または翻訳)を自動で起動する
     *  - セットしていなければ起動しない。重ねて表示を「AI OFF の状態」で出し、本人が「AI ON」を押せば
     *    何もセットしていない AI を起動する([startAiNow])
     */
    private fun onCallActive() {
        callActive = true
        updateBubble()
        handler.post { checkPrivilege() }
        Log.i(
            TAG,
            "onCallActive translateArmed=${TranslateStore.isArmed()} briefArmed=${CallBriefStore.isActive()} " +
                "bridge=${BridgeConnector.bridge != null}",
        )
        when {
            // 翻訳がセットされていれば、AI 代行の代わりに翻訳を起動する(両方は同時に動かさない)
            TranslateStore.isArmed() -> startTranslateIfArmed()
            CallBriefStore.isActive() -> startAi()
            else -> Log.i(TAG, "セットなし: AI は自動起動しません")
        }
    }

    /** 重ねて表示の「AI ON」: 何もセットしていない状態の AI を、いま起動する。開始できなければ理由を返す */
    private fun startAiNow(): String? {
        if (AiSessionManager.running.get() || TranslateManager.running.get()) return null
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val key = GeminiKeyChecker.clean(readStoredApiKey(prefs))
        if (key.isEmpty()) return "Gemini API キーが未設定です"
        if (BridgeConnector.bridge == null) return "priv-kit が未接続です。アプリを開いて接続を確認してください"
        return if (AiSessionManager.start(this, key)) null else "AI を開始できませんでした"
    }

    private fun onCallIdle() {
        callActive = false
        if (AiSessionManager.running.get()) {
            AiSessionManager.stop()
        }
        if (TranslateManager.running.get()) {
            TranslateManager.stop()
        }
        if (translateUsed) {
            translateUsed = false
            TranslateStore.setArmed(false)
            Log.i(TAG, "通話終了のため翻訳のセットを解除しました")
        }
        applyPostCallArmPolicy()
        updateBubble()
    }

    /** 設定に従い、通話終了後の電話待機・用件の扱いを適用する */
    private fun applyPostCallArmPolicy() {
        if (!CallBriefStore.isActive()) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val clearArm = prefs.getBoolean(KEY_CLEAR_ARM_ON_END, true)
        val deleteBrief = prefs.getBoolean(KEY_DELETE_BRIEF_ON_END, false)
        if (!clearArm && !deleteBrief) {
            Log.i(TAG, "post-call: keep arm (no clear/delete)")
            return
        }
        val id = CallBriefStore.active?.id
        if (deleteBrief && id != null) {
            CallBriefStore.delete(id)
            Log.i(TAG, "post-call: deleted prepared call brief")
        } else if (clearArm) {
            CallBriefStore.clearActive()
            Log.i(TAG, "post-call: cleared prepared call brief arm")
        }
    }


    private fun readStoredApiKey(prefs: android.content.SharedPreferences): String =
        when (val result = SecretStore.readApiKey(prefs)) {
            is SecretStore.ReadResult.Success -> {
                if (result.migrationFailed) Log.w(TAG, "旧 API キーの暗号化移行に失敗。設定を開いて保存し直してください")
                result.value
            }
            is SecretStore.ReadResult.Failure -> {
                Log.e(TAG, "API キーを復号できません (${result.reason})。設定から再登録してください")
                ""
            }
        }

    private fun startTranslateIfArmed() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val key = GeminiKeyChecker.clean(readStoredApiKey(prefs))
        if (key.isEmpty()) {
            Log.w(TAG, "翻訳セット中だが API キー未設定")
            return
        }
        val my = TranslateStore.myLang(prefs)
        val their = TranslateStore.theirLang(prefs)
        val mute = TranslateStore.muteOriginal(prefs)
        val monitor = TranslateStore.monitorOut(prefs)
        val echo = TranslateStore.echoTarget(prefs)
        fun go() {
            translateUsed = true
            TranslateManager.start(this, key, my, their, mute, monitor, echo)
        }
        if (BridgeConnector.bridge == null) {
            Log.w(TAG, "翻訳: priv-kit ブリッジ未接続。再試行します")
            handler.postDelayed({
                if (callActive && BridgeConnector.bridge != null && !TranslateManager.running.get()) go()
            }, 2500)
            return
        }
        if (!TranslateManager.running.get()) go()
    }

    private fun startAi() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val key = GeminiKeyChecker.clean(readStoredApiKey(prefs))
        if (key.isEmpty()) {
            Log.w(TAG, "通話中だが API キー未設定")
            return
        }
        if (BridgeConnector.bridge == null) {
            Log.w(TAG, "通話中だが priv-kit ブリッジ未接続")
            handler.postDelayed({
                if (callActive && BridgeConnector.bridge != null && !AiSessionManager.running.get()) {
                    Log.i(TAG, "AI start (retry after bridge ready)")
                    AiSessionManager.start(this, key)
                }
            }, 2500)
            return
        }
        if (!AiSessionManager.running.get()) {
            Log.i(TAG, "AI start (call brief armed)")
            AiSessionManager.start(this, key)
        }
    }


    private fun checkPrivilege() {
        val nm = getSystemService(NotificationManager::class.java)
        if (BridgeConnector.bridge != null) {
            if (lastWarnText != null) nm.cancel(NOTI_WARN)
            lastWarnText = null
            return
        }
        val serverUp = Privilege.serverState.value != null
        val reason = when {
            !serverUp -> "priv-kit が起動していません。アプリを開いて、設定タブの priv-kit 接続の「設定」を押してください。"
            else -> "AudioBridge に未接続です。アプリを開いて接続状態を確認してください。"
        }
        val text = AppLanguage.tr(
            if (callActive) "通話中ですが AI は動作していません。$reason" else "AI通話は動作しません。$reason",
            this,
        )
        if (text == lastWarnText) return
        lastWarnText = text

        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CH_WARN)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(AppLanguage.tr("priv-kit 未接続：機能していません", this))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            nm.notify(NOTI_WARN, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify denied", e)
        }
    }

    private fun updateBubble() {
        val tr = TranslateManager.running.get()
        val tArmed = TranslateStore.isArmed()
        val ai = AiSessionManager.running.get() || tr
        val armed = CallBriefStore.isActive() || tArmed
        // セット中（電話待機）は自アプリ前面でも表示。通話/AI 中は背面のときのみ（従来どおり）
        val show = when {
            armed -> true
            (callActive || ai) && !appInForeground -> true
            else -> false
        }
        val brief = CallBriefStore.active
        val sub = if (tr || tArmed) {
            TranslateStore.brief(getSharedPreferences(PREFS, MODE_PRIVATE))
        } else {
            brief?.label()?.take(16).orEmpty()
        }
        Log.i(
            TAG,
            "updateBubble show=$show call=$callActive ai=$ai armed=$armed " +
                "fg=$appInForeground overlay=${bubble?.canDrawOverlays()} brief=$sub",
        )
        if (!show) {
            bubble?.hide()
            return
        }
        val mode = when {
            ai -> ReturnBubbleController.Mode.AI_ACTIVE
            callActive -> ReturnBubbleController.Mode.IN_CALL
            else -> ReturnBubbleController.Mode.WAITING
        }
        val title = when (mode) {
            ReturnBubbleController.Mode.WAITING -> when {
                tArmed && CallBriefStore.isActive() -> "電話待機"
                tArmed -> "翻訳待機"
                else -> "電話待機"
            }
            ReturnBubbleController.Mode.IN_CALL -> "通話中"
            ReturnBubbleController.Mode.AI_ACTIVE -> if (tr) "翻訳中" else "AI通話中"
        }
        bubble?.show(mode, AppLanguage.tr(title, this), sub.ifBlank { AppLanguage.tr("用件なし", this) })
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, AppLanguage.tr("AI通話", this), NotificationManager.IMPORTANCE_LOW).apply {
                description = AppLanguage.tr("通話中の AI 支援", this@AiCallService)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WARN, AppLanguage.tr("動作警告", this), NotificationManager.IMPORTANCE_HIGH).apply {
                description = AppLanguage.tr("priv-kit 未接続などで AI 通話が動作しないとき", this@AiCallService)
            },
        )
    }

    private fun startAsForeground() {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle(AppLanguage.tr("AI通話モニター", this))
            .setContentText(AppLanguage.tr("セット中の通話で AI を起動します", this))
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()

        // 翻訳(自分→相手)のアプリ側マイク録音用に、RECORD_AUDIO が許可済みなら microphone タイプも付ける。
        // (バックグラウンドの録音は OS に無音化されるため。付けられない環境では従来どおりの種別で起動する)
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    (if (micOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
                startForeground(NOTI_ID, n, type)
            } else if (micOk) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTI_ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "microphone タイプでの前面化に失敗。従来の種別で起動します: ${e.javaClass.simpleName}: ${e.message}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTI_ID, n)
            }
        }
    }
}
