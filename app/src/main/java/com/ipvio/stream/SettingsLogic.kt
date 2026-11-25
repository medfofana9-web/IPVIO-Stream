package com.ipvio.stream

import java.util.Calendar
import java.util.TimeZone

/** Plan for the automatic playlist refresh. */
data class UpdatePlan(
    /** 0 = at every start, 1 = every X hours, 2 = daily, 3 = weekly, 4 = by hand only */
    val mode: Int,
    val intervalH: Int = 12,
    val hour: Int = 4,
    val minute: Int = 0,
    /** Weekday as in Calendar: 1 = Sunday … 7 = Saturday */
    val dow: Int = Calendar.MONDAY
)

/** Pure helper functions of the settings (without Android, hence testable). */
object SettingsLogic {

    /** Menu numbers: 0 Start, 1 Movies, 2 Series, 3 Live TV, 4 Search. If the chosen start page is hidden, the app starts on "Start". */
    fun resolveStartTab(pref: Int, order: List<Int>, hidden: List<Int>): Int =
        if (pref in 1..4 && pref in order && pref !in hidden) pref else 0

    /** Last scheduled time (≤ now) for daily/weekly. */
    fun lastScheduled(plan: UpdatePlan, nowMs: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = nowMs
        c.set(Calendar.HOUR_OF_DAY, plan.hour.coerceIn(0, 23))
        c.set(Calendar.MINUTE, plan.minute.coerceIn(0, 59))
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (plan.mode == 3) {
            val diff = (c.get(Calendar.DAY_OF_WEEK) - plan.dow + 7) % 7
            c.add(Calendar.DAY_OF_MONTH, -diff)
        }
        val step = if (plan.mode == 3) 7 else 1
        while (c.timeInMillis > nowMs) c.add(Calendar.DAY_OF_MONTH, -step)
        return c.timeInMillis
    }

    /** "At every start": a new start within this time of the last successful download keeps the saved playlist. */
    const val START_MIN_GAP_MS = 4 * 3_600_000L
    /** After a failed automatic download (provider down, new Wi-Fi without internet) the next automatic try waits this long. */
    const val FAIL_BACKOFF_MS = 30 * 60_000L

    /**
     * Refresh at app start? Like [refreshDue], but "at every start" means at most every [START_MIN_GAP_MS] (the Fire TV
     * restarts the app often; every start downloaded and re-parsed the whole playlist, slowing navigation for a minute),
     * and a failed attempt is not repeated before [FAIL_BACKOFF_MS] ([lastFailMs] = last failed try, 0 = none).
     */
    fun startRefreshDue(plan: UpdatePlan, lastMs: Long, lastFailMs: Long, nowMs: Long, zone: TimeZone): Boolean {
        if (lastFailMs in 1..nowMs && nowMs - lastFailMs < FAIL_BACKOFF_MS) return false
        if (plan.mode == 0) return lastMs <= 0L || nowMs < lastMs || nowMs - lastMs >= START_MIN_GAP_MS
        return refreshDue(plan, lastMs, nowMs, zone)
    }

    /** Is a refresh due now according to the plan? [lastMs] = last successful refresh (0 = never). */
    fun refreshDue(plan: UpdatePlan, lastMs: Long, nowMs: Long, zone: TimeZone): Boolean = when (plan.mode) {
        0 -> true
        1 -> lastMs <= 0L || nowMs < lastMs || nowMs - lastMs >= plan.intervalH.coerceAtLeast(1) * 3_600_000L
        2, 3 -> lastMs < lastScheduled(plan, nowMs, zone)
        else -> false
    }

    /** Next scheduled time (null for "at every start" and "by hand only"). */
    fun nextDue(plan: UpdatePlan, lastMs: Long, nowMs: Long, zone: TimeZone): Long? = when (plan.mode) {
        1 -> if (lastMs <= 0L) nowMs else lastMs + plan.intervalH.coerceAtLeast(1) * 3_600_000L
        2, 3 -> {
            val c = Calendar.getInstance(zone)
            c.timeInMillis = lastScheduled(plan, nowMs, zone)
            c.add(Calendar.DAY_OF_MONTH, if (plan.mode == 3) 7 else 1)
            c.timeInMillis
        }
        else -> null
    }

    fun fmtBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> String.format(java.util.Locale.GERMANY, "%.0f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.GERMANY, "%.1f MB", b / 1048576.0)
        else -> String.format(java.util.Locale.GERMANY, "%.2f GB", b / 1073741824.0)
    }

    // ---- Time ----
    class Zone(val id: String, val label: String)

    /** Choice: first entry = system time of the device. */
    val ZONES = listOf(
        Zone("", ""),
        Zone("Europe/Berlin", "Berlin · Wien · Zürich · Zagreb · Belgrad"),
        Zone("Europe/London", "London · Dublin"),
        Zone("Europe/Lisbon", "Lissabon"),
        Zone("Europe/Athens", "Athen · Helsinki · Bukarest"),
        Zone("Europe/Istanbul", "Istanbul · Ankara"),
        Zone("Europe/Moscow", "Moskau"),
        Zone("Asia/Dubai", "Dubai"),
        Zone("Asia/Kolkata", "Indien"),
        Zone("Asia/Tokyo", "Tokio · Seoul"),
        Zone("Australia/Sydney", "Sydney"),
        Zone("America/Sao_Paulo", "Brasília"),
        Zone("America/New_York", "New York · Toronto"),
        Zone("America/Chicago", "Chicago · Mexiko-Stadt"),
        Zone("America/Los_Angeles", "Los Angeles · Vancouver"),
        Zone("UTC", "UTC")
    )

    fun zoneOf(id: String): TimeZone =
        if (id.isBlank()) TimeZone.getDefault()
        else TimeZone.getTimeZone(id).takeIf { it.id == id } ?: TimeZone.getDefault()

    /** Time pattern: mode 0 = system, 1 = 24 hours, 2 = 12 hours. */
    fun timePattern(mode: Int, system24: Boolean): String =
        if (mode == 1 || (mode == 0 && system24)) "HH:mm" else "h:mm a"

    fun isMidnight(sec: Long, zone: TimeZone): Boolean {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = sec * 1000
        return c.get(Calendar.HOUR_OF_DAY) == 0 && c.get(Calendar.MINUTE) == 0
    }
}
