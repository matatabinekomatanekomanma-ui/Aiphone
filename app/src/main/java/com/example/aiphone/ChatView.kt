package com.example.aiphone

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.toColorInt
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LINE 風のバブルチャット。
 *  左（白）  = 通話の相手
 *  右（緑）  = AI（こちら側の発言）
 *  中央      = システム表示
 */
class ChatView(
    private val context: Context,
    private val scroll: ScrollView,
    private val container: LinearLayout
) {
    private val views = HashMap<ChatStore.Msg, TextView>()
    private val fmt = SimpleDateFormat("HH:mm", Locale.JAPAN)

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
    ).toInt()

    private fun themeColor(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return fallback
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(context, value.resourceId)
        } else value.data
    }

    init {
        // 通話タブだけ青い背景にせず、他タブと同じテーマ背景（昼夜対応）を使う。
        scroll.setBackgroundColor(themeColor(android.R.attr.colorBackground, Color.WHITE))
        container.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
    }

    fun renderAll() {
        container.removeAllViews()
        views.clear()
        ChatStore.messages.forEach { add(it) }
    }

    fun onMessage(m: ChatStore.Msg, isNew: Boolean) {
        if (isNew) add(m) else views[m]?.text = m.text
        scrollToEnd()
    }

    fun clear() {
        container.removeAllViews()
        views.clear()
    }

    private fun bubbleBg(color: String, ai: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(color.toColorInt())
            val r = dp(16f).toFloat()
            val s = dp(3f).toFloat()
            // 発言側の下の角だけ小さくして「しっぽ」っぽく見せる
            cornerRadii = if (ai) floatArrayOf(r, r, r, r, s, s, r, r)
            else floatArrayOf(r, r, r, r, r, r, s, s)
        }

    private fun add(m: ChatStore.Msg) {
        if (m.role == ChatStore.SYSTEM) {
            val tv = TextView(context).apply {
                text = m.text
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
                background = GradientDrawable().apply {
                    setColor("#55000000".toColorInt())
                    cornerRadius = dp(10f).toFloat()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8f); bottomMargin = dp(4f)
            }
            container.addView(tv, lp)
            scrollToEnd()
            return
        }

        val ai = m.role == ChatStore.AI

        val bubble = TextView(context).apply {
            text = m.text
            textSize = 15f
            setTextColor(if (ai) Color.WHITE else "#222222".toColorInt())
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.68f).toInt()
            background = bubbleBg(if (ai) "#06C755" else "#FFFFFF", ai)
            setTextIsSelectable(true)
        }
        val time = TextView(context).apply {
            text = fmt.format(Date(m.time))
            textSize = 10f
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.DKGRAY))
        }
        val name = TextView(context).apply {
            text = if (ai) "AI" else AppLanguage.tr("相手", context)
            textSize = 11f
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.DKGRAY))
            setPadding(dp(6f), 0, dp(6f), dp(2f))
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or (if (ai) Gravity.END else Gravity.START)
            if (ai) { addView(time); addView(spacer()); addView(bubble) }
            else { addView(bubble); addView(spacer()); addView(time) }
        }
        val side = if (ai) Gravity.END else Gravity.START
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(name, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = side })
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = side })
        }
        container.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6f) })

        views[m] = bubble
        scrollToEnd()
    }

    private fun spacer() = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(4f), 1)
    }

    private fun scrollToEnd() {
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
