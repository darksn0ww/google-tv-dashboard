package com.home.dashboard

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * tado API, signed in with the OAuth device flow (the password is only ever typed on tado's site).
 * Accounts without Auto-Assist get very few requests a day, shared by every device, so each
 * refresh is a single call and the interval follows the `ratelimit` response header.
 */
class Tado(private val ctx: Context) {
    val state = MutableStateFlow<HeatingState>(HeatingState.NotLinked)

    private val http = OkHttpClient()
    private val file = File(ctx.filesDir, "tado.json")
    private var store = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private var access = ""
    private var accessExpires = 0L

    init {
        if (store.optString("refresh").isNotEmpty()) state.value = HeatingState.Reading(cached())
    }

    private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(store.toString())
        tmp.renameTo(file)
    }

    private fun post(url: String, form: Map<String, String>): Pair<Int, JSONObject> {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
            return r.code to runCatching { JSONObject(r.body!!.string()) }.getOrDefault(JSONObject())
        }
    }

    // ---------- Sign in ----------

    /** Shows a code and waits (until it expires) for it to be approved in a browser. */
    suspend fun link() = withContext(Dispatchers.IO) {
        try {
            val (code, j) = post("$LOGIN/device_authorize", mapOf("client_id" to CLIENT, "scope" to "offline_access"))
            if (code != 200) throw IOException("HTTP $code")
            state.value = HeatingState.Linking(j.getString("verification_uri_complete"), j.getString("user_code"))
            val every = j.optLong("interval", 5).coerceAtLeast(5) * 1000
            val until = System.currentTimeMillis() + j.optLong("expires_in", 300) * 1000
            while (System.currentTimeMillis() < until) {
                delay(every)
                val (status, token) = try {
                    post(
                    "$LOGIN/token",
                    mapOf(
                        "client_id" to CLIENT,
                        "device_code" to j.getString("device_code"),
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                )
                } catch (e: IOException) {
                    continue // keep waiting until the code expires
                }
                if (status == 200) {
                    keep(token)
                    state.value = HeatingState.Reading(null)
                    return@withContext
                }
                if (token.optString("error") !in setOf("authorization_pending", "slow_down")) break
            }
            state.value = HeatingState.NotLinked
        } catch (e: IOException) {
            state.value = HeatingState.NotLinked
        }
    }

    private fun keep(token: JSONObject) {
        access = token.getString("access_token")
        accessExpires = System.currentTimeMillis() + (token.optLong("expires_in", 600) - 30) * 1000
        store.put("refresh", token.getString("refresh_token")) // rotates on every use
        save()
    }

    private suspend fun accessToken(): String {
        if (System.currentTimeMillis() < accessExpires) return access
        // Not cancellable: losing a rotated refresh token would unlink the TV.
        withContext(NonCancellable) {
            val (status, token) = post(
                "$LOGIN/token",
                mapOf("client_id" to CLIENT, "grant_type" to "refresh_token", "refresh_token" to store.getString("refresh")),
            )
            if (status == 200) keep(token)
            else if (token.optString("error") == "invalid_grant") {
                store = JSONObject(); save()
                state.value = HeatingState.NotLinked
                throw IOException("Sign-in expired")
            } else throw IOException("HTTP $status")
        }
        return access
    }

    // ---------- Data ----------

    private suspend fun get(url: String): String {
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${accessToken()}").build()
        http.newCall(req).execute().use { r ->
            r.header("ratelimit")?.let { h ->
                Regex("r=(\\d+)").find(h)?.let { store.put("remaining", it.groupValues[1].toLong()) }
                Regex("t=(\\d+)").find(h)?.let { store.put("resetSeconds", it.groupValues[1].toLong()) }
            }
            if (r.code == 429) { store.put("remaining", 0); save(); throw IOException("Daily limit reached") }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body!!.string()
        }
    }

    /** Spread what's left of the allowance until it refills, assuming two devices share it. */
    private fun interval(): Long {
        if (!store.has("remaining") || !store.has("resetSeconds")) return 30 * 60_000L
        val share = (store.getLong("remaining") / 2).coerceAtLeast(1)
        return (store.getLong("resetSeconds") * 1000 / share).coerceIn(5 * 60_000L, 2 * 60 * 60_000L)
    }

    /** Fetches only if the last reading is older than the allowed interval. */
    suspend fun refresh(wantedZone: String) = withContext(Dispatchers.IO) {
        if (store.optString("refresh").isEmpty()) return@withContext
        val last = cached()
        val sameZone = store.optString("wanted") == wantedZone
        if (last != null && sameZone && System.currentTimeMillis() - store.optLong("fetched") < interval()) return@withContext
        if (last == null && System.currentTimeMillis() - store.optLong("fetched") < 5 * 60_000L) return@withContext
        try {
            store.put("fetched", System.currentTimeMillis()).put("wanted", wantedZone)
            if (!store.has("home")) {
                val home = JSONObject(get("$API/me")).getJSONArray("homes").getJSONObject(0).getLong("id")
                val x = JSONObject(get("$API/homes/$home")).optString("generation") == "LINE_X"
                store.put("home", home).put("tadoX", x)
            }
            val home = store.getLong("home")
            val heating = if (store.optBoolean("tadoX")) rooms(home, wantedZone) else zones(home, wantedZone)
            store.put("last", heating.toJson())
            save()
            state.value = HeatingState.Reading(heating)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            save()
            if (state.value is HeatingState.Reading) state.value = HeatingState.Reading(cached(), e.message ?: "Unavailable")
        }
    }

    private suspend fun zones(home: Long, wanted: String): Heating {
        if (!store.has("zones")) {
            val a = JSONArray(get("$API/homes/$home/zones"))
            val heating = JSONObject()
            for (i in 0 until a.length()) {
                val z = a.getJSONObject(i)
                if (z.optString("type") == "HEATING") heating.put(z.getLong("id").toString(), z.getString("name"))
            }
            store.put("zones", heating)
        }
        val names = store.getJSONObject("zones")
        val id = names.keys().asSequence().firstOrNull { names.getString(it).equals(wanted, true) }
            ?: names.keys().asSequence().minByOrNull { it.toInt() } ?: throw IOException("No heating zones")
        val z = JSONObject(get("$API/homes/$home/zoneStates")).getJSONObject("zoneStates").getJSONObject(id)
        return heating(
            name = names.getString(id),
            current = z.optJSONObject("sensorDataPoints")?.optJSONObject("insideTemperature"),
            setting = z.optJSONObject("setting"),
            manual = !z.isNull("overlay"),
            next = z.optJSONObject("nextScheduleChange"),
        )
    }

    private suspend fun rooms(home: Long, wanted: String): Heating {
        val a = JSONArray(get("https://hops.tado.com/homes/$home/rooms"))
        val all = (0 until a.length()).map { a.getJSONObject(it) }
        val r = all.firstOrNull { it.optString("name").equals(wanted, true) } ?: all.firstOrNull()
            ?: throw IOException("No rooms")
        return heating(
            name = r.optString("name"),
            current = r.optJSONObject("sensorDataPoints")?.optJSONObject("insideTemperature"),
            setting = r.optJSONObject("setting"),
            manual = !r.isNull("manualControlTermination"),
            next = r.optJSONObject("nextScheduleChange"),
        )
    }

    /** Classic API says `temperature.celsius`, tado X says `temperature.value`. */
    private fun temp(o: JSONObject?): Double? {
        if (o == null) return null
        val v = if (o.has("celsius")) o.optDouble("celsius") else o.optDouble("value")
        return v.takeIf { !it.isNaN() }
    }

    private fun target(setting: JSONObject?): Double? =
        if (setting == null || setting.optString("power") == "OFF") null else temp(setting.optJSONObject("temperature"))

    private fun heating(name: String, current: JSONObject?, setting: JSONObject?, manual: Boolean, next: JSONObject?) = Heating(
        zone = name,
        current = temp(current),
        target = target(setting),
        manual = manual,
        nextAt = next?.optString("start")?.let { s ->
            runCatching { OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        },
        nextTarget = target(next?.optJSONObject("setting")),
        updated = System.currentTimeMillis(),
    )

    private fun cached(): Heating? = store.optJSONObject("last")?.let { heatingFromJson(it) }

    private companion object {
        const val CLIENT = "1bb50063-6b0c-4d11-bd99-387f4a91cc46" // tado's published public client id
        const val LOGIN = "https://login.tado.com/oauth2"
        const val API = "https://my.tado.com/api/v2"
    }
}
