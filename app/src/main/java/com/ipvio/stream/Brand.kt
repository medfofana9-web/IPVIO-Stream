package com.ipvio.stream

import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding

/**
 * The Fof TV mark (official since 15 Nov 2026): a white-to-violet play ribbon on a deep indigo glass tile
 * (res/drawable/ic_brand_mark.png, drawn from docs/brand/mark.svg; same picture as the app icon). [FofLogo] sets the word mark "fof TV" next to it.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_brand_mark),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}

/** Mark plus word mark: "fof" bold, "TV" small and grey (Outfit, the app font). */
@Composable
fun FofLogo(markSize: androidx.compose.ui.unit.Dp, textSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        BrandMark(Modifier.size(markSize))
        androidx.compose.foundation.layout.Spacer(Modifier.width(markSize * 0.3f))
        Text("fof", color = androidx.compose.ui.graphics.Color.White, fontSize = textSize, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, raw = true)
        androidx.compose.foundation.layout.Spacer(Modifier.width(markSize * 0.12f))
        Text("TV", color = androidx.compose.ui.graphics.Color(0xFFB9B4F0), fontSize = textSize * 0.42f, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, raw = true,
            modifier = Modifier.padding(top = markSize * 0.12f))
    }
}
