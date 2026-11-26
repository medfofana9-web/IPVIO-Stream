package com.ipvio.stream

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Request headers that some addon streams need (Stremio `behaviorHints.proxyHeaders.request`, e.g. Referer, Origin,
 * User-Agent). The addon lookup records them per stream URL; [begin] picks them up when the player opens that URL, and
 * [wrap] adds them to every request of that playback (playlist, segments, keys), since segments often come from
 * another host. Opening any other URL (IPTV channel, plain link) clears them, so they never leak to other streams.
 */
object StreamHeaders {
    private const val MAX = 500
    private val byUrl = ConcurrentHashMap<String, Map<String, String>>()

    @Volatile private var active: Map<String, String>? = null

    fun remember(url: String, headers: Map<String, String>) {
        if (headers.isEmpty()) return
        if (byUrl.size > MAX) byUrl.clear()
        byUrl[url] = headers
    }

    /** Headers an addon asked for this link (the background source check sends them like the player). */
    fun headersFor(url: String): Map<String, String> = byUrl[url].orEmpty()

    /** Called right before the player opens [url]. */
    fun begin(url: String) {
        active = byUrl[url]
    }

    fun wrap(upstream: DataSource.Factory): DataSource.Factory = ResolvingDataSource.Factory(upstream) { spec ->
        val h = active
        val scheme = spec.uri.scheme
        if (h == null || (scheme != "http" && scheme != "https")) spec else spec.withAdditionalHeaders(h)
    }

    /** Header names an addon may set; anything else (Host, Content-Length …) is ignored. */
    internal fun allowed(name: String): Boolean = name.isNotBlank() && name.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding")
}
