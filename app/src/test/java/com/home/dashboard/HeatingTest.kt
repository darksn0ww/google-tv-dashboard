package com.home.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.time.ZonedDateTime
import kotlin.concurrent.thread

class HeatingTest {
    private fun entity(id: String, state: String, attributes: String = "{}", at: String = "2026-01-01T10:00:00.000000+00:00") =
        """{"entity_id":"$id","state":"$state","attributes":$attributes,"last_updated":"$at"}"""

    private val climate = entity(
        "climate.room", "auto",
        """{"friendly_name":"Room","current_temperature":19.0,"current_humidity":50,"temperature":20.5,
            "hvac_action":"heating","preset_mode":"home"}""",
    )

    @Test fun mapsEntities() {
        val sensor = entity("sensor.t", "19.4", at = "2026-01-01T11:00:00+00:00")
        val h = heatingFrom(parseEntity(climate), parseEntity(sensor), null, "")
        assertEquals("Room", h.zone)
        assertEquals(19.4, h.current!!, 0.0)
        assertEquals(20.5, h.target!!, 0.0)
        assertEquals(50.0, h.humidity!!, 0.0)
        assertTrue(h.active)
        assertEquals(parseEntity(sensor).updated, h.updated) // newest entity time, not the poll time
    }

    @Test fun prefersLastReported() {
        val unchanged = entity("sensor.t", "19.4").dropLast(1) + ""","last_reported":"2026-01-01T12:00:00+00:00"}"""
        assertEquals(parseEntity(entity("sensor.t", "19.4", at = "2026-01-01T12:00:00+00:00")).updated, parseEntity(unchanged).updated)
    }

    @Test fun offAndUnavailableSensor() {
        val off = parseEntity(entity("climate.room", "off", """{"current_temperature":18.0,"temperature":5.0}"""))
        val h = heatingFrom(off, parseEntity(entity("sensor.t", "unavailable")), null, "Lounge")
        assertNull(h.target)
        assertEquals(18.0, h.current!!, 0.0)
    }

    @Test fun nextScheduledChange() {
        val day = """[["00:00",null],["06:30",19.0],["09:00",19.0],["22:00",16.0]]"""
        val week = JSONObject(
            listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
                .joinToString(",", "{", "}") { "\"$it\":$day" },
        )
        fun at(time: String) = ZonedDateTime.parse("2026-01-05T$time:00Z") // a Monday
        fun ms(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()
        assertEquals(ms("2026-01-05T06:30:00Z") to 19.0, nextChange(week, at("03:00")))
        assertEquals(ms("2026-01-05T22:00:00Z") to 16.0, nextChange(week, at("07:00"))) // 09:00 changes nothing
        assertEquals(ms("2026-01-06T00:00:00Z") to null, nextChange(week, at("23:00")))
        assertNull(nextChange(JSONObject("{}"), at("03:00")))
        val cloud = entity("climate.room", "heat", """{"hvac_modes":["off","auto","heat"],"temperature":21.0}""")
        val manual = heatingFrom(parseEntity(cloud), null, null, "")
        assertTrue(manual.manual)
        assertNull(manual.withSchedule(week, false, at("03:00")).nextAt)

        // HomeKit only knows off and heat: judge by the target instead.
        fun local(target: Double) = heatingFrom(
            parseEntity(entity("climate.room", "heat", """{"hvac_modes":["off","heat"],"temperature":$target}""")), null, null, "",
        )
        assertFalse(local(19.0).manual)
        assertTrue(local(19.0).onChangeOnly)
        assertFalse(manual.onChangeOnly)
        assertEquals(16.0, local(19.0).withSchedule(week, false, at("07:00")).nextTarget!!, 0.0) // on schedule
        assertEquals(16.0, local(16.0).withSchedule(week, false, at("21:30")).nextTarget!!, 0.0) // heading there early
        assertNull(local(23.0).withSchedule(week, false, at("07:00")).nextAt) // turned up by hand
    }

    @Test fun panelOnlyDrivesListedDevices() {
        val lamp = HaDevice("Lamp", "switch.lamp")
        val door = HaDevice("Door", "lock.door")
        FakeHa(
            mapOf(
                "switch.lamp" to (200 to entity("switch.lamp", "on")),
                "lock.door" to (200 to entity("lock.door", "locked")),
                "vacuum.v" to (200 to entity("vacuum.v", "docked")),
                "sensor.b" to (200 to entity("sensor.b", "51")),
                "select.floor" to (200 to entity("select.floor", "Up")),
                "sensor.filter" to (200 to entity("sensor.filter", "147.125", """{"unit_of_measurement":"h"}""")),
                "image.up" to (200 to entity("image.up", "2026-01-01T09:00:00+00:00", """{"friendly_name":"Robot Up"}""")),
                "image.down" to (200 to entity("image.down", "2026-01-01T10:00:00+00:00", """{"friendly_name":"Robot Down"}""")),
                "/api/services/switch/turn_off" to (200 to "[]"),
                "/api/services/vacuum/start" to (200 to "[]"),
            ),
        ).use { fake ->
            val home = SmartHome(
                HaConfig(
                    fake.url, "good", "", vacuum = "vacuum.v", battery = "sensor.b", devices = listOf(lamp, door),
                    maps = listOf("image.down", "image.up"), mapSelect = "select.floor",
                    care = listOf(HaDevice("Filter", "sensor.filter")),
                ),
            )
            val state = home.read()
            assertEquals(VacuumState("docked", 51), state.vacuum)
            assertEquals(true, state.devices.first().on)
            assertEquals("image.up", state.map!!.entity) // the selected floor, not the newer picture
            assertEquals("Up", state.floor)
            assertEquals(listOf("Filter" to "147 h"), state.care)
            assertNull(home.set(lamp, false))
            assertNull(home.vacuum("start"))
            assertEquals("not allowed", home.set(door, true))
            assertEquals("not allowed", home.set(HaDevice("Other", "switch.other"), true))
            assertEquals(listOf("/api/services/switch/turn_off", "/api/services/vacuum/start"), fake.posts)
        }
    }

    @Test fun readsAutomaticLocation() {
        val p = Locator.parse("""{"latitude":"51.5","longitude":"-0.12","city":"London"}""")
        assertEquals(Place(51.5, -0.12, "London"), p)
    }

    private fun read(state: String, attributes: String) =
        heatingFrom(parseEntity(entity("climate.t", state, attributes)), null, null, "T")

    @Test fun otherThermostats() {
        // HomeKit "auto" with both a heating and a cooling threshold
        val range = read("heat_cool", """{"target_temp_low":18.0,"target_temp_high":24.0,"hvac_action":"cooling","min_temp":10,"max_temp":32}""")
        assertEquals(18.0, range.target!!, 0.0)
        assertEquals(24.0, range.upperTarget!!, 0.0)
        assertTrue(range.cooling)
        assertEquals(10.0, range.min, 0.0)
        // a HomeKit heater-cooler on auto names no target, which is not the same as off
        assertTrue(read("heat_cool", """{"current_temperature":20.0}""").auto)
        assertFalse(read("off", """{"temperature":7.0}""").auto)
        // Hive: schedule is "auto", boost is a preset
        val hive = """"hvac_modes":["auto","heat","off"],"temperature":22.0"""
        assertFalse(read("auto", "{$hive}").manual)
        assertTrue(read("heat", "{$hive}").manual)
        val boost = read("heat", """{$hive,"preset_mode":"boost"}""")
        assertTrue(boost.boost)
        assertFalse(boost.manual)
    }

    /** A one-route-table stand-in for Home Assistant. */
    private class FakeHa(var routes: Map<String, Pair<Int, String>>) : AutoCloseable {
        private val server = ServerSocket(0)
        val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val url = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) server.accept().use { s ->
                        val input = s.getInputStream().bufferedReader()
                        val (method, path) = input.readLine().split(" ")
                        if (method == "POST") posts += path
                        var auth = ""
                        while (true) {
                            val line = input.readLine()
                            if (line.isNullOrEmpty()) break
                            if (line.startsWith("Authorization:")) auth = line.substringAfter(": ")
                        }
                        val (code, body) = if (auth != "Bearer good") 401 to ""
                        else routes[path.removePrefix("/api/states/")] ?: (404 to "")
                        s.getOutputStream().write(
                            "HTTP/1.1 $code X\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                        )
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    @Test fun keepsLastGoodReadingWhenThingsFail() {
        val cache = File.createTempFile("heating", ".json").apply { delete(); deleteOnExit() }
        val fake = FakeHa(mapOf("climate.room" to (200 to climate)))
        fun config(token: String = "good", humidity: String = "") =
            HaConfig(fake.url, token, "climate.room", humidity = humidity)

        assertEquals("token rejected", HaHeating(config("bad"), cache).read().problem)
        assertNull(HaHeating(config("bad"), cache).read().heating)

        val good = HaHeating(config(), cache).read()
        assertNull(good.problem)
        assertEquals("sensor not found", HaHeating(config(humidity = "sensor.gone"), cache).read().problem)

        fun failsWith(problem: String) {
            val r = HaHeating(config(), cache).read()
            assertEquals(problem, r.problem)
            assertEquals(20.5, r.heating!!.target!!, 0.0)
            assertEquals(good.heating!!.updated, r.heating!!.updated)
        }
        fake.routes = mapOf("climate.room" to (200 to entity("climate.room", "unavailable")))
        failsWith("unavailable")
        fake.routes = emptyMap()
        failsWith("entity not found")
        fake.routes = mapOf("climate.room" to (200 to "<html>"))
        failsWith("bad response")
        fake.close()
        failsWith("offline")
    }
}
