package com.home.dashboard

/** A switch or light the panel may turn on and off. */
data class HaDevice(val name: String, val entity: String)

data class VacuumState(val state: String, val battery: Int?)
data class DeviceState(val device: HaDevice, val on: Boolean?) // null: unavailable
/** [stamp] changes when the picture does. */
data class VacuumMap(val entity: String, val stamp: String)
data class PanelState(
    val vacuum: VacuumState? = null,
    val devices: List<DeviceState> = emptyList(),
    val problem: String? = null,
    val map: VacuumMap? = null,
    val floor: String? = null,
    val care: List<Pair<String, String>> = emptyList(), // name to what is left, e.g. "245 h"
)

/** The smart home panel's reads and commands. It only ever touches entities listed in the config. */
class SmartHome(private val cfg: HaConfig) {
    private val ha = HomeAssistant(cfg.url, cfg.token)

    /** Blocking; never throws for network or Home Assistant errors. */
    fun read(): PanelState {
        var problem: String? = null
        fun entity(id: String): HaEntity? = try {
            ha.state(id)
        } catch (e: HaException) {
            if (e.message != "entity not found") throw e
            problem = "$id not found"
            null
        }
        return try {
            val vacuum = if (cfg.vacuum.isBlank()) null else entity(cfg.vacuum)?.let { v ->
                val battery = if (cfg.battery.isBlank()) v.number("battery_level") else entity(cfg.battery)?.state?.toDoubleOrNull()
                VacuumState(v.state, battery?.toInt())
            }
            val devices = cfg.devices.map { d ->
                DeviceState(d, entity(d.entity)?.takeIf { it.available }?.let { it.state == "on" })
            }
            // The floor the vacuum has selected, else whichever map changed last.
            val floor = if (cfg.mapSelect.isBlank()) null else entity(cfg.mapSelect)?.state
            val maps = cfg.maps.mapNotNull { entity(it) }.filter { it.available }
            val shown = maps.firstOrNull { floor != null && it.attributes.optString("friendly_name").endsWith(floor, true) }
                ?: maps.maxByOrNull { it.state }
            val care = cfg.care.mapNotNull { c ->
                val e = entity(c.entity)?.takeIf { it.available } ?: return@mapNotNull null
                val number = e.state.toDoubleOrNull()?.let { Math.round(it).toString() } ?: e.state
                c.name to "$number ${e.attributes.optString("unit_of_measurement")}".trim()
            }
            PanelState(vacuum, devices, problem, shown?.let { VacuumMap(it.id, it.state) }, floor, care)
        } catch (e: HaException) {
            PanelState(devices = cfg.devices.map { DeviceState(it, null) }, problem = e.message)
        }
    }

    /** Blocking. Null if it can't be fetched. */
    fun mapImage(map: VacuumMap): ByteArray? = try {
        ha.image(map.entity)
    } catch (e: HaException) {
        null
    }

    /** [action] is `start`, `pause` or `return_to_base`. Returns what went wrong, if anything. */
    fun vacuum(action: String): String? {
        require(action in setOf("start", "pause", "return_to_base"))
        return send("vacuum", action, cfg.vacuum)
    }

    fun set(device: HaDevice, on: Boolean): String? {
        val domain = device.entity.substringBefore('.')
        // Nothing like locks, covers or alarms can be driven from the TV, even if listed.
        if (device !in cfg.devices || domain !in setOf("light", "switch", "fan", "input_boolean")) return "not allowed"
        return send(domain, if (on) "turn_on" else "turn_off", device.entity)
    }

    private fun send(domain: String, service: String, entity: String): String? = try {
        ha.call(domain, service, entity)
        null
    } catch (e: HaException) {
        e.message
    }
}
