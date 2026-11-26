package com.israel.quickrecord

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class RecStatus { IDLE, STARTING, RECORDING, PAUSED, STOPPING }

enum class RecMode(val label: String) {
    VIDEO_AUDIO("Video and sound"),
    VIDEO("Video, no sound"),
    AUDIO("Sound only"),
}

/**
 * The one shared picture of what the recorder is doing. Written only by
 * [RecordService], always on the main thread; read by the screens (Compose
 * redraws by itself), the tile and the button service.
 */
object RecorderState {
    private val main = Handler(Looper.getMainLooper())

    var status by mutableStateOf(RecStatus.IDLE)
        private set
    var mode by mutableStateOf<RecMode?>(null)
        private set
    /** What is being recorded, for example "1080p · 30 fps · Back camera". */
    var summary by mutableStateOf("")
        private set
    var startedAtMs by mutableLongStateOf(0L)
        private set
    /** Time spent paused so far, so the timer can leave it out. */
    var pausedTotalMs by mutableLongStateOf(0L)
        private set
    var pausedAtMs by mutableLongStateOf(0L)
        private set
    var piece by mutableIntStateOf(0)
        private set
    var torchOn by mutableStateOf(false)
        private set
    var torchAvailable by mutableStateOf(false)
        private set

    /** Thread-safe copy of [status] for code that is not on the main thread. */
    @Volatile var statusNow: RecStatus = RecStatus.IDLE
        private set

    val isActive get() = statusNow == RecStatus.RECORDING || statusNow == RecStatus.PAUSED

    fun elapsedMs(now: Long = System.currentTimeMillis()): Long {
        if (startedAtMs == 0L) return 0
        val pausedNow = if (status == RecStatus.PAUSED && pausedAtMs > 0) now - pausedAtMs else 0
        return (now - startedAtMs - pausedTotalMs - pausedNow).coerceAtLeast(0)
    }

    internal fun update(block: Editor.() -> Unit) {
        val run = Runnable {
            val before = status
            Editor.block()
            statusNow = status
            if (before != status) QRTileService.stateChanged(App.instance)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    internal object Editor {
        fun status(s: RecStatus) { RecorderState.status = s }
        fun mode(m: RecMode?) { RecorderState.mode = m }
        fun summary(s: String) { RecorderState.summary = s }
        fun started(ms: Long) {
            RecorderState.startedAtMs = ms
            RecorderState.pausedTotalMs = 0
            RecorderState.pausedAtMs = 0
        }
        fun paused(now: Long) { RecorderState.pausedAtMs = now }
        fun resumed(now: Long) {
            if (RecorderState.pausedAtMs > 0) {
                RecorderState.pausedTotalMs += now - RecorderState.pausedAtMs
            }
            RecorderState.pausedAtMs = 0
        }
        fun piece(n: Int) { RecorderState.piece = n }
        fun torch(available: Boolean, on: Boolean) {
            RecorderState.torchAvailable = available
            RecorderState.torchOn = on
        }
        fun reset() {
            RecorderState.status = RecStatus.IDLE
            RecorderState.mode = null
            RecorderState.summary = ""
            RecorderState.startedAtMs = 0
            RecorderState.pausedTotalMs = 0
            RecorderState.pausedAtMs = 0
            RecorderState.piece = 0
            RecorderState.torchOn = false
            RecorderState.torchAvailable = false
        }
    }
}
