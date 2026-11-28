package com.home.dashboard

import org.json.JSONObject
import java.io.File
import java.time.LocalTime
import java.time.ZonedDateTime

/** What the heating card shows. A null target means the heating is off. */
data class Heating(
    val zone: String,
    val current: Double?,
    val target: Double?,
    val manual: Boolean,
    val updated: Long,
    val upperTarget: Double? = null, // Nest heat/cool mode
    val eco: Boolean = false,
    val humidity: Double? = null,
    val active: Boolean = false, // calling for heat right now
    val away: Boolean = false,
    val boost: Boolean = false, // Hive: heating flat out for a while
    val cooling: Boolean = false,
    val auto: Boolean = false, // on, but the thermostat names no target
    val min: Double = 5.0, // the thermostat's range, for the dial
    val max: Double = 25.0,
    val onChangeOnly: Boolean = false, // [updated] only moves when a value does (HomeKit)
    val nextAt: Long? = null, // next scheduled change, epoch millis
    val nextTarget: Double? = null,
)

sealed interface HeatingState {
    data object NotConfigured : HeatingState
    data object NotLinked : HeatingState // direct tado, before sign-in
    data class Linking(val url: String, val code: String) : HeatingState
    /** [problem] set means [heating] is the last good reading, now stale. */
    data class Reading(val heating: Heating?, val problem: String? = null) : HeatingState
}

/** The `homeAssistant` block of config.json: the one place the address, token and entity IDs live. */
data class HaConfig(
    val url: String,
    val token: String,
    val climate: String,
    val temperature: String = "", // optional sensor; otherwise the climate entity's reading
    val humidity: String = "", // optional sensor; likewise
    val name: String = "",
    val schedule: String = "", // optional sensor from tools/tado-schedule.py
    val vacuum: String = "", // smart home panel
    val battery: String = "",
    val devices: List<HaDevice> = emptyList(),
    val maps: List<String> = emptyList(), // vacuum map `image` entities, one per floor
    val mapSelect: String = "", // optional `select` naming the floor in use
    val care: List<HaDevice> = emptyList(), // sensors for brush, filter etc. life
    val dockMap: String = "", // optional: which of [maps] is the floor with the dock
) {
    val connected get() = url.isNotBlank() && token.isNotBlank()
    val ready get() = connected && climate.isNotBlank()
    val hasPanel get() = connected && (vacuum.isNotBlank() || devices.isNotEmpty())
    override fun toString() = "HaConfig(climate=$climate)" // keep the token out of any log
}

private fun HaEntity.has(mode: String): Boolean {
    val modes = attributes.optJSONArray("hvac_modes") ?: return false
    return (0 until modes.length()).any { modes.optString(it) == mode }
}

/**
 * Reads any `climate` entity: tado (cloud or HomeKit), Hive, and other thermostats, including ones
 * that cool, hold a range (`heat_cool`) or run on auto without a target.
 */
fun heatingFrom(climate: HaEntity, temperature: HaEntity?, humidity: HaEntity?, name: String): Heating {
    fun HaEntity?.value() = this?.takeIf { it.available }?.state?.toDoubleOrNull()
    val off = climate.state == "off"
    val range = climate.state == "heat_cool" && climate.number("target_temp_low") != null
    val target = when {
        off -> null
        range -> climate.number("target_temp_low")
        else -> climate.number("temperature")
    }
    val action = climate.attributes.optString("hvac_action")
    val preset = climate.attributes.optString("preset_mode").lowercase()
    val min = climate.number("min_temp") ?: 5.0
    return Heating(
        zone = name.ifBlank { climate.attributes.optString("friendly_name") }.ifBlank { "Heating" },
        current = temperature.value() ?: climate.number("current_temperature"),
        target = target,
        // With an "auto" mode (tado through the cloud, Hive), "heat" means set by hand. Without one
        // (HomeKit and most thermostats) "heat" is the normal state and says nothing.
        manual = climate.state == "heat" && climate.has("auto") && preset != "boost",
        updated = listOfNotNull(climate, temperature, humidity).filter { it.available }.maxOf { it.updated },
        upperTarget = if (range) climate.number("target_temp_high") else null,
        humidity = humidity.value() ?: climate.number("current_humidity"),
        active = action == "heating",
        away = preset == "away",
        boost = preset == "boost",
        cooling = action == "cooling",
        auto = !off && target == null,
        min = min,
        max = (climate.number("max_temp") ?: 25.0).coerceAtLeast(min + 1),
        onChangeOnly = !climate.has("auto"),
    )
}

/**
 * Every temperature change in the weekly [schedule] from yesterday to a week ahead, in order.
 * The schedule is `{"monday": [["06:30", 19.0], ...], ...}`, with null meaning off.
 */
private fun changes(schedule: JSONObject, now: ZonedDateTime): List<Pair<ZonedDateTime, Double?>> =
    // From yesterday, so we know the temperature in force just after midnight; to the same day next week.
    (-1L..7L).flatMap { offset ->
        val date = now.toLocalDate().plusDays(offset)
        val blocks = schedule.getJSONArray(date.dayOfWeek.name.lowercase())
        (0 until blocks.length()).map {
            val b = blocks.getJSONArray(it)
            date.atTime(LocalTime.parse(b.getString(0))).atZone(now.zone) to if (b.isNull(1)) null else b.getDouble(1)
        }
    }.sortedBy { it.first.toInstant() }

/** The next time the schedule changes temperature: epoch millis and the new temperature. */
fun nextChange(schedule: JSONObject, now: ZonedDateTime): Pair<Long, Double?>? = runCatching {
    val all = changes(schedule, now)
    val current = all.last { !it.first.isAfter(now) }.second
    all.firstOrNull { it.first.isAfter(now) && it.second != current }
        ?.let { it.first.toInstant().toEpochMilli() to it.second }
}.getOrNull()

/** The schedule doesn't apply while the heating is off, set by hand or in away mode. */
fun Heating.withSchedule(schedule: JSONObject?, off: Boolean, now: ZonedDateTime): Heating {
    var next = if (schedule == null || off || manual || away || boost) null else nextChange(schedule, now)
    if (next != null) {
        // A thermostat that can't say it was set by hand: a target the schedule doesn't explain means
        // it was. The next target counts too, as tado's Early Start heats towards it ahead of time.
        val scheduled = runCatching { changes(schedule!!, now).last { !it.first.isAfter(now) }.second }.getOrNull()
        if (target != null && target != scheduled && target != next.second) next = null
    }
    return copy(nextAt = next?.first, nextTarget = next?.second)
}

fun Heating.toJson(): JSONObject = JSONObject()
    .put("zone", zone).put("current", current).put("target", target).put("manual", manual).put("updated", updated)
    .put("humidity", humidity).put("active", active).put("away", away).put("nextAt", nextAt).put("nextTarget", nextTarget)
    .put("upperTarget", upperTarget).put("boost", boost).put("cooling", cooling).put("auto", auto)
    .put("min", min).put("max", max).put("onChangeOnly", onChangeOnly)

fun heatingFromJson(j: JSONObject): Heating {
    fun d(k: String) = if (j.has(k)) j.getDouble(k) else null
    return Heating(
        zone = j.optString("zone"), current = d("current"), target = d("target"), manual = j.optBoolean("manual"),
        updated = j.optLong("updated"), humidity = d("humidity"), active = j.optBoolean("active"),
        away = j.optBoolean("away"), nextAt = if (j.has("nextAt")) j.getLong("nextAt") else null,
        nextTarget = d("nextTarget"), upperTarget = d("upperTarget"), boost = j.optBoolean("boost"),
        cooling = j.optBoolean("cooling"), auto = j.optBoolean("auto"), min = d("min") ?: 5.0, max = d("max") ?: 25.0,
        onChangeOnly = j.optBoolean("onChangeOnly"),
    )
}

/** Reads heating through Home Assistant, remembering the last good reading in [cache]. */
class HaHeating(private val cfg: HaConfig, private val cache: File) {
    private val ha = HomeAssistant(cfg.url, cfg.token)

    fun cached(): Heating? = runCatching {
        val j = JSONObject(cache.readText())
        if (j.getString("entity") != cfg.climate) return null
        val h = heatingFromJson(j)
        if ((h.nextAt ?: 0) > System.currentTimeMillis()) h else h.copy(nextAt = null)
    }.getOrNull()

    private fun keep(h: Heating) {
        runCatching { cache.writeText(h.toJson().put("entity", cfg.climate).toString()) }
    }

    /** Blocking; never throws for network or Home Assistant errors. */
    fun read(): HeatingState.Reading = try {
        val climate = ha.state(cfg.climate)
        if (!climate.available) throw HaException(climate.state)
        var problem: String? = null
        // A broken sensor falls back to the climate entity; only a wrong ID is worth reporting.
        fun sensor(id: String): HaEntity? = if (id.isBlank()) null else try {
            ha.state(id)
        } catch (e: HaException) {
            if (e.message != "entity not found") throw e
            problem = "sensor not found"
            null
        }
        // The schedule is an extra: without it the readings are still good.
        val schedule = if (cfg.schedule.isBlank()) null else
            runCatching { ha.state(cfg.schedule).attributes.optJSONObject("schedule") }.getOrNull()
        val h = heatingFrom(climate, sensor(cfg.temperature), sensor(cfg.humidity), cfg.name)
            .withSchedule(schedule, climate.state == "off", ZonedDateTime.now())
        keep(h)
        HeatingState.Reading(h, problem)
    } catch (e: HaException) {
        HeatingState.Reading(cached(), e.message)
    }
}
