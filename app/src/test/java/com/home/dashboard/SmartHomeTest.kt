package com.home.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartHomeTest {
    private fun map(id: String, name: String, changed: String) =
        HaEntity(id, changed, JSONObject().put("friendly_name", name), 0)

    private val down = map("image.robot_downstairs", "Robot Downstairs", "2026-01-01T09:00:00")
    private val up = map("image.robot_upstairs", "Robot Upstairs", "2026-01-01T10:00:00")
    private val maps = listOf(down, up)
    private val floors = listOf("Downstairs", "Upstairs")

    private fun show(selected: String?, dock: String, docked: Boolean) = mapToShow(maps, selected, floors, dock, docked)

    @Test fun dockedShowsTheDocksFloor() {
        assertEquals(ShownMap(down, "Downstairs"), show("Upstairs", down.id, docked = true))
    }

    @Test fun awayFromTheDockShowsTheSelectedFloor() {
        assertEquals(ShownMap(up, "Upstairs"), show("Upstairs", down.id, docked = false))
    }

    @Test fun withoutADockMapNothingChanges() {
        assertEquals(ShownMap(up, "Upstairs"), show("Upstairs", "", docked = true))
        assertEquals(ShownMap(up, null), show(null, "", docked = true)) // the newest
    }

    @Test fun aMissingDockMapFallsBack() {
        assertEquals(ShownMap(up, "Upstairs"), show("Upstairs", "image.gone", docked = true))
    }

    @Test fun floorIsUnknownWithoutTheFloorList() {
        assertNull(mapToShow(maps, "Upstairs", emptyList(), down.id, true).floor)
    }
}
