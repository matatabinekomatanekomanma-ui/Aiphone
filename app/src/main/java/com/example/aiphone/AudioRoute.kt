package com.example.aiphone

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.Executor

/**
 * Bluetooth / 有線 / USB のイヤホンマイクを使っているときに、録音と再生が正しい機器を向くようにする。
 *
 * - 録音(マイク): 音源が MIC などだと、既定では本体マイクが選ばれてしまう。
 *   通話に使われている機器(API 31+ は communicationDevice)に合わせて、入力機器を明示的に指定する。
 * - 再生: VOICE_COMMUNICATION は通話の経路に自動で従うが、通話機器が分かるときは出力機器も明示する。
 * - 機器の着脱・通話機器の切り替えを検知して、指定し直す(Watcher)。
 *
 * アプリ本体と shell(特権)側の両方から使う。
 */
object AudioRoute {
    // AudioDeviceInfo.TYPE_*（BLE 系は API 31 だが、定数値を直接書いて minSdk 30 でも参照できるようにしている）
    private const val T_EARPIECE = 1
    private const val T_SPEAKER = 2
    private const val T_WIRED_HEADSET = 3
    private const val T_WIRED_HEADPHONES = 4
    private const val T_BT_SCO = 7
    private const val T_BT_A2DP = 8
    private const val T_USB_DEVICE = 11
    private const val T_USB_HEADSET = 22
    private const val T_HEARING_AID = 23
    private const val T_BLE_HEADSET = 26

    /** マイクとして使える外付け機器 */
    private val EXTERNAL_INPUT = setOf(T_BLE_HEADSET, T_BT_SCO, T_WIRED_HEADSET, T_USB_HEADSET, T_USB_DEVICE)
    /** 通話の出力として使える外付け機器 */
    private val EXTERNAL_OUTPUT = setOf(
        T_BLE_HEADSET, T_BT_SCO, T_WIRED_HEADSET, T_WIRED_HEADPHONES, T_USB_HEADSET, T_USB_DEVICE, T_HEARING_AID,
    )
    private val BUILTIN_OUTPUT = setOf(T_EARPIECE, T_SPEAKER)
    /** 通話の機器が分からないときの優先順(入力) */
    private val INPUT_PRIORITY = listOf(T_BLE_HEADSET, T_BT_SCO, T_WIRED_HEADSET, T_USB_HEADSET, T_USB_DEVICE)

    fun typeName(type: Int): String = when (type) {
        T_EARPIECE -> "受話口"
        T_SPEAKER -> "スピーカー"
        T_WIRED_HEADSET -> "有線ヘッドセット"
        T_WIRED_HEADPHONES -> "有線イヤホン(マイクなし)"
        T_BT_SCO -> "Bluetooth(通話)"
        T_BT_A2DP -> "Bluetooth(音楽)"
        T_USB_DEVICE -> "USB音声機器"
        T_USB_HEADSET -> "USBヘッドセット"
        T_HEARING_AID -> "補聴器"
        T_BLE_HEADSET -> "Bluetooth LE Audio"
        15 -> "本体マイク"
        else -> "機器(type=$type)"
    }

    /** 画面・ログ用の名前。null は「本体(既定の経路)」 */
    fun describe(d: AudioDeviceInfo?): String {
        if (d == null) return "本体"
        val name = try { d.productName?.toString().orEmpty() } catch (_: Throwable) { "" }
        return typeName(d.type) + if (name.isNotBlank()) "「$name」" else ""
    }

    private fun commDevice(am: AudioManager): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= 31) try { am.communicationDevice } catch (_: Throwable) { null } else null

    private fun sameDevice(a: AudioDeviceInfo, b: AudioDeviceInfo): Boolean =
        a.type == b.type && (a.address.isNullOrEmpty() || b.address.isNullOrEmpty() || a.address == b.address)

    /**
     * 録音に使う機器。null = 本体マイク(既定のまま)。
     * 通話の機器が分かればそれに合わせ(受話口/スピーカーなら本体マイク)、分からなければ外付け機器を優先する。
     */
    fun pickInput(am: AudioManager): AudioDeviceInfo? {
        val inputs = try {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in EXTERNAL_INPUT }
        } catch (_: Throwable) { emptyList() }
        if (inputs.isEmpty()) return null
        val comm = commDevice(am)
        if (comm != null) {
            inputs.firstOrNull { sameDevice(it, comm) }?.let { return it }
            if (comm.type in BUILTIN_OUTPUT) return null // 利用者が本体を選んでいる
        }
        for (t in INPUT_PRIORITY) inputs.firstOrNull { it.type == t }?.let { return it }
        return null
    }

    /**
     * 再生に使う機器。通話の機器が外付けだと分かるときだけ明示する(null = システムに任せる)。
     * 機器が分からないときに勝手に外付けへ向けると、本体で通話している人の音が消えるため。
     */
    fun pickOutput(am: AudioManager): AudioDeviceInfo? {
        val comm = commDevice(am) ?: return null
        if (comm.type !in EXTERNAL_OUTPUT) return null
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { sameDevice(it, comm) }
        } catch (_: Throwable) { null }
    }

    /** 外付け機器を使っているときだけ「入力=… / 出力=…」を返す(本体のみなら空) */
    fun summary(am: AudioManager): String {
        val i = pickInput(am)
        val o = pickOutput(am)
        if (i == null && o == null) return ""
        return "入力=${describe(i)} / 出力=${describe(o)}"
    }

    /** AudioRecord の入力機器を指定する(録音の開始前でも後でもよい) */
    fun applyInput(rec: AudioRecord, am: AudioManager, tag: String, log: (String) -> Unit) {
        val d = pickInput(am)
        val ok = try { rec.setPreferredDevice(d) } catch (_: Throwable) { false }
        log("$tag: 入力機器=${describe(d)}" + if (d != null && !ok) "（指定に失敗。既定の経路を使います）" else "")
    }

    /** AudioTrack の出力機器を指定する */
    fun applyOutput(track: AudioTrack, am: AudioManager, tag: String, log: (String) -> Unit) {
        val d = pickOutput(am)
        val ok = try { track.setPreferredDevice(d) } catch (_: Throwable) { false }
        log("$tag: 出力機器=${describe(d)}" + if (d != null && !ok) "（指定に失敗。既定の経路を使います）" else "")
    }

    /**
     * 機器の着脱・通話機器の切り替えを検知して onChange を呼ぶ(連続した通知は 0.5 秒にまとめる)。
     * 使い終わったら stop() すること。
     */
    class Watcher(private val am: AudioManager, private val onChange: () -> Unit) {
        private val ht = HandlerThread("audio-route").also { it.start() }
        private val handler = Handler(ht.looper)
        private val fire = Runnable { try { onChange() } catch (_: Throwable) {} }
        private var commListener: Any? = null

        private val deviceCb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = schedule()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = schedule()
        }

        private fun schedule() {
            handler.removeCallbacks(fire)
            handler.postDelayed(fire, 500)
        }

        fun start(): Watcher {
            try { am.registerAudioDeviceCallback(deviceCb, handler) } catch (_: Throwable) {}
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    val l = AudioManager.OnCommunicationDeviceChangedListener { schedule() }
                    am.addOnCommunicationDeviceChangedListener(Executor { r -> handler.post(r) }, l)
                    commListener = l
                } catch (_: Throwable) {}
            }
            return this
        }

        fun stop() {
            try { am.unregisterAudioDeviceCallback(deviceCb) } catch (_: Throwable) {}
            if (Build.VERSION.SDK_INT >= 31) {
                (commListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let {
                    try { am.removeOnCommunicationDeviceChangedListener(it) } catch (_: Throwable) {}
                }
            }
            commListener = null
            handler.removeCallbacks(fire)
            ht.quitSafely()
        }
    }
}
