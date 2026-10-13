package com.example.aiphone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.example.aiphone.adb.AdbPairingService

/**
 * priv-kit(ワイヤレスデバッグ)の設定まわりの共通動作。
 * 設定タブの「設定」ボタンと、設定方法ページのボタンで同じ処理を使う。
 */
object PrivSetupActions {
    private const val ARG_KEY = ":settings:fragment_args_key"
    private const val ARG_BUNDLE = ":settings:show_fragment_args"

    /** ペアリング用の通知サービスを起動する(通知にコード入力欄が出る)。失敗時はエラー文を返す */
    fun startPairingService(context: Context): String? {
        val intent = AdbPairingService.startIntent(context)
        return try {
            context.startForegroundService(intent)
            null
        } catch (e: Exception) {
            try {
                context.startService(intent)
                null
            } catch (e2: Exception) {
                "startService failed: ${e2.message ?: e.message}"
            }
        }
    }

    /** 開発者向けオプションを開き、「ワイヤレスデバッグ」を強調表示する。開けなければエラー文 */
    fun openWirelessDebugging(context: Context): String? =
        openSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "toggle_adb_wireless")

    /** 端末情報の画面を開き、「ビルド番号」を強調表示する(機種によっては強調されず、画面だけ開く) */
    fun openBuildNumber(context: Context): String? =
        openSettings(context, Settings.ACTION_DEVICE_INFO_SETTINGS, "build_number")

    private fun openSettings(context: Context, action: String, highlightKey: String): String? {
        val intent = Intent(action).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(ARG_KEY, highlightKey)
            putExtra(ARG_BUNDLE, Bundle().apply { putString(ARG_KEY, highlightKey) })
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "目的の画面を開けなかったため、端末の設定を開きました"
            } catch (_: Exception) {
                "設定画面を開けませんでした: ${e.message}"
            }
        }
    }
}
