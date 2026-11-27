package com.aichat.app.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** OkHttp 客户端。超时同旧版 NativeBridge：连接 30s；流式读 600s（spec B-M4）。带 Key 的请求不跟随重定向。 */
object HttpClients {
    const val CONNECT_TIMEOUT_S = 30L
    const val READ_TIMEOUT_S = 600L

    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)   // 重试策略由 ChatEngine（netretry.js 移植）决定，避免重复扣费
            .build()
    }
    /** 带 Key 的请求：不跟随重定向，避免认证头被带到其他主机 */
    val authed: OkHttpClient by lazy { base.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    /** 公开图片：短超时 + 可重试连接；不要复用 base 的 600s 流式读超时。 */
    val image: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
