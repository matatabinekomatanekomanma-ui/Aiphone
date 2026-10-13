@file:Suppress("PrivateApi")

package com.example.aiphone

import android.content.Context
import androidx.annotation.Keep

import android.app.Application
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.os.Process
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.lang.reflect.InvocationTargetException
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * priv-kit の UserService として shell(UID 2000) 権限のプロセスで動く。
 * hidden API(AudioPolicy / AudioMix / AudioMixingRule)はすべてリフレクションで呼ぶ。
 */
class AudioBridgeService : IAudioBridge.Stub {

    // priv-kit may invoke no-arg or Context constructor via reflection
    @Keep
    constructor() : super()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    companion object {
        /** どのビルドが動いているかをログで確認するための目印 */
        val BUILD = "r${BuildConfig.VERSION_CODE}-privkit"
    }

    private class Session(val policy: Any, val am: AudioManager, val ht: HandlerThread) {
        @Volatile var running = true
        var thread: Thread? = null
        val closers = mutableListOf<() -> Unit>()
    }

    private class Reg(val policy: Any, val mix: Any, val am: AudioManager, val ht: HandlerThread, val rate: Int)

    private val pkg = "android.media.audiopolicy."
    private val TAG = "RLAudio"

    // ---------- ログ基盤(logcat タグ RLAudio / 失敗時に詳細レポートを組み立てる) ----------
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val trace = StringBuilder()
    @Volatile private var report = "(まだレポートはありません)"
    @Volatile private var lastStep = "-"
    @Volatile private var sessionStamp = "00-00 00:00:00.000"
    private fun markSession() { sessionStamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) }

    private fun t(msg: String) {
        val line = "${timeFmt.format(Date())} $msg"
        synchronized(trace) { trace.appendLine(line); if (trace.length > 60000) trace.delete(0, 20000) }
        logLong(Log.INFO, "[svc] $msg")
    }

    private fun te(msg: String, e: Throwable) {
        val line = "${timeFmt.format(Date())} !! $msg -> ${err(e)}"
        synchronized(trace) { trace.appendLine(line) }
        logLong(Log.ERROR, "[svc] $msg\n${fullTrace(e)}")
    }

    /** logcat は 1 行 約4000 バイトで切れるので、行単位で分割して出す */
    private fun logLong(prio: Int, text: String) {
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { Log.println(prio, TAG, sb.toString().trimEnd()); sb.clear() } }
        for (line in text.lines()) {
            if (sb.length + line.length > 3000) flush()
            sb.appendLine(line)
        }
        flush()
    }

    private fun fullTrace(e: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 8) {
            sb.appendLine((if (depth == 0) "" else "Caused by: ") + "${cur.javaClass.name}: ${cur.message}")
            cur.stackTrace.take(25).forEach { sb.appendLine("    at $it") }
            val next = if (cur is InvocationTargetException) cur.targetException else cur.cause
            cur = if (next === cur) null else next
            depth++
        }
        return sb.toString()
    }

    /** 失敗箇所を特定できるよう、手順名を記録しながら実行する */
    private fun <R> step(name: String, block: () -> R): R {
        lastStep = name
        t("STEP $name ...")
        try {
            val r = block()
            t("STEP $name OK")
            return r
        } catch (e: Throwable) {
            te("STEP $name FAILED", e)
            throw e
        }
    }

    private fun envHeader(): String {
        val sb = StringBuilder()
        sb.appendLine("uid=${Process.myUid()} pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} patch=${Build.VERSION.SECURITY_PATCH}")
        sb.appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) abi=${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("fingerprint=${Build.FINGERPRINT}")
        return sb.toString()
    }

    private fun permissionStatus(): String {
        val sb = StringBuilder()
        val perms = listOf(
            "android.permission.MODIFY_AUDIO_ROUTING",
            "android.permission.CAPTURE_AUDIO_OUTPUT",
            "android.permission.CAPTURE_MEDIA_OUTPUT",
            "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT",
            "android.permission.CALL_AUDIO_INTERCEPTION",
            "android.permission.RECORD_AUDIO",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.READ_LOGS"
        )
        val c = try { ctx } catch (e: Throwable) { sb.appendLine("(shell環境が作れず権限確認不可: ${err(e)})"); return sb.toString() }
        for (p in perms) {
            val r = try {
                if (c.checkPermission(p, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
            } catch (e: Throwable) { "ERR ${err(e)}" }
            sb.appendLine("  ${p.removePrefix("android.permission.")}: $r")
        }
        return sb.toString()
    }

    /** shell 権限で logcat を読み、オーディオ系システムログの末尾を取り出す(拒否理由は AudioService 側に出る) */
    private fun systemLogExcerpt(): String = try {
        val cmd = listOf("logcat", "-d", "-v", "threadtime", "-t", "600", "-s",
            "AudioService:V", "AudioPolicy:V", "AudioPolicyManager:V", "AudioPolicyIntefaceImpl:V",
            "AudioPolicyService:V", "AudioFlinger:V", "AudioMix:V", "AudioRecord:V", "AudioTrack:V",
            "AudioSystem:V", "AudioManager:V", "PermissionChecker:V", "AppOps:V")
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        // threadtime 形式の先頭 18 文字("MM-dd HH:mm:ss.SSS")で、今回の操作開始以降だけに絞る
        val lines = out.lines().filter { it.length > 18 && it.substring(0, 18) >= sessionStamp }
        if (lines.isEmpty()) "(今回の操作以降のオーディオ系システムログなし)" else lines.takeLast(150).joinToString("\n")
    } catch (e: Throwable) {
        "(logcat 取得失敗: ${err(e)})"
    }

    private fun buildReport(title: String, e: Throwable?): String {
        val sb = StringBuilder()
        sb.appendLine("===== RLAudio REPORT: $title =====")
        val fs = lastStep
        sb.appendLine("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}  failedStep=$fs")
        sb.append(envHeader())
        sb.appendLine("--- shell プロセスの権限 ---")
        sb.append(permissionStatus())
        if (e != null) {
            sb.appendLine("--- 例外(全スタック) ---")
            sb.append(fullTrace(e))
        }
        sb.appendLine("--- 手順ログ ---")
        synchronized(trace) { sb.append(trace.toString().lines().takeLast(120).joinToString("\n")) }
        sb.appendLine()
        sb.appendLine("--- システム側 logcat 抜粋(オーディオ系, 今回操作以降・最大150行) ---")
        sb.appendLine(systemLogExcerpt())
        sb.appendLine("===== RLAudio REPORT END =====")
        val r = sb.toString().take(200_000)
        report = r
        logLong(Log.ERROR, r)
        return r
    }

    override fun lastReport(): String = report

    /**
     * shell UID で appops を実行し、指定パッケージに SYSTEM_ALERT_WINDOW を付与する。
     * 成功時 "ok"、失敗時は理由文字列。
     */
    override fun grantOverlayPermission(packageName: String): String {
        if (packageName.isBlank()) return "packageName empty"
        val cmds = listOf(
            listOf("cmd", "appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow"),
            listOf("appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow"),
        )
        val logs = StringBuilder()
        for (cmd in cmds) {
            try {
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                val code = proc.waitFor()
                logs.appendLine("cmd=${cmd.joinToString(" ")} exit=$code out=$out")
                if (code == 0) {
                    Log.i(TAG, "grantOverlayPermission ok via ${cmd[0]}: $out")
                    return "ok"
                }
            } catch (e: Throwable) {
                logs.appendLine("cmd=${cmd.joinToString(" ")} err=${e.message}")
                Log.w(TAG, "grantOverlayPermission failed ${cmd.joinToString(" ")}", e)
            }
        }
        return "failed: ${logs.toString().trim()}"
    }

    private var cap: Session? = null
    private var inj: Session? = null
    @Volatile private var peak = 0
    private val ctx: Context by lazy { prepareEnv() }

    // ---------- 診断 ----------
    override fun diagnose(targetUid: Int): String {
        val sb = StringBuilder()
        sb.appendLine("process uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}) ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("target uid=$targetUid")
        for (n in listOf("AudioPolicy", "AudioMix", "AudioMixingRule", "AudioPolicyConfig")) {
            sb.appendLine("class $n: " + try { Class.forName(pkg + n); "OK" } catch (e: Throwable) { "NG" })
        }
        try {
            val rc = Class.forName(pkg + "AudioMixingRule")
            val mc = Class.forName(pkg + "AudioMix")
            val rbm = Class.forName(pkg + "AudioMixingRule\$Builder").methods.map { it.name }.toSet()
            sb.appendLine("MIX_ROLE_INJECTOR=${ic(rc, "MIX_ROLE_INJECTOR", -1)} RULE_UID=${ic(rc, "RULE_MATCH_UID", -1)} " +
                "RULE_USAGE=${ic(rc, "RULE_MATCH_ATTRIBUTE_USAGE", -1)} RULE_PRESET=${ic(rc, "RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET", -1)}")
            sb.appendLine("ROUTE_LOOP_BACK=${ic(mc, "ROUTE_FLAG_LOOP_BACK", -1)} ROUTE_RENDER=${ic(mc, "ROUTE_FLAG_RENDER", -1)} " +
                "ROUTE_LOOP_BACK_RENDER=${ic(mc, "ROUTE_FLAG_LOOP_BACK_RENDER", -1)}")
            sb.appendLine("setTargetMixRole: ${"setTargetMixRole" in rbm}  allowPrivilegedPlaybackCapture: ${"allowPrivilegedPlaybackCapture" in rbm}")
        } catch (e: Throwable) {
            sb.appendLine("定数/メソッド確認失敗: ${err(e)}")
        }
        sb.appendLine("shell環境(ShellContext/ActivityThread): " + try { step("prepareEnv(診断)") { ctx }; "OK" } catch (e: Throwable) { "NG ${err(e)}" })
        sb.appendLine("shell権限:")
        sb.append(permissionStatus())
        t("diagnose:\n$sb")
        buildReport("診断", null)
        return sb.toString() + "(詳細レポートは logcat タグ RLAudio / 「レポート取得」ボタン)"

    }

    // ---------- 相手音声の取得 ----------
    override fun startCapture(targetUid: Int, variant: Int, sink: ParcelFileDescriptor): String {
        // AudioPolicy は checkCallingOrSelfPermission を使うため、Binder 呼び出し中は「呼び出し元(アプリ)」の権限で判定されてしまう。
        // 自プロセス(shell)の権限で判定させるため、呼び出し元情報をクリアして実行する。
        val callerUid = Binder.getCallingUid(); val callerPid = Binder.getCallingPid()
        val token = Binder.clearCallingIdentity()
        try {
            t("calling identity: before clear uid=$callerUid pid=$callerPid → after clear uid=${Binder.getCallingUid()} pid=${Binder.getCallingPid()}")
            return startCaptureImpl(targetUid, variant, sink)
        } finally { Binder.restoreCallingIdentity(token) }
    }

    private fun startCaptureImpl(targetUid: Int, variant: Int, sink: ParcelFileDescriptor): String {
        stopCapture()
        synchronized(trace) { trace.setLength(0) }
        lastStep = "-"
        markSession()
        t("===== startCapture build=$BUILD targetUid=$targetUid variant=$variant =====")
        t("env: " + envHeader().replace("\n", " | "))
        return try {
            val r = register(false, targetUid, variant)
            val rec = step("createAudioRecordSink") {
                call(r.policy, "createAudioRecordSink", r.mix) as AudioRecord?
                    ?: throw IllegalStateException("createAudioRecordSink が null を返した(システム側 'AudioPolicy:' のログに理由が出る)")
            }
            t("AudioRecord state=${rec.state} (1=INITIALIZED) format=${rec.format} bufSize=${rec.bufferSizeInFrames}")
            val os = FileOutputStream(sink.fileDescriptor)
            val s = Session(r.policy, r.am, r.ht)
            s.closers += { try { rec.stop() } catch (_: Throwable) {}; rec.release(); os.close(); sink.close() }
            step("rec.startRecording") { rec.startRecording() }
            t("recordingState=${rec.recordingState} (3=RECORDING)")
            s.thread = Thread {
                val buf = ByteArray(3840)
                var total = 0L
                var lastLog = System.currentTimeMillis()
                try {
                    while (s.running) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n < 0) { t("rec.read がエラーコード $n を返した(-3=INVALID_OPERATION,-6=DEAD_OBJECT) → 取得スレッド終了"); break }
                        if (n > 0) { os.write(buf, 0, n); total += n; peak = maxOf(peak, blockPeak(buf, n)) }
                        val now = System.currentTimeMillis()
                        if (now - lastLog > 3000) { t("capture: total=${total}bytes peak=$peak"); lastLog = now }
                    }
                } catch (e: Throwable) { te("取得スレッドで例外", e) }
                t("capture thread end total=${total}bytes")
            }.also { it.start() }
            cap = s
            t("===== startCapture OK =====")
            "OK: 取得開始 (variant=$variant, rate=${r.rate}, svcBuild=$BUILD)"
        } catch (e: Throwable) {
            try { sink.close() } catch (_: Throwable) {}
            val fs = lastStep
            te("startCapture 失敗 (step=$fs)", e)
            buildReport("取得開始 NG (variant=$variant)", e)
            "NG(step=$fs, svcBuild=$BUILD): ${err(e)}"
        }
    }

    override fun stopCapture(): String {
        val s = cap ?: return "取得は動いていません"
        cap = null
        t("stopCapture")
        // unregisterAudioPolicy も呼び出し元(アプリ)の権限で判定されるため、identity をクリアして実行する
        val token = Binder.clearCallingIdentity()
        try { close(s) } finally { Binder.restoreCallingIdentity(token) }
        return "OK: 取得停止"
    }

    override fun captureLevel(): Int { val p = peak; peak = 0; return p }

    // ---------- マイク入力への注入 ----------
    override fun startInject(targetUid: Int, variant: Int, input: ParcelFileDescriptor): String {
        val callerUid = Binder.getCallingUid(); val callerPid = Binder.getCallingPid()
        val token = Binder.clearCallingIdentity()
        try {
            t("calling identity: before clear uid=$callerUid pid=$callerPid → after clear uid=${Binder.getCallingUid()} pid=${Binder.getCallingPid()}")
            return startInjectImpl(targetUid, variant, input)
        } finally { Binder.restoreCallingIdentity(token) }
    }

    private fun startInjectImpl(targetUid: Int, variant: Int, input: ParcelFileDescriptor): String {
        stopInject()
        synchronized(trace) { trace.setLength(0) }
        lastStep = "-"
        markSession()
        t("===== startInject build=$BUILD targetUid=$targetUid variant=$variant =====")
        t("env: " + envHeader().replace("\n", " | "))
        return try {
            val r = register(true, targetUid, variant)
            val track = step("createAudioTrackSource") {
                call(r.policy, "createAudioTrackSource", r.mix) as AudioTrack?
                    ?: throw IllegalStateException("createAudioTrackSource が null を返した(システム側 'AudioPolicy:' のログに理由が出る)")
            }
            t("AudioTrack state=${track.state} (1=INITIALIZED) format=${track.format}")
            val ins = FileInputStream(input.fileDescriptor)
            val s = Session(r.policy, r.am, r.ht)
            s.closers += { try { track.stop() } catch (_: Throwable) {}; track.release(); ins.close(); input.close() }
            step("track.play") { track.play() }
            t("playState=${track.playState} (3=PLAYING)")
            s.thread = Thread {
                val buf = ByteArray(1920)
                var total = 0L
                var lastLog = System.currentTimeMillis()
                try {
                    while (s.running) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            val w = track.write(buf, 0, n)
                            if (w < 0) { t("track.write がエラーコード $w を返した"); break }
                            total += w
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastLog > 3000) { t("inject: total=${total}bytes"); lastLog = now }
                    }
                } catch (e: Throwable) { te("注入スレッドで例外", e) }
                t("inject thread end total=${total}bytes")
            }.also { it.start() }
            inj = s
            t("===== startInject OK =====")
            "OK: 注入開始 (variant=$variant)"
        } catch (e: Throwable) {
            try { input.close() } catch (_: Throwable) {}
            val fs = lastStep
            te("startInject 失敗 (step=$fs)", e)
            buildReport("注入開始 NG (variant=$variant)", e)
            "NG(step=$fs, svcBuild=$BUILD): ${err(e)}"
        }
    }

    override fun stopInject(): String {
        val s = inj ?: return "注入は動いていません"
        inj = null
        t("stopInject")
        val token = Binder.clearCallingIdentity()
        try { close(s) } finally { Binder.restoreCallingIdentity(token) }
        return "OK: 注入停止"
    }

    // ---------- 本物のマイク録音(翻訳機能: 自分の声を Gemini へ送るため) ----------
    // 注入(startInject)は通話アプリのマイク入力を差し替えるので、本物のマイクはこちらで別に録る。
    // shell(UID 2000) は audioserver の「サービス UID」扱いで、他アプリが録音中でも無音化されない想定(要・実機確認)。
    private class MicSession(val rec: AudioRecord, val sink: ParcelFileDescriptor) {
        @Volatile var running = true
        var thread: Thread? = null
        /** イヤホンマイクの着脱・通話機器の切り替えを見て、入力機器を指定し直す */
        var watcher: AudioRoute.Watcher? = null
    }

    private var mic: MicSession? = null

    /** 今 録音中のクライアント(音源・無音化されているか)。マイクが取れない原因の切り分け用 */
    private fun recordingConfigs(): String = try {
        val am = ctx.getSystemService(AudioManager::class.java)
        val list = am.activeRecordingConfigurations
        if (list.isEmpty()) {
            "(録音中のクライアントなし)"
        } else {
            list.joinToString("; ") { c -> "src=${c.clientAudioSource} silenced=${c.isClientSilenced} session=${c.clientAudioSessionId}" }
        }
    } catch (e: Throwable) {
        "(取得不可: ${err(e)})"
    }

    /**
     * source: MediaRecorder.AudioSource の値。100 以上なら「値-100」の音源を privacySensitive=false で録る。
     * (VOICE_COMMUNICATION などの既定は privacy sensitive。他アプリの録音中に新規録音が拒否される要因になりうる)
     */
    override fun startMicCapture(source: Int, sink: ParcelFileDescriptor): String {
        val token = Binder.clearCallingIdentity()
        try {
            return startMicImpl(source, sink)
        } finally { Binder.restoreCallingIdentity(token) }
    }

    private fun startMicImpl(source: Int, sink: ParcelFileDescriptor): String {
        stopMicCapture()
        lastStep = "-"
        markSession()
        val nonSensitive = source >= 100
        val src = source % 100
        t("===== startMicCapture build=$BUILD source=$src privacySensitive=${!nonSensitive} =====")
        return try {
            step("prepareEnv(shell環境)") { ctx }
            t("RECORD_AUDIO(shell)=${ctx.checkPermission("android.permission.RECORD_AUDIO", Process.myPid(), Process.myUid())} (0=GRANTED)")
            t("録音前の録音中クライアント: ${recordingConfigs()}")
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = step("new AudioRecord(source=$src, ${rate}Hz)") {
                AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(min, rate)) // 0.5 秒分
                    .apply { if (nonSensitive) setPrivacySensitive(false) }
                    .build()
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                try { rec.release() } catch (_: Throwable) {}
                throw IllegalStateException("AudioRecord が初期化できない state=${rec.state}")
            }
            val os = FileOutputStream(sink.fileDescriptor)
            val s = MicSession(rec, sink)
            // Bluetooth / 有線のイヤホンマイクを使っているときは、その入力機器を指定する(未指定だと本体マイクになることがある)
            val routeAm = try { ctx.getSystemService(AudioManager::class.java) } catch (_: Throwable) { null }
            if (routeAm != null) AudioRoute.applyInput(rec, routeAm, "マイク(特権)") { t(it) }
            step("mic.startRecording") { rec.startRecording() }
            var state = rec.recordingState
            if (state != AudioRecord.RECORDSTATE_RECORDING) {
                // PERMISSION_DENIED の典型原因: shell の RECORD_AUDIO appop が foreground 限定/ignore になっている。
                // 状態を記録し、allow に変えて 1 回だけ再試行する(shell は appops を変更できる)
                t("appops(before): " + shellOut("cmd", "appops", "get", "com.android.shell", "RECORD_AUDIO"))
                t("appops uid: " + shellOut("cmd", "appops", "get", "--uid", "2000", "RECORD_AUDIO"))
                t("appops set: " + shellOut("cmd", "appops", "set", "--uid", "2000", "RECORD_AUDIO", "allow"))
                t("appops set pkg: " + shellOut("cmd", "appops", "set", "com.android.shell", "RECORD_AUDIO", "allow"))
                t("appops(after): " + shellOut("cmd", "appops", "get", "com.android.shell", "RECORD_AUDIO"))
                try { rec.stop() } catch (_: Throwable) {}
                step("mic.startRecording(retry)") { rec.startRecording() }
                state = rec.recordingState
                t("retry recordingState=$state")
            }
            t("mic recordingState=$state (3=RECORDING)")
            if (state != AudioRecord.RECORDSTATE_RECORDING) {
                // startRecording() は失敗しても例外を投げない。AudioRecord の start status -1 は PERMISSION_DENIED
                val others = recordingConfigs()
                try { rec.release() } catch (_: Throwable) {}
                throw IllegalStateException(
                    "startRecording が拒否された(recordingState=$state / AudioRecord start status -1 = PERMISSION_DENIED の可能性)。" +
                        "録音中のクライアント: $others",
                )
            }
            if (routeAm != null) {
                s.watcher = AudioRoute.Watcher(routeAm) {
                    if (s.running) AudioRoute.applyInput(rec, routeAm, "マイク(特権・機器変更)") { t(it) }
                }.start()
            }
            s.thread = Thread {
                val buf = ByteArray(3200) // 16kHz s16le mono の 100ms
                var total = 0L
                var peakAll = 0
                var lastLog = System.currentTimeMillis()
                try {
                    while (s.running) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n < 0) { t("mic rec.read がエラーコード $n を返した(-3=INVALID_OPERATION,-6=DEAD_OBJECT) → マイク取得スレッド終了"); break }
                        if (n > 0) { os.write(buf, 0, n); total += n; peakAll = maxOf(peakAll, blockPeak(buf, n)) }
                        val now = System.currentTimeMillis()
                        if (now - lastLog > 3000) {
                            val routed = try { AudioRoute.describe(rec.routedDevice) } catch (_: Throwable) { "?" }
                            t("mic: total=${total}bytes peak=$peakAll routed=$routed"); lastLog = now; peakAll = 0
                        }
                    }
                } catch (e: Throwable) { te("マイク取得スレッドで例外", e) }
                try { os.close() } catch (_: Throwable) {}
                t("mic thread end total=${total}bytes")
            }.also { it.start() }
            mic = s
            t("===== startMicCapture OK =====")
            "OK: マイク取得開始 (source=$src, privacySensitive=${!nonSensitive}, rate=$rate, svcBuild=$BUILD)"
        } catch (e: Throwable) {
            try { sink.close() } catch (_: Throwable) {}
            val fs = lastStep
            te("startMicCapture 失敗 (step=$fs)", e)
            buildReport("マイク取得開始 NG (source=$src privacySensitive=${!nonSensitive})", e)
            "NG(step=$fs, svcBuild=$BUILD): ${err(e)}"
        }
    }

    /** 短いコマンドを実行して出力(最大 400 字)を返す。診断用 */
    private fun shellOut(vararg cmd: String): String = try {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out.take(400).ifEmpty { "(出力なし)" }
    } catch (e: Throwable) { "実行失敗: ${err(e)}" }

    override fun stopMicCapture(): String {
        val s = mic ?: return "マイク取得は動いていません"
        mic = null
        t("stopMicCapture")
        s.running = false
        try { s.watcher?.stop() } catch (_: Throwable) {}
        s.watcher = null
        try { s.thread?.join(500) } catch (_: Throwable) {}
        try { s.rec.stop() } catch (_: Throwable) {}
        try { s.rec.release() } catch (_: Throwable) {}
        try { s.sink.close() } catch (_: Throwable) {}
        return "OK: マイク取得停止"
    }

    // ---------- 音声クライアント一覧(通話アプリの特定) ----------
    /**
     * いま録音/再生している音声クライアントを返す。通話アプリは「通話用(VOICE_COMMUNICATION)の録音/再生をしているアプリ」なので、
     * 相手が話しているかどうか(音量)に依存せず、OS の状態から特定できる。
     * uid / playerState は @SystemApi のためリフレクションで読む(shell は MODIFY_AUDIO_ROUTING を持つので伏せ字にならない)。
     */
    override fun listAudioClients(): String {
        val token = Binder.clearCallingIdentity()
        try {
            return listAudioClientsImpl()
        } finally { Binder.restoreCallingIdentity(token) }
    }

    private fun intGetter(o: Any, name: String): Int? = try {
        (o.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(o) as? Number)?.toInt()
    } catch (_: Throwable) { null }

    private fun strGetter(o: Any, name: String): String? = try {
        o.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(o)?.toString()
    } catch (_: Throwable) { null }

    private fun listAudioClientsImpl(): String = try {
        val am = ctx.getSystemService(AudioManager::class.java)
        val sb = StringBuilder("OK\n")
        for (r in am.activeRecordingConfigurations) {
            sb.append("R uid=").append(intGetter(r, "getClientUid") ?: -1)
                .append(" pkg=").append(strGetter(r, "getClientPackageName") ?: "-")
                .append(" src=").append(r.clientAudioSource)
                .append(" silenced=").append(r.isClientSilenced)
                .append('\n')
        }
        for (p in am.activePlaybackConfigurations) {
            val aa = p.audioAttributes
            sb.append("P uid=").append(intGetter(p, "getClientUid") ?: -1)
                .append(" pid=").append(intGetter(p, "getClientPid") ?: -1)
                .append(" usage=").append(aa.usage)
                .append(" ctype=").append(aa.contentType)
                .append(" state=").append(intGetter(p, "getPlayerState") ?: -1)
                .append('\n')
        }
        sb.toString()
    } catch (e: Throwable) {
        "NG: ${err(e)}"
    }

    override fun destroy() {
        stopCapture(); stopInject(); stopMicCapture()
        val embedded = try {
            Class.forName("priv.kit.core.userservice.PrivilegeUserServiceEnvironment")
                .getDeclaredField("isEmbedded")
                .apply { isAccessible = true }
                .get(null) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }
        if (!embedded) {
            exitProcess(0)
        }
    }

    // ---------- AudioPolicy の登録(固定パターン: 取得=F / 注入=A) ----------
    // 相手音声取得: パターンF = UID指定 + voiceCommunicationCaptureAllowed(特権なし → 48kHz)
    // こちらの音声送信: パターンA = UID + 録音プリセット(VOICE_COMMUNICATION) / LOOP_BACK
    private fun register(inject: Boolean, uid: Int, variant: Int): Reg {
        // variant は互換のため受け取っているが、常に固定パターンで動作する
        val rc = step("Class AudioMixingRule") { Class.forName(pkg + "AudioMixingRule") }
        val mc = step("Class AudioMix") { Class.forName(pkg + "AudioMix") }
        val rb = step("new AudioMixingRule.Builder") { Class.forName(pkg + "AudioMixingRule\$Builder").getConstructor().newInstance() }
        if (inject) step("setTargetMixRole(INJECTOR)") { call(rb, "setTargetMixRole", ic(rc, "MIX_ROLE_INJECTOR", 1)) }

        val ruleUid = ic(rc, "RULE_MATCH_UID", 4)
        val rulePreset = ic(rc, "RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET", 2)
        val voicePreset = step("AudioAttributes.setCapturePreset(7)") {
            AudioAttributes.Builder().also {
                AudioAttributes.Builder::class.java.getMethod("setCapturePreset", Int::class.javaPrimitiveType).invoke(it, 7) // VOICE_COMMUNICATION
            }.build()
        }
        val loop = ic(mc, "ROUTE_FLAG_LOOP_BACK", 2)
        val render = ic(mc, "ROUTE_FLAG_RENDER", 1)

        val flags: Int
        val priv = false // パターンF/A とも特権なし → 48kHz
        val desc: String
        if (!inject) {
            // パターンF: UID + voiceCommunicationCaptureAllowed (特権なし, 48kHz)
            // variant=6(翻訳用・実験): LOOP_BACK のみ = 取得した相手音声を通常の出力(スピーカー/受話口)へ流さない(元の声を消す)
            val muteOriginal = variant == 6
            flags = if (muteOriginal) loop else ic(mc, "ROUTE_FLAG_LOOP_BACK_RENDER", loop or render)
            step("addMixRule(UID=$uid)") { call(rb, "addMixRule", ruleUid, uid) }
            step("voiceCommunicationCaptureAllowed(true)") { call(rb, "voiceCommunicationCaptureAllowed", true) }
            desc = if (muteOriginal) "capture F-mute: UID+voiceCommFlag/LOOP_BACK only (original muted, 48k)"
                   else "capture F: UID+voiceCommFlag(non-privileged, 48k)"
        } else {
            // パターンA: UID + 録音プリセット(VOICE_COMM) / LOOP_BACK
            step("addMixRule(UID=$uid)") { call(rb, "addMixRule", ruleUid, uid) }
            step("addMixRule(PRESET=VOICE_COMM)") { call(rb, "addMixRule", rulePreset, voicePreset) }
            flags = loop
            desc = "inject A: UID+preset/LOOP_BACK"
        }
        t("設定: $desc  routeFlags=$flags(0x${Integer.toHexString(flags)}) [LOOP_BACK=$loop RENDER=$render] privileged=$priv ruleConst(uid=$ruleUid preset=$rulePreset)")
        val rule = step("AudioMixingRule.Builder.build") { call(rb, "build")!! }

        val rate = 48000
        t("ミックスのサンプルレート=$rate (特権=$priv)")
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val mb = step("new AudioMix.Builder") { Class.forName(pkg + "AudioMix\$Builder").getConstructor(rc).newInstance(rule) }
        step("AudioMix.Builder.setFormat") { call(mb, "setFormat", fmt) }
        step("AudioMix.Builder.setRouteFlags") { call(mb, "setRouteFlags", flags) }
        val mix = step("AudioMix.Builder.build") { call(mb, "build")!! }

        val c = step("prepareEnv(shell環境)") { ctx }
        val ht = HandlerThread("audio-policy").also { it.start() }
        try {
            val pb = step("new AudioPolicy.Builder(ctx)") { Class.forName(pkg + "AudioPolicy\$Builder").getConstructor(Context::class.java).newInstance(c) }
            step("AudioPolicy.Builder.setLooper") { call(pb, "setLooper", ht.looper) }
            step("AudioPolicy.Builder.addMix") { call(pb, "addMix", mix) }
            val policy = step("AudioPolicy.Builder.build") { call(pb, "build")!! }
            val am = step("AudioManager 取得") {
                try { AudioManager::class.java.getConstructor(Context::class.java).newInstance(c) }
                catch (e: Throwable) { t("AudioManager(Context) 生成失敗→getSystemService にフォールバック: ${err(e)}"); c.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
            }
            val res = step("registerAudioPolicy") {
                try {
                    AudioManager::class.java.getMethod("registerAudioPolicy", Class.forName(pkg + "AudioPolicy")).invoke(am, policy) as Int
                } catch (e: InvocationTargetException) { throw e.targetException }
            }
            t("registerAudioPolicy 戻り値=$res (0=SUCCESS, -1=ERROR)")
            if (res != 0) throw IllegalStateException("registerAudioPolicy が $res を返した(0=成功)。AudioService 側のログに拒否理由が出ているはず")
            return Reg(policy, mix, am, ht, rate)
        } catch (e: Throwable) {
            ht.quitSafely(); throw e
        }
    }

    private fun close(s: Session) {
        s.running = false
        try { s.thread?.join(500) } catch (_: Throwable) {}
        s.closers.forEach { try { it() } catch (_: Throwable) {} }
        try {
            val r = AudioManager::class.java.getMethod("unregisterAudioPolicy", Class.forName(pkg + "AudioPolicy")).invoke(s.am, s.policy)
            t("unregisterAudioPolicy OK (戻り値=$r)")
        } catch (e: Throwable) { te("unregisterAudioPolicy 失敗(ポリシーが残る可能性)", e) }
        // 解除通知が届いてから Looper を止める(dead thread 例外の回避)
        try { Thread.sleep(100) } catch (_: Throwable) {}
        s.ht.quitSafely()
    }

    // ---------- shell プロセスを「アプリっぽく」する(AudioRecord の attribution 対策) ----------
    private fun prepareEnv(): Context {
        val at = step("Class ActivityThread") { Class.forName("android.app.ActivityThread") }
        try { Looper.prepareMainLooper(); t("Looper.prepareMainLooper OK") } catch (e: Throwable) { t("Looper.prepareMainLooper 例外(無視): ${err(e)}") }
        var thread = step("currentActivityThread") { at.getMethod("currentActivityThread").invoke(null) }
        if (thread == null) {
            thread = step("new ActivityThread + sCurrentActivityThread") {
                val th = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, th)
                at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(th, true)
                th
            }
            try { // Android 14+
                val internal = Class.forName("android.app.ActivityThreadInternal")
                val ctl = Class.forName("android.app.ConfigurationController").getDeclaredConstructor(internal).newInstance(thread)
                at.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, ctl)
                t("ConfigurationController 設定 OK(Android 14+)")
            } catch (e: Throwable) { t("ConfigurationController 設定スキップ: ${err(e)}") }
        } else t("既存の ActivityThread を使用")
        val sys = step("getSystemContext") { at.getMethod("getSystemContext").invoke(thread) as Context }
        val shell = ShellContext(sys)
        val app = step("Instrumentation.newApplication") { Instrumentation.newApplication(Application::class.java, shell) }
        step("mInitialApplication 設定") { at.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, app) }
        return shell
    }

    // ---------- util ----------
    private fun ic(c: Class<*>, n: String, def: Int) = try { c.getField(n).getInt(null) } catch (_: Throwable) { def }

    private fun call(o: Any, name: String, vararg a: Any?): Any? {
        val m = o.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == a.size }
            ?: throw NoSuchMethodException("${o.javaClass.simpleName}.$name/${a.size}")
        try { return m.invoke(o, *a) } catch (e: InvocationTargetException) { throw e.targetException }
    }

    private fun err(e: Throwable): String {
        val t = (e as? InvocationTargetException)?.targetException ?: e
        return "${t.javaClass.simpleName}: ${t.message}"
    }

    private fun blockPeak(b: ByteArray, n: Int): Int {
        var m = 0; var i = 0
        while (i + 1 < n) {
            val v = ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toInt()
            m = maxOf(m, abs(v)); i += 2
        }
        return m
    }
}
