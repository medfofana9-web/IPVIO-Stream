package com.ipvio.stream

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Design values of the player. Same spacing and sizes everywhere so everything looks calm and even. */
object PT {
    // Colors (base colors Bg/Surf/Accent1/Accent2/Muted come from Ui.kt)
    val T2 = Color(0xFFB8BDCC)          // second text color
    val Live = Color(0xFFE60023)
    val Gold = DS.Gold
    val Ink = Color(0xFF0B0C12)         // dark text on white focus
    val InkSoft = Color(0xFF3C4256)
    val PanelBg = Color(0xF00E101A)     // panel surfaces (opaque enough that nothing shows through)
    val Hint = Color(0xFF8E95A8)        // key hints and section labels in panels
    val RowText = Color(0xFFDDE1EA)     // panel rows at rest (white is for the chosen one)
    val PanelBorder = DS.Overlay1
    val BtnBg = Color(0x24FFFFFF)       // round buttons at rest
    val Track = DS.Overlay3
    val Buffered = Color(0x45FFFFFF)
    val ScrimTop = Color(0xCC000000)
    val ScrimBottom = Color(0xE6000000)

    // Margins (overscan-safe)
    val MarginX = 56.dp
    val MarginY = 32.dp

    // Buttons
    val BtnSize = 52.dp
    val BtnPrimary = 64.dp
    val BtnGap = 16.dp
    val LabelSlot = 30.dp               // space under a control button for its name when focused

    // Menus
    val PanelRadius = 24.dp
    val RowHeight = 52.dp
    val RowGap = 4.dp
}

/** Equal-width digits so clock times do not "jitter". */
val TNUM = TextStyle(fontFeatureSettings = "tnum")
