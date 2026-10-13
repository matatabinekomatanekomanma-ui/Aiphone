package com.example.aiphone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 設定方法ページ。いまは文字だけ。
 * 内容は [SetupGuideContent]。手順に imageRes を指定すれば、本文の下に写真が出る。
 */
class SetupGuideActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    companion object {
        private const val EXTRA_GUIDE = "guide"

        fun start(context: Context, guide: String) {
            context.startActivity(Intent(context, SetupGuideActivity::class.java).putExtra(EXTRA_GUIDE, guide))
        }
    }

    /** 通知が使えることを確かめてから、ワイヤレスデバッグの設定を始める(拒否されていたら案内する) */
    private val notificationGate = NotificationGate(this) { startPrivSetup() }

    private fun onPrivSetupClicked() = notificationGate.run()

    private fun startPrivSetup() {
        PrivSetupActions.startPairingService(this)?.let { toast(it) }
        PrivSetupActions.openWirelessDebugging(this)?.let { toast(it) }
    }

    private fun toast(msg: String) = Toast.makeText(this, AppLanguage.tr(msg, this), Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val guide = SetupGuideContent.get(intent.getStringExtra(EXTRA_GUIDE) ?: SetupGuideContent.PRIV)
        title = AppLanguage.tr(guide.title, this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply { isFillViewport = false }
        scroll.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        content.addView(text(AppLanguage.tr(guide.title, this), 20f, bold = true))
        content.addView(text(AppLanguage.tr(guide.summary, this), 14f, topDp = 8))

        guide.steps.forEach { step ->
            content.addView(text(AppLanguage.tr(step.title, this), 16f, bold = true, topDp = 20))
            content.addView(text(AppLanguage.tr(step.body, this), 14f, topDp = 4))
            step.action?.let { action ->
                content.addView(Button(this).apply {
                    text = AppLanguage.tr(step.actionLabel, this@SetupGuideActivity)
                    setOnClickListener {
                        when (action) {
                            SetupGuideContent.Action.OPEN_BUILD_NUMBER ->
                                PrivSetupActions.openBuildNumber(this@SetupGuideActivity)?.let { toast(it) }
                            SetupGuideContent.Action.START_PRIV_SETUP -> onPrivSetupClicked()
                        }
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) })
            }
            step.imageRes?.let { res ->
                content.addView(ImageView(this).apply {
                    setImageResource(res)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = AppLanguage.tr(step.title, this@SetupGuideActivity)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) })
            }
        }
        if (guide.notes.isNotEmpty()) {
            content.addView(text(AppLanguage.tr("困ったとき・注意", this), 16f, bold = true, topDp = 24))
            guide.notes.forEach { content.addView(text("・${AppLanguage.tr(it, this)}", 14f, topDp = 6)) }
        }

        val close = Button(this).apply {
            text = AppLanguage.tr("閉じる", this@SetupGuideActivity)
            setOnClickListener { finish() }
        }
        root.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    private fun text(t: String, sp: Float, bold: Boolean = false, topDp: Int = 0) = TextView(this).apply {
        text = t
        textSize = sp
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.START
        setTextIsSelectable(true)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }
    }
}
