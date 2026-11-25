package com.israel.quickrecord.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.israel.quickrecord.ButtonService
import com.israel.quickrecord.ButtonState
import com.israel.quickrecord.ErrorLog
import com.israel.quickrecord.Prefs
import com.israel.quickrecord.R
import com.israel.quickrecord.Sizes

/** Where "Report a problem" goes. */
private const val ISSUES_URL = "https://github.com/yair-654/yairs-kav-extensions/issues"

/** Which choice dialog is open. */
private enum class Pick { CAMERA, RES, FPS, QUALITY, CODEC, PIECE, AUTOSTOP, MINFREE, BATTERY }

@Composable
fun SettingsScreen(pad: PaddingValues, openButton: () -> Unit, openLog: () -> Unit) {
    val ctx = LocalContext.current
    Prefs.version // redraw on every change
    var pick by remember { mutableStateOf<Pick?>(null) }
    var editFolder by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Gap(24)
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            "Every setting starts at a good default. Tap one to see what it does.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        SectionTitle("Shortcut")
        Panel {
            val on = ButtonState.connected || ButtonState.isEnabledInSettings(ctx)
            NavRow(
                R.drawable.ic_volume, "Button shortcut",
                if (on) "${gestureLabel(Prefs.gesture)}${if (Prefs.screenOffButton) " · also with the screen off" else ""}" else "Off. Tap to set up",
                onClick = openButton,
            )
        }

        SectionTitle("Video")
        Panel {
            NavRow(R.drawable.ic_flip, "Camera", if (Prefs.camera == "front") "Front (selfie) camera" else "Back camera") { pick = Pick.CAMERA }
            NavRow(R.drawable.ic_videocam, "Resolution", resLabel(Prefs.resolution)) { pick = Pick.RES }
            NavRow(R.drawable.ic_timer, "Frame rate", "${Prefs.fps} frames per second") { pick = Pick.FPS }
            NavRow(R.drawable.ic_shield, "Quality", qualityLabel(Prefs.quality) + " · about ${Sizes.bytes(Prefs.bytesPerMinute())} per minute") { pick = Pick.QUALITY }
            NavRow(R.drawable.ic_library, "Video format", if (Prefs.codec == "hevc") "HEVC (smaller files)" else "H.264 (plays everywhere)") { pick = Pick.CODEC }
            SwitchRow(R.drawable.ic_videocam, "Stabilization", "Smooths shaky hands. Crops the picture slightly.", Prefs.stabilization, { Prefs.stabilization = it })
            SwitchRow(R.drawable.ic_torch, "Light on at start", "Turns the flashlight on when recording starts. You can switch it during recording too.", Prefs.torchAtStart, { Prefs.torchAtStart = it })
        }

        SectionTitle("Sound")
        Panel {
            SwitchRow(
                R.drawable.ic_mic, "Record sound",
                if (Prefs.sound) "On: videos include sound from the microphone. If the camera isn't allowed or can't start, QuickRecord records sound only."
                else "Off: videos are silent and the microphone is never used. With the camera not allowed, nothing can be recorded.",
                Prefs.sound, { Prefs.sound = it },
            )
        }

        SectionTitle("Saving")
        Panel {
            NavRow(R.drawable.ic_list, "Piece length", pieceLabel(Prefs.pieceMinutes)) { pick = Pick.PIECE }
            NavRow(R.drawable.ic_folder, "Folder", "Movies/${Prefs.folder} (sound only: Recordings/${Prefs.folder})") { editFolder = true }
        }

        SectionTitle("Safety stops")
        Panel {
            NavRow(R.drawable.ic_timer, "Stop automatically", if (Prefs.autoStopMinutes == 0) "Never" else "After ${durLabel(Prefs.autoStopMinutes)}") { pick = Pick.AUTOSTOP }
            NavRow(R.drawable.ic_storage, "Keep storage free", "Stops when less than ${Sizes.bytes(Prefs.minFreeMb * 1_000_000L)} is left") { pick = Pick.MINFREE }
            NavRow(R.drawable.ic_battery, "Low battery", if (Prefs.batteryStopPercent == 0) "Never stop for battery" else "Stops at ${Prefs.batteryStopPercent}%") { pick = Pick.BATTERY }
        }

        SectionTitle("Privacy and feedback")
        Panel {
            SwitchRow(R.drawable.ic_location, "Save location",
                "Puts where you were into the video and its evidence record. Uses the phone's last known position, so it never delays the start.",
                Prefs.location, {
                    Prefs.location = it
                    if (it && !ctx.granted(Manifest.permission.ACCESS_FINE_LOCATION) && !ctx.granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                })
            SwitchRow(R.drawable.ic_discreet, "Discreet mode",
                "No start or stop vibration, and the notification and tile just say \"QuickRecord\". Android still shows its green camera dot: no app can hide that. A failure still buzzes so you know.",
                Prefs.discreet, { Prefs.discreet = it })
            SwitchRow(R.drawable.ic_vibration, "Vibrate",
                "One short buzz when recording starts, two when it's saved, a long one if something fails.",
                Prefs.vibration, { Prefs.vibration = it })
        }

        SectionTitle("Help")
        Panel {
            NavRow(R.drawable.ic_warning, "Problems log", if (ErrorLog.entries.isEmpty()) "Nothing logged" else "${ErrorLog.entries.size} entries", onClick = openLog)
            NavRow(R.drawable.ic_bug, "Report a problem", "Opens QuickRecord's issues page on GitHub") {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    ErrorLog.notice("No browser is installed. The issues page is $ISSUES_URL")
                }
            }
            NavRow(R.drawable.ic_reset, "Reset settings", "Back to the recommended settings. Recordings are not touched.") { confirmReset = true }
        }

        Gap(16)
        AboutBlock()
        MadeInIsrael()
    }

    when (pick) {
        Pick.CAMERA -> ChoiceDialog(
            "Camera", "Which camera starts recording. Switching cameras while recording isn't possible without a gap, so it is set here.",
            listOf(Choice("back", "Back camera", "Best quality, and it faces what you're looking at."), Choice("front", "Front camera", "Faces you.")),
            Prefs.camera, "back", { Prefs.camera = it; pick = null }, { pick = null })
        Pick.RES -> ChoiceDialog(
            "Resolution", "How sharp the video is. Sharper means bigger files. If the camera can't do your choice, QuickRecord uses the next best one and notes it in the problems log.",
            listOf(
                Choice(720, "720p (HD)", "Small files, fine for faces and voices."),
                Choice(1080, "1080p (Full HD)", "Sharp, and still a sensible file size."),
                Choice(2160, "2160p (4K)", "Most detail, about 3 times bigger files, makes the phone warm."),
            ), Prefs.resolution, 1080, { Prefs.resolution = it; pick = null }, { pick = null })
        Pick.FPS -> ChoiceDialog(
            "Frame rate", "Pictures per second. 60 shows fast movement more smoothly but makes bigger files and is darker indoors.",
            listOf(Choice(30, "30 fps", "Standard, brighter in poor light."), Choice(60, "60 fps", "Smoother motion.")),
            Prefs.fps, 30, { Prefs.fps = it; pick = null }, { pick = null })
        Pick.QUALITY -> ChoiceDialog(
            "Quality", "How much detail is kept in each second of video (the bitrate). Higher keeps more detail in movement and low light, and uses more space.",
            listOf(
                Choice("saver", "Space saver", "Smallest files."),
                Choice("standard", "Standard", "A good balance."),
                Choice("high", "High", "Most detail, about 60% bigger files."),
            ), Prefs.quality, "standard", { Prefs.quality = it; pick = null }, { pick = null })
        Pick.CODEC -> ChoiceDialog(
            "Video format", "How the video is compressed.",
            listOf(
                Choice("h264", "H.264", "Plays on every phone, computer and court system. Best for evidence."),
                Choice("hevc", "HEVC (H.265)", "About 40% smaller files, but some older computers can't play it without extra software."),
            ), Prefs.codec, "h264", { Prefs.codec = it; pick = null }, { pick = null })
        Pick.PIECE -> ChoiceDialog(
            "Piece length",
            "A long recording is saved as several files. A video file can only be played once it is properly closed, so if the phone crashes or dies mid-recording, only the piece being written is at risk: every earlier piece is already safe and fingerprinted. Pieces follow each other without any gap. The split is by size, so the length is approximate.",
            listOf(
                Choice(2, "About 2 minutes", "Safest, more files."),
                Choice(5, "About 5 minutes", "Safe, with a reasonable number of files."),
                Choice(10, "About 10 minutes"),
                Choice(15, "About 15 minutes"),
                Choice(30, "About 30 minutes"),
                Choice(0, "One file", "Not split (only at the 3.8 GB file limit). A crash can lose the whole recording."),
            ), Prefs.pieceMinutes, 5, { Prefs.pieceMinutes = it; pick = null }, { pick = null })
        Pick.AUTOSTOP -> ChoiceDialog(
            "Stop automatically", "Ends the recording by itself after this long, for example if you forget it's running.",
            listOf(Choice(0, "Never"), Choice(15, "After 15 minutes"), Choice(30, "After 30 minutes"), Choice(60, "After 1 hour"), Choice(120, "After 2 hours"), Choice(240, "After 4 hours")),
            Prefs.autoStopMinutes, 0, { Prefs.autoStopMinutes = it; pick = null }, { pick = null })
        Pick.MINFREE -> ChoiceDialog(
            "Keep storage free", "Stops and saves the recording before the phone runs out of space, so the phone keeps working and the file isn't damaged.",
            listOf(Choice(500, "500 MB"), Choice(1000, "1 GB"), Choice(2000, "2 GB"), Choice(5000, "5 GB")),
            Prefs.minFreeMb, 1000, { Prefs.minFreeMb = it; pick = null }, { pick = null })
        Pick.BATTERY -> ChoiceDialog(
            "Low battery", "Stops and saves the recording before the battery dies, so nothing is lost. Ignored while charging.",
            listOf(Choice(0, "Never", "Not recommended: a dead battery can lose the last piece."), Choice(3, "3%"), Choice(5, "5%"), Choice(10, "10%"), Choice(15, "15%")),
            Prefs.batteryStopPercent, 5, { Prefs.batteryStopPercent = it; pick = null }, { pick = null })
        null -> {}
    }

    if (editFolder) {
        var text by remember { mutableStateOf(Prefs.folder) }
        AlertDialog(
            onDismissRequest = { editFolder = false },
            title = { Text("Folder") },
            text = {
                Column {
                    Text(
                        "Videos go to Movies/<folder>, sound-only recordings to Recordings/<folder>, evidence records to Documents/<folder> and exports to Download/<folder>. Existing recordings stay where they are and are still listed.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Gap()
                    OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, singleLine = true, label = { Text("Folder name") })
                }
            },
            confirmButton = { TextButton(onClick = { Prefs.folder = text; editFolder = false }) { Text("Save") } },
            dismissButton = {
                TextButton(onClick = { Prefs.folder = Prefs.DEFAULT_FOLDER; editFolder = false }) { Text("Use QuickRecord") }
            },
        )
    }

    if (confirmReset) {
        ConfirmDialog(
            "Reset settings?",
            "Every setting goes back to its recommended value, including the button gesture and the screen-off switch. Recordings, evidence records and the problems log are not touched.",
            "Reset",
            onConfirm = {
                Prefs.resetAll()
                ButtonService.settingsChanged()
            },
            onDismiss = { confirmReset = false },
            destructive = true,
        )
    }
}

@Composable
private fun AboutBlock() {
    val ctx = LocalContext.current
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "" }
    }
    Panel(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("QuickRecord $version", style = MaterialTheme.typography.titleSmall)
            Text(
                "No internet access, no accounts, no tracking. Everything stays on this phone until you share it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun gestureLabel(g: String) = when (g) {
    "down2" -> "Press Volume Down 2 times"
    "up3" -> "Press Volume Up 3 times"
    "up2" -> "Press Volume Up 2 times"
    "either3" -> "Press either volume button 3 times"
    "both" -> "Press Volume Up and Down together"
    else -> "Press Volume Down 3 times"
}

private fun resLabel(h: Int) = when (h) {
    720 -> "720p (HD)"
    2160 -> "2160p (4K)"
    else -> "1080p (Full HD)"
}

private fun qualityLabel(q: String) = when (q) {
    "saver" -> "Space saver"
    "high" -> "High"
    else -> "Standard"
}

private fun pieceLabel(m: Int) = if (m == 0) "One file (not split)" else "New file about every $m minutes"

private fun durLabel(m: Int) = if (m % 60 == 0) "${m / 60} h" else "$m min"
