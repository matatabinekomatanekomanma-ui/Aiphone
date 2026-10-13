package com.example.aiphone

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import kotlin.coroutines.resume

/**
 * priv-kit の UserService(AudioBridgeService) との接続を管理する。
 *
 * 以前は MainActivity が接続していたため、Activity の破棄（画面回転・タスク破棄）で
 * 通話中でもブリッジが切れていた。ここでは [AiCallService] が所有し、
 * - priv-kit サーバーの起動/停止を監視して自動で接続/切断
 * - 特権プロセスが死んだら(Binder death)バックオフ付きで再接続
 * を行う。UI は [state] を観測するだけでよい。
 */
object BridgeConnector {
    private const val TAG = "RLAudio"
    private const val BACKOFF_MIN_MS = 1_000L
    private const val BACKOFF_MAX_MS = 30_000L

    private val spec = PrivilegeUserServiceSpec(
        serviceClassName = "com.example.aiphone.AudioBridgeService",
        tag = "audio-bridge",
        // versionCode に連動。上げ忘れによる古い UserService の居残りを防ぐ
        version = BuildConfig.VERSION_CODE,
        embedded = false,
        daemon = false,
    )

    private val _state = MutableStateFlow<IAudioBridge?>(null)

    /** 接続中のブリッジ。未接続は null */
    val state: StateFlow<IAudioBridge?> = _state
    val bridge: IAudioBridge? get() = _state.value

    fun isAlive(): Boolean = bridge?.asBinder()?.isBinderAlive == true

    /**
     * priv-kit(shell)経由で SYSTEM_ALERT_WINDOW を自パッケージに付与する。
     * 未接続や失敗時は false。
     */
    fun grantOverlayPermission(packageName: String): Boolean {
        val b = bridge ?: return false
        return try {
            val r = b.grantOverlayPermission(packageName)
            Log.i(TAG, "grantOverlayPermission($packageName) → $r")
            r == "ok" || r.startsWith("ok")
        } catch (e: Exception) {
            Log.w(TAG, "grantOverlayPermission remote failed", e)
            false
        }
    }


    private val lock = Mutex()
    private var job: Job? = null
    private var connection: Any? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            Privilege.serverState.collectLatest { info ->
                if (info == null) {
                    Log.i(TAG, "[bridge] priv-kit サーバーが停止しました")
                    unbind(stopService = true)
                } else {
                    Log.i(TAG, "[bridge] priv-kit サーバー準備完了 — AudioBridge に接続します")
                    superviseConnection()
                }
            }
        }
    }

    /** サービス終了時に呼ぶ。監視を止めて接続を解放する */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        CoroutineScope(Dispatchers.IO).launch { unbind(stopService = true) }
    }

    /** 接続→死活監視→切れたら再接続、を繰り返す（キャンセルされるまで） */
    private suspend fun superviseConnection() {
        var backoff = BACKOFF_MIN_MS
        while (currentCoroutineContext().isActive) {
            try {
                val binder = bind()
                backoff = BACKOFF_MIN_MS
                awaitDeath(binder)
                Log.w(TAG, "[bridge] 特権プロセスとの接続が切れました")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "[bridge] UserService 接続失敗: ${e.javaClass.simpleName}: ${e.message}")
            }
            unbind(stopService = false)
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
        }
    }

    private suspend fun bind(): IBinder = lock.withLock {
        bridge?.asBinder()?.takeIf { it.isBinderAlive }?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            Privilege.startUserService(spec)
            val conn = Privilege.bindUserService(spec)
            val binder = extractBinder(conn) ?: error("bind OK but binder is null")
            connection = conn
            _state.value = IAudioBridge.Stub.asInterface(binder)
            Log.i(TAG, "[bridge] サービス接続OK (priv-kit shell/ADB)")
            binder
        }
    }

    private suspend fun unbind(stopService: Boolean) {
        lock.withLock {
            val conn = connection
            connection = null
            _state.value = null
            if (conn != null) {
                runCatching {
                    conn.javaClass.methods
                        .firstOrNull { it.name == "unbind" && it.parameterCount == 0 }
                        ?.invoke(conn)
                }
            }
            if (stopService) {
                runCatching { Privilege.stopUserService(spec) }
            }
        }
    }

    private suspend fun awaitDeath(binder: IBinder) = suspendCancellableCoroutine<Unit> { c ->
        val recipient = IBinder.DeathRecipient { if (c.isActive) c.resume(Unit) }
        try {
            binder.linkToDeath(recipient, 0)
        } catch (_: RemoteException) {
            c.resume(Unit)
            return@suspendCancellableCoroutine
        }
        c.invokeOnCancellation { runCatching { binder.unlinkToDeath(recipient, 0) } }
    }

    private fun extractBinder(connection: Any): IBinder? {
        for (name in listOf("getBinder", "binder", "asBinder")) {
            val m = connection.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            } ?: continue
            val v = m.invoke(connection)
            if (v is IBinder) return v
        }
        for (field in connection.javaClass.declaredFields) {
            field.isAccessible = true
            val v = field.get(connection)
            if (v is IBinder) return v
        }
        return null
    }
}
