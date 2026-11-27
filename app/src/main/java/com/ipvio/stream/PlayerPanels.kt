package com.ipvio.stream

import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride

/** Simple selection list for menus (speed, aspect ratio, quality ...). */
@Composable
fun PChoiceList(options: List<String>, selected: Int, focus: FocusRequester, onPick: (Int) -> Unit) {
    val st = rememberLazyListState(maxOf(selected - 3, 0))
    LazyColumn(state = st, verticalArrangement = Arrangement.spacedBy(PT.RowGap)) {
        itemsIndexed(options) { i, o ->
            PPanelRow(o, i == selected, if (i == maxOf(selected, 0)) Modifier.focusRequester(focus) else Modifier) { onPick(i) }
        }
    }
}

/**
 * Audio & Subtitles (design A, 27 Nov 2026): a panel along the right edge, full height, opaque, never wider than the
 * screen allows. Audio on top, subtitles below with plain names ("English · best match", "English · version 2"), then
 * "More languages". Timing (Left earlier, Right later, right on the row), "Sync to a line" and the subtitle style sit
 * behind one "Adjust subtitles" row (player redesign, 27 Nov 2026), so the list stays short; Left goes back from there.
 * The player's control bar is hidden while it is open, so nothing shows through; the subtitles stay visible on the
 * picture. Up/Down move, OK selects, Back closes.
 */
@Composable
fun BoxScope.TracksPanel(
    player: Player, ver: Int, onChanged: () -> Unit,
    audioFocus: FocusRequester, subsFocus: FocusRequester,
    onClose: () -> Unit, onStyle: () -> Unit, onOnline: (() -> Unit)? = null,
    onEnglish: (() -> Unit)? = null,
    timingMs: Long? = null, onTimingStep: (Long) -> Unit = {}, onSyncLine: (() -> Unit)? = null,
    styleLabel: String = "", syncInfo: String? = null
) {
    val audio = remember(ver) { collectTracks(player, C.TRACK_TYPE_AUDIO) }
    val subs = remember(ver) { collectTracks(player, C.TRACK_TYPE_TEXT) }
    val subsOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    val listState = rememberLazyListState(0)
    val panelW = minOf(420, (LocalConfiguration.current.screenWidthDp * 0.44f).toInt()).dp
    var adjust by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val adjustRow = remember { FocusRequester() }
    val adjustFirst = remember { FocusRequester() }
    // Into "Adjust subtitles": its first row; back out: the row that opened it
    val adjustSeen = remember { booleanArrayOf(false) }
    LaunchedEffect(adjust) {
        // Not on opening the panel (the effect below picks the track in use then)
        if (!adjustSeen[0]) { adjustSeen[0] = true; return@LaunchedEffect }
        delay(60)
        runCatching { (if (adjust) adjustFirst else adjustRow).requestFocus() }
    }

    // Focus on the subtitle in use (or Off) when there is only one audio track, else on the audio in use
    LaunchedEffect(Unit) {
        delay(200)
        repeat(8) {
            if (runCatching { (if (audio.size > 1) audioFocus else subsFocus).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(70)
        }
    }
    Column(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(panelW)
            .background(Color(0xFF0E101A)).border(1.dp, Color(0x1AFFFFFF))
            // After the rows (not before): the Timing row uses Left/Right itself; elsewhere Left closes the panel
            // (from "Adjust subtitles": back to the list)
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { if (adjust) adjust = false else onClose(); true } else false }
            .focusGroup()
            .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 20.dp)
    ) {
        if (adjust) {
            Text("Adjust subtitles", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text("Left = Back", color = PT.Hint, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (timingMs != null) {
                    // Timing right on the row: Left earlier, Right later (0.5 s a press); OK opens "Sync to a line"
                    PPanelRow(
                        "Timing   " + timingText(timingMs), false,
                        Modifier.focusRequester(adjustFirst).onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) false
                            else when (e.key) {
                                Key.DirectionLeft -> { onTimingStep(-500); true }
                                Key.DirectionRight -> { onTimingStep(500); true }
                                else -> false
                            }
                        },
                        trailing = "◀ earlier · later ▶"
                    ) { onSyncLine?.invoke() }
                    if (onSyncLine != null) PPanelRow("Sync to a line", false, trailing = "press OK on the voice", chevron = true, onClick = onSyncLine)
                }
                PPanelRow("Subtitle style", false, if (timingMs == null) Modifier.focusRequester(adjustFirst) else Modifier,
                    trailing = styleLabel.ifEmpty { null }, chevron = true, onClick = onStyle)
            }
        } else {
            Text("Audio & Subtitles", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text("OK selects · Back closes", color = PT.Hint, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { PSectionLabel("AUDIO") }
                if (audio.isEmpty()) item {
                    Text("No audio choice in this stream", color = Muted, fontSize = 15.sp, modifier = Modifier.focusRequester(audioFocus).focusable().padding(12.dp))
                }
                itemsIndexed(audio) { i, t ->
                    val sel = t.selected
                    PPanelRow(t.label, sel, if (sel || (i == 0 && audio.none { it.selected })) Modifier.focusRequester(audioFocus) else Modifier,
                        trailing = if (sel) "Playing" else null) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.idx)).build()
                        onChanged()
                    }
                }
                item { Spacer(Modifier.height(10.dp)); PSectionLabel("SUBTITLES") }
                // How the online subtitles were timed: made for this file, synced to the voices, still checking …
                if (syncInfo != null) item {
                    Text((if (syncInfo.startsWith("In sync") || syncInfo.startsWith("Made for") || syncInfo.startsWith("Synced")) "\u2713 " else "") + syncInfo,
                        color = if (syncInfo.startsWith("Couldn")) Color(0xFFFFB4A8) else Color(0xFF9BE7B0), fontSize = 13.sp, maxLines = 2,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                }
                item {
                    PPanelRow("Off", subsOff, if (subsOff || subs.none { it.selected }) Modifier.focusRequester(subsFocus) else Modifier) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        onChanged()
                    }
                }
                itemsIndexed(subs) { _, t ->
                    val on = t.selected && !subsOff
                    PPanelRow(t.label, on, if (on) Modifier.focusRequester(subsFocus) else Modifier, trailing = if (on) "On" else null) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.idx)).build()
                        onChanged()
                    }
                }
                // The stream has no English subtitles, but English ones from the internet are downloaded and ready
                if (onEnglish != null) item { PPanelRow("English", false, trailing = "from the internet", onClick = onEnglish) }
                if (onOnline != null) item { PPanelRow("More languages…", false, chevron = true, onClick = onOnline) }
                if (subs.isEmpty() && onEnglish == null && onOnline == null) item {
                    Text("This stream has no subtitles.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1AFFFFFF)))
            Spacer(Modifier.height(10.dp))
            // Timing, Sync to a line and the style: one row, opened only when needed
            PPanelRow("Adjust subtitles", false, Modifier.focusRequester(adjustRow),
                trailing = timingMs?.takeIf { it != 0L }?.let { timingText(it) } ?: styleLabel.ifEmpty { null }, chevron = true) { adjust = true }
        }
    }
}

/** "As loaded", "1.5 s later", "0.5 s earlier". */
fun timingText(ms: Long): String = when {
    ms == 0L -> "as loaded"
    ms > 0 -> String.format(java.util.Locale.US, "%.1f s later", ms / 1000.0)
    else -> String.format(java.util.Locale.US, "%.1f s earlier", -ms / 1000.0)
}

@Composable
private fun PSectionLabel(text: String) {
    Text(text, color = FofVisual.Secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, maxLines = 1,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp), raw = true)
}

/**
 * Episodes of the series as a strip along the bottom (player redesign, 27 Nov 2026): season pills on top, the season's
 * episodes as cards below, the picture stays visible above. The running episode is focused on opening. Up goes to the
 * seasons, Down closes, Back closes. Long seasons (One Piece & Co.): fast-forward / rewind jump 50 episodes.
 */
@Composable
fun BoxScope.EpisodesPanel(
    episodes: List<Episode>, curSeason: Int, curNum: Int,
    focus: FocusRequester, onClose: () -> Unit, seriesItem: Item? = null, onPick: (Episode) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sid = seriesItem?.id
    // Episode details shared with the title page; a catalog series opened straight from Continue watching loads them here once
    var infoVer by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(sid) {
        if (seriesItem != null && sid != null && !EpisodeInfo.has(sid) && sid.startsWith("stremio:")) { runCatching { LazySeries.load(ctx, seriesItem) }; infoVer++ }
    }
    val progress = remember(sid) { if (sid != null) EpProgress.of(ctx, sid).toMap() else emptyMap() }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct() }
    var season by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(if (curSeason in seasons) curSeason else seasons.firstOrNull() ?: curSeason) }
    val list = remember(episodes, season) { episodes.filter { it.season == season } }
    val curIdx = if (season == curSeason) list.indexOfFirst { it.num == curNum } else -1
    val focusIdx = if (curIdx >= 0) curIdx else 0
    val nextIdx = if (curIdx >= 0 && curIdx < list.lastIndex) curIdx + 1 else null
    val st = androidx.compose.runtime.key(season) { rememberLazyListState(maxOf(focusIdx - 1, 0)) }
    val reqs = remember(list) { HashMap<Int, FocusRequester>() }
    val scope = rememberCoroutineScope()
    val long = list.size > EpisodeLogic.LONG
    fun jumpTo(target: Int) {
        scope.launch {
            st.scrollToItem(target)
            withFrameNanos { }
            withFrameNanos { }
            runCatching { reqs.getOrPut(target) { FocusRequester() }.requestFocus() }
        }
    }
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, 0.22f to Color(0xE60A0B12), 1f to Color(0xFA0A0B12)))
            .focusGroup()
            .padding(start = PT.MarginX, top = 64.dp, bottom = PT.MarginY)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Episodes", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            if (seasons.size > 1) androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(end = PT.MarginX)
            ) {
                items(seasons.size) { k ->
                    val n = seasons[k]
                    FilterPill("Season $n", n == season, raw = true) { season = n }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        androidx.compose.foundation.lazy.LazyRow(
            state = st, horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(end = PT.MarginX),
            modifier = Modifier.onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.DirectionDown -> { onClose(); true }
                    else -> false
                }
            }
        ) {
            itemsIndexed(list, key = { _, e -> "e${e.season}_${e.num}" }) { i, ep ->
                val req = if (i == focusIdx) focus else reqs.getOrPut(i) { FocusRequester() }
                if (i == focusIdx) reqs[i] = focus
                val cardMod = Modifier.focusRequester(req).then(if (!long) Modifier else Modifier.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) {
                        Key.MediaFastForward, Key.ChannelDown, Key.PageDown -> { jumpTo(minOf(i + EpisodeLogic.BLOCK, list.lastIndex)); true }
                        Key.MediaRewind, Key.ChannelUp, Key.PageUp -> { jumpTo(maxOf(i - EpisodeLogic.BLOCK, 0)); true }
                        else -> false
                    }
                })
                val meta = if (sid != null) remember(sid, ep.season, ep.num, infoVer) { EpisodeInfo.get(sid, ep.season, ep.num) } else null
                PEpisodeCard(ep, meta, progress[ResumeLogic.key(ep.season, ep.num)], i == curIdx, i == nextIdx, cardMod) { onPick(ep) }
            }
        }
        Text(
            if (long) "Up: seasons · Fast-forward / rewind jumps 50 · OK plays · Back closes" else "Up: seasons · OK plays · Back closes",
            color = PT.Hint, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 14.dp)
        )
    }
}
