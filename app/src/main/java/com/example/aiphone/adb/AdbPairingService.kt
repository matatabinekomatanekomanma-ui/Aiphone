package com.example.aiphone.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.example.aiphone.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import priv.kit.core.Privilege

/**
 * Shizuku と同じワイヤレス ADB ペアリング通知フロー。
 *
 * 1. 検索中通知をフォアグラウンド表示
 * 2. mDNS でペアリングポート発見 → 画面上部にヘッドアップ通知
 *    （RemoteInput のテキスト入力欄付き）
 * 3. 6桁入力 → priv-core でペアリング
 *
 * - NSD の resolveService は同時に1件しか走らせられない端末(API 33 以下)があるため直列化する。
 * - 入力待ちのまま放置されても前面サービスが居座らないよう、一定時間でタイムアウトして終了する。
 */
class AdbPairingService : Service() {

    companion object {
        const val CHANNEL_ID = "denwa_adb_pairing_v2"
        private const val TAG = "AdbPairingService"
        private const val NOTIFICATION_ID = 301

        private const val ACTION_START = "com.example.aiphone.adb.START"
        private const val ACTION_STOP = "com.example.aiphone.adb.STOP"
        private const val ACTION_REPLY = "com.example.aiphone.adb.REPLY"
        private const val EXTRA_PORT = "port"
        private const val REMOTE_INPUT_KEY = "pairing_code"

        private const val SERVICE_TYPE = "_adb-tls-pairing._tcp"

        private const val REQUEST_REPLY = 1
        private const val REQUEST_STOP = 2

        /** 検索開始から入力完了までの待ち時間 */
        private const val SEARCH_TIMEOUT_MS = 5 * 60_000L
        /** 失敗通知を出してから自動終了するまで */
        private const val FAILURE_LINGER_MS = 2 * 60_000L

        fun startIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_STOP)

        private fun replyIntent(context: Context, port: Int): Intent =
            Intent(context, AdbPairingService::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(EXTRA_PORT, port)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var foundPort: Int = -1
    @Volatile private var searching = false
    private var timeoutJob: Job? = null

    // resolveService の直列化（NSD コールバックスレッドから触るので synchronized）
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification? = when (intent?.action) {
            ACTION_START -> onStart()
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_INPUT_KEY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                if (port > 0 && code.length == 6 && code.all { it.isDigit() }) {
                    submitCode(code, port)
                    workingNotification()
                } else {
                    val p = if (port > 0) port else foundPort
                    if (p > 0) createInputNotification(p) else onStart()
                }
            }
            ACTION_STOP -> {
                timeoutJob?.cancel()
                stopSearch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            else -> onStart()
        }

        if (notification != null) {
            startForegroundSafely(notification)
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        timeoutJob?.cancel()
        stopSearch()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // 既存チャネルを作り直すと importance が反映されない端末があるため、
        // 初回作成時に HIGH で作る。ユーザーが下げている場合は案内が必要。
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.adb_pairing_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(R.string.adb_pairing_channel_name)
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 200)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }

    private fun onStart(): Notification {
        startSearch()
        scheduleTimeout(SEARCH_TIMEOUT_MS)
        return searchingNotification()
    }

    /** 一定時間後に検索を止めてサービスを終了する（再設定すると前のタイマーは破棄） */
    private fun scheduleTimeout(ms: Long) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(ms)
            Log.i(TAG, "pairing timeout → stop")
            stopSearch()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startSearch() {
        if (searching) return
        searching = true
        foundPort = -1
        synchronized(resolveQueue) { resolveQueue.clear(); resolving = false }
        val manager = getSystemService(NsdManager::class.java)
        nsdManager = manager

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "mDNS discovery started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service found: ${serviceInfo.serviceName}")
                synchronized(resolveQueue) {
                    resolveQueue.addLast(serviceInfo)
                }
                resolveNext(manager)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "mDNS discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                searching = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices failed", e)
            searching = false
        }
    }

    /** キューの先頭を1件だけ resolve する。完了/失敗したら次へ進む */
    private fun resolveNext(manager: NsdManager) {
        val info = synchronized(resolveQueue) {
            if (resolving || resolveQueue.isEmpty()) return
            resolving = true
            resolveQueue.removeFirst()
        }
        fun done() {
            synchronized(resolveQueue) { resolving = false }
            if (searching) resolveNext(manager)
        }
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed: $errorCode")
                        done()
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val port = resolved.port
                        Log.i(TAG, "Pairing port resolved: $port")
                        if (port > 0 && searching) {
                            foundPort = port
                            // IMPORTANCE_HIGH + onlyAlertOnce なしで、画面上部にヘッドアップ。
                            // サービスは既にフォアグラウンドなので notify だけで通知を差し替えられる。
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, createInputNotification(port))
                        }
                        done()
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "resolveService failed", e)
            done()
        }
    }

    private fun stopSearch() {
        if (!searching) return
        searching = false
        discoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        discoveryListener = null
        nsdManager = null
        foundPort = -1
        synchronized(resolveQueue) { resolveQueue.clear(); resolving = false }
    }

    private fun submitCode(code: String, port: Int) {
        // 入力後は成功/失敗が決まるまでタイマーを止める
        timeoutJob?.cancel()
        scope.launch(Dispatchers.IO) {
            val nm = getSystemService(NotificationManager::class.java)
            try {
                val adbManager = Privilege.createAdbManager()
                adbManager.pair(
                    pairingCode = code,
                    port = port,
                    portDiscoveryTimeoutMillis = 5_000L,
                )
                Log.i(TAG, "Pairing succeeded on port $port")
                stopSearch()

                // ペアリング成功 → 自動で priv-kit の ADB サーバーを起動
                // (既にフォアグラウンドなので notify だけで通知を差し替える)
                nm.notify(NOTIFICATION_ID, startingServerNotification())

                Privilege.startAdb()
                Log.i(TAG, "Privilege.startAdb() succeeded")

                nm.notify(NOTIFICATION_ID, successNotification())
                delay(2_500)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Pairing or start failed", e)
                nm.notify(NOTIFICATION_ID, failureNotification(e.message))
                scheduleTimeout(FAILURE_LINGER_MS)
            }
        }
    }

    private fun startForegroundSafely(notification: Notification) {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    private fun mutablePendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun immutablePendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun searchingNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle(getString(R.string.adb_pairing_searching_title))
            .setContentText(getString(R.string.adb_pairing_searching_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(stopNotificationAction())
            .build()

    /**
     * Shizuku と同じ入力通知。
     * ポート発見時に差し替えられ、画面上部へヘッドアップする。
     * 「入力」をタップ／展開するとキーボード付きテキスト欄が表示される。
     */
    private fun createInputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(getString(R.string.adb_pairing_input_label))
            .build()

        val replyPending = PendingIntent.getForegroundService(
            this,
            REQUEST_REPLY,
            replyIntent(this, port),
            mutablePendingFlags(),
        )

        val replyAction = Notification.Action.Builder(
            null,
            getString(R.string.adb_pairing_input_action),
            replyPending,
        )
            .addRemoteInput(remoteInput)
            .build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle(getString(R.string.adb_pairing_found_title))
            .setContentText(getString(R.string.adb_pairing_found_text))
            .setOngoing(true)
            // 検索→入力の切替でヘッドアップさせるため onlyAlertOnce は付けない
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                @Suppress("DEPRECATION")
                setDefaults(Notification.DEFAULT_VIBRATE)
            }
            .addAction(replyAction)
            .addAction(stopNotificationAction())
            .build()
    }

    private fun workingNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle(getString(R.string.adb_pairing_working_title))
            .setContentText(getString(R.string.adb_pairing_working_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

    private fun startingServerNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle(getString(R.string.adb_pairing_starting_title))
            .setContentText(getString(R.string.adb_pairing_starting_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

    private fun successNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle(getString(R.string.adb_pairing_success_title))
            .setContentText(getString(R.string.adb_pairing_success_text))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

    private fun failureNotification(message: String?): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle(getString(R.string.adb_pairing_failed_title))
            .setContentText(
                message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.adb_pairing_failed_text),
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .addAction(stopNotificationAction())
            .build()

    private fun stopNotificationAction(): Notification.Action {
        val pending = PendingIntent.getForegroundService(
            this,
            REQUEST_STOP,
            stopIntent(this),
            immutablePendingFlags(),
        )
        return Notification.Action.Builder(
            null,
            getString(R.string.adb_pairing_stop_action),
            pending,
        ).build()
    }

    private fun smallIcon(): Int =
        applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
}
