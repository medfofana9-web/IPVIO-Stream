package com.ipvio.stream

/** Persisted sports tab indices include deep-linked Teams, Favorites and Channels. */
internal object SportTabState {
    fun restore(saved: Int, count: Int): Int = saved.takeIf { it in 0 until count } ?: 0
}
