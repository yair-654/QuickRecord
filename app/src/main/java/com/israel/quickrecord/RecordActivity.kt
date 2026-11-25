package com.israel.quickrecord

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * An invisible window that appears for a moment over the lock screen (or
 * over whatever app is open). Android only lets an app start using the
 * camera while one of its windows is on screen, so the tile and the button
 * open this, it starts the recording, and it closes again. The phone stays
 * locked the whole time.
 */
class RecordActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val source = intent?.getStringExtra(RecordService.EXTRA_SOURCE) ?: Recorder.SOURCE_TILE
        if (RecorderState.statusNow == RecStatus.IDLE) {
            try {
                RecordService.start(this, source)
            } catch (e: Exception) {
                ErrorLog.problem("Could not start recording: ${e.message}", e)
                Haptics.failed(this)
            }
        }
        // Stay just long enough for Android to see the app on screen when the
        // recording service starts using the camera.
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, LINGER_MS)
    }

    private companion object {
        const val LINGER_MS = 600L
    }
}
