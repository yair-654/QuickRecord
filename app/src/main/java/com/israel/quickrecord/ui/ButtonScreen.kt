@file:OptIn(ExperimentalMaterial3Api::class)

package com.israel.quickrecord.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.israel.quickrecord.ButtonService
import com.israel.quickrecord.ButtonState
import com.israel.quickrecord.Prefs
import com.israel.quickrecord.R

@Composable
fun ButtonScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    UiTick.resumed
    Prefs.version
    val enabled = ButtonState.connected || ButtonState.isEnabledInSettings(ctx)

    // While this screen is open and in front, the gesture is only shown,
    // never acted on. (MainActivity.onPause switches this off as well.)
    LaunchedEffect(UiTick.resumed) { ButtonState.testMode = true }
    DisposableEffect(Unit) {
        ButtonState.resetTest()
        onDispose { ButtonState.testMode = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Button shortcut") },
                navigationIcon = { IconButton(onClick = onBack) { Ico(R.drawable.ic_back, tint = MaterialTheme.colorScheme.onSurface) } },
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                "Start and stop recording with the volume buttons, without looking at the phone. Your volume buttons keep working normally: after the gesture, the volume is put back.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )

            // ---- Step 1: turn it on ----
            SectionTitle("1. Turn it on")
            Panel(color = if (enabled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(if (enabled) R.drawable.ic_check else R.drawable.ic_accessibility, if (enabled) OkGreen else MaterialTheme.colorScheme.primary)
                        RowGap(12)
                        Text(
                            if (enabled) "The button shortcut is on" else "Android needs your OK",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (!enabled) {
                        Gap(8)
                        Text(
                            "Android only lets an app notice the volume buttons through its Accessibility settings. QuickRecord uses this only for the buttons: it can't read your screen or what you type.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Gap(8)
                        Text(
                            "Tap below, open \"QuickRecord button shortcut\" and switch it on.",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        )
                        Gap(8)
                        Button(onClick = { openAccessibility(ctx) }) { Text("Open Accessibility settings") }
                        Gap(12)
                        Text(
                            "If Android says the setting is restricted (this happens for apps not installed from an app store): open App info, tap ⋮ in the top corner, choose \"Allow restricted settings\", then try again.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { openAppInfo(ctx) }) { Text("Open App info") }
                    }
                }
            }

            // ---- Step 2: gesture ----
            SectionTitle("2. Choose the gesture")
            Panel {
                for ((value, label, detail) in listOf(
                    Triple("down3", "Volume Down 3 times", "Three quick presses. Hard to do by accident. Recommended."),
                    Triple("down2", "Volume Down 2 times", "Faster, but easy to do by accident when turning the volume down."),
                    Triple("up3", "Volume Up 3 times", "Three quick presses of Volume Up."),
                    Triple("either3", "Either button 3 times", "Three quick presses of Volume Up, or three of Volume Down: whichever is easier to reach. Mixing them doesn't count."),
                    Triple("up2", "Volume Up 2 times", "Faster, easier to do by accident."),
                    Triple("both", "Up and Down together", "Press both within a moment of each other. Very hard to do by accident."),
                )) {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .selectable(selected = Prefs.gesture == value, role = Role.RadioButton) { Prefs.gesture = value }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = Prefs.gesture == value, onClick = null, modifier = Modifier.padding(end = 12.dp))
                        Column {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    "Quick means each press within about half a second of the last.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // ---- Step 3: screen off ----
            SectionTitle("3. With the screen off")
            Panel {
                SwitchRow(
                    R.drawable.ic_discreet, "Works with the screen off",
                    "Lets the gesture work in your pocket, with the screen dark.",
                    Prefs.screenOffButton,
                    { Prefs.screenOffButton = it; ButtonService.settingsChanged() },
                    enabled = enabled,
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "How it works: while the screen is off, QuickRecord tells Android it controls the volume of a \"remote speaker\", the way casting apps let the buttons control a TV. Android then passes the volume presses to it. As soon as the screen turns on, it lets go and the buttons work normally again. It uses close to no battery: nothing runs between presses.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Gap(12)
                    Text("When the gesture works", style = MaterialTheme.typography.titleSmall)
                    Gap(6)
                    WorksRow("Screen on, locked or unlocked, even while music plays", true)
                    WorksRow("Screen off", Prefs.screenOffButton, if (Prefs.screenOffButton) null else "Switch on \"Works with the screen off\" above.")
                    WorksRow("Screen off, while music or a video plays", false, "The playing app gets the buttons. Turn the screen on first, or use the tile.")
                    WorksRow("Screen off, during a phone call", false, "The call gets the buttons.")
                    WorksRow("After a restart, before you unlock once", false, "Android lets no app save files until the first unlock.")
                    if (ButtonState.screenOffActive) {
                        Gap(6)
                        Tag("Screen-off listening is active right now", OkGreen)
                    }
                }
            }

            // ---- Step 4: test ----
            SectionTitle("4. Try it")
            Panel {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "While this screen is open, nothing is recorded: do your gesture and watch it being noticed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Gap(12)
                    val detected = ButtonState.testDetections
                    val color by animateColorAsState(if (detected > 0) OkGreen else MaterialTheme.colorScheme.outline, label = "t")
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column {
                            Text("${ButtonState.testPresses}", style = MaterialTheme.typography.headlineMedium)
                            Text("presses", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column {
                            Text("$detected", style = MaterialTheme.typography.headlineMedium, color = color)
                            Text("gestures noticed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (ButtonState.lastPress.isNotEmpty()) {
                        Gap(6)
                        Text("Last press: ${ButtonState.lastPress}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!enabled) {
                        Gap(6)
                        Text("Turn the shortcut on first (step 1).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Gap(6)
                    Text(
                        "To test the screen-off mode, leave this screen first (the test only runs while it's open), turn the screen off and do the gesture: a recording should start.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Gap(32)
        }
    }
}

@Composable
private fun WorksRow(what: String, works: Boolean, why: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Ico(
            if (works) R.drawable.ic_check else R.drawable.ic_warning,
            tint = if (works) OkGreen else WarnAmber, size = 18,
        )
        RowGap(10)
        Column(Modifier.weight(1f)) {
            Text(what, style = MaterialTheme.typography.bodyMedium)
            if (why != null) Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun openAccessibility(ctx: Context) {
    val me = ComponentName(ctx, ButtonService::class.java).flattenToString()
    try {
        ctx.startActivity(
            // Opens QuickRecord's own page in Accessibility settings. Android
            // doesn't publish a name for this request, so it is written out.
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, me)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

private fun openAppInfo(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}

