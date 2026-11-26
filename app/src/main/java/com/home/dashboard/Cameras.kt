@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.home.dashboard

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun CameraTile(cam: Camera, feed: CameraFeed, modifier: Modifier, onClick: () -> Unit) {
    Tile(cam.name, onClick, modifier) {
        Box(Modifier.fillMaxSize().background(Color(0xFF111111)))
        // The view keeps showing the last frame after the player stops.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { c -> TextureView(c).also { feed.player.setVideoTextureView(it) } },
                onRelease = { feed.player.clearVideoTextureView(it); feed.hasFrame = false },
                modifier = Modifier.videoShape(feed.aspect),
            )
        }
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
            Spacer(Modifier.width(5.dp))
            Txt(cam.name, 11.sp, FontWeight.SemiBold, shadow = false)
        }
    }
}

// ---------- Camera stills & live video ----------

/** One camera's still-picture player. Lives outside the list so scrolling doesn't rebuild it. */
class CameraFeed(val player: ExoPlayer) {
    var aspect by mutableStateOf(16f / 9f)
    var hasFrame = false
    var shotAt = 0L
    /** True while the tile is on screen; there is no point connecting otherwise. */
    var visible = false
}

private fun VideoSize.aspect(): Float? =
    if (width > 0 && height > 0) width * pixelWidthHeightRatio / height else null

/** Largest box of this shape that fits, so 4:3 cameras get black bars instead of stretching. */
private fun Modifier.videoShape(aspect: Float) =
    if (aspect < 16f / 9f) fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true)
    else fillMaxWidth().aspectRatio(aspect)

private fun rtspSource(url: String) =
    RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url))

private fun mutedPlayer(ctx: Context) = ExoPlayer.Builder(ctx).build().apply {
    volume = 0f
    // UniFi's Opus audio track breaks Media3's RTP Opus reader.
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
    playWhenReady = true
}

/**
 * Live video in every tile redrew the whole screen ~30 times a second, so tiles show stills:
 * one camera at a time connects, draws a frame into its tile and disconnects.
 */
@Composable
fun rememberCameraFeeds(cams: List<Camera>, everySeconds: Int, paused: Boolean): List<CameraFeed> {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val feeds = remember(cams) { cams.map { CameraFeed(mutedPlayer(ctx)) } }
    DisposableEffect(feeds) { onDispose { feeds.forEach { it.player.release() } } }
    LaunchedEffect(feeds, everySeconds, paused, owner) {
        if (paused) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(500)
                val now = SystemClock.elapsedRealtime()
                val i = feeds.indices.firstOrNull { n ->
                    val f = feeds[n]
                    // Retry quickly while a tile is blank, otherwise on the normal interval.
                    f.visible && now - f.shotAt >= if (f.hasFrame) everySeconds * 1000L else 2_000L
                } ?: continue
                grabFrame(cams[i], feeds[i])
            }
        }
    }
    return feeds
}

private suspend fun grabFrame(cam: Camera, feed: CameraFeed) {
    val result = CompletableDeferred<Boolean>()
    val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() { result.complete(true) }
        override fun onVideoSizeChanged(videoSize: VideoSize) { videoSize.aspect()?.let { feed.aspect = it } }
        override fun onPlayerError(error: PlaybackException) { result.complete(false) }
    }
    feed.player.addListener(listener)
    try {
        feed.player.setMediaSource(rtspSource(cam.streamUrl))
        feed.player.prepare()
        if (withTimeoutOrNull(8_000) { result.await() } == true) {
            delay(300)
            feed.hasFrame = true
        }
    } finally {
        feed.shotAt = SystemClock.elapsedRealtime()
        feed.player.removeListener(listener)
        feed.player.stop()
    }
}

@Composable
fun LiveVideo(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember { mutedPlayer(ctx) }
    var errors by remember { mutableIntStateOf(0) }
    var aspect by remember { mutableStateOf(16f / 9f) }
    DisposableEffect(player, owner, url) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) { videoSize.aspect()?.let { aspect = it } }
            override fun onPlayerError(error: PlaybackException) { errors++ }
        }
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> { player.prepare(); player.play() }
                Lifecycle.Event.ON_STOP -> player.stop()
                else -> {}
            }
        }
        player.addListener(listener)
        player.setMediaSource(rtspSource(url))
        player.prepare()
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            player.removeListener(listener)
            player.stop()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(errors) {
        if (errors > 0) { delay(3_000); player.prepare() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c -> SurfaceView(c).also { player.setVideoSurfaceView(it) } },
            modifier = Modifier.videoShape(aspect),
        )
    }
}

@Composable
fun FullCamera(cams: List<Camera>, index: Int, onChange: (Int) -> Unit, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    BackHandler { onClose() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(fr)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onChange((index - 1 + cams.size) % cams.size); true }
                    Key.DirectionRight -> { onChange((index + 1) % cams.size); true }
                    Key.DirectionCenter, Key.Enter -> { onClose(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        key(index) { LiveVideo(cams[index].hdStreamUrl, Modifier.fillMaxSize()) }
        Txt(cams[index].name, 24.sp, FontWeight.SemiBold, modifier = Modifier.align(Alignment.TopStart).padding(40.dp))
        if (cams.size > 1) {
            Txt(
                "◀  ▶  switch camera    ·    Back to close", 13.sp, alpha = 0.6f,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            )
        }
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
}
