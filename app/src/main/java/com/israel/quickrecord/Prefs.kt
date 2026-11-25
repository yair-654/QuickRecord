package com.israel.quickrecord

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Every user setting, with its default. Settings live in their own file so
 * "Reset settings" can clear them without touching recordings, protected
 * marks, titles or the problems log (those live in [AppState]).
 *
 * [version] changes on every write, so Compose screens that read it redraw.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        migrateFrom1x(context)
    }

    /** QuickRecord 1.x kept the folder name in another file: carry it over once. */
    private fun migrateFrom1x(context: Context) {
        val old = context.getSharedPreferences("quickrecord_prefs", Context.MODE_PRIVATE)
        val folder = old.getString("custom_folder", null) ?: return
        if (!sp.contains("folder") && folder.isNotBlank()) {
            sp.edit().putString("folder", sanitizeFolder(folder)).apply()
        }
        old.edit().remove("custom_folder").apply()
    }

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        version++
    }

    // ---- Video ----
    var camera: String
        get() = str("camera", "back")
        set(v) = put { putString("camera", v) }

    /** Video height: 720, 1080 or 2160. */
    var resolution: Int
        get() = int("resolution", 1080)
        set(v) = put { putInt("resolution", v) }

    var fps: Int
        get() = int("fps", 30)
        set(v) = put { putInt("fps", v) }

    /** "saver", "standard" or "high". */
    var quality: String
        get() = str("quality", "standard")
        set(v) = put { putString("quality", v) }

    /** "h264" or "hevc". */
    var codec: String
        get() = str("codec", "h264")
        set(v) = put { putString("codec", v) }

    var stabilization: Boolean
        get() = bool("stabilization", true)
        set(v) = put { putBoolean("stabilization", v) }

    var torchAtStart: Boolean
        get() = bool("torch_at_start", false)
        set(v) = put { putBoolean("torch_at_start", v) }

    // ---- Sound ----
    var sound: Boolean
        get() = bool("sound", true)
        set(v) = put { putBoolean("sound", v) }

    // ---- Saving ----
    /** Minutes per piece. 0 = one file (split only at the 3.8 GB file limit). */
    var pieceMinutes: Int
        get() = int("piece_minutes", 5)
        set(v) = put { putInt("piece_minutes", v) }

    var folder: String
        get() = sanitizeFolder(str("folder", DEFAULT_FOLDER))
        set(v) = put { putString("folder", sanitizeFolder(v)) }

    // ---- Safety stops ----
    /** 0 = never. */
    var autoStopMinutes: Int
        get() = int("auto_stop_minutes", 0)
        set(v) = put { putInt("auto_stop_minutes", v) }

    var minFreeMb: Int
        get() = int("min_free_mb", 1000)
        set(v) = put { putInt("min_free_mb", v) }

    var batteryStopPercent: Int
        get() = int("battery_stop", 5)
        set(v) = put { putInt("battery_stop", v) }

    // ---- Privacy and feedback ----
    var location: Boolean
        get() = bool("location", true)
        set(v) = put { putBoolean("location", v) }

    var discreet: Boolean
        get() = bool("discreet", false)
        set(v) = put { putBoolean("discreet", v) }

    var vibration: Boolean
        get() = bool("vibration", true)
        set(v) = put { putBoolean("vibration", v) }

    // ---- Button ----
    /** "down3", "down2", "up3", "up2" or "both". */
    var gesture: String
        get() = str("gesture", "down3")
        set(v) = put { putString("gesture", v) }

    var screenOffButton: Boolean
        get() = bool("screen_off_button", false)
        set(v) = put { putBoolean("screen_off_button", v) }

    fun resetAll() = put { clear() }

    const val DEFAULT_FOLDER = "QuickRecord"

    fun sanitizeFolder(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
            .trim('.', ' ')
        return cleaned.ifEmpty { DEFAULT_FOLDER }.take(60)
    }

    // ---- Derived values ----

    fun videoWidth(height: Int) = when (height) {
        720 -> 1280
        2160 -> 3840
        else -> 1920
    }

    /** Target video bitrate in bits per second. */
    fun videoBitrate(height: Int, fps: Int, codec: String, quality: String): Int {
        var base = when (height) {
            720 -> 6_000_000.0
            2160 -> 40_000_000.0
            else -> 12_000_000.0
        }
        if (fps >= 60) base *= 1.5
        if (codec == "hevc") base *= 0.6
        base *= when (quality) {
            "saver" -> 0.6
            "high" -> 1.6
            else -> 1.0
        }
        return base.toInt()
    }

    const val AUDIO_BITRATE_VIDEO = 192_000
    const val AUDIO_BITRATE_ONLY = 128_000

    /** Approximate bytes per minute for the current settings, sound included. */
    fun bytesPerMinute(): Long {
        val v = videoBitrate(resolution, fps, codec, quality).toLong()
        val a = if (sound) AUDIO_BITRATE_VIDEO.toLong() else 0L
        return (v + a) / 8 * 60
    }
}

/**
 * Things the app remembers that are not settings: protected recordings,
 * titles, whether the tile was added, the test mode of the button screen.
 */
object AppState {
    private lateinit var sp: SharedPreferences

    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        sp = context.getSharedPreferences("state", Context.MODE_PRIVATE)
    }

    private fun bump() { version++ }

    var tileAdded: Boolean
        get() = sp.getBoolean("tile_added", false)
        set(v) { sp.edit().putBoolean("tile_added", v).apply(); bump() }

    fun isProtected(sessionId: String) =
        sp.getStringSet("protected", emptySet())!!.contains(sessionId)

    fun setProtected(sessionId: String, value: Boolean) {
        val set = sp.getStringSet("protected", emptySet())!!.toMutableSet()
        if (value) set.add(sessionId) else set.remove(sessionId)
        sp.edit().putStringSet("protected", set).apply()
        bump()
    }
}
