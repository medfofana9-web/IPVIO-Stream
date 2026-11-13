package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Fof TV switches (also changeable on the phone settings page, see [SettingsWeb]). */
object FofPrefs {
    private const val NAME = "fof_flags"
    /** Play starts the best addon stream at once instead of showing the list. */
    var oneClick by mutableStateOf(true)
        private set

    /** Movies and series from the IPTV provider in search (off: the Stremio addons are the only source for them). */
    var providerVod by mutableStateOf(false)
        private set

    /** Pictures of episodes not watched yet are blurred (no spoilers); they become sharp once watched. */
    var spoilerPics by mutableStateOf(true)
        private set
    /** Descriptions of episodes not watched yet are hidden. */
    var spoilerText by mutableStateOf(false)
        private set

    fun setFlag(c: Context, key: String, on: Boolean) {
        when (key) {
            "spoiler_pics" -> spoilerPics = on
            "spoiler_text" -> spoilerText = on
            "provider_vod" -> providerVod = on
            "one_click" -> oneClick = on
            else -> return
        }
        c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(key, on).apply()
    }

    fun init(c: Context) {
        val p = c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        spoilerPics = p.getBoolean("spoiler_pics", true)
        spoilerText = p.getBoolean("spoiler_text", false)
        oneClick = p.getBoolean("one_click", true)
        providerVod = p.getBoolean("provider_vod", false)
    }

    fun setProviderVod(c: Context, on: Boolean) {
        providerVod = on
        c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean("provider_vod", on).apply()
    }

    fun setOneClick(c: Context, on: Boolean) {
        oneClick = on
        c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean("one_click", on).apply()
    }
}
