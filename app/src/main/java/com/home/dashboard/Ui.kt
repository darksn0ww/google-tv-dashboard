@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.home.dashboard

import android.content.Context
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.content.Intent
import android.media.tv.TvContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime

internal fun launch(ctx: Context, intent: Intent) {
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
        val last = mutableMapOf<String, List<CalEvent>>()
        while (true) {
            val result = withContext(Dispatchers.IO) {
                // From midnight, so the calendar screen also lists what has already happened today.
                val today = ZonedDateTime.now().let { it.toLocalDate().atStartOfDay(it.zone) }
                Calendars.fetchAll(config.calendars, today, today.plusDays(31), last)
            }
            value = result.events
            // Try again soon after a failure, for instance at boot before the network is up.
            delay(60_000L * if (result.failed == 0) 15 else if (result.events.isEmpty()) 1 else 5)
        }
    }

    val apps by produceState(AppsData(), resumeTick, config) {
        if (config.loaded) value = withContext(Dispatchers.IO) { Apps.load(ctx) }
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
    var screen by remember { mutableStateOf<String?>(null) } // "weather" or "calendar", opened from its widget
    var lastScreen by remember { mutableStateOf<String?>(null) }
    val weatherFocus = remember { FocusRequester() }
    val calendarFocus = remember { FocusRequester() }
    val feeds = rememberCameraFeeds(cams, config.snapshotSeconds, paused = fullCam != null || screen != null)
    var lastCam by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstFav = remember { FocusRequester() }
    val favRow = rememberLazyListState()
    // Favourites can be rearranged on the TV: hold OK on any app.
    var favourites by remember(config.favourites) { mutableStateOf(Favourites.load(ctx, config.favourites)) }
    var editing by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<String?>(null) } // favourite being moved
    var focusApp by remember { mutableStateOf<String?>(null) } // tile to focus after the rows change
    val appFocus = remember { mutableMapOf<String, FocusRequester>() }
    val allApps = remember(apps) { apps.all.associateBy { it.pkg } }
    val favApps = remember(allApps, favourites) { favourites.mapNotNull { allApps[it] } }
    val otherApps = remember(allApps, favourites, config.hidden) {
        allApps.values.filter { it.pkg !in favourites && it.pkg !in config.hidden }.sortedBy { it.label.lowercase() }
    }
    fun keepFavourites(list: List<String>) {
        favourites = list
        Favourites.save(ctx, config.favourites, list)
    }
    val camFocus = remember(cams.size) { List(cams.size) { FocusRequester() } }
    val homeCount by homePresses.collectAsState()
    val hasFavs = favApps.isNotEmpty()

    // Back to the top and the first favourite, however far either has been scrolled.
    suspend fun goHome() {
        if (!hasFavs) return
        listState.scrollToItem(0)
        favRow.scrollToItem(0)
        delay(80)
        runCatching { firstFav.requestFocus() }
    }
    // Home button (or first load).
    LaunchedEffect(homeCount, apps) {
        editing = false
        held = null
        fullCam = null
        lastCam = -1
        panelOpen = false
        screen = null
        lastScreen = null
        goHome()
    }
    LaunchedEffect(focusApp) {
        val pkg = focusApp ?: return@LaunchedEffect
        delay(80)
        runCatching { appFocus[pkg]?.requestFocus() }
        focusApp = null
    }
    // Returning from the weather or calendar screen: refocus its widget.
    LaunchedEffect(screen) {
        if (screen == null && lastScreen != null) {
            delay(120)
            runCatching { (if (lastScreen == "weather") weatherFocus else calendarFocus).requestFocus() }
            lastScreen = null
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
            } else if (screen == "weather" && weather != null) {
                WeatherScreen(weather!!, config.place.ifBlank { foundPlace }) { screen = null }
            } else if (screen == "calendar") {
                CalendarScreen(events) { screen = null }
            } else {
                // Back leaves editing first; otherwise it does what Home does.
                BackHandler {
                    if (editing) {
                        editing = false
                        held = null
                    } else scope.launch { goHome() }
                }
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
                                onWeather = { if (weather != null) { lastScreen = "weather"; screen = "weather" } },
                                onCalendar = { lastScreen = "calendar"; screen = "calendar" },
                                weatherModifier = Modifier.focusRequester(weatherFocus),
                                calendarModifier = Modifier.focusRequester(calendarFocus),
                            )
                        }
                    }
                    if (hasFavs) item(key = "fav") {
                        TileRow(
                            when {
                                held != null -> "Favourites  ·  ◀ ▶ move   ▼ remove   OK to drop"
                                editing -> "Favourites  ·  OK to pick up   Back when done"
                                else -> "Favourites"
                            },
                            Modifier.onFocusChanged {
                                // Let the focus scroll settle first, then reveal the widgets above.
                                if (it.hasFocus && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) {
                                    scope.launch { delay(150); listState.animateScrollToItem(0) }
                                }
                            },
                            favRow,
                        ) {
                            itemsIndexed(favApps, key = { _, a -> a.pkg }) { i, a ->
                                val requester = appFocus.getOrPut(a.pkg) { FocusRequester() }
                                val moving = held == a.pkg
                                val keys = Modifier.onPreviewKeyEvent { e ->
                                    if (!moving) return@onPreviewKeyEvent false
                                    val step = when (e.key) {
                                        Key.DirectionLeft -> -1
                                        Key.DirectionRight -> 1
                                        Key.DirectionUp, Key.DirectionDown -> 0
                                        else -> return@onPreviewKeyEvent false
                                    }
                                    if (e.type == KeyEventType.KeyDown) {
                                        val shown = favApps.map { it.pkg }
                                        val at = shown.indexOf(a.pkg)
                                        if (e.key == Key.DirectionDown) {
                                            held = null
                                            focusApp = shown.getOrNull(at + 1) ?: shown.getOrNull(at - 1)
                                            keepFavourites(favourites - a.pkg)
                                        } else if (step != 0) {
                                            keepFavourites(Favourites.move(favourites, shown, a.pkg, step))
                                            scope.launch {
                                                favRow.animateScrollToItem((at + step - 2).coerceAtLeast(0))
                                                runCatching { requester.requestFocus() }
                                            }
                                        }
                                    }
                                    true
                                }
                                AppTile(
                                    a,
                                    (if (i == 0) Modifier.focusRequester(firstFav) else Modifier).focusRequester(requester).then(keys),
                                    jiggle = if (editing) i else null,
                                    badge = if (moving) "◀ ▶" else null,
                                    onLongClick = { editing = true },
                                ) {
                                    if (editing) held = if (moving) null else a.pkg else launch(ctx, a.intent)
                                }
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
                    if (otherApps.isNotEmpty()) item(key = "apps") {
                        TileRow(if (editing) "Apps  ·  OK to add to favourites" else "Apps") {
                            itemsIndexed(otherApps, key = { _, a -> a.pkg }) { i, a ->
                                AppTile(
                                    a,
                                    Modifier.focusRequester(appFocus.getOrPut(a.pkg) { FocusRequester() }),
                                    jiggle = if (editing) i else null,
                                    badge = if (editing) "+" else null,
                                    onLongClick = { editing = true },
                                ) {
                                    if (editing) {
                                        held = null
                                        focusApp = otherApps.getOrNull(i + 1)?.pkg ?: otherApps.getOrNull(i - 1)?.pkg ?: a.pkg
                                        keepFavourites(favourites - a.pkg + a.pkg)
                                    } else launch(ctx, a.intent)
                                }
                            }
                        }
                    }
                }
            }
            if (config.problem.isNotEmpty()) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(16.dp).background(Color(0xE61C1C1E), RoundedCornerShape(10.dp))
                        .padding(12.dp, 8.dp),
                ) { Txt(config.problem, 12.sp, FontWeight.SemiBold, color = Color(0xFFFF9F0A), shadow = false) }
            }
        }
    }
}
