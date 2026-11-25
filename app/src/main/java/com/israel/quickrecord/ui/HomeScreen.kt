package com.israel.quickrecord.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.israel.quickrecord.AppState
import com.israel.quickrecord.ButtonState
import com.israel.quickrecord.ErrorLog
import com.israel.quickrecord.Prefs
import com.israel.quickrecord.QRTileService
import com.israel.quickrecord.R
import com.israel.quickrecord.RecMode
import com.israel.quickrecord.RecStatus
import com.israel.quickrecord.RecordService
import com.israel.quickrecord.Recorder
import com.israel.quickrecord.RecorderState
import com.israel.quickrecord.Sizes
import com.israel.quickrecord.Times
import kotlinx.coroutines.delay

fun Context.granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

@Composable
fun HomeScreen(pad: PaddingValues, openButton: () -> Unit, openLog: () -> Unit, openRecordings: () -> Unit) {
    val ctx = LocalContext.current
    val status = RecorderState.status
    val active = status == RecStatus.RECORDING || status == RecStatus.PAUSED

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (true) {
            now = System.currentTimeMillis()
            delay(if (active) 250 else 5_000)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Gap(24)
        Text("QuickRecord", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        Text(
            when (status) {
                RecStatus.IDLE -> "Ready to record"
                RecStatus.STARTING -> "Starting…"
                RecStatus.RECORDING -> "Recording"
                RecStatus.PAUSED -> "Paused"
                RecStatus.STOPPING -> "Saving and fingerprinting…"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (active) RecordRed else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )

        Gap(36)
        RecordButton(status) {
            when (status) {
                RecStatus.IDLE -> Recorder.startFromApp(ctx)
                RecStatus.RECORDING, RecStatus.PAUSED -> Recorder.stop(ctx, Recorder.SOURCE_APP)
                else -> {}
            }
        }
        Gap(24)

        Text(
            if (active || status == RecStatus.STOPPING) Times.duration(RecorderState.elapsedMs(now)) else "0:00",
            style = TimerStyle,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Text(
            if (active) {
                RecorderState.summary + (if (RecorderState.piece > 1) " · piece ${RecorderState.piece}" else "")
            } else {
                plannedSummary(ctx)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        AnimatedVisibility(visible = active) {
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = {
                    RecordService.send(ctx, if (status == RecStatus.PAUSED) RecordService.ACTION_RESUME else RecordService.ACTION_PAUSE, Recorder.SOURCE_APP)
                }) {
                    Ico(if (status == RecStatus.PAUSED) R.drawable.ic_play else R.drawable.ic_pause, tint = MaterialTheme.colorScheme.onSecondaryContainer, size = 18)
                    RowGap()
                    Text(if (status == RecStatus.PAUSED) "Resume" else "Pause")
                }
                if (RecorderState.torchAvailable && RecorderState.mode != RecMode.AUDIO) {
                    FilledTonalButton(onClick = { RecordService.send(ctx, RecordService.ACTION_TORCH, Recorder.SOURCE_APP) }) {
                        Ico(R.drawable.ic_torch, tint = MaterialTheme.colorScheme.onSecondaryContainer, size = 18)
                        RowGap()
                        Text(if (RecorderState.torchOn) "Light off" else "Light on")
                    }
                }
            }
        }

        Gap(32)
        ProblemBanner(openLog)
        SetupCard(openButton)
        StorageCard(now, openRecordings)
        MadeInIsrael()
    }
}

/** What will be recorded with the current settings. */
private fun plannedSummary(ctx: Context): String {
    Prefs.version // redraw when settings change
    val cam = ctx.granted(Manifest.permission.CAMERA)
    val mic = ctx.granted(Manifest.permission.RECORD_AUDIO) && Prefs.sound
    if (!cam) return if (mic) "Sound only (the camera isn't allowed)" else "Nothing can be recorded yet"
    return buildString {
        append("${Prefs.resolution}p · ${Prefs.fps} fps · ")
        append(if (Prefs.camera == "front") "Front camera" else "Back camera")
        if (!mic) append(" · No sound")
    }
}

@Composable
private fun RecordButton(status: RecStatus, onClick: () -> Unit) {
    val active = status == RecStatus.RECORDING || status == RecStatus.PAUSED
    val busy = status == RecStatus.STARTING || status == RecStatus.STOPPING
    val pulse = rememberInfiniteTransition(label = "pulse")
    val halo by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "halo",
    )
    val ring by animateColorAsState(if (active) RecordRed else MaterialTheme.colorScheme.outlineVariant, label = "ring")
    val corner by animateFloatAsState(if (active) 0.28f else 0.5f, label = "corner")
    val inner by animateFloatAsState(if (active) 0.42f else 0.72f, label = "inner")

    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        if (status == RecStatus.RECORDING) {
            Box(
                Modifier.size(176.dp).scale(halo).clip(CircleShape).background(RecordRed.copy(alpha = 0.12f))
            )
        }
        Box(
            Modifier.size(176.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(6.dp, ring, CircleShape)
                .clickable(enabled = !busy, onClick = onClick)
                .semantics { contentDescription = if (active) "Stop recording" else "Start recording" },
            contentAlignment = Alignment.Center,
        ) {
            val c = if (busy) RecordRed.copy(alpha = 0.4f) else RecordRed
            Box(
                Modifier.size((176 * inner).dp).clip(RoundedCornerShape(percent = (corner * 100).toInt())).background(c)
            )
        }
    }
}

@Composable
private fun ProblemBanner(openLog: () -> Unit) {
    val problem = ErrorLog.recentProblem() ?: return
    Panel(color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Ico(R.drawable.ic_warning, tint = MaterialTheme.colorScheme.onErrorContainer)
            RowGap(12)
            Column(Modifier.weight(1f)) {
                Text("Something needs attention", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("${Times.niceTime(problem.timeMs)} · ${problem.message}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Row {
                    TextButton(onClick = openLog) { Text("Problems log") }
                    TextButton(onClick = { ErrorLog.dismissRecent() }) { Text("Dismiss") }
                }
            }
        }
    }
    Gap()
}

@Composable
private fun SetupCard(openButton: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    // Read so the card redraws when any of these change.
    @Suppress("UNUSED_VARIABLE") val watch = listOf(UiTick.resumed, AppState.version, refresh, ButtonState.connected)

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    val camMic = ctx.granted(Manifest.permission.CAMERA) && ctx.granted(Manifest.permission.RECORD_AUDIO)
    val notif = ctx.granted(Manifest.permission.POST_NOTIFICATIONS)
    val loc = ctx.granted(Manifest.permission.ACCESS_FINE_LOCATION) || ctx.granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    val tile = AppState.tileAdded
    val button = ButtonState.isEnabledInSettings(ctx)
    if (camMic && notif && tile && button && (loc || !Prefs.location)) return

    Panel {
        Text("Finish setting up", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
        SetupRow(R.drawable.ic_videocam, "Camera and microphone", "Needed to record video and sound.", camMic, "Allow") {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
        SetupRow(R.drawable.ic_info, "Notifications", "Shows a silent Stop and Pause notification while recording.", notif, "Allow") {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        SetupRow(R.drawable.ic_add, "Quick Settings tile", "Start from the lock screen with one tap.", tile, "Add") {
            requestTile(ctx) { refresh++ }
        }
        SetupRow(R.drawable.ic_volume, "Button shortcut", "Start and stop with the volume buttons.", button, "Set up", onAction = openButton)
        if (Prefs.location) {
            SetupRow(R.drawable.ic_location, "Location (optional)", "Saves where a recording was made. Turn off in Settings if you don't want it.", loc, "Allow") {
                permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }
    Gap()
}

@Composable
private fun SetupRow(icon: Int, title: String, detail: String, done: Boolean, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(if (done) R.drawable.ic_check else icon, if (done) OkGreen else MaterialTheme.colorScheme.primary)
        RowGap(16)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(if (done) "Done" else detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun requestTile(ctx: Context, done: () -> Unit) {
    try {
        val sbm = ctx.getSystemService(StatusBarManager::class.java)
        sbm.requestAddTileService(
            ComponentName(ctx, QRTileService::class.java),
            "QuickRecord",
            Icon.createWithResource(ctx, R.drawable.ic_tile),
            ctx.mainExecutor,
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            ) AppState.tileAdded = true
            done()
        }
    } catch (e: Exception) {
        ErrorLog.notice("Add the tile by hand: swipe down twice, tap the pencil, and drag QuickRecord up.")
    }
}

@Composable
private fun StorageCard(now: Long, openRecordings: () -> Unit) {
    Prefs.version
    val free = remember(now / 30_000) { Sizes.freeBytes() }
    val perMin = Prefs.bytesPerMinute()
    val minutesLeft = if (free > 0 && perMin > 0) (free - Prefs.minFreeMb * 1_000_000L).coerceAtLeast(0) / perMin else 0
    Panel {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(R.drawable.ic_storage)
            RowGap(16)
            Column(Modifier.weight(1f)) {
                Text(if (free >= 0) "${Sizes.bytes(free)} free" else "Storage", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "About ${Times.roughMinutes(minutesLeft)} of recording at your settings (${Sizes.bytes(perMin)} per minute)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = openRecordings) { Text("See recordings") }
        }
    }
}
