package com.israel.quickrecord

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The evidence record of one recording session: when, where, with what, and
 * the SHA-256 fingerprint of every piece, each chained to the one before.
 *
 * Two copies are kept: a private one inside the app (the one the app trusts)
 * and a public copy as Documents/<folder>/REC_<session>.json, so the record
 * can be found and handed over without the app.
 */
class SessionRecord private constructor(val id: String, private val j: JSONObject) {

    @Synchronized
    fun edit(block: (JSONObject) -> Unit) = block(j)

    @Synchronized
    fun <T> read(block: (JSONObject) -> T): T = block(j)

    /** The record as handed over: without the app's own bookkeeping. */
    @Synchronized
    fun publicJson(): JSONObject = JSONObject(j.toString()).apply { remove(INTERNAL) }

    val title: String? get() = read { it.optString("title").takeIf { t -> t.isNotBlank() } }
    val status: String get() = read { it.optString("status") }
    val startedMs: Long get() = read { it.optJSONObject("started")?.optLong("epoch_ms") ?: 0L }

    fun addEvent(ms: Long, what: String) = edit {
        it.getJSONArray("events").put(JSONObject().put("utc", Times.utc(ms)).put("local", Times.local(ms)).put("event", what))
    }

    fun addNote(text: String) = edit { it.getJSONArray("notes").put(text) }

    /** SHA-256 of the last piece written so far, or null. */
    fun lastHash(): String? = read {
        val arr = it.getJSONArray("pieces")
        if (arr.length() == 0) null else arr.getJSONObject(arr.length() - 1).optString("sha256").ifEmpty { null }
    }

    fun pieceHashes(): Map<String, String> = read {
        val out = HashMap<String, String>()
        val arr = it.getJSONArray("pieces")
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            out[p.optString("file")] = p.optString("sha256")
            out[p.optString("original_file")] = p.optString("sha256")
        }
        out
    }

    internal var publicUri: Uri?
        get() = read { it.optJSONObject(INTERNAL)?.optString("public_uri")?.takeIf { s -> s.isNotEmpty() }?.let(Uri::parse) }
        set(v) = edit {
            val o = it.optJSONObject(INTERNAL) ?: JSONObject().also { n -> it.put(INTERNAL, n) }
            o.put("public_uri", v?.toString() ?: "")
        }

    companion object {
        private const val INTERNAL = "_app"

        private fun dir(context: Context) = File(context.filesDir, "records").apply { mkdirs() }
        private fun file(context: Context, id: String) = File(dir(context), "$id.json")

        fun load(context: Context, id: String): SessionRecord? = try {
            val f = file(context, id)
            if (f.exists()) SessionRecord(id, JSONObject(f.readText())) else null
        } catch (e: Exception) {
            ErrorLog.problem("Could not read the record of $id: ${e.message}", e)
            null
        }

        fun allIds(context: Context): List<String> =
            dir(context).listFiles()?.filter { it.name.endsWith(".json") }?.map { it.name.removeSuffix(".json") } ?: emptyList()

        fun deleteLocal(context: Context, id: String) {
            file(context, id).delete()
        }

        fun create(context: Context, id: String, startedMs: Long, source: String): SessionRecord {
            val zoneNow = ZonedDateTime.now(Times.zone())
            val cr = context.contentResolver
            val j = JSONObject()
                .put("format", "QuickRecord evidence record")
                .put("format_version", 1)
                .put("session", id)
                .put("title", "")
                .put("status", "recording")
                .put("started", moment(startedMs).put("elapsed_realtime_ms", SystemClock.elapsedRealtime()))
                .put("start_source", source)
                .put("timezone", JSONObject()
                    .put("id", Times.zone().id)
                    .put("utc_offset", zoneNow.offset.id))
                .put("clock", JSONObject()
                    .put("automatic_time", Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, 0) == 1)
                    .put("automatic_timezone", Settings.Global.getInt(cr, Settings.Global.AUTO_TIME_ZONE, 0) == 1))
                .put("device", JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("device", Build.DEVICE)
                    .put("os_build", Build.DISPLAY)
                    .put("os_fingerprint", Build.FINGERPRINT)
                    .put("android_version", Build.VERSION.RELEASE)
                    .put("security_patch", Build.VERSION.SECURITY_PATCH))
                .put("app", appInfo(context))
                .put("events", JSONArray())
                .put("pieces", JSONArray())
                .put("notes", JSONArray()
                    .put("All times are given twice: in UTC (ending in Z) and in the phone's local time with its offset from UTC.")
                    .put("Each SHA-256 value was computed on the phone by QuickRecord right after the piece was closed and before any other app could see it. If a file still has the same SHA-256 today, it has not been changed by even one byte.")
                    .put("previous_sha256 links each piece to the one before it, so a missing, swapped or reordered piece can be noticed.")
                    .put("container_creation_time_utc is the time Android's recorder wrote inside the video file. Android writes the moment the piece was closed (its end), in UTC, as the MP4 format requires. Some players show it without converting to local time."))
            return SessionRecord(id, j)
        }

        fun moment(ms: Long): JSONObject =
            JSONObject().put("utc", Times.utc(ms)).put("local", Times.local(ms)).put("epoch_ms", ms)

        fun appInfo(context: Context): JSONObject = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            JSONObject().put("name", "QuickRecord").put("package", context.packageName)
                .put("version", pi.versionName).put("version_code", pi.longVersionCode)
        } catch (_: Exception) {
            JSONObject().put("name", "QuickRecord")
        }
    }

    // ---- Saving ----

    /** Writes both copies. Call from a background thread. */
    fun save(context: Context) {
        val text: String = read { it.toString(2) }
        try {
            val f = file(context, id)
            val tmp = File(f.parentFile, "$id.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            ErrorLog.problem("Could not save the record of $id: ${e.message}", e)
        }
        writePublic(context)
    }

    private fun writePublic(context: Context) {
        val cr = context.contentResolver
        val body = publicJson().toString(2).toByteArray()
        try {
            val existing = publicUri
            if (existing != null && writeTo(cr, existing, body)) return
            // Never written yet, or deleted by the user: make a new one.
            val created = insertPublic(cr, "REC_$id.json") ?: return
            if (writeTo(cr, created, body)) {
                publicUri = created
                // Remember where it went (private copy only).
                file(context, id).writeText(read { it.toString(2) })
            }
        } catch (e: Exception) {
            ErrorLog.problem("Could not write the public record file for $id: ${e.message}", e)
        }
    }

    private fun writeTo(cr: ContentResolver, uri: Uri, body: ByteArray): Boolean = try {
        val os = cr.openOutputStream(uri, "wt")
        if (os == null) false else {
            os.use { it.write(body) }
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun insertPublic(cr: ContentResolver, name: String): Uri? {
        val folder = Prefs.folder
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + folder)
        }
        return try {
            cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (_: Exception) {
            // Fall back to Download/, which every app may write to.
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder)
            try {
                cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                ErrorLog.problem("No place to save the public record file: ${e.message}", e)
                null
            }
        }
    }

    /** Removes the public copy (used when the recording is deleted). */
    fun deletePublic(context: Context) {
        val uri = publicUri ?: return
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }
}

object Evidence {

    fun sha256(cr: ContentResolver, uri: Uri, onBytes: ((Long) -> Unit)? = null): String =
        cr.openInputStream(uri)?.use { sha256(it, onBytes) } ?: throw IllegalStateException("Cannot open $uri")

    fun sha256(input: InputStream, onBytes: ((Long) -> Unit)? = null): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            onBytes?.invoke(n.toLong())
        }
        return hex(md.digest())
    }

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private const val MP4_EPOCH_OFFSET_S = 2082844800L // 1904-01-01 to 1970-01-01

    /**
     * Reads (never writes) the creation time inside an MP4 file's "mvhd" box.
     * Returns epoch milliseconds, or null if it can't be read.
     */
    fun mp4CreationTimeMs(cr: ContentResolver, uri: Uri): Long? = try {
        cr.openFileDescriptor(uri, "r")?.let { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                val ch = stream.channel
                val moov = findBox(ch, 0, ch.size(), "moov") ?: return@use null
                val mvhd = findBox(ch, moov.first, moov.second, "mvhd") ?: return@use null
                val head = ByteBuffer.allocate(20)
                ch.read(head, mvhd.first)
                head.flip()
                val version = head.get().toInt()
                head.position(4)
                val seconds = if (version == 1) head.long else (head.int.toLong() and 0xFFFFFFFFL)
                if (seconds <= 0) null else (seconds - MP4_EPOCH_OFFSET_S) * 1000
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Finds a box inside [start, end); returns (payload start, payload end). */
    private fun findBox(ch: FileChannel, start: Long, end: Long, type: String): Pair<Long, Long>? {
        var pos = start
        val hdr = ByteBuffer.allocate(16)
        while (pos + 8 <= end) {
            hdr.clear()
            hdr.limit(8)
            if (ch.read(hdr, pos) < 8) return null
            hdr.flip()
            var size = hdr.int.toLong() and 0xFFFFFFFFL
            val t = ByteArray(4).also { hdr.get(it) }.toString(Charsets.US_ASCII)
            var headerLen = 8L
            if (size == 1L) {
                val ext = ByteBuffer.allocate(8)
                if (ch.read(ext, pos + 8) < 8) return null
                ext.flip()
                size = ext.long
                headerLen = 16
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < headerLen || pos + size > end) return null
            if (t == type) return Pair(pos + headerLen, pos + size)
            pos += size
        }
        return null
    }

    // ---- Verify ----

    data class Check(val file: String, val result: String, val ok: Boolean)

    /** Re-fingerprints every piece and compares with the record. Slow for big files. */
    fun verify(context: Context, session: Library.Session, progress: (Float) -> Unit): List<Check> {
        val cr = context.contentResolver
        val expected = session.record?.pieceHashes() ?: emptyMap()
        val total = session.pieces.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        return session.pieces.map { p ->
            try {
                val actual = sha256(cr, p.uri) { n -> done += n; progress(done.toFloat() / total) }
                val want = expected[p.name]
                when {
                    want.isNullOrEmpty() -> Check(p.name, "No fingerprint on record. Today's SHA-256: $actual", false)
                    want == actual -> Check(p.name, "Unchanged since it was recorded", true)
                    else -> Check(p.name, "CHANGED: the file is not the same as when it was recorded", false)
                }
            } catch (e: Exception) {
                Check(p.name, "Could not read the file: ${e.message}", false)
            }
        }
    }

    // ---- Export ----

    data class ExportResult(val name: String, val allVerified: Boolean, val checks: List<Check>)

    /**
     * Writes Download/<folder>/Evidence_<session>.zip with every piece, the
     * record, a SHA256SUMS.txt and a short how-to-verify text. Every piece is
     * fingerprinted again while it is copied and compared with the record.
     */
    fun exportPackage(context: Context, session: Library.Session, progress: (Float) -> Unit): ExportResult {
        val cr = context.contentResolver
        val zipName = "Evidence_${session.id}.zip"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, zipName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Prefs.folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val out = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the export file in Download/")
        val expected = session.record?.pieceHashes() ?: emptyMap()
        val total = session.pieces.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        val checks = ArrayList<Check>()
        val sums = StringBuilder()
        try {
            cr.openOutputStream(out, "w")!!.use { os ->
                ZipOutputStream(os.buffered(1 shl 20)).use { zip ->
                    zip.setLevel(Deflater.NO_COMPRESSION) // video is already compressed
                    for (p in session.pieces) {
                        zip.putNextEntry(ZipEntry(p.name))
                        val md = MessageDigest.getInstance("SHA-256")
                        cr.openInputStream(p.uri)!!.use { input ->
                            val buf = ByteArray(1 shl 20)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                md.update(buf, 0, n)
                                zip.write(buf, 0, n)
                                done += n
                                progress(done.toFloat() / total)
                            }
                        }
                        zip.closeEntry()
                        val actual = hex(md.digest())
                        sums.append(actual).append("  ").append(p.name).append('\n')
                        val want = expected[p.name]
                        checks += when {
                            want.isNullOrEmpty() -> Check(p.name, "no fingerprint on record", false)
                            want == actual -> Check(p.name, "matches the record", true)
                            else -> Check(p.name, "DOES NOT match the record", false)
                        }
                    }
                    val record = session.record
                    if (record != null) {
                        zip.putNextEntry(ZipEntry("REC_${session.id}.json"))
                        zip.write(record.publicJson().toString(2).toByteArray())
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("SHA256SUMS.txt"))
                    zip.write(sums.toString().toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("HOW_TO_VERIFY.txt"))
                    zip.write(howToVerify(session, checks, record != null).toByteArray())
                    zip.closeEntry()
                }
            }
            cr.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { cr.delete(out, null, null) } catch (_: Exception) {}
            throw e
        }
        session.record?.let { r ->
            r.addEvent(System.currentTimeMillis(), "Evidence package exported as Download/${Prefs.folder}/$zipName")
            r.save(context)
        }
        return ExportResult("Download/${Prefs.folder}/$zipName", checks.all { it.ok }, checks)
    }

    private fun howToVerify(session: Library.Session, checks: List<Check>, hasRecord: Boolean): String {
        val now = System.currentTimeMillis()
        return buildString {
            appendLine("QuickRecord evidence package")
            appendLine("Session: ${session.id}")
            appendLine("Packed: ${Times.local(now)} (${Times.utc(now)})")
            appendLine()
            appendLine("What is inside")
            appendLine("- The video or audio pieces of one recording, unchanged.")
            if (hasRecord) appendLine("- REC_${session.id}.json: the evidence record written while recording (times, device, location, fingerprints).")
            appendLine("- SHA256SUMS.txt: the SHA-256 fingerprint of every piece, computed while this package was made.")
            appendLine()
            appendLine("Check when packing")
            for (c in checks) appendLine("- ${c.file}: ${c.result}")
            appendLine()
            appendLine("How to check the files yourself")
            appendLine("A SHA-256 fingerprint changes completely if even one byte of a file changes.")
            appendLine("Linux / macOS:  sha256sum -c SHA256SUMS.txt   (macOS: shasum -a 256 -c SHA256SUMS.txt)")
            appendLine("Windows (PowerShell):  Get-FileHash <file> -Algorithm SHA256")
            if (hasRecord) appendLine("Then compare each value with \"sha256\" for the same file in REC_${session.id}.json.")
        }
    }
}
