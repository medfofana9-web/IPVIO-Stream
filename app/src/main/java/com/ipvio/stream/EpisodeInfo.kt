package com.ipvio.stream

import java.util.concurrent.ConcurrentHashMap

/**
 * One place for episode details (title, synopsis, still, runtime) shared by the title page and the player's episode
 * list. Keys use the app's own season and episode numbers. The title page stores what it loaded from TMDB (already
 * mapped to the app's numbering, long anime included); the catalog's episode list (Cinemeta) is the fallback.
 * Watch progress has its own single store: [EpProgress] (per profile, synced with the account).
 */
object EpisodeInfo {
    private val bySeries = ConcurrentHashMap<String, ConcurrentHashMap<Long, EpMeta>>()

    fun put(seriesId: String, season: Int, ep: Int, m: EpMeta) {
        bySeries.getOrPut(seriesId) { ConcurrentHashMap() }[ResumeLogic.key(season, ep)] = m
    }

    fun get(seriesId: String, season: Int, ep: Int): EpMeta? =
        bySeries[seriesId]?.get(ResumeLogic.key(season, ep))
            ?: LazySeries.cmEpisodes[seriesId]?.get(season)?.firstOrNull { it.num == ep }

    fun has(seriesId: String): Boolean = bySeries[seriesId]?.isNotEmpty() == true || LazySeries.cmEpisodes[seriesId] != null
}
