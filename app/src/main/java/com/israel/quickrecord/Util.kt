package com.israel.quickrecord

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.VibratorManager
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Times {
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
    private val niceDate = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.getDefault())
    private val niceTime = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())

    fun zone(): ZoneId = ZoneId.systemDefault()

    /** "20261125_220512", local time: used in file names. */
    fun fileStamp(ms: Long): String =
        fileStamp.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone()))

    /** "2026-11-25T20:05:12.345Z": the exact moment, in UTC. */
    fun utc(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    /** "2026-11-25T22:05:12.345+02:00": the same moment in the phone's local time. */
    fun local(ms: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun nice(ms: Long): String =
        niceDate.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone()))

    fun niceTime(ms: Long): String =
        niceTime.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone()))

    /** "1:02:03" or "2:03". */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "2 h 5 min", "12 min", "under 1 min". */
    fun roughMinutes(minutes: Long): String = when {
        minutes < 1 -> "under 1 min"
        minutes < 60 -> "$minutes min"
        else -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0L) "$h h" else "$h h $m min"
        }
    }
}

object Sizes {
    fun bytes(b: Long): String = when {
        b >= 1_000_000_000L -> String.format(Locale.getDefault(), "%.1f GB", b / 1e9)
        b >= 1_000_000L -> String.format(Locale.getDefault(), "%.0f MB", b / 1e6)
        b >= 1_000L -> String.format(Locale.getDefault(), "%.0f KB", b / 1e3)
        else -> "$b B"
    }

    /** Free bytes on the phone's shared storage, where recordings go. */
    fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (_: Exception) {
        -1
    }
}

object Haptics {
    /** [evenIfDiscreet]: still felt in discreet mode (but never when vibration is off). */
    private fun vibrate(context: Context, timings: LongArray, evenIfDiscreet: Boolean = false) {
        if (!Prefs.vibration) return
        if (Prefs.discreet && !evenIfDiscreet) return
        try {
            val vm = context.getSystemService(VibratorManager::class.java) ?: return
            val v = vm.defaultVibrator
            if (!v.hasVibrator()) return
            v.vibrate(VibrationEffect.createWaveform(timings, -1))
        } catch (_: Exception) {
        }
    }

    /** One short tap: recording started. */
    fun started(context: Context) = vibrate(context, longArrayOf(0, 45))

    /** Two short taps: recording stopped and saved. */
    fun stopped(context: Context) = vibrate(context, longArrayOf(0, 35, 90, 35))

    /** A longer buzz: something went wrong. Felt even in discreet mode. */
    fun failed(context: Context) = vibrate(context, longArrayOf(0, 350), evenIfDiscreet = true)

    /** A tiny tick for the button test screen. */
    fun tick(context: Context) = vibrate(context, longArrayOf(0, 15), evenIfDiscreet = true)
}
