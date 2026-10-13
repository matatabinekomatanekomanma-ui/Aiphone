package com.example.aiphone

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Button
import android.widget.Toast

/**
 * 画面右端の操作パネル。タイトル部分をドラッグして移動し、AI ON/OFF とアプリ起動を独立操作する。
 */
class ReturnBubbleController(private val context: Context) {
    companion object {
        private const val TAG = "RLAudio"
        private const val WIDTH_DP = 176f
        private const val HEIGHT_DP = 84f
    }

    enum class Mode { WAITING, IN_CALL, AI_ACTIVE }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastTitle: String = ""
    private var lastSubtitle: String = ""
    private var lastMode: Mode? = null

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /** 表示または内容更新 */
    fun show(mode: Mode, title: String, subtitle: String) {
        if (!canDrawOverlays()) {
            Log.w(TAG, "overlay permission missing")
            return
        }
        val t = title.ifBlank {
            AppLanguage.tr(when (mode) {
                Mode.WAITING -> "電話待機"
                Mode.IN_CALL -> "通話中"
                Mode.AI_ACTIVE -> "AI通話中"
            }, context)
        }
        val s = subtitle.trim()
        if (view != null) {
            applyContent(mode, t, s)
            return
        }
        addView(mode, t, s)
    }

    fun hide() {
        val v = view ?: return
        try { wm.removeView(v) } catch (_: Throwable) {}
        view = null
        params = null
        lastMode = null
        Log.i(TAG, "status pill hidden")
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun addView(mode: Mode, title: String, subtitle: String) {
        val widthPx = dp(WIDTH_DP)
        val heightPx = dp(HEIGHT_DP)
        val screenH = screenHeight()
        val yFromTop = (screenH - heightPx) / 2

        val v = LayoutInflater.from(context).inflate(R.layout.overlay_return_bubble, null)
        applyContentTo(v, mode, title, subtitle)

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val p = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = yFromTop.coerceAtLeast(0)
        }
        params = p

        var downRawY = 0f
        var startY = 0
        val dragHandle = v.findViewById<View>(R.id.bubbleDragArea)
        dragHandle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY
                    startY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downRawY).toInt()
                    p.x = 0
                    p.y = (startY + dy).coerceIn(0, (screenH - heightPx).coerceAtLeast(0))
                    try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // ヘッダーは位置移動専用。アプリ起動は下のボタンから行う。
                    p.x = 0
                    try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(v, p)
            view = v
            lastTitle = title
            lastSubtitle = subtitle
            lastMode = mode
            // タイトルや補足には通話用件などが入る可能性があるため、内容はログに残さない。
            Log.i(TAG, "status pill shown mode=$mode y=$yFromTop")
        } catch (e: Throwable) {
            Log.e(TAG, "pill show failed", e)
        }
    }

    private fun applyContent(mode: Mode, title: String, subtitle: String) {
        val v = view ?: return
        // 本文が変わらなくても、待機状態やAI実行状態に応じたボタン表示は更新する。
        if (mode == lastMode && title == lastTitle && subtitle == lastSubtitle) {
            applyButtons(v)
            return
        }
        applyContentTo(v, mode, title, subtitle)
        lastMode = mode
        lastTitle = title
        lastSubtitle = subtitle
        Log.i(TAG, "status pill updated mode=$mode")
    }

    private fun applyContentTo(v: View, mode: Mode, title: String, subtitle: String) {
        v.findViewById<TextView>(R.id.tvBubbleTitle)?.text = title
        val sub = v.findViewById<TextView>(R.id.tvBubbleSubtitle)
        if (subtitle.isBlank()) {
            sub?.visibility = View.GONE
            sub?.text = ""
        } else {
            sub?.visibility = View.VISIBLE
            sub?.text = subtitle
        }
        val bg = when (mode) {
            Mode.WAITING -> R.drawable.bg_link_pill_waiting
            Mode.IN_CALL, Mode.AI_ACTIVE -> R.drawable.bg_link_pill_call
        }
        v.setBackgroundResource(bg)
        applyButtons(v)
    }

    private fun applyButtons(v: View) {
        val aiOn = AiSessionManager.running.get() || TranslateManager.running.get()
        val waiting = CallBriefStore.isActive() || TranslateStore.isArmed()
        v.findViewById<Button>(R.id.btnBubbleAiToggle)?.apply {
            text = if (aiOn || waiting) AppLanguage.tr("AI OFF", context) else AppLanguage.tr("AI ON", context)
            contentDescription = if (aiOn || waiting) AppLanguage.tr("AI機能を停止", context) else AppLanguage.tr("AI機能を有効にする", context)
            setOnClickListener { toggleAi() }
        }
        v.findViewById<Button>(R.id.btnBubbleOpenApp)?.apply {
            text = AppLanguage.tr("アプリを開く", context)
            setOnClickListener { openApp() }
        }
    }

    private fun toggleAi() {
        val running = AiSessionManager.running.get() || TranslateManager.running.get()
        val waiting = CallBriefStore.isActive() || TranslateStore.isArmed()
        if (running) {
            AiSessionManager.stop()
            TranslateManager.stop()
            CallBriefStore.clearActive()
            TranslateStore.setArmed(false)
            Toast.makeText(context, AppLanguage.tr("AI機能をOFFにしました", context), Toast.LENGTH_SHORT).show()
        } else if (waiting) {
            // 着信など、AIを起動したくない通話が始まった場合も、ここで待機を解除できる。
            CallBriefStore.clearActive()
            TranslateStore.setArmed(false)
            Toast.makeText(context, AppLanguage.tr("AI待機を解除しました", context), Toast.LENGTH_SHORT).show()
        } else {
            // 何もセットしていない状態の AI を、いま起動する
            val err = AiCallService.startAiNow()
            val msg = if (err == null) "AIを起動しました" else err
            Toast.makeText(context, AppLanguage.tr(msg, context), Toast.LENGTH_SHORT).show()
        }
        AiCallService.refreshBubble()
    }

    private fun openApp() {
        val i = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(MainActivity.EXTRA_FROM_BUBBLE, true)
            putExtra(MainActivity.EXTRA_OPEN_CALL_TAB, true)
        }
        context.startActivity(i)
    }

    private fun screenHeight(): Int {
        return try {
            wm.currentWindowMetrics.bounds.height()
        } catch (_: Throwable) {
            fallbackH()
        }
    }

    @Suppress("DEPRECATION")
    private fun fallbackH(): Int {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.heightPixels
    }
}
