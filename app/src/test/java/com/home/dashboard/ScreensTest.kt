package com.home.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime

class ScreensTest {
    @Test fun calendarDetailsAndDays() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Trip
            DTSTART;VALUE=DATE:20260110
            DTEND;VALUE=DATE:20260112
            LOCATION:York\, UK
            DESCRIPTION:Bring\nboots
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val from = ZonedDateTime.parse("2026-01-01T00:00:00Z")
        val e = Calendars.parse(ics, 0, from, from.plusDays(31)).single()
        assertEquals("York, UK", e.location)
        assertEquals("Bring boots", e.notes)
        assertFalse(occursOn(e, LocalDate.of(2026, 1, 9)))
        assertTrue(occursOn(e, LocalDate.of(2026, 1, 10)))
        assertTrue(occursOn(e, LocalDate.of(2026, 1, 11)))
        assertFalse(occursOn(e, LocalDate.of(2026, 1, 12)))
    }

    @Test fun weeksCoverThirtyDays() {
        val today = LocalDate.of(2026, 11, 26) // a Thursday
        val weeks = calendarWeeks(today, DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 11, 23), weeks.first().first())
        assertTrue(today.plusDays(29) in weeks.last())
        assertTrue(weeks.all { it.size == 7 })
    }

    @Test fun readsForecast() {
        val json = """{
            "current":{"temperature_2m":4.4,"apparent_temperature":1.6,"weather_code":3,"is_day":1,
                       "relative_humidity_2m":81,"wind_speed_10m":9.6,"wind_direction_10m":230},
            "hourly":{"time":["2026-11-26T15:00","2026-11-26T16:00"],"temperature_2m":[4.4,3.6],"weather_code":[3,61],
                      "is_day":[1,0],"precipitation_probability":[5,null]},
            "daily":{"time":["2026-11-26"],"weather_code":[3],"temperature_2m_max":[6.2],"temperature_2m_min":[1.8],
                     "precipitation_probability_max":[40],"precipitation_sum":[1.25],"uv_index_max":[0.8],
                     "sunrise":["2026-11-26T07:37"],"sunset":["2026-11-26T15:56"]}}"""
        val w = WeatherApi.parse(json, "mph")
        assertEquals(4, w.temp)
        assertEquals(10, w.wind)
        assertEquals(0, w.hours[1].rain)
        assertEquals(40, w.days[0].rain)
        assertEquals(1, w.days[0].uv)
        assertEquals(15, w.days[0].sunset!!.hour)
        assertEquals(6, w.hi)
    }
}
