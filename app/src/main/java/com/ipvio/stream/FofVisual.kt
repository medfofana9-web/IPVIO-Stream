package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared, TV-friendly editorial elements for Player, Sports, Details, Search and Profiles.
 * Solid/cached layers instead of realtime blurring make this safe for lower-memory TV sticks.
 */
internal object FofVisual {
    val Violet = Color(0xFFB9B4F0)
    val VioletDeep = Color(0xFF8C84DA)
    val Secondary = Color(0xFFC5C7D5)
    val GlassDark = Color(0xE6161723)
    val GlassBorder = Color(0x4DFFFFFF)
}

@Composable
internal fun FofEyebrow(section: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.background(FofVisual.GlassDark, shape)
            .border(1.dp, FofVisual.GlassBorder, shape)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 9.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(FofVisual.Violet, CircleShape))
        Text("FOF TV", raw = true, color = Color.White, fontSize = if (compact) 11.sp else 13.sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
        Box(Modifier.width(1.dp).height(13.dp).background(FofVisual.GlassBorder))
        Text(section.uppercase(), raw = true, color = FofVisual.Violet, fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
