package com.home.dashboard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import android.view.KeyEvent
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class MainActivity : ComponentActivity() {
    private val homePresses = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launcher has nowhere to go "back" to; Compose BackHandlers registered later still win.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        setContent { Dashboard(homePresses) }
    }

    override fun onResume() {
        super.onResume()
        // Harmless if notification access isn't granted; re-binds the media listener if it dropped.
        runCatching {
            NotificationListenerService.requestRebind(ComponentName(this, MediaListener::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePresses.value += 1
    }
}

/**
 * Google TV's own launcher always wins the HOME intent, so when it comes to the
 * foreground we immediately bring our dashboard up instead.
 */
class HomeOverrideService : AccessibilityService() {
    // At boot (or after an update) Google TV's home may already be showing when we connect,
    // and pressing Home again then fires no window change — so take over straight away. At any
    // other time stay out of the way: the watchdog may be reconnecting us while something plays.
    // The service is direct-boot aware (Android skips non-aware ones at boot and this TV never
    // retries), so it can connect before the user is unlocked; the dashboard can't start until then.
    private val handler = Handler(Looper.getMainLooper())
    private var returnAfterDream = false
    private var dreaming = false
    private var lastWindow = "" // class of the window that most recently opened
    private var updateResults: BroadcastReceiver? = null
    private val updateWhileIdle = object : Runnable {
        override fun run() {
            handler.postDelayed(this, Updates.interval(autoUpdate = true)) // a long screensaver looks again
            Thread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val cfg = Config.load(applicationContext)
                    if (cfg.autoUpdate && cfg.updateCheck) runCatching { AutoUpdate.run(applicationContext) { dreaming } }
                }
            }.start()
        }
    }

    private fun screensaverMarker() = File(filesDir, "screensaver-on")

    private fun scheduleUpdate() {
        handler.removeCallbacks(updateWhileIdle)
        handler.postDelayed(updateWhileIdle, UPDATE_AFTER_DREAMING)
        Log.i("DashboardUpdate", "Screensaver on; will look for an update shortly")
    }
    private val dreamEnded = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Updates wait until the screensaver has been on for a while: nobody is watching.
            dreaming = intent.action == Intent.ACTION_DREAMING_STARTED
            if (dreaming) {
                runCatching { screensaverMarker().writeText("") }
                scheduleUpdate()
                return
            }
            handler.removeCallbacks(updateWhileIdle)
            runCatching { screensaverMarker().delete() }
            // Otherwise waking up would land on the settings screen we opened.
            if (returnAfterDream) { returnAfterDream = false; showDashboard() }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateWhileIdle)
        updateResults?.let { runCatching { unregisterReceiver(it) } }
        runCatching { unregisterReceiver(dreamEnded) }
        super.onDestroy()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ContextCompat.registerReceiver(
            this, dreamEnded,
            IntentFilter(Intent.ACTION_DREAMING_STOPPED).apply { addAction(Intent.ACTION_DREAMING_STARTED) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) updateResults = AutoUpdate.watchResults(this)
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            val updated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && AutoUpdate.justUpdated(this)
            if (updated || SystemClock.elapsedRealtime() < JUST_BOOTED) showDashboard()
            resumeScreensaverWait()
        } else {
            ContextCompat.registerReceiver(
                this,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        runCatching { unregisterReceiver(this) }
                        showDashboard()
                    }
                },
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /**
     * Restarted while the screensaver was already on (nothing announces that again), so look for
     * an update anyway. The marker must be from this boot, and nothing may be playing.
     */
    private fun resumeScreensaverWait() {
        val marker = screensaverMarker()
        val bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (marker.exists() && marker.lastModified() > bootedAt && !getSystemService(AudioManager::class.java).isMusicActive) {
            dreaming = true
            scheduleUpdate()
        }
    }

    /**
     * Take the Home button before the system does, so Google TV never flashes up. Every other key
     * is passed straight through untouched. The window check below remains as a fallback for
     * anything that reaches Google TV's home another way.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) showDashboard()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        lastWindow = event.className?.toString()?.substringAfterLast('.').orEmpty()
        // The clock was just selected: press the pre-selected "Start now" on Google's ambient screen.
        if (SystemClock.elapsedRealtime() < startAmbientUntil && lastWindow in AMBIENT_SCREENS) {
            startAmbientUntil = 0
            pressStartNow(attempt = 1)
            return
        }
        if (event.packageName?.toString() != GOOGLE_TV_LAUNCHER) return
        val cls = event.className?.toString() ?: return
        if (!cls.contains("HomeActivity")) return
        showDashboard()
    }

    /**
     * The "Start now" row loads a moment after its window, and sometimes later than that, so try a
     * few times. Stops as soon as the screensaver starts or any other window opens.
     */
    private fun pressStartNow(attempt: Int) {
        handler.postDelayed({
            if (dreaming || lastWindow !in AMBIENT_SCREENS) return@postDelayed
            if (performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)) returnAfterDream = true
            if (attempt < 3) pressStartNow(attempt + 1)
        }, if (attempt == 1) 900L else 1_800L)
    }

    private fun showDashboard() {
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    override fun onInterrupt() {}

    companion object {
        const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"
        private const val UPDATE_AFTER_DREAMING = 2 * 60_000L
        private const val JUST_BOOTED = 5 * 60_000L
        private val AMBIENT_SCREENS = setOf("AmbientActivity", "AmbientSliceActivity")

        /** Set by the clock card; only within this window will "Start now" be pressed. */
        @Volatile var startAmbientUntil = 0L
    }
}
