package com.home.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import kotlinx.coroutines.delay
import java.io.File

/** `autoUpdate` is on, but Android hasn't been told this app may install: only then is there anything to ask. */
fun needsInstallPermission(ctx: Context, cfg: Config): Boolean =
    cfg.autoUpdate && cfg.updateCheck && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !ctx.packageManager.canRequestPackageInstalls()

private fun asked(ctx: Context) = File(ctx.filesDir, "asked-install-permission")
fun alreadyAsked(ctx: Context) = asked(ctx).exists()

/** Asked once. Allowing happens on the TV's own settings screen; the app can't grant itself anything. */
@Composable
fun InstallPermissionPrompt(ctx: Context, onDone: () -> Unit) {
    val notNow = remember { FocusRequester() }
    fun done() {
        runCatching { asked(ctx).writeText("") }
        onDone()
    }
    Dialog(onDismissRequest = ::done) {
        Column(
            Modifier.width(420.dp).background(Color(0xFF1C1C1E), RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Txt("Update automatically?", 20.sp, FontWeight.SemiBold, shadow = false)
            val listedAs = remember { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() }
            Txt(
                "New releases can install while the screensaver is on. To allow it, switch on \"$listedAs\" " +
                    "in the list on the next screen, then press the Home button.",
                14.sp, lineHeight = 19.sp, shadow = false,
            )
            if (Build.MANUFACTURER.equals("TCL", ignoreCase = true)) {
                Txt(
                    "TCL TVs don't restart the Home button after an update. Only allow this if you run the watchdog.",
                    13.sp, color = Color(0xFFFF9F0A), lineHeight = 18.sp, shadow = false,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PromptButton("Not now", Modifier.focusRequester(notNow), ::done)
                PromptButton("Open settings", Modifier) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    done()
                }
            }
        }
        // The safe choice has the focus, so a stray press of OK changes nothing.
        LaunchedEffect(Unit) {
            delay(80)
            runCatching { notNow.requestFocus() }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PromptButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF3A3A3C), focusedContainerColor = Color.White,
            contentColor = Color.White, focusedContentColor = Color.Black,
        ),
    ) {
        androidx.tv.material3.Text(label, Modifier.padding(18.dp, 10.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
