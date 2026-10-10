package com.deluxe.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.deluxe.recorder.ui.AboutScreen
import com.deluxe.recorder.ui.DeluxeTheme
import com.deluxe.recorder.util.FileUtils
import com.deluxe.recorder.ui.HistoryScreen
import com.deluxe.recorder.ui.MainScreen
import com.deluxe.recorder.ui.PermissionsScreen

class MainActivity : AppCompatActivity() {

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            RecordingState.setProjectionData(result.resultCode, result.data!!)
            RecordingState.setQuality(RecordingState.resolution, RecordingState.bitrateMbps, RecordingState.fps)
            startRecordingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Permissão de captura necessária", Toast.LENGTH_SHORT).show()
        }
    }

    private val screenshotProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            RecordingState.setProjectionData(result.resultCode, result.data!!)
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_SCREENSHOT
                putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RecordingService.EXTRA_DATA, result.data!!)
            }
            startForegroundService(intent)
        } else {
            Toast.makeText(this, "Permissão de captura necessária", Toast.LENGTH_SHORT).show()
        }
    }

    private fun prefs() = getSharedPreferences("perms", MODE_PRIVATE)

    private fun allPermissionsGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return false
        if (!Settings.canDrawOverlays(this) && !prefs().getBoolean("overlay_bypassed", false)) return false
        return true
    }

    private fun expandNotificationPanel() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "perms_done_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "Permissões",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Permissões concedidas"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(this, channelId)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(this)
            }.apply {
                setSmallIcon(R.drawable.ic_record)
                setContentTitle("Deluxe Recorder")
                setContentText("Todas as permissões foram concedidas!")
                setAutoCancel(true)
                setPriority(android.app.Notification.PRIORITY_HIGH)
            }.build()
            nm.notify(9999, notification)
            val idleIntent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_INIT_IDLE
            }
            startService(idleIntent)
        } catch (_: Exception) {}
    }

    private fun closeSystemDialogs() {
        try {
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                startActivity(CollapseShadeActivity.createIntent(this))
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        RecordingState.loadSettings(this)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        setTheme(R.style.AppTheme_Dark)
        super.onCreate(savedInstanceState)
        closeSystemDialogs()
        val barStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(
            statusBarStyle = barStyle,
            navigationBarStyle = barStyle
        )
        setContent {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            LaunchedEffect(Unit) {
                insetsController.isAppearanceLightStatusBars = false
                insetsController.isAppearanceLightNavigationBars = false
            }
            DeluxeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppContent()
                }
            }
        }
    }

    @Composable
    private fun AppContent() {
        var showHistory by remember { mutableStateOf(false) }
        var showAbout by remember { mutableStateOf(false) }
        var appLanguage by remember { mutableStateOf(RecordingState.language) }
        var recordingState by remember { mutableStateOf(RecordingState.isRecording) }
        var resolution by remember { mutableStateOf(RecordingState.resolution) }
        var bitrateMbps by remember { mutableIntStateOf(RecordingState.bitrateMbps) }
        var fps by remember { mutableIntStateOf(RecordingState.fps) }
        var audioSource by remember { mutableStateOf(RecordingState.audioSource) }

        fun checkNotif(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true

        fun checkAudio(): Boolean =
            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun checkOverlay(): Boolean =
            Settings.canDrawOverlays(this@MainActivity) || prefs().getBoolean("overlay_bypassed", false)

        var hasNotifPerm by remember { mutableStateOf(checkNotif()) }
        var hasAudioPerm by remember { mutableStateOf(checkAudio()) }
        var hasOverlayPerm by remember { mutableStateOf(checkOverlay()) }

        val refreshPerms = {
            hasNotifPerm = checkNotif()
            hasAudioPerm = checkAudio()
            hasOverlayPerm = checkOverlay()
        }

        var permsGranted by remember {
            mutableStateOf(prefs().getBoolean("perms_granted", false) && allPermissionsGranted())
        }

        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    refreshPerms()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        val overlayLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            prefs().edit().putBoolean("overlay_bypassed", true).apply()
            refreshPerms()
            if (checkNotif() && checkAudio()) {
                prefs().edit().putBoolean("perms_granted", true).apply()
                permsGranted = true
                expandNotificationPanel()
            }
        }

        val audioPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            refreshPerms()
            if (granted) {
                val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_RECORD_AUDIO,
                    android.os.Process.myUid(), packageName
                )
                if (mode == android.app.AppOpsManager.MODE_FOREGROUND &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Toast.makeText(
                        this@MainActivity,
                        "Para áudio interno, mude para 'Permitir sempre' em Configurações > Apps > Deluxe Recorder > Permissões > Microfone",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                }
                if (!checkOverlay()) {
                    overlayLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${packageName}"))
                    )
                } else if (checkNotif()) {
                    prefs().edit().putBoolean("perms_granted", true).apply()
                    permsGranted = true
                    expandNotificationPanel()
                }
            } else {
                Toast.makeText(this@MainActivity, "Permissão de áudio necessária", Toast.LENGTH_LONG).show()
            }
        }

        val notificationPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { _ ->
            refreshPerms()
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else if (!checkOverlay()) {
                overlayLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${packageName}"))
                )
            } else {
                prefs().edit().putBoolean("perms_granted", true).apply()
                permsGranted = true
                expandNotificationPanel()
            }
        }

        val handleRequestPermissions = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else if (!checkOverlay()) {
                overlayLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${packageName}"))
                )
            } else {
                prefs().edit().putBoolean("perms_granted", true).apply()
                permsGranted = true
                expandNotificationPanel()
            }
        }

        DisposableEffect(Unit) {
            val listener: (Boolean) -> Unit = { recordingState = it }
            val langListener: (String) -> Unit = { appLanguage = it }
            RecordingState.addListener(listener)
            RecordingState.addLanguageListener(langListener)
            onDispose {
                RecordingState.removeListener(listener)
                RecordingState.removeLanguageListener(langListener)
            }
        }

        LaunchedEffect(permsGranted) {
            if (permsGranted && !RecordingState.isRecording) {
                try {
                    val idleIntent = Intent(this@MainActivity, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_INIT_IDLE
                    }
                    startService(idleIntent)
                } catch (_: Exception) {}
            }
        }

        val currentScreen = when {
            !permsGranted -> "permissions"
            showHistory -> "history"
            showAbout -> "about"
            else -> "main"
        }

        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState == "history") {
                    (slideInHorizontally(tween(350)) { it } + fadeIn(tween(300)))
                        .togetherWith(slideOutHorizontally(tween(350)) { -it / 3 } + fadeOut(tween(200)))
                } else if (targetState == "about") {
                    (slideInHorizontally(tween(350)) { it } + fadeIn(tween(300)))
                        .togetherWith(slideOutHorizontally(tween(350)) { -it / 3 } + fadeOut(tween(200)))
                } else if (initialState == "history" || initialState == "about") {
                    (slideInHorizontally(tween(350)) { -it / 3 } + fadeIn(tween(300)))
                        .togetherWith(slideOutHorizontally(tween(350)) { it } + fadeOut(tween(200)))
                } else {
                    fadeIn(tween(300)).togetherWith(fadeOut(tween(200)))
                }
            },
            label = "screenTransition"
        ) { screen ->
            when (screen) {
                "permissions" -> PermissionsScreen(
                    hasNotifPerm = hasNotifPerm,
                    hasAudioPerm = hasAudioPerm,
                    hasOverlayPerm = hasOverlayPerm,
                    onRequestNotif = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRequestAudio = {
                        audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onRequestOverlay = {
                        try {
                            overlayLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${packageName}"))
                            )
                        } catch (e: Exception) {
                            prefs().edit().putBoolean("overlay_bypassed", true).apply()
                            refreshPerms()
                        }
                    },
                    onRequestPending = handleRequestPermissions,
                    onAllGrantedContinue = {
                        prefs().edit().putBoolean("perms_granted", true).apply()
                        permsGranted = true
                        expandNotificationPanel()
                    }
                )
                "history" -> HistoryScreen(
                    language = appLanguage,
                    onBack = { showHistory = false }
                )
                "about" -> {
                    val aboutStats = remember {
                        val stat = StatFs(Environment.getDataDirectory().path)
                        val totalBytes = stat.totalBytes
                        val availableBytes = stat.availableBytes
                        val usedBytes = totalBytes - stat.availableBytes
                        val totalGB = (totalBytes / (1024.0 * 1024.0 * 1024.0)).toInt()
                        val usedGB = (usedBytes / (1024.0 * 1024.0 * 1024.0)).toInt()
                        val totalRecordedMs = FileUtils.getTotalRecordingDurationMs(this@MainActivity)
                        val maxDurationMs = RecordingState.calculateEstimatedDurationMs(availableBytes)
                        listOf(totalRecordedMs, maxDurationMs, usedGB.toLong(), totalGB.toLong())
                    }
                    AboutScreen(
                        language = appLanguage,
                        totalRecordedMs = aboutStats[0],
                        maxDurationMs = aboutStats[1],
                        usedStorageGB = aboutStats[2].toInt(),
                        totalStorageGB = aboutStats[3].toInt(),
                        onBack = { showAbout = false }
                    )
                }
                else -> MainScreen(
                    language = appLanguage,
                    onLanguageChange = { newLang ->
                        appLanguage = newLang
                        RecordingState.setLanguage(newLang, this@MainActivity)
                    },
                    isRecording = recordingState,
                    latestFilePath = RecordingState.currentFilePath,
                    resolution = resolution,
                    bitrateMbps = bitrateMbps,
                    fps = fps,
                    orientation = RecordingState.orientation,
                    audioSource = audioSource,
                    countdownSeconds = RecordingState.countdownSeconds,
                    onResolutionChange = { resolution = it; RecordingState.setQuality(it, bitrateMbps, fps, this@MainActivity) },
                    onBitrateChange = { bitrateMbps = it; RecordingState.setQuality(resolution, it, fps, this@MainActivity) },
                    onFpsChange = { fps = it; RecordingState.setQuality(resolution, bitrateMbps, it, this@MainActivity) },
                    onOrientationChange = { RecordingState.setOrientation(it, this@MainActivity) },
                    onAudioSourceChange = { audioSource = it; RecordingState.setAudioSource(it, this@MainActivity) },
                    onCountdownChange = { RecordingState.setCountdown(it, this@MainActivity) },
                    onStartRecording = {
                        try {
                            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    onStopRecording = { stopRecordingService() },
                    onShowHistory = { showHistory = true },
                    onShowAbout = { showAbout = true },
                    onScreenshot = { sendScreenshotIntent() }
                )
            }
        }
    }

    private fun sendScreenshotIntent() {
        val resultCode = RecordingState.projectionResultCode
        val data = RecordingState.projectionData
        if (resultCode != 0 && data != null) {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_SCREENSHOT
                putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(RecordingService.EXTRA_DATA, data)
            }
            startForegroundService(intent)
            Toast.makeText(this, "Capturando tela...", Toast.LENGTH_SHORT).show()
        } else {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            screenshotProjectionLauncher.launch(manager.createScreenCaptureIntent())
        }
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
        try {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START_RECORDING
                putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(RecordingService.EXTRA_DATA, data)
                putExtra(RecordingService.EXTRA_RESOLUTION, RecordingState.resolution)
                putExtra(RecordingService.EXTRA_BITRATE_MBPS, RecordingState.bitrateMbps)
                putExtra(RecordingService.EXTRA_FPS, RecordingState.fps)
                putExtra(RecordingService.EXTRA_ORIENTATION, RecordingState.orientation)
                putExtra(RecordingService.EXTRA_AUDIO_SOURCE, RecordingState.audioSource)
                putExtra(RecordingService.EXTRA_COUNTDOWN, RecordingState.countdownSeconds)
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao iniciar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun stopRecordingService() {
        try {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP_RECORDING
            }
            startService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao parar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
