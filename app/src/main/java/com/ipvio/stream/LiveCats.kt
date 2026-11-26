package com.ipvio.stream

import android.content.Context
import org.json.JSONArray

/**
 * "My categories": the live categories of the provider this household uses, chosen once on the Mac or iPhone and
 * stored in the account (fof_account_config.settings.live_cats). Every TV then reads only these categories from the
 * playlist: far less memory and start time on small TVs (a full provider list did not fit on the Fire TV), and only
 * the channels the family watches. Empty = everything, as before.
 */
object LiveCats {
    private const val PREFS = "fof_livecats"
    private const val KEY = "cats"

    fun get(c: Context): Set<String> = runCatching {
        val a = JSONArray(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    /** Saves the account's choice; true when it changed (the playlist is then read again). */
    fun set(c: Context, cats: Set<String>): Boolean {
        if (cats == get(c)) return false
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, JSONArray(cats.sorted()).toString()).apply()
        return true
    }

    /** Live channels outside the chosen categories are left out (movies and series are not affected). */
    fun keep(kind: Kind, group: String, cats: Set<String>): Boolean = cats.isEmpty() || kind != Kind.LIVE || group in cats
}
