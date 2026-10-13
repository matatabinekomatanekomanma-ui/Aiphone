package com.example.aiphone

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通話タブの「AI からの確認カード」(ask_user / 個人情報の許可)の画面制御。MainActivity から切り出したもの。
 * 音声入力(マイク)も含む。
 *
 * @param reqMic マイク権限リクエストの requestCode(結果は MainActivity が onMicPermissionResult へ渡す)
 */
class AskCardController(
    private val activity: AppCompatActivity,
    private val reqMic: Int,
) {
    private lateinit var askCard: View
    private lateinit var tvAskTitle: TextView
    private lateinit var tvAskQuestion: TextView
    private lateinit var etAskText: EditText
    private lateinit var askButtons: List<Button>
    private var shownAskId: String? = null
    private lateinit var btnAskMic: Button
    private lateinit var tvAskMic: TextView
    private var voice: VoiceInput? = null

    fun setup() {
        askCard = activity.findViewById(R.id.askCard)
        tvAskTitle = activity.findViewById(R.id.tvAskTitle)
        tvAskQuestion = activity.findViewById(R.id.tvAskQuestion)
        etAskText = activity.findViewById(R.id.etAskText)
        askButtons = listOf(R.id.btnAsk1, R.id.btnAsk2, R.id.btnAsk3).map { activity.findViewById<Button>(it) }
        askButtons.forEachIndexed { i, b ->
            b.setOnClickListener {
                AskUserStore.current()?.options?.getOrNull(i)?.let { answerAsk(it, false, i) }
            }
        }
        btnAskMic = activity.findViewById(R.id.btnAskMic)
        tvAskMic = activity.findViewById(R.id.tvAskMic)
        voice = VoiceInput(
            activity,
            onText = { t, _ -> etAskText.setText(t); etAskText.setSelection(t.length) },
            onState = { st -> tvAskMic.text = AppLanguage.tr(st, activity); tvAskMic.visibility = View.VISIBLE },
            onEnd = { btnAskMic.text = "🎤" }
        )
        btnAskMic.setOnClickListener {
            if (voice?.listening == true) voice?.stop() else startMic()
        }
        activity.findViewById<Button>(R.id.btnAskSend).setOnClickListener {
            val t = etAskText.text.toString().trim()
            if (t.isEmpty()) {
                etAskText.error = AppLanguage.tr("返答内容を入力してください", activity)
                return@setOnClickListener
            }
            answerAsk(t, true)
        }
    }

    fun render() {
        if (!::askCard.isInitialized) return
        val req = AskUserStore.current()
        if (req == null) {
            stopMic()
            askCard.visibility = View.GONE
            shownAskId = null
            return
        }
        if (shownAskId != req.id) {
            stopMic()
            etAskText.setText("")
            etAskText.error = null
            shownAskId = req.id
        }
        askCard.visibility = View.VISIBLE
        val n = AskUserStore.count()
        val title = if (req.isPii) "AIが個人情報の開示許可を求めています" else "AIが判断を求めています"
        tvAskTitle.text = AppLanguage.tr(if (n > 1) "$title(あと${n - 1}件待ち)" else title, activity)
        tvAskQuestion.text = req.question
        askButtons.forEachIndexed { i, b ->
            val t = req.options.getOrNull(i)
            b.visibility = if (t == null) View.GONE else View.VISIBLE
            b.text = t ?: ""
            b.isEnabled = !req.responding
        }
        btnAskMic.isEnabled = !req.responding
        activity.findViewById<Button>(R.id.btnAskSend).isEnabled = !req.responding
    }

    private fun startMic() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), reqMic)
            return
        }
        etAskText.error = null
        btnAskMic.text = "■"
        voice?.start()
    }

    fun stopMic() {
        voice?.cancel()
        if (::tvAskMic.isInitialized) tvAskMic.visibility = View.GONE
    }

    /** マイク権限のダイアログの結果 */
    fun onMicPermissionResult(granted: Boolean) {
        if (granted) {
            startMic()
        } else {
            tvAskMic.text = AppLanguage.tr("マイクの権限が許可されていません", activity)
            tvAskMic.visibility = View.VISIBLE
        }
    }

    private fun answerAsk(text: String, free: Boolean, optionIndex: Int = -1) {
        val req = AskUserStore.current() ?: return
        stopMic()
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(etAskText.windowToken, 0)
        activity.lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) {
                AiSessionManager.answerAskUser(req.id, text, free, optionIndex)
            }
            if (sent) {
                etAskText.setText("")
                etAskText.error = null
                render()
            } else {
                // 送信失敗時は入力と確認カードを残し、接続回復後に再試行できるようにする。
                etAskText.error = AppLanguage.tr("回答を送れませんでした。接続を確認して再試行してください", activity)
                render()
            }
        }
    }
}
