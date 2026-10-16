package com.example.aiphone

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * 翻訳タブ。言語を選んで「セット」すると、次の通話で翻訳が自動起動する(通話終了で解除)。
 * 「今すぐ開始」は、通話中に手動で試すためのボタン。
 */
class TranslateTabController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val apiKey: () -> String,
    private val log: (String) -> Unit,
) {
    companion object {
        const val REQ_MIC_FALLBACK = 2004
    }

    private lateinit var spMy: Spinner
    private lateinit var spTheir: Spinner
    private lateinit var btnSet: Button
    private lateinit var btnUnset: Button
    private lateinit var btnNow: Button
    private lateinit var tvState: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView

    fun setup() {
        spMy = activity.findViewById(R.id.spTrMy)
        spTheir = activity.findViewById(R.id.spTrTheir)
        btnSet = activity.findViewById(R.id.btnTrSet)
        btnUnset = activity.findViewById(R.id.btnTrUnset)
        btnNow = activity.findViewById(R.id.btnTrNow)
        tvState = activity.findViewById(R.id.tvTrState)
        tvLog = activity.findViewById(R.id.tvTrLog)
        logScroll = activity.findViewById(R.id.trLogScroll)

        fun bind(sp: Spinner, items: List<TranslateLang>, key: String, def: String) {
            sp.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, items.map { TranslateLangs.displayLabel(it, activity) })
            sp.setSelection(TranslateLangs.indexIn(items, prefs.getString(key, def) ?: def))
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit { putString(key, items[pos].code) }
                    updateDependentChecks()
                    refresh()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        bind(spMy, TranslateLangs.forMy, TranslateStore.KEY_MY, TranslateStore.DEFAULT_MY)
        bind(spTheir, TranslateLangs.forTheir, TranslateStore.KEY_THEIR, TranslateStore.DEFAULT_THEIR)

        updateDependentChecks()

        btnSet.setOnClickListener { arm() }
        btnUnset.setOnClickListener {
            TranslateStore.setArmed(false)
            AiCallService.refreshBubble()
        }
        btnNow.setOnClickListener { toggleNow() }

        TranslateManager.onStatus = { activity.runOnUiThread { refresh() } }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TranslateStore.armed.collect {
                        refresh()
                        AiCallService.refreshBubble()
                    }
                }
                launch { TranslateStore.lines.collect { render(it) } }
            }
        }
        refresh()
    }

    private fun arm() {
        val my = TranslateStore.myLang(prefs)
        val their = TranslateStore.theirLang(prefs)
        if (!checkDirections(my, their)) return
        // アプリ側マイク(特権でマイクが録れなかったときの代替)用。許可がなくても特権側で動く可能性はある
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_FALLBACK)
        }
        TranslateStore.setArmed(true)
        ensureOverlayForArming()
        log("翻訳セット: 相手→自分=$my / 自分→相手=$their")
        toast("セットしました。5分以内の通話で翻訳を自動開始します")
    }

    private fun toggleNow() {
        if (TranslateManager.running.get()) {
            TranslateManager.stop()
            refresh()
            return
        }
        val key = apiKey()
        if (key.isBlank()) {
            toast("Gemini API キーが未設定です")
            return
        }
        if (AiSessionManager.running.get()) {
            toast("先に AI 通話を停止してください")
            return
        }
        val my = TranslateStore.myLang(prefs)
        val their = TranslateStore.theirLang(prefs)
        if (!checkDirections(my, their)) return
        val ok = TranslateManager.start(
            activity, key, my, their,
            muteOriginalPref = TranslateStore.muteOriginal(prefs),
            monitorOut = TranslateStore.monitorOut(prefs),
            echoTarget = TranslateStore.echoTarget(prefs),
        )
        if (!ok) toast("開始できません（priv-kit の接続・楽天リンクのインストールを確認）")
        refresh()
        AiCallService.refreshBubble()
    }

    /** 少なくとも 1 方向は必要。両方とも明示的に同じ言語だと意味がないので止める */
    private fun checkDirections(my: String, their: String): Boolean {
        if (my == TranslateLangs.OFF && their == TranslateLangs.OFF) {
            toast("どちらかの言語を「翻訳しない」以外にしてください")
            return false
        }
        if (my != TranslateLangs.OFF && my == their) {
            toast("自分の言語と相手の言語が同じです。別の言語を選んでください")
            return false
        }
        return true
    }

    private fun directionSummary(my: String, their: String): String = listOfNotNull(
        if (my != TranslateLangs.OFF) "相手→自分: ${TranslateLangs.displayLabel(my, activity)}" else null,
        if (their != TranslateLangs.OFF) "自分→相手: ${TranslateLangs.displayLabel(their, activity)}" else null,
    ).joinToString(" / ")

    /** 「元の声を消す」は 相手→自分、「モニター」は 自分→相手 のときだけ意味がある */
    private fun updateDependentChecks() {
    }

    fun refresh() {
        if (!::tvState.isInitialized) return
        val running = TranslateManager.running.get()
        val armed = TranslateStore.isArmed()
        val my = TranslateStore.myLang(prefs)
        val their = TranslateStore.theirLang(prefs)
        when {
            running -> {
                tvState.text = AppLanguage.tr("翻訳中: ${TranslateManager.lastStatus}", activity)
                tvState.setTextColor("#2E7D32".toColorInt())
            }
            armed -> {
                tvState.text = AppLanguage.tr("セット中: 次の通話で自動開始（${directionSummary(my, their)}）", activity)
                tvState.setTextColor("#2E7D32".toColorInt())
            }
            else -> {
                tvState.text = AppLanguage.tr("未セット（${TranslateManager.lastStatus}）", activity)
                tvState.setTextColor(activity.getColor(R.color.sub_text))
            }
        }
        btnSet.isEnabled = !armed
        btnUnset.isEnabled = armed
        btnNow.text = AppLanguage.tr(if (running) "停止" else "今すぐ開始（通話中のテスト）", activity)
    }

    private fun render(lines: List<String>) {
        tvLog.text = if (lines.isEmpty()) AppLanguage.tr("翻訳が始まると、ここに原文と訳が表示されます", activity) else lines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }


    private fun ensureOverlayForArming() {
        activity.lifecycleScope.launch {
            val result = OverlayPermissionHelper.ensure(activity, "translate-set")
            log("[overlay-diag] translate-set permission result=$result")
            if (result.granted) {
                if (!result.wasGrantedBefore) {
                    Toast.makeText(activity, AppLanguage.tr("重ねて表示を自動許可しました", activity), Toast.LENGTH_SHORT).show()
                }
                AiCallService.refreshBubble()
            } else {
                AlertDialog.Builder(activity)
                    .setTitle(AppLanguage.tr("重ねて表示の許可が必要です", activity))
                    .setMessage(AppLanguage.tr(
                        if (result.shellGrantSucceeded) "shell で許可コマンドは成功しましたが、OS の権限状態を確認できませんでした。診断ログを確認してください。"
                        else "shell 経由の自動許可を確認できませんでした。priv-kit 接続と診断ログを確認してください。", activity
                    ))
                    .setPositiveButton(AppLanguage.tr("設定を開く", activity)) { _, _ ->
                        try {
                            activity.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    "package:${activity.packageName}".toUri(),
                                ),
                            )
                        } catch (e: Exception) {
                            log("[overlay-diag] overlay settings launch failed: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                    .setNegativeButton(AppLanguage.tr("閉じる", activity), null)
                    .show()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(activity, AppLanguage.tr(msg, activity), Toast.LENGTH_SHORT).show()
}
