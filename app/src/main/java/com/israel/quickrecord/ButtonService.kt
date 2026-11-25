package com.israel.quickrecord

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the button screen shows: is the shortcut on, and what was just pressed. */
object ButtonState {
    var connected by mutableStateOf(false)
        internal set
    var screenOffActive by mutableStateOf(false)
        internal set
    /** While the test screen is open, a gesture is only reported, not acted on. */
    var testMode by mutableStateOf(false)
    var testPresses by mutableIntStateOf(0)
        internal set
    var testDetections by mutableIntStateOf(0)
        internal set
    var lastPress by mutableStateOf("")
        internal set

    fun resetTest() {
        testPresses = 0
        testDetections = 0
        lastPress = ""
    }

    fun isEnabledInSettings(context: Context): Boolean {
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, ButtonService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

/**
 * The volume button shortcut.
 *
 * Screen on (including the lock screen): Android shows this accessibility
 * service every volume key press. It never blocks a press, so the volume
 * still changes as usual; when the gesture is recognised, the volume is put
 * back to where it was before the gesture began.
 *
 * Screen off (optional): accessibility services don't get key presses then.
 * Instead, while the screen is off, QuickRecord holds a "remote volume"
 * media session, the same way casting apps let the volume buttons control a
 * TV. Android then hands volume presses to it. The session switches off as
 * soon as the screen turns on, so normal use is never affected. It does not
 * work while another app plays sound or during a call: they get the buttons.
 */
class ButtonService : AccessibilityService() {

    companion object {
        @Volatile private var instance: ButtonService? = null

        /** Call after the "screen off" setting changes. */
        fun settingsChanged() {
            instance?.let { s -> s.main.post { s.updateScreenOffSession() } }
        }

        const val UP = 1
        const val DOWN = -1
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var detector: Gesture
    private var mediaSession: MediaSession? = null
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateScreenOffSession()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        detector = Gesture(this) { onGesture() }
        ButtonState.connected = true
        if (!receiverRegistered) {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenReceiver, f, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        updateScreenOffSession()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> DOWN
            else -> return false
        }
        if (!isScreenOn()) return false // the screen-off session handles these
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            detector.press(dir, screenOn = true)
        }
        return false // never swallow the press: volume keeps working normally
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        ButtonState.connected = false
        releaseSession()
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    private fun isScreenOn() = getSystemService(PowerManager::class.java)?.isInteractive != false

    private fun onGesture() {
        if (ButtonState.testMode) {
            ButtonState.testDetections++
            Haptics.tick(this)
            return
        }
        Recorder.toggle(this, Recorder.SOURCE_BUTTON)
    }

    // ---- Screen-off media session ----

    private fun updateScreenOffSession() {
        val want = Prefs.screenOffButton && !isScreenOn()
        if (want && mediaSession == null) createSession()
        if (!want && mediaSession != null) releaseSession()
    }

    private fun createSession() {
        try {
            val ms = MediaSession(this, "QuickRecordButton")
            val provider = object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction == 0) return
                    main.post { detector.press(if (direction > 0) UP else DOWN, screenOn = false) }
                }
            }
            ms.setPlaybackToRemote(provider)
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f, SystemClock.elapsedRealtime())
                    .setActions(0)
                    .build()
            )
            ms.isActive = true
            mediaSession = ms
            ButtonState.screenOffActive = true
        } catch (e: Exception) {
            ErrorLog.problem("The screen-off button could not be turned on: ${e.message}", e)
        }
    }

    private fun releaseSession() {
        mediaSession?.let {
            try {
                it.isActive = false
                it.release()
            } catch (_: Exception) {
            }
        }
        mediaSession = null
        ButtonState.screenOffActive = false
    }
}

/**
 * Recognises the chosen gesture from a stream of volume presses.
 * Presses belong together when each comes within [GAP_MS] of the last.
 */
class Gesture(private val context: Context, private val onTrigger: () -> Unit) {

    private companion object {
        const val GAP_MS = 600L
        const val BOTH_GAP_MS = 400L
        const val COOLDOWN_MS = 1500L
        val STREAMS = intArrayOf(
            AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_ALARM, AudioManager.STREAM_ACCESSIBILITY,
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val presses = ArrayDeque<Pair<Int, Long>>()
    private var cooldownUntil = 0L
    private var savedVolumes: IntArray? = null
    private var savedRinger = -1

    fun press(dir: Int, screenOn: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (ButtonState.testMode) {
            ButtonState.testPresses++
            ButtonState.lastPress = if (dir > 0) "Volume Up" else "Volume Down"
        }
        if (now < cooldownUntil) return
        if (presses.isNotEmpty() && now - presses.last().second > GAP_MS) presses.clear()
        if (presses.isEmpty()) {
            if (screenOn) saveVolumes() else savedVolumes = null
        }
        presses.addLast(dir to now)
        while (presses.size > 4) presses.removeFirst()

        if (matches(Prefs.gesture)) {
            presses.clear()
            cooldownUntil = now + COOLDOWN_MS
            if (screenOn) main.postDelayed({ restoreVolumes() }, 350)
            onTrigger()
        }
    }

    private fun matches(g: String): Boolean {
        fun last(n: Int, dir: Int) = presses.size >= n && presses.takeLast(n).all { it.first == dir }
        return when (g) {
            "down2" -> last(2, ButtonService.DOWN)
            "up2" -> last(2, ButtonService.UP)
            "up3" -> last(3, ButtonService.UP)
            "either3" -> last(3, ButtonService.UP) || last(3, ButtonService.DOWN)
            "both" -> presses.size >= 2 && presses.takeLast(2).let { (a, b) ->
                a.first != b.first && b.second - a.second <= BOTH_GAP_MS
            }
            else -> last(3, ButtonService.DOWN)
        }
    }

    private fun saveVolumes() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        savedVolumes = IntArray(STREAMS.size) { i -> try { am.getStreamVolume(STREAMS[i]) } catch (_: Exception) { -1 } }
        savedRinger = try { am.ringerMode } catch (_: Exception) { -1 }
    }

    /** Puts every volume back to where it was before the gesture. */
    private fun restoreVolumes() {
        val saved = savedVolumes ?: return
        savedVolumes = null
        val am = context.getSystemService(AudioManager::class.java) ?: return
        for (i in STREAMS.indices) {
            if (saved[i] < 0) continue
            try {
                if (am.getStreamVolume(STREAMS[i]) != saved[i]) am.setStreamVolume(STREAMS[i], saved[i], 0)
            } catch (_: Exception) {
            }
        }
        try {
            if (savedRinger >= 0 && am.ringerMode != savedRinger) am.ringerMode = savedRinger
        } catch (_: Exception) {
        }
    }
}
