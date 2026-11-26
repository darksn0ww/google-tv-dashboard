package com.home.dashboard

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** [message] is short enough for a widget and never contains the token or the address. */
class HaException(message: String) : IOException(message)

data class HaEntity(
    val id: String,
    val state: String,
    val attributes: JSONObject,
    val updated: Long, // epoch millis of `last_reported`: when the value was last confirmed, changed or not
) {
    val available get() = state != "unavailable" && state != "unknown"
    fun number(attribute: String): Double? = attributes.optDouble(attribute).takeIf { !it.isNaN() }
}

/** Minimal Home Assistant REST client, signed in with a long-lived access token. */
class HomeAssistant(baseUrl: String, private val token: String) {
    private val base = baseUrl.trim().trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /** Blocking; call off the main thread. */
    fun state(entityId: String): HaEntity {
        val body = send("$base/api/states/$entityId", null).decodeToString()
        return runCatching { parseEntity(body) }.getOrElse { throw HaException("bad response") }
    }

    /** The picture behind an `image` entity. */
    fun image(entityId: String): ByteArray = send("$base/api/image_proxy/$entityId", null)

    /** Runs a service on one entity, e.g. `switch.turn_on`. Blocking. */
    fun call(domain: String, service: String, entityId: String) {
        send("$base/api/services/$domain/$service", JSONObject().put("entity_id", entityId).toString())
    }

    private fun send(url: String, json: String?): ByteArray =
        try {
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer ${token.trim()}")
                .apply { if (json != null) post(json.toRequestBody("application/json".toMediaType())) }
                .build()
            http.newCall(request).execute().use { r ->
                when {
                    r.code == 401 -> throw HaException("token rejected")
                    r.code == 404 -> throw HaException("entity not found")
                    !r.isSuccessful -> throw HaException("HTTP ${r.code}")
                    else -> r.body!!.bytes()
                }
            }
        } catch (e: HaException) {
            throw e
        } catch (e: IOException) {
            throw HaException("offline")
        } catch (e: IllegalArgumentException) {
            throw HaException("bad URL or token")
        }
}

fun parseEntity(json: String): HaEntity {
    val j = JSONObject(json)
    return HaEntity(
        id = j.getString("entity_id"),
        state = j.getString("state"),
        attributes = j.optJSONObject("attributes") ?: JSONObject(),
        // Older Home Assistant versions have no last_reported.
        updated = OffsetDateTime.parse(j.optString("last_reported").ifBlank { j.getString("last_updated") })
            .toInstant().toEpochMilli(),
    )
}
