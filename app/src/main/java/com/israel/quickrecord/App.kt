package com.israel.quickrecord

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.Executors

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        processStartWallMs = System.currentTimeMillis() -
            (SystemClock.uptimeMillis() - Process.getStartUptimeMillis())
        Prefs.init(this)
        AppState.init(this)
        ErrorLog.init(this)
        createChannels()

        // A recording that was cut off by a crash or a forced stop leaves its
        // last piece "pending" (hidden). Finish those off in the background.
        background.execute {
            try {
                Library.recoverInterrupted(this)
            } catch (e: Exception) {
                ErrorLog.problem("Could not check for interrupted recordings: ${e.message}", e)
            }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_RECORDING,
            getString(R.string.notif_channel_recording),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_recording_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        lateinit var instance: App
            private set

        /** Roughly when this process started, to tell old pending files from new ones. */
        var processStartWallMs = 0L
            private set

        const val CHANNEL_RECORDING = "recording"

        /** One shared thread for slow work started from the screens. */
        val background = Executors.newSingleThreadExecutor()
    }
}
