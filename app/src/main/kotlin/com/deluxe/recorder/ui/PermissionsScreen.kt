package com.deluxe.recorder.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deluxe.recorder.R

private val ScreenBg = Color(0xFF000000)
private val CardBg = Color(0xFF0C0D11)
private val CardBorder = Color(0xFF1B1E27)
private val CardGrantedBg = Color(0xFF090E0C)
private val CardGrantedBorder = Color(0x5900E676)
private val Crimson = Color(0xFFFF2E54)
private val StatusGreen = Color(0xFF00E676)
private val StatusGreenDark = Color(0xFF0A2617)
private val StatusGreenBorder = Color(0x6600E676)
private val PendingBadgeBg = Color(0xFF1C1518)
private val PendingBadgeBorder = Color(0x4DFF2E54)
private val PendingBadgeText = Color(0xFFFF8A9E)
private val TextWhite = Color(0xFFFFFFFF)
private val TextDim = Color(0xFF9CA3AF)

@Composable
fun PermissionsScreen(
    hasNotifPerm: Boolean,
    hasAudioPerm: Boolean,
    hasOverlayPerm: Boolean,
    onRequestNotif: () -> Unit,
    onRequestAudio: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestPending: () -> Unit,
    onAllGrantedContinue: () -> Unit
) {
    val grantedCount = (if (hasNotifPerm) 1 else 0) +
            (if (hasAudioPerm) 1 else 0) +
            (if (hasOverlayPerm) 1 else 0)
    val allGranted = grantedCount == 3

    val animatedProgress by animateFloatAsState(
        targetValue = grantedCount / 3f,
        animationSpec = tween(400),
        label = "permProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))

        // 1. Header Badge com Escudo
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF11141E))
                .border(BorderStroke(1.5.dp, Crimson), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_shield_check),
                contentDescription = null,
                tint = Crimson,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = "Deluxe Recorder",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextWhite,
            letterSpacing = (-0.3).sp
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "Para gravar a tela com máximo desempenho e áudio cristalino, libere as 3 autorizações abaixo.",
            style = MaterialTheme.typography.bodySmall,
            color = TextDim,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(Modifier.height(20.dp))

        // 2. Card de Progresso do Sistema
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Status do Sistema",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite
                    )
                    Text(
                        text = "$grantedCount de 3 Concedidas",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (allGranted) StatusGreen else Crimson
                    )
                }

                Spacer(Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF171A24))
                ) {
                    if (animatedProgress > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedProgress)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(Crimson, StatusGreen)
                                    )
                                )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 3. Três Cards de Permissão
        PermissionTile(
            title = "Notificações",
            description = "Permite enviar alertas e a barra fixa para pausar/salvar sem abrir o aplicativo.",
            iconRes = R.drawable.ic_notification,
            isGranted = hasNotifPerm,
            onClick = { if (!hasNotifPerm) onRequestNotif() }
        )

        Spacer(Modifier.height(10.dp))

        PermissionTile(
            title = "Áudio / Voz",
            description = "Captura som interno de jogos/apps e microfone em alta fidelidade.",
            iconRes = R.drawable.ic_mic_permission,
            isGranted = hasAudioPerm,
            onClick = { if (!hasAudioPerm) onRequestAudio() }
        )

        Spacer(Modifier.height(10.dp))

        PermissionTile(
            title = "Sobreposição de Tela",
            description = "Exibe os botões flutuantes sobre outros apps durante a gravação.",
            iconRes = R.drawable.ic_overlay_permission,
            isGranted = hasOverlayPerm,
            onClick = { if (!hasOverlayPerm) onRequestOverlay() }
        )

        Spacer(Modifier.height(16.dp))

        // 4. Nota de Privacidade
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF08090D)),
            border = BorderStroke(1.dp, Color(0xFF191B22))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = TextDim,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(Modifier.width(12.dp))

                Text(
                    text = "100% Local & Seguro: Seus vídeos ficam gravados apenas no seu aparelho. O Deluxe Recorder não envia dados para a internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 5. Botão de Ação Dinâmico
        val buttonText = when {
            allGranted -> "Tudo Pronto • Ir para o Gravador ➔"
            !hasNotifPerm && hasAudioPerm && hasOverlayPerm -> "Conceder Notificações Pendente"
            hasNotifPerm && !hasAudioPerm && hasOverlayPerm -> "Conceder Áudio Pendente"
            hasNotifPerm && hasAudioPerm && !hasOverlayPerm -> "Conceder Sobreposição Pendente"
            else -> "Conceder Permissões Pendentes"
        }

        Button(
            onClick = {
                if (allGranted) {
                    onAllGrantedContinue()
                } else {
                    onRequestPending()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (allGranted) StatusGreen else Crimson,
                contentColor = if (allGranted) Color.Black else Color.White
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_shield_check),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = buttonText,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionTile(
    title: String,
    description: String,
    iconRes: Int,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) CardGrantedBg else CardBg
        ),
        border = BorderStroke(
            1.dp,
            if (isGranted) CardGrantedBorder else CardBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Caixa do Ícone
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isGranted) StatusGreenDark else Color(0xFF131620))
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isGranted) StatusGreenBorder else CardBorder
                        ),
                        RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (isGranted) StatusGreen else Crimson
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )

                    // Badge de Status
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isGranted) StatusGreenDark else PendingBadgeBg)
                            .border(
                                BorderStroke(
                                    1.dp,
                                    if (isGranted) StatusGreenBorder else PendingBadgeBorder
                                ),
                                CircleShape
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isGranted) "✔ Concedida" else "Pendente",
                            color = if (isGranted) StatusGreen else PendingBadgeText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(3.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
