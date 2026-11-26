package com.home.dashboard

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Notices newer releases; it never downloads or installs anything. At most once a day it asks
 * GitHub where "latest release" points, which says the version without an API call.
 */
object Updates {
    private const val LATEST = "https://github.com/darksn0ww/google-tv-dashboard/releases/latest"
    private const val DAY = 24 * 60 * 60_000L

    private val http = OkHttpClient.Builder().followRedirects(false).callTimeout(15, TimeUnit.SECONDS).build()

    /** The version in a release page address such as `.../releases/tag/v1.4.0`. */
    fun versionIn(address: String): String? = Regex("/releases/tag/v(\\d+(?:\\.\\d+)*)$").find(address)?.groupValues?.get(1)

    fun isNewer(latest: String, installed: String): Boolean {
        val a = latest.split('.').map { it.toIntOrNull() ?: 0 }
        val b = installed.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The newer version if there is one. Blocking; failures just mean "nothing to report". */
    fun check(ctx: Context): String? {
        val file = File(ctx.filesDir, "latest-version")
        var latest = runCatching { file.readText() }.getOrDefault("")
        if (System.currentTimeMillis() - file.lastModified() >= DAY) {
            val found = runCatching {
                http.newCall(Request.Builder().url(LATEST).head().build()).execute().use { r ->
                    versionIn(r.header("Location").orEmpty())
                }
            }.getOrNull()
            if (found != null) {
                latest = found
                runCatching { file.writeText(found) }
            }
        }
        val installed = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: return null
        return latest.takeIf { it.isNotEmpty() && isNewer(it, installed) }
    }
}
