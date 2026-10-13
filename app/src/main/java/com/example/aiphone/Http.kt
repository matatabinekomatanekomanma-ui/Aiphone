package com.example.aiphone

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** アプリ全体で共有する OkHttpClient（接続プール・スレッドを使い回す）。 */
object Http {
    /** WebSocket 向け: 読み取りタイムアウトなし + 20 秒 ping */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
