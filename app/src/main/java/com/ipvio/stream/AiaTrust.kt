package com.ipvio.stream

import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Completes a server's certificate chain like Chrome does: some providers send their certificate without the
 * intermediate certificate. Browsers (and the Mac app) download that missing link from the address written in the
 * certificate (Authority Information Access) – Android does not and fails with "Secure connection failed
 * (certificate)". When the normal check fails, the missing issuer is downloaded and the full chain is checked again by
 * the device's own trust check (same roots, same rules; nothing is accepted that would not pass with a complete chain).
 */
object AiaTrust {
    private val system: X509TrustManager by lazy {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
    private val fetched = ConcurrentHashMap<String, X509Certificate>()

    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                val full = complete(chain) ?: throw e
                system.checkServerTrusted(full, authType)
            }
        }
    }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    /** Chain plus up to two downloaded issuers, or null when nothing could be added. */
    private fun complete(chain: Array<X509Certificate>): Array<X509Certificate>? {
        if (chain.isEmpty()) return null
        val out = chain.toMutableList()
        var last = chain.last()
        repeat(2) {
            if (last.subjectX500Principal == last.issuerX500Principal) return@repeat
            val url = issuerUrl(last) ?: return@repeat
            val next = fetched[url] ?: runCatching { download(url) }.getOrNull()?.also { fetched[url] = it } ?: return@repeat
            if (out.any { it == next }) return@repeat
            out.add(next); last = next
        }
        return if (out.size > chain.size) out.toTypedArray() else null
    }

    /** "CA Issuers" address from the certificate's Authority Information Access extension. */
    private fun issuerUrl(c: X509Certificate): String? {
        val raw = c.getExtensionValue("1.3.6.1.5.5.7.1.1") ?: return null
        val text = String(raw, Charsets.ISO_8859_1)
        return Regex("https?://[\\x21-\\x7e]+?\\.(?:crt|cer|der|p7c)").find(text)?.value
    }

    private fun download(url: String): X509Certificate? {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 5_000; con.readTimeout = 5_000
        return try {
            con.inputStream.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as? X509Certificate }
        } finally { con.disconnect() }
    }
}
