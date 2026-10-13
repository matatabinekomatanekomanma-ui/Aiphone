package com.example.aiphone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 「通知が使えること」を確かめてから [onReady] を実行する。
 * ワイヤレデバッグのペアリングコードは通知の入力欄で入れるため、通知が拒否されていると先へ進めない。
 *
 *  - まだ許可していない → 許可ダイアログを出す
 *  - 拒否された → 理由を説明するダイアログで「もう一度許可する」を促す
 *  - 「今後表示しない」相当で OS のダイアログが出せない / 設定で通知がオフ → 通知設定画面を開くボタンを出す
 *
 * Activity のプロパティとして作る(registerForActivityResult は STARTED になる前に呼ぶ必要があるため)。
 */
class NotificationGate(
    private val activity: AppCompatActivity,
    private val onReady: () -> Unit,
) {
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (enabled()) onReady() else showDialog()
    }

    private fun enabled(): Boolean = NotificationManagerCompat.from(activity).areNotificationsEnabled()

    private fun permissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 通知が使えれば [onReady]。使えなければ許可を求める(または設定画面へ案内する) */
    fun run() {
        when {
            enabled() -> onReady()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissionGranted() &&
                !askedBefore() -> {
                markAsked()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> showDialog()
        }
    }

    // 「一度も聞いていない」と「拒否された」を区別する(shouldShowRequestPermissionRationale は初回も false を返すため)
    private fun askedBefore(): Boolean =
        activity.getSharedPreferences(PREFS, AppCompatActivity.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    private fun markAsked() {
        activity.getSharedPreferences(PREFS, AppCompatActivity.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    private fun showDialog() {
        // OS の許可ダイアログをまだ出せるか。出せない(2 回拒否など)なら、設定画面から許可してもらう
        val canAskAgain = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissionGranted() &&
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        val builder = AlertDialog.Builder(activity)
            .setTitle(AppLanguage.tr("通知を許可してください", activity))
            .setMessage(
                AppLanguage.tr(
                    "ワイヤレスデバッグのペアリングコードは、通知の入力欄に入れます。" +
                        "通知が許可されていないと、設定を進められません。",
                    activity,
                ),
            )
            .setNegativeButton(AppLanguage.tr("キャンセル", activity), null)
        if (canAskAgain) {
            builder.setPositiveButton(AppLanguage.tr("もう一度許可する", activity)) { _, _ -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        } else {
            builder.setPositiveButton(AppLanguage.tr("通知設定を開く", activity)) { _, _ -> openNotificationSettings() }
            builder.setMessage(
                AppLanguage.tr(
                    "ワイヤレスデバッグのペアリングコードは、通知の入力欄に入れます。" +
                        "通知が許可されていないと、設定を進められません。\n\n" +
                        "設定画面で、このアプリの通知をオンにしてから、もう一度ボタンを押してください。",
                    activity,
                ),
            )
        }
        builder.show()
    }

    private fun openNotificationSettings() {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(i)
        } catch (_: Exception) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${activity.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val PREFS = "notification_gate"
        const val KEY_ASKED = "asked"
    }
}
