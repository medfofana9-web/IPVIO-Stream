package com.ipvio.stream

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Name lookup over a secure DNS service (Cloudflare, then Google) with the device's own lookup as fallback.
 * Some home networks answer the lookup for IPTV addresses with their own block page: the provider's https address
 * then fails its certificate and http returns a web page instead of the playlist. Encrypted lookups get the real
 * address, so the same login works on any network (requested by the maintainer). Results are kept for 10 minutes.
 */
object SmartDns : Dns {
    private val boot = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    private val cloudflare = DnsOverHttps.Builder().client(boot).url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1")).build()
    private val google = DnsOverHttps.Builder().client(boot).url("https://dns.google/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4")).build()
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    override fun lookup(hostname: String): List<InetAddress> {
        // Literal addresses and local names need no lookup service
        if (hostname.firstOrNull()?.isDigit() == true || hostname.contains(':') || !hostname.contains('.') || hostname.endsWith(".local")) return Dns.SYSTEM.lookup(hostname)
        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (now - it.first < 600_000L) return it.second }
        val r = runCatching { cloudflare.lookup(hostname) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { google.lookup(hostname) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return Dns.SYSTEM.lookup(hostname)
        cache[hostname] = now to r
        return r
    }
}
