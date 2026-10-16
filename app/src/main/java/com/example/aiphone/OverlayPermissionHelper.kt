package com.example.aiphone

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 重ねて表示の権限を shell/priv-kit 経由で設定し、OS 側の反映を確認する。
 * ユーザーに手動の許可操作を求める前に、接続待ち・反映待ちを行う。
 */
object OverlayPermissionHelper {
    private const val TAG = "RLAudio"

    data class Result(
        val granted: Boolean,
        val wasGrantedBefore: Boolean,
        val shellAttempted: Boolean,
        val shellGrantSucceeded: Boolean,
        val attempts: Int,
    )

    suspend fun ensure(context: Context, reason: String): Result {
        val app = context.applicationContext
        val initial = Settings.canDrawOverlays(app)
        Log.i(TAG, "[overlay-diag] ensure begin reason=$reason package=${app.packageName} " +
            "canDrawOverlays=$initial bridgeConnected=${BridgeConnector.bridge != null}")
        if (initial) {
            Log.i(TAG, "[overlay-diag] already permitted reason=$reason")
            return Result(true, true, false, false, 0)
        }

        var shellAttempted = false
        var shellGrantSucceeded = false
        var attempts = 0
        // AppOps の変更直後は端末によって設定判定への反映に遅延があるため、数回確認する。
        val waitsMs = longArrayOf(0L, 250L, 500L, 1000L, 1500L)
        for (waitMs in waitsMs) {
            if (waitMs > 0L) delay(waitMs)

            val beforeAttempt = Settings.canDrawOverlays(app)
            Log.i(TAG, "[overlay-diag] verify reason=$reason attempt=${attempts + 1} " +
                "canDrawOverlays=$beforeAttempt bridgeConnected=${BridgeConnector.bridge != null}")
            if (beforeAttempt) {
                Log.i(TAG, "[overlay-diag] permitted reason=$reason attempts=$attempts")
                return Result(true, false, shellAttempted, shellGrantSucceeded, attempts)
            }

            // app-op 設定は冪等なので、接続が整った段階で最大 2 回まで再試行する。
            if (BridgeConnector.bridge != null && attempts < 2) {
                attempts++
                shellAttempted = true
                val ok = withContext(Dispatchers.IO) {
                    BridgeConnector.grantOverlayPermission(app.packageName)
                }
                shellGrantSucceeded = shellGrantSucceeded || ok
                Log.i(TAG, "[overlay-diag] shell grant reason=$reason attempt=$attempts callOk=$ok")
            } else if (BridgeConnector.bridge == null) {
                Log.w(TAG, "[overlay-diag] shell grant skipped: AudioBridge not connected reason=$reason")
            }

            val afterAttempt = Settings.canDrawOverlays(app)
            Log.i(TAG, "[overlay-diag] verify-after-grant reason=$reason attempt=$attempts " +
                "canDrawOverlays=$afterAttempt shellGrantSucceeded=$shellGrantSucceeded")
            if (afterAttempt) {
                Log.i(TAG, "[overlay-diag] permission confirmed reason=$reason attempts=$attempts")
                return Result(true, false, shellAttempted, shellGrantSucceeded, attempts)
            }
        }

        val finalState = Settings.canDrawOverlays(app)
        Log.e(TAG, "[overlay-diag] permission NOT confirmed reason=$reason final=$finalState " +
            "bridgeConnected=${BridgeConnector.bridge != null} shellAttempted=$shellAttempted " +
            "shellGrantSucceeded=$shellGrantSucceeded attempts=$attempts")
        return Result(finalState, false, shellAttempted, shellGrantSucceeded, attempts)
    }
}
