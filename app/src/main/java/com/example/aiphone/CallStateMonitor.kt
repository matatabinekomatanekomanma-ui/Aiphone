package com.example.aiphone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * 通話検知（回線 + VoIP 共通）。着信か発信かは区別しない。
 * 1) Telephony（RINGING / OFFHOOK / IDLE）
 * 2) AudioManager.mode — 楽天リンク / LINE / Viber 等
 *    API 31+ は OnModeChangedListener（イベント駆動）、API 30 のみ 500ms ポーリング。
 *    API 31+ でも取りこぼし対策として 10 秒間隔の照合だけ行う。
 * 着信のベル中(RINGING / MODE_RINGTONE)は「通話中」とみなさず、応答して通話が成立した時点で onActive を呼ぶ。
 */
class CallStateMonitor(
    private val context: Context,
    private val onActive: () -> Unit,
    private val onIdle: () -> Unit,
) {
    companion object {
        private const val TAG = "RLAudio"
        /** API 30（リスナー無し）用のポーリング間隔 */
        private const val POLL_MS = 500L
        /** API 31+ の取りこぼし対策の照合間隔 */
        private const val RECONCILE_MS = 10_000L
    }

    private val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var modernCb: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var legacy: PhoneStateListener? = null
    private var modeListener: Any? = null

    @Volatile private var telActive = false
    @Volatile private var audioActive = false
    @Volatile private var lastReportedActive = false
    @Volatile private var running = false
    @Volatile private var lastAudioMode: Int? = null
    @Volatile private var lastTelephonyState = "UNINITIALIZED"
    @Volatile private var lastTelephonyChangeAt = 0L
    @Volatile private var lastAudioModeChangeAt = 0L
    @Volatile private var lastDiagnosticLogAt = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                onAudioMode(am.mode, "poll")
                logDiagnosticSnapshot("poll", force = false)
            } catch (e: Exception) {
                Log.e(TAG, "[call-diag] audio poll failed", e)
            }
            handler.postDelayed(
                this,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RECONCILE_MS else POLL_MS
            )
        }
    }

    /** AudioManager.mode の変化（リスナー/ポーリング共通の処理） */
    private fun onAudioMode(mode: Int, source: String) {
        val previousMode = lastAudioMode
        if (previousMode != mode) {
            lastAudioModeChangeAt = System.currentTimeMillis()
            Log.i(TAG, "[call-diag] AudioManager mode changed source=$source " +
                "${modeName(previousMode)} -> ${modeName(mode)} ($mode) telActive=$telActive audioActiveBefore=$audioActive")
            lastAudioMode = mode
        }
        when (mode) {
            AudioManager.MODE_RINGTONE -> {
                // ベル中は「通話確立」ではない
                if (audioActive) {
                    audioActive = false
                    recompute("audio-mode:${modeName(mode)}")
                }
            }
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> {
                if (!audioActive) {
                    Log.i(TAG, "AudioManager mode=$mode → audioActive=true")
                    audioActive = true
                    recompute("audio-mode:${modeName(mode)}")
                }
            }
            else -> {
                if (audioActive) {
                    Log.i(TAG, "AudioManager mode=$mode → audioActive=false")
                    audioActive = false
                    recompute("audio-mode:${modeName(mode)}")
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startModeListener() {
        val l = AudioManager.OnModeChangedListener { mode -> onAudioMode(mode, "listener") }
        modeListener = l
        am.addOnModeChangedListener(context.mainExecutor, l)
    }

    fun start() {
        if (running) return
        running = true
        val phonePermission = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "CallStateMonitor start sdk=${Build.VERSION.SDK_INT} readPhoneState=$phonePermission")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) startModern() else startLegacy()
        } catch (e: Exception) {
            Log.e(TAG, "[call-diag] telephony start failed (audio monitoring continues)", e)
        }
        try {
            val mode = am.mode
            audioActive = mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION
            lastAudioMode = mode
            lastAudioModeChangeAt = System.currentTimeMillis()
            Log.i(TAG, "[call-diag] initial audio state mode=${modeName(mode)}($mode) audioActive=$audioActive")
        } catch (e: Throwable) {
            Log.w(TAG, "[call-diag] initial AudioManager.mode read failed", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                startModeListener()
            } catch (e: Exception) {
                Log.w(TAG, "[call-diag] mode listener registration failed; polling only", e)
            }
        }
        handler.post(poll)
        recompute("start")
        logDiagnosticSnapshot("start", force = true)
        Log.i(TAG, "[call-diag] CallStateMonitor started sdk=${Build.VERSION.SDK_INT} " +
            "phonePermission=$phonePermission modeListener=${modeListener != null}")
    }

    fun stop() {
        Log.i(TAG, "[call-diag] stop requested state=${diagnosticState()}")
        running = false
        handler.removeCallbacks(poll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                (modeListener as? AudioManager.OnModeChangedListener)?.let { am.removeOnModeChangedListener(it) }
            } catch (e: Exception) {
                Log.w(TAG, "mode listener remove failed type=${e.javaClass.simpleName}")
            }
        }
        modeListener = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCb?.let { tm.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                legacy?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "telephony stop failed type=${e.javaClass.simpleName}")
        }
        modernCb = null
        legacy = null
        Log.i(TAG, "[call-diag] stopped state=${diagnosticState()}")
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startModern() {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = onTelephonyState(state)
        }
        modernCb = cb
        tm.registerTelephonyCallback(context.mainExecutor, cb)
    }

    @Suppress("DEPRECATION")
    private fun startLegacy() {
        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) = onTelephonyState(state)
        }
        legacy = l
        tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun onTelephonyState(state: Int) {
        val name = when (state) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "UNKNOWN($state)"
        }
        val previous = lastTelephonyState
        lastTelephonyState = name
        lastTelephonyChangeAt = System.currentTimeMillis()
        Log.i(TAG, "[call-diag] Telephony state $previous -> $name ($state)")
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> telActive = false
            TelephonyManager.CALL_STATE_OFFHOOK -> telActive = true
            TelephonyManager.CALL_STATE_IDLE -> telActive = false
        }
        recompute("telephony:$name")
    }

    private fun recompute(reason: String) {
        val active = telActive || audioActive
        val changed = active != lastReportedActive
        Log.i(TAG, "[call-diag] recompute reason=$reason telActive=$telActive audioActive=$audioActive " +
            "effectiveActive=$active previousReported=$lastReportedActive changed=$changed")
        if (!changed) {
            logDiagnosticSnapshot(reason, force = false)
            return
        }
        lastReportedActive = active
        logDiagnosticSnapshot("transition:$reason", force = true)
        if (active) {
            Log.i(TAG, "[call-diag] call ACTIVE (tel=$telActive audio=$audioActive)")
            onActive()
        } else {
            Log.i(TAG, "[call-diag] call IDLE (tel=$telActive audio=$audioActive)")
            onIdle()
        }
    }

    /** 一定間隔で両方の入力状態を記録し、通話中判定が残る信号を特定する。 */
    private fun logDiagnosticSnapshot(reason: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDiagnosticLogAt < 5_000L) return
        lastDiagnosticLogAt = now
        Log.i(TAG, "[call-diag] snapshot reason=$reason ${diagnosticState()} " +
            "telChangeAgeMs=${ageMs(lastTelephonyChangeAt, now)} " +
            "audioModeChangeAgeMs=${ageMs(lastAudioModeChangeAt, now)}")
    }

    fun diagnosticState(): String = "telState=$lastTelephonyState telActive=$telActive " +
        "audioMode=${modeName(lastAudioMode)}(${lastAudioMode ?: -1}) audioActive=$audioActive " +
        "effectiveActive=${telActive || audioActive} reportedActive=$lastReportedActive running=$running"

    private fun ageMs(timestamp: Long, now: Long): Long = if (timestamp <= 0L) -1L else (now - timestamp).coerceAtLeast(0L)

    private fun modeName(mode: Int?): String = when (mode) {
        null -> "UNINITIALIZED"
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> "OTHER($mode)"
    }
}
