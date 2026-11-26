package com.home.dashboard

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Grey = Color(0xFF2C2C2E)
private val SideColor = Color(0xFF1C1C1E)
private val FullColor = Color(0xFF111A2B)
private val Dim = Color(0xFFAEAEB2)
private val Amber = Color(0xFFFF9F0A)

private class PanelModel(
    val cfg: HaConfig,
    val state: PanelState?,
    val map: ImageBitmap?,
    val heating: Heating?,
    val stale: String?,
    val problem: String?,
    val start: () -> Unit,
    val pause: () -> Unit,
    val dock: () -> Unit,
    val toggle: (HaDevice) -> Unit,
) {
    fun on(d: HaDevice) = state?.devices?.firstOrNull { it.device == d }?.on
    val lightsOn = cfg.devices.count { on(it) == true && it.entity.startsWith("light.") }
    val vacuum = state?.vacuum?.state?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
}

/**
 * A column on the right that expands to the whole screen. A dialog, so the remote stays inside it.
 * Back shrinks an expanded panel first, then closes it. Heating is read-only.
 */
@Composable
fun SmartHomePanel(cfg: HaConfig, heating: HeatingState?, onClose: () -> Unit) {
    val home = remember(cfg) { SmartHome(cfg) }
    var state by remember { mutableStateOf<PanelState?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var toggled by remember { mutableStateOf(false) } // after expanding or shrinking, stay on that button
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val sideSize = remember { FocusRequester() }
    val fullSize = remember { FocusRequester() }
    // Both layouts are kept, so expanding only fades between them. Building one mid-animation
    // stalled it. The full screen is built just after the panel opens, so opening stays quick.
    var warm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(600)
        warm = true
    }

    val map = state?.map
    LaunchedEffect(map) {
        if (map == null) return@LaunchedEffect
        picture = withContext(Dispatchers.IO) {
            home.mapImage(map)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.trimmed()?.asImageBitmap() }
        } ?: picture
    }
    LaunchedEffect(home, reload) {
        while (true) {
            state = withContext(Dispatchers.IO) { home.read() }
            delay(4_000)
        }
    }
    fun command(run: () -> String?) {
        scope.launch {
            failed = withContext(Dispatchers.IO) { run() }
            delay(800) // give Home Assistant a moment to see the change
            reload++
        }
    }

    val reading = heating as? HeatingState.Reading
    val model = PanelModel(
        cfg, state, picture, reading?.heating, reading?.problem, failed ?: state?.problem,
        start = { command { home.vacuum("start") } },
        pause = { command { home.vacuum("pause") } },
        dock = { command { home.vacuum("return_to_base") } },
        toggle = { d -> state?.devices?.firstOrNull { it.device == d }?.on?.let { on -> command { home.set(d, !on) } } },
    )
    fun resize() {
        toggled = true
        expanded = !expanded
    }

    Dialog(
        onDismissRequest = { if (expanded) resize() else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Expanding grows the panel's background out to the edges while the contents cross over.
        // One filled rectangle and two fades, all in the draw phase: anything more drops frames.
        val progress = animateFloatAsState(if (expanded) 1f else 0f, tween(360, easing = FastOutSlowInEasing), label = "size")
        Box(
            Modifier.fillMaxSize().drawBehind {
                val p = progress.value
                val inset = 12.dp.toPx() * (1 - p)
                val left = (size.width - 408.dp.toPx()) * (1 - p)
                drawRoundRect(
                    lerp(SideColor, FullColor, p), Offset(left, inset),
                    Size(size.width - left - inset, size.height - 2 * inset), CornerRadius(30.dp.toPx() * (1 - p)),
                )
            },
        ) {
            Layer(shown = !expanded, alpha = { (1 - progress.value * 2).coerceIn(0f, 1f) }) {
                SidePanel(model, Modifier.focusRequester(sideSize), Modifier.focusRequester(first), ::resize)
            }
            if (warm || expanded) {
                Layer(shown = expanded, alpha = { (progress.value * 2 - 1).coerceIn(0f, 1f) }) {
                    FullHome(model, Modifier.focusRequester(fullSize), ::resize)
                }
            }
        }
        LaunchedEffect(expanded) {
            delay(80)
            val target = when {
                expanded -> fullSize
                toggled || cfg.vacuum.isBlank() -> sideSize
                else -> first
            }
            runCatching { target.requestFocus() }
        }
    }
}

/** False inside the hidden layout. Read by every button: lists don't pass a parent's focus rule on. */
private val LocalReachable = compositionLocalOf { true }

/** One of the two layouts. While not [shown] the remote can't reach it. */
@Composable
private fun Layer(shown: Boolean, alpha: () -> Float, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            compositingStrategy = CompositingStrategy.ModulateAlpha
            this.alpha = alpha()
        },
        contentAlignment = Alignment.CenterEnd,
    ) { CompositionLocalProvider(LocalReachable provides shown, content = content) }
}

// ---------- Side panel ----------

@Composable
private fun SidePanel(m: PanelModel, sizing: Modifier, initial: Modifier, onExpand: () -> Unit) {
    Column(
        Modifier.padding(12.dp).width(396.dp).fillMaxHeight().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Home", 20.sp, FontWeight.Bold, shadow = false)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (m.lightsOn > 0) Txt(lights(m.lightsOn), 12.sp, FontWeight.Medium, color = Dim, shadow = false)
            }
            RoundButton(Icons.Filled.OpenInFull, "Expand to full screen", sizing, 36.dp, onClick = onExpand)
        }

        m.heating?.let { h ->
            Row(
                Modifier.fillMaxWidth().height(108.dp).background(Grey, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dial(h, 86.dp, 8.dp) { Txt(deg(h.current), 19.sp, FontWeight.SemiBold, shadow = false) }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Txt(h.zone, 15.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
                    Txt(heatingLine(h), 13.sp, FontWeight.SemiBold, color = if (h.active) Amber else Color.White, shadow = false)
                    val extra = listOfNotNull(
                        h.nextAt?.let { "Next ${clock(it)} → ${deg(h.nextTarget)}" },
                        h.humidity?.let { "${it.roundToInt()}% humidity" },
                    ).joinToString(" · ")
                    Txt(m.stale?.let { "Stale · $it" } ?: extra, 12.sp, color = Dim, maxLines = 1, shadow = false)
                }
            }
        }

        if (m.cfg.vacuum.isNotBlank()) {
            // Without lights or plugs the vacuum gets the rest of the panel.
            val size = if (m.cfg.devices.isEmpty()) Modifier.weight(1f) else Modifier.height(226.dp)
            Column(size.fillMaxWidth().background(Grey, RoundedCornerShape(22.dp)).padding(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("Robot", 15.sp, FontWeight.SemiBold, Modifier.weight(1f), shadow = false)
                    VacuumStatus(m)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxSize()) {
                    MapTile(m.map, Color(0xFF1C1C1E), Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
                        RoundButton(Icons.Filled.PlayArrow, "Start cleaning", initial, 50.dp, Color(0xFF0A84FF), m.start)
                        RoundButton(Icons.Filled.Pause, "Pause", Modifier, 50.dp, onClick = m.pause)
                        RoundButton(Icons.Filled.Home, "Return to dock", Modifier, 50.dp, onClick = m.dock)
                    }
                }
            }
        }

        if (m.cfg.devices.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(m.cfg.devices) { d ->
                    DeviceTile(d, m.on(d), Modifier.width(116.dp).fillMaxHeight(), Grey, wide = false) { m.toggle(d) }
                }
            }
        }

        m.problem?.let { Problem(it) }
    }
}

// ---------- Full screen ----------

@Composable
private fun FullHome(m: PanelModel, sizing: Modifier, onShrink: () -> Unit) {
    val card = Color(0xFF1C1C1E)
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt("Home", 28.sp, FontWeight.Bold, Modifier.weight(1f), shadow = false)
            m.heating?.let { Pill("${deg(it.current)} ${it.zone}") }
            if (m.lightsOn > 0) Pill(lights(m.lightsOn))
            m.vacuum?.let { Pill("Vacuum ${it.lowercase()}") }
            Spacer(Modifier.width(8.dp))
            RoundButton(Icons.Filled.CloseFullscreen, "Shrink to side panel", sizing, 36.dp, onClick = onShrink)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            m.heating?.let { h ->
                Section("Climate", Modifier.width(270.dp)) {
                    Column(
                        Modifier.fillMaxSize().background(card, RoundedCornerShape(26.dp)).padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Dial(h, 212.dp, 18.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (h.active) Txt("HEATING", 12.sp, FontWeight.Bold, color = Amber, shadow = false)
                                Txt(deg(h.current), 40.sp, FontWeight.SemiBold, shadow = false)
                                Txt("Set to ${deg(h.target)}", 13.sp, FontWeight.Medium, color = Dim, shadow = false)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        h.nextAt?.let { Line("Next", "${clock(it)} → ${deg(h.nextTarget)}") }
                        h.humidity?.let { Line("Humidity", "${it.roundToInt()}%") }
                        m.stale?.let { Line("Stale", it) }
                    }
                }
            }
            if (m.cfg.vacuum.isNotBlank()) {
                Section("Robot", Modifier.width(366.dp), trailing = { VacuumStatus(m) }) {
                    Column(Modifier.fillMaxSize().background(card, RoundedCornerShape(26.dp)).padding(14.dp)) {
                        MapTile(m.map, card, Modifier.fillMaxWidth().weight(1f))
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("Start", Modifier.weight(1f), Color(0xFF0A64D6), m.start)
                            PillButton("Pause", Modifier.weight(1f), Color(0xFF3A3A3C), m.pause)
                            PillButton("Dock", Modifier.weight(1f), Color(0xFF3A3A3C), m.dock)
                        }
                    }
                }
            }
            if (m.cfg.devices.isNotEmpty()) {
                Section("Lights & plugs", Modifier.weight(1f)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(m.cfg.devices) { d ->
                            DeviceTile(d, m.on(d), Modifier.fillMaxWidth().height(74.dp), Color(0xFF2A3550), wide = true) { m.toggle(d) }
                        }
                    }
                }
            } else if (m.state?.care?.isNotEmpty() == true) {
                Section("Robot care", Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        m.state.care.forEach { (name, left) ->
                            Column(Modifier.fillMaxWidth().background(card, RoundedCornerShape(22.dp)).padding(14.dp, 10.dp)) {
                                Txt(name, 12.sp, FontWeight.Medium, color = Dim, maxLines = 1, shadow = false)
                                Txt(left, 20.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
                            }
                        }
                    }
                }
            }
        }
        m.problem?.let { Problem(it) }
    }
}

// ---------- Pieces ----------

private fun lights(n: Int) = if (n == 1) "1 light on" else "$n lights on"

private fun heatingLine(h: Heating) = when {
    h.target == null -> "Off"
    h.active -> "Heating to ${deg(h.target)}"
    else -> "Set to ${deg(h.target)}"
}

private fun clock(millis: Long): String = java.time.Instant.ofEpochMilli(millis)
    .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

/** A read-only gauge from 5° to 25°. */
@Composable
private fun Dial(h: Heating, size: Dp, stroke: Dp, content: @Composable () -> Unit) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val arc = Size(this.size.width - width, this.size.height - width)
            val corner = Offset(width / 2, width / 2)
            val style = Stroke(width, cap = StrokeCap.Round)
            drawArc(Color(0xFF48484A), 135f, 270f, false, corner, arc, style = style)
            val part = (((h.current ?: 5.0) - 5) / 20).toFloat().coerceIn(0f, 1f)
            drawArc(if (h.active) Amber else Color(0xFF8E8E93), 135f, 270f * part, false, corner, arc, style = style)
        }
        content()
    }
}

@Composable
private fun VacuumStatus(m: PanelModel) {
    val battery = m.state?.vacuum?.battery?.let { "$it%" }
    val text = listOfNotNull(m.vacuum ?: "…", battery, m.state?.floor).joinToString(" · ")
    Txt(text, 12.sp, FontWeight.Medium, color = Dim, maxLines = 1, shadow = false)
}

@Composable
private fun MapTile(map: ImageBitmap?, color: Color, modifier: Modifier) {
    Box(modifier.background(color, RoundedCornerShape(16.dp)).padding(6.dp), contentAlignment = Alignment.Center) {
        if (map != null) Image(map, "Vacuum map", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Txt("No map", 12.sp, color = Dim, shadow = false)
    }
}

@Composable
private fun Section(
    title: String, modifier: Modifier, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, 14.sp, FontWeight.SemiBold, Modifier.weight(1f), shadow = false)
            trailing()
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Pill(text: String) {
    Box(Modifier.padding(start = 8.dp).background(Color(0xFF2A3550), RoundedCornerShape(20.dp)).padding(12.dp, 7.dp)) {
        Txt(text, 12.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Txt(label, 13.sp, modifier = Modifier.weight(1f), color = Dim, shadow = false)
        Txt(value, 13.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
    }
}

@Composable
private fun Problem(text: String) =
    Txt(text.replaceFirstChar { it.uppercase() }, 12.sp, color = Amber, maxLines = 2, shadow = false)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelSurface(
    onClick: () -> Unit, modifier: Modifier, shape: Shape, color: Color, content: @Composable BoxScope.() -> Unit,
) {
    val reachable = LocalReachable.current
    Surface(
        onClick = onClick,
        modifier = modifier.focusProperties { canFocus = reachable },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color, focusedContainerColor = if (color == Color.White) color else Color(0xFF636366),
            contentColor = Color.White, focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
        content = content,
    )
}

@Composable
private fun RoundButton(
    icon: ImageVector, label: String, modifier: Modifier, size: Dp,
    color: Color = Color(0xFF48484A), onClick: () -> Unit,
) {
    PanelSurface(onClick, modifier.size(size), CircleShape, color) {
        Image(icon, label, Modifier.align(Alignment.Center).size(size * 0.46f), colorFilter = ColorFilter.tint(Color.White))
    }
}

@Composable
private fun PillButton(label: String, modifier: Modifier, color: Color, onClick: () -> Unit) {
    PanelSurface(onClick, modifier.height(44.dp), RoundedCornerShape(22.dp), color) {
        Txt(label, 14.sp, FontWeight.SemiBold, Modifier.align(Alignment.Center), shadow = false)
    }
}

/** White when on, as in Apple's Home app. */
@Composable
private fun DeviceTile(d: HaDevice, on: Boolean?, modifier: Modifier, offColor: Color, wide: Boolean, onClick: () -> Unit) {
    val lit = on == true
    val text = if (lit) Color(0xFF1C1C1E) else Color.White
    val status = when (on) { true -> "On"; false -> "Off"; null -> "Unavailable" }
    val icon = if (d.entity.startsWith("light.")) Icons.Filled.Lightbulb else Icons.Filled.Power
    PanelSurface(onClick, modifier, RoundedCornerShape(22.dp), if (lit) Color.White else offColor) {
        val badge: @Composable () -> Unit = {
            Box(
                Modifier.size(if (wide) 38.dp else 28.dp).background(if (lit) Color(0xFFFFD60A) else Color(0xFF48484A), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Image(icon, null, Modifier.size(if (wide) 22.dp else 17.dp), colorFilter = ColorFilter.tint(text)) }
        }
        val labels: @Composable () -> Unit = {
            Column {
                Txt(d.name, 13.sp, FontWeight.SemiBold, color = text, maxLines = 1, shadow = false)
                Txt(status, 12.sp, FontWeight.Medium, color = if (lit) Color(0xFF636366) else Dim, maxLines = 1, shadow = false)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                badge()
                Spacer(Modifier.width(12.dp))
                labels()
            }
        } else {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                badge()
                labels()
            }
        }
    }
}

/** Roborock maps sit in a wide transparent border; cut to what is drawn. */
private fun Bitmap.trimmed(): Bitmap {
    val row = IntArray(width)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            if (row[x] ushr 24 == 0) continue
            left = minOf(left, x)
            right = maxOf(right, x)
            top = minOf(top, y)
            bottom = y
        }
    }
    return if (right < 0) this else Bitmap.createBitmap(this, left, top, right - left + 1, bottom - top + 1)
}
