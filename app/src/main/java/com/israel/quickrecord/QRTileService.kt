package com.israel.quickrecord

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.israel.quickrecord.ui.MainActivity

/** The Quick Settings tile: tap to start, tap again to stop. Works on the lock screen. */
class QRTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        AppState.tileAdded = true
        refresh()
    }

    override fun onTileRemoved() {
        AppState.tileAdded = false
        super.onTileRemoved()
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        when (RecorderState.statusNow) {
            RecStatus.IDLE -> start()
            RecStatus.RECORDING, RecStatus.PAUSED -> Recorder.stop(this, Recorder.SOURCE_TILE)
            else -> {} // starting or saving
        }
        refresh()
    }

    private fun start() {
        val problem = Recorder.precheck(this)
        if (problem != null) {
            ErrorLog.problem(problem)
            Haptics.failed(this)
            // Open the app so the problem can be fixed (asks to unlock if locked).
            val pi = PendingIntent.getActivity(
                this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
            return
        }
        val intent = Intent(this, RecordActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(RecordService.EXTRA_SOURCE, Recorder.SOURCE_TILE)
        val pi = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            startActivityAndCollapse(pi)
        } catch (e: Exception) {
            ErrorLog.problem("The tile could not start the recording: ${e.message}", e)
            Haptics.failed(this)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val status = RecorderState.statusNow
        tile.state = if (status == RecStatus.IDLE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.subtitle = if (Prefs.discreet) null else when (status) {
            RecStatus.IDLE -> "Ready"
            RecStatus.STARTING -> "Starting…"
            RecStatus.RECORDING -> "Recording"
            RecStatus.PAUSED -> "Paused"
            RecStatus.STOPPING -> "Saving…"
        }
        tile.updateTile()
    }
}
