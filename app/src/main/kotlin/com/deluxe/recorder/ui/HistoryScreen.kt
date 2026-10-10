package com.deluxe.recorder.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deluxe.recorder.I18n
import com.deluxe.recorder.R
import com.deluxe.recorder.util.FileUtils
import com.deluxe.recorder.util.RecordingEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ScreenBg = Color(0xFF000000)
private val CardBg = Color(0xFF0C0D11)
private val CardBorder = Color(0xFF1B1E27)
private val Crimson = Color(0xFFFF2E54)
private val CrimsonDark = Color(0xFF270B11)
private val CrimsonBorder = Color(0x66FF2E54)
private val TextWhite = Color(0xFFFFFFFF)
private val TextDim = Color(0xFF9CA3AF)
private val TextMuted = Color(0xFF6B7280)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    language: String = "pt",
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = pagerState.currentPage

    var recordings by remember { mutableStateOf(FileUtils.getRecordings(context)) }
    var screenshots by remember { mutableStateOf(FileUtils.getScreenshots(context)) }

    var showDeleteDialog by remember { mutableStateOf<RecordingEntry?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var playingEntry by remember { mutableStateOf<RecordingEntry?>(null) }

    LaunchedEffect(pagerState.currentPage) {
        selectedIds = emptySet()
    }

    val activeList = if (selectedTab == 0) recordings else screenshots
    val selectionActive = selectedIds.isNotEmpty()
    val allSelected = activeList.isNotEmpty() && selectedIds.size == activeList.size

    fun reload() {
        recordings = FileUtils.getRecordings(context)
        screenshots = FileUtils.getScreenshots(context)
    }

    fun handleBack() {
        if (selectionActive) {
            selectedIds = emptySet()
        } else if (pagerState.currentPage == 1) {
            coroutineScope.launch {
                pagerState.animateScrollToPage(0)
            }
            selectedIds = emptySet()
        } else {
            onBack()
        }
    }

    BackHandler {
        handleBack()
    }

    Scaffold(
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (selectionActive) I18n.selectedCount(language, selectedIds.size)
                            else I18n.galleryTitle(language),
                            color = TextWhite,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 17.sp
                        )
                        if (!selectionActive) {
                            Text(
                                text = I18n.gallerySubtitle(language),
                                color = Crimson,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (selectionActive) {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = I18n.cancel(language),
                                tint = TextWhite,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        IconButton(onClick = { handleBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = I18n.back(language),
                                tint = TextWhite,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (activeList.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectedIds = if (allSelected) emptySet()
                                else activeList.map { it.id }.toSet()
                            }
                        ) {
                            Text(
                                text = if (allSelected) I18n.deselectAll(language) else I18n.selectAll(language),
                                color = Crimson,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (selectionActive) {
                        IconButton(onClick = { showBulkDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = I18n.deleteSelected(language),
                                tint = Crimson
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ScreenBg
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Segmented Tabs Fiel ao HTML
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF08090D),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Tab 0: Vídeos
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(0)
                                }
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedTab == 0) Crimson else Color.Transparent
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = I18n.tabRecordedVideos(language, recordings.size),
                                color = if (selectedTab == 0) TextWhite else TextDim,
                                fontSize = 11.5.sp,
                                fontWeight = if (selectedTab == 0) FontWeight.ExtraBold else FontWeight.SemiBold
                            )
                        }
                    }

                    // Tab 1: Capturas
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(1)
                                }
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedTab == 1) Crimson else Color.Transparent
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = I18n.tabScreenshots(language, screenshots.size),
                                color = if (selectedTab == 1) TextWhite else TextDim,
                                fontSize = 11.5.sp,
                                fontWeight = if (selectedTab == 1) FontWeight.ExtraBold else FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Pager com Suporte a Gestos de Deslizar (Swipe) para a Esquerda/Direita
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val isVideoPage = page == 0
                val pageList = if (isVideoPage) recordings else screenshots

                if (pageList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // Ícone em Círculo Estilizado com Borda AMOLED
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF11131A))
                                    .border(BorderStroke(1.5.dp, CardBorder), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (isVideoPage) R.drawable.ic_videocam else R.drawable.ic_screenshot
                                    ),
                                    contentDescription = null,
                                    tint = Crimson,
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            Spacer(Modifier.height(18.dp))

                            Text(
                                text = if (isVideoPage) I18n.noVideosTitle(language) else I18n.noScreenshotsTitle(language),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextWhite,
                                fontSize = 16.sp
                            )

                            Spacer(Modifier.height(6.dp))

                            Text(
                                text = if (isVideoPage) I18n.noVideosDesc(language) else I18n.noScreenshotsDesc(language),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextDim,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )

                            Spacer(Modifier.height(20.dp))

                            // Botão de Ação Rápida
                            Surface(
                                modifier = Modifier.clickable(onClick = onBack),
                                shape = RoundedCornerShape(20.dp),
                                color = Crimson,
                                shadowElevation = 4.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (isVideoPage) R.drawable.ic_record else R.drawable.ic_screenshot
                                        ),
                                        contentDescription = null,
                                        tint = TextWhite,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (isVideoPage) I18n.startRecording(language) else I18n.takeScreenshot(language),
                                        color = TextWhite,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        items(pageList, key = { it.id }) { entry ->
                            val isSelected = entry.id in selectedIds
                            HistoryItemCard(
                                entry = entry,
                                isVideo = isVideoPage,
                                isSelected = isSelected,
                                selectionActive = selectionActive,
                                onClick = {
                                    if (selectionActive) {
                                        selectedIds = if (isSelected) selectedIds - entry.id else selectedIds + entry.id
                                    } else {
                                        if (isVideoPage) {
                                            playingEntry = entry
                                        } else {
                                            // Abrir foto no visualizador do sistema
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(entry.uri, "image/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            try { context.startActivity(intent) } catch (_: Exception) {}
                                        }
                                    }
                                },
                                onToggle = {
                                    selectedIds = if (isSelected) selectedIds - entry.id else selectedIds + entry.id
                                },
                                onShare = {
                                    FileUtils.shareEntry(context, entry, isVideo = isVideoPage)
                                },
                                onDelete = {
                                    showDeleteDialog = entry
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Diálogos de Exclusão
    if (showBulkDeleteDialog && selectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            containerColor = CardBg,
            title = {
                Text(
                    text = I18n.deleteDialogTitleBulk(language),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = I18n.deleteDialogDescBulk(language, selectedIds.size),
                    color = TextDim
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    activeList.filter { it.id in selectedIds }.forEach { entry ->
                        FileUtils.deleteEntry(context, entry)
                    }
                    selectedIds = emptySet()
                    showBulkDeleteDialog = false
                    reload()
                }) {
                    Text(I18n.delete(language), color = Crimson, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) {
                    Text(I18n.cancel(language), color = TextDim)
                }
            }
        )
    }

    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = CardBg,
            title = {
                Text(
                    text = I18n.deleteDialogTitleSingle(language),
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = I18n.deleteDialogDescSingle(language),
                    color = TextDim
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog?.let { entry ->
                        FileUtils.deleteEntry(context, entry)
                        selectedIds = selectedIds - entry.id
                        reload()
                    }
                    showDeleteDialog = null
                }) {
                    Text(I18n.delete(language), color = Crimson, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(I18n.cancel(language), color = TextDim)
                }
            }
        )
    }

    playingEntry?.let { entry ->
        VideoPlayerSheet(
            uri = entry.uri,
            videoName = entry.name,
            onClose = { playingEntry = null }
        )
    }
}

@Composable
private fun HistoryItemCard(
    entry: RecordingEntry,
    isVideo: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) CrimsonDark else CardBg
        ),
        border = BorderStroke(1.dp, if (isSelected) Crimson else CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionActive) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Crimson,
                        checkmarkColor = Color.White,
                        uncheckedColor = TextMuted
                    )
                )
                Spacer(Modifier.width(8.dp))
            }

            // Thumbnail com cantos arredondados
            if (isVideo) {
                VideoThumbnail(entry = entry)
            } else {
                ImageThumbnail(entry = entry)
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = FileUtils.getFileSizeString(entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = Crimson,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Compartilhar",
                        tint = TextDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Excluir",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoThumbnail(entry: RecordingEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val targetPx = with(LocalDensity.current) { 96.dp.toPx() }.toInt()
    var bitmap by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(entry.uri) {
        bitmap = withContext(Dispatchers.IO) {
            FileUtils.getVideoThumbnail(context, entry.uri, targetPx)
        }
    }

    Box(
        modifier = modifier
            .size(width = 68.dp, height = 52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF141620))
            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        val thumb = bitmap
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Play overlay icon
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Text("▶", color = TextWhite, fontSize = 9.sp)
            }
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_videocam),
                contentDescription = null,
                tint = Crimson,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ImageThumbnail(entry: RecordingEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(entry.uri) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(entry.uri, Size(120, 90), null)
                } else {
                    BitmapFactory.decodeStream(
                        context.contentResolver.openInputStream(entry.uri)
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(
        modifier = modifier
            .size(width = 68.dp, height = 52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF141620))
            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        val thumb = bitmap
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_screenshot),
                contentDescription = null,
                tint = Crimson,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
