package com.deluxe.recorder.ui

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private val Crimson = Color(0xFFFF2E54)
private val OverlayBg = Color(0x66000000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerSheet(
    uri: Uri,
    videoName: String?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val mediaPlayer = remember { MediaPlayer() }
    val prepared = remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var videoRatio by remember { mutableStateOf(16f / 9f) }
    var dragPosition by remember { mutableFloatStateOf(-1f) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val (width, height, rotation) = withContext(Dispatchers.IO) {
            try {
                MediaMetadataRetriever().use { mmr ->
                    mmr.setDataSource(context, uri)
                    val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    Triple(w, h, rot)
                }
            } catch (_: Exception) {
                Triple(0, 0, 0)
            }
        }
        if (width > 0 && height > 0) {
            val swapped = rotation == 90 || rotation == 270
            videoRatio = if (swapped) height.toFloat() / width else width.toFloat() / height
        }
    }

    DisposableEffect(mediaPlayer) {
        mediaPlayer.setOnCompletionListener {
            positionMs = mediaPlayer.duration.toLong().coerceAtLeast(0L)
            isPlaying = false
            finished = true
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            isPlaying = false
            true
        }
        onDispose {
            try {
                mediaPlayer.reset()
                mediaPlayer.release()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(prepared.value) {
        while (prepared.value) {
            if (!finished) {
                val duration = mediaPlayer.duration.toLong().coerceAtLeast(0L)
                val pos = mediaPlayer.currentPosition.toLong()
                if (isPlaying && duration > 0 && pos >= duration - 50L) {
                    positionMs = duration
                    isPlaying = false
                    finished = true
                } else {
                    positionMs = pos
                }
            }
            delay(200)
        }
    }

    LaunchedEffect(Unit) {
        delay(3000)
        controlsVisible = false
    }

    fun togglePlayPause() {
        if (!prepared.value) return
        if (isPlaying) {
            mediaPlayer.pause()
            positionMs = mediaPlayer.currentPosition.toLong()
            isPlaying = false
        } else {
            if (finished) {
                mediaPlayer.seekTo(0)
                finished = false
            }
            mediaPlayer.start()
            isPlaying = true
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    if (!prepared.value) {
                                        try {
                                            mediaPlayer.setDataSource(context, uri)
                                            mediaPlayer.setSurface(holder.surface)
                                            mediaPlayer.prepare()
                                            durationMs = mediaPlayer.duration
                                            prepared.value = true
                                            mediaPlayer.start()
                                            isPlaying = true
                                        } catch (_: Exception) {}
                                    }
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {}

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    holder.removeCallback(this)
                                }
                            })
                        }
                    },
                    modifier = Modifier
                        .aspectRatio(videoRatio)
                        .fillMaxSize()
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { controlsVisible = !controlsVisible }
            )

            if (controlsVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(OverlayBg)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Fechar",
                            tint = Color.White
                        )
                    }
                    Text(
                        videoName ?: "Reprodução",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                IconButton(
                    onClick = { togglePlayPause() },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .background(Color(0x99000000), CircleShape)
                ) {
                    if (isPlaying) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .width(7.dp)
                                    .height(30.dp)
                                    .background(Color.White, RoundedCornerShape(2.dp))
                            )
                            Box(
                                Modifier
                                    .width(7.dp)
                                    .height(30.dp)
                                    .background(Color.White, RoundedCornerShape(2.dp))
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Reproduzir",
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .background(OverlayBg)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { }
                ) {
                    val sliderMax = durationMs.coerceAtLeast(1).toFloat()
                    val sliderValue = (if (dragPosition >= 0f) dragPosition
                    else positionMs.toFloat()).coerceIn(0f, sliderMax)
                    Slider(
                        value = sliderValue,
                        onValueChange = { dragPosition = it },
                        onValueChangeFinished = {
                            if (dragPosition >= 0f) {
                                mediaPlayer.seekTo(dragPosition.toInt())
                                finished = false
                                dragPosition = -1f
                            }
                        },
                        valueRange = 0f..sliderMax,
                        colors = SliderDefaults.colors(
                            thumbColor = Crimson,
                            activeTrackColor = Crimson,
                            inactiveTrackColor = Color.White.copy(alpha = 0.4f)
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatTime(if (dragPosition >= 0f) dragPosition.toLong() else positionMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            formatTime(durationMs.toLong()),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
