package com.example.aiphone

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「準備」タブ(Gemma との対話 → まとめ → 用件の編集・保存・セット)の画面制御。
 * MainActivity から切り出したもの。画面の見た目と動作は変えていない。
 *
 * @param apiKey 画面に入力されている Gemini API キー(整形済み)を返す
 * @param openOverlaySettings 「重ねて表示」の設定画面を開く
 */
class PrepTabController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val apiKey: () -> String,
    private val openOverlaySettings: () -> Unit,
    private val log: (String) -> Unit,
) {
    private var prepBusy = false
    /** 最後のGemmaリクエストが失敗した場合のみ、同じ会話内容で再送するため保持する。 */
    private var retryPrepText: String? = null
    private var retryPrepContentsJson: String? = null
    private var retryPrepSystemInstruction: String? = null
    /** 失敗したユーザーメッセージ。再送アイコンはこの吹き出しの中だけに表示する。 */
    private var retryPrepMessage: PrepChatStore.Msg? = null
    private val prepTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setup() {
        activity.findViewById<Button>(R.id.btnPrepSend).setOnClickListener { sendPrepMessage() }
        activity.findViewById<Button>(R.id.btnPrepSummarize).setOnClickListener { summarizePrep() }
        activity.findViewById<Button>(R.id.btnPrepClearChat).setOnClickListener {
            clearPrepRetry()
            PrepChatStore.clear()
            renderPrepChat()
            setPrepStatus("履歴をクリアしました")
        }
        activity.findViewById<Button>(R.id.btnPrepUnset).setOnClickListener {
            if (CallBriefStore.isActive()) {
                CallBriefStore.clearActive()
                refreshPrepActiveBar()
                refreshPrepSavedList()
                setPrepStatus("セットを解除しました（一覧には残っています）")
            }
        }

        activity.findViewById<Button>(R.id.btnBriefCancel).setOnClickListener {
            CallBriefStore.beginEdit(null)
            showPrepChatMode()
            refreshPrepSavedList()
        }
        activity.findViewById<Button>(R.id.btnBriefSave).setOnClickListener { saveBriefFromEditors(setActive = false) }
        activity.findViewById<Button>(R.id.btnBriefDelete).setOnClickListener { deleteBriefFromEditors() }
        activity.findViewById<Button>(R.id.btnBriefSet).setOnClickListener { saveBriefFromEditors(setActive = true) }
        refreshPrepSavedList()

        PrepChatStore.listener = { activity.runOnUiThread { renderPrepChat() } }
        CallBriefStore.onChange = { activity.runOnUiThread { refreshPrepActiveBar(); refreshPrepSavedList() } }
        CallBriefStore.onStorageError = {
            activity.runOnUiThread {
                setPrepStatus("用件を安全に保存・復号できません。元データ保護のため変更を保存していません")
                android.widget.Toast.makeText(
                    activity,
                    AppLanguage.tr("用件を安全に保存・復号できません。元データ保護のため変更を保存していません", activity),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        if (CallBriefStore.isStorageBlocked()) {
            activity.runOnUiThread { setPrepStatus("保存済み用件の暗号化データを読めません。データ保護のため保存を停止しています") }
        }
        renderPrepChat()
        refreshPrepActiveBar()
        if (PrepChatStore.messages.isEmpty()) {
            PrepChatStore.add(
                PrepChatStore.SYSTEM,
                AppLanguage.tr("発信先や用件を書いて送信してください。Gemma が質問をしながら整理します。「まとめる」で項目に分け、編集してからセットできます。", activity)
            )
        }
    }

    private fun setPrepLoading(loading: Boolean, message: String = "Gemma の応答を待っています…") {
        val row = activity.findViewById<View>(R.id.prepLoadingRow)
        val tv = activity.findViewById<android.widget.TextView>(R.id.tvPrepLoading)
        row?.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) tv?.text = AppLanguage.tr(message, activity)
        activity.findViewById<Button>(R.id.btnPrepSend)?.isEnabled = !loading
        activity.findViewById<Button>(R.id.btnPrepSummarize)?.isEnabled = !loading
    }

    private fun ensureOverlayForArming() {
        activity.lifecycleScope.launch {
            val result = OverlayPermissionHelper.ensure(activity, "prep-set")
            log("[overlay-diag] prep-set permission result=$result")
            if (result.granted) {
                if (!result.wasGrantedBefore) {
                    Toast.makeText(activity, AppLanguage.tr("重ねて表示を自動許可しました", activity), Toast.LENGTH_SHORT).show()
                }
                AiCallService.refreshBubble()
            } else {
                promptOverlaySettings(
                    if (result.shellGrantSucceeded) "shell で許可コマンドは成功しましたが、OS の権限状態を確認できませんでした。診断ログを確認してください。"
                    else "shell 経由の自動許可を確認できませんでした。priv-kit 接続と診断ログを確認してください。"
                )
            }
        }
    }

    private fun promptOverlaySettings(message: String) {
        AlertDialog.Builder(activity)
            .setTitle(AppLanguage.tr("重ねて表示の許可が必要です", activity))
            .setMessage(AppLanguage.tr(message, activity))
            .setPositiveButton(AppLanguage.tr("設定を開く", activity)) { _, _ -> openOverlaySettings() }
            .setNegativeButton(AppLanguage.tr("閉じる", activity), null)
            .show()
    }

    private fun setPrepStatus(s: String) {
        activity.findViewById<TextView>(R.id.tvPrepStatus).text = AppLanguage.tr(s, activity)
    }

    fun refreshPrepActiveBar() {
        val bar = activity.findViewById<View>(R.id.prepActiveBar)
        val tv = activity.findViewById<TextView>(R.id.tvPrepActive)
        val btn = activity.findViewById<Button>(R.id.btnPrepUnset)
        val a = CallBriefStore.active
        if (a != null) {
            bar.visibility = View.VISIBLE
            tv.text = AppLanguage.tr("セット中: ${a.label()}（通話終了で解除・一覧には残る）", activity)
            btn.text = AppLanguage.tr("解除", activity)
        } else {
            bar.visibility = View.GONE
        }
    }

    private fun dp(value: Float): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun prepBubbleBackground(color: Int, user: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            val radius = dp(16f).toFloat()
            val small = dp(3f).toFloat()
            // 右側（こちら）は右下、左側（Gemma）は左下を小さくして吹き出し風にする。
            cornerRadii = if (user) {
                floatArrayOf(radius, radius, radius, radius, small, small, radius, radius)
            } else {
                floatArrayOf(radius, radius, radius, radius, radius, radius, small, small)
            }
        }

    private fun themeColor(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!activity.theme.resolveAttribute(attribute, value, true)) return fallback
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(activity, value.resourceId)
        } else value.data
    }

    private fun renderPrepChat() {
        val container = activity.findViewById<LinearLayout>(R.id.prepChatContainer)
        container.removeAllViews()
        val scroll = activity.findViewById<android.widget.ScrollView>(R.id.prepChatScroll)
        val secondaryText = themeColor(android.R.attr.textColorSecondary, Color.DKGRAY)
        val maxBubbleWidth = (activity.resources.displayMetrics.widthPixels * 0.68f).toInt()

        for (m in PrepChatStore.messages) {
            if (m.role == PrepChatStore.SYSTEM) {
                val system = TextView(activity).apply {
                    text = m.text
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                    background = GradientDrawable().apply {
                        setColor(0x99000000.toInt())
                        cornerRadius = dp(10f).toFloat()
                    }
                }
                container.addView(system, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(8f)
                    bottomMargin = dp(4f)
                })
                continue
            }

            val user = m.role == PrepChatStore.USER
            val side = if (user) Gravity.END else Gravity.START
            val messageColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = side
            }
            val speaker = TextView(activity).apply {
                text = if (user) AppLanguage.tr("あなた", activity) else "Gemma"
                textSize = 11f
                setTextColor(secondaryText)
                setPadding(dp(6f), 0, dp(6f), dp(2f))
            }
            messageColumn.addView(speaker, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = side })

            val bubble = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(8f), dp(8f), dp(5f))
                background = prepBubbleBackground(
                    if (user) Color.rgb(6, 199, 85) else Color.WHITE,
                    user,
                )
            }
            val body = TextView(activity).apply {
                text = m.text
                textSize = 15f
                setTextColor(if (user) Color.WHITE else Color.rgb(34, 34, 34))
                setTextIsSelectable(true)
                maxWidth = maxBubbleWidth - dp(20f)
            }
            bubble.addView(body, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))

            val meta = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (user) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
            }
            val time = TextView(activity).apply {
                text = prepTimeFormat.format(Date(m.time))
                textSize = 10f
                setTextColor(if (user) 0xDFFFFFFF.toInt() else 0x99000000.toInt())
            }
            if (user) {
                // こちら側の時刻と、失敗した発言にだけ付く再送アイコンを同じバブル内に置く。
                meta.addView(time, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_VERTICAL })
                if (m === retryPrepMessage && !prepBusy) {
                    val retry = ImageButton(activity).apply {
                        setImageResource(R.drawable.ic_refresh)
                        contentDescription = AppLanguage.tr("直近のメッセージを再送信", activity)
                        setColorFilter(Color.WHITE)
                        setBackgroundResourceSelectableBorderless()
                        isEnabled = true
                        setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
                        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                        setOnClickListener { retryLastPrepMessage() }
                    }
                    meta.addView(retry, LinearLayout.LayoutParams(dp(30f), dp(30f)).apply {
                        marginStart = dp(4f)
                    })
                }
            } else {
                meta.addView(time, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_VERTICAL })
            }
            bubble.addView(meta, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2f) })

            messageColumn.addView(bubble, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = side })
            container.addView(messageColumn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6f) })
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** テーマのボーダーレス選択背景をImageButtonに設定する。 */
    private fun ImageButton.setBackgroundResourceSelectableBorderless() {
        val value = TypedValue()
        if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)) {
            if (value.resourceId != 0) setBackgroundResource(value.resourceId)
            else setBackgroundColor(value.data)
        }
    }

    private fun sendPrepMessage() {
        if (prepBusy) return
        val et = activity.findViewById<EditText>(R.id.etPrepInput)
        val text = et.text.toString().trim()
        if (text.isEmpty()) return
        val key = apiKey()
        if (key.isBlank()) {
            setPrepStatus("先に設定タブで API キーを保存してください")
            return
        }
        et.setText("")
        clearPrepRetry()
        PrepChatStore.add(PrepChatStore.USER, text)
        // 送信時点の会話を固定しておき、失敗時は同じ内容を再送できるようにする。
        runPrepRequest(text, key, PrepChatStore.toApiContents(), systemInstructionOverride = null)
    }

    private fun retryLastPrepMessage() {
        if (prepBusy) return
        val text = retryPrepText ?: return
        val contentsJson = retryPrepContentsJson ?: return
        val key = apiKey()
        if (key.isBlank()) {
            setPrepStatus("先に設定タブで API キーを保存してください")
            return
        }
        val contents = try {
            org.json.JSONArray(contentsJson)
        } catch (e: org.json.JSONException) {
            setPrepStatus("再送する会話データを読み込めません。もう一度メッセージを送信してください")
            clearPrepRetry()
            return
        }
        runPrepRequest(text, key, contents, systemInstructionOverride = retryPrepSystemInstruction)
    }

    private fun clearPrepRetry() {
        retryPrepText = null
        retryPrepContentsJson = null
        retryPrepSystemInstruction = null
        retryPrepMessage = null
        renderPrepChat()
    }

    private fun runPrepRequest(
        requestText: String,
        key: String,
        contents: org.json.JSONArray,
        systemInstructionOverride: String?,
    ) {
        if (prepBusy) return
        prepBusy = true
        renderPrepChat()
        val languageCode = AppLanguage.resolvedCode(activity)
        val maxAtt = GemmaClient.maxAttemptsFromPrefs(prefs)
        setPrepLoading(true, "Gemma にリクエスト中…（応答待ち・最大${maxAtt}回まで自動リトライ）")
        setPrepStatus("Gemma 応答待ち…")
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val sys = systemInstructionOverride ?: run {
                val labels = PersonalInfo.registeredLabels(prefs, languageCode)
                val customPrompt = prefs.getString(AiCallService.KEY_GEMMA_CHAT_PROMPT, null)
                GemmaClient.chatSystemInstruction(labels, customPrompt, languageCode)
            }
            // JSONArrayを文字列として退避し、ライブラリ側で変更されても再送内容が変わらないようにする。
            val contentsJson = contents.toString()
            val result = GemmaClient.generate(
                apiKey = key,
                systemInstruction = sys,
                contents = org.json.JSONArray(contentsJson),
                enableSearch = true,
                jsonMode = false,
                thinkingLevel = GemmaClient.thinkingFromPrefs(prefs),
                maxAttempts = GemmaClient.maxAttemptsFromPrefs(prefs),
            )
            withContext(Dispatchers.Main) {
                prepBusy = false
                setPrepLoading(false)
                if (!result.ok) {
                    retryPrepText = requestText
                    retryPrepContentsJson = contentsJson
                    retryPrepSystemInstruction = sys
                    retryPrepMessage = PrepChatStore.messages.lastOrNull {
                        it.role == PrepChatStore.USER && it.text == requestText
                    }
                    renderPrepChat()
                    setPrepStatus(result.error)
                    PrepChatStore.add(PrepChatStore.SYSTEM, "Gemma失敗: ${result.error}")
                } else {
                    clearPrepRetry()
                    var reply = result.text
                    if (result.sources.isNotEmpty()) {
                        val src = result.sources.take(5).joinToString("\n") { (title, uri) -> "・$title — $uri" }
                        val sourceHeading = when (languageCode) {
                            "ja" -> "【参考（未確認）】"
                            "zh-CN" -> "【参考资料（未确认）】"
                            "zh-TW" -> "【參考資料（未確認）】"
                            "ko" -> "【참고 자료(미확인)】"
                            "es" -> "【Referencias (sin confirmar)】"
                            else -> "【References (unconfirmed)】"
                        }
                        reply += "\n\n$sourceHeading\n$src"
                    }
                    PrepChatStore.add(PrepChatStore.MODEL, reply)
                    renderPrepChat()
                    setPrepStatus("応答完了")
                }
            }
        }
    }

    private fun summarizePrep() {
        if (prepBusy) return
        val key = apiKey()
        if (key.isBlank()) {
            setPrepStatus("先に設定タブで API キーを保存してください")
            return
        }
        if (PrepChatStore.messages.none { it.role == PrepChatStore.USER }) {
            setPrepStatus("先に用件を送信してください")
            return
        }
        // 「まとめる」は別のリクエストなので、以前のチャット再送状態は解除する。
        clearPrepRetry()
        prepBusy = true
        val languageCode = AppLanguage.resolvedCode(activity)
        val maxAtt = GemmaClient.maxAttemptsFromPrefs(prefs)
        setPrepLoading(true, "用件をまとめています…（Gemma 応答待ち・最大${maxAtt}回まで自動リトライ）")
        setPrepStatus("まとめ待ち…")
        activity.lifecycleScope.launch(Dispatchers.IO) {
            // まとめ用に会話をコピーし、最後にまとめ指示を足す
            val contents = PrepChatStore.toApiContents()
            contents.put(org.json.JSONObject().apply {
                put("role", "user")
                put("parts", org.json.JSONArray().put(org.json.JSONObject().put(
                    "text", PromptLocalization.summarizeRequest(languageCode)
                )))
            })
            val result = GemmaClient.generate(
                apiKey = key,
                systemInstruction = GemmaClient.summarizeSystemInstruction(
                    prefs.getString(AiCallService.KEY_GEMMA_SUMMARY_PROMPT, null),
                    languageCode,
                ),
                contents = contents,
                enableSearch = false,
                jsonMode = true,
                thinkingLevel = GemmaClient.thinkingFromPrefs(prefs),
                maxAttempts = GemmaClient.maxAttemptsFromPrefs(prefs),
            )
            withContext(Dispatchers.Main) {
                prepBusy = false
                setPrepLoading(false)
                if (!result.ok) {
                    setPrepStatus(result.error)
                    PrepChatStore.add(PrepChatStore.SYSTEM, "まとめ失敗: ${result.error}")
                    return@withContext
                }
                val brief = parseBriefJson(result.text)
                if (brief == null) {
                    setPrepStatus("JSON の解析に失敗しました。もう一度まとめてください")
                    PrepChatStore.add(PrepChatStore.SYSTEM, "まとめ失敗: ${result.text.take(200)}")
                    return@withContext
                }
                val id = CallBriefStore.beginNewEdit()
                val withId = brief.copy(id = id)
                CallBriefStore.upsert(withId)
                CallBriefStore.beginEdit(id)
                fillBriefEditors(withId)
                showPrepEditMode()
                refreshPrepSavedList()
                setPrepStatus(
                    if (CallBriefStore.lastOperationSucceeded) "内容を確認・編集して「保存」または「セット」を押してください"
                    else "用件を端末に保存できませんでした。内容をコピーしてから保存状態を確認してください"
                )
            }
        }
    }

    private fun parseBriefJson(raw: String): CallBrief? {
        return try {
            var s = raw.trim()
            // コードフェンス除去
            if (s.startsWith("```")) {
                s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
                if (s.endsWith("```")) s = s.removeSuffix("```").trim()
            }
            val start = s.indexOf('{')
            val end = s.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            CallBrief.fromJson(org.json.JSONObject(s.substring(start, end + 1)))
        } catch (e: Exception) {
            log("parseBriefJson: ${e.message}")
            null
        }
    }

    private fun fillBriefEditors(b: CallBrief) {
        activity.findViewById<EditText>(R.id.etBriefDest).setText(b.destinationName)
        activity.findViewById<EditText>(R.id.etBriefPurpose).setText(b.purpose)
        activity.findViewById<EditText>(R.id.etBriefTell).setText(b.toTell)
        activity.findViewById<EditText>(R.id.etBriefAsk).setText(b.toAsk)
        activity.findViewById<EditText>(R.id.etBriefScope).setText(b.judgmentScope)
        activity.findViewById<EditText>(R.id.etBriefDont).setText(b.doNotSay)
        activity.findViewById<EditText>(R.id.etBriefQa).setText(b.expectedQa)
        activity.findViewById<EditText>(R.id.etBriefFacts).setText(b.researchedFacts)
        activity.findViewById<EditText>(R.id.etBriefEnd).setText(b.endCondition)
    }

    private fun readBriefEditors(): CallBrief = CallBrief(
        id = CallBriefStore.editingId ?: java.util.UUID.randomUUID().toString(),
        destinationName = activity.findViewById<EditText>(R.id.etBriefDest).text.toString().trim(),
        purpose = activity.findViewById<EditText>(R.id.etBriefPurpose).text.toString().trim(),
        toTell = activity.findViewById<EditText>(R.id.etBriefTell).text.toString().trim(),
        toAsk = activity.findViewById<EditText>(R.id.etBriefAsk).text.toString().trim(),
        judgmentScope = activity.findViewById<EditText>(R.id.etBriefScope).text.toString().trim(),
        doNotSay = activity.findViewById<EditText>(R.id.etBriefDont).text.toString().trim(),
        expectedQa = activity.findViewById<EditText>(R.id.etBriefQa).text.toString().trim(),
        researchedFacts = activity.findViewById<EditText>(R.id.etBriefFacts).text.toString().trim(),
        endCondition = activity.findViewById<EditText>(R.id.etBriefEnd).text.toString().trim(),
    )

    private fun saveBriefFromEditors(setActive: Boolean) {
        val raw = readBriefEditors()
        if (raw.isEmpty()) {
            setPrepStatus("内容が空です。少なくとも用件を入れてください")
            return
        }
        val id = CallBriefStore.editingId ?: CallBriefStore.beginNewEdit()
        val b = raw.copy(id = id)
        if (setActive) {
            CallBriefStore.setActive(b)
            if (!CallBriefStore.lastOperationSucceeded) {
                setPrepStatus("用件を安全に保存できないためセットできませんでした")
                return
            }
            showPrepChatMode()
            refreshPrepActiveBar()
            refreshPrepSavedList()
            ensureOverlayForArming()
            setPrepStatus("セットしました: ${b.label()}。右端に「電話待機」が出ます（5分で解除）。通話アプリで発信してください")
            log("通話用件をセット")
        } else {
            CallBriefStore.upsert(b)
            CallBriefStore.beginEdit(id)
            refreshPrepSavedList()
            if (CallBriefStore.lastOperationSucceeded) {
                setPrepStatus("保存しました: ${b.label()}")
                log("用件を保存")
            } else {
                setPrepStatus("用件を安全に保存できませんでした。既存データは変更していません")
            }
        }
    }

    private fun deleteBriefFromEditors() {
        val id = CallBriefStore.editingId
        if (id == null) {
            setPrepStatus("削除する用件がありません")
            return
        }
        val label = CallBriefStore.getById(id)?.label() ?: "用件"
        android.app.AlertDialog.Builder(activity)
            .setTitle(AppLanguage.tr("用件を削除", activity))
            .setMessage(AppLanguage.tr("「$label」を削除しますか？", activity))
            .setPositiveButton(AppLanguage.tr("削除", activity)) { _, _ ->
                CallBriefStore.delete(id)
                if (!CallBriefStore.lastOperationSucceeded) {
                    setPrepStatus("安全に削除を保存できませんでした。既存データは残しています")
                    return@setPositiveButton
                }
                CallBriefStore.beginEdit(null)
                showPrepChatMode()
                refreshPrepActiveBar()
                refreshPrepSavedList()
                setPrepStatus("削除しました: $label")
            }
            .setNegativeButton(AppLanguage.tr("キャンセル", activity), null)
            .show()
    }

    private fun openBriefForEdit(id: String) {
        val b = CallBriefStore.getById(id) ?: return
        CallBriefStore.beginEdit(id)
        fillBriefEditors(b)
        showPrepEditMode()
        setPrepStatus("編集中: ${b.label()}")
    }

    private fun refreshPrepSavedList() {
        val list = activity.findViewById<android.widget.LinearLayout>(R.id.prepSavedList) ?: return
        val title = activity.findViewById<android.widget.TextView>(R.id.tvPrepSavedTitle)
        list.removeAllViews()
        val items = CallBriefStore.all()
        title?.visibility = if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        if (items.isEmpty()) return
        val density = activity.resources.displayMetrics.density
        val pad = (10 * density).toInt()
        for (b in items) {
            val row = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(R.drawable.bg_input)
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.topMargin = (4 * density).toInt()
                layoutParams = lp
                isClickable = true
                isFocusable = true
            }
            val texts = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val isAct = CallBriefStore.active?.id == b.id
            val titleTv = android.widget.TextView(activity).apply {
                text = if (isAct) "● ${b.label()}" else b.label()
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val subTv = android.widget.TextView(activity).apply {
                text = b.purpose.take(48).ifBlank { b.summaryLine() }
                textSize = 12f
            }
            texts.addView(titleTv)
            texts.addView(subTv)
            row.addView(texts)
            val menuBtn = android.widget.TextView(activity).apply {
                text = "⋮"
                textSize = 20f
                setPadding(pad, 0, pad, 0)
                setOnClickListener { v -> showBriefItemMenu(v, b.id) }
            }
            row.addView(menuBtn)
            row.setOnClickListener { openBriefForEdit(b.id) }
            row.setOnLongClickListener {
                showBriefItemMenu(row, b.id)
                true
            }
            list.addView(row)
        }
    }

    private fun showBriefItemMenu(anchor: android.view.View, id: String) {
        val popup = android.widget.PopupMenu(activity, anchor)
        popup.menu.add(0, 1, 0, AppLanguage.tr("編集", activity))
        popup.menu.add(0, 2, 1, AppLanguage.tr("セット", activity))
        popup.menu.add(0, 3, 2, AppLanguage.tr("削除", activity))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openBriefForEdit(id)
                2 -> {
                    if (CallBriefStore.setActiveById(id)) {
                        refreshPrepActiveBar()
                        refreshPrepSavedList()
                        ensureOverlayForArming()
                        setPrepStatus("セットしました: ${CallBriefStore.getById(id)?.label()}。右端に「電話待機」が表示されます（5分で解除）")
                    }
                }
                3 -> {
                    val label = CallBriefStore.getById(id)?.label() ?: "用件"
                    android.app.AlertDialog.Builder(activity)
                        .setTitle(AppLanguage.tr("用件を削除", activity))
                        .setMessage(AppLanguage.tr("「$label」を削除しますか？", activity))
                        .setPositiveButton(AppLanguage.tr("削除", activity)) { _, _ ->
                            CallBriefStore.delete(id)
                            if (!CallBriefStore.lastOperationSucceeded) {
                                setPrepStatus("安全に削除を保存できませんでした。既存データは残しています")
                                return@setPositiveButton
                            }
                            if (CallBriefStore.editingId == id) {
                                CallBriefStore.beginEdit(null)
                                showPrepChatMode()
                            }
                            refreshPrepActiveBar()
                            refreshPrepSavedList()
                            setPrepStatus("削除しました: $label")
                        }
                        .setNegativeButton(AppLanguage.tr("キャンセル", activity), null)
                        .show()
                }
            }
            true
        }
        popup.show()
    }

    private fun showPrepEditMode() {
        activity.findViewById<View>(R.id.prepSavedList)?.visibility = View.GONE
        activity.findViewById<View>(R.id.tvPrepSavedTitle)?.visibility = View.GONE
        activity.findViewById<View>(R.id.prepChatScroll).visibility = View.GONE
        activity.findViewById<View>(R.id.prepInputRow).visibility = View.GONE
        activity.findViewById<View>(R.id.btnPrepSummarize).visibility = View.GONE
        activity.findViewById<View>(R.id.btnPrepClearChat).visibility = View.GONE
        activity.findViewById<View>(R.id.prepEditScroll).visibility = View.VISIBLE
    }

    private fun showPrepChatMode() {
        activity.findViewById<View>(R.id.prepEditScroll).visibility = View.GONE
        activity.findViewById<View>(R.id.prepSavedList)?.visibility = View.VISIBLE
        activity.findViewById<View>(R.id.tvPrepSavedTitle)?.visibility = View.VISIBLE
        activity.findViewById<View>(R.id.prepChatScroll).visibility = View.VISIBLE
        activity.findViewById<View>(R.id.prepInputRow).visibility = View.VISIBLE
        activity.findViewById<View>(R.id.btnPrepSummarize).visibility = View.VISIBLE
        activity.findViewById<View>(R.id.btnPrepClearChat).visibility = View.VISIBLE
    }
}
