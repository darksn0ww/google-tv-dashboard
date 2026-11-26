package com.home.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Shapes follow Google's sample device resource. */
class NestTest {
    private fun thermostat(name: String, mode: String, eco: String = "OFF") = """
        {"type":"sdm.devices.types.THERMOSTAT",
         "parentRelations":[{"displayName":"Hallway"}],
         "traits":{
          "sdm.devices.traits.Info":{"customName":"$name"},
          "sdm.devices.traits.Temperature":{"ambientTemperatureCelsius":19.5},
          "sdm.devices.traits.ThermostatMode":{"mode":"$mode"},
          "sdm.devices.traits.ThermostatEco":{"mode":"$eco","heatCelsius":15.0,"coolCelsius":26.0},
          "sdm.devices.traits.ThermostatTemperatureSetpoint":{"heatCelsius":20.0,"coolCelsius":23.0}}}
    """
    private fun devices(vararg d: String) = """{"devices":[${d.joinToString(",")}]}"""

    @Test fun heating() {
        val h = parseNest(devices(thermostat("Lounge", "HEAT")), "", 1L)!!
        assertEquals("Lounge", h.zone)
        assertEquals(19.5, h.current!!, 0.0)
        assertEquals(20.0, h.target!!, 0.0)
        assertNull(h.upperTarget)
    }

    @Test fun coolingRangeEcoAndOff() {
        assertEquals(23.0, parseNest(devices(thermostat("A", "COOL")), "", 1L)!!.target!!, 0.0)
        assertEquals(23.0, parseNest(devices(thermostat("A", "HEATCOOL")), "", 1L)!!.upperTarget!!, 0.0)
        assertEquals(15.0, parseNest(devices(thermostat("A", "HEAT", "MANUAL_ECO")), "", 1L)!!.target!!, 0.0)
        assertNull(parseNest(devices(thermostat("A", "OFF")), "", 1L)!!.target)
    }

    @Test fun picksRoomAndFallsBackToParentName() {
        val json = devices(thermostat("Lounge", "HEAT"), thermostat("Bedroom", "HEAT"))
        assertEquals("Bedroom", parseNest(json, "bedroom", 1L)!!.zone)
        assertEquals("Hallway", parseNest(devices(thermostat("", "HEAT")), "", 1L)!!.zone)
        assertNull(parseNest("""{"devices":[{"type":"sdm.devices.types.CAMERA"}]}""", "", 1L))
    }
}
