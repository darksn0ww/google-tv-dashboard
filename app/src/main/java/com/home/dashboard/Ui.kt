@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.home.dashboard

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.tv.TvContract
import android.net.Uri
import android.view.SurfaceView
import android.view.TextureView
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.SettingsInputHdmi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Size
import coil.transform.Transformation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

// ---------- Look & feel ----------

val Inter = FontFamily(
    Font(R.font.inter_extralight, FontWeight.ExtraLight),
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

// Five tiles + four gaps exactly fill the 960dp screen minus 48dp either side.
private val TileW = 152.dp
private val TileH = 86.dp
private val RowInset = 48.dp
private val TileShape = RoundedCornerShape(14.dp)
private val CardH = 176.dp
private val CardShape = RoundedCornerShape(24.dp)
private val IosRed = Color(0xFFFF453A)

private val HHmm = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val WeekdayFmt = DateTimeFormatter.ofPattern("EEEE", Locale.UK)
private val DayMonthFmt = DateTimeFormatter.ofPattern("d MMMM", Locale.UK)
private val ShortDayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val DowFmt = DateTimeFormatter.ofPattern("EEE", Locale.UK)
private val MonthFmt = DateTimeFormatter.ofPattern("MMMM", Locale.UK)

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

private fun launch(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun launchInput(ctx: Context, id: String) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Scroll only as far as needed to reveal the focused item (TV default pivots it to 30%). */
private class MinimalScroll(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // margin leaves room for the focused tile's scale-up and the label under it
        val trailing = offset + size + margin
        // The same headroom above, for row titles and the padding over the widgets.
        val leading = offset - margin
        return when {
            leading >= 0f && trailing <= containerSize -> 0f
            leading < 0f -> leading
            else -> minOf(trailing - containerSize, leading)
        }
    }
}

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

// ---------- Screen ----------

@Composable
fun Dashboard(homePresses: StateFlow<Int>) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    var config by remember { mutableStateOf(Config()) }
    LaunchedEffect(resumeTick) {
        val c = withContext(Dispatchers.IO) { Config.load(ctx) }
        if (c != config) config = c
    }

    var foundPlace by remember { mutableStateOf("") }
    val weather by produceState<Weather?>(null, config.loaded, config.autoLocation, config.latitude, config.longitude) {
        if (!config.loaded) return@produceState
        var found: Place? = null
        while (true) {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (config.autoLocation && found == null) found = Locator.find()
                    WeatherApi.fetch(found?.latitude ?: config.latitude, found?.longitude ?: config.longitude)
                }
            }.onSuccess {
                foundPlace = found?.name.orEmpty()
                value = it
            }
            delay(if (value == null) 60_000L else 15 * 60_000L)
        }
    }

    val events by produceState(emptyList<CalEvent>(), config.loaded, config.calendars) {
        if (!config.loaded) return@produceState
        while (true) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val now = ZonedDateTime.now()
                    Calendars.fetchAll(config.calendars, now, now.plusDays(14))
                }
            }.onSuccess { value = it }
            delay(15 * 60_000L)
        }
    }

    val apps by produceState(AppsData(), resumeTick, config) {
        if (config.loaded) value = withContext(Dispatchers.IO) { Apps.load(ctx, config) }
    }

    val photo = rememberWallpaper(config)
    // Where heating comes from: Home Assistant if it names a thermostat, else Nest, else tado directly.
    // Only the chosen source is ever polled, and only while on screen.
    val ha = config.homeAssistant
    val source = when {
        !ha?.climate.isNullOrBlank() -> "ha"
        config.nest != null -> "nest"
        config.tado -> "tado"
        ha != null && !ha.connected -> "ha"
        else -> null
    }
    val nestState by produceState<HeatingState>(HeatingState.Reading(null), source, config.nest, owner) {
        val cfg = config.nest ?: return@produceState
        if (source != "nest") return@produceState
        val nest = Nest(cfg)
        var last: Heating? = null
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = runCatching { withContext(Dispatchers.IO) { nest.read() } }.fold(
                    { last = it; HeatingState.Reading(it) },
                    { HeatingState.Reading(last, it.message ?: "Nest unavailable") },
                )
                delay(5 * 60_000L)
            }
        }
    }
    val haState by produceState<HeatingState>(HeatingState.Reading(null), source, ha, owner) {
        if (source != "ha" || ha == null) return@produceState
        if (!ha.ready) {
            value = HeatingState.NotConfigured
            return@produceState
        }
        val reader = HaHeating(ha, File(ctx.filesDir, "heating.json"))
        value = HeatingState.Reading(withContext(Dispatchers.IO) { reader.cached() })
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = withContext(Dispatchers.IO) { reader.read() }
                delay(60_000L)
            }
        }
    }
    val tado = remember(source) { if (source == "tado") Tado(ctx.applicationContext) else null }
    val tadoState = tado?.state?.collectAsState()?.value ?: HeatingState.NotLinked
    // Tado.refresh itself decides whether a request is due (its API is rationed).
    LaunchedEffect(tado, config.tadoZone, tadoState is HeatingState.Reading, owner) {
        if (tado == null) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { tado.refresh(config.tadoZone); delay(60_000) }
        }
    }
    val heating: HeatingState? = when (source) {
        "ha" -> haState
        "nest" -> nestState
        "tado" -> tadoState
        else -> null
    }
    var panelOpen by remember { mutableStateOf(false) }
    val panel = ha?.takeIf { it.hasPanel }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { RecentStore.load(ctx) } }
    val recent by RecentStore.items.collectAsState()
    val cams = config.cameras

    var fullCam by remember { mutableStateOf<Int?>(null) }
    val feeds = rememberCameraFeeds(cams, config.snapshotSeconds, paused = fullCam != null)
    var lastCam by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstFav = remember { FocusRequester() }
    val camFocus = remember(cams.size) { List(cams.size) { FocusRequester() } }
    val homeCount by homePresses.collectAsState()
    val hasFavs = apps.favourites.isNotEmpty()

    // Home button (or first load): back to the top, focus the first favourite.
    LaunchedEffect(homeCount, hasFavs) {
        fullCam = null
        lastCam = -1
        panelOpen = false
        if (hasFavs) {
            listState.scrollToItem(0)
            delay(80)
            runCatching { firstFav.requestFocus() }
        }
    }
    // Returning from a full-screen camera: refocus its tile.
    LaunchedEffect(fullCam) {
        if (fullCam == null && lastCam in camFocus.indices) {
            delay(120)
            runCatching { camFocus[lastCam].requestFocus() }
        }
    }

    val scrollMargin = with(LocalDensity.current) { 44.dp.toPx() }
    val bringSpec = remember(scrollMargin) { MinimalScroll(scrollMargin) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringSpec) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            WallpaperLayer(photo)
            if (panelOpen && panel != null) SmartHomePanel(panel, heating) { panelOpen = false }
            val fc = fullCam
            if (fc != null && fc in cams.indices) {
                FullCamera(cams, fc, onChange = { fullCam = it; lastCam = it }, onClose = { fullCam = null })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 64.dp),
                ) {
                    item(key = "header") {
                        Box(
                            Modifier.onFocusChanged {
                                if (it.hasFocus) scope.launch { delay(150); listState.animateScrollToItem(0) }
                            },
                        ) {
                            Header(
                                weather, events, config.place.ifBlank { foundPlace }, heating,
                                onLink = { scope.launch { tado?.link() } },
                                onPanel = if (panel != null) ({ panelOpen = true }) else null,
                                hasCalendars = config.calendars.isNotEmpty(),
                            )
                        }
                    }
                    if (hasFavs) item(key = "fav") {
                        TileRow(
                            "Favourites",
                            Modifier.onFocusChanged {
                                // Let the focus scroll settle first, then reveal the widgets above.
                                if (it.hasFocus && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) {
                                    scope.launch { delay(150); listState.animateScrollToItem(0) }
                                }
                            },
                        ) {
                            itemsIndexed(apps.favourites, key = { _, a -> a.pkg }) { i, a ->
                                AppTile(a, if (i == 0) Modifier.focusRequester(firstFav) else Modifier) { launch(ctx, a.intent) }
                            }
                        }
                    }
                    if (recent.isNotEmpty()) item(key = "recent") {
                        TileRow("Continue watching") {
                            items(recent, key = { it.id }) { r ->
                                RecentTile(r) {
                                    ctx.packageManager.getLeanbackLaunchIntentForPackage(r.pkg)?.let { launch(ctx, it) }
                                }
                            }
                        }
                    }
                    if (cams.isNotEmpty() && feeds.size == cams.size) item(key = "cams") {
                        TileRow("Cameras") {
                            itemsIndexed(cams, key = { i, c -> "$i${c.url}" }) { i, c ->
                                DisposableEffect(feeds[i]) {
                                    feeds[i].visible = true
                                    onDispose { feeds[i].visible = false }
                                }
                                CameraTile(c, feeds[i], Modifier.focusRequester(camFocus[i])) { lastCam = i; fullCam = i }
                            }
                        }
                    }
                    if (apps.inputs.isNotEmpty()) item(key = "inputs") {
                        TileRow("Inputs") {
                            items(apps.inputs, key = { it.id }) { InputTile(it) { launchInput(ctx, it.id) } }
                        }
                    }
                    if (apps.others.isNotEmpty()) item(key = "apps") {
                        TileRow("Apps") {
                            items(apps.others, key = { it.pkg }) { a -> AppTile(a) { launch(ctx, a.intent) } }
                        }
                    }
                }
            }
        }
    }
}

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
            val own = Photo("own", url, author = "", downloadLocation = "")
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
    override val cacheKey = "scrim-1"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val out = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(input, 0f, 0f, null)
        val h = out.height.toFloat()
        val paint = Paint().apply {
            isDither = true
            shader = LinearGradient(
                0f, 0f, 0f, h, intArrayOf(0x26000000, 0x0D000000, 0xBF000000.toInt()),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
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

// ---------- Widgets (iOS style) ----------

/**
 * Shared card. A plain translucent fill on purpose: frosted wallpaper, blurred text shadows and
 * focus glow together made these boxes' GPUs miss almost every frame.
 */
@Composable
fun WidgetCard(modifier: Modifier, background: Brush? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.clip(CardShape)) {
        Box(
            Modifier.matchParentSize()
                .then(if (background != null) Modifier.background(background) else Modifier.background(Color(0xE61C1C1E))),
        )
        Box(Modifier.matchParentSize().border(1.dp, Color.White.copy(alpha = 0.10f), CardShape))
        Column(Modifier.matchParentSize().padding(17.dp), content = content)
    }
}

@Composable
fun Header(
    weather: Weather?, events: List<CalEvent>, place: String, heating: HeatingState?,
    onLink: () -> Unit, onPanel: (() -> Unit)?, hasCalendars: Boolean,
) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            val t = LocalDateTime.now()
            value = t.withSecond(0).withNano(0) // unchanged within a minute, so no redraw
            delay(1000L - t.nano / 1_000_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 12.dp).height(CardH),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ClockCard(now, Modifier.width(160.dp).fillMaxHeight())
        val third = heating != null || onPanel != null
        WeatherCard(weather, place, Modifier.width(if (third) 292.dp else 372.dp).fillMaxHeight())
        val side = Modifier.width(166.dp).fillMaxHeight()
        if (heating != null) {
            HeatingCard(heating, onLink, onPanel, side)
        } else if (onPanel != null) {
            ButtonCard(onPanel, side) {
                Txt("SMART HOME", 11.sp, FontWeight.SemiBold, alpha = 0.6f, shadow = false)
                Spacer(Modifier.height(10.dp))
                Txt("Open controls", 20.sp, FontWeight.SemiBold, shadow = false)
            }
        }
        val rest = Modifier.weight(1f).fillMaxHeight()
        if (hasCalendars) CalendarCard(events, now.toLocalDate(), rest) else MonthCard(now.toLocalDate(), rest)
    }
}

@Composable
fun ClockCard(now: LocalDateTime, modifier: Modifier) {
    val ctx = LocalContext.current
    // Select the clock to start Google's ambient slideshow.
    Surface(
        onClick = {
            // The accessibility service presses "Start now"; without it you just press OK once more.
            HomeOverrideService.startAmbientUntil = SystemClock.elapsedRealtime() + 4_000
            launch(ctx, Intent("com.google.android.tv.settings.ambient").setPackage("com.android.tv.settings"))
        },
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(CardShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xE61C1C1E), focusedContainerColor = Color(0xFF3A3A3C),
            contentColor = Color.White, focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape = CardShape),
            focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)), shape = CardShape),
        ),
    ) {
    Image(
        Icons.Filled.Landscape, "Ambient screensaver",
        Modifier.align(Alignment.TopEnd).padding(16.dp).size(16.dp), colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.6f)),
    )
    Column(Modifier.fillMaxSize().padding(17.dp)) {
    Txt(now.format(WeekdayFmt).uppercase(), 12.sp, FontWeight.SemiBold, alpha = 0.6f, letterSpacing = 0.6.sp, shadow = false)
    Txt(now.format(DayMonthFmt), 16.sp, FontWeight.SemiBold, shadow = false)
    Spacer(Modifier.weight(1f))
    Txt(now.format(HHmm), 44.sp, FontWeight.SemiBold, letterSpacing = (-1.5).sp, lineHeight = 50.sp, maxLines = 1, shadow = false)
    }
    }
}

@Composable
fun WeatherCard(w: Weather?, place: String, modifier: Modifier) =
    WidgetCard(modifier, background = w?.let { weatherBrush(it.code, it.isDay) }) {
        val title = place.ifBlank { "Weather" }
        if (w == null) {
            Txt(title, 15.sp, FontWeight.SemiBold, shadow = false)
            Txt("--°", 46.sp, FontWeight.Medium, shadow = false)
            return@WidgetCard
        }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Txt(title, 15.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
                Txt("${w.temp}°", 46.sp, FontWeight.Medium, letterSpacing = (-1).sp, lineHeight = 50.sp, shadow = false)
            }
            Column(horizontalAlignment = Alignment.End) {
                WIcon(w.code, w.isDay, 22.dp)
                Spacer(Modifier.height(4.dp))
                Txt(weatherLabel(w.code), 13.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
                Txt("H:${w.hi}° L:${w.lo}°", 13.sp, FontWeight.Medium, shadow = false)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            w.days.take(5).forEachIndexed { i, d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(if (i == 0) "Today" else d.date.format(DowFmt), 11.sp, FontWeight.SemiBold, alpha = 0.8f, shadow = false)
                    Spacer(Modifier.height(3.dp))
                    WIcon(d.code, true, 16.dp)
                    Spacer(Modifier.height(3.dp))
                    Row {
                        Txt("${d.hi}°", 12.sp, FontWeight.SemiBold, shadow = false)
                        Spacer(Modifier.width(4.dp))
                        Txt("${d.lo}°", 12.sp, FontWeight.Medium, alpha = 0.6f, shadow = false)
                    }
                }
            }
        }
    }

internal fun deg(t: Double?): String =
    if (t == null) "Off" else String.format(Locale.UK, "%.1f", t).removeSuffix(".0") + "°"

/** The time of day, with the weekday once it isn't today. */
private fun readingTime(millis: Long): String {
    val at = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    val time = at.format(HHmm)
    return if (at.toLocalDate() == LocalDate.now()) time else "${at.format(DowFmt)} $time"
}

/** A widget you can select. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ButtonCard(onClick: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(CardShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xE61C1C1E), focusedContainerColor = Color(0xFF3A3A3C),
            contentColor = Color.White, focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape = CardShape),
            focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)), shape = CardShape),
        ),
    ) {
        Column(Modifier.fillMaxSize().padding(17.dp), content = content)
    }
}

@Composable
fun HeatingCard(state: HeatingState, onLink: () -> Unit, onPanel: (() -> Unit)?, modifier: Modifier) {
    when {
        state is HeatingState.NotLinked -> ButtonCard(onLink, modifier) {
            Txt("HEATING", 11.sp, FontWeight.SemiBold, alpha = 0.6f, shadow = false)
            Spacer(Modifier.height(10.dp))
            Txt("Link Tado", 20.sp, FontWeight.SemiBold, shadow = false)
            Spacer(Modifier.height(6.dp))
            Txt("Select to get a sign-in code", 12.sp, alpha = 0.7f, shadow = false)
        }
        state is HeatingState.Linking -> WidgetCard(modifier) {
            Txt("ON YOUR PHONE", 11.sp, FontWeight.SemiBold, alpha = 0.6f, shadow = false)
            Spacer(Modifier.height(6.dp))
            Txt("login.tado.com/oauth2/device", 11.sp, FontWeight.Medium, shadow = false)
            Spacer(Modifier.height(14.dp))
            Txt("Code", 12.sp, alpha = 0.7f, shadow = false)
            Txt(state.code, 22.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
        }
        onPanel != null -> ButtonCard(onPanel, modifier) { HeatingContent(state) }
        else -> WidgetCard(modifier) { HeatingContent(state) }
    }
}

@Composable
private fun ColumnScope.HeatingContent(state: HeatingState) {
        if (state !is HeatingState.Reading) {
            Txt("HEATING", 11.sp, FontWeight.SemiBold, alpha = 0.6f, shadow = false)
            Spacer(Modifier.height(10.dp))
            Txt("Home Assistant not configured", 17.sp, FontWeight.SemiBold, lineHeight = 21.sp, shadow = false)
            Spacer(Modifier.height(6.dp))
            Txt("Set homeAssistant in config.json", 12.sp, alpha = 0.7f, shadow = false)
            return
        }
        val h = state.heating
        Txt((h?.zone ?: "Heating").uppercase(), 11.sp, FontWeight.SemiBold, alpha = 0.6f, maxLines = 1, shadow = false)
        if (h == null) {
            Txt(state.problem?.replaceFirstChar { it.uppercase() } ?: "Loading…", 13.sp, alpha = 0.7f, shadow = false)
            return
        }
        Txt(deg(h.current).takeIf { h.current != null } ?: "--°", 38.sp, FontWeight.Medium, lineHeight = 42.sp, shadow = false)
        val range = h.upperTarget?.let { "–${deg(it)}" }.orEmpty()
        val note = if (h.away) " · away" else if (h.eco) " · eco" else if (h.manual) " · manual" else ""
        Txt("Set to ${deg(h.target)}$range$note", 13.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
        Spacer(Modifier.weight(1f))
        h.nextAt?.let {
            Txt("Next ${readingTime(it)} → ${deg(h.nextTarget)}", 13.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
        }
        val humidity = h.humidity?.let { "${it.roundToInt()}%" }
        if (h.active) {
            Txt(listOfNotNull("Heating", humidity?.let { "$it RH" }).joinToString(" · "), 12.sp, FontWeight.SemiBold, color = Color(0xFFFF9F0A), maxLines = 1, shadow = false)
        } else if (humidity != null) {
            Txt("Humidity $humidity", 12.sp, FontWeight.Medium, alpha = 0.7f, maxLines = 1, shadow = false)
        }
        Spacer(Modifier.height(4.dp))
        val problem = state.problem
        Txt(
            if (problem == null) "Updated ${readingTime(h.updated)}" else "Stale ${readingTime(h.updated)} · $problem",
            9.sp, alpha = if (problem == null) 0.5f else 0.9f, maxLines = 1, shadow = false,
        )
    }

@Composable
fun MonthCard(today: LocalDate, modifier: Modifier) =
    WidgetCard(modifier) {
        Txt(today.format(MonthFmt).uppercase(), 11.sp, FontWeight.SemiBold, color = IosRed, maxLines = 1, shadow = false)
        val start = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val first = today.withDayOfMonth(1)
        val blanks = (first.dayOfWeek.value - start.value + 7) % 7
        val cells = List(blanks) { 0 } + (1..today.lengthOfMonth()).toList()
        @Composable
        fun week(days: List<Any>) = Row(Modifier.fillMaxWidth().height(17.dp)) {
            repeat(7) { i ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val day = days.getOrNull(i)
                    if (day == today.dayOfMonth) Box(Modifier.size(17.dp).background(IosRed, CircleShape))
                    if (day is String) Txt(day, 9.sp, FontWeight.SemiBold, alpha = 0.5f, shadow = false)
                    else if (day is Int && day > 0) Txt(day.toString(), 10.sp, FontWeight.SemiBold, shadow = false)
                }
            }
        }
        week((0L..6L).map { start.plus(it).getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()) })
        cells.chunked(7).forEach { week(it) }
    }

@Composable
fun CalendarCard(events: List<CalEvent>, today: LocalDate, modifier: Modifier) =
    WidgetCard(modifier) {
        Txt(today.format(WeekdayFmt).uppercase(), 11.sp, FontWeight.SemiBold, color = IosRed, maxLines = 1, shadow = false)
        Txt(today.dayOfMonth.toString(), 32.sp, FontWeight.Medium, lineHeight = 36.sp, shadow = false)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            val now = ZonedDateTime.now()
            val upcoming = events.filter { it.end.isAfter(now) }.take(2)
            if (upcoming.isEmpty()) Txt("No upcoming events", 13.sp, FontWeight.Medium, alpha = 0.6f, shadow = false)
            upcoming.forEach { EventRow(it, now) }
        }
    }

@Composable
fun EventRow(e: CalEvent, now: ZonedDateTime) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Color(e.color)))
        Spacer(Modifier.width(8.dp))
        Column {
            Txt(e.title, 13.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
            Txt(eventTime(e, now), 11.sp, FontWeight.Medium, alpha = 0.6f, maxLines = 1, shadow = false)
        }
    }
}

private fun eventTime(e: CalEvent, now: ZonedDateTime): String {
    val today = now.toLocalDate()
    val d = e.start.toLocalDate()
    val day = when {
        !d.isAfter(today) -> ""
        d == today.plusDays(1) -> "Tomorrow"
        else -> d.format(ShortDayFmt)
    }
    val time = when {
        e.allDay -> "All day"
        !e.start.isAfter(now) -> "Now"
        else -> e.start.format(HHmm) + "–" + e.end.format(HHmm)
    }
    return listOf(day, time).filter { it.isNotEmpty() }.joinToString(", ")
}

// ---------- Rows & tiles ----------

@Composable
fun TileRow(title: String, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier.padding(top = 2.dp)) {
        Txt(title, 15.sp, FontWeight.SemiBold, alpha = 0.8f, modifier = Modifier.padding(start = 52.dp))
        val inset = with(LocalDensity.current) { RowInset.toPx() }
        val rowScroll = remember(inset) { RowScroll(inset) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowScroll) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().focusRestorer(),
                contentPadding = PaddingValues(horizontal = RowInset, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                content = content,
            )
        }
    }
}

@Composable
fun Tile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(TileW), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(TileW, TileH).onFocusChanged { focused = it.isFocused },
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
fun AppTile(app: AppEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Tile(app.label, onClick, modifier) {
        if (app.banner != null) {
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

@Composable
fun InputTile(input: InputEntry, onClick: () -> Unit) {
    Tile(input.label, onClick) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2E3440), Color(0xFF1A1D24)))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(Icons.Outlined.SettingsInputHdmi, null, Modifier.size(34.dp), colorFilter = ColorFilter.tint(Color.White))
            Spacer(Modifier.height(6.dp))
            Txt(input.label, 13.sp, FontWeight.Medium, shadow = false)
        }
    }
}

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
