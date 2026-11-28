@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.home.dashboard

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SettingsInputHdmi
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import coil.compose.AsyncImage
import java.io.File

/** Rows keep the focused tile at least [inset] from both screen edges, in both directions. */
private class RowScroll(private val inset: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val before = offset - inset
        val after = offset + size + inset - containerSize
        return when {
            before >= -1f && after <= 1f -> 0f
            before < 0f -> before
            else -> after
        }
    }
}

// ---------- Rows & tiles ----------

@Composable
fun TileRow(
    title: String, modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.padding(top = 2.dp)) {
        Txt(title, 16.sp, FontWeight.Bold, modifier = Modifier.padding(start = 52.dp))
        val inset = with(LocalDensity.current) { RowInset.toPx() }
        val rowScroll = remember(inset) { RowScroll(inset) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowScroll) {
            LazyRow(
                state = state,
                modifier = Modifier.fillMaxWidth().focusRestorer(),
                contentPadding = PaddingValues(horizontal = RowInset, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                content = content,
            )
        }
    }
}

/** The home-screen wobble while apps are being rearranged. */
@Composable
private fun Modifier.wobble(index: Int): Modifier {
    val angle = rememberInfiniteTransition(label = "wobble").animateFloat(
        initialValue = -1.6f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(130 + index % 3 * 15, easing = LinearEasing), RepeatMode.Reverse),
        label = "angle",
    )
    return graphicsLayer { rotationZ = if (index % 2 == 0) angle.value else -angle.value }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Tile(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null, jiggle: Int? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(TileW), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.size(TileW, TileH).onFocusChanged { focused = it.isFocused }
                .then(if (jiggle != null) Modifier.wobble(jiggle) else Modifier),
            shape = ClickableSurfaceDefaults.shape(TileShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0x40FFFFFF),
                focusedContainerColor = Color(0x66FFFFFF),
                contentColor = Color.White,
                focusedContentColor = Color.White,
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.55f)), shape = TileShape),
            ),
        ) { content() }
        Spacer(Modifier.height(12.dp))
        Txt(if (focused) label else " ", 14.sp, FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun AppTile(
    app: AppEntry, modifier: Modifier = Modifier, jiggle: Int? = null, badge: String? = null,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Tile(app.label, onClick, modifier, onLongClick, jiggle) {
        if (app.pkg == WALLPAPER_TILE) {
            IconFace(Icons.Outlined.Shuffle, 40.dp)
        } else if (app.banner != null) {
            Image(app.banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A3A42), Color(0xFF1C1C21)))),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                app.icon?.let { Image(it, null, Modifier.size(46.dp)) }
                Spacer(Modifier.height(6.dp))
                Txt(app.label, 12.sp, FontWeight.Medium, maxLines = 1, shadow = false)
            }
        }
        if (badge != null) {
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 7.dp, vertical = 1.dp),
            ) { Txt(badge, 13.sp, FontWeight.Bold, shadow = false) }
        }
    }
}

@Composable
fun RecentTile(r: Recent, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val app = remember(r.pkg) {
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(r.pkg, 0)).toString()
        }.getOrDefault("")
    }
    Tile(r.title, onClick) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF34343C), Color(0xFF18181D)))))
        if (r.art.isNotEmpty()) {
            val model: Any = if (r.art.startsWith("/")) java.io.File(r.art) else r.art
            AsyncImage(model, r.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Txt(r.title, 12.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
            Txt(listOf(r.subtitle, app).filter { it.isNotEmpty() }.joinToString(" · "), 10.sp, FontWeight.Medium, alpha = 0.7f, maxLines = 1, shadow = false)
            if (r.duration > 0) {
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxWidth(r.progress).fillMaxHeight().background(Color.White))
                }
            }
        }
    }
}

/** A white symbol, with an optional name under it, for tiles that have no artwork of their own. */
@Composable
private fun IconFace(icon: ImageVector, size: Dp, name: String? = null) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2E3440), Color(0xFF1A1D24)))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(icon, null, Modifier.size(size), colorFilter = ColorFilter.tint(Color.White))
        if (name != null) {
            Spacer(Modifier.height(6.dp))
            Txt(name, 13.sp, FontWeight.Medium, maxLines = 1, shadow = false)
        }
    }
}

@Composable
fun InputTile(input: InputEntry, onClick: () -> Unit) {
    Tile(input.label, onClick) { IconFace(inputKind(input.label).icon, 38.dp, input.label) }
}
