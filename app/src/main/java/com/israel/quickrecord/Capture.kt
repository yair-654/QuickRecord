package com.israel.quickrecord

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.roundToInt

/** One way of recording video that the recorder will try. */
data class VideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: String,
    val bitrate: Int,
    val stereo: Boolean = true,
) {
    val label: String
        get() = "${height}p · $fps fps · ${if (codec == "hevc") "HEVC" else "H.264"}"
}

object CameraPlan {

    /** The first camera facing the wanted way, or any camera. */
    fun findCamera(cm: CameraManager, facing: String): String? {
        val want = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val ids = try { cm.cameraIdList.toList() } catch (_: Exception) { emptyList() }
        return ids.firstOrNull { id ->
            try { cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == want } catch (_: Exception) { false }
        } ?: ids.firstOrNull()
    }

    /**
     * The settings to try, best first: what the user chose (if this camera
     * and phone can do it), then safer steps down. Each step is tried until
     * one works, and the one used is written in the evidence record.
     */
    fun attempts(chars: CameraCharacteristics, wanted: VideoConfig): List<VideoConfig> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val sizes = map.getOutputSizes(MediaRecorder::class.java)?.toSet() ?: emptySet()
        fun sizeOk(w: Int, h: Int) = sizes.contains(Size(w, h))
        fun fpsOk(w: Int, h: Int, fps: Int): Boolean {
            if (fpsRange(chars, fps) == null) return false
            val minFrame = try { map.getOutputMinFrameDuration(MediaRecorder::class.java, Size(w, h)) } catch (_: Exception) { 0L }
            return minFrame == 0L || minFrame <= 1_000_000_000L / fps
        }
        fun encoderOk(c: VideoConfig): Boolean {
            val mime = if (c.codec == "hevc") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val fmt = MediaFormat.createVideoFormat(mime, c.width, c.height).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, c.fps)
                setInteger(MediaFormat.KEY_BIT_RATE, c.bitrate)
            }
            return try { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(fmt) != null } catch (_: Exception) { true }
        }

        val q = Prefs.quality
        fun cfg(h: Int, fps: Int, codec: String, stereo: Boolean = true) =
            VideoConfig(Prefs.videoWidth(h), h, fps, codec, Prefs.videoBitrate(h, fps, codec, q), stereo)

        val candidates = listOf(
            wanted,
            cfg(wanted.height, 30, wanted.codec),
            cfg(wanted.height, 30, "h264"),
            cfg(1080, 30, "h264"),
            cfg(720, 30, "h264"),
            cfg(1080, 30, "h264", stereo = false),
            cfg(720, 30, "h264", stereo = false),
        )
        return candidates.distinct().filter { sizeOk(it.width, it.height) && fpsOk(it.width, it.height, it.fps) && encoderOk(it) }
    }

    /** A fixed frame-rate range, so the video doesn't speed up or slow down. */
    fun fpsRange(chars: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
    }

    fun stabilizationAvailable(chars: CameraCharacteristics): Boolean =
        chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

    fun torchAvailable(chars: CameraCharacteristics): Boolean =
        chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
}

/**
 * How the phone is held right now, from the accelerometer: 0 (upright),
 * 90, 180 or 270 degrees. The lock screen is locked to portrait, so the
 * screen rotation can't tell us this.
 */
object Orientation {
    fun read(context: Context, timeoutMs: Long): Int {
        val sm = context.getSystemService(SensorManager::class.java) ?: return 0
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return 0
        val latch = CountDownLatch(1)
        var result = 0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = -e.values[0]
                val y = -e.values[1]
                val z = -e.values[2]
                // Same maths as Android's OrientationEventListener.
                if ((x * x + y * y) * 4 >= z * z) {
                    var o = 90 - (atan2(-y, x) * 57.29578f).roundToInt()
                    while (o >= 360) o -= 360
                    while (o < 0) o += 360
                    result = ((o + 45) / 90 * 90) % 360
                } // else lying flat: keep 0 (portrait)
                latch.countDown()
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(Looper.getMainLooper()))
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            sm.unregisterListener(listener)
        }
        return result
    }
}

object Locations {
    fun permitted(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun precise(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The newest position Android already knows, without waiting. */
    fun lastKnown(context: Context): Location? {
        if (!permitted(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        var best: Location? = null
        for (p in lm.allProviders) {
            val l = try { if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null } catch (_: Exception) { null }
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        return best
    }

    /** Asks for one fresh position; [onFix] is called on [handler]'s thread, at most once. */
    fun requestFresh(context: Context, handler: Handler, onFix: (Location) -> Unit): CancellationSignal? {
        if (!permitted(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = when {
            precise(context) && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.allProviders.contains(LocationManager.FUSED_PROVIDER) && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.allProviders.contains(LocationManager.NETWORK_PROVIDER) && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        val signal = CancellationSignal()
        val exec = Executor { handler.post(it) }
        return try {
            lm.getCurrentLocation(provider, signal, exec) { loc -> if (loc != null) onFix(loc) }
            handler.postDelayed({ signal.cancel() }, 90_000)
            signal
        } catch (_: Exception) {
            null
        }
    }

    fun ageMs(l: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    fun toJson(l: Location): JSONObject = JSONObject()
        .put("latitude", l.latitude)
        .put("longitude", l.longitude)
        .put("accuracy_m", if (l.hasAccuracy()) l.accuracy.toDouble() else JSONObject.NULL)
        .put("altitude_m", if (l.hasAltitude()) l.altitude else JSONObject.NULL)
        .put("provider", l.provider ?: "")
        .put("fix_time_utc", Times.utc(l.time))
        .put("fix_age_seconds", ageMs(l) / 1000)
}
