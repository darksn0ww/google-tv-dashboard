package com.home.dashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Slide-out column on the right. A dialog, so the remote stays inside it and Back closes it. */
@Composable
fun SmartHomePanel(cfg: HaConfig, heating: HeatingState?, onClose: () -> Unit) {
    val home = remember(cfg) { SmartHome(cfg) }
    var state by remember { mutableStateOf<PanelState?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    val map = state?.map
    LaunchedEffect(map) {
        if (map == null) return@LaunchedEffect
        picture = withContext(Dispatchers.IO) {
            home.mapImage(map)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.trimmed()?.asImageBitmap() }
        } ?: picture
    }

    LaunchedEffect(home, reload) {
        while (true) {
            state = withContext(Dispatchers.IO) { home.read() }
            delay(4_000)
        }
    }
    fun command(run: () -> String?) {
        scope.launch {
            failed = withContext(Dispatchers.IO) { run() }
            delay(800) // give Home Assistant a moment to see the change
            reload++
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.width(340.dp).fillMaxHeight()
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Txt("Smart home", 22.sp, FontWeight.SemiBold, shadow = false)

                val reading = (heating as? HeatingState.Reading)?.heating
                if (reading != null) {
                    Section(reading.zone)
                    Txt("${deg(reading.current)} now, set to ${deg(reading.target)}", 15.sp, FontWeight.Medium, shadow = false)
                }

                var focusTaken = false
                fun firstFocus(): Modifier =
                    if (focusTaken) Modifier else Modifier.focusRequester(first).also { focusTaken = true }

                if (cfg.vacuum.isNotBlank()) {
                    Section("Vacuum")
                    val v = state?.vacuum
                    val battery = v?.battery?.let { " · $it%" }.orEmpty()
                    Txt(
                        v?.state?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }?.plus(battery) ?: "…",
                        15.sp, FontWeight.Medium, shadow = false,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelButton("Start", firstFocus().weight(1f)) { command { home.vacuum("start") } }
                        PanelButton("Pause", Modifier.weight(1f)) { command { home.vacuum("pause") } }
                        PanelButton("Dock", Modifier.weight(1f)) { command { home.vacuum("return_to_base") } }
                    }
                    picture?.let {
                        Image(
                            it, "Vacuum map",
                            Modifier.fillMaxWidth().height(200.dp)
                                .background(Color(0xFF2C2C2E), RoundedCornerShape(12.dp)).padding(6.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }

                if (cfg.devices.isNotEmpty()) {
                    Section("Plugs and lights")
                    cfg.devices.forEach { d ->
                        val on = state?.devices?.firstOrNull { it.device == d }?.on
                        val label = "${d.name} · ${when (on) { true -> "On"; false -> "Off"; null -> "unavailable" }}"
                        PanelButton(label, firstFocus().fillMaxWidth(), highlight = on == true) {
                            if (on != null) command { home.set(d, !on) }
                        }
                    }
                }

                (failed ?: state?.problem)?.let {
                    Spacer(Modifier.height(4.dp))
                    Txt(it.replaceFirstChar { c -> c.uppercase() }, 12.sp, color = Color(0xFFFF9F0A), shadow = false)
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(80)
            runCatching { first.requestFocus() }
        }
    }
}

/** Roborock maps sit in a wide transparent border; cut to what is drawn. */
private fun Bitmap.trimmed(): Bitmap {
    val row = IntArray(width)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            if (row[x] ushr 24 == 0) continue
            left = minOf(left, x)
            right = maxOf(right, x)
            top = minOf(top, y)
            bottom = y
        }
    }
    return if (right < 0) this else Bitmap.createBitmap(this, left, top, right - left + 1, bottom - top + 1)
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(6.dp))
    Txt(title.uppercase(), 11.sp, FontWeight.SemiBold, alpha = 0.6f, shadow = false)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelButton(label: String, modifier: Modifier, highlight: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (highlight) Color(0xFF5A4A1E) else Color(0xFF2C2C2E),
            focusedContainerColor = Color(0xFF636366),
            contentColor = Color.White, focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)), shape = shape),
        ),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 11.dp, horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Txt(label, 14.sp, FontWeight.SemiBold, maxLines = 1, shadow = false)
        }
    }
}
