package com.ipvio.stream

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

@Composable
fun Modifier.favKeys(onFav: () -> Unit): Modifier {
    var longDone by remember { mutableStateOf(false) }
    var downAt by remember { mutableStateOf(0L) }
    return this.onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        when {
            e.key == Key.Menu && e.type == KeyEventType.KeyDown -> { onFav(); true }
            // Fire TV remotes send no repeat events while OK is held: measure the time instead (held 0.6 s = long press)
            ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && !e.nativeKeyEvent.isLongPress -> { downAt = System.currentTimeMillis(); false }
            ok && e.type == KeyEventType.KeyUp && !longDone && downAt > 0 && System.currentTimeMillis() - downAt >= 600 -> { downAt = 0; onFav(); true }
            // Long OK: trigger only on release. If the action opens a menu, that menu would otherwise receive the release
            // as a click on the first entry.
            ok && e.type == KeyEventType.KeyDown && (longDone || e.nativeKeyEvent.isLongPress) -> { longDone = true; true }
            ok && e.type == KeyEventType.KeyUp && longDone -> { longDone = false; downAt = 0; onFav(); true }
            else -> false
        }
    }
}

/**
 * Menu key opens a menu ([onMenu]), OK held long does [onLong] (on release).
 * Many remotes (Fire TV) have no menu key: then a long OK opens the same menu.
 */
@Composable
fun Modifier.menuKeys(onMenu: () -> Unit, onLong: () -> Unit): Modifier {
    var longDone by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { e ->
        when {
            e.key == Key.Menu && e.type == KeyEventType.KeyDown -> { onMenu(); true }
            e.key == Key.DirectionCenter && e.type == KeyEventType.KeyDown && (longDone || e.nativeKeyEvent.isLongPress) -> { longDone = true; true }
            e.key == Key.DirectionCenter && e.type == KeyEventType.KeyUp && longDone -> { longDone = false; onLong(); true }
            else -> false
        }
    }
}
