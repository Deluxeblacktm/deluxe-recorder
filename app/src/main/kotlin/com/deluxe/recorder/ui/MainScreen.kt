package com.deluxe.recorder.ui

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deluxe.recorder.I18n
import com.deluxe.recorder.R
import com.deluxe.recorder.RecordingState
import kotlinx.coroutines.launch

private val Crimson = Color(0xFFFF2E54)
private val CrimsonDark = Color(0xFF240B10)
private val CrimsonBorder = Color(0xFF4A101C)
private val Emerald = Color(0xFF22C55E)
private val ScreenBg = Color(0xFF000000)
private val CardBg = Color(0xFF0D0D0E)
private val CardBorder = Color(0xFF1E1E22)
private val TextWhite = Color(0xFFFFFFFF)
private val TextDim = Color(0xFFA0A0A5)
private val ChipInactiveBg = Color(0xFF141417)

private val resolutions = listOf(
    Pair("360p", "360p"),
    Pair("480p", "480p"),
    Pair("720p", "720p HD"),
    Pair("1080p", "1080p FHD")
)

private val bitrateOptions = listOf(
    Pair(2, "2M"),
    Pair(4, "4M"),
    Pair(6, "6M"),
    Pair(8, "8M"),
    Pair(10, "10M"),
    Pair(12, "12M Ultra")
)

private data class AudioOption(
    val id: String,
    val iconEmoji: String,
    val title: String,
    val subtitle: String,
    val iconRes: Int
)

@Composable
fun MainScreen(
    language: String = "pt",
    onLanguageChange: (String) -> Unit = {},
    isRecording: Boolean,
    latestFilePath: String?,
    resolution: String,
    bitrateMbps: Int,
    fps: Int,
    orientation: String,
    audioSource: String,
    countdownSeconds: Int,
    onResolutionChange: (String) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onFpsChange: (Int) -> Unit,
    onOrientationChange: (String) -> Unit,
    onAudioSourceChange: (String) -> Unit,
    onCountdownChange: (Int) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onShowHistory: () -> Unit,
    onShowAbout: () -> Unit,
    onScreenshot: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    var hideOverlayEnabled by remember { mutableStateOf(RecordingState.hideOverlay) }
    var useHevcEnabled by remember { mutableStateOf(RecordingState.useHevc) }

    val storageStats = remember {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeGB = (freeBytes / (1024L * 1024L * 1024L)).toInt()
            val totalGB = (totalBytes / (1024L * 1024L * 1024L)).toInt()
            val pct = if (totalGB > 0) (((totalGB - freeGB).toFloat() / totalGB) * 100).toInt() else 0
            Triple(freeGB, totalGB, pct) to freeBytes
        } catch (_: Exception) {
            Triple(0, 0, 0) to 0L
        }
    }
    val (freeGB, totalGB, storagePercent) = storageStats.first
    val availableBytes = storageStats.second

    val estimatedTimeText = remember(availableBytes, resolution, fps, bitrateMbps, language) {
        val durationMs = RecordingState.calculateEstimatedDurationMs(availableBytes, resolution, fps, bitrateMbps)
        RecordingState.formatEstimatedDuration(durationMs)
    }

    val currentAudioLabel = I18n.audioLabel(language, audioSource)

    val currentFpsOptions = remember(language) {
        listOf(
            Pair(30, I18n.fpsLabel(language, 30)),
            Pair(60, I18n.fpsLabel(language, 60))
        )
    }

    val audioChoices = remember(language) {
        listOf(
            AudioOption("mute", "🔇", I18n.audioLabel(language, "mute"), I18n.audioDesc(language, "mute"), R.drawable.ic_videocam),
            AudioOption("internal", "🔊", I18n.audioLabel(language, "internal"), I18n.audioDesc(language, "internal"), R.drawable.ic_tools),
            AudioOption("mic", "🎙️", I18n.audioLabel(language, "mic"), I18n.audioDesc(language, "mic"), R.drawable.ic_mic),
            AudioOption("both", "🎛️", I18n.audioLabel(language, "both"), I18n.audioDesc(language, "both"), R.drawable.ic_record)
        )
    }

    Scaffold(
        containerColor = ScreenBg,
        bottomBar = {
            BottomNavigationBar(
                language = language,
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    selectedTab = tab
                    if (tab == 1) onShowHistory()
                    else if (tab == 2) onShowAbout()
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // TOP APP BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Nova Logo do Aplicativo no Topo
                Image(
                    painter = painterResource(id = R.drawable.logo_camera_header),
                    contentDescription = "Logo Deluxe Recorder",
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                )

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        "Deluxe Recorder",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextWhite,
                        letterSpacing = (-0.3).sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isRecording) Crimson else Emerald)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (isRecording) I18n.recordingNow(language) else I18n.systemReady(language),
                            color = if (isRecording) Crimson else Emerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Seletor de Idioma: 🇧🇷 PT / 🇺🇸 EN
                LanguageSelectorPill(
                    currentLanguage = language,
                    onLanguageChange = onLanguageChange
                )
            }

                Spacer(Modifier.height(14.dp))

                // CÁPSULA DE ARMAZENAMENTO
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(38.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { (storagePercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxSize(),
                                color = Crimson,
                                trackColor = Color(0xFF1E1E24),
                                strokeWidth = 3.5.dp
                            )
                            Icon(
                                painter = painterResource(id = R.drawable.ic_record),
                                contentDescription = null,
                                tint = Crimson,
                                modifier = Modifier.size(12.dp)
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column {
                            Text(
                                text = I18n.freeGB(language, freeGB),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = I18n.spaceForRecording(language, estimatedTimeText),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextDim,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // HERO RECORD CARD (Card Exclusivo do Botão de Gravação com Pills)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, if (isRecording) CrimsonBorder else CardBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 22.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        HeroBigRecordButton(
                            language = language,
                            isRecording = isRecording,
                            onClick = {
                                if (isRecording) onStopRecording()
                                else onStartRecording()
                            }
                        )

                        Spacer(Modifier.height(14.dp))

                        Text(
                            text = I18n.recordTitle(language, isRecording),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isRecording) Crimson else TextWhite
                        )

                        Spacer(Modifier.height(12.dp))

                        // Linha de Tags de Parâmetros Rápidos (Fiel ao HTML)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ParamTagPill(label = resolution)
                            ParamTagPill(label = "${fps} FPS")
                            ParamTagPill(label = "${bitrateMbps} Mbps")
                            ParamTagPill(label = currentAudioLabel)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // TÍTULO DA SEÇÃO
                Text(
                    text = I18n.videoParams(language),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextDim,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                )

                // CARD 1: RESOLUÇÃO
                SettingCardWrapper(title = I18n.resolution(language), currentVal = resolution) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF08090D),
                        border = BorderStroke(1.dp, Color(0xFF1A1E28))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            resolutions.forEach { (key, label) ->
                                ModernSegmentChip(
                                    label = label,
                                    selected = resolution == key,
                                    onClick = { onResolutionChange(key) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // CARD 2: TAXA DE QUADROS (FPS)
                SettingCardWrapper(title = I18n.fps(language), currentVal = "$fps FPS") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF08090D),
                        border = BorderStroke(1.dp, Color(0xFF1A1E28))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            currentFpsOptions.forEach { (f, label) ->
                                ModernSegmentChip(
                                    label = label,
                                    selected = fps == f,
                                    onClick = { onFpsChange(f) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // CARD 3: BITRATE (Centralizado e distribuído por igual)
                SettingCardWrapper(title = I18n.bitrate(language), currentVal = "$bitrateMbps Mbps") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF08090D),
                        border = BorderStroke(1.dp, Color(0xFF1A1E28))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            bitrateOptions.forEach { (br, label) ->
                                ModernSegmentChip(
                                    label = label,
                                    selected = bitrateMbps == br,
                                    onClick = { onBitrateChange(br) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // CARD 4: FONTE DE ÁUDIO
                SettingCardWrapper(title = I18n.audioSource(language), currentVal = currentAudioLabel) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ModernAudioCard(
                                option = audioChoices[0],
                                isSelected = audioSource == audioChoices[0].id,
                                onClick = { onAudioSourceChange(audioChoices[0].id) },
                                modifier = Modifier.weight(1f)
                            )
                            ModernAudioCard(
                                option = audioChoices[1],
                                isSelected = audioSource == audioChoices[1].id,
                                onClick = { onAudioSourceChange(audioChoices[1].id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ModernAudioCard(
                                option = audioChoices[2],
                                isSelected = audioSource == audioChoices[2].id,
                                onClick = { onAudioSourceChange(audioChoices[2].id) },
                                modifier = Modifier.weight(1f)
                            )
                            ModernAudioCard(
                                option = audioChoices[3],
                                isSelected = audioSource == audioChoices[3].id,
                                onClick = { onAudioSourceChange(audioChoices[3].id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // CARD 5: TOGGLES RÁPIDOS (Overlay e Codec HEVC)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Switch Overlay
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    hideOverlayEnabled = !hideOverlayEnabled
                                    RecordingState.setHideOverlay(hideOverlayEnabled, context)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    I18n.hideOverlay(language),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                Text(
                                    I18n.hideOverlayDesc(language),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextDim,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = hideOverlayEnabled,
                                onCheckedChange = {
                                    hideOverlayEnabled = it
                                    RecordingState.setHideOverlay(it, context)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TextWhite,
                                    checkedTrackColor = Crimson,
                                    uncheckedThumbColor = TextDim,
                                    uncheckedTrackColor = CardBorder
                                )
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = CardBorder
                        )

                        // Switch HEVC Codec
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    useHevcEnabled = !useHevcEnabled
                                    RecordingState.setHevc(useHevcEnabled, context)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    I18n.hevc(language),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                Text(
                                    I18n.hevcDesc(language),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextDim,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = useHevcEnabled,
                                onCheckedChange = {
                                    useHevcEnabled = it
                                    RecordingState.setHevc(it, context)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TextWhite,
                                    checkedTrackColor = Crimson,
                                    uncheckedThumbColor = TextDim,
                                    uncheckedTrackColor = CardBorder
                                )
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

@Composable
private fun SettingCardWrapper(
    title: String,
    currentVal: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp
                )
                Text(
                    text = currentVal,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Crimson,
                    fontSize = 11.sp
                )
            }
            content()
        }
    }
}

@Composable
private fun ParamTagPill(label: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF141720),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Text(
            text = label,
            color = Color(0xFFE2E4E9),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ModernSegmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) Crimson else Color.Transparent,
        label = "chipBg"
    )
    val textColor = if (selected) TextWhite else TextDim

    Surface(
        modifier = modifier
            .height(32.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = bgColor
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = if (label.length > 7) 9.5.sp else 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun ModernAudioCard(
    option: AudioOption,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) Color(0xFF270B11) else Color(0xFF11131A)
    val border = if (isSelected) BorderStroke(1.dp, Crimson) else BorderStroke(1.dp, CardBorder)

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = border
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF000000)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option.iconEmoji,
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = option.title,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                color = if (isSelected) TextWhite else Color(0xFFD1D5DB),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HeroBigRecordButton(
    language: String,
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val recordingBrush = remember { Brush.radialGradient(listOf(Crimson, Color(0xFFD91A3E))) }
    val idleBrush = remember { Brush.radialGradient(listOf(Color(0xFFFF3B5F), Crimson)) }
    val heroBorder = remember { BorderStroke(3.5.dp, Color.White.copy(alpha = 0.2f)) }

    val pulseScaleState = if (isRecording) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.07f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
    } else null

    Box(
        modifier = Modifier
            .size(104.dp)
            .graphicsLayer {
                val s = pulseScaleState?.value ?: 1f
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .background(if (isRecording) recordingBrush else idleBrush)
            .border(heroBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isRecording) {
                // Quadrado de Parar
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(TextWhite)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = I18n.recordBtn(language, true),
                    color = TextWhite,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp
                )
            } else {
                // Círculo de Gravar
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(TextWhite)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = I18n.recordBtn(language, false),
                    color = TextWhite,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}

@Composable
private fun BottomNavigationBar(
    language: String,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ScreenBg,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomNavItem(
                    icon = R.drawable.ic_record,
                    label = I18n.tabHome(language),
                    selected = selectedTab == 0,
                    onClick = { onTabSelected(0) }
                )
                BottomNavItem(
                    icon = R.drawable.ic_videocam,
                    label = I18n.tabVideos(language),
                    selected = selectedTab == 1,
                    onClick = { onTabSelected(1) }
                )
                BottomNavItem(
                    icon = R.drawable.ic_tools,
                    label = I18n.tabAbout(language),
                    selected = selectedTab == 2,
                    onClick = { onTabSelected(2) }
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (selected) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF181A24),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)) {
                    Icon(
                        painter = painterResource(id = icon),
                        contentDescription = label,
                        tint = Crimson,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        } else {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = label,
                    tint = Color(0xFF6B7280),
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 9.5.sp,
            color = if (selected) TextWhite else Color(0xFF6B7280),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun LanguageSelectorPill(
    currentLanguage: String,
    onLanguageChange: (String) -> Unit
) {
    val isPt = currentLanguage == "pt"
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF141418))
            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(20.dp))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // PT button (Brasil)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(if (isPt) Crimson else Color.Transparent)
                .clickable { onLanguageChange("pt") }
                .padding(horizontal = 7.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🇧🇷 PT",
                fontSize = 10.5.sp,
                fontWeight = if (isPt) FontWeight.Bold else FontWeight.Medium,
                color = if (isPt) TextWhite else TextDim
            )
        }

        // EN button (Estados Unidos)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(if (!isPt) Crimson else Color.Transparent)
                .clickable { onLanguageChange("en") }
                .padding(horizontal = 7.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🇺🇸 EN",
                fontSize = 10.5.sp,
                fontWeight = if (!isPt) FontWeight.Bold else FontWeight.Medium,
                color = if (!isPt) TextWhite else TextDim
            )
        }
    }
}
