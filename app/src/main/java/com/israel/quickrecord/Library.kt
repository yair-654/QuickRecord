package com.israel.quickrecord

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import org.json.JSONObject

/**
 * The recordings on the phone, grouped into sessions (one session = one
 * press of record, possibly saved as several pieces).
 *
 * File names: VID_<yyyyMMdd_HHmmss>[_<title>]_p<NN>.mp4, AUD_... .m4a.
 * Recordings from QuickRecord 1.x (VID_<stamp>.mp4) are shown too.
 */
object Library {

    data class Piece(
        val id: Long,
        val uri: Uri,
        val name: String,
        val sessionId: String,
        val index: Int,
        val isVideo: Boolean,
        val size: Long,
        val durationMs: Long,
        val dateTakenMs: Long,
        val width: Int,
        val height: Int,
        val folder: String,
    )

    data class Session(
        val id: String,
        val pieces: List<Piece>,
        val record: SessionRecord?,
    ) {
        val isAudio get() = pieces.none { it.isVideo }
        val totalBytes get() = pieces.sumOf { it.size }
        val totalDurationMs get() = pieces.sumOf { it.durationMs }
        val title get() = record?.title
        val startedMs: Long
            get() = record?.startedMs?.takeIf { it > 0 }
                ?: pieces.minOfOrNull { it.dateTakenMs }?.takeIf { it > 0 }
                ?: parseStamp(id)
        val hasFingerprints get() = record != null && record.pieceHashes().isNotEmpty()
        val wasInterrupted get() = record?.status == "interrupted"
    }

    private val NEW_NAME = Regex("^(VID|AUD)_(\\d{8}_\\d{6})(?:_[^.]*?)?_p(\\d+)\\.(mp4|m4a)$")
    private val OLD_NAME = Regex("^(VID|AUD)_(\\d{8}_\\d{6})(?:_F)?\\.(mp4|m4a)$")

    fun parseName(name: String): Pair<String, Int>? {
        NEW_NAME.matchEntire(name)?.let { return Pair(it.groupValues[2], it.groupValues[3].toInt()) }
        OLD_NAME.matchEntire(name)?.let { return Pair(it.groupValues[2], 1) }
        return null
    }

    fun pieceName(isVideo: Boolean, sessionId: String, index: Int, title: String?): String {
        val prefix = if (isVideo) "VID" else "AUD"
        val ext = if (isVideo) "mp4" else "m4a"
        val slug = slug(title)
        val mid = if (slug.isEmpty()) "" else "_$slug"
        return String.format(java.util.Locale.US, "%s_%s%s_p%02d.%s", prefix, sessionId, mid, index, ext)
    }

    /** Letters (any language), digits and dashes; safe in every file system. */
    fun slug(title: String?): String {
        if (title.isNullOrBlank()) return ""
        val sb = StringBuilder()
        for (ch in title.trim()) {
            when {
                ch.isLetterOrDigit() -> sb.append(ch)
                ch == ' ' || ch == '-' || ch == '_' -> if (sb.isNotEmpty() && sb.last() != '-') sb.append('-')
            }
        }
        return sb.toString().trim('-').take(40)
    }

    private fun parseStamp(id: String): Long = try {
        java.time.LocalDateTime.parse(id, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            .atZone(Times.zone()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    // ---- Loading ----

    fun loadSessions(context: Context): List<Session> {
        val cr = context.contentResolver
        val pieces = ArrayList<Piece>()
        pieces += query(cr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        pieces += query(cr, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false)
        return pieces.groupBy { it.sessionId }
            .map { (id, list) -> Session(id, list.sortedBy { it.index }, SessionRecord.load(context, id)) }
            .sortedByDescending { it.startedMs }
    }

    private fun query(cr: ContentResolver, collection: Uri, video: Boolean): List<Piece> {
        val out = ArrayList<Piece>()
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        if (video) {
            projection += MediaStore.MediaColumns.WIDTH
            projection += MediaStore.MediaColumns.HEIGHT
        }
        val pattern = if (video) "VID\\_%" else "AUD\\_%"
        try {
            cr.query(
                collection, projection.toTypedArray(),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'", arrayOf(pattern), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val parsed = parseName(name) ?: continue
                    val id = c.getLong(0)
                    out += Piece(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = name,
                        sessionId = parsed.first,
                        index = parsed.second,
                        isVideo = video,
                        size = c.getLong(2),
                        durationMs = c.getLong(3),
                        dateTakenMs = c.getLong(4),
                        width = if (video) c.getInt(6) else 0,
                        height = if (video) c.getInt(7) else 0,
                        folder = c.getString(5) ?: "",
                    )
                }
            }
        } catch (e: Exception) {
            ErrorLog.problem("Could not list recordings: ${e.message}", e)
        }
        return out
    }

    // ---- Rename ----

    /**
     * Gives the session a title. The title is shown in the app, written into
     * the evidence record, and added to the file names (the fingerprints do
     * not change: they cover the content, not the name).
     */
    fun rename(context: Context, session: Session, title: String) {
        val cr = context.contentResolver
        val clean = title.trim().take(80)
        val record = session.record
        for (p in session.pieces) {
            if (!NEW_NAME.matches(p.name)) continue // leave 1.x names alone
            val newName = pieceName(p.isVideo, session.id, p.index, clean)
            if (newName == p.name) continue
            try {
                cr.update(p.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null)
                record?.edit { j ->
                    val arr = j.getJSONArray("pieces")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optString("file") == p.name) o.put("file", newName)
                    }
                }
            } catch (e: Exception) {
                ErrorLog.problem("Could not rename ${p.name}: ${e.message}", e)
            }
        }
        if (record != null) {
            record.edit { it.put("title", clean) }
            record.addEvent(System.currentTimeMillis(), if (clean.isEmpty()) "Title removed" else "Title set to \"$clean\"")
            record.save(context)
        }
    }

    // ---- Delete ----

    /**
     * Deletes the session's files and records. Returns a system confirmation
     * to show for files Android no longer counts as ours (for example after a
     * reinstall), or null when everything is gone already.
     */
    fun delete(context: Context, session: Session): PendingIntent? {
        val cr = context.contentResolver
        val needConfirm = ArrayList<Uri>()
        for (p in session.pieces) {
            try {
                cr.delete(p.uri, null, null)
            } catch (_: SecurityException) {
                needConfirm += p.uri
            } catch (e: Exception) {
                ErrorLog.problem("Could not delete ${p.name}: ${e.message}", e)
            }
        }
        session.record?.deletePublic(context)
        SessionRecord.deleteLocal(context, session.id)
        AppState.setProtected(session.id, false)
        return if (needConfirm.isEmpty()) null else MediaStore.createDeleteRequest(cr, needConfirm)
    }

    // ---- Recovery after a crash ----

    /**
     * Finds pieces still marked "pending" from an earlier run (the app was
     * killed while recording), fingerprints and publishes them, and marks
     * their records as interrupted.
     */
    fun recoverInterrupted(context: Context) {
        val cr = context.contentResolver
        val cutoffSec = (App.processStartWallMs - 5_000) / 1000
        val touched = HashMap<String, SessionRecord>()
        for ((collection, pattern) in listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "VID\\_%",
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "AUD\\_%",
        )) {
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(pattern))
            }
            val found = ArrayList<Triple<Uri, String, Long>>()
            try {
                cr.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED),
                    args, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getLong(3) >= cutoffSec) continue // belongs to a recording running now
                        found += Triple(ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1) ?: "", c.getLong(2))
                    }
                }
            } catch (e: Exception) {
                ErrorLog.problem("Could not look for interrupted recordings: ${e.message}", e)
            }
            for ((uri, name, size) in found) {
                val parsed = parseName(name)
                if (size <= 0L || parsed == null) {
                    try { cr.delete(uri, null, null) } catch (_: Exception) {}
                    continue
                }
                try {
                    val hash = Evidence.sha256(cr, uri)
                    cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    val record = touched.getOrPut(parsed.first) {
                        SessionRecord.load(context, parsed.first) ?: SessionRecord.create(context, parsed.first, parseStamp(parsed.first), "unknown")
                    }
                    val prev = record.lastHash()
                    record.edit { j ->
                        j.getJSONArray("pieces").put(JSONObject()
                            .put("index", parsed.second)
                            .put("file", name)
                            .put("original_file", name)
                            .put("bytes", size)
                            .put("sha256", hash)
                            .put("previous_sha256", prev ?: JSONObject.NULL)
                            .put("sha256_computed", SessionRecord.moment(System.currentTimeMillis()))
                            .put("closed_cleanly", false)
                            .put("note", "QuickRecord was stopped by Android or crashed while this piece was being written. It was fingerprinted when the app next opened. It may not play, or may play only partly."))
                    }
                    ErrorLog.problem("A recording was cut off ($name). It was saved, but this last piece may not play.")
                } catch (e: Exception) {
                    ErrorLog.problem("Could not recover $name: ${e.message}", e)
                }
            }
        }
        // Records still "recording" from an earlier run.
        for (id in SessionRecord.allIds(context)) {
            if (touched.containsKey(id)) continue
            val r = SessionRecord.load(context, id) ?: continue
            if (r.status == "recording" && r.startedMs < App.processStartWallMs - 5_000) touched[id] = r
        }
        for (r in touched.values) {
            r.edit { it.put("status", "interrupted").put("end_reason", "interrupted (the app was stopped while recording)") }
            r.save(context)
        }
    }

    fun thumbnail(context: Context, piece: Piece, w: Int, h: Int): android.graphics.Bitmap? = try {
        context.contentResolver.loadThumbnail(piece.uri, android.util.Size(w, h), null)
    } catch (_: Exception) {
        null
    }
}
