package com.israel.quickrecord

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.media.MediaRecorder
import android.net.Uri
import android.os.BatteryManager
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import com.israel.quickrecord.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Owns the camera and the recorder while a recording runs.
 *
 * Threads:
 *  - every camera and recorder step runs on one thread ([rec]), in order;
 *  - closing, fingerprinting and publishing finished pieces runs on another
 *    ([sealer]), also in order, so the recording never waits for it;
 *  - [RecorderState] is updated on the main thread.
 *
 * A recording is saved in pieces: when a piece reaches its size (about the
 * chosen number of minutes), the recorder moves to the next file on the very
 * next frame, with no gap, and the finished piece is sealed.
 */
class RecordService : Service() {

    companion object {
        const val ACTION_START = "com.israel.quickrecord.START"
        const val ACTION_STOP = "com.israel.quickrecord.STOP"
        const val ACTION_PAUSE = "com.israel.quickrecord.PAUSE"
        const val ACTION_RESUME = "com.israel.quickrecord.RESUME"
        const val ACTION_TORCH = "com.israel.quickrecord.TORCH"
        const val EXTRA_SOURCE = "source"

        private const val NOTIF_ID = 7
        private const val MAX_FILE_BYTES = 3_800_000_000L // MP4 files must stay under 4 GB
        private const val MIN_PIECE_BYTES = 20_000_000L
        private const val START_TIMEOUT_MS = 12_000L
        private const val MONITOR_MS = 10_000L
        private const val MAX_LOCATION_AGE_TO_EMBED_MS = 10 * 60_000L

        @Volatile private var lastSessionId = ""

        fun start(context: Context, source: String) {
            context.startForegroundService(
                Intent(context, RecordService::class.java).setAction(ACTION_START).putExtra(EXTRA_SOURCE, source)
            )
        }

        fun send(context: Context, action: String, source: String) {
            try {
                context.startService(Intent(context, RecordService::class.java).setAction(action).putExtra(EXTRA_SOURCE, source))
            } catch (e: Exception) {
                ErrorLog.problem("Could not reach the recorder: ${e.message}", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // One recording session. Only touched on the rec thread.
    // ------------------------------------------------------------------

    private class Piece(
        val index: Int,
        val uri: Uri,
        val pfd: ParcelFileDescriptor,
        val name: String,
        val isVideo: Boolean,
    ) {
        var startedMs = 0L
        var endedMs = 0L
    }

    private class Plan(
        val wantVideo: Boolean,
        val wantAudio: Boolean,
        val facing: String,
        val wanted: VideoConfig,
        val stabilization: Boolean,
        val torch: Boolean,
        val pieceMinutes: Int,
        val folder: String,
        val location: Boolean,
        val autoStopMinutes: Int,
        val minFreeBytes: Long,
        val batteryStop: Int,
        val discreet: Boolean,
        val source: String,
    )

    private inner class Session(val id: String, val plan: Plan, val record: SessionRecord) {
        var camera: CameraDevice? = null
        var capture: CameraCaptureSession? = null
        var surface: Surface? = null
        var request: CaptureRequest.Builder? = null
        var recorder: MediaRecorder? = null
        var recorderStarted = false
        var config: VideoConfig? = null
        var attempts: List<VideoConfig> = emptyList()
        var attempt = 0
        var current: Piece? = null
        var next: Piece? = null
        var nextIndex = 1
        var mode: RecMode? = null
        var isFront = false
        var sensorOrientation = 90
        var deviceOrientation = 0
        var chars: CameraCharacteristics? = null
        var stabilizationAvailable = false
        var torchAvailable = false
        var torchOn = false
        var location: Location? = null
        var locationSignal: CancellationSignal? = null
        var startedMs = 0L
        var pausedTotalMs = 0L
        var pausedAtMs = 0L
        var stopping = false
        var savedPieces = 0
    }

    private lateinit var thread: HandlerThread
    private lateinit var rec: Handler
    private val exec = Executor { r -> rec.post(r) }
    private val sealer = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("QuickRecord-recorder").also { it.start() }
        rec = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "unknown"
        when (intent?.action) {
            ACTION_START -> handleStart(source)
            ACTION_STOP -> rec.post { stopRecording(stopReason(source)) }
            ACTION_PAUSE -> rec.post { pause() }
            ACTION_RESUME -> rec.post { resume() }
            ACTION_TORCH -> rec.post { toggleTorch() }
            else -> if (RecorderState.statusNow == RecStatus.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Normally the recording is already finished. If Android destroys the
        // service anyway, finish and save what we have before the thread ends.
        val done = java.util.concurrent.CountDownLatch(1)
        rec.post {
            if (session != null) stopRecording("the recording service was stopped by Android")
            done.countDown()
        }
        try { done.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        thread.quitSafely()
        sealer.shutdown() // already queued sealing still runs to the end
        super.onDestroy()
    }

    private fun stopReason(source: String) = when (source) {
        Recorder.SOURCE_TILE -> "stopped with the Quick Settings tile"
        Recorder.SOURCE_BUTTON -> "stopped with the volume button shortcut"
        Recorder.SOURCE_NOTIFICATION -> "stopped from the notification"
        Recorder.SOURCE_APP -> "stopped in the app"
        else -> "stopped"
    }

    // ------------------------------------------------------------------
    // Start
    // ------------------------------------------------------------------

    /** On the main thread. Must call startForeground quickly, or Android ends the app. */
    private fun handleStart(source: String) {
        if (RecorderState.statusNow != RecStatus.IDLE || session != null) {
            // Already busy. Still answer Android's "start in the foreground" request.
            try {
                startForeground(NOTIF_ID, notification(session), currentTypes)
            } catch (_: Exception) {
            }
            return
        }
        val problem = Recorder.precheck(this)
        if (problem != null) {
            ErrorLog.problem(problem)
            Haptics.failed(this)
            stopSelf()
            return
        }
        val plan = buildPlan(source)
        var types = 0
        if (plan.wantVideo) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (plan.wantAudio) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, notification(null), types)
            currentTypes = types
        } catch (e: Exception) {
            ErrorLog.problem("Android did not allow the recording to start: ${e.message}", e)
            Haptics.failed(this)
            stopSelf()
            return
        }
        RecorderState.update { status(RecStatus.STARTING) }
        rec.post { begin(plan) }
    }

    private var currentTypes = 0

    private fun buildPlan(source: String): Plan {
        val cam = Recorder.hasCamera(this)
        val mic = Recorder.hasMic(this) && Prefs.sound
        if (!cam) ErrorLog.notice("The camera isn't allowed, so QuickRecord is recording sound only.")
        val h = Prefs.resolution
        val fps = Prefs.fps
        val codec = Prefs.codec
        return Plan(
            wantVideo = cam,
            wantAudio = mic,
            facing = Prefs.camera,
            wanted = VideoConfig(Prefs.videoWidth(h), h, fps, codec, Prefs.videoBitrate(h, fps, codec, Prefs.quality)),
            stabilization = Prefs.stabilization,
            torch = Prefs.torchAtStart,
            pieceMinutes = Prefs.pieceMinutes,
            folder = Prefs.folder,
            location = Prefs.location && Locations.permitted(this),
            autoStopMinutes = Prefs.autoStopMinutes,
            minFreeBytes = Prefs.minFreeMb * 1_000_000L,
            batteryStop = Prefs.batteryStopPercent,
            discreet = Prefs.discreet,
            source = source,
        )
    }

    /** On the rec thread. */
    private fun begin(plan: Plan) {
        var now = System.currentTimeMillis()
        var id = Times.fileStamp(now)
        if (id == lastSessionId) { now += 1000; id = Times.fileStamp(now) }
        lastSessionId = id

        val record = SessionRecord.create(this, id, now, plan.source)
        val s = Session(id, plan, record)
        session = s
        rec.postDelayed(startWatchdog, START_TIMEOUT_MS)

        s.deviceOrientation = Orientation.read(this, 400)

        if (plan.location) {
            val last = Locations.lastKnown(this)
            if (last != null) {
                val fresh = Locations.ageMs(last) <= MAX_LOCATION_AGE_TO_EMBED_MS
                if (fresh) s.location = last
                record.edit {
                    it.put("location", Locations.toJson(last).put("embedded_in_video", fresh)
                        .put("note", if (fresh) "Last position the phone knew when recording began." else
                            "Last position the phone knew when recording began. It was too old to put inside the video file."))
                }
            } else {
                record.edit { it.put("location", JSONObject.NULL) }
            }
            s.locationSignal = Locations.requestFresh(this, rec) { fix ->
                record.edit { j ->
                    val arr = j.optJSONArray("location_updates") ?: JSONArray().also { j.put("location_updates", it) }
                    arr.put(Locations.toJson(fix).put("received_utc", Times.utc(System.currentTimeMillis())))
                }
            }
        } else {
            record.edit { it.put("location", JSONObject.NULL) }
        }

        if (plan.wantVideo) openCamera(s) else startAudioOnly(s)
    }

    private val startWatchdog = Runnable {
        val s = session ?: return@Runnable
        if (!s.recorderStarted && !s.stopping) fail(s, "The camera did not start within ${START_TIMEOUT_MS / 1000} seconds.")
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private fun openCamera(s: Session) {
        try {
            val cm = getSystemService(CameraManager::class.java)
            val id = CameraPlan.findCamera(cm, s.plan.facing) ?: return cameraUnavailable(s, "No camera was found")
            val chars = cm.getCameraCharacteristics(id)
            s.sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            s.isFront = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            s.stabilizationAvailable = CameraPlan.stabilizationAvailable(chars)
            s.torchAvailable = CameraPlan.torchAvailable(chars)
            s.attempts = CameraPlan.attempts(chars, s.plan.wanted)
            if (s.attempts.isEmpty()) return cameraUnavailable(s, "This camera can't record video with any setting QuickRecord knows")
            if (s.attempts.first() != s.plan.wanted) {
                ErrorLog.notice("This camera can't record ${s.plan.wanted.label}. Using ${s.attempts.first().label} instead.")
            }
            s.chars = chars
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (session !== s || s.stopping) { camera.close(); return }
                    s.camera = camera
                    configure(s)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (session !== s || s.stopping) return
                    s.camera = null
                    if (!s.recorderStarted) cameraUnavailable(s, "The camera is being used by another app")
                    else stopRecording("the camera was taken by another app or turned off",
                        "The camera was taken by another app or turned off, so the recording was stopped and saved.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (session !== s || s.stopping) return
                    s.camera = null
                    val why = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "The camera is being used by another app"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "The camera is turned off for apps on this phone"
                        else -> "The camera stopped working (error $error)"
                    }
                    if (!s.recorderStarted) cameraUnavailable(s, why)
                    else stopRecording("camera error $error", "$why. The recording was stopped and saved.")
                }
            }, rec)
        } catch (e: SecurityException) {
            cameraUnavailable(s, "Android did not allow the camera")
        } catch (e: Exception) {
            cameraUnavailable(s, "The camera could not be opened (${e.message})")
        }
    }

    /** Video is impossible: record sound only if allowed, otherwise give up. */
    private fun cameraUnavailable(s: Session, why: String) {
        closeCamera(s)
        try { s.recorder?.release() } catch (_: Exception) {}
        s.recorder = null
        s.current?.let { discard(it) }
        s.current = null
        s.config = null
        s.nextIndex = 1
        if (s.plan.wantAudio) {
            ErrorLog.problem("$why. Recording sound only.")
            s.record.addNote("$why, so this recording has sound only.")
            startAudioOnly(s)
        } else {
            fail(s, "$why, and sound recording is off or not allowed, so nothing could be recorded.")
        }
    }

    private fun configure(s: Session) {
        val cam = s.camera ?: return
        val cfg = s.attempts.getOrNull(s.attempt)
            ?: return cameraUnavailable(s, "The camera could not record with any of the settings tried")
        val piece = newPiece(s, video = true)
            ?: return fail(s, "Could not create a file to record into. Is the storage full?")
        val recorder = try {
            videoRecorder(s, cfg, piece)
        } catch (e: Exception) {
            discard(piece)
            s.nextIndex = piece.index
            ErrorLog.notice("Recording at ${cfg.label} didn't work here (${e.message}); trying the next setting.")
            s.attempt++
            return configure(s)
        }
        s.recorder = recorder
        s.current = piece
        s.config = cfg
        val surface = recorder.surface
        s.surface = surface
        try {
            cam.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(surface)),
                exec,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cs: CameraCaptureSession) {
                        if (session !== s || s.stopping) { cs.close(); return }
                        s.capture = cs
                        startStreaming(s)
                    }

                    override fun onConfigureFailed(cs: CameraCaptureSession) {
                        cs.close()
                        if (session !== s || s.stopping) return
                        retryNext(s, "the camera could not be set up for ${cfg.label}")
                    }
                }
            ))
        } catch (e: Exception) {
            retryNext(s, "the camera could not be set up for ${cfg.label} (${e.message})")
        }
    }

    private fun retryNext(s: Session, why: String) {
        ErrorLog.notice("Trying another setting: $why.")
        try { s.capture?.close() } catch (_: Exception) {}
        s.capture = null
        try { s.recorder?.release() } catch (_: Exception) {}
        s.recorder = null
        s.current?.let { discard(it) }
        s.current = null
        s.nextIndex = 1
        s.attempt++
        configure(s)
    }

    private fun startStreaming(s: Session) {
        val cam = s.camera ?: return
        val cs = s.capture ?: return
        val surface = s.surface ?: return
        val cfg = s.config ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(surface)
            s.chars?.let { chars ->
                CameraPlan.fpsRange(chars, cfg.fps)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }
            if (s.plan.stabilization && s.stabilizationAvailable) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
            s.torchOn = s.plan.torch && s.torchAvailable
            applyTorch(b, s.torchOn)
            s.request = b
            cs.setRepeatingRequest(b.build(), null, rec)
            s.recorder!!.start()
            s.recorderStarted = true
            s.current!!.startedMs = System.currentTimeMillis()
            recordingStarted(s, if (s.plan.wantAudio) RecMode.VIDEO_AUDIO else RecMode.VIDEO)
        } catch (e: Exception) {
            try { cs.stopRepeating() } catch (_: Exception) {}
            retryNext(s, "the recording could not start at ${cfg.label} (${e.message})")
        }
    }

    private fun applyTorch(b: CaptureRequest.Builder, on: Boolean) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.FLASH_MODE, if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    private fun closeCamera(s: Session) {
        try { s.capture?.close() } catch (_: Exception) {}
        s.capture = null
        try { s.camera?.close() } catch (_: Exception) {}
        s.camera = null
    }

    // ------------------------------------------------------------------
    // Recorders
    // ------------------------------------------------------------------

    private fun orientationHint(s: Session): Int =
        if (s.isFront) (s.sensorOrientation - s.deviceOrientation + 360) % 360
        else (s.sensorOrientation + s.deviceOrientation) % 360

    private fun pieceBytes(s: Session, totalBitrate: Int): Long {
        val minutes = s.plan.pieceMinutes
        if (minutes <= 0) return MAX_FILE_BYTES
        val bytes = totalBitrate.toLong() / 8 * minutes * 60
        return bytes.coerceIn(MIN_PIECE_BYTES, MAX_FILE_BYTES)
    }

    private fun videoRecorder(s: Session, cfg: VideoConfig, piece: Piece): MediaRecorder {
        val r = MediaRecorder(this)
        try {
            val audio = s.plan.wantAudio
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            s.location?.let { r.setLocation(it.latitude.toFloat(), it.longitude.toFloat()) }
            r.setOrientationHint(orientationHint(s))
            r.setOutputFile(piece.pfd.fileDescriptor)
            r.setVideoEncoder(if (cfg.codec == "hevc") MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(cfg.width, cfg.height)
            r.setVideoFrameRate(cfg.fps)
            r.setVideoEncodingBitRate(cfg.bitrate)
            var total = cfg.bitrate
            if (audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(if (cfg.stereo) 2 else 1)
                r.setAudioSamplingRate(48_000)
                r.setAudioEncodingBitRate(Prefs.AUDIO_BITRATE_VIDEO)
                total += Prefs.AUDIO_BITRATE_VIDEO
            }
            r.setMaxFileSize(pieceBytes(s, total))
            r.setOnInfoListener { _, what, _ -> rec.post { onInfo(s, what) } }
            r.setOnErrorListener { _, what, extra -> rec.post { onRecorderError(s, what, extra) } }
            r.prepare()
            return r
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            throw e
        }
    }

    private fun startAudioOnly(s: Session) {
        val piece = newPiece(s, video = false)
            ?: return fail(s, "Could not create a file to record into. Is the storage full?")
        val r = MediaRecorder(this)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            s.location?.let { r.setLocation(it.latitude.toFloat(), it.longitude.toFloat()) }
            r.setOutputFile(piece.pfd.fileDescriptor)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(Prefs.AUDIO_BITRATE_ONLY)
            r.setMaxFileSize(pieceBytes(s, Prefs.AUDIO_BITRATE_ONLY))
            r.setOnInfoListener { _, what, _ -> rec.post { onInfo(s, what) } }
            r.setOnErrorListener { _, what, extra -> rec.post { onRecorderError(s, what, extra) } }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            discard(piece)
            return fail(s, "The microphone could not be started (${e.message}).")
        }
        s.recorder = r
        s.recorderStarted = true
        s.current = piece
        piece.startedMs = System.currentTimeMillis()
        recordingStarted(s, RecMode.AUDIO)
    }

    private fun onInfo(s: Session, what: Int) {
        if (session !== s || s.stopping) return
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                if (s.next != null) return
                val cur = s.current ?: return
                val n = newPiece(s, cur.isVideo) ?: run {
                    ErrorLog.problem("Could not create the next piece. The recording will stop when this piece is full.")
                    return
                }
                try {
                    s.recorder?.setNextOutputFile(n.pfd.fileDescriptor)
                    s.next = n
                } catch (e: Exception) {
                    discard(n)
                    s.nextIndex--
                    ErrorLog.problem("Could not prepare the next piece: ${e.message}", e)
                }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                val old = s.current ?: return
                val n = s.next ?: return
                val now = System.currentTimeMillis()
                old.endedMs = now
                n.startedMs = now
                s.current = n
                s.next = null
                seal(s, old, cleanly = true)
                s.record.addEvent(now, "Piece ${n.index} started")
                RecorderState.update { piece(n.index) }
                updateNotification()
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ->
                stopRecording("a piece reached the largest allowed file size and the next one could not be started",
                    "The recording stopped because the next piece could not be started.")
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ->
                stopRecording("the recorder's time limit was reached")
        }
    }

    private fun onRecorderError(s: Session, what: Int, extra: Int) {
        if (session !== s || s.stopping) return
        stopRecording("recorder error $what/$extra",
            "The recorder reported an error ($what/$extra). The recording was stopped and saved.")
    }

    // ------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------

    private fun recordingStarted(s: Session, m: RecMode) {
        rec.removeCallbacks(startWatchdog)
        s.mode = m
        s.startedMs = s.current?.startedMs ?: System.currentTimeMillis()
        val cfg = s.config
        s.record.edit { j ->
            j.put("started", SessionRecord.moment(s.startedMs).put("elapsed_realtime_ms", android.os.SystemClock.elapsedRealtime()))
            j.put("recording", JSONObject()
                .put("mode", when (m) {
                    RecMode.VIDEO_AUDIO -> "video and sound"
                    RecMode.VIDEO -> "video only"
                    RecMode.AUDIO -> "sound only"
                })
                .put("camera", if (m == RecMode.AUDIO) JSONObject.NULL else if (s.isFront) "front" else "back")
                .put("video", if (cfg == null || m == RecMode.AUDIO) JSONObject.NULL else JSONObject()
                    .put("width", cfg.width).put("height", cfg.height).put("frames_per_second", cfg.fps)
                    .put("codec", if (cfg.codec == "hevc") "H.265 / HEVC" else "H.264 / AVC")
                    .put("target_bitrate_bps", cfg.bitrate)
                    .put("stabilization", s.plan.stabilization && s.stabilizationAvailable)
                    .put("rotation_degrees", orientationHint(s)))
                .put("audio", if (m == RecMode.VIDEO) JSONObject.NULL else JSONObject()
                    .put("codec", "AAC").put("sample_rate_hz", 48_000)
                    .put("channels", if (m == RecMode.AUDIO || cfg?.stereo == false) 1 else 2)
                    .put("bitrate_bps", if (m == RecMode.AUDIO) Prefs.AUDIO_BITRATE_ONLY else Prefs.AUDIO_BITRATE_VIDEO))
                .put("piece_length_minutes", s.plan.pieceMinutes)
                .put("folder", "${if (m == RecMode.AUDIO) Environment.DIRECTORY_RECORDINGS else Environment.DIRECTORY_MOVIES}/${s.plan.folder}"))
        }
        s.record.addEvent(s.startedMs, "Recording started")
        sealer.execute { s.record.save(this) }

        val line = when (m) {
            RecMode.AUDIO -> "Sound only"
            else -> buildString {
                append(cfg?.label ?: "")
                append(if (s.isFront) " · Front camera" else " · Back camera")
                if (m == RecMode.VIDEO) append(" · No sound")
            }
        }
        val startAt = s.startedMs
        val torchAv = s.torchAvailable && m != RecMode.AUDIO
        val torchNow = s.torchOn
        val newMode = m
        RecorderState.update {
            status(RecStatus.RECORDING)
            mode(newMode)
            summary(line)
            started(startAt)
            piece(1)
            torch(torchAv, torchNow)
        }
        updateNotification()
        Haptics.started(this)
        rec.postDelayed(monitor, MONITOR_MS)
        if (s.plan.autoStopMinutes > 0) rec.postDelayed(autoStop, s.plan.autoStopMinutes * 60_000L)
    }

    private val monitor = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (s.stopping) return
            val free = Sizes.freeBytes()
            if (free in 0 until s.plan.minFreeBytes) {
                stopRecording("storage was almost full (${Sizes.bytes(free)} left)",
                    "Storage is almost full (${Sizes.bytes(free)} left), so the recording was stopped and saved.")
                return
            }
            val bm = getSystemService(BatteryManager::class.java)
            if (bm != null && s.plan.batteryStop > 0) {
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (pct in 1..s.plan.batteryStop && !bm.isCharging) {
                    stopRecording("the battery reached $pct%",
                        "The battery reached $pct%, so the recording was stopped and saved before the phone could die.")
                    return
                }
            }
            rec.postDelayed(this, MONITOR_MS)
        }
    }

    private val autoStop = Runnable {
        val s = session ?: return@Runnable
        stopRecording("the automatic stop after ${s.plan.autoStopMinutes} minutes")
    }

    private fun pause() {
        val s = session ?: return
        if (s.stopping || RecorderState.statusNow != RecStatus.RECORDING) return
        try {
            s.recorder?.pause()
        } catch (e: Exception) {
            ErrorLog.problem("Could not pause: ${e.message}", e)
            return
        }
        val now = System.currentTimeMillis()
        s.pausedAtMs = now
        s.record.addEvent(now, "Paused")
        RecorderState.update { status(RecStatus.PAUSED); paused(now) }
        updateNotification(paused = true)
    }

    private fun resume() {
        val s = session ?: return
        if (s.stopping || RecorderState.statusNow != RecStatus.PAUSED) return
        try {
            s.recorder?.resume()
        } catch (e: Exception) {
            ErrorLog.problem("Could not resume: ${e.message}", e)
            return
        }
        val now = System.currentTimeMillis()
        if (s.pausedAtMs > 0) s.pausedTotalMs += now - s.pausedAtMs
        s.pausedAtMs = 0
        s.record.addEvent(now, "Resumed")
        RecorderState.update { status(RecStatus.RECORDING); resumed(now) }
        updateNotification()
    }

    private fun toggleTorch() {
        val s = session ?: return
        if (s.stopping || !s.torchAvailable) return
        val b = s.request ?: return
        val cs = s.capture ?: return
        val on = !s.torchOn
        try {
            applyTorch(b, on)
            cs.setRepeatingRequest(b.build(), null, rec)
            s.torchOn = on
            s.record.addEvent(System.currentTimeMillis(), if (on) "Light turned on" else "Light turned off")
            RecorderState.update { torch(true, on) }
            updateNotification(paused = s.pausedAtMs > 0)
        } catch (e: Exception) {
            ErrorLog.problem("Could not switch the light: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // Stop
    // ------------------------------------------------------------------

    private fun fail(s: Session, why: String) {
        if (s.stopping) return
        Haptics.failed(this)
        stopRecording("failed: $why", why, failed = true)
    }

    /** On the rec thread. [problem] goes to the problems log. */
    private fun stopRecording(reason: String, problem: String? = null, failed: Boolean = false) {
        val s = session ?: run {
            // Nothing running. (While the last pieces are being sealed the
            // status is STOPPING: leave it alone, sealing finishes the service.)
            if (RecorderState.statusNow == RecStatus.IDLE) main.post { finishService(false) }
            return
        }
        if (s.stopping) return
        s.stopping = true
        rec.removeCallbacks(startWatchdog)
        rec.removeCallbacks(monitor)
        rec.removeCallbacks(autoStop)
        s.locationSignal?.cancel()
        RecorderState.update { status(RecStatus.STOPPING) }
        if (problem != null) ErrorLog.problem(problem)

        val now = System.currentTimeMillis()
        var cleanly = true
        val r = s.recorder
        if (r != null && s.recorderStarted) {
            try {
                r.stop()
            } catch (e: Exception) {
                cleanly = false
            }
        }
        try { r?.release() } catch (_: Exception) {}
        s.recorder = null
        closeCamera(s)
        s.next?.let { discard(it) }
        s.next = null
        val last = s.current
        s.current = null
        if (last != null) {
            if (s.recorderStarted) {
                last.endedMs = now
                seal(s, last, cleanly)
            } else {
                discard(last)
            }
        }
        if (s.pausedAtMs > 0) s.record.addEvent(now, "Resumed (by stopping)")
        session = null

        val record = s.record
        sealer.execute {
            val pieces = record.read { it.getJSONArray("pieces").length() }
            val anySaved = pieces > 0
            if (anySaved) {
                record.edit { j ->
                    j.put("status", if (failed) "failed" else "complete")
                    j.put("ended", SessionRecord.moment(now))
                    j.put("end_reason", reason)
                    j.put("recorded_duration_ms", (now - s.startedMs - s.pausedTotalMs).coerceAtLeast(0))
                }
                record.addEvent(now, "Recording ended: $reason")
                record.save(this)
            } else {
                // Nothing was recorded: leave no empty record behind.
                record.deletePublic(this)
                SessionRecord.deleteLocal(this, record.id)
            }
            main.post { finishService(anySaved && !failed) }
        }
    }

    /** On the main thread. */
    private fun finishService(saved: Boolean) {
        RecorderState.update { reset() }
        if (saved) Haptics.stopped(this)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    private fun newPiece(s: Session, video: Boolean): Piece? {
        val cr = contentResolver
        val index = s.nextIndex
        val name = Library.pieceName(video, s.id, index, null)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_RECORDINGS) + "/" + s.plan.folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, now)
        }
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        return try {
            val uri = cr.insert(collection, values) ?: return null
            val pfd = try {
                cr.openFileDescriptor(uri, "rw")
            } catch (e: Exception) {
                null
            }
            if (pfd == null) {
                try { cr.delete(uri, null, null) } catch (_: Exception) {}
                return null
            }
            s.nextIndex = index + 1
            Piece(index, uri, pfd, name, video)
        } catch (e: Exception) {
            ErrorLog.problem("Could not create $name: ${e.message}", e)
            null
        }
    }

    /** Removes a piece that holds nothing useful (never started). */
    private fun discard(p: Piece) {
        try { p.pfd.close() } catch (_: Exception) {}
        try { contentResolver.delete(p.uri, null, null) } catch (_: Exception) {}
    }

    /**
     * Closes a finished piece, fingerprints it while it is still hidden from
     * other apps, makes it visible, and adds it to the evidence record.
     */
    private fun seal(s: Session, p: Piece, cleanly: Boolean) {
        val record = s.record
        sealer.execute {
            val cr = contentResolver
            val size = try { p.pfd.statSize } catch (_: Exception) { -1L }
            try { p.pfd.close() } catch (_: Exception) {}
            if (size == 0L) {
                try { cr.delete(p.uri, null, null) } catch (_: Exception) {}
                record.addEvent(System.currentTimeMillis(), "Piece ${p.index} was empty and was removed")
                record.save(this)
                return@execute
            }
            val actualName = try {
                cr.query(p.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Exception) { null } ?: p.name

            var hash: String? = null
            var hashError: String? = null
            try {
                hash = Evidence.sha256(cr, p.uri)
            } catch (e: Exception) {
                hashError = e.message
                ErrorLog.problem("Could not fingerprint $actualName: ${e.message}", e)
            }
            val containerMs = Evidence.mp4CreationTimeMs(cr, p.uri)
            try {
                cr.update(p.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                // Galleries sort by this: make it the moment the piece began.
                cr.update(p.uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, p.startedMs) }, null, null)
            } catch (e: Exception) {
                ErrorLog.problem("Could not finish saving $actualName: ${e.message}", e)
            }
            val prev = record.lastHash()
            val folder = (if (p.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_RECORDINGS) + "/" + s.plan.folder
            record.edit { j ->
                j.getJSONArray("pieces").put(JSONObject()
                    .put("index", p.index)
                    .put("file", actualName)
                    .put("original_file", actualName)
                    .put("folder", folder)
                    .put("mime_type", if (p.isVideo) "video/mp4" else "audio/mp4")
                    .put("bytes", size)
                    .put("started", SessionRecord.moment(p.startedMs))
                    .put("ended", SessionRecord.moment(p.endedMs))
                    .put("sha256", hash ?: JSONObject.NULL)
                    .put("sha256_error", hashError ?: JSONObject.NULL)
                    .put("previous_sha256", prev ?: JSONObject.NULL)
                    .put("sha256_computed", SessionRecord.moment(System.currentTimeMillis()))
                    .put("container_creation_time_utc", containerMs?.let { Times.utc(it) } ?: JSONObject.NULL)
                    .put("closed_cleanly", cleanly)
                    .put("note", if (cleanly) JSONObject.NULL else
                        "The recorder reported a problem while closing this piece. It was kept, but it may not play fully."))
            }
            record.save(this)
            s.savedPieces++
        }
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun updateNotification(paused: Boolean = false) {
        val n = notification(session, paused)
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (_: Exception) {
        }
    }

    private fun action(icon: Int, label: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code,
            Intent(this, RecordService::class.java).setAction(action).putExtra(EXTRA_SOURCE, Recorder.SOURCE_NOTIFICATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun notification(s: Session?, paused: Boolean = false): Notification {
        val discreet = s?.plan?.discreet ?: Prefs.discreet
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, App.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)

        val running = s != null && s.recorderStarted && !s.stopping
        if (!running) {
            b.setContentTitle(if (discreet) "QuickRecord" else "Starting…").setShowWhen(false)
            return b.build()
        }
        s!!
        val mode = s.mode
        if (discreet) {
            b.setContentTitle("QuickRecord").setContentText(if (paused) "Paused" else "Active")
        } else {
            b.setContentTitle(if (paused) "Paused" else "Recording")
            val parts = ArrayList<String>()
            if (mode == RecMode.AUDIO) parts += "Sound only" else s.config?.let { parts += it.label }
            if (mode == RecMode.VIDEO) parts += "no sound"
            if (s.nextIndex > 2) parts += "piece ${s.nextIndex - 1}"
            b.setContentText(parts.joinToString(" · "))
        }
        if (paused) {
            b.setShowWhen(false)
        } else {
            // The timer counts from this moment, so it leaves out paused time.
            b.setWhen(s.startedMs + s.pausedTotalMs).setUsesChronometer(true).setShowWhen(true)
        }
        b.addAction(action(R.drawable.ic_stop, "Stop", ACTION_STOP, 1))
        b.addAction(if (paused) action(R.drawable.ic_play, "Resume", ACTION_RESUME, 2)
                    else action(R.drawable.ic_pause, "Pause", ACTION_PAUSE, 2))
        if (s.torchAvailable && mode != RecMode.AUDIO) {
            b.addAction(action(R.drawable.ic_torch, if (s.torchOn) "Light off" else "Light on", ACTION_TORCH, 3))
        }
        return b.build()
    }
}
