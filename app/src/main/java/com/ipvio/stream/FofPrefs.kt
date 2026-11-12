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

    fun init(c: Context) { oneClick = c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("one_click", true) }

    fun setOneClick(c: Context, on: Boolean) {
        oneClick = on
        c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean("one_click", on).apply()
    }
}
