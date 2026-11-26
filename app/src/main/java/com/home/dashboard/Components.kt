@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.home.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api

// ---------- Look & feel ----------

val Inter = FontFamily(
    Font(R.font.inter_extralight, FontWeight.ExtraLight),
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

// Five tiles + four gaps exactly fill the 960dp screen minus 48dp either side.
internal val TileW = 152.dp
internal val TileH = 86.dp
internal val RowInset = 48.dp
internal val TileShape = RoundedCornerShape(14.dp)
internal val CardH = 176.dp
internal val CardShape = RoundedCornerShape(24.dp)
internal val IosRed = Color(0xFFFF453A)


@Composable
fun Txt(
    text: String,
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    color: Color = Color.White,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    shadow: Boolean = true,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color.copy(alpha = color.alpha * alpha),
            fontSize = size,
            fontWeight = weight,
            fontFamily = Inter,
            letterSpacing = letterSpacing,
            lineHeight = lineHeight,
            shadow = if (shadow) Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 0f) else null,
        ),
    )
}

fun weatherIcon(code: Int, isDay: Boolean): ImageVector = when (code) {
    0, 1 -> if (isDay) Icons.Filled.WbSunny else Icons.Filled.NightsStay
    2 -> Icons.Filled.WbCloudy
    3, 45, 48 -> Icons.Filled.Cloud
    51, 53, 55, 56, 57 -> Icons.Filled.Grain
    71, 73, 75, 77, 85, 86 -> Icons.Filled.AcUnit
    95, 96, 99 -> Icons.Filled.FlashOn
    else -> Icons.Filled.Umbrella
}

/** iOS Weather-style backdrop: blue for clear days, grey for cloud, navy at night. */
fun weatherBrush(code: Int, isDay: Boolean): Brush {
    val (top, bottom) = when {
        !isDay && code <= 1 -> 0xFF0B1A3A to 0xFF26365E
        !isDay -> 0xFF1C2333 to 0xFF3B4559
        code <= 1 -> 0xFF2D7FD3 to 0xFF62AEEB
        code == 2 -> 0xFF4A7FB5 to 0xFF8AA9C6
        code == 3 || code == 45 || code == 48 -> 0xFF5E6B7A to 0xFF8C97A3
        code >= 95 -> 0xFF2E3440 to 0xFF4C566A
        else -> 0xFF3F4C5C to 0xFF67768A
    }
    return Brush.verticalGradient(listOf(Color(top), Color(bottom)))
}

@Composable
fun WIcon(code: Int, isDay: Boolean, size: Dp) =
    Image(weatherIcon(code, isDay), null, Modifier.size(size), colorFilter = ColorFilter.tint(Color.White))
