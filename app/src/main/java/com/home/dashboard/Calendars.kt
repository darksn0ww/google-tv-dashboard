package com.home.dashboard

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class CalEvent(
    val title: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean,
    val color: Int,
    val calendar: String,
    val location: String = "",
    val notes: String = "",
)

/** Minimal iCalendar (.ics / webcal) reader with common RRULE support. */
object Calendars {
    private val palette = intArrayOf(
        0xFF0A84FF.toInt(), 0xFFFF9F0A.toInt(), 0xFF30D158.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(),
    )
    private val dtFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val local: ZoneId get() = ZoneId.systemDefault()

    fun fetchAll(urls: List<String>, from: ZonedDateTime, to: ZonedDateTime): List<CalEvent> =
        urls.flatMapIndexed { i, u ->
            val https = u.trim().replaceFirst(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")
            parse(Net.get(https), palette[i % palette.size], from, to)
        }.sortedWith(compareBy<CalEvent>({ it.start }, { !it.allDay }))

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + raw.substring(1)
            } else if (raw.isNotEmpty()) out += raw
        }
        return out
    }

    private fun parseLine(line: String): Prop? {
        var inQ = false
        var colon = -1
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') inQ = !inQ else if (ch == ':' && !inQ) { colon = i; break }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { p ->
            val eq = p.indexOf('=')
            if (eq < 0) null else p.substring(0, eq).uppercase() to p.substring(eq + 1).trim('"')
        }.toMap()
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun unescape(s: String) =
        s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun parseColor(v: String): Int? {
        val h = v.trim().removePrefix("#")
        if (h.length < 6) return null
        return h.substring(0, 6).toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
    }

    private fun parseDt(v: String, params: Map<String, String>): Pair<ZonedDateTime, Boolean> {
        val s = v.trim()
        if (params["VALUE"] == "DATE" || s.length == 8) {
            return LocalDate.parse(s.take(8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(local) to true
        }
        if (s.endsWith("Z")) {
            return LocalDateTime.parse(s.dropLast(1).take(15), dtFmt).atZone(ZoneOffset.UTC).withZoneSameInstant(local) to false
        }
        val zone = params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: local
        return LocalDateTime.parse(s.take(15), dtFmt).atZone(zone).withZoneSameInstant(local) to false
    }

    private fun parseDuration(v: String): Duration {
        val s = v.trim().removePrefix("+")
        Regex("^P(\\d+)W$").find(s)?.let { return Duration.ofDays(7L * it.groupValues[1].toLong()) }
        return Duration.parse(s)
    }

    fun parse(text: String, defaultColor: Int, from: ZonedDateTime, to: ZonedDateTime): List<CalEvent> {
        var calName = ""
        var color = defaultColor
        val raw = mutableListOf<Map<String, List<Prop>>>()
        var cur: MutableMap<String, MutableList<Prop>>? = null
        var nested = 0
        for (l in unfold(text)) {
            val p = parseLine(l) ?: continue
            when {
                p.name == "BEGIN" && p.value.equals("VEVENT", true) -> { cur = mutableMapOf(); nested = 0 }
                p.name == "END" && p.value.equals("VEVENT", true) -> { cur?.let { raw += it }; cur = null }
                cur != null && p.name == "BEGIN" -> nested++
                cur != null && p.name == "END" -> nested--
                cur != null && nested == 0 -> cur.getOrPut(p.name) { mutableListOf() }.add(p)
                cur == null && p.name == "X-WR-CALNAME" -> calName = unescape(p.value)
                cur == null && p.name == "X-APPLE-CALENDAR-COLOR" -> parseColor(p.value)?.let { color = it }
            }
        }

        fun first(e: Map<String, List<Prop>>, n: String) = e[n]?.firstOrNull()

        // Instances that were moved/edited/cancelled individually
        val overrides = HashSet<String>()
        for (e in raw) {
            val rid = first(e, "RECURRENCE-ID") ?: continue
            val uid = first(e, "UID")?.value ?: continue
            runCatching { overrides += uid + "|" + parseDt(rid.value, rid.params).first.toEpochSecond() }
        }

        val out = mutableListOf<CalEvent>()
        for (e in raw) {
            val ds = first(e, "DTSTART") ?: continue
            val (start, allDay) = runCatching { parseDt(ds.value, ds.params) }.getOrNull() ?: continue
            if (first(e, "STATUS")?.value.equals("CANCELLED", true)) continue
            val end = first(e, "DTEND")?.let { runCatching { parseDt(it.value, it.params).first }.getOrNull() }
                ?: first(e, "DURATION")?.let { runCatching { start.plus(parseDuration(it.value)) }.getOrNull() }
                ?: if (allDay) start.plusDays(1) else start.plusHours(1)
            val length = Duration.between(start, end).let { if (it.isNegative) Duration.ZERO else it }
            val title = first(e, "SUMMARY")?.let { unescape(it.value) }?.ifBlank { null } ?: "Busy"
            val uid = first(e, "UID")?.value ?: ""
            val location = first(e, "LOCATION")?.let { unescape(it.value).trim() }.orEmpty()
            val notes = first(e, "DESCRIPTION")?.let { unescape(it.value).trim().take(400) }.orEmpty()
            val rrule = first(e, "RRULE")
            val starts = if (rrule == null || first(e, "RECURRENCE-ID") != null) listOf(start) else {
                val ex = e["EXDATE"].orEmpty().flatMap { p ->
                    p.value.split(',').mapNotNull { v -> runCatching { parseDt(v, p.params).first.toEpochSecond() }.getOrNull() }
                }.toSet()
                expand(start, rrule.value, from.minus(length), to)
                    .filter { it.toEpochSecond() !in ex && (uid + "|" + it.toEpochSecond()) !in overrides }
            }
            for (s in starts) {
                val en = s.plus(length)
                if (en.isAfter(from) && s.isBefore(to)) {
                    out += CalEvent(title, s, en, allDay, color, calName, location, notes)
                }
            }
        }
        return out
    }

    private fun dow(s: String): DayOfWeek = when (s) {
        "MO" -> DayOfWeek.MONDAY; "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY; "FR" -> DayOfWeek.FRIDAY; "SA" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }

    private fun expand(start: ZonedDateTime, rule: String, from: ZonedDateTime, to: ZonedDateTime): List<ZonedDateTime> {
        val r = rule.split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i).uppercase() to it.substring(i + 1)
        }.toMap()
        val freq = r["FREQ"]?.uppercase() ?: return listOf(start)
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return listOf(start)
        val interval = r["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = r["COUNT"]?.toIntOrNull()
        val until = r["UNTIL"]?.let { runCatching { parseDt(it, emptyMap()).first }.getOrNull() }
        val zone = start.zone
        val base = start.toLocalDateTime()
        val time = base.toLocalTime()
        val byDay = r["BYDAY"]?.split(',')?.mapNotNull { d ->
            Regex("^([+-]?\\d*)(MO|TU|WE|TH|FR|SA|SU)$").find(d.trim().uppercase())?.let { m ->
                (m.groupValues[1].toIntOrNull() ?: 0) to dow(m.groupValues[2])
            }
        }.orEmpty()
        val byMonthDay = r["BYMONTHDAY"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val byMonth = r["BYMONTH"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val bySetPos = r["BYSETPOS"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

        fun setPos(list: List<LocalDate>): List<LocalDate> =
            if (bySetPos.isEmpty()) list
            else bySetPos.mapNotNull { sp -> if (sp > 0) list.getOrNull(sp - 1) else list.getOrNull(list.size + sp) }.sorted()

        // Days in a month matching BYMONTHDAY / BYDAY (incl. "2TU", "-1FR"); defaults to DTSTART's day.
        fun monthDays(ym: YearMonth): List<LocalDate> {
            val len = ym.lengthOfMonth()
            if (byDay.isEmpty() && byMonthDay.isEmpty()) {
                return if (base.dayOfMonth <= len) listOf(ym.atDay(base.dayOfMonth)) else emptyList()
            }
            return (1..len).map { ym.atDay(it) }.filter { d ->
                (byMonthDay.isEmpty() || byMonthDay.any { md -> if (md > 0) d.dayOfMonth == md else d.dayOfMonth == len + md + 1 }) &&
                    (byDay.isEmpty() || byDay.any { (nth, w) ->
                        d.dayOfWeek == w && when {
                            nth > 0 -> (d.dayOfMonth - 1) / 7 + 1 == nth
                            nth < 0 -> (len - d.dayOfMonth) / 7 + 1 == -nth
                            else -> true
                        }
                    })
            }
        }

        fun period(p: Long): List<LocalDateTime> = when (freq) {
            "DAILY" -> listOf(base.plusDays(p * interval))
            "WEEKLY" -> if (byDay.isEmpty()) listOf(base.plusWeeks(p * interval)) else {
                val ws = base.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(p * interval)
                byDay.map { ws.plusDays((it.second.value - 1).toLong()).atTime(time) }.sorted()
            }
            "MONTHLY" -> {
                val ym = YearMonth.from(base).plusMonths(p * interval)
                if (byMonth.isNotEmpty() && ym.monthValue !in byMonth) emptyList()
                else setPos(monthDays(ym)).map { it.atTime(time) }
            }
            else -> { // YEARLY
                val year = base.year + p * interval
                val months = byMonth.ifEmpty { listOf(base.monthValue) }
                setPos(months.sorted().flatMap { monthDays(YearMonth.of(year.toInt(), it)) }).map { it.atTime(time) }
            }
        }

        val out = mutableListOf<ZonedDateTime>()
        val endLdt = to.withZoneSameInstant(zone).toLocalDateTime()
        var n = 0
        var p = 0L
        loop@ while (p < 200_000) {
            val periodStart = when (freq) {
                "DAILY" -> base.plusDays(p * interval)
                "WEEKLY" -> base.plusWeeks(p * interval)
                "MONTHLY" -> base.withDayOfMonth(1).plusMonths(p * interval)
                else -> base.withDayOfYear(1).plusYears(p * interval)
            }
            if (periodStart.toLocalDate().minusDays(1).atStartOfDay().isAfter(endLdt)) break
            for (c in period(p++)) {
                if (c.isBefore(base)) continue
                if (c.isAfter(endLdt)) break@loop
                val z = c.atZone(zone)
                if (until != null && z.isAfter(until)) break@loop
                n++
                if (count != null && n > count) break@loop
                if (!z.isBefore(from)) out += z
            }
        }
        return out
    }
}
