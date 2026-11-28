package com.ipvio.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Control button (player redesign, 27 Nov 2026): a plain white icon at rest (no circle, no border), a white shape with a
 * dark icon when focused (easily recognizable from 3 m). [square]: rounded square instead of a circle (the option buttons
 * on the right, so "watching" and "options" read as two groups). [showLabel]: the focused button's name appears right
 * under it, in a slot of fixed size, so the row never moves.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PRoundButton(
    modifier: Modifier = Modifier,
    size: Dp = PT.BtnSize,
    enabled: Boolean = true,
    label: String = "",
    onLabel: (String) -> Unit = {},
    square: Boolean = false,
    showLabel: Boolean = false,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.06f) else 1f, animationSpec = tween(145), label = "pbtn")
    val shape = if (square) RoundedCornerShape(size * 0.28f) else CircleShape
    val button: @Composable () -> Unit = {
        Box(
            modifier
                .focusProperties { canFocus = enabled }
                .graphicsLayer { scaleX = sc; scaleY = sc }
                .size(size)
                .clip(shape)
                .background(if (f) Color.White else Color.Transparent)
                .onFocusChanged { f = it.isFocused; if (it.isFocused) onLabel(label) }
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            content(if (!enabled) Color(0x59FFFFFF) else if (f) PT.Ink else Color(0xE6FFFFFF))
        }
    }
    if (!showLabel) button()
    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
        button()
        Box(Modifier.width(size).height(PT.LabelSlot).padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
            if (f && label.isNotEmpty()) Text(
                label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                modifier = Modifier.wrapContentWidth(unbounded = true)
            )
        }
    }
}

/** Small info badge, e.g. "4K", "HEVC", "Staffel 2". */
@Composable
fun PChip(text: String, modifier: Modifier = Modifier, filled: Boolean = false, icon: AIcon? = null, raw: Boolean = false) {
    Box(
        modifier.height(28.dp).clip(RoundedCornerShape(DS.RadiusXs))
            .background(if (filled) Color.White else FofVisual.GlassDark)
            .border(1.dp, if (filled) Color.White else FofVisual.GlassBorder, RoundedCornerShape(DS.RadiusXs))
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        IconLabel(icon, text, if (filled) PT.Ink else Color.White, 14.sp, FontWeight.Bold, iconSize = 15.dp, raw = raw)
    }
}

@Composable
fun PLiveChip(modifier: Modifier = Modifier, behindSec: Long = 0L) {
    val behind = behindSec >= 3
    Box(
        modifier.height(26.dp).clip(RoundedCornerShape(DS.RadiusXs)).background(if (behind) Color(0xFF555B6E) else PT.Live).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (behind) "LIVE  −" + (behindSec / 60) + ":" + (behindSec % 60).toString().padStart(2, '0') else "LIVE",
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, maxLines = 1
        )
    }
}

/** Subtle skip button (intro, recap, credits → next episode): small, translucent, in the style of the app. */
@Composable
fun PSkipPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.05f) else 1f, animationSpec = tween(120), label = "skip")
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .height(38.dp).clip(shape)
            .then(if (f) Modifier.background(DS.FocusFill) else Modifier.background(FofGlass.body(0, false)).background(FofGlass.sheen(0, false)).border(1.dp, FofGlass.edge(false), shape))
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlayerIcon(PIcon.NextEp, if (f) DS.FocusInk else Color.White, size = 16.dp)
        Text(if (f && !text.endsWith("(OK)")) "$text  (OK)" else text, color = if (f) DS.FocusInk else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * Draws the timeline. All values arrive as lambdas and are read only when DRAWING –
 * so on every position tick it only has to draw, not recompose.
 */
@Composable
fun PSeekTrack(
    modifier: Modifier,
    pos: () -> Long, dur: () -> Long, buffered: () -> Long, segs: () -> List<Seg>,
    focused: Boolean
) {
    val fp by animateFloatAsState(if (focused) 1f else 0f, animationSpec = tween(120), label = "seekfocus")
    Canvas(modifier) {
        val d = dur().coerceAtLeast(1L).toFloat()
        val w = size.width
        val cy = size.height / 2f
        val th = (6f + 4f * fp).dp.toPx()
        val top = cy - th / 2f
        val rad = CornerRadius(th / 2f)
        drawRoundRect(PT.Track, Offset(0f, top), Size(w, th), rad)
        val bw = (buffered().toFloat() / d).coerceIn(0f, 1f) * w
        if (bw > 0f) drawRoundRect(PT.Buffered, Offset(0f, top), Size(bw, th), rad)
        val frac = (pos().toFloat() / d).coerceIn(0f, 1f)
        val pw = frac * w
        // Clean bar (Apple TV style): the played part in the accent, no glow around it
        if (pw > 0f) drawRoundRect(FofVisual.Violet, Offset(0f, top), Size(pw, th), rad)
        for (s in segs()) {
            val x1 = (s.start.toFloat() / d).coerceIn(0f, 1f) * w
            val x2 = (s.end.toFloat() / d).coerceIn(0f, 1f) * w
            if (x2 - x1 > 1f) drawRoundRect(Color(0xFFFFC266).copy(alpha = 0.8f), Offset(x1, top), Size(x2 - x1, th), rad)
        }
        val tr = 7f.dp.toPx() + 4f.dp.toPx() * fp
        val cx = pw.coerceIn(tr, (w - tr).coerceAtLeast(tr))
        if (fp > 0.01f) drawCircle(FofVisual.VioletDeep, radius = tr, center = Offset(cx, cy), alpha = fp)
        drawCircle(Color.White, radius = tr - 3f.dp.toPx() * fp, center = Offset(cx, cy))
    }
}

/** Time bubble above the slider (follows the position). */
@Composable
private fun PTimeBubble(pos: () -> Long, dur: () -> Long) {
    val shape = RoundedCornerShape(DS.RadiusXs)
    Box(
        Modifier
            .layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                val barW = c.maxWidth
                val d = dur().coerceAtLeast(1L)
                val cx = pos().toFloat() / d * barW
                val x = (cx - p.width / 2f).toInt().coerceIn(0, (barW - p.width).coerceAtLeast(0))
                layout(p.width, p.height) { p.place(x, -(p.height + 6.dp.roundToPx())) }
            }
            .glass(shape, 2)
            .height(28.dp).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(fmtTime(pos()), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, style = TNUM, maxLines = 1)
    }
}

/** Focusable timeline: Left/Right jump, holding jumps further, Down goes to the buttons. */
@Composable
fun PSeekBar(
    modifier: Modifier,
    pos: () -> Long, dur: () -> Long, buffered: () -> Long, segs: () -> List<Seg>,
    scrubbing: () -> Boolean,
    onFocus: (Boolean) -> Unit,
    onDown: () -> Unit, onSeekKey: (Int) -> Unit, onRepeat: (Int) -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val active = f || scrubbing()
    Box(
        modifier.height(32.dp)
            .onFocusChanged { f = it.isFocused; onFocus(it.isFocused) }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.DirectionLeft -> { if (e.nativeKeyEvent.repeatCount > 0) onRepeat(-1) else onSeekKey(-1); true }
                    Key.DirectionRight -> { if (e.nativeKeyEvent.repeatCount > 0) onRepeat(1) else onSeekKey(1); true }
                    Key.DirectionDown -> { onDown(); true }
                    else -> false
                }
            }
            .focusable()
    ) {
        PSeekTrack(Modifier.fillMaxSize(), pos, dur, buffered, segs, active)
        if (active) PTimeBubble(pos, dur)
    }
}

/** Non-focusable timeline for the seeking moment when the control bar is hidden. */
@Composable
fun PMiniSeek(
    pos: () -> Long, dur: () -> Long, buffered: () -> Long, segs: () -> List<Seg>,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(32.dp)) {
            PSeekTrack(Modifier.fillMaxSize(), pos, dur, buffered, segs, true)
            PTimeBubble(pos, dur)
        }
        PTimeRow(pos, dur)
    }
}

/**
 * Row under the timeline: elapsed time on the left, remaining time on the right; with [speed] also when it ends
 * ("−51:14 · Ends at 4:34 PM"), so the bar needs no extra line for it.
 */
@Composable
fun PTimeRow(pos: () -> Long, dur: () -> Long, speed: (() -> Float)? = null) {
    Row(
        Modifier.fillMaxWidth().height(22.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(fmtTime(pos()), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, style = TNUM, maxLines = 1)
        val remain = (dur() - pos()).coerceAtLeast(0L)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("−" + fmtTime(remain), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, style = TNUM, maxLines = 1)
            if (speed != null) Text(
                "  ·  " + Lang.tr("Endet um") + " " + hhmm(endClockSec(System.currentTimeMillis(), remain, speed())),
                color = PT.T2, fontSize = 16.sp, style = TNUM, maxLines = 1, raw = true
            )
        }
    }
}

/** The clock itself (size and opacity from the settings). */
@Composable
private fun ClockText(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(10_000) } }
    val sp = when (AppSettings.clockSize) { 0 -> 16f; 2 -> 30f; else -> 22f }
    Text(
        hhmm(now / 1000), color = Color.White.copy(alpha = AppSettings.clockOpacity / 100f), fontSize = sp.sp, fontWeight = FontWeight.SemiBold, style = TNUM, maxLines = 1,
        modifier = modifier
            .then(if (AppSettings.clockBg) Modifier.background(Color.Black.copy(alpha = AppSettings.clockBgOpacity / 100f), RoundedCornerShape(10.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Clock on screen: fully in the chosen corner (narrow margin). */
@Composable
fun BoxScope.PClock() {
    val al = when (AppSettings.clockPos) { 0 -> Alignment.TopEnd; 1 -> Alignment.TopStart; 2 -> Alignment.BottomEnd; else -> Alignment.BottomStart }
    ClockText(Modifier.align(al).padding(12.dp))
}

/** Preview of the clock in the settings: only the clock in real size and opacity in front of a picture background (without corner pattern). */
@Composable
fun ClockSample(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.width(220.dp).height(84.dp).clip(shape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3A5A8C), Color(0xFFB07A3C), Color(0xFF1E3A2F))))
            .border(1.dp, Color(0x33FFFFFF), shape),
        contentAlignment = Alignment.Center
    ) { ClockText() }
}

// ---------------------------------------------------------------------------------------------
// Menus (top right)
// ---------------------------------------------------------------------------------------------

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.PPanelFrame(
    width: Dp, title: String, hint: String,
    modifier: Modifier = Modifier,
    onEdgeDown: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val maxH = (LocalConfiguration.current.screenHeightDp - 64).coerceAtLeast(240).dp
    val shape = RoundedCornerShape(PT.PanelRadius)
    Column(
        Modifier.align(Alignment.TopEnd).padding(top = PT.MarginY, end = PT.MarginY)
            .width(width).heightIn(max = maxH)
            // One look for every player panel: an opaque dark surface with a hairline edge (no glass)
            .clip(shape).background(PT.PanelBg).border(1.dp, Color(0x1AFFFFFF), shape)
            // Down at the end of the list: close the menu, the focus goes back to the icon in the control bar.
            // "exit" is called only when the focus would leave the menu downward (i.e. nothing below in the menu).
            .focusProperties {
                exitTo { dir ->
                    if (dir == FocusDirection.Down && onEdgeDown != null) { onEdgeDown(); FocusRequester.Cancel }
                    else FocusRequester.Default
                }
            }
            .focusGroup()
            .then(modifier)
            .padding(24.dp)
    ) {
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(hint, color = PT.Hint, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        content()
    }
}

/** Menu with standard keys: Left closes, Right stays in the menu. */
@Composable
fun BoxScope.PSimplePanel(
    width: Dp, title: String, hint: String, onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    PPanelFrame(
        width, title, hint,
        Modifier
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onClose(); true } else false }
            // Swallow Right only after the rows: rows with an arrow open their submenu with it, otherwise the focus stays in the menu
            .onKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight },
        onEdgeDown = onClose,
        content = content
    )
}

/** A menu row: 52 dp high, 17 sp. Chosen = bold + turquoise check mark, focus = white with dark text. */
@Composable
fun PPanelRow(
    text: String, selected: Boolean, modifier: Modifier = Modifier,
    trailing: String? = null, chevron: Boolean = false, tinted: Boolean = false,
    trailingIcon: AIcon? = null, raw: Boolean = false, onClick: () -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().height(PT.RowHeight).clip(shape)
            .background(if (f) Color.White else if (tinted) DS.Overlay1 else Color.Transparent)
            .onFocusChanged { f = it.isFocused }
            .then(if (chevron || trailing != null) Modifier.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onClick(); true } else false
            } else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text, color = if (f) PT.Ink else if (selected) Color.White else PT.RowText, fontSize = 17.sp,
            fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), raw = raw
        )
        if (trailing != null) {
            Text(trailing, color = if (f) PT.InkSoft else PT.T2, fontSize = 17.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
        }
        if (trailingIcon != null) AppIcon(trailingIcon, if (f) PT.InkSoft else PT.Gold, Modifier.padding(start = 12.dp), 18.dp)
        if (chevron) {
            PlayerIcon(PIcon.Chevron, if (f) PT.Ink else PT.T2, Modifier.padding(start = 8.dp), 20.dp)
        } else if (selected) {
            PlayerIcon(PIcon.Check, if (f) PT.Ink else FofVisual.Violet, Modifier.padding(start = 8.dp), 20.dp)
        }
    }
}

/** Column heading in the menu (e.g. "Audio", "Subtitles"). */
@Composable
fun PColumnHeader(text: String) {
    Text(
        text, color = PT.T2, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
    )
}
