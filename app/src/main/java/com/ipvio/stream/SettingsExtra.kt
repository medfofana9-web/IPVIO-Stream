package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Settings sections from round 10 (own file so SettingsHub.kt does not keep growing).
 * Uses the building blocks from SettingsHub.kt (SelectRow, ToggleRow, Hint, SectionTitle, ConfirmPill).
 */

/** Sound & subtitles: first and second audio language, when subtitles appear, language, CC. */
@Composable
fun TrackSettingsSection(ctx: Context) {
    val ai = Langs.AUDIO.indexOfFirst { it.first == AppSettings.audioLang }.coerceAtLeast(0)
    val a2 = Langs.AUDIO2.indexOfFirst { it.first == AppSettings.audioLang2 }.coerceAtLeast(0)
    val si = Langs.SUBS.indexOfFirst { it.first == AppSettings.subLang }.coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SelectRow("Audio language", Langs.AUDIO[ai].second, Langs.AUDIO.map { it.second }, ai) {
            AppSettings.setLangs(ctx, Langs.AUDIO[it].first, AppSettings.subLang)
        }
        if (AppSettings.audioLang.isNotEmpty()) SelectRow("Secondary audio language", Langs.AUDIO2[a2].second, Langs.AUDIO2.map { it.second }, a2) {
            AppSettings.setTrackExtra(ctx, Langs.AUDIO2[it].first, AppSettings.subMode, AppSettings.ccPref)
        }
        SelectRow("Untertitel", Langs.SUB_MODES[AppSettings.subMode], Langs.SUB_MODES, AppSettings.subMode) {
            AppSettings.setTrackExtra(ctx, AppSettings.audioLang2, it, AppSettings.ccPref)
        }
        if (AppSettings.subMode != TrackLogic.SUB_OFF) {
            SelectRow("Subtitle language", Langs.SUBS[si].second, Langs.SUBS.map { it.second }, si) {
                AppSettings.setLangs(ctx, AppSettings.audioLang, Langs.SUBS[it].first)
            }
            ToggleRow(
                "Closed captions (CC)",
                "Prefer captions with sound descriptions and speaker labels when available.",
                AppSettings.ccPref
            ) { AppSettings.setTrackExtra(ctx, AppSettings.audioLang2, AppSettings.subMode, it) }
        }
    }
    Hint(
        "“Only for foreign audio”: If a title plays in a language you have not selected (such as Japanese), " +
            "subtitles appear automatically. They stay off for your selected audio language. If you select tracks in the player, " +
            "FOF TV remembers your choice for that movie or series, including its next episode."
    )
}

/** Player: which source the app takes when there are several qualities, and falling back on stutter/errors. */
@Composable
fun SourceSettingsSection(ctx: Context) {
    PlaybackPrefs.init(ctx)
    DeviceCaps.init(ctx)
    SectionTitle("Sources & Quality")
    val opts = listOf("Automatic (best for this device)", "Always highest quality", "Up to Full HD", "Up to HD (saves data)")
    SelectRow("When multiple sources exist", opts[PlaybackPrefs.quality], opts, PlaybackPrefs.quality) { PlaybackPrefs.setQuality(ctx, it) }
    ToggleRow(
        "Switch sources on failure",
        "If a stream repeatedly buffers or fails, FOF TV tries another source for the same title and resumes at the same position.",
        PlaybackPrefs.autoFallback
    ) { PlaybackPrefs.setFallback(ctx, it) }
    Text("This device: " + DeviceCaps.summary(), color = Muted, fontSize = 15.sp)
    if (PlaybackPrefs.learnedCap in 1..3) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Learned preference: 4K buffered repeatedly on this device, so automatic selection uses up to Full HD.", color = DS.Gold, fontSize = 15.sp)
        SmallPill("Reset") { PlaybackPrefs.resetLearned(ctx) }
    }
    Hint(
        "Some providers offer the same movie in multiple qualities (4K, Full HD, HD). Automatic chooses the highest quality your device can handle: " +
            "4K requires a compatible display and decoder. A source you choose manually always takes priority."
    )
}

/** Player: links that no longer worked during playback. */
@Composable
fun DeadLinksSection(ctx: Context) {
    DeadLinks.init(ctx)
    SectionTitle("Broken sources")
    Text(
        if (DeadLinks.count == 0) "No broken sources recorded." else "${DeadLinks.count} sources marked as failing",
        color = if (DeadLinks.count == 0) Muted else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
    )
    ToggleRow(
        "Hide failing titles",
        "Hides movies or channels that repeatedly fail or report they are unavailable. Otherwise, they display a warning icon.",
        DeadLinks.hide
    ) { DeadLinks.setHide(ctx, it) }
    if (DeadLinks.count > 0) ConfirmPill("Clear list") { DeadLinks.clear(ctx) }
    Hint(
        "FOF TV does not proactively check thousands of provider links. It only remembers " +
            "failures that happen during playback. Connectivity, authorization, or server issues are not counted as broken content. " +
            "A working title is immediately restored."
    )
}

/** Live TV: own channel lists (overview, operation). */
@Composable
fun ChannelListsSection(ctx: Context) {
    ChannelLists.init(ctx)
    SectionTitle("Custom channel lists")
    val ls = ChannelLists.lists
    Text(if (ls.isEmpty()) "No custom channel lists yet." else ls.joinToString("  ·  ") { it.name + " (" + it.ids.size + ")" }, color = if (ls.isEmpty()) Muted else Color.White, fontSize = 16.sp, raw = ls.isNotEmpty())
    Hint(
        "To create a list, open Live TV > New channel list, or long-press OK on a channel and choose New channel list. " +
            "Long-press OK on a channel to favorite it, add or remove it from a list, or change its position. " +
            "Long-press OK on a list to rename or delete it. The remote's Menu key also works, when available. " +
            "Use Left in the player to switch between your lists; channel switching stays within the active list."
    )
}

/** Live TV: reminders for shows (lead time, upcoming reminders). */
@Composable
fun ReminderSettingsSection(ctx: Context) {
    Reminders.init(ctx)
    SectionTitle("Reminders")
    val leads = ReminderLogic.LEADS
    val li = leads.indexOf(Reminders.leadMin).coerceAtLeast(0)
    SelectRow("Remind me", if (Reminders.leadMin == 0) "At start" else "${Reminders.leadMin} min before",
        leads.map { if (it == 0) "At start" else "$it min before" }, li) { Reminders.setLead(ctx, leads[it]) }
    val list = Reminders.list.toList()
    if (list.isEmpty()) Text("No reminders set.", color = Muted, fontSize = 16.sp)
    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(raw = true, text = dayLabel(r.start) + " " + hhmm(r.start) + "  " + r.title + "  ·  " + r.chTitle, color = Color.White, fontSize = 16.sp, maxLines = 1)
                SmallPill("Delete") { Reminders.toggle(ctx, r) }
            }
        }
    }
    Hint(
        "In the TV guide, select a future program and choose Remind me. While FOF TV is open, a card offers Switch channel, " +
            "even while watching something else. When the app is closed, tapping a notification opens the channel. " +
            "The device must remain powered on; some devices show alerts only in their notification center."
    )
}

/** Live TV: archive (catchup) – how many channels offer it and how to use it. */
@Composable
fun CatchupSection() {
    SectionTitle("Catch-up TV")
    val n = Catchup.count
    IconLabel(
        AIcon.Catchup,
        if (n == 0) "Your playlist contains no channels with catch-up TV." else "$n channels with catch-up",
        if (n == 0) Muted else Color.White, 16.sp, FontWeight.SemiBold, iconSize = 18.dp, iconTint = if (n == 0) Muted else Accent2
    )
    Hint(
        "Catch-up channels display an archive icon. In the TV guide, press OK on a past program to watch it. " +
            "Use Rewind/Fast-forward to move one day. In the player, Start from beginning restarts the current program; Catch-up lists " +
            "previous programs. Playback proceeds to the next program when one ends, and Back returns to live TV. " +
            "Catch-up availability and retention are controlled by your provider."
    )
}
