@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.home.dashboard

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

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
    onWeather: () -> Unit, onCalendar: () -> Unit, weatherModifier: Modifier, calendarModifier: Modifier,
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
        WeatherCard(weather, place, onWeather, weatherModifier.width(if (third) 292.dp else 372.dp).fillMaxHeight())
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
        val rest = calendarModifier.weight(1f).fillMaxHeight()
        if (hasCalendars) CalendarCard(events, now.toLocalDate(), onCalendar, rest)
        else MonthCard(now.toLocalDate(), onCalendar, rest)
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
fun WeatherCard(w: Weather?, place: String, onClick: () -> Unit, modifier: Modifier) =
    ButtonCard(onClick, modifier, background = w?.let { weatherBrush(it.code, it.isDay) }) {
        val title = place.ifBlank { "Weather" }
        if (w == null) {
            Txt(title, 15.sp, FontWeight.SemiBold, shadow = false)
            Txt("--°", 46.sp, FontWeight.Medium, shadow = false)
            return@ButtonCard
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

/** A widget you can select. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ButtonCard(
    onClick: () -> Unit, modifier: Modifier, background: Brush? = null, content: @Composable ColumnScope.() -> Unit,
) {
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
        Box(Modifier.fillMaxSize()) {
            if (background != null) Box(Modifier.matchParentSize().background(background))
            Column(Modifier.fillMaxSize().padding(17.dp), content = content)
        }
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
        val note = if (h.boost) " · boost" else if (h.away) " · away" else if (h.eco) " · eco" else if (h.manual) " · manual" else ""
        val target = if (h.auto) "Auto" else "Set to ${deg(h.target)}$range"
        Txt("$target$note", 13.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
        Spacer(Modifier.weight(1f))
        h.nextAt?.let {
            // Within a day the time alone is clear, and a weekday wouldn't fit beside the temperature.
            val soon = it - System.currentTimeMillis() < 24 * 60 * 60_000L
            val at = if (soon) clockTime(it) else readingTime(it)
            Txt("Next: $at → ${deg(h.nextTarget)}", 13.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
        }
        val humidity = h.humidity?.let { "${it.roundToInt()}%" }
        if (h.active || h.cooling) {
            val doing = if (h.cooling) "Cooling" else "Heating"
            val tint = if (h.cooling) Color(0xFF64D2FF) else Color(0xFFFF9F0A)
            Txt(listOfNotNull(doing, humidity?.let { "$it RH" }).joinToString(" · "), 12.sp, FontWeight.SemiBold, color = tint, maxLines = 1, shadow = false)
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
fun MonthCard(today: LocalDate, onClick: () -> Unit, modifier: Modifier) =
    ButtonCard(onClick, modifier) {
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
fun CalendarCard(events: List<CalEvent>, today: LocalDate, onClick: () -> Unit, modifier: Modifier) =
    ButtonCard(onClick, modifier) {
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
