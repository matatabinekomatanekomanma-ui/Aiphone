package com.example.aiphone

import android.content.SharedPreferences
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

    fun setup() {
        activity.findViewById<Button>(R.id.btnPrepSend).setOnClickListener { sendPrepMessage() }
        activity.findViewById<Button>(R.id.btnPrepSummarize).setOnClickListener { summarizePrep() }
        activity.findViewById<Button>(R.id.btnPrepClearChat).setOnClickListener {
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
        if (Settings.canDrawOverlays(activity)) {
            AiCallService.refreshBubble()
            return
        }
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val ok = BridgeConnector.grantOverlayPermission(activity.packageName)
            withContext(Dispatchers.Main) {
                // 付与直後は Settings が遅延することがあるため短く再確認
                var granted = Settings.canDrawOverlays(activity)
                if (ok && !granted) {
                    kotlinx.coroutines.delay(400)
                    granted = Settings.canDrawOverlays(activity)
                }
                if (granted) {
                    Toast.makeText(activity, AppLanguage.tr("重ねて表示を自動許可しました", activity), Toast.LENGTH_SHORT).show()
                    AiCallService.refreshBubble()
                } else {
                    promptOverlaySettings(
                        if (ok) "自動許可の反映を確認できませんでした。設定画面で「他のアプリの上に表示」を許可してください。"
                        else "重ねて表示を自動許可できませんでした。priv-kit の接続を確認するか、設定画面で許可してください。"
                    )
                }
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

    private fun renderPrepChat() {
        val container = activity.findViewById<LinearLayout>(R.id.prepChatContainer)
        container.removeAllViews()
        val scroll = activity.findViewById<android.widget.ScrollView>(R.id.prepChatScroll)
        for (m in PrepChatStore.messages) {
            val tv = TextView(activity)
            val pad = (8 * activity.resources.displayMetrics.density).toInt()
            tv.setPadding(pad, pad, pad, pad)
            when (m.role) {
                PrepChatStore.USER -> {
                    tv.text = "${AppLanguage.tr("あなた", activity)}\n${m.text}"
                    tv.setBackgroundColor(0xFFCDEBD8.toInt())
                    tv.setTextColor(0xFF10281A.toInt())
                }
                PrepChatStore.MODEL -> {
                    tv.text = "Gemma\n${m.text}"
                    tv.setBackgroundColor(0xFFFFFFFF.toInt())
                    tv.setTextColor(0xFF10281A.toInt())
                }
                else -> {
                    tv.text = m.text
                    tv.setTextColor(0xFF555555.toInt())
                    tv.textSize = 12f
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = pad / 2
            container.addView(tv, lp)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
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
        PrepChatStore.add(PrepChatStore.USER, text)
        prepBusy = true
        val languageCode = AppLanguage.resolvedCode(activity)
        val maxAtt = GemmaClient.maxAttemptsFromPrefs(prefs)
        setPrepLoading(true, "Gemma にリクエスト中…（応答待ち・最大${maxAtt}回まで自動リトライ）")
        setPrepStatus("Gemma 応答待ち…")
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val labels = PersonalInfo.registeredLabels(prefs, languageCode)
            val customPrompt = prefs.getString(AiCallService.KEY_GEMMA_CHAT_PROMPT, null)
            val sys = GemmaClient.chatSystemInstruction(labels, customPrompt, languageCode)
            val result = GemmaClient.generate(
                apiKey = key,
                systemInstruction = sys,
                contents = PrepChatStore.toApiContents(),
                enableSearch = true,
                jsonMode = false,
                thinkingLevel = GemmaClient.thinkingFromPrefs(prefs),
                maxAttempts = GemmaClient.maxAttemptsFromPrefs(prefs),
            )
            withContext(Dispatchers.Main) {
                prepBusy = false
                setPrepLoading(false)
                if (!result.ok) {
                    setPrepStatus(result.error)
                    PrepChatStore.add(PrepChatStore.SYSTEM, "Gemma失敗: ${result.error}")
                } else {
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
