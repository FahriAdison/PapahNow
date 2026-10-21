package com.papahchan.papahnow.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide OkHttp clients shared by every GfnSessionClient and
 * GfnSignalingClient instance.
 *
 * Building a client per Connect cycle orphaned its dispatcher, connection pool
 * and worker threads on every Disconnect. The signaling client is derived from
 * the session client with newBuilder(), so both share one dispatcher and one
 * connection pool for the lifetime of the process.
 */
object GfnHttpClients {

    val session: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** WebSocket client: no read timeout, 10s ping, same pool as [session]. */
    val signaling: OkHttpClient = session.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
}
