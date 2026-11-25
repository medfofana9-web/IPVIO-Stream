package com.ipvio.stream

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Name lookup over a secure DNS service (Cloudflare and Google) with the device's own lookup as fallback.
 * Some home networks answer the lookup for IPTV addresses with their own block page: the provider's https address
 * then fails its certificate and http returns a web page instead of the playlist. Encrypted lookups get the real
 * address, so the same login works on any network (requested by the maintainer).
 *
 * Speed (every stream start resolves the provider and its redirect host): Cloudflare and Google are asked at the same
 * time and the first answer wins, with a [DnsLogic.DOH_BUDGET_MS] budget instead of 3 + 3 s per service one after the
 * other (up to 12 s on a network where they are slow). A known name answers at once from the cache, also after its
 * 10 minutes, while it is renewed in the background. When the secure services do not answer on this network they are
 * skipped for a while and the device lookup is used directly.
 */
object SmartDns : Dns {
    private val boot = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    private val cloudflare = DnsOverHttps.Builder().client(boot).url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1")).build()
    private val google = DnsOverHttps.Builder().client(boot).url("https://dns.google/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4")).build()
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val renewing = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "fof-dns").apply { isDaemon = true } }
    @Volatile private var dohSlowUntil = 0L

    override fun lookup(hostname: String): List<InetAddress> {
        // Literal addresses and local names need no lookup service
        if (hostname.firstOrNull()?.isDigit() == true || hostname.contains(':') || !hostname.contains('.') || hostname.endsWith(".local")) return Dns.SYSTEM.lookup(hostname)
        val now = System.currentTimeMillis()
        cache[hostname]?.let { (at, addrs) ->
            when (DnsLogic.cacheUse(at, now)) {
                DnsLogic.Use.FRESH -> return addrs
                // Known name: answer now, renew in the background (the stream start never waits for the lookup)
                DnsLogic.Use.STALE -> { renewLater(hostname); return addrs }
                DnsLogic.Use.EXPIRED -> {}
            }
        }
        return resolve(hostname)
    }

    /** Resolve [hostname] ahead (e.g. the provider when Sports opens), so the first stream start finds it cached. */
    fun prefetch(hostname: String?) {
        if (hostname.isNullOrBlank()) return
        val c = cache[hostname]
        if (c != null && DnsLogic.cacheUse(c.first, System.currentTimeMillis()) == DnsLogic.Use.FRESH) return
        renewLater(hostname)
    }

    private fun renewLater(hostname: String) {
        if (!renewing.add(hostname)) return
        pool.execute { try { runCatching { resolve(hostname) } } finally { renewing.remove(hostname) } }
    }

    private fun resolve(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        val secure = if (now < dohSlowUntil) null else secureLookup(hostname)
        if (secure == null) {
            if (now >= dohSlowUntil) dohSlowUntil = now + DnsLogic.DOH_SKIP_MS
            val sys = Dns.SYSTEM.lookup(hostname)
            // Device answers are kept briefly only: the secure answer replaces them once the services respond again
            if (sys.isNotEmpty()) cache[hostname] = (now - DnsLogic.FRESH_MS + DnsLogic.SYSTEM_KEEP_MS) to sys
            return sys
        }
        dohSlowUntil = 0L
        cache[hostname] = now to secure
        return secure
    }

    /** Both secure services at once; the first non-empty answer within the budget, else null. */
    private fun secureLookup(hostname: String): List<InetAddress>? {
        val cs = ExecutorCompletionService<List<InetAddress>?>(pool)
        val tasks = listOf(
            cs.submit { runCatching { cloudflare.lookup(hostname) }.getOrNull()?.takeIf { it.isNotEmpty() } },
            cs.submit { runCatching { google.lookup(hostname) }.getOrNull()?.takeIf { it.isNotEmpty() } }
        )
        val deadline = System.currentTimeMillis() + DnsLogic.DOH_BUDGET_MS
        try {
            repeat(tasks.size) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                val f = cs.poll(left, TimeUnit.MILLISECONDS) ?: return null
                runCatching { f.get() }.getOrNull()?.let { return it }
            }
            return null
        } finally {
            tasks.forEach { it.cancel(true) }
        }
    }
}

/** Pure timing rules of [SmartDns] (tested in DnsLogicTest). */
object DnsLogic {
    const val FRESH_MS = 10 * 60_000L
    /** A cached answer may still be used (and renewed in the background) for this long. */
    const val STALE_MS = 24 * 3_600_000L
    const val DOH_BUDGET_MS = 1_500L
    const val DOH_SKIP_MS = 5 * 60_000L
    const val SYSTEM_KEEP_MS = 60_000L

    enum class Use { FRESH, STALE, EXPIRED }

    fun cacheUse(atMs: Long, nowMs: Long): Use = when {
        nowMs < atMs -> Use.STALE   // clock moved back: still usable, renew it
        nowMs - atMs < FRESH_MS -> Use.FRESH
        nowMs - atMs < STALE_MS -> Use.STALE
        else -> Use.EXPIRED
    }
}
