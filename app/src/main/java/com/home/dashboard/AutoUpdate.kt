package com.home.dashboard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Opt-in self-update (`"autoUpdate": true`). It runs only while the screensaver is on, never asks
 * on screen, and installs only a newer release of this app signed with the same key. Android
 * checks that signature again itself.
 */
@RequiresApi(Build.VERSION_CODES.S) // silent updates arrived in Android 12
object AutoUpdate {
    private const val APK = "https://github.com/darksn0ww/google-tv-dashboard/releases/latest/download/tv-dashboard.apk"
    private const val RESULT = "com.home.dashboard.UPDATE_RESULT"
    private const val TAG = "DashboardUpdate"

    private val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build()

    /** Blocking. [stillIdle] is asked again just before installing. */
    fun run(ctx: Context, stillIdle: () -> Boolean) {
        val version = Updates.check(ctx, Updates.interval(autoUpdate = true)) ?: run {
            Log.i(TAG, "Nothing newer to install")
            return
        }
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            Log.i(TAG, "$version is out, but installing isn't allowed on this TV")
            return
        }
        // One attempt per version per day, so a refused update isn't downloaded over and over.
        val tried = File(ctx.filesDir, "update-tried")
        if (runCatching { tried.readText() }.getOrNull() == version &&
            System.currentTimeMillis() - tried.lastModified() < 24 * 60 * 60_000L
        ) {
            Log.i(TAG, "Already tried $version today")
            return
        }
        val apk = File(ctx.cacheDir, "update.apk")
        try {
            http.newCall(Request.Builder().url(APK).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                apk.outputStream().use { r.body!!.byteStream().copyTo(it) }
            }
            tried.writeText(version)
            val problem = reject(ctx, apk, version)
            if (problem != null) Log.w(TAG, "Not installing $version: $problem")
            else if (stillIdle()) {
                Log.i(TAG, "Installing $version")
                updating(ctx).writeText(version)
                install(ctx, apk)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Update to $version failed: ${e.message}")
        } finally {
            apk.delete()
        }
    }

    private fun updating(ctx: Context) = File(ctx.filesDir, "updating")

    /** True once, just after this app has replaced itself. */
    fun justUpdated(ctx: Context): Boolean {
        val marker = updating(ctx)
        val recent = marker.exists() && System.currentTimeMillis() - marker.lastModified() < 10 * 60_000L
        marker.delete()
        return recent
    }

    private fun signers(info: PackageInfo?) =
        info?.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray().toList() }.toSet()

    private fun reject(ctx: Context, apk: File, version: String): String? {
        val pm = ctx.packageManager
        val new = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return "not an app"
        val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        return when {
            new.packageName != ctx.packageName -> "a different app"
            new.versionName != version -> "version ${new.versionName}, not $version"
            signers(mine).isEmpty() || signers(new) != signers(mine) -> "signed with a different key"
            else -> null
        }
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                ctx, id, Intent(RESULT).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(result.intentSender)
        }
    }

    /** Success replaces this process, so only refusals are ever heard here. */
    fun watchResults(ctx: Context): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // Never interrupt with a prompt: drop it and leave the on-screen notice to say so.
                    val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                    runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                    Log.w(TAG, "Android wanted confirmation, so nothing was installed")
                } else {
                    Log.i(TAG, "Install status $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}")
                }
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }
}
