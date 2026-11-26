package com.home.dashboard

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Credentials for Google's Smart Device Management API; create them with tools/nest-auth.py. */
data class NestConfig(
    val projectId: String,
    val clientId: String,
    val clientSecret: String,
    val refreshToken: String,
    val room: String = "",
)

/**
 * Read-only Nest thermostat support. EXPERIMENTAL: written from Google's documentation and not yet
 * tried against a real thermostat. The API exposes no schedule, so there is no "next change".
 */
class Nest(private val cfg: NestConfig) {
    private val http = OkHttpClient()
    private var access = ""
    private var accessExpires = 0L

    private fun body(request: Request): String = http.newCall(request).execute().use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        r.body!!.string()
    }

    private fun accessToken(): String {
        if (System.currentTimeMillis() < accessExpires) return access
        val form = FormBody.Builder()
            .add("client_id", cfg.clientId)
            .add("client_secret", cfg.clientSecret)
            .add("refresh_token", cfg.refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val j = JSONObject(body(Request.Builder().url("https://www.googleapis.com/oauth2/v4/token").post(form).build()))
        access = j.getString("access_token")
        accessExpires = System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 60) * 1000
        return access
    }

    /** Blocking; call off the main thread. */
    fun read(): Heating {
        val url = "https://smartdevicemanagement.googleapis.com/v1/enterprises/${cfg.projectId}/devices"
        val json = body(Request.Builder().url(url).header("Authorization", "Bearer ${accessToken()}").build())
        return parseNest(json, cfg.room, System.currentTimeMillis()) ?: throw IOException("No thermostat found")
    }
}

/** Picks the thermostat whose name or room matches [room], else the first one. */
fun parseNest(json: String, room: String, now: Long): Heating? {
    val devices = JSONObject(json).optJSONArray("devices") ?: return null
    val thermostats = (0 until devices.length()).map { devices.getJSONObject(it) }
        .filter { it.optString("type") == "sdm.devices.types.THERMOSTAT" }

    fun JSONObject.trait(name: String) = optJSONObject("traits")?.optJSONObject("sdm.devices.traits.$name")
    fun name(d: JSONObject): String =
        d.trait("Info")?.optString("customName").orEmpty().ifBlank {
            d.optJSONArray("parentRelations")?.optJSONObject(0)?.optString("displayName").orEmpty()
        }.ifBlank { "Thermostat" }

    val d = thermostats.firstOrNull { name(it).equals(room, true) } ?: thermostats.firstOrNull() ?: return null
    fun JSONObject?.number(key: String): Double? = this?.optDouble(key)?.takeIf { !it.isNaN() }

    val mode = d.trait("ThermostatMode")?.optString("mode").orEmpty()
    val eco = d.trait("ThermostatEco")?.optString("mode") == "MANUAL_ECO"
    val setpoint = if (eco) d.trait("ThermostatEco") else d.trait("ThermostatTemperatureSetpoint")
    val heat = setpoint.number("heatCelsius")
    val cool = setpoint.number("coolCelsius")
    val (target, upper) = when {
        mode == "OFF" -> null to null
        mode == "COOL" -> cool to null
        mode == "HEATCOOL" -> heat to cool
        else -> heat to null
    }
    return Heating(
        zone = name(d),
        current = d.trait("Temperature").number("ambientTemperatureCelsius"),
        target = target,
        manual = false,
        nextAt = null,
        nextTarget = null,
        updated = now,
        upperTarget = upper,
        eco = eco,
    )
}
