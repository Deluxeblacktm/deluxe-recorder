package com.deluxe.recorder.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deluxe.recorder.I18n
import com.deluxe.recorder.R

private val ScreenBg = Color(0xFF000000)
private val CardBg = Color(0xFF0D0D0E)
private val CardBorder = Color(0xFF1E1E22)
private val CrimsonAccent = Color(0xFFFF2E54)
private val CrimsonRing = Color(0xFFFF2E54)
private val SpaceRing = Color(0xFF38BDF8)
private val TextLight = Color(0xFFFFFFFF)
private val TextDim = Color(0xFFA0A0A5)

@Composable
fun AboutScreen(
    totalRecordedMs: Long,
    maxDurationMs: Long,
    usedStorageGB: Int,
    totalStorageGB: Int,
    language: String = "pt",
    onBack: () -> Unit
) {
    val context = LocalContext.current

    BackHandler {
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .systemBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = I18n.back(language),
                    tint = TextLight
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF200B10), Color(0xFF0D0D0E))
                        )
                    )
                    .border(
                        BorderStroke(
                            2.5.dp,
                            Brush.linearGradient(
                                colors = listOf(CrimsonAccent, Color(0xFFFF5C7A), Color(0xFF2A0E14))
                            )
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo_camera_header),
                    contentDescription = "Logo Deluxe Recorder",
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "Deluxe Recorder",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextLight,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            StatusCard(
                totalRecordedMs = totalRecordedMs,
                maxDurationMs = maxDurationMs,
                usedStorageGB = usedStorageGB,
                totalStorageGB = totalStorageGB,
                language = language
            )

            Spacer(Modifier.height(28.dp))

            SectionTitle(I18n.devBy(language))

            Spacer(Modifier.height(12.dp))

            SocialCard(
                avatarRes = R.drawable.avatar_deluxe_black,
                icon = R.drawable.ic_tiktok,
                title = "TikTok",
                subtitle = "@deluxeblacktm",
                url = "https://www.tiktok.com/@deluxeblacktm",
                context = context
            )

            Spacer(Modifier.height(28.dp))

            SectionTitle(I18n.otherProjects(language))

            Spacer(Modifier.height(12.dp))

            SocialCard(
                icon = R.drawable.ic_tiktok,
                iconTint = Color.Unspecified,
                title = "TikTok",
                subtitle = "@crosssoficial",
                url = "https://www.tiktok.com/@crosssoficial?_r=1&_t=ZS-9AOk5XgfNY4",
                context = context
            )

            Spacer(Modifier.height(12.dp))

            SocialCard(
                icon = R.drawable.ic_telegram,
                iconTint = Color.Unspecified,
                title = "Telegram",
                subtitle = "@Projectcrosssoficial",
                url = "https://t.me/Projectcrosssoficial",
                context = context
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = TextLight,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun StatusCard(
    totalRecordedMs: Long,
    maxDurationMs: Long,
    usedStorageGB: Int,
    totalStorageGB: Int,
    language: String
) {
    val durationProgress = if (maxDurationMs > 0) {
        totalRecordedMs.toFloat() / maxDurationMs
    } else {
        0f
    }
    val storagePercent = if (totalStorageGB > 0) {
        (usedStorageGB.toFloat() * 100f / totalStorageGB).toInt()
    } else {
        0
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RingIndicator(
                progress = durationProgress,
                ringColor = CrimsonRing,
                centerText = "${formatDuration(totalRecordedMs)} / ${formatDuration(maxDurationMs)}",
                legend = I18n.totalDuration(language),
                subtext = I18n.recordedTime(language)
            )
            RingIndicator(
                progress = if (totalStorageGB > 0) usedStorageGB.toFloat() / totalStorageGB else 0f,
                ringColor = SpaceRing,
                centerText = "$storagePercent%",
                legend = I18n.usedSpace(language),
                subtext = "$usedStorageGB GB / $totalStorageGB GB"
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

@Composable
private fun RingIndicator(
    progress: Float,
    ringColor: Color,
    centerText: String,
    legend: String,
    subtext: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(116.dp)
    ) {
        Box(
            modifier = Modifier.size(92.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize(),
                color = ringColor,
                trackColor = if (ringColor == CrimsonRing) Color(0xFF260D13) else Color(0xFF0D2230),
                strokeWidth = 8.dp
            )
            Text(
                centerText,
                color = TextLight,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            legend,
            color = TextLight,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(2.dp))
        Text(
            subtext,
            color = TextDim,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SocialCard(
    avatarRes: Int? = null,
    icon: Int,
    iconTint: Color = CrimsonAccent,
    title: String,
    subtitle: String,
    url: String,
    context: Context
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(if (avatarRes != null) CircleShape else RoundedCornerShape(10.dp))
                    .background(CardBorder),
                contentAlignment = Alignment.Center
            ) {
                if (avatarRes != null) {
                    Image(
                        painter = painterResource(id = avatarRes),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                } else {
                    Icon(
                        painter = painterResource(id = icon),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextLight
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim
                )
            }
            Text(
                "Abrir",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = CrimsonAccent
            )
        }
    }
}
