package com.home.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime

class ConfigTest {
    private fun temp(text: String) = File.createTempFile("config", ".json").apply { writeText(text); deleteOnExit() }

    @Test fun badSettingsNeverReplaceGoodOnes() {
        val stored = temp("""{"place":"Good"}""")
        for (bad in listOf("""{"place":"Unclosed" """, """{"cameras":[{"name":"No link"}]}""", "")) {
            val incoming = temp(bad)
            assertFalse(bad, Config.accept(incoming, stored))
            assertTrue("left for the script to see", incoming.exists())
            assertEquals("""{"place":"Good"}""", stored.readText())
        }
        val incoming = temp("""{"place":"New"}""")
        assertTrue(Config.accept(incoming, stored))
        assertFalse(incoming.exists())
        assertEquals("""{"place":"New"}""", stored.readText())
    }

    @Test fun onlyUniFiProtectLinksAreRewritten() {
        assertEquals("rtsp://nvr:7447/abc", Camera("c", "rtsps://nvr:7441/abc?enableSrtp").streamUrl)
        for (link in listOf("rtsps://user:pw@cam:322/stream", "rtsps://nvr:7441/abc", "rtsp://user:pw@cam:554/s", "not a url")) {
            assertEquals(link, Camera("c", link).streamUrl)
        }
    }

    @Test fun oneBrokenCalendarKeepsTheOthers() {
        fun ics(title: String) = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:$title\nDTSTART:20260102T100000Z\nEND:VEVENT\nEND:VCALENDAR\n"
        val from = ZonedDateTime.parse("2026-01-01T00:00:00Z")
        val last = mutableMapOf<String, List<CalEvent>>()
        var broken = false
        fun fetch() = Calendars.fetchAll(listOf("https://a", "https://b"), from, from.plusDays(7), last) {
            if (broken && it == "https://b") throw IOException("down") else ics(it.removePrefix("https://"))
        }
        broken = true
        assertEquals(listOf("a"), fetch().events.map { it.title })
        assertEquals(1, fetch().failed)
        broken = false
        assertEquals(2, fetch().events.size)
        broken = true
        assertEquals("its last events stay", 2, fetch().events.size)
    }
}
