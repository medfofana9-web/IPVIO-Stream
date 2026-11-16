package com.ipvio.stream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Quick actions (Nuvio style): hold OK on a movie or show (or right-click / long-press on touch) and a glass menu
 * opens beside the content with what fits that title: open, resume, start over, My List, favorite, watched,
 * remove from Continue watching. One menu for the whole app ([QuickMenuHost] at the root); cards only call
 * [quickActions]. Every action writes the profile's own stores, so it syncs like everything else of the profile.
 * Back closes the menu and focus returns to the card it came from.
 */
object QuickMenu {
    class Target(val item: Item, val entry: Entry?, val open: () -> Unit, val resume: ((Entry) -> Unit)?, val back: FocusRequester)
    var target by mutableStateOf<Target?>(null)

    class Action(val label: String, val icon: AIcon, val run: (Context) -> Unit)

    /** The actions that apply to this title right now (only ones that work). */
    fun actions(t: Target): List<Action> {
        val it = t.item
        val e = t.entry ?: WatchStore.entries.firstOrNull { w -> w.itemId == it.id }
        val out = ArrayList<Action>()
        val resume = t.resume
        if (e != null && resume != null) {
            out += Action(if (e.next) "Play next episode" else "Resume", AIcon.Play) { _ -> resume(e) }
            if (!e.next && e.pos > 0) out += Action("Start over", AIcon.History) { _ -> resume(e.copy(pos = 0L)) }
        }
        out += Action(if (it.kind == Kind.SERIES) "Seasons & episodes" else "View details", AIcon.Info) { _ -> t.open() }
        out += if (Library.isWatch(it.id)) Action("Remove from My List", AIcon.Check) { c -> Library.toggleWatch(c, it.id) }
               else Action("Add to My List", AIcon.Plus) { c -> Library.toggleWatch(c, it.id) }
        out += if (Library.isFav(it.id)) Action("Remove from favorites", AIcon.HeartFilled) { c -> Library.toggleFav(c, it.id) }
               else Action("Add to favorites", AIcon.Heart) { c -> Library.toggleFav(c, it.id) }
        if (it.kind == Kind.MOVIE) {
            val k = ResumeLogic.key(0, 0)
            out += Action("Mark as watched", AIcon.Check) { c -> EpProgress.mark(c, it.id, listOf(k), true); WatchStore.remove(c, it.id) }
            out += Action("Mark as unwatched", AIcon.Close) { c -> EpProgress.mark(c, it.id, listOf(k), false) }
        }
        if (e != null) out += Action("Remove from Continue watching", AIcon.Close) { c -> WatchStore.remove(c, it.id) }
        return out
    }
}

/**
 * Long press (OK held, Fire TV included), the Menu key, or a right-click opens the quick actions for [item].
 * [entry] / [onResume]: Continue watching cards resume or start over from the menu.
 */
@Composable
fun Modifier.quickActions(item: Item, onOpen: () -> Unit, entry: Entry? = null, onResume: ((Entry) -> Unit)? = null): Modifier {
    val back = remember { FocusRequester() }
    return this.focusRequester(back).favKeys { QuickMenu.target = QuickMenu.Target(item, entry, onOpen, onResume, back) }
}

@Composable
fun QuickMenuHost() {
    val t = QuickMenu.target
    val ctx = LocalContext.current
    AnimatedVisibility(t != null, enter = fadeIn(tween(140)) + slideInHorizontally(tween(160)) { it / 6 }, exit = fadeOut(tween(120))) {
        val tt = t ?: return@AnimatedVisibility
        // A one-shot list for this opening (an action runs once, then the menu closes)
        val acts = remember(tt) { QuickMenu.actions(tt) }
        var done by remember(tt) { mutableStateOf(false) }
        fun close() {
            QuickMenu.target = null
            runCatching { tt.back.requestFocus() }
        }
        BackHandler { close() }
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { close() }, contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.padding(end = 72.dp).width(420.dp).glass(RoundedCornerShape(DS.RadiusL), 2)
                    .clickable(enabled = false) {}.padding(vertical = 18.dp, horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(tt.item.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, raw = true, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
                val first = remember(tt) { FocusRequester() }
                LaunchedEffect(tt) { delay(60); runCatching { first.requestFocus() } }
                acts.forEachIndexed { i, a ->
                    QuickRow(a, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        if (done) return@QuickRow
                        done = true
                        close()
                        a.run(ctx)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickRow(a: QuickMenu.Action, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
    Row(
        modifier.fillMaxWidth().onFocusChanged { f = it.isFocused }
            .then(if (f) Modifier.background(Color.White, shape) else Modifier.border(1.dp, Color.Transparent, shape))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppIcon(a.icon, if (f) DS.FocusInk else Color(0xCCFFFFFF), size = 20.dp)
        Text(a.label, color = if (f) DS.FocusInk else Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, raw = true)
    }
}
