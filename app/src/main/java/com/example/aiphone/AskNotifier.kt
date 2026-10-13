package com.example.aiphone

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** AI が判断を求めたとき、バイブ + 通知(ヒートアップ)で知らせる。通話中なので音は鳴らさない。 */
object AskNotifier {
    private const val CH = "ai_ask"
    private const val NOTI_ID = 44
    private const val NOTI_ID_INFO = 45

    @SuppressLint("MissingPermission")
    fun notifyAsk(ctx: Context, req: AskUserStore.Request) {
        ensureChannel(ctx)
        vibrate(ctx)
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_CALL_TAB, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // ロック画面に質問・選択肢を表示すると個人情報が漏れる可能性があるため、通知本文は一般的な案内にする。
        val title = if (req.isPii) "AIが個人情報の開示許可を求めています" else "AIが判断を求めています"
        val body = "Aiphone を開いて内容を確認し、回答してください。"
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTI_ID, n) } catch (_: SecurityException) {}
    }

    /** 確認依頼ではない、本人への知らせ(AI の接続切れなど)。バイブ付き */
    @SuppressLint("MissingPermission")
    fun notifyInfo(ctx: Context, title: String, text: String) {
        ensureChannel(ctx)
        vibrate(ctx)
        val open = PendingIntent.getActivity(
            ctx, 3,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_CALL_TAB, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTI_ID_INFO, n) } catch (_: SecurityException) {}
    }

    fun cancel(ctx: Context) {
        try { NotificationManagerCompat.from(ctx).cancel(NOTI_ID) } catch (_: Throwable) {}
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "AIの確認依頼", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "AI が判断できず、返答内容の選択を求めるとき"
                enableVibration(false) // バイブは vibrate() で確実に鳴らす
                setSound(null, null)   // 通話中なので音は鳴らさない
            }
        )
    }

    private fun vibrate(ctx: Context) {
        try {
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180, 120, 400), -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(effect)
            } else {
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(effect)
            }
        } catch (_: Throwable) {}
    }
}
