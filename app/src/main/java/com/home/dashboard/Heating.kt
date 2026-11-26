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
) {
    val connected get() = url.isNotBlank() && token.isNotBlank()
    val ready get() = connected && climate.isNotBlank()
    val hasPanel get() = connected && (vacuum.isNotBlank() || devices.isNotEmpty())
    override fun toString() = "HaConfig(climate=$climate)" // keep the token out of any log
}

fun heatingFrom(climate: HaEntity, temperature: HaEntity?, humidity: HaEntity?, name: String): Heating {
    fun HaEntity?.value() = this?.takeIf { it.available }?.state?.toDoubleOrNull()
    return Heating(
        zone = name.ifBlank { climate.attributes.optString("friendly_name") }.ifBlank { "Heating" },
        current = temperature.value() ?: climate.number("current_temperature"),
        target = if (climate.state == "off") null else climate.number("temperature"),
        manual = climate.state == "heat", // "auto" means following the schedule
        updated = listOfNotNull(climate, temperature, humidity).filter { it.available }.maxOf { it.updated },
        humidity = humidity.value() ?: climate.number("current_humidity"),
        active = climate.attributes.optString("hvac_action") == "heating",
        away = climate.attributes.optString("preset_mode") == "away",
    )
}

/**
 * The next time the weekly [schedule] changes temperature: `{"monday": [["06:30", 19.0], ...], ...}`,
 * with null meaning off. Returns epoch millis and the new temperature.
 */
fun nextChange(schedule: JSONObject, now: ZonedDateTime): Pair<Long, Double?>? = runCatching {
    // From yesterday, so we know the temperature in force just after midnight; to the same day next week.
    val changes = (-1L..7L).flatMap { offset ->
        val date = now.toLocalDate().plusDays(offset)
        val blocks = schedule.getJSONArray(date.dayOfWeek.name.lowercase())
        (0 until blocks.length()).map {
            val b = blocks.getJSONArray(it)
            date.atTime(LocalTime.parse(b.getString(0))).atZone(now.zone) to if (b.isNull(1)) null else b.getDouble(1)
        }
    }.sortedBy { it.first.toInstant() }
    val current = changes.last { !it.first.isAfter(now) }.second
    changes.firstOrNull { it.first.isAfter(now) && it.second != current }
        ?.let { it.first.toInstant().toEpochMilli() to it.second }
}.getOrNull()

/** The schedule doesn't apply while the heating is off, set by hand or in away mode. */
fun Heating.withSchedule(schedule: JSONObject?, off: Boolean, now: ZonedDateTime): Heating {
    val next = if (schedule == null || off || manual || away) null else nextChange(schedule, now)
    return copy(nextAt = next?.first, nextTarget = next?.second)
}

/** Reads heating through Home Assistant, remembering the last good reading in [cache]. */
class HaHeating(private val cfg: HaConfig, private val cache: File) {
    private val ha = HomeAssistant(cfg.url, cfg.token)

    fun cached(): Heating? = runCatching {
        val j = JSONObject(cache.readText())
        if (j.getString("entity") != cfg.climate) return null
        fun d(k: String) = if (j.has(k)) j.getDouble(k) else null
        Heating(
            zone = j.getString("zone"), current = d("current"), target = d("target"),
            manual = j.getBoolean("manual"), updated = j.getLong("updated"), humidity = d("humidity"),
            active = j.getBoolean("active"), away = j.getBoolean("away"),
            nextAt = if (j.has("nextAt")) j.getLong("nextAt").takeIf { it > System.currentTimeMillis() } else null,
            nextTarget = d("nextTarget"),
        )
    }.getOrNull()

    private fun keep(h: Heating) {
        val j = JSONObject().put("entity", cfg.climate).put("zone", h.zone).put("current", h.current)
            .put("target", h.target).put("manual", h.manual).put("updated", h.updated)
            .put("humidity", h.humidity).put("active", h.active).put("away", h.away)
            .put("nextAt", h.nextAt).put("nextTarget", h.nextTarget)
        runCatching { cache.writeText(j.toString()) }
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
