package com.home.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

private val Time = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val LongDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
private val ShortDay = DateTimeFormatter.ofPattern("EEE", Locale.UK)
private val Month = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)
private val ShortMonth = DateTimeFormatter.ofPattern("MMM", Locale.UK)

// ---------- Calendar ----------

/** Whether [e] touches [day], including all-day and multi-day events. */
fun occursOn(e: CalEvent, day: LocalDate): Boolean {
    val from = day.atStartOfDay(e.start.zone)
    val to = day.plusDays(1).atStartOfDay(e.start.zone)
    return e.start.isBefore(to) && (e.end.isAfter(from) || e.start == from)
}

/** Whole weeks covering today and the following 29 days. */
fun calendarWeeks(today: LocalDate, firstDayOfWeek: java.time.DayOfWeek): List<List<LocalDate>> {
    val start = today.minusDays(((today.dayOfWeek.value - firstDayOfWeek.value + 7) % 7).toLong())
    val last = today.plusDays(29)
    return generateSequence(start) { it.plusDays(7) }.takeWhile { !it.isAfter(last) }
        .map { week -> (0L..6L).map { week.plusDays(it) } }.toList()
}

/** Month grid with event dots on the left, the selected day's events on the right. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CalendarScreen(events: List<CalEvent>, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val today = remember { LocalDate.now() }
    val last = today.plusDays(29)
    val weeks = remember(today) { calendarWeeks(today, WeekFields.of(Locale.getDefault()).firstDayOfWeek) }
    var selected by remember { mutableStateOf(today) }
    val todayFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { todayFocus.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(Color(0xFF0E0E10)).padding(horizontal = 40.dp, vertical = 28.dp)) {
        Column(Modifier.width(520.dp)) {
            Txt(selected.format(Month), 24.sp, FontWeight.SemiBold, shadow = false)
            Spacer(Modifier.height(12.dp))
            Row {
                weeks.first().forEach { d ->
                    Box(Modifier.width(74.dp), contentAlignment = Alignment.Center) {
                        Txt(d.format(ShortDay).uppercase(), 11.sp, FontWeight.SemiBold, alpha = 0.45f, shadow = false)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            weeks.forEach { week ->
                Row(Modifier.height(if (weeks.size > 5) 66.dp else 74.dp)) {
                    week.forEach { day ->
                        val inRange = !day.isBefore(today) && !day.isAfter(last)
                        val dots = if (inRange) events.filter { occursOn(it, day) }.map { it.color }.take(4) else emptyList()
                        val cell: @Composable () -> Unit = {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    Modifier.size(30.dp).background(if (day == today) IosRed else Color.Transparent, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Txt(day.dayOfMonth.toString(), 16.sp, FontWeight.SemiBold, alpha = if (inRange) 1f else 0.25f, shadow = false)
                                }
                                if (day.dayOfMonth == 1) Txt(day.format(ShortMonth), 9.sp, FontWeight.SemiBold, alpha = 0.6f, shadow = false)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    dots.forEach { Box(Modifier.size(6.dp).background(Color(it), CircleShape)) }
                                }
                            }
                        }
                        val size = Modifier.width(74.dp).fillMaxHeight().padding(2.dp)
                        if (!inRange) {
                            Box(size) { cell() }
                        } else {
                            val shape = RoundedCornerShape(12.dp)
                            Surface(
                                onClick = { selected = day },
                                modifier = (if (day == today) size.focusRequester(todayFocus) else size)
                                    .onFocusChanged { if (it.isFocused) selected = day },
                                shape = ClickableSurfaceDefaults.shape(shape),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (day == selected) Color(0xFF2C2C2E) else Color.Transparent,
                                    focusedContainerColor = Color(0xFF48484A),
                                    contentColor = Color.White, focusedContentColor = Color.White,
                                ),
                                border = ClickableSurfaceDefaults.border(
                                    focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)), shape = shape),
                                ),
                            ) { cell() }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(28.dp))
        Column(Modifier.fillMaxSize()) {
            val title = when (selected) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                else -> selected.format(LongDay)
            }
            Txt(title, 20.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
            Spacer(Modifier.height(12.dp))
            val day = events.filter { occursOn(it, selected) }
            if (day.isEmpty()) Txt("No events", 15.sp, FontWeight.Medium, alpha = 0.5f, shadow = false)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(day) { EventDetail(it, selected) }
            }
        }
    }
}

private fun timeRange(e: CalEvent, day: LocalDate): String {
    if (e.allDay) return "All day"
    fun at(t: ZonedDateTime) = if (t.toLocalDate() == day) t.format(Time) else "${t.format(ShortDay)} ${t.format(Time)}"
    return "${at(e.start)} – ${at(e.end)}"
}

@Composable
private fun EventDetail(e: CalEvent, day: LocalDate) =
    FocusCard(Modifier.fillMaxWidth(), Color(0xFF1C1C1E)) {
        Row(Modifier.padding(12.dp)) {
            Box(Modifier.padding(top = 3.dp).width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(Color(e.color)))
            Spacer(Modifier.width(10.dp))
            Column {
                Txt(e.title, 15.sp, FontWeight.SemiBold, maxLines = 2, shadow = false)
                Txt(timeRange(e, day), 12.sp, FontWeight.Medium, alpha = 0.7f, shadow = false)
                if (e.location.isNotBlank()) Txt(e.location, 12.sp, alpha = 0.7f, maxLines = 2, shadow = false)
                if (e.notes.isNotBlank()) Txt(e.notes, 11.sp, alpha = 0.55f, maxLines = 4, shadow = false)
                if (e.calendar.isNotBlank()) Txt(e.calendar, 11.sp, FontWeight.Medium, alpha = 0.45f, maxLines = 1, shadow = false)
            }
        }
    }

/** A card the remote can land on, so lists can be scrolled. */
@Composable
private fun FocusCard(modifier: Modifier, color: Color, content: @Composable ColumnScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White.copy(alpha = 0.22f) else color)
            .focusable(interactionSource = source),
        content = content,
    )
}

// ---------- Weather ----------

private val Panel = Color.Black.copy(alpha = 0.22f)

private fun compass(degrees: Int) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((degrees + 22) / 45) % 8]

private fun uvLabel(uv: Int) = when {
    uv <= 2 -> "Low"
    uv <= 5 -> "Moderate"
    uv <= 7 -> "High"
    uv <= 10 -> "Very high"
    else -> "Extreme"
}

data class WeatherTile(val label: String, val value: String, val note: String)

/** The six detail tiles for day [index]; today shows conditions as they are now. */
fun dayTiles(w: Weather, index: Int): List<WeatherTile> {
    val d = w.days.getOrNull(index)
    val today = index == 0
    return listOf(
        if (today) WeatherTile("FEELS LIKE", "${w.feels}°", "Now")
        else WeatherTile("FEELS LIKE", "${d?.feelsHi ?: 0}°", "Low ${d?.feelsLo ?: 0}°"),
        if (today) WeatherTile("HUMIDITY", "${w.humidity}%", "Now")
        else WeatherTile("HUMIDITY", "${d?.humidity ?: 0}%", "Average"),
        if (today) WeatherTile("WIND", "${w.wind} ${w.windUnit}", "From ${compass(w.windDirection)}")
        else WeatherTile("WIND", "${d?.wind ?: 0} ${w.windUnit}", "Up to, from ${compass(d?.windDirection ?: 0)}"),
        WeatherTile("UV INDEX", "${d?.uv ?: 0}", uvLabel(d?.uv ?: 0)),
        WeatherTile("SUNRISE", d?.sunrise?.format(Time) ?: "--", "Sunset ${d?.sunset?.format(Time) ?: "--"}"),
        WeatherTile("RAINFALL", "${((d?.rainMm ?: 0.0) * 10).roundToInt() / 10.0} mm", "${d?.rain ?: 0}% chance"),
    )
}

/** Current conditions and the next 12 hours; choose one of ten days to see its details. */
@Composable
fun WeatherScreen(w: Weather, place: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val first = remember { FocusRequester() }
    var chosen by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    val now = remember { LocalDateTime.now() }
    val hours = w.hours.filter { it.time.isAfter(now.minusHours(1)) }.take(12)

    Column(Modifier.fillMaxSize().background(weatherBrush(w.code, w.isDay)).padding(horizontal = 40.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(place.ifBlank { "Weather" }, 24.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
                Txt("${weatherLabel(w.code)} · H:${w.hi}° L:${w.lo}°", 15.sp, FontWeight.Medium, shadow = false)
            }
            WIcon(w.code, w.isDay, 36.dp)
            Spacer(Modifier.width(14.dp))
            Txt("${w.temp}°", 60.sp, FontWeight.Medium, lineHeight = 66.sp, shadow = false)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(vertical = 8.dp)) {
            hours.forEachIndexed { i, h ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(if (i == 0) "Now" else h.time.format(Time), 11.sp, FontWeight.SemiBold, shadow = false)
                    Spacer(Modifier.height(5.dp))
                    WIcon(h.code, h.isDay, 20.dp)
                    Spacer(Modifier.height(2.dp))
                    Txt(if (h.rain >= 10) "${h.rain}%" else " ", 10.sp, FontWeight.SemiBold, color = Color(0xFF9AD7FF), shadow = false)
                    Txt("${h.temp}°", 15.sp, FontWeight.SemiBold, shadow = false)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(460.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Panel).padding(8.dp, 8.dp)) {
                Txt("10-DAY FORECAST", 11.sp, FontWeight.SemiBold, Modifier.padding(start = 6.dp), alpha = 0.6f, shadow = false)
                val low = w.days.minOfOrNull { it.lo } ?: 0
                val span = ((w.days.maxOfOrNull { it.hi } ?: 1) - low).coerceAtLeast(1)
                w.days.forEachIndexed { i, d ->
                    Row(
                        Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (i == chosen) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { if (it.isFocused) chosen = i }
                            .focusable()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(if (i == 0) "Today" else d.date.format(ShortDay), 13.sp, FontWeight.SemiBold, Modifier.width(52.dp), shadow = false)
                        WIcon(d.code, true, 16.dp)
                        Txt(if (d.rain >= 10) "${d.rain}%" else "", 10.sp, FontWeight.SemiBold, Modifier.width(36.dp).padding(start = 6.dp), color = Color(0xFF9AD7FF), shadow = false)
                        Txt("${d.lo}°", 13.sp, FontWeight.Medium, Modifier.width(32.dp), alpha = 0.65f, shadow = false)
                        BoxWithConstraints(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.25f))) {
                            val from = (d.lo - low).toFloat() / span
                            val width = ((d.hi - d.lo).toFloat() / span).coerceAtLeast(0.04f)
                            Box(
                                Modifier.offset(x = maxWidth * from).width(maxWidth * width).fillMaxHeight().clip(CircleShape)
                                    .background(Brush.horizontalGradient(listOf(Color(0xFF64D2FF), Color(0xFFFF9F0A)))),
                            )
                        }
                        Txt("${d.hi}°", 13.sp, FontWeight.SemiBold, Modifier.padding(start = 10.dp).width(28.dp), shadow = false)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val date = w.days.getOrNull(chosen)?.date
                Txt(if (chosen == 0 || date == null) "Today" else date.format(LongDay), 16.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
                val tiles = dayTiles(w, chosen)
                tiles.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (label, value, note) ->
                            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp, 8.dp)) {
                                Txt(label, 11.sp, FontWeight.SemiBold, alpha = 0.6f, shadow = false)
                                Txt(value, 24.sp, FontWeight.Medium, maxLines = 1, shadow = false)
                                if (note.isNotEmpty()) Txt(note, 12.sp, FontWeight.Medium, alpha = 0.8f, maxLines = 1, shadow = false)
                            }
                        }
                    }
                }
            }
        }
    }
}
