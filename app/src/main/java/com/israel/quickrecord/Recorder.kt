package com.israel.quickrecord

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager

/** Start, stop and checks, used by the tile, the button, the app and the notification. */
object Recorder {

    const val SOURCE_TILE = "tile"
    const val SOURCE_BUTTON = "button"
    const val SOURCE_APP = "app"
    const val SOURCE_NOTIFICATION = "notification"

    fun hasCamera(context: Context) =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun hasMic(context: Context) =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Why a recording can't start now, in plain words; null when it can. */
    fun precheck(context: Context): String? {
        val cam = hasCamera(context)
        val mic = hasMic(context) && Prefs.sound
        if (!cam && !mic) {
            return if (!hasCamera(context) && !hasMic(context))
                "QuickRecord isn't allowed to use the camera or the microphone. Open the app and allow them."
            else "The camera isn't allowed and sound is turned off in Settings, so there is nothing to record."
        }
        val bm = context.getSystemService(BatteryManager::class.java)
        if (bm != null) {
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val limit = Prefs.batteryStopPercent
            if (limit > 0 && pct in 1..limit && !bm.isCharging) {
                return "The battery is at $pct%. QuickRecord doesn't start at $limit% or below, so the recording can't be cut off when the phone dies. (Settings › Safety stops)"
            }
        }
        val free = Sizes.freeBytes()
        val minFree = Prefs.minFreeMb * 1_000_000L
        if (free in 0 until minFree) {
            return "Only ${Sizes.bytes(free)} of storage is free. QuickRecord keeps at least ${Sizes.bytes(minFree)} free. (Settings › Safety stops)"
        }
        return null
    }

    /** From a place that may be in the background but is allowed to open a screen (button, tile path). */
    fun toggle(context: Context, source: String) {
        when (RecorderState.statusNow) {
            RecStatus.IDLE -> startViaScreen(context, source)
            RecStatus.RECORDING, RecStatus.PAUSED -> RecordService.send(context, RecordService.ACTION_STOP, source)
            else -> {} // starting or saving: ignore the extra press
        }
    }

    /**
     * Opens the invisible lock-screen window, which starts the recording.
     * Android only gives camera access to an app that is on screen at the
     * moment the recording begins.
     */
    fun startViaScreen(context: Context, source: String) {
        val problem = precheck(context)
        if (problem != null) {
            ErrorLog.problem(problem)
            Haptics.failed(context)
            return
        }
        try {
            context.startActivity(
                Intent(context, RecordActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(RecordService.EXTRA_SOURCE, source)
            )
        } catch (e: Exception) {
            ErrorLog.problem("Could not start recording: ${e.message}", e)
            Haptics.failed(context)
        }
    }

    /** From a screen of the app that is open right now. */
    fun startFromApp(context: Context) {
        val problem = precheck(context)
        if (problem != null) {
            ErrorLog.problem(problem)
            Haptics.failed(context)
            return
        }
        RecordService.start(context, SOURCE_APP)
    }

    fun stop(context: Context, source: String) =
        RecordService.send(context, RecordService.ACTION_STOP, source)
}
