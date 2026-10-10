package com.deluxe.recorder

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object RecordingState {
    @Volatile var isRecording = false
        private set
    @Volatile var currentFilePath: String? = null
        private set
    var currentFile: File? = null
        private set

    @Volatile var isPaused = false
        private set

    private val pauseLock = java.lang.Object()

    @Volatile var elapsedSeconds = 0
        private set

    @Volatile var projectionResultCode: Int = 0
        private set
    var projectionData: Intent? = null
        private set

    var resolution = "360p"
        private set
    var bitrateMbps = 8
        private set
    var fps = 30
        private set

    var orientation = "auto"
        private set
    var audioSource = "internal"
        private set
    var countdownSeconds = 3
        private set

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val pauseListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    private const val PREFS_NAME = "quality_settings"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_BITRATE = "bitrate"
    private const val KEY_FPS = "fps"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_AUDIO_SOURCE = "audio_source"
    private const val KEY_COUNTDOWN = "countdown"
    private const val KEY_HIDE_OVERLAY = "hide_overlay"
    private const val KEY_LANGUAGE = "language"

    var hideOverlay = false
        private set

    var useHevc = true
        private set

    var language = "pt"
        private set

    private const val KEY_USE_HEVC = "use_hevc"

    fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        resolution = prefs.getString(KEY_RESOLUTION, "360p") ?: "360p"
        bitrateMbps = prefs.getInt(KEY_BITRATE, 8)
        fps = prefs.getInt(KEY_FPS, 30)
        orientation = prefs.getString(KEY_ORIENTATION, "auto") ?: "auto"
        audioSource = prefs.getString(KEY_AUDIO_SOURCE, "internal") ?: "internal"
        countdownSeconds = prefs.getInt(KEY_COUNTDOWN, 3)
        hideOverlay = prefs.getBoolean(KEY_HIDE_OVERLAY, false)
        useHevc = prefs.getBoolean(KEY_USE_HEVC, true)
        language = prefs.getString(KEY_LANGUAGE, "pt") ?: "pt"
    }

    fun saveSettings(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_RESOLUTION, resolution)
            .putInt(KEY_BITRATE, bitrateMbps)
            .putInt(KEY_FPS, fps)
            .putString(KEY_ORIENTATION, orientation)
            .putString(KEY_AUDIO_SOURCE, audioSource)
            .putInt(KEY_COUNTDOWN, countdownSeconds)
            .putBoolean(KEY_HIDE_OVERLAY, hideOverlay)
            .putBoolean(KEY_USE_HEVC, useHevc)
            .putString(KEY_LANGUAGE, language)
            .apply()
    }

    fun setRecording(recording: Boolean, file: File? = null) {
        isRecording = recording
        isPaused = false
        if (recording) elapsedSeconds = 0
        currentFile = file
        currentFilePath = file?.absolutePath
        listeners.forEach { it(recording) }
    }

    fun incrementElapsedSeconds() {
        if (isRecording && !isPaused) {
            elapsedSeconds++
        }
    }

    fun awaitNotPaused() {
        if (!isPaused) return
        synchronized(pauseLock) {
            while (isPaused) {
                pauseLock.wait()
            }
        }
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
        pauseListeners.forEach { it(paused) }
        if (!paused) {
            synchronized(pauseLock) {
                pauseLock.notifyAll()
            }
        }
    }

    fun isBatteryOptimizationExempted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestBatteryExemption(context: Context) {
        if (isBatteryOptimizationExempted(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun setProjectionData(resultCode: Int, data: Intent) {
        projectionResultCode = resultCode
        projectionData = data
    }

    fun clearProjectionData() {
        projectionResultCode = 0
        projectionData = null
    }

    fun setQuality(res: String, br: Int, f: Int, context: Context? = null) {
        resolution = res
        bitrateMbps = br
        fps = f
        context?.let { saveSettings(it) }
    }

    fun setOrientation(orient: String, context: Context? = null) {
        orientation = orient
        context?.let { saveSettings(it) }
    }

    fun setAudioSource(source: String, context: Context? = null) {
        audioSource = source
        context?.let { saveSettings(it) }
    }

    fun setHideOverlay(hide: Boolean, context: Context? = null) {
        hideOverlay = hide
        context?.let { saveSettings(it) }
    }

    fun setCountdown(seconds: Int, context: Context? = null) {
        countdownSeconds = seconds
        context?.let { saveSettings(it) }
    }

    fun setHevc(hevc: Boolean, context: Context? = null) {
        useHevc = hevc
        context?.let { saveSettings(it) }
    }

    private val languageListeners = mutableListOf<(String) -> Unit>()

    fun setLanguage(lang: String, context: Context? = null) {
        language = lang
        context?.let { saveSettings(it) }
        languageListeners.forEach { it(lang) }
    }

    fun addLanguageListener(listener: (String) -> Unit) {
        languageListeners.add(listener)
    }

    fun removeLanguageListener(listener: (String) -> Unit) {
        languageListeners.remove(listener)
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    fun addPauseListener(listener: (Boolean) -> Unit) {
        pauseListeners.add(listener)
    }

    fun removePauseListener(listener: (Boolean) -> Unit) {
        pauseListeners.remove(listener)
    }

    fun calculateEstimatedDurationMs(
        availableBytes: Long,
        res: String = resolution,
        f: Int = fps,
        br: Int = bitrateMbps
    ): Long {
        val resFactor = when (res) {
            "1080p" -> 1.0f
            "720p"  -> 0.65f
            "480p"  -> 0.40f
            "360p"  -> 0.25f
            else    -> 1.0f
        }
        val fpsFactor = when (f) {
            60   -> 1.0f
            30   -> 0.65f
            else -> 1.0f
        }
        val effectiveBitrateMbps = (br.toFloat() * resFactor * fpsFactor).coerceAtLeast(0.8f)
        val bps = (effectiveBitrateMbps * 1_000_000L).toLong()
        return if (bps > 0L && availableBytes > 0L) (availableBytes * 8L * 1000L) / bps else 0L
    }

    fun formatEstimatedDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        return when {
            hours > 0 && minutes > 0 -> "~${hours}h ${minutes}m"
            hours > 0 -> "~${hours}h"
            minutes > 0 -> "~${minutes}m"
            else -> "< 1m"
        }
    }
}

object I18n {
    fun isPt(lang: String) = lang == "pt"

    fun systemReady(lang: String) = if (isPt(lang)) "Sistema Pronto" else "System Ready"
    fun recordingNow(lang: String) = if (isPt(lang)) "Gravando agora" else "Recording now"
    fun freeGB(lang: String, gb: Int) = if (isPt(lang)) "$gb GB Livres" else "$gb GB Free"
    fun spaceForRecording(lang: String, time: String) = if (isPt(lang)) "Espaço para $time de gravação" else "Space for $time recording"
    fun recordBtn(lang: String, isRecording: Boolean) = if (isRecording) {
        if (isPt(lang)) "PARAR" else "STOP"
    } else {
        if (isPt(lang)) "GRAVAR" else "RECORD"
    }
    fun recordTitle(lang: String, isRecording: Boolean) = if (isRecording) {
        if (isPt(lang)) "Gravação em Andamento" else "Recording in Progress"
    } else {
        if (isPt(lang)) "Toque para Iniciar Gravação" else "Tap to Start Recording"
    }
    fun videoParams(lang: String) = if (isPt(lang)) "PARÂMETROS DE VÍDEO" else "VIDEO PARAMETERS"
    fun resolution(lang: String) = if (isPt(lang)) "Resolução" else "Resolution"
    fun fps(lang: String) = if (isPt(lang)) "Taxa de Quadros (FPS)" else "Frame Rate (FPS)"
    fun fpsLabel(lang: String, f: Int) = if (f == 60) {
        if (isPt(lang)) "60 FPS Fluido" else "60 FPS Smooth"
    } else {
        if (isPt(lang)) "30 FPS Padrão" else "30 FPS Standard"
    }
    fun bitrate(lang: String) = if (isPt(lang)) "Taxa de Bits (Bitrate)" else "Bitrate"
    fun audioSource(lang: String) = if (isPt(lang)) "Fonte de Áudio" else "Audio Source"
    fun audioLabel(lang: String, source: String) = when (source) {
        "mute" -> if (isPt(lang)) "Sem Áudio" else "No Audio"
        "internal" -> if (isPt(lang)) "Áudio Interno" else "Internal Audio"
        "mic" -> if (isPt(lang)) "Microfone" else "Microphone"
        "both" -> if (isPt(lang)) "Interno + Mic" else "Internal + Mic"
        else -> if (isPt(lang)) "Áudio Interno" else "Internal Audio"
    }
    fun audioDesc(lang: String, source: String) = when (source) {
        "mute" -> if (isPt(lang)) "Apenas vídeo mudo" else "Muted video only"
        "internal" -> if (isPt(lang)) "Sons do sistema e jogos" else "System and game sounds"
        "mic" -> if (isPt(lang)) "Voz e som ambiente" else "Voice and ambient audio"
        "both" -> if (isPt(lang)) "Áudio duplo sincronizado" else "Dual synced audio"
        else -> ""
    }
    fun advancedSettings(lang: String) = if (isPt(lang)) "CONFIGURAÇÕES AVANÇADAS" else "ADVANCED SETTINGS"
    fun hideOverlay(lang: String) = if (isPt(lang)) "Ocultar Botão Flutuante" else "Hide Floating Button"
    fun hideOverlayDesc(lang: String) = if (isPt(lang)) "Gravar sem o widget na tela" else "Record without on-screen widget"
    fun hevc(lang: String) = if (isPt(lang)) "Codec H.265 (HEVC)" else "H.265 (HEVC) Codec"
    fun hevcDesc(lang: String) = if (isPt(lang)) "Arquivos menores com alta qualidade" else "Smaller files with high quality"
    fun tabHome(lang: String) = if (isPt(lang)) "Início" else "Home"
    fun tabVideos(lang: String) = if (isPt(lang)) "Vídeos" else "Videos"
    fun tabAbout(lang: String) = if (isPt(lang)) "Sobre" else "About"

    // History
    fun galleryTitle(lang: String) = if (isPt(lang)) "Galeria de Gravações" else "Recording Gallery"
    fun gallerySubtitle(lang: String) = if (isPt(lang)) "Gravados no Armazenamento" else "Saved in Storage"
    fun selectedCount(lang: String, count: Int) = if (isPt(lang)) "$count selecionado${if (count > 1) "s" else ""}" else "$count selected"
    fun deselectAll(lang: String) = if (isPt(lang)) "Desmarcar" else "Deselect"
    fun selectAll(lang: String) = if (isPt(lang)) "Selecionar Tudo" else "Select All"
    fun deleteSelected(lang: String) = if (isPt(lang)) "Excluir selecionados" else "Delete selected"
    fun tabRecordedVideos(lang: String, count: Int) = if (isPt(lang)) "Vídeos Gravados ($count)" else "Recorded Videos ($count)"
    fun tabScreenshots(lang: String, count: Int) = if (isPt(lang)) "Capturas de Tela ($count)" else "Screenshots ($count)"
    fun noVideosTitle(lang: String) = if (isPt(lang)) "Nenhum Vídeo Gravado" else "No Recorded Videos"
    fun noVideosDesc(lang: String) = if (isPt(lang)) "Suas gravações de tela em alta qualidade aparecerão aqui." else "Your high quality screen recordings will appear here."
    fun startRecording(lang: String) = if (isPt(lang)) "Começar a Gravar" else "Start Recording"
    fun noScreenshotsTitle(lang: String) = if (isPt(lang)) "Nenhuma Captura Salva" else "No Saved Screenshots"
    fun noScreenshotsDesc(lang: String) = if (isPt(lang)) "Fotos capturadas pelo atalho da notificação ou do app aparecerão aqui." else "Photos captured from notification or app shortcut will appear here."
    fun takeScreenshot(lang: String) = if (isPt(lang)) "Tirar Captura de Tela" else "Take Screenshot"
    fun deleteDialogTitleSingle(lang: String) = if (isPt(lang)) "Excluir gravação?" else "Delete recording?"
    fun deleteDialogDescSingle(lang: String) = if (isPt(lang)) "Este item será removido permanentemente do seu dispositivo." else "This item will be permanently removed from your device."
    fun deleteDialogTitleBulk(lang: String) = if (isPt(lang)) "Excluir itens?" else "Delete items?"
    fun deleteDialogDescBulk(lang: String, count: Int) = if (isPt(lang)) "Tem certeza de que deseja excluir os $count itens selecionados? Essa ação não pode ser desfeita." else "Are you sure you want to delete the $count selected items? This action cannot be undone."
    fun cancel(lang: String) = if (isPt(lang)) "Cancelar" else "Cancel"
    fun delete(lang: String) = if (isPt(lang)) "Excluir" else "Delete"
    fun share(lang: String) = if (isPt(lang)) "Compartilhar" else "Share"
    fun playVideo(lang: String) = if (isPt(lang)) "Reproduzir Vídeo" else "Play Video"

    // About
    fun back(lang: String) = if (isPt(lang)) "Voltar" else "Back"
    fun totalDuration(lang: String) = if (isPt(lang)) "Duração Total" else "Total Duration"
    fun recordedTime(lang: String) = if (isPt(lang)) "Tempo gravado" else "Recorded time"
    fun usedSpace(lang: String) = if (isPt(lang)) "Espaço Usado" else "Used Space"
    fun devBy(lang: String) = if (isPt(lang)) "DESENVOLVIDO POR" else "DEVELOPED BY"
    fun otherProjects(lang: String) = if (isPt(lang)) "ACOMPANHE MEUS OUTROS PROJETOS" else "FOLLOW MY OTHER PROJECTS"

    // Notification Panel
    fun notifAppTitle(lang: String, isRecording: Boolean) = if (isRecording) {
        if (isPt(lang)) "Deluxe Recorder • Gravando" else "Deluxe Recorder • Recording"
    } else {
        if (isPt(lang)) "Deluxe Recorder • Ativo" else "Deluxe Recorder • Active"
    }
    fun notifStatus(lang: String, isRecording: Boolean) = if (isRecording) {
        if (isPt(lang)) "Ao Vivo" else "Live"
    } else {
        if (isPt(lang)) "Pronto" else "Ready"
    }
    fun notifBtnRecord(lang: String, isRecording: Boolean) = if (isRecording) {
        if (isPt(lang)) "Parar" else "Stop"
    } else {
        if (isPt(lang)) "Gravar" else "Record"
    }
    fun notifBtnScreenshot(lang: String) = if (isPt(lang)) "Captura" else "Screenshot"
    fun notifBtnTools(lang: String) = if (isPt(lang)) "Ferramentas" else "Tools"
    fun notifBtnBubble(lang: String) = if (isPt(lang)) "Bolha" else "Bubble"
    fun notifBtnPause(lang: String) = if (isPt(lang)) "Pausar" else "Pause"
    fun notifBtnResume(lang: String) = if (isPt(lang)) "Retomar" else "Resume"
    fun notifBtnHome(lang: String) = if (isPt(lang)) "Início" else "Home"
    fun notifBtnExit(lang: String) = if (isPt(lang)) "Sair" else "Exit"
}
