package com.home.dashboard

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Recent(
    val pkg: String,
    val title: String,
    val subtitle: String,
    val art: String, // file path or http(s) url, may be blank
    val position: Long,
    val duration: Long,
    val updated: Long,
) {
    val id get() = "$pkg|$title|$subtitle"
    val progress: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

/** "Recently played on this device", kept only in the app's private storage. */
object RecentStore {
    val items = MutableStateFlow<List<Recent>>(emptyList())
    private var loaded = false

    private val musicApps = setOf(
        "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "uk.co.bbc.sounds", "com.apple.android.music",
    )
    private const val MIN_DURATION = 3 * 60_000L // skips adverts and trailers
    private const val MAX_ITEMS = 12

    private fun file(ctx: Context) = File(ctx.filesDir, "recent.json")
    private fun artDir(ctx: Context) = File(ctx.filesDir, "art").apply { mkdirs() }

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        items.value = runCatching {
            val a = JSONArray(file(ctx).readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Recent(
                    o.getString("pkg"), o.getString("title"), o.optString("subtitle"), o.optString("art"),
                    o.optLong("position"), o.optLong("duration"), o.optLong("updated"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, list: List<Recent>) {
        items.value = list
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("pkg", it.pkg).put("title", it.title).put("subtitle", it.subtitle).put("art", it.art)
                    .put("position", it.position).put("duration", it.duration).put("updated", it.updated),
            )
        }
        runCatching { file(ctx).writeText(a.toString()) }
        val keep = list.map { it.art }.toSet()
        artDir(ctx).listFiles()?.filter { it.path !in keep }?.forEach { it.delete() }
    }

    @Synchronized
    fun record(ctx: Context, c: MediaController) {
        load(ctx)
        val pkg = c.packageName
        if (pkg == ctx.packageName || pkg in musicApps) return
        val md = c.metadata ?: return
        val state = c.playbackState ?: return
        if (state.state != PlaybackState.STATE_PLAYING && state.state != PlaybackState.STATE_PAUSED) return
        val title = (md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md.getText(MediaMetadata.METADATA_KEY_TITLE))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration in 1 until MIN_DURATION) return
        // Apps differ in where they put the show/channel name.
        val show = listOf(
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM,
        ).firstNotNullOfOrNull { k -> md.getText(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != title } }.orEmpty()
        // Plex puts "Season 1" in the album field (it publishes no episode number).
        val season = md.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?.let { Regex("^(?:Season|Series) (\\d+)$").find(it.trim()) }?.groupValues?.get(1)
        val subtitle = if (season != null && show.isNotEmpty()) "$show · S$season" else show

        var position = state.position.coerceAtLeast(0)
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }

        val id = "$pkg|$title|$subtitle"
        // Also replaces an earlier entry for this title that was saved before the subtitle arrived.
        val others = items.value.filter { it.id != id && !(it.pkg == pkg && it.title == title && subtitle.startsWith(it.subtitle)) }
        // Watched to the end: nothing left to resume.
        if (duration > 0 && position > duration * 0.95) {
            if (others.size != items.value.size) save(ctx, others)
            return
        }
        val previous = items.value.firstOrNull { it.id == id }
        val art = previous?.art?.takeIf { it.isNotEmpty() } ?: artFor(ctx, id, md)
        val entry = Recent(pkg, title, subtitle, art, position, duration, System.currentTimeMillis())
        save(ctx, (listOf(entry) + others).take(MAX_ITEMS))
    }

    private fun artFor(ctx: Context, id: String, md: MediaMetadata): String {
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) {
            return runCatching {
                val scale = minOf(1f, 480f / bmp.width)
                val small = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                val f = File(artDir(ctx), "${id.hashCode()}.jpg")
                f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                f.path
            }.getOrDefault("")
        }
        return listOf(
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { k -> md.getString(k)?.takeIf { it.startsWith("http") || it.startsWith("content:") } }.orEmpty()
    }
}

/**
 * Notification access is what Android requires to see other apps' media sessions. This listener
 * handles no notifications at all (and the manifest opts out of receiving them).
 */
class MediaListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val watched = mutableMapOf<MediaController, MediaController.Callback>()
    private var manager: MediaSessionManager? = null
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    // Players only report position on changes, so sample while something is playing.
    private val tick = object : Runnable {
        override fun run() {
            watched.keys.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .forEach { RecentStore.record(applicationContext, it) }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onListenerConnected() {
        runCatching {
            val me = ComponentName(this, MediaListener::class.java)
            manager = getSystemService(MediaSessionManager::class.java).also {
                it.addOnActiveSessionsChangedListener(sessionsChanged, me, handler)
                watch(it.getActiveSessions(me))
            }
            handler.postDelayed(tick, 30_000)
        }
    }

    override fun onListenerDisconnected() = stop()
    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun stop() {
        handler.removeCallbacksAndMessages(null)
        manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        watch(emptyList())
    }

    private fun watch(sessions: List<MediaController>) {
        watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watched.clear()
        sessions.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = RecentStore.record(applicationContext, c)
                override fun onPlaybackStateChanged(state: PlaybackState?) = RecentStore.record(applicationContext, c)
            }
            c.registerCallback(cb, handler)
            watched[c] = cb
            RecentStore.record(applicationContext, c)
        }
    }
}
