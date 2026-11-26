package com.home.dashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Favourites edited on the TV. They replace the list in config.json until that list itself
 * changes, so installing the same settings again doesn't undo your edits. A copy is left where
 * adb can read it, and the setup scripts write it back into your config.json.
 */
object Favourites {
    private fun file(ctx: Context) = File(ctx.filesDir, "favourites.json")

    fun load(ctx: Context, fromConfig: List<String>): List<String> {
        val saved = runCatching { file(ctx).readText() }.getOrNull()
        if (saved != null) publish(ctx, saved) // covers edits made before copies were left
        return decode(saved, fromConfig)
    }

    fun save(ctx: Context, fromConfig: List<String>, list: List<String>) {
        val saved = encode(fromConfig, list)
        runCatching { file(ctx).writeText(saved) }
        publish(ctx, saved)
    }

    /** Package names only, nothing private. */
    private fun publish(ctx: Context, saved: String) {
        runCatching { File(ctx.getExternalFilesDir(null), "favourites.json").writeText(saved) }
    }

    fun encode(fromConfig: List<String>, list: List<String>): String =
        JSONObject().put("config", JSONArray(fromConfig)).put("list", JSONArray(list)).toString()

    fun decode(saved: String?, fromConfig: List<String>): List<String> = runCatching {
        val j = JSONObject(saved!!)
        fun strings(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
        if (strings("config") == fromConfig) strings("list") else fromConfig
    }.getOrDefault(fromConfig)

    /**
     * Moves [pkg] by [step] places among the apps that are [shown]. Entries for apps that aren't
     * installed stay in the list.
     */
    fun move(list: List<String>, shown: List<String>, pkg: String, step: Int): List<String> {
        val other = shown.getOrNull(shown.indexOf(pkg) + step) ?: return list
        if (pkg !in shown) return list
        return list.map { if (it == pkg) other else if (it == other) pkg else it }
    }
}
