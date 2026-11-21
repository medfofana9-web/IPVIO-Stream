package com.ipvio.stream

/** English-first migration with respect for a language chosen by the viewer. */
internal object AppLanguagePolicy {
    /**
     * On the first launch after this upgrade, an unset or legacy automatic
     * (blank) language becomes English. Explicit choices ("de", "es" etc.)
     * are preserved. On later launches, even an explicitly chosen automatic
     * (blank) setting is respected.
     */
    fun initial(saved: String?, initialized: Boolean): String =
        if (initialized) saved ?: "en" else saved?.takeIf { it.isNotBlank() } ?: "en"
}
