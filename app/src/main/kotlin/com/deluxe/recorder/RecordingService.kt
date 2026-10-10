package com.deluxe.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.deluxe.recorder.util.FileUtils

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class RecordingDimensions(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val scaledDpi: Int,
    val physicalHeight: Int,
    val isLandscape: Boolean,
    val orientationHint: Int
)

class RecordingService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var stopping = false
    private var videoDrainThread: Thread? = null
    private var audioDrainThread: Thread? = null
    private var recordingInitThread: Thread? = null

    companion object {
        const val ACTION_INIT_SERVICE = "com.deluxe.recorder.INIT_SERVICE"
        const val ACTION_INIT_IDLE = "com.deluxe.recorder.INIT_IDLE"
        const val ACTION_START_RECORDING = "com.deluxe.recorder.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.deluxe.recorder.STOP_RECORDING"
        const val ACTION_PAUSE_RECORDING = "com.deluxe.recorder.PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "com.deluxe.recorder.RESUME_RECORDING"
        const val ACTION_SCREENSHOT = "com.deluxe.recorder.SCREENSHOT"
        const val ACTION_REQUEST_SCREENSHOT_PROJECTION = "com.deluxe.recorder.REQUEST_SCREENSHOT_PROJECTION"
        const val ACTION_EXIT = "com.deluxe.recorder.EXIT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_RESOLUTION = "resolution"
        const val EXTRA_BITRATE_MBPS = "bitrate_mbps"
        const val EXTRA_FPS = "fps"
        const val EXTRA_ORIENTATION = "orientation"
        const val EXTRA_AUDIO_SOURCE = "audio_source"
        const val EXTRA_COUNTDOWN = "countdown"
        private const val CHANNEL_ID = "screen_recorder"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "ScreenRecorder"
        private const val FIFO_MAX_SAMPLES = 176_400 // 2 segundos de buffer em 44.1kHz stereo PCM
        private const val STALL_TIMEOUT_MS = 8000L
    }

    private val languageListener: (String) -> Unit = {
        try {
            val notification = buildCustomNotification(isRecording = isRecordingActive)
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private val pauseListener: (Boolean) -> Unit = { _ ->
        try {
            if (isRecordingActive) {
                val notification = buildCustomNotification(isRecording = true)
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {}
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var outputFile: File? = null

    private var audioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var videoEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    @Volatile private var muxerStarted = false
    @Volatile private var isRecordingActive = false
    private var hasAudioTrack = true
    @Volatile private var lastVideoFrameMs = 0L
    private var stallRestarting = false
    private var watchdogThread: Thread? = null
    private var finishRecordingThread: Thread? = null
    private var screenshotThread: Thread? = null
    @Volatile private var isServiceDestroyed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var restartAfterFinish = false
    @Volatile private var restartResultCode = 0
    @Volatile private var restartData: Intent? = null
    @Volatile private var pauseStartTimeNs = 0L
    @Volatile private var totalPauseDurationUs = 0L
    @Volatile private var initialVideoPtsUs = -1L

    private val pendingOutputData = mutableListOf<Pair<java.nio.ByteBuffer, MediaCodec.BufferInfo>>()

    private class MuxerSample(
        val trackIndex: Int,
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo
    )

    private val ramMuxerQueue = LinkedBlockingQueue<MuxerSample>(5000)
    @Volatile private var isWriterRunning = false
    private var muxerWriterThread: Thread? = null

    private fun startMuxerWriterThread() {
        ramMuxerQueue.clear()
        isWriterRunning = true
        muxerWriterThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            Log.d(TAG, "MuxerWriterThread started (Async RAM Buffer)")
            while (isWriterRunning || (muxerStarted && ramMuxerQueue.isNotEmpty())) {
                if (!muxerStarted) {
                    if (!isWriterRunning) break
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                    continue
                }
                val sample = ramMuxerQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                muxerLock.withLock {
                    if (muxerStarted) {
                        try {
                            mediaMuxer?.writeSampleData(sample.trackIndex, sample.data, sample.info)
                        } catch (e: Exception) {
                            Log.w(TAG, "Async Muxer writeSampleData error: ${e.message}")
                        }
                    }
                }
            }
            Log.d(TAG, "MuxerWriterThread finished cleanly, remaining=${ramMuxerQueue.size}")
        }, "MuxerAsyncRamWriter").also { it.start() }
    }

    private fun enqueueMuxerSample(trackIndex: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (trackIndex >= 0 && info.size > 0) {
            ramMuxerQueue.offer(MuxerSample(trackIndex, data, info))
        }
    }


    private val muxerLock = ReentrantLock()
    private var finishLatch = CountDownLatch(2)

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            stopRecording()
        }
    }

    private fun releaseMediaProjection() {
        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
        projectionCallback = null
    }

    private fun releaseAndResetComponents() {
        Log.d(TAG, "releaseAndResetComponents: liberando todos os recursos")
        isRecordingActive = false
        isWriterRunning = false
        try { muxerWriterThread?.join(2000) } catch (_: Exception) {}
        muxerWriterThread = null
        ramMuxerQueue.clear()
        joinDrainThreads()
        recordingInitThread?.let { t ->
            if (t.isAlive) {
                t.interrupt()
                try { t.join(2000) } catch (_: Exception) {}
            }
            recordingInitThread = null
        }
        try { videoEncoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { videoEncoder?.stop(); videoEncoder?.release() } catch (_: Exception) {}
        videoEncoder = null
        try { audioEncoder?.stop(); audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { micAudioRecord?.stop(); micAudioRecord?.release() } catch (_: Exception) {}
        micAudioRecord = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try {
            muxerLock.withLock {
                if (muxerStarted) {
                    mediaMuxer?.stop()
                    mediaMuxer?.release()
                }
            }
        } catch (_: Exception) {}
        mediaMuxer = null
        muxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1
        releaseMediaProjection()
        RecordingState.clearProjectionData()
        stopping = false
        outputFile = null
        pauseStartTimeNs = 0L
        totalPauseDurationUs = 0L
        initialVideoPtsUs = -1L

        Log.d(TAG, "releaseAndResetComponents: todos os recursos liberados")
    }

    private fun stopVideoWatchdog() {
        watchdogThread?.let { t ->
            if (t.isAlive) {
                t.interrupt()
                try { t.join(1000) } catch (_: Exception) {}
            }
        }
        watchdogThread = null
    }

    private fun joinDrainThreads() {
        stopVideoWatchdog()
        listOf(videoDrainThread, audioDrainThread).forEach { t ->
            t?.let {
                if (it.isAlive) {
                    it.interrupt()
                    try { it.join(3000) } catch (_: Exception) {}
                }
            }
        }
        videoDrainThread = null
        audioDrainThread = null
    }

    private fun calculateDimensions(): RecordingDimensions {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            displayMetrics.widthPixels = bounds.width()
            displayMetrics.heightPixels = bounds.height()
            displayMetrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            display.getRealMetrics(displayMetrics)
        }
        val physicalW = displayMetrics.widthPixels
        val physicalH = displayMetrics.heightPixels
        val densityDpi = displayMetrics.densityDpi

        val longEdgePreset = when (RecordingState.resolution) {
            "1080p" -> 1920
            "720p" -> 1280
            "480p" -> 854
            "360p" -> 640
            else -> 1280
        }

        val aspectRatio = maxOf(physicalW, physicalH).toFloat() / minOf(physicalW, physicalH).toFloat()
        val shortEdge = (longEdgePreset / aspectRatio + 0.5f).toInt().coerceAtLeast(240)

        val rotation = wm.defaultDisplay.rotation
        val isPhysicalLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

        val isLandscape = when (RecordingState.orientation) {
            "horizontal" -> true
            "vertical" -> false
            else -> isPhysicalLandscape
        }

        val rawWidth: Int
        val rawHeight: Int
        if (isLandscape) {
            rawWidth = longEdgePreset
            rawHeight = shortEdge
        } else {
            rawWidth = shortEdge
            rawHeight = longEdgePreset
        }

        val alignedWidth = (rawWidth + 15) and 15.inv()
        val alignedHeight = (rawHeight + 15) and 15.inv()

        val orientationHint = 0

        Log.d(TAG, "calculateDimensions: physical=${physicalW}x${physicalH} preset=${longEdgePreset} aspectRatio=${aspectRatio} landscape=${isLandscape} result=${alignedWidth}x${alignedHeight} hint=${orientationHint}")
        val scaledDpi = (densityDpi * alignedHeight) / physicalH
        return RecordingDimensions(alignedWidth, alignedHeight, densityDpi, scaledDpi, physicalH, isLandscape, orientationHint)
    }

    override fun onCreate() {
        super.onCreate()
        RecordingState.loadSettings(this)
        createNotificationChannel()
        RecordingState.addLanguageListener(languageListener)
        RecordingState.addPauseListener(pauseListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, IntentFilter(ACTION_STOP_RECORDING), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(stopReceiver, IntentFilter(ACTION_STOP_RECORDING))
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "onTrimMemory: level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && isRecordingActive) {
            Log.w(TAG, "Critical memory! Parando gravacao para evitar perda do arquivo")
            stopRecording()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_INIT_SERVICE -> {
                Log.d(TAG, "Init service requested")
                setupMediaProjection()
                Log.d(TAG, "MediaProjection setup done, no foreground started")
            }
            ACTION_INIT_IDLE -> {
                Log.d(TAG, "Init idle - showing notification")
                updateNotificationIdle()
            }
            ACTION_START_RECORDING -> {
                closeNotificationShade()
                Log.d(TAG, "Starting foreground service")
                startForegroundWithType(buildCustomNotification(isRecording = true))
                Log.d(TAG, "Foreground started")

                var resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                var data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

                Log.d(TAG, "Received: resultCode=$resultCode data=${data != null}")

                if (resultCode == 0 || data == null) {
                    if (RecordingState.projectionResultCode != 0 && RecordingState.projectionData != null) {
                        resultCode = RecordingState.projectionResultCode
                        data = RecordingState.projectionData
                    }
                }

                Log.d(TAG, "Final: resultCode=$resultCode data=${data != null}")

                if (resultCode != 0 && data != null) {
                    if (RecordingState.hideOverlay) {
                        val hideIntent = Intent(this, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_HIDE_BUBBLE
                        }
                        startService(hideIntent)
                        Log.d(TAG, "Overlay hidden for clean recording")
                    } else {
                        val overlayIntent = Intent(this, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_SHOW_RECORDING
                        }
                        startService(overlayIntent)
                        Log.d(TAG, "Overlay shown")
                    }

                    Log.d(TAG, "Calling startRecording in background")
                    recordingInitThread = Thread({
                        android.os.Looper.prepare()
                        startRecording(resultCode, data)
                    }, "RecordingInitThread").also { it.start() }
                } else {
                    Log.e(TAG, "Invalid data, staying in idle")
                    updateNotificationIdle()
                }
            }
            ACTION_STOP_RECORDING -> {
                closeNotificationShade()
                Log.d(TAG, "Stop recording requested")
                stopRecording()
            }
            ACTION_PAUSE_RECORDING -> {
                closeNotificationShade()
                Log.d(TAG, "Pause recording requested")
                startForegroundWithType(buildCustomNotification(isRecording = true))
                pauseRecording()
            }
            ACTION_RESUME_RECORDING -> {
                closeNotificationShade()
                Log.d(TAG, "Resume recording requested")
                startForegroundWithType(buildCustomNotification(isRecording = true))
                resumeRecording()
            }
            ACTION_SCREENSHOT -> {
                closeNotificationShade()
                Log.d(TAG, "Screenshot requested")
                if (intent.hasExtra(EXTRA_RESULT_CODE)) {
                    val rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    @Suppress("DEPRECATION")
                    val d = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                    if (rc != 0 && d != null) {
                        RecordingState.setProjectionData(rc, d)
                    }
                }
                startForegroundWithType(buildCustomNotification(isRecording = false))
                takeScreenshot()
            }
            ACTION_EXIT -> {
                closeNotificationShade()
                Log.d(TAG, "Exit requested")
                exitApp()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupMediaProjection() {
        val resultCode = RecordingState.projectionResultCode
        val data = RecordingState.projectionData

        if (resultCode == 0 || data == null) {
            Log.w(TAG, "No projection data available")
            return
        }

        try {
            releaseMediaProjection()
            val projectionManager = applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val cb = ProjectionCallback(this)
            projectionCallback = cb
            mediaProjection = projectionManager.getMediaProjection(resultCode, data).apply {
                registerCallback(cb, Handler(Looper.getMainLooper()))
            }
            Log.d(TAG, "MediaProjection created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MediaProjection: ${e.message}")
        }
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        Log.d(TAG, "startRecording called (audio=${RecordingState.audioSource})")
        stopping = false
        stallRestarting = false
        restartAfterFinish = false
        lastVideoFrameMs = 0L

        try {
            RecordingState.setProjectionData(resultCode, data)
            if (mediaProjection == null) {
                setupMediaProjection()
            }

            if (mediaProjection == null) {
                throw RuntimeException("MediaProjection não disponível")
            }

            outputFile = FileUtils.createOutputFile(this)

            val dim = calculateDimensions()

            Log.d(TAG, "Quality: ${RecordingState.resolution} ${dim.width}x${dim.height} ${RecordingState.bitrateMbps}Mbps ${RecordingState.fps}fps audio=${RecordingState.audioSource} orientation=${RecordingState.orientation} landscape=${dim.isLandscape} hint=${dim.orientationHint}")

            startRecordingInternal(dim)


        } catch (e: Exception) {
            Log.e(TAG, "Recording failed: ${e.message}")
            e.printStackTrace()
            mainHandler.post {
                if (isServiceDestroyed) return@post
                Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
            }
            cleanup()
            RecordingState.setRecording(false)
            updateNotificationIdle()
        }
    }

    private fun startRecordingInternal(dim: RecordingDimensions) {
        when (RecordingState.audioSource) {
            "mute" -> {
                startVideoOnlyRecording(dim)
            }
            "internal" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startInternalAudioRecording(dim)
                } else {
                    throw RuntimeException("Áudio interno requer Android 10 ou superior")
                }
            }
            "mic" -> {
                startMicAudioRecording(dim)
            }
            "both" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startBothAudioRecording(dim)
                } else {
                    throw RuntimeException("Áudio interno requer Android 10 ou superior")
                }
            }
        }
    }

    private fun startVideoOnlyRecording(dim: RecordingDimensions) {
        Log.d(TAG, "Setting up VIDEO-ONLY recording (mute)")

        val videoEncoderResult = createVideoEncoderWithFallback(dim)
        videoEncoder = videoEncoderResult.encoder
        Log.d(TAG, "Video encoder configured at ${videoEncoderResult.width}x${videoEncoderResult.height}")

        val encoderInputSurface = videoEncoder!!.createInputSurface()
        val scaledDpi = (dim.densityDpi * videoEncoderResult.height) / resources.displayMetrics.heightPixels

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenRecorder",
            videoEncoderResult.width,
            videoEncoderResult.height,
            scaledDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderInputSurface,
            null,
            null
        )
        Log.d(TAG, "VirtualDisplay created")

        mediaMuxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mediaMuxer!!.setOrientationHint(dim.orientationHint)
        hasAudioTrack = false
        finishLatch = CountDownLatch(1)
        videoTrackIndex = -1
        audioTrackIndex = -1
        muxerStarted = false

        videoEncoder!!.start()
        isRecordingActive = true
        startMuxerWriterThread()
        Log.d(TAG, "Video encoder started, no audio track")

        videoDrainThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            drainVideoEncoder()
        }, "VideoEncoderDrain").also { it.start() }

        RecordingState.setRecording(true, outputFile)
        Log.d(TAG, "Recording started successfully (VIDEO-ONLY)")

        startVideoWatchdog()

        vibrate()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startInternalAudioRecording(dim: RecordingDimensions) {
        Log.d(TAG, "Setting up INTERNAL audio recording")

        val audioEncoderConfig = createAudioEncoderConfig()
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder!!.configure(audioEncoderConfig, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        Log.d(TAG, "Audio encoder configured")

        val videoEncoderResult = createVideoEncoderWithFallback(dim)
        videoEncoder = videoEncoderResult.encoder
        val finalVideoWidth = videoEncoderResult.width
        val finalVideoHeight = videoEncoderResult.height
        Log.d(TAG, "Video encoder configured at ${finalVideoWidth}x${finalVideoHeight}")

        val encoderInputSurface = videoEncoder!!.createInputSurface()
        val scaledDpi = (dim.densityDpi * finalVideoHeight) / resources.displayMetrics.heightPixels

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenRecorder",
            finalVideoWidth,
            finalVideoHeight,
            scaledDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderInputSurface,
            null,
            null
        )
        Log.d(TAG, "VirtualDisplay created")

        val audioConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        var audioInitialized = false
        for (attempt in 1..3) {
            try {
                audioRecord?.release()
                audioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(audioConfig)
                    .setAudioFormat(audioFormat)
                    .build()

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioInitialized = true
                    Log.d(TAG, "AudioRecord initialized on attempt $attempt")
                    break
                } else {
                    Log.w(TAG, "AudioRecord not initialized on attempt $attempt, state=${audioRecord?.state}")
                    audioRecord?.release()
                    audioRecord = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord creation failed on attempt $attempt: ${e.message}")
                audioRecord?.release()
                audioRecord = null
            }
        }

        if (!audioInitialized) {
            Log.e(TAG, "AudioRecord failed after 3 attempts, cleaning up")
            cleanup()
            throw RuntimeException("Falha ao inicializar captura de áudio interno após múltiplas tentativas")
        }

        mediaMuxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mediaMuxer!!.setOrientationHint(dim.orientationHint)
        hasAudioTrack = true
        finishLatch = CountDownLatch(2)
        videoTrackIndex = -1
        audioTrackIndex = -1
        muxerStarted = false

        videoEncoder!!.start()
        audioEncoder!!.start()
        audioRecord!!.startRecording()
        isRecordingActive = true
        startMuxerWriterThread()
        Log.d(TAG, "Encoders and AudioRecord started")
        videoDrainThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            drainVideoEncoder()
        }, "VideoEncoderDrain").also { it.start() }

        audioDrainThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            drainAudioEncoder()
        }, "AudioEncoderThread").also { it.start() }

        RecordingState.setRecording(true, outputFile)
        Log.d(TAG, "Recording started successfully (INTERNAL)")

        startVideoWatchdog()

        vibrate()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startMicAudioRecording(dim: RecordingDimensions) {
        Log.d(TAG, "Setting up MIC audio recording")

        val audioFormatStereo = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        var record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormatStereo)
                .build()
        } catch (_: Exception) { null }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            val audioFormatMono = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormatMono)
                .build()
        }

        micAudioRecord = record
        if (micAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Mic AudioRecord failed to initialize")
            throw RuntimeException("Falha ao inicializar microfone")
        }
        Log.d(TAG, "Mic AudioRecord initialized (channels=${micAudioRecord?.channelCount})")

        val audioEncoderConfig = createAudioEncoderConfig()
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder!!.configure(audioEncoderConfig, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val videoEncoderResult = createVideoEncoderWithFallback(dim)
        videoEncoder = videoEncoderResult.encoder
        Log.d(TAG, "Video encoder configured at ${videoEncoderResult.width}x${videoEncoderResult.height}")

        val encoderInputSurface = videoEncoder!!.createInputSurface()
        val scaledDpi = (dim.densityDpi * videoEncoderResult.height) / resources.displayMetrics.heightPixels

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenRecorder",
            videoEncoderResult.width,
            videoEncoderResult.height,
            scaledDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderInputSurface,
            null,
            null
        )
        Log.d(TAG, "VirtualDisplay created")

        mediaMuxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mediaMuxer!!.setOrientationHint(dim.orientationHint)
        hasAudioTrack = true
        finishLatch = CountDownLatch(2)
        videoTrackIndex = -1
        audioTrackIndex = -1
        muxerStarted = false

        videoEncoder!!.start()
        audioEncoder!!.start()
        micAudioRecord!!.startRecording()
        isRecordingActive = true
        startMuxerWriterThread()
        Log.d(TAG, "Encoders and Mic AudioRecord started")
        videoDrainThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            drainVideoEncoder()
        }, "VideoEncoderDrain").also { it.start() }

        audioDrainThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            drainMicAudioEncoder()
        }, "AudioEncoderThread").also { it.start() }

        RecordingState.setRecording(true, outputFile)
        Log.d(TAG, "Recording started successfully (MIC)")

        startVideoWatchdog()

        vibrate()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startBothAudioRecording(dim: RecordingDimensions) {
        Log.d(TAG, "Setting up BOTH (internal+mic) audio recording")

        val audioFormatStereo = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        var record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormatStereo)
                .build()
        } catch (_: Exception) { null }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            val audioFormatMono = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormatMono)
                .build()
        }

        micAudioRecord = record
        if (micAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Mic AudioRecord failed to initialize")
            throw RuntimeException("Falha ao inicializar microfone")
        }
        Log.d(TAG, "Mic AudioRecord initialized (channels=${micAudioRecord?.channelCount})")

        val audioEncoderConfig = createAudioEncoderConfig()
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder!!.configure(audioEncoderConfig, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val videoEncoderResult = createVideoEncoderWithFallback(dim)
        videoEncoder = videoEncoderResult.encoder
        Log.d(TAG, "Video encoder configured at ${videoEncoderResult.width}x${videoEncoderResult.height}")

        val encoderInputSurface = videoEncoder!!.createInputSurface()
        val scaledDpi = (dim.densityDpi * videoEncoderResult.height) / resources.displayMetrics.heightPixels

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenRecorder",
            videoEncoderResult.width,
            videoEncoderResult.height,
            scaledDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderInputSurface,
            null,
            null
        )
        Log.d(TAG, "VirtualDisplay created")

        val internalConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        var audioInitialized = false
        for (attempt in 1..3) {
            try {
                audioRecord?.release()
                audioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(internalConfig)
                    .setAudioFormat(audioFormatStereo)
                    .build()

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioInitialized = true
                    Log.d(TAG, "Internal AudioRecord initialized on attempt $attempt")
                    break
                } else {
                    Log.w(TAG, "Internal AudioRecord not initialized on attempt $attempt, state=${audioRecord?.state}")
                    audioRecord?.release()
                    audioRecord = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Internal AudioRecord creation failed on attempt $attempt: ${e.message}")
                audioRecord?.release()
                audioRecord = null
            }
        }

        if (!audioInitialized) {
            Log.e(TAG, "Internal AudioRecord failed after 3 attempts, cleaning up")
            cleanup()
            throw RuntimeException("Falha ao inicializar captura de áudio interno após múltiplas tentativas")
        }

        mediaMuxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mediaMuxer!!.setOrientationHint(dim.orientationHint)
        hasAudioTrack = true
        finishLatch = CountDownLatch(2)
        videoTrackIndex = -1
        audioTrackIndex = -1
        muxerStarted = false

        videoEncoder!!.start()
        audioEncoder!!.start()
        audioRecord!!.startRecording()
        micAudioRecord!!.startRecording()
        isRecordingActive = true
        startMuxerWriterThread()
        Log.d(TAG, "Encoders and both AudioRecords started")
        videoDrainThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            drainVideoEncoder()
        }, "VideoEncoderDrain").also { it.start() }

        audioDrainThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            drainBothAudioEncoder()
        }, "AudioEncoderThread").also { it.start() }

        RecordingState.setRecording(true, outputFile)
        Log.d(TAG, "Recording started successfully (BOTH)")

        startVideoWatchdog()

        vibrate()
    }

    private fun getAdaptiveBitrate(res: String, fps: Int): Int = when {
        res == "1080p" && fps == 60 -> 12_000_000
        res == "1080p" && fps == 30 -> 8_000_000
        res == "720p" && fps == 60  -> 6_000_000
        res == "720p" && fps == 30  -> 4_000_000
        res == "480p"               -> 2_500_000
        res == "360p"               -> 1_500_000
        else -> 4_000_000
    }

    private fun isHevcSupported(): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080)
            codecList.findEncoderForFormat(format) != null
        } catch (e: Exception) {
            false
        }
    }

    private fun createAudioEncoderConfig(): MediaFormat {
        return MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
    }

    private data class VideoEncoderResult(
        val encoder: MediaCodec,
        val width: Int,
        val height: Int
    )

    private fun createVideoEncoderWithFallback(dim: RecordingDimensions): VideoEncoderResult {
        val videoMime = if (RecordingState.useHevc && isHevcSupported()) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val bitrateBps = getAdaptiveBitrate(RecordingState.resolution, RecordingState.fps)
        val iFrameInterval = if (RecordingState.fps >= 60) 2 else 3
        val isLowRes = dim.height <= 480

        Log.d(TAG, "Codec: $videoMime, Bitrate: $bitrateBps, iFrame: $iFrameInterval, isLowRes: $isLowRes")

        var encoder = MediaCodec.createEncoderByType(videoMime)
        var w = dim.width
        var h = dim.height

        val repeatFrameUs = 1_000_000L / RecordingState.fps
        val format = MediaFormat.createVideoFormat(videoMime, w, h).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, RecordingState.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatFrameUs)
            if (videoMime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                if (isLowRes) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                } else {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                }
            }
        }

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "Configure failed at ${w}x${h}, retrying at 720p")
            encoder.release()
            w = 1280; h = 720
            encoder = MediaCodec.createEncoderByType(videoMime)
            val fallbackFormat = MediaFormat.createVideoFormat(videoMime, w, h).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, RecordingState.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatFrameUs)
                if (videoMime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                }
            }
            encoder.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        return VideoEncoderResult(encoder, w, h)
    }

    private fun drainMicAudioEncoder() {
        val info = MediaCodec.BufferInfo()
        val encoder = audioEncoder ?: return
        val micRecord = micAudioRecord ?: return

        val isMono = micRecord.channelCount == 1
        val bufferSize = AudioRecord.getMinBufferSize(
            44100,
            if (isMono) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val readBuffer = ShortArray(maxOf(bufferSize / 2, 1024))
        val fifo = BoundedShortArrayFifo(FIFO_MAX_SAMPLES)
        val chunkSize = 2048 // 1024 stereo frames (AAC-LC standard)
        val shortBuffer = ShortArray(chunkSize)
        var audioFramesQueued = 0
        var totalAudioFramesQueued = 0L

        val readerThread = Thread({
            while (isRecordingActive) {
                try {
                    RecordingState.awaitNotPaused()
                    val read = micRecord.read(readBuffer, 0, readBuffer.size)
                    if (read > 0) {
                        if (isMono) {
                            val stereoConverted = ShortArray(read * 2)
                            for (i in 0 until read) {
                                stereoConverted[i * 2] = readBuffer[i]
                                stereoConverted[i * 2 + 1] = readBuffer[i]
                            }
                            fifo.write(stereoConverted, stereoConverted.size)
                        } else {
                            fifo.write(readBuffer, read)
                        }
                        fifo.signalDataAvailable()
                    } else if (read < 0) {
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Mic audio read error: ${e.message}")
                    break
                }
            }
            fifo.signalDataAvailable()
        }, "MicReaderThread")
        readerThread.start()

        try {
            while (isRecordingActive) {
                if (fifo.size() < chunkSize) {
                    fifo.waitForData(20)
                    continue
                }

                fifo.read(shortBuffer, chunkSize)

                try {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        inputBuffer.order(ByteOrder.nativeOrder())
                        val sb = inputBuffer.asShortBuffer()
                        sb.put(shortBuffer, 0, chunkSize)
                        val ptsUs = (totalAudioFramesQueued * 1_000_000L) / 44100L
                        encoder.queueInputBuffer(inputIndex, 0, chunkSize * 2, ptsUs, 0)
                        totalAudioFramesQueued += 1024L
                        audioFramesQueued++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Mic audio encode error: ${e.message}")
                    break
                }

                drainEncoderOutput(encoder, info, isAudio = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mic audio encoder loop exception: ${e.message}")
            e.printStackTrace()
            isRecordingActive = false
        }

        readerThread.join(2000)
        Log.d(TAG, "Mic audio encoding loop ended, $audioFramesQueued frames queued")

        val finalAudioPtsUs = (totalAudioFramesQueued * 1_000_000L) / 44100L
        sendAudioEOS(encoder, info, audioFramesQueued, finalAudioPtsUs)

        try { encoder.stop(); encoder.release() } catch (_: Exception) {}
        audioEncoder = null
        Log.d(TAG, "Mic audio encoder stopped and released")

        finishLatch.countDown()
    }

    private fun drainBothAudioEncoder() {
        val info = MediaCodec.BufferInfo()
        val encoder = audioEncoder ?: return
        val internalRecord = audioRecord ?: return
        val micRecord = micAudioRecord ?: return

        val isMonoMic = micRecord.channelCount == 1
        val internalBufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val micBufferSize = AudioRecord.getMinBufferSize(
            44100,
            if (isMonoMic) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val internalBuffer = ShortArray(internalBufferSize / 2)
        val micBuffer = ShortArray(micBufferSize / 2)
        val chunkSize = 2048 // 1024 stereo frames (AAC-LC standard)
        val mixedBuffer = ShortArray(chunkSize)
        val internalData = ShortArray(chunkSize)
        val micData = ShortArray(chunkSize)
        var audioFramesQueued = 0
        var totalAudioFramesQueued = 0L

        val fifoInternal = BoundedShortArrayFifo(FIFO_MAX_SAMPLES)
        val fifoMic = BoundedShortArrayFifo(FIFO_MAX_SAMPLES)

        val internalReader = Thread({
            while (isRecordingActive) {
                try {
                    RecordingState.awaitNotPaused()
                    val read = internalRecord.read(internalBuffer, 0, internalBuffer.size)
                    if (read > 0) fifoInternal.write(internalBuffer, read)
                    else if (read < 0) break
                } catch (e: Exception) {
                    Log.w(TAG, "Internal audio read error: ${e.message}")
                    break
                }
            }
            Log.d(TAG, "Internal reader thread ended")
            fifoInternal.signalDataAvailable()
        }, "InternalReaderThread")
        internalReader.start()

        val micReader = Thread({
            while (isRecordingActive) {
                try {
                    RecordingState.awaitNotPaused()
                    val read = micRecord.read(micBuffer, 0, micBuffer.size)
                    if (read > 0) {
                        if (isMonoMic) {
                            val stereoConverted = ShortArray(read * 2)
                            for (i in 0 until read) {
                                stereoConverted[i * 2] = micBuffer[i]
                                stereoConverted[i * 2 + 1] = micBuffer[i]
                            }
                            fifoMic.write(stereoConverted, stereoConverted.size)
                        } else {
                            fifoMic.write(micBuffer, read)
                        }
                    } else if (read < 0) break
                } catch (e: Exception) {
                    Log.w(TAG, "Mic audio read error: ${e.message}")
                    break
                }
            }
            Log.d(TAG, "Mic reader thread ended")
            fifoMic.signalDataAvailable()
        }, "MicReaderThread")
        micReader.start()

        try {
            while (isRecordingActive) {
                val hasInternal = fifoInternal.size() >= chunkSize
                val hasMic = fifoMic.size() >= chunkSize

                if (!hasInternal && !hasMic) {
                    fifoInternal.waitForData(20)
                    continue
                }

                if (hasInternal) {
                    fifoInternal.read(internalData, chunkSize)
                } else {
                    internalData.fill(0)
                }

                if (hasMic) {
                    fifoMic.read(micData, chunkSize)
                } else {
                    micData.fill(0)
                }

                if (hasInternal && hasMic) {
                    for (i in 0 until chunkSize) {
                        mixedBuffer[i] = ((internalData[i].toInt() + micData[i].toInt()) / 2).toShort()
                    }
                } else if (hasInternal) {
                    System.arraycopy(internalData, 0, mixedBuffer, 0, chunkSize)
                } else {
                    System.arraycopy(micData, 0, mixedBuffer, 0, chunkSize)
                }

                try {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        inputBuffer.order(ByteOrder.nativeOrder())
                        val sb = inputBuffer.asShortBuffer()
                        sb.put(mixedBuffer, 0, chunkSize)
                        val ptsUs = (totalAudioFramesQueued * 1_000_000L) / 44100L
                        encoder.queueInputBuffer(inputIndex, 0, chunkSize * 2, ptsUs, 0)
                        totalAudioFramesQueued += 1024L
                        audioFramesQueued++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Mixed audio encode error: ${e.message}")
                    break
                }

                drainEncoderOutput(encoder, info, isAudio = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Both audio encoder loop exception: ${e.message}")
            e.printStackTrace()
            isRecordingActive = false
        }

        internalReader.join(2000)
        micReader.join(2000)
        Log.d(TAG, "Both audio encoding loop ended, $audioFramesQueued frames queued")

        val finalAudioPtsUs = (totalAudioFramesQueued * 1_000_000L) / 44100L
        sendAudioEOS(encoder, info, audioFramesQueued, finalAudioPtsUs)

        try { encoder.stop(); encoder.release() } catch (_: Exception) {}
        audioEncoder = null
        Log.d(TAG, "Both audio encoder stopped and released")

        finishLatch.countDown()
    }

    private fun drainVideoEncoder() {
        val info = MediaCodec.BufferInfo()
        val encoder = videoEncoder ?: return

        try {
            while (isRecordingActive) {
                val outputIndex = encoder.dequeueOutputBuffer(info, 10_000)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerLock.withLock {
                        videoTrackIndex = mediaMuxer!!.addTrack(encoder.outputFormat)
                        checkAndStartMuxerLocked()
                    }
                } else if (outputIndex >= 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && !RecordingState.isPaused) {
                        if (initialVideoPtsUs < 0) {
                            initialVideoPtsUs = info.presentationTimeUs
                        }
                        val adjustedPtsUs = (info.presentationTimeUs - initialVideoPtsUs - totalPauseDurationUs).coerceAtLeast(0L)

                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val copied = ByteBuffer.allocate(info.size).order(ByteOrder.nativeOrder())
                        copied.put(outputBuffer)
                        copied.flip()
                        val copiedInfo = MediaCodec.BufferInfo()
                        copiedInfo.set(0, info.size, adjustedPtsUs, info.flags)

                        if (videoTrackIndex >= 0) {
                            enqueueMuxerSample(videoTrackIndex, copied, copiedInfo)
                            lastVideoFrameMs = System.currentTimeMillis()
                        }
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video encoder loop exception: ${e.message}")
            e.printStackTrace()
            isRecordingActive = false
        }

        Log.d(TAG, "Video drain loop ended, signaling EOS")
        try {
            encoder.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "Signal EOS error: ${e.message}")
        }

        var eosDrained = false
        var eosAttempts = 0
        while (!eosDrained && eosAttempts < 10) {
            val outputIndex = encoder.dequeueOutputBuffer(info, 10_000)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxerLock.withLock {
                    videoTrackIndex = mediaMuxer!!.addTrack(encoder.outputFormat)
                    checkAndStartMuxerLocked()
                }
            } else if (outputIndex >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputIndex) ?: continue
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && !RecordingState.isPaused) {
                    if (initialVideoPtsUs < 0) {
                        initialVideoPtsUs = info.presentationTimeUs
                    }
                    val adjustedPtsUs = (info.presentationTimeUs - initialVideoPtsUs - totalPauseDurationUs).coerceAtLeast(0L)

                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)
                    val copied = ByteBuffer.allocate(info.size).order(ByteOrder.nativeOrder())
                    copied.put(outputBuffer)
                    copied.flip()
                    val copiedInfo = MediaCodec.BufferInfo()
                    copiedInfo.set(0, info.size, adjustedPtsUs, info.flags)

                    if (videoTrackIndex >= 0) {
                        enqueueMuxerSample(videoTrackIndex, copied, copiedInfo)
                        lastVideoFrameMs = System.currentTimeMillis()
                    }
                }
                encoder.releaseOutputBuffer(outputIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(TAG, "Video encoder EOS drained")
                    eosDrained = true
                }
            } else {
                eosAttempts++
                if (eosAttempts >= 10) {
                    Log.w(TAG, "Video EOS drain timed out after $eosAttempts attempts")
                    break
                }
            }
        }

        try { encoder.stop(); encoder.release() } catch (_: Exception) {}
        videoEncoder = null
        Log.d(TAG, "Video encoder stopped and released")

        finishLatch.countDown()
    }

    private fun startVideoWatchdog() {
        if (watchdogThread?.isAlive == true) return
        watchdogThread = Thread({
            while (isRecordingActive && !stopping) {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
                if (!isRecordingActive || stopping || RecordingState.isPaused || stallRestarting) continue
                val idleMs = System.currentTimeMillis() - lastVideoFrameMs
                if (idleMs >= STALL_TIMEOUT_MS && lastVideoFrameMs > 0) {
                    Log.w(TAG, "WATCHDOG: video encoder stalled for ${idleMs}ms, restarting recording")
                    stallRestarting = true
                    restartAfterFinish = true
                    restartResultCode = RecordingState.projectionResultCode
                    restartData = RecordingState.projectionData
                    finishRecording()
                }
            }
            watchdogThread = null
        }, "VideoWatchdog").also { it.start() }
    }

    private fun drainAudioEncoder() {
        val info = MediaCodec.BufferInfo()
        val encoder = audioEncoder ?: run {
            Log.e(TAG, "drainAudioEncoder: audioEncoder is null, aborting")
            finishLatch.countDown()
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val readBuffer = ShortArray(bufferSize / 2)
        val fifo = BoundedShortArrayFifo(FIFO_MAX_SAMPLES)
        val chunkSize = 2048 // 1024 stereo frames (AAC-LC standard)
        val shortBuffer = ShortArray(chunkSize)
        var audioFramesQueued = 0
        var totalBytesRead = 0L
        var logCounter = 0
        var totalAudioFramesQueued = 0L

        Log.d(TAG, "drainAudioEncoder: starting, audioRecord=${audioRecord != null}, bufferSize=$bufferSize")

        val readerThread = Thread({
            var readerFrames = 0
            while (isRecordingActive) {
                try {
                    RecordingState.awaitNotPaused()
                    val read = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
                    if (read > 0) {
                        fifo.write(readBuffer, read)
                        fifo.signalDataAvailable()
                        totalBytesRead += read * 2
                        readerFrames++
                        if (readerFrames % 500 == 0) {
                            Log.d(TAG, "AudioReader: $readerFrames frames read, ${totalBytesRead} bytes total, fifo=${fifo.size()}")
                        }
                    } else if (read == 0) {
                        Log.w(TAG, "AudioReader: read() returned 0 (no data)")
                    } else {
                        Log.e(TAG, "AudioReader: read() returned error: $read")
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioReader: error: ${e.message}")
                    break
                }
            }
            Log.d(TAG, "AudioReader: ended, $readerFrames frames, $totalBytesRead bytes")
            fifo.signalDataAvailable()
        }, "AudioReaderThread")
        readerThread.start()

        try {
            while (isRecordingActive) {
                if (fifo.size() < chunkSize) {
                    fifo.waitForData(20)
                    continue
                }

                fifo.read(shortBuffer, chunkSize)

                try {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        inputBuffer.order(ByteOrder.nativeOrder())
                        val sb = inputBuffer.asShortBuffer()
                        sb.put(shortBuffer, 0, chunkSize)
                        val ptsUs = (totalAudioFramesQueued * 1_000_000L) / 44100L
                        encoder.queueInputBuffer(inputIndex, 0, chunkSize * 2, ptsUs, 0)
                        totalAudioFramesQueued += 1024L
                        audioFramesQueued++
                    } else {
                        Log.w(TAG, "AudioEncoder: no input buffer available")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioEncoder: encode error: ${e.message}")
                    break
                }

                drainEncoderOutput(encoder, info, isAudio = true)

                logCounter++
                if (logCounter % 500 == 0) {
                    Log.d(TAG, "AudioEncoder: $audioFramesQueued frames queued, audioTrackIndex=$audioTrackIndex, muxerStarted=$muxerStarted")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioEncoder: loop exception: ${e.message}")
            e.printStackTrace()
            isRecordingActive = false
        }

        readerThread.join(2000)
        Log.d(TAG, "AudioEncoder: loop ended, $audioFramesQueued frames queued, audioTrackIndex=$audioTrackIndex, muxerStarted=$muxerStarted")

        val finalAudioPtsUs = (totalAudioFramesQueued * 1_000_000L) / 44100L
        sendAudioEOS(encoder, info, audioFramesQueued, finalAudioPtsUs)

        try { encoder.stop(); encoder.release() } catch (_: Exception) {}
        audioEncoder = null
        Log.d(TAG, "AudioEncoder: stopped and released")

        finishLatch.countDown()
    }

    private fun drainEncoderOutput(encoder: MediaCodec, info: MediaCodec.BufferInfo, isAudio: Boolean) {
        var outputIndex = encoder.dequeueOutputBuffer(info, 1000)
        while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxerLock.withLock {
                    if (isAudio) {
                        audioTrackIndex = mediaMuxer!!.addTrack(encoder.outputFormat)
                        Log.d(TAG, "Audio track added to muxer, index=$audioTrackIndex")
                    } else {
                        videoTrackIndex = mediaMuxer!!.addTrack(encoder.outputFormat)
                        Log.d(TAG, "Video track added to muxer, index=$videoTrackIndex")
                    }
                    checkAndStartMuxerLocked()
                }
            } else if (outputIndex >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && !RecordingState.isPaused) {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex) ?: continue
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)
                    val copied = ByteBuffer.allocate(info.size).order(ByteOrder.nativeOrder())
                    copied.put(outputBuffer)
                    copied.flip()
                    val copiedInfo = MediaCodec.BufferInfo()
                    val ptsUs = if (isAudio) info.presentationTimeUs else {
                        if (initialVideoPtsUs < 0) initialVideoPtsUs = info.presentationTimeUs
                        (info.presentationTimeUs - initialVideoPtsUs - totalPauseDurationUs).coerceAtLeast(0L)
                    }
                    copiedInfo.set(0, info.size, ptsUs, info.flags)

                    val trackIndex = if (isAudio) audioTrackIndex else videoTrackIndex
                    if (trackIndex >= 0) {
                        enqueueMuxerSample(trackIndex, copied, copiedInfo)
                    }
                }
                encoder.releaseOutputBuffer(outputIndex, false)
            }
            outputIndex = encoder.dequeueOutputBuffer(info, 0)
        }
    }

    private fun sendAudioEOS(encoder: MediaCodec, info: MediaCodec.BufferInfo, framesQueued: Int, finalAudioPtsUs: Long) {
        if (framesQueued > 0) {
            try {
                val inputIndex = encoder.dequeueInputBuffer(5_000)
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, finalAudioPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio EOS error: ${e.message}")
            }

            try {
                var eosDrained = false
                var eosAttempts = 0
                var currentEosAudioPtsUs = finalAudioPtsUs
                while (!eosDrained && eosAttempts < 10) {
                    val outputIndex = encoder.dequeueOutputBuffer(info, 10_000)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxerLock.withLock {
                            audioTrackIndex = mediaMuxer!!.addTrack(encoder.outputFormat)
                            checkAndStartMuxerLocked()
                        }
                    } else if (outputIndex >= 0) {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex) ?: continue
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && !RecordingState.isPaused) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val copied = ByteBuffer.allocate(info.size).order(ByteOrder.nativeOrder())
                            copied.put(outputBuffer)
                            copied.flip()
                            val copiedInfo = MediaCodec.BufferInfo()
                            val ptsUs = if (info.presentationTimeUs in 0..currentEosAudioPtsUs + 100_000L) {
                                info.presentationTimeUs
                            } else {
                                currentEosAudioPtsUs
                            }
                            copiedInfo.set(0, info.size, ptsUs, info.flags)

                            if (audioTrackIndex >= 0) {
                                enqueueMuxerSample(audioTrackIndex, copied, copiedInfo)
                            }
                            currentEosAudioPtsUs += 23219L
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "Audio encoder EOS drained")
                            eosDrained = true
                        }
                    } else {
                        eosAttempts++
                        if (eosAttempts >= 10) {
                            Log.w(TAG, "Audio EOS drain timed out after $eosAttempts attempts")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio drain error: ${e.message}")
            }
        }
    }

    private fun checkAndStartMuxerLocked() {
        if (!muxerStarted && videoTrackIndex >= 0 && (audioTrackIndex >= 0 || !hasAudioTrack)) {
            mediaMuxer?.start()
            muxerStarted = true
            Log.d(TAG, "Muxer started, video=$videoTrackIndex audio=$audioTrackIndex")

            if (pendingOutputData.isNotEmpty()) {
                Log.d(TAG, "Flushing ${pendingOutputData.size} pending audio samples")
                for ((buf, bufInfo) in pendingOutputData) {
                    if (audioTrackIndex >= 0) {
                        mediaMuxer?.writeSampleData(audioTrackIndex, buf, bufInfo)
                    }
                }
                pendingOutputData.clear()
                Log.d(TAG, "Pending audio samples flushed")
            }
        }
    }
    private fun finishRecording() {
        Log.d(TAG, "finishRecording called")
        isRecordingActive = false
        if (RecordingState.isPaused) {
            if (pauseStartTimeNs > 0) {
                totalPauseDurationUs += (System.nanoTime() - pauseStartTimeNs) / 1000
                pauseStartTimeNs = 0L
            }
            RecordingState.setPaused(false)
        }

        finishRecordingThread = Thread({
            val latchCompleted = try {
                finishLatch.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }

            if (!latchCompleted) {
                Log.w(TAG, "finishLatch timed out! Forcing shutdown")
            }

            // Flush RAM queue completely to disk before releasing muxer
            isWriterRunning = false
            try {
                muxerWriterThread?.join(5000)
            } catch (_: Exception) {}
            muxerWriterThread = null

            val savedFile = outputFile
            releaseAndResetComponents()

            Log.d(TAG, "Recording pipeline complete, file: ${savedFile?.absolutePath}")

            val publicFile = savedFile?.let { src ->
                if (!src.exists() || src.length() < 64 * 1024) {
                    Log.w(TAG, "File too small or invalid (${src.length()} bytes), discarded")
                    src.delete()
                    null
                } else {
                    FileUtils.copyToPublicStorage(this@RecordingService, src)?.also { public ->
                        src.delete()
                        Log.d(TAG, "File saved to public storage: ${public.absolutePath}")
                        android.media.MediaScannerConnection.scanFile(
                            this@RecordingService,
                            arrayOf(public.absolutePath),
                            arrayOf("video/mp4"),
                            null
                        )
                    }
                }
            }

            mainHandler.post {
                if (isServiceDestroyed) return@post
                RecordingState.setRecording(false, publicFile ?: savedFile)
                if (!RecordingState.hideOverlay) {
                    val overlayIntent = Intent(this@RecordingService, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_HIDE_BUBBLE
                    }
                    try { startService(overlayIntent) } catch (_: Exception) {}
                }
                vibrate()
                stopForeground(STOP_FOREGROUND_REMOVE)
                updateNotificationIdle()

                if (restartAfterFinish) {
                    restartAfterFinish = false
                    stallRestarting = false
                    val rc = restartResultCode
                    val data = restartData
                    if (rc != 0 && data != null) {
                        Log.w(TAG, "WATCHDOG: restarting recording automatically")
                        RecordingState.setProjectionData(rc, data)
                        startForegroundWithType(buildCustomNotification(isRecording = true))
                        recordingInitThread = Thread({
                            android.os.Looper.prepare()
                            startRecording(rc, data)
                        }, "RecordingRestartThread").also { it.start() }
                    }
                }
            }
        }, "FinishRecording").also { it.start() }
    }

    private fun pauseRecording() {
        if (!RecordingState.isRecording || RecordingState.isPaused) return
        pauseStartTimeNs = System.nanoTime()
        RecordingState.setPaused(true)
    }

    private fun resumeRecording() {
        if (!RecordingState.isRecording || !RecordingState.isPaused) return
        if (pauseStartTimeNs > 0) {
            totalPauseDurationUs += (System.nanoTime() - pauseStartTimeNs) / 1000
            pauseStartTimeNs = 0L
        }
        RecordingState.setPaused(false)
    }

    private fun stopRecording() {
        Log.d(TAG, "stopRecording called, isRecording=${RecordingState.isRecording}, stopping=$stopping")
        if (!RecordingState.isRecording || stopping) return
        stopping = true
        if (RecordingState.isPaused) {
            if (pauseStartTimeNs > 0) {
                totalPauseDurationUs += (System.nanoTime() - pauseStartTimeNs) / 1000
                pauseStartTimeNs = 0L
            }
            RecordingState.setPaused(false)
        }
        finishRecording()
    }

    private fun takeScreenshot() {
        Log.d(TAG, "takeScreenshot: projectionResultCode=${RecordingState.projectionResultCode}, mediaProjection=${mediaProjection != null}")
        if (RecordingState.projectionResultCode != 0 && RecordingState.projectionData != null) {
            setupMediaProjection()
            Log.d(TAG, "takeScreenshot: after setupMediaProjection, mediaProjection=${mediaProjection != null}")
        }

        if (mediaProjection != null) {
            Log.d(TAG, "takeScreenshot: calling captureScreenshot")
            captureScreenshot()
        } else if (!RecordingState.hideOverlay) {
            Log.d(TAG, "takeScreenshot: no projection, showing overlay")
            val overlayIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW_READY
            }
            startService(overlayIntent)
        } else {
            Log.d(TAG, "takeScreenshot: no projection, overlay hidden by preference")
        }
    }

    private fun collapseStatusBar() {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java)
                .invoke(null, "statusbar") as android.os.IBinder
            val stub = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            val proxy = stub.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)
            proxy.javaClass.getMethod("collapsePanels").invoke(proxy)
            Log.d(TAG, "collapsePanels via IStatusBarService succeeded")
        } catch (e: Exception) {
            Log.w(TAG, "IStatusBarService collapse failed: ${e.message}")
        }
    }

    private fun closeNotificationShade() {
        try {
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                startActivity(CollapseShadeActivity.createIntentFromService(this))
            } catch (_: Exception) {}
        } else {
            collapseStatusBar()
        }
    }

    private fun captureScreenshot() {
        Log.d(TAG, "captureScreenshot: starting")
        screenshotThread = Thread({
            var imageReader: ImageReader? = null
            var screenshotDisplay: VirtualDisplay? = null
            try {
                val displayMetrics = DisplayMetrics()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val metrics = wm.currentWindowMetrics
                    val bounds = metrics.bounds
                    displayMetrics.widthPixels = bounds.width()
                    displayMetrics.heightPixels = bounds.height()
                    displayMetrics.densityDpi = resources.displayMetrics.densityDpi
                } else {
                    @Suppress("DEPRECATION")
                    val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                    display.getRealMetrics(displayMetrics)
                }

                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels
                val density = displayMetrics.densityDpi

                imageReader = ImageReader.newInstance(
                    width, height,
                    PixelFormat.RGBA_8888, 1
                )

                screenshotDisplay = mediaProjection!!.createVirtualDisplay(
                    "Screenshot",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null, null
                )

                Thread.sleep(300)

                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    bitmap.recycle()

                    val file = FileUtils.saveScreenshot(this, croppedBitmap)
                    croppedBitmap.recycle()

                    mainHandler.post {
                        if (isServiceDestroyed) return@post
                        if (file != null) {
                            Toast.makeText(this, "Captura salva: ${file.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Erro ao salvar captura", Toast.LENGTH_SHORT).show()
                        }
                    }

                    image.close()
                } else {
                    mainHandler.post {
                        if (isServiceDestroyed) return@post
                        Toast.makeText(this, "Falha ao capturar tela", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Screenshot error: ${e.message}")
                mainHandler.post {
                    if (isServiceDestroyed) return@post
                    Toast.makeText(this, "Erro na captura: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                Log.d(TAG, "captureScreenshot: cleanup")
                screenshotDisplay?.release()
                imageReader?.close()
                if (!RecordingState.isRecording) {
                    Log.d(TAG, "captureScreenshot: stopping MediaProjection")
                    releaseMediaProjection()
                    RecordingState.clearProjectionData()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    updateNotificationIdle()
                }
            }
        }, "ScreenshotThread").also { it.start() }
    }

    private fun exitApp() {
        isRecordingActive = false
        releaseAndResetComponents()

        try {
            val overlayIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_HIDE_BUBBLE
            }
            startService(overlayIntent)
            stopService(overlayIntent)
        } catch (_: Exception) {}

        RecordingState.setRecording(false)
        RecordingState.clearProjectionData()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        Log.d(TAG, "cleanup")
        releaseAndResetComponents()
    }

    private class ProjectionCallback(service: RecordingService) : MediaProjection.Callback() {
        private val serviceRef = java.lang.ref.WeakReference(service)

        override fun onStop() {
            val s = serviceRef.get() ?: return
            if (s.isServiceDestroyed) return
            s.mainHandler.post {
                if (s.isServiceDestroyed) return@post
                if (RecordingState.isRecording) {
                    s.stopRecording()
                } else {
                    s.releaseMediaProjection()
                }
            }
        }
    }

    private var projectionCallback: ProjectionCallback? = null

    private fun vibrate() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (_: Exception) { }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_recording),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.recording_notification_title)
            setShowBadge(false)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val isMicNeeded = RecordingState.audioSource == "mic" || RecordingState.audioSource == "both"
            val type = if (isMicNeeded) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundWithType failed with type=$type, fallback: ${e.message}")
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } catch (e2: Exception) {
                    Log.e(TAG, "startForegroundWithType fallback failed: ${e2.message}")
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildCustomNotification(isRecording: Boolean): Notification {
        val remoteViews = RemoteViews(packageName, R.layout.notification_custom)

        val gravarPendingIntent = if (isRecording) {
            val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP_RECORDING }
            PendingIntent.getService(
                this, 10, stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            val recordIntent = Intent(this, RecordTrampolineActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            PendingIntent.getActivity(
                this, 10, recordIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val lang = RecordingState.language
        val isPt = lang == "pt"

        remoteViews.setTextViewText(R.id.notif_app_title, I18n.notifAppTitle(lang, isRecording))
        remoteViews.setTextViewText(R.id.notif_status_text, I18n.notifStatus(lang, isRecording))
        remoteViews.setTextViewText(R.id.notif_btn_record_text, I18n.notifBtnRecord(lang, isRecording))
        remoteViews.setTextViewText(R.id.notif_btn_screenshot_text, I18n.notifBtnScreenshot(lang))
        remoteViews.setTextViewText(R.id.notif_btn_home_text, I18n.notifBtnHome(lang))
        remoteViews.setTextViewText(R.id.notif_btn_exit_text, I18n.notifBtnExit(lang))

        if (isRecording) {
            remoteViews.setImageViewResource(R.id.notif_icon_gravar, R.drawable.ic_stop)
        } else {
            remoteViews.setImageViewResource(R.id.notif_icon_gravar, R.drawable.ic_record)
        }

        val screenshotIntent = Intent(this, CaptureTrampolineActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        val screenshotPendingIntent = PendingIntent.getActivity(
            this, 11, screenshotIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toolsPendingIntent = if (isRecording) {
            val isPaused = RecordingState.isPaused
            val pauseAction = if (isPaused) ACTION_RESUME_RECORDING else ACTION_PAUSE_RECORDING
            val pauseText = if (isPaused) I18n.notifBtnResume(lang) else I18n.notifBtnPause(lang)
            val pauseIcon = if (isPaused) R.drawable.ic_play else R.drawable.ic_pause

            remoteViews.setTextViewText(R.id.notif_btn_tools_text, pauseText)
            remoteViews.setImageViewResource(R.id.notif_icon_tools, pauseIcon)

            val pauseIntent = Intent(this, RecordingService::class.java).apply { action = pauseAction }
            PendingIntent.getService(
                this, 12, pauseIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            remoteViews.setTextViewText(R.id.notif_btn_tools_text, I18n.notifBtnBubble(lang))
            remoteViews.setImageViewResource(R.id.notif_icon_tools, R.drawable.ic_tools)

            val bubbleIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_TOGGLE_BUBBLE
            }
            PendingIntent.getService(
                this, 12, bubbleIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val homeIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val homePendingIntent = PendingIntent.getActivity(
            this, 13, homeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val exitIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_EXIT }
        val exitPendingIntent = PendingIntent.getService(
            this, 14, exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        remoteViews.setOnClickPendingIntent(R.id.notif_btn_gravar, gravarPendingIntent)
        remoteViews.setOnClickPendingIntent(R.id.notif_btn_screenshot, screenshotPendingIntent)
        remoteViews.setOnClickPendingIntent(R.id.notif_btn_tools, toolsPendingIntent)
        remoteViews.setOnClickPendingIntent(R.id.notif_btn_home, homePendingIntent)
        remoteViews.setOnClickPendingIntent(R.id.notif_btn_exit, exitPendingIntent)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 1, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentIntent(openPendingIntent)
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotificationIdle() {
        val notification = buildCustomNotification(isRecording = false)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        isServiceDestroyed = true
        isRecordingActive = false
        mainHandler.removeCallbacksAndMessages(null)
        stopVideoWatchdog()
        recordingInitThread?.let { t ->
            if (t.isAlive) {
                t.interrupt()
                try { t.join(1000) } catch (_: Exception) {}
            }
        }
        recordingInitThread = null
        finishRecordingThread?.let { t ->
            if (t.isAlive) {
                try { t.join(2000) } catch (_: Exception) {}
            }
        }
        finishRecordingThread = null
        screenshotThread?.let { t ->
            if (t.isAlive) {
                try { t.join(1000) } catch (_: Exception) {}
            }
        }
        screenshotThread = null
        joinDrainThreads()
        cleanup()
        RecordingState.removeLanguageListener(languageListener)
        RecordingState.removePauseListener(pauseListener)
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) { }
        RecordingState.setRecording(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private class BoundedShortArrayFifo(private val maxSize: Int) {
    private var buffer = ShortArray(1024 * 16)
    private var head = 0
    private var tail = 0
    private var size = 0
    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()

    fun write(data: ShortArray, length: Int) {
        lock.lock()
        try {
            val spaceNeeded = if (size + length > maxSize) {
                val drop = (size + length) - maxSize
                head = (head + drop) % buffer.size
                size -= drop
                length
            } else {
                length
            }
            ensureCapacity(size + spaceNeeded)
            val firstPart = minOf(spaceNeeded, buffer.size - tail)
            System.arraycopy(data, 0, buffer, tail, firstPart)
            if (spaceNeeded > firstPart) {
                System.arraycopy(data, firstPart, buffer, 0, spaceNeeded - firstPart)
            }
            tail = (tail + spaceNeeded) % buffer.size
            size += spaceNeeded
        } finally {
            lock.unlock()
        }
    }

    fun read(out: ShortArray, length: Int): Int {
        lock.lock()
        try {
            val toRead = minOf(length, size)
            val firstPart = minOf(toRead, buffer.size - head)
            System.arraycopy(buffer, head, out, 0, firstPart)
            if (toRead > firstPart) {
                System.arraycopy(buffer, 0, out, firstPart, toRead - firstPart)
            }
            head = (head + toRead) % buffer.size
            size -= toRead
            return toRead
        } finally {
            lock.unlock()
        }
    }

    fun size(): Int {
        lock.lock()
        try {
            return size
        } finally {
            lock.unlock()
        }
    }

    fun clear() {
        lock.lock()
        try {
            head = 0; tail = 0; size = 0
        } finally {
            lock.unlock()
        }
    }

    fun waitForData(timeoutMs: Long) {
        lock.lock()
        try {
            dataAvailable.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        } finally {
            lock.unlock()
        }
    }

    fun signalDataAvailable() {
        lock.lock()
        try {
            dataAvailable.signalAll()
        } finally {
            lock.unlock()
        }
    }

    private fun ensureCapacity(requiredCapacity: Int) {
        if (requiredCapacity > buffer.size) {
            var newCapacity = buffer.size * 2
            while (newCapacity < requiredCapacity) newCapacity *= 2
            val newBuffer = ShortArray(newCapacity)
            for (i in 0 until size) newBuffer[i] = buffer[(head + i) % buffer.size]
            buffer = newBuffer; head = 0; tail = size
        }
    }
}
