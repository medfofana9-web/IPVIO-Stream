package com.ipvio.stream

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One shared HTTP client for the whole app. Derived clients (other timeouts) share
 * the connection pool and threads instead of each area creating its own – saves memory on weak devices.
 */
object Net {
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // Secure name lookup: works on networks that block IPTV addresses (see SmartDns)
        .dns(SmartDns)
        // Backup servers: if an Xtream server fails, requests go to one of the backup servers entered for it
        .addInterceptor(HostFailover.Interceptor)
        .build()

    /** For the player: same secure lookup, follows redirects (also http <-> https, common with IPTV servers). */
    val player: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .dns(SmartDns)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun withTimeouts(connectSec: Long, readSec: Long): OkHttpClient =
        base.newBuilder().connectTimeout(connectSec, TimeUnit.SECONDS).readTimeout(readSec, TimeUnit.SECONDS).build()
}
