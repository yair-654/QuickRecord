@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.israel.quickrecord.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.israel.quickrecord.AppState
import com.israel.quickrecord.ErrorLog
import com.israel.quickrecord.Evidence
import com.israel.quickrecord.Library
import com.israel.quickrecord.R
import com.israel.quickrecord.RecStatus
import com.israel.quickrecord.RecorderState
import com.israel.quickrecord.Sizes
import com.israel.quickrecord.Times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A slow job (export or verify) shown in a dialog with a progress bar. */
private class Job(val title: String) {
    var progress by mutableFloatStateOf(0f)
    var result by mutableStateOf<String?>(null)
    var ok by mutableStateOf(true)
}

@Composable
fun RecordingsScreen(pad: PaddingValues) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val idle = RecorderState.status == RecStatus.IDLE
    var sessions by remember { mutableStateOf<List<Library.Session>?>(null) }
    LaunchedEffect(UiTick.resumed, reload, idle) {
        sessions = withContext(Dispatchers.IO) { Library.loadSessions(ctx) }
    }

    var open by remember { mutableStateOf<Library.Session?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var renaming by remember { mutableStateOf<Library.Session?>(null) }
    var deleting by remember { mutableStateOf<List<Library.Session>?>(null) }

    // Selection: long-press a recording to start, tap to add or remove more.
    val selected = remember { mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected.clear() }
    val chosen = sessions?.filter { it.id in selected } ?: emptyList()
    fun toggle(id: String) { if (id in selected) selected.remove(id) else selected.add(id) }

    val deleteConfirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload++ }
    val mediaPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload++ }

    fun runJob(j: Job, work: suspend ((Float) -> Unit) -> Pair<String, Boolean>) {
        job = j
        scope.launch {
            try {
                val (text, ok) = withContext(Dispatchers.IO) { work { p -> j.progress = p } }
                j.result = text
                j.ok = ok
            } catch (e: Exception) {
                ErrorLog.problem("${j.title} failed: ${e.message}", e)
                j.result = "It didn't work: ${e.message}"
                j.ok = false
            }
            reload++
        }
    }

    fun export(list: List<Library.Session>) = runJob(Job("Export")) { progress ->
        val r = Evidence.exportPackage(ctx, list, progress)
        val lines = r.checks.joinToString("\n") { "• ${it.file}: ${it.result}" }
        Pair("Saved to ${r.name}\n\n$lines", r.allVerified)
    }

    fun verify(list: List<Library.Session>) = runJob(Job("Verify")) { progress ->
        val total = list.sumOf { it.totalBytes }.coerceAtLeast(1).toFloat()
        var before = 0L
        val lines = ArrayList<String>()
        var allOk = true
        for (s in list) {
            val share = s.totalBytes / total
            val start = before / total
            val checks = Evidence.verify(ctx, s) { p -> progress(start + p * share) }
            before += s.totalBytes
            if (list.size > 1) lines += (s.title ?: Times.nice(s.startedMs))
            checks.forEach { lines += "• ${it.file}: ${it.result}" }
            if (!checks.all { it.ok }) allOk = false
        }
        Pair(lines.joinToString("\n"), allOk)
    }

    Box(Modifier.fillMaxSize().padding(pad)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = if (selecting) 170.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val list = sessions
                if (selecting) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${selected.size} selected", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                        if (list != null && selected.size < list.size) {
                            TextButton(onClick = { selected.clear(); selected.addAll(list.map { it.id }) }) { Text("Select all") }
                        }
                        TextButton(onClick = { selected.clear() }) { Text("Cancel") }
                    }
                } else {
                    Text("Recordings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
                    if (list != null) {
                        Text(
                            if (list.isEmpty()) "Nothing recorded yet"
                            else "${list.size} recording${if (list.size == 1) "" else "s"} · ${Sizes.bytes(list.sumOf { it.totalBytes })}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        if (list.size > 1) {
                            Text(
                                "Tip: press and hold a recording to select several.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            if (!selecting && !ctx.granted(Manifest.permission.READ_MEDIA_VIDEO)) {
                item {
                    Panel(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Missing older recordings?", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "After QuickRecord is reinstalled, Android no longer shows it the recordings it made before. Allow access to see them here again. Not needed otherwise.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = {
                                mediaPerm.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO))
                            }) { Text("Allow access") }
                        }
                    }
                }
            }
            val list = sessions
            if (list == null) {
                item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else if (list.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Ico(R.drawable.ic_library, size = 56, tint = MaterialTheme.colorScheme.outline)
                        Gap()
                        Text("Your recordings will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(list, key = { it.id }) { s ->
                    SessionCard(
                        s,
                        isSelected = s.id in selected,
                        selecting = selecting,
                        onClick = { if (selecting) toggle(s.id) else open = s },
                        onLongClick = { toggle(s.id) },
                    )
                }
            }
        }

        if (selecting) {
            SelectionBar(
                Modifier.align(Alignment.BottomCenter),
                onExport = { export(chosen) },
                onVerify = { verify(chosen) },
                onShare = { share(ctx, chosen) },
                onDelete = { deleting = chosen },
            )
        }
    }

    open?.let { s ->
        SessionSheet(
            s,
            onDismiss = { open = null },
            onExport = { export(listOf(s)) },
            onVerify = { verify(listOf(s)) },
            onRename = { renaming = s },
            onDelete = { deleting = listOf(s) },
        )
    }

    job?.let { j ->
        AlertDialog(
            onDismissRequest = { if (j.result != null) job = null },
            title = { Text(if (j.result == null) "${j.title}…" else if (j.ok) "${j.title}: all good" else "${j.title}: please read") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val res = j.result
                    if (res == null) {
                        Text("Reading every byte to check its fingerprint. Big recordings take a while.")
                        Gap()
                        LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(res)
                    }
                }
            },
            confirmButton = {
                if (j.result != null) TextButton(onClick = { job = null; selected.clear() }) { Text("OK") }
            },
        )
    }

    renaming?.let { s ->
        var text by remember(s.id) { mutableStateOf(s.title ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this recording") },
            text = {
                Column {
                    Text(
                        "The name is shown here, written into the evidence record and added to the file names. The fingerprints don't change: they cover what was recorded, not the name.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Gap()
                    OutlinedTextField(value = text, onValueChange = { text = it.take(80) }, singleLine = true, label = { Text("Name") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = text
                    renaming = null
                    open = null
                    scope.launch {
                        withContext(Dispatchers.IO) { Library.rename(ctx, s, t) }
                        reload++
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { list ->
        val files = list.sumOf { it.pieces.size }
        val bytes = list.sumOf { it.totalBytes }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = {
                Text(if (list.size > 1) "Delete ${list.size} recordings?" else "Delete this recording?")
            },
            text = {
                Text(
                    "This deletes $files file${if (files == 1) "" else "s"} (${Sizes.bytes(bytes)}) and ${if (list.size == 1) "its evidence record" else "their evidence records"}. It can't be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        open = null
                        selected.clear()
                        scope.launch {
                            val confirm = withContext(Dispatchers.IO) { Library.delete(ctx, list) }
                            if (confirm != null) deleteConfirm.launch(IntentSenderRequest.Builder(confirm.intentSender).build())
                            reload++
                        }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** The actions for the selected recordings, floating at the bottom. */
@Composable
private fun SelectionBar(
    modifier: Modifier,
    onExport: () -> Unit,
    onVerify: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
    ) {
        FlowRow(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = onExport) { BtnContent(R.drawable.ic_download, "Export evidence") }
            FilledTonalButton(onClick = onShare) { BtnContent(R.drawable.ic_share, "Share") }
            OutlinedButton(onClick = onVerify) { BtnContent(R.drawable.ic_shield, "Verify") }
            OutlinedButton(onClick = onDelete) { BtnContent(R.drawable.ic_delete, "Delete") }
        }
    }
}

@Composable
private fun Thumb(piece: Library.Piece?, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, piece?.id) {
        value = if (piece == null || !piece.isVideo) null else withContext(Dispatchers.IO) {
            Library.thumbnail(ctx, piece, 480, 270)?.asImageBitmap()
        }
    }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Ico(if (piece?.isVideo == false) R.drawable.ic_audio else R.drawable.ic_videocam, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun SessionCard(
    s: Library.Session,
    isSelected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Panel(
        Modifier.clip(MaterialTheme.shapes.large).combinedClickable(
            onClick = onClick,
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
        ),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Thumb(s.pieces.firstOrNull(), Modifier.width(120.dp).aspectRatio(16f / 9f))
                if (selecting) {
                    Box(
                        Modifier.padding(6.dp).size(24.dp).clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) Ico(R.drawable.ic_check, size = 24, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            RowGap(14)
            Column(Modifier.weight(1f)) {
                Text(
                    s.title ?: Times.nice(s.startedMs),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (s.title != null) {
                    Text(Times.nice(s.startedMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    buildString {
                        if (s.totalDurationMs > 0) append(Times.duration(s.totalDurationMs)).append(" · ")
                        append(Sizes.bytes(s.totalBytes))
                        if (s.pieces.size > 1) append(" · ${s.pieces.size} pieces")
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.isAudio) Tag("Sound only", icon = R.drawable.ic_audio)
                    if (s.hasFingerprints) Tag("Fingerprinted", OkGreen, R.drawable.ic_shield)
                    if (s.wasInterrupted) Tag("Cut off", WarnAmber, R.drawable.ic_warning)
                }
            }
        }
    }
}

@Composable
private fun SessionSheet(
    s: Library.Session,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onVerify: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    AppState.version
    val record = s.record
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Thumb(s.pieces.firstOrNull(), Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable { s.pieces.firstOrNull()?.let { play(ctx, it) } })
            Gap(16)
            Text(s.title ?: Times.nice(s.startedMs), style = MaterialTheme.typography.titleLarge)
            Text(
                buildString {
                    if (s.title != null) append(Times.nice(s.startedMs)).append(" · ")
                    if (s.totalDurationMs > 0) append(Times.duration(s.totalDurationMs)).append(" · ")
                    append(Sizes.bytes(s.totalBytes))
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(16)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { s.pieces.firstOrNull()?.let { play(ctx, it) } }) { BtnContent(R.drawable.ic_play, "Play") }
                FilledTonalButton(onClick = { share(ctx, s) }) { BtnContent(R.drawable.ic_share, "Share") }
                FilledTonalButton(onClick = onExport) { BtnContent(R.drawable.ic_download, "Export evidence") }
                OutlinedButton(onClick = onVerify) { BtnContent(R.drawable.ic_shield, "Verify") }
                OutlinedButton(onClick = onRename) { BtnContent(R.drawable.ic_edit, "Rename") }
                OutlinedButton(onClick = onDelete) { BtnContent(R.drawable.ic_delete, "Delete") }
            }
            Gap(20)
            Text("Details", style = MaterialTheme.typography.titleMedium)
            Gap(4)
            if (record == null) {
                Text(
                    "No evidence record exists for this recording (it was made by an older version of QuickRecord, or before a reinstall). \"Verify\" and \"Export evidence\" still compute fingerprints for it now.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                record.read { j ->
                    val started = j.optJSONObject("started")
                    val ended = j.optJSONObject("ended")
                    val rec = j.optJSONObject("recording")
                    val loc = j.optJSONObject("location")
                    val dev = j.optJSONObject("device")
                    listOfNotNull(
                        started?.let { "Started" to "${it.optString("local")}\n${it.optString("utc")}" },
                        ended?.let { "Ended" to "${it.optString("local")}\n${it.optString("utc")}" },
                        j.optString("end_reason").takeIf { it.isNotEmpty() }?.let { "How it ended" to it },
                        rec?.let { "Recorded" to listOf(it.optString("mode"), it.optString("camera").takeIf { c -> c.isNotEmpty() && c != "null" }?.let { c -> "$c camera" }).filterNotNull().joinToString(" · ") },
                        rec?.optJSONObject("video")?.let { v -> "Video" to "${v.optInt("width")}×${v.optInt("height")} · ${v.optInt("frames_per_second")} fps · ${v.optString("codec")}" },
                        loc?.let { "Location" to "%.5f, %.5f (±%s m)".format(it.optDouble("latitude"), it.optDouble("longitude"), it.opt("accuracy_m")?.toString() ?: "?") },
                        dev?.let { "Phone" to "${it.optString("manufacturer")} ${it.optString("model")}, build ${it.optString("os_build")}" },
                        "Status" to j.optString("status"),
                    )
                }.forEach { (k, v) -> DetailRow(k, v) }
                if (record.read { it.optJSONObject("location") } != null) {
                    TextButton(onClick = { openMap(ctx, record) }) { Text("Show location on a map") }
                }
            }

            Gap(20)
            Text("Files", style = MaterialTheme.typography.titleMedium)
            val hashes = record?.pieceHashes() ?: emptyMap()
            Text(
                "Tap a file to open its folder in the Files app. ▶ plays it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (p in s.pieces) {
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { openFolder(ctx, p.folder) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(if (p.isVideo) R.drawable.ic_videocam else R.drawable.ic_audio)
                    RowGap(12)
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Internal storage/${p.folder.trimEnd('/')}/",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${if (p.durationMs > 0) Times.duration(p.durationMs) + " · " else ""}${Sizes.bytes(p.size)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        hashes[p.name]?.takeIf { it.isNotEmpty() }?.let { h ->
                            Text("SHA-256 ${h.take(16)}…", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { play(ctx, p) }) { Ico(R.drawable.ic_play, tint = MaterialTheme.colorScheme.primary) }
                }
            }
            if (record != null) {
                val recPath = remember(record) { recordFolder(record) }
                Gap(8)
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .clickable(enabled = recPath != null) { recPath?.let { openFolder(ctx, it) } }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(R.drawable.ic_shield, OkGreen)
                    RowGap(12)
                    Column(Modifier.weight(1f)) {
                        Text("REC_${s.id}.json (evidence record)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (recPath != null) "Internal storage/$recPath/" else "Kept inside the app only",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "The evidence record is a small text file written while recording: when it started and ended, where, with which phone and settings, and the fingerprint of every file, which proves the files haven't changed since. Android doesn't allow it in the same folder as videos, so it's kept apart. \"Export evidence\" puts them together in one zip.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BtnContent(icon: Int, text: String) {
    Ico(icon, size = 18, tint = MaterialTheme.colorScheme.primary)
    RowGap(6)
    Text(text)
}

@Composable
private fun DetailRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Where the public copy of the evidence record is, like "Documents/QuickRecord". */
private fun recordFolder(record: com.israel.quickrecord.SessionRecord): String? {
    val ctx = com.israel.quickrecord.App.instance
    val uri = record.publicUri ?: return null
    return try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.trimEnd('/') else null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Opens a folder in Android's own Files app. Android has no way to point at
 * one file inside it, so the folder is opened and the file is listed there.
 */
private fun openFolder(ctx: Context, relativePath: String) {
    val docId = "primary:" + relativePath.trim('/')
    val uri = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        ErrorLog.notice("Couldn't open the Files app. The file is in Internal storage/${relativePath.trim('/')}/.")
    }
}

private fun play(ctx: Context, p: Library.Piece) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(p.uri, if (p.isVideo) "video/mp4" else "audio/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    } catch (_: Exception) {
        ErrorLog.notice("No app on this phone can play ${p.name}.")
    }
}

private fun share(ctx: Context, s: Library.Session) = share(ctx, listOf(s))

private fun share(ctx: Context, sessions: List<Library.Session>) {
    val uris = ArrayList<Uri>(sessions.flatMap { it.pieces }.map { it.uri })
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    val allAudio = sessions.all { it.isAudio }
    val allVideo = sessions.none { it.isAudio }
    intent.type = when {
        allAudio -> "audio/*"
        allVideo -> "video/*"
        else -> "*/*"
    }
    intent.clipData = ClipData.newRawUri(null, uris[0]).apply { for (u in uris.drop(1)) addItem(ClipData.Item(u)) }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(intent, if (uris.size == 1) "Share recording" else "Share ${uris.size} files"))
    } catch (_: Exception) {
    }
}

private fun openMap(ctx: Context, record: com.israel.quickrecord.SessionRecord) {
    val loc = record.read { it.optJSONObject("location") } ?: return
    val lat = loc.optDouble("latitude")
    val lon = loc.optDouble("longitude")
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")))
    } catch (_: Exception) {
        ErrorLog.notice("No maps app is installed. The position is $lat, $lon.")
    }
}
