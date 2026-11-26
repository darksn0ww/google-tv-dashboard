@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.home.dashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Size
import coil.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- Wallpaper ----------

@Composable
fun rememberWallpaper(cfg: Config): Photo? {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf<Photo?>(null) }
    LaunchedEffect(cfg.wallpaper, cfg.unsplashKey, cfg.unsplashQueries, cfg.wallpaperMinutes) {
        if (cfg.wallpaper.isNotBlank()) {
            // Your own picture wins over Unsplash.
            val url = if (cfg.wallpaper != "file") cfg.wallpaper
            else Uri.fromFile(File(ctx.filesDir, "wallpaper")).toString()
            val own = Photo(url, author = "", downloadLocation = "")
            while (ctx.imageLoader.execute(wallpaperRequest(ctx, own)) !is SuccessResult) delay(60_000)
            current = own
            return@LaunchedEffect
        }
        if (cfg.unsplashKey.isBlank()) return@LaunchedEffect
        val queue = ArrayDeque<Photo>()
        while (true) {
            if (queue.isEmpty()) {
                queue.addAll(runCatching { withContext(Dispatchers.IO) { Unsplash.random(cfg) } }.getOrDefault(emptyList()))
            }
            val next = queue.removeFirstOrNull()
            if (next == null) { delay(60_000); continue }
            val res = ctx.imageLoader.execute(wallpaperRequest(ctx, next)) // preload exactly what is drawn
            if (res is SuccessResult) {
                current = next
                launch(Dispatchers.IO) { Unsplash.trackDownload(cfg, next) }
                delay(cfg.wallpaperMinutes * 60_000L)
            }
        }
    }
    return current
}

/**
 * The wallpaper is redrawn on every frame (and camera tiles force ~30 frames a second), which on
 * weak TV GPUs was most of the frame time. So the darkening gradient is painted into the bitmap
 * once, and it is kept at 720p and opaque.
 */
private object Scrim : Transformation {
    override val cacheKey = "scrim-2"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val out = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(input, 0f, 0f, null)
        val h = out.height.toFloat()
        val paint = Paint().apply {
            isDither = true
            shader = LinearGradient(
                0f, 0f, 0f, h, intArrayOf(0x26000000, 0x73000000, 0x73000000, 0xCC000000.toInt()),
                floatArrayOf(0f, 0.38f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, out.width.toFloat(), h, paint)
        out.setHasAlpha(false)
        return out
    }
}

private fun wallpaperRequest(ctx: Context, photo: Photo) =
    ImageRequest.Builder(ctx).data(photo.url).size(1280, 720).precision(Precision.EXACT)
        .transformations(Scrim).build()

@Composable
fun WallpaperLayer(photo: Photo?) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = photo, animationSpec = tween(1200), label = "wallpaper") { p ->
            if (p == null) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2A41), Color(0xFF0B0D12)))))
            } else {
                val req = remember(p.url) { wallpaperRequest(ctx, p) }
                AsyncImage(model = req, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        photo?.takeIf { it.author.isNotEmpty() }?.let {
            Txt(
                "Photo: ${it.author} / Unsplash", 10.sp, alpha = 0.55f,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 10.dp),
            )
        }
    }
}
