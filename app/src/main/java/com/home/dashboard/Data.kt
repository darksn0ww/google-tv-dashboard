package com.home.dashboard

import android.content.Context
import android.content.Intent
import android.media.tv.TvInputManager
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// ---------- Config (lives only on the TV, in the app's private files dir) ----------

/** [url] is the low-quality stream used for tiles; [hd] (optional) is used full screen. */
data class Camera(val name: String, val url: String, val hd: String = "") {
    val streamUrl: String get() = plain(url)
    val hdStreamUrl: String get() = plain(hd.ifBlank { url })

    /** UniFi Protect gives rtsps://host:7441/<id>?enableSrtp; Media3 needs the plain RTSP alias on 7447. */
    private fun plain(link: String): String {
        val u = Uri.parse(link)
        return if (u.scheme.equals("rtsps", true)) "rtsp://${u.host}:7447${u.path}" else link
    }
}

data class Config(
    val loaded: Boolean = false,
    val place: String = "",
    val unsplashKey: String = "",
    val wallpaper: String = "", // your own picture instead: a web link, or "file" for the one installed with the settings
    val unsplashQueries: List<String> = listOf("landscape"),
    val wallpaperMinutes: Int = 10,
    val autoLocation: Boolean = false, // look the location up from the internet connection
    val latitude: Double = 51.5,
    val longitude: Double = -0.12,
    val calendars: List<String> = emptyList(),
    val cameras: List<Camera> = emptyList(),
    val snapshotSeconds: Int = 15,
    val nest: NestConfig? = null,
    val homeAssistant: HaConfig? = null,
    val tado: Boolean = false,
    val tadoZone: String = "",
    val favourites: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
) {
    companion object {
        fun load(ctx: Context): Config {
            val f = File(ctx.filesDir, "config.json")
            // deploy.sh drops a new config in the app's external dir; move it into private storage.
            ctx.getExternalFilesDir(null)?.let { dir ->
                listOf("config.json" to f, "wallpaper" to File(ctx.filesDir, "wallpaper")).forEach { (name, target) ->
                    val incoming = File(dir, name)
                    if (incoming.exists()) runCatching { incoming.copyTo(target, overwrite = true); incoming.delete() }
                }
            }
            if (!f.exists()) return Config(loaded = true)
            val config = runCatching { parse(JSONObject(f.readText())) }.getOrDefault(Config(loaded = true))
            // Heating now comes from Home Assistant: don't keep an unused tado sign-in around.
            if (!config.homeAssistant?.climate.isNullOrBlank()) File(ctx.filesDir, "tado.json").delete()
            return config
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }

        private fun parse(j: JSONObject) = Config(
            loaded = true,
            place = j.optString("place"),
            unsplashKey = j.optString("unsplashKey"),
            wallpaper = j.optString("wallpaper"),
            unsplashQueries = j.optJSONArray("unsplashQueries").strings().ifEmpty { listOf("landscape") },
            wallpaperMinutes = j.optInt("wallpaperMinutes", 10).coerceAtLeast(1),
            autoLocation = j.optString("location") == "auto",
            latitude = j.optDouble("latitude", 51.5),
            longitude = j.optDouble("longitude", -0.12),
            calendars = j.optJSONArray("calendars").strings(),
            cameras = j.optJSONArray("cameras")?.let { a ->
                (0 until a.length()).map { val o = a.getJSONObject(it); Camera(o.getString("name"), o.getString("url"), o.optString("hd")) }
            }.orEmpty(),
            snapshotSeconds = j.optInt("snapshotSeconds", 15).coerceAtLeast(5),
            nest = j.optJSONObject("nest")?.let { n ->
                NestConfig(
                    n.optString("projectId"), n.optString("clientId"), n.optString("clientSecret"),
                    n.optString("refreshToken"), n.optString("room"),
                ).takeIf { it.projectId.isNotBlank() && it.refreshToken.isNotBlank() }
            },
            homeAssistant = j.optJSONObject("homeAssistant")?.let { h ->
                HaConfig(
                    h.optString("url"), h.optString("token"), h.optString("climate"),
                    h.optString("temperature"), h.optString("humidity"), h.optString("name"), h.optString("schedule"),
                    h.optString("vacuum"), h.optString("battery"),
                    h.optJSONArray("devices")?.let { a ->
                        (0 until a.length()).map { val d = a.getJSONObject(it); HaDevice(d.getString("name"), d.getString("entity")) }
                    }.orEmpty(),
                    h.optJSONArray("maps").strings(), h.optString("mapSelect"),
                )
            },
            tado = j.optBoolean("tado"),
            tadoZone = j.optString("tadoZone"),
            favourites = j.optJSONArray("favourites").strings(),
            hidden = j.optJSONArray("hidden").strings(),
        )
    }
}

// ---------- HTTP ----------

object Net {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body!!.string()
        }
    }
}

// ---------- Weather (Open-Meteo, no key) ----------

data class Day(
    val date: LocalDate, val code: Int, val hi: Int, val lo: Int,
    val rain: Int = 0, val rainMm: Double = 0.0, val uv: Int = 0,
    val sunrise: LocalDateTime? = null, val sunset: LocalDateTime? = null,
)
data class Hour(val time: LocalDateTime, val temp: Int, val code: Int, val isDay: Boolean, val rain: Int)
data class Weather(
    val temp: Int, val feels: Int, val code: Int, val isDay: Boolean,
    val hi: Int, val lo: Int, val days: List<Day>,
    val hours: List<Hour> = emptyList(),
    val humidity: Int = 0, val wind: Int = 0, val windDirection: Int = 0, val windUnit: String = "mph",
)

object WeatherApi {
    fun fetch(lat: Double, lon: Double): Weather {
        val unit = if (Locale.getDefault().country in setOf("GB", "US")) "mph" else "kmh"
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m,wind_direction_10m" +
            "&hourly=temperature_2m,weather_code,is_day,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum," +
            "uv_index_max,sunrise,sunset&wind_speed_unit=$unit&timezone=auto&forecast_days=10"
        return parse(Net.get(url), if (unit == "kmh") "km/h" else unit)
    }

    fun parse(json: String, windUnit: String): Weather {
        val j = JSONObject(json)
        val c = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        fun JSONObject.num(key: String, i: Int) = optJSONArray(key)?.optDouble(i)?.takeIf { !it.isNaN() } ?: 0.0
        fun JSONObject.time(key: String, i: Int) =
            runCatching { LocalDateTime.parse(getJSONArray(key).getString(i)) }.getOrNull()
        val dates = d.getJSONArray("time")
        val days = (0 until dates.length()).map { i ->
            Day(
                LocalDate.parse(dates.getString(i)),
                d.getJSONArray("weather_code").getInt(i),
                d.getJSONArray("temperature_2m_max").getDouble(i).roundToInt(),
                d.getJSONArray("temperature_2m_min").getDouble(i).roundToInt(),
                rain = d.num("precipitation_probability_max", i).roundToInt(),
                rainMm = d.num("precipitation_sum", i),
                uv = d.num("uv_index_max", i).roundToInt(),
                sunrise = d.time("sunrise", i),
                sunset = d.time("sunset", i),
            )
        }
        val h = j.optJSONObject("hourly")
        val times = h?.optJSONArray("time")
        val hours = (0 until (times?.length() ?: 0)).mapNotNull { i ->
            Hour(
                h!!.time("time", i) ?: return@mapNotNull null,
                h.num("temperature_2m", i).roundToInt(),
                h.num("weather_code", i).roundToInt(),
                h.num("is_day", i) == 1.0,
                h.num("precipitation_probability", i).roundToInt(),
            )
        }
        return Weather(
            temp = c.getDouble("temperature_2m").roundToInt(),
            feels = c.getDouble("apparent_temperature").roundToInt(),
            code = c.getInt("weather_code"),
            isDay = c.getInt("is_day") == 1,
            hi = days.firstOrNull()?.hi ?: 0,
            lo = days.firstOrNull()?.lo ?: 0,
            days = days,
            hours = hours,
            humidity = c.optInt("relative_humidity_2m"),
            wind = c.optDouble("wind_speed_10m", 0.0).roundToInt(),
            windDirection = c.optInt("wind_direction_10m"),
            windUnit = windUnit,
        )
    }
}

fun weatherLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Cloudy"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61 -> "Light rain"
    63 -> "Rain"
    65 -> "Heavy rain"
    66, 67 -> "Freezing rain"
    71, 73, 75, 77 -> "Snow"
    80, 81 -> "Showers"
    82 -> "Heavy showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorms"
    96, 99 -> "Thunderstorms with hail"
    else -> ""
}

// ---------- Automatic location (geojs.io, no key) ----------

data class Place(val latitude: Double, val longitude: Double, val name: String)

object Locator {
    /** Roughly where the internet connection is; the service sees the public IP address. */
    fun find(): Place = parse(Net.get("https://get.geojs.io/v1/ip/geo.json"))

    fun parse(json: String): Place {
        val j = JSONObject(json)
        // The coordinates arrive as strings.
        return Place(j.getString("latitude").toDouble(), j.getString("longitude").toDouble(), j.optString("city"))
    }
}

// ---------- Unsplash ----------

data class Photo(val id: String, val url: String, val author: String, val downloadLocation: String)

object Unsplash {
    private fun auth(key: String) = mapOf("Authorization" to "Client-ID $key", "Accept-Version" to "v1")

    fun random(cfg: Config): List<Photo> {
        val q = cfg.unsplashQueries.random()
        val url = "https://api.unsplash.com/photos/random?count=30&orientation=landscape&content_filter=high&query=" +
            URLEncoder.encode(q, "UTF-8")
        val arr = JSONArray(Net.get(url, auth(cfg.unsplashKey)))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Photo(
                id = o.getString("id"),
                url = o.getJSONObject("urls").getString("raw") + "&w=1920&h=1080&fit=crop&crop=entropy&q=82&fm=jpg",
                author = o.getJSONObject("user").getString("name"),
                downloadLocation = o.getJSONObject("links").getString("download_location"),
            )
        }.shuffled()
    }

    /** Required by Unsplash API guidelines when a photo is displayed. */
    fun trackDownload(cfg: Config, p: Photo) {
        runCatching { Net.get(p.downloadLocation, auth(cfg.unsplashKey)) }
    }
}

// ---------- Apps & inputs ----------

data class AppEntry(val pkg: String, val label: String, val banner: ImageBitmap?, val icon: ImageBitmap?, val intent: Intent)
data class InputEntry(val id: String, val label: String)
data class AppsData(
    val favourites: List<AppEntry> = emptyList(),
    val inputs: List<InputEntry> = emptyList(),
    val others: List<AppEntry> = emptyList(),
)

object Apps {
    fun load(ctx: Context, cfg: Config): AppsData {
        val pm = ctx.packageManager
        val ris = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0,
        )
        val all = ris.distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                val banner = runCatching {
                    (ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm))?.toBitmap(320, 180)?.asImageBitmap()
                }.getOrNull()
                val icon = runCatching { ai.loadIcon(pm).toBitmap(144, 144).asImageBitmap() }.getOrNull()
                AppEntry(
                    ai.packageName, ai.loadLabel(pm).toString(), banner, icon,
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setClassName(ai.packageName, ai.name),
                )
            }
        val byPkg = all.associateBy { it.pkg }
        val favs = cfg.favourites.mapNotNull { byPkg[it] }
        val others = all.filter { it.pkg !in cfg.favourites && it.pkg !in cfg.hidden }
            .sortedBy { it.label.lowercase() }
        val inputs = runCatching {
            val tim = ctx.getSystemService(TvInputManager::class.java)
            tim.tvInputList
                // Only physical sockets (HDMI, AV...). A streaming box has none, so the Inputs row disappears.
                .filter { it.isPassthroughInput && it.parentId == null && !it.isHidden(ctx) }
                .map {
                    val custom = it.loadCustomLabel(ctx)?.toString()?.takeIf { s -> s.isNotBlank() }
                    InputEntry(it.id, custom ?: it.loadLabel(ctx).toString())
                }
                .sortedBy { it.label }
        }.getOrDefault(emptyList())
        return AppsData(favs, inputs, others)
    }
}
