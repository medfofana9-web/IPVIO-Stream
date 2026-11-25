package com.ipvio.stream

/**
 * Work for the next stream start done ahead, while a sports screen is on (never on the OK press): the provider's
 * stream count ([ConnGuard]) and its host name ([SmartDns]). Pressing OK then goes straight to the player.
 */
object StartPrep {
    @Volatile private var lastAt = 0L

    /** At most every 20 s; cheap when nothing is due. */
    fun warm() {
        val now = System.currentTimeMillis()
        if (now - lastAt < 20_000L) return
        lastAt = now
        val url = runCatching {
            PlaylistStore.channels.asSequence().filter { it.kind == Kind.LIVE && it.src == 0 }.map { it.url }
                .firstOrNull { ConnLogic.acctOf(it) != null }
        }.getOrNull() ?: return
        ConnGuard.prefetch(url)
        SmartDns.prefetch(runCatching { java.net.URI(url).host }.getOrNull())
    }
}
