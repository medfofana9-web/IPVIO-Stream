package com.ipvio.stream

import java.util.Locale

/**
 * Lower-cases and spells out letters that Unicode NFD does not split into base letter + accent
 * ("Bodø/Glimt" → "bodo/glimt", "Æ" → "ae", "ß" → "ss"). Every sports name and channel comparison runs
 * text through this before stripping accents, so a playlist and a scoreboard that spell a team
 * differently still match.
 */
object TextFold {
    fun fold(s: String): String {
        val l = s.lowercase(Locale.ROOT)
        if (l.none { it in SPECIAL }) return l
        val b = StringBuilder(l.length + 4)
        for (ch in l) when (ch) {
            'ø' -> b.append('o'); 'æ' -> b.append("ae"); 'œ' -> b.append("oe"); 'ß' -> b.append("ss")
            'đ', 'ð' -> b.append('d'); 'ł' -> b.append('l'); 'ı' -> b.append('i'); 'þ' -> b.append("th")
            else -> b.append(ch)
        }
        return b.toString()
    }

    private const val SPECIAL = "øæœßđðłıþ"
}
