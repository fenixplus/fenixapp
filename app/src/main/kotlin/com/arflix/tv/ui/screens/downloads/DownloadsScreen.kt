package com.arflix.tv.ui.screens.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.arflix.tv.R
import com.arflix.tv.data.download.DownloadItem
import com.arflix.tv.data.download.DownloadStatus
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.skin.resolveAccentColor
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.ui.theme.appBackgroundDark

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = hiltViewModel(),
    currentProfile: Profile? = null,
    onNavigateToPlayer: (MediaType, Int, Int?, Int?, String?, String?) -> Unit = { _, _, _, _, _, _ -> },
    onNavigateToHome: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToTv: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var itemPendingDelete by remember { mutableStateOf<DownloadItem?>(null) }

    // Separate active/in-progress queue from completed library (TMPlayer pattern)
    val activeDownloads = remember(uiState.items) {
        uiState.items.filter { it.status != DownloadStatus.COMPLETED }
    }
    val completedDownloads = remember(uiState.items) {
        uiState.items.filter { it.status == DownloadStatus.COMPLETED }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundDark())
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Text(
                    text = stringResource(R.string.my_downloads),
                    style = ArflixTypography.sectionTitle.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextPrimary
                )
            }

            // Storage Stats Card
            StorageUsageCard(
                totalDownloadsFormatted = uiState.totalDownloadsFormatted,
                availableSpaceFormatted = uiState.availableSpaceFormatted,
                downloadsBytes = uiState.totalDownloadsBytes,
                freeBytes = uiState.availableSpaceBytes
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Main Content: Empty State vs Categorized Lists
            if (uiState.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.06f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileDownload,
                                contentDescription = null,
                                tint = resolveAccentColor(Pink),
                                modifier = Modifier.size(38.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.downloads_empty_title),
                            style = ArflixTypography.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.downloads_empty_desc),
                            style = ArflixTypography.caption.copy(fontSize = 13.sp),
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(0.8f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    // SECTION 1: ACTIVE / QUEUED DOWNLOADS (only while queue is not empty)
                    if (activeDownloads.isNotEmpty()) {
                        item(key = "h_active") {
                            SectionHeader(
                                title = "Baixando (${activeDownloads.size})"
                            )
                        }
                        items(activeDownloads, key = { "active_${it.id}" }) { item ->
                            ActiveDownloadCard(
                                item = item,
                                onPause = { viewModel.pauseDownload(item.id) },
                                onResume = { viewModel.resumeDownload(item.id) },
                                onCancel = { itemPendingDelete = item }
                            )
                        }
                    }

                    // SECTION 2: COMPLETED DOWNLOADS (shelf library)
                    if (completedDownloads.isNotEmpty()) {
                        item(key = "h_completed") {
                            SectionHeader(
                                title = "Baixados (${completedDownloads.size})"
                            )
                        }
                        items(completedDownloads, key = { "completed_${it.id}" }) { item ->
                            CompletedDownloadCard(
                                item = item,
                                onPlay = {
                                    item.localPath?.let { localPath ->
                                        onNavigateToPlayer(
                                            item.mediaType,
                                            item.mediaId,
                                            item.seasonNumber,
                                            item.episodeNumber,
                                            "file://$localPath",
                                            item.title
                                        )
                                    }
                                },
                                onDelete = { itemPendingDelete = item }
                            )
                        }
                    }
                }
            }
        }

        // Delete Confirmation Dialog
        itemPendingDelete?.let { item ->
            val displayTitle = if (item.seasonNumber != null && item.episodeNumber != null) {
                "T${item.seasonNumber}:E${item.episodeNumber} - ${item.title}"
            } else {
                item.title
            }
            val isCompleted = item.status == DownloadStatus.COMPLETED
            val titleText = if (isCompleted) "Excluir download" else "Cancelar download"
            val messageText = if (isCompleted) {
                "Deseja excluir \"$displayTitle\"? O arquivo baixado será removido do seu aparelho."
            } else {
                "Deseja cancelar o download de \"$displayTitle\"? Os dados baixados até agora serão descartados."
            }

            AlertDialog(
                onDismissRequest = { itemPendingDelete = null },
                containerColor = Color(0xFF1E1E24),
                shape = RoundedCornerShape(16.dp),
                title = {
                    Text(
                        text = titleText,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = messageText,
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteDownload(item.id)
                            itemPendingDelete = null
                        }
                    ) {
                        Text(
                            text = if (isCompleted) "Excluir" else "Cancelar download",
                            color = Color(0xFFE53935),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { itemPendingDelete = null }) {
                        Text(
                            text = "Voltar",
                            color = TextPrimary
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = ArflixTypography.caption.copy(
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        ),
        color = TextSecondary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp)
    )
}

/**
 * TMPlayer-inspired ActiveDownloadCard:
 * Dedicated exclusively to in-progress, queued or paused downloads.
 * Features clean full-width progress bar, cohesive single status line, and primary/cancel actions.
 */
@Composable
private fun ActiveDownloadCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val displayTitle = remember(item.title, item.seasonNumber, item.episodeNumber) {
        if (item.seasonNumber != null && item.episodeNumber != null) {
            "T${item.seasonNumber}:E${item.episodeNumber} - ${item.title}"
        } else {
            item.title
        }
    }

    // Cohesive single status line (no splitting, no overflowing letters)
    val statusLine = when (item.status) {
        DownloadStatus.DOWNLOADING -> {
            val pct = (item.progress * 100).toInt()
            val bytesStr = "${formatBytesShort(item.downloadedBytes)} de ${formatBytesShort(item.totalBytes)}"
            val speedStr = if (item.speedBytesPerSec > 0L) item.formattedSpeed else null
            listOfNotNull("$pct%", bytesStr, speedStr).joinToString(" • ")
        }
        DownloadStatus.PAUSED -> {
            val bytesStr = "${formatBytesShort(item.downloadedBytes)} de ${formatBytesShort(item.totalBytes)}"
            "Pausado • $bytesStr"
        }
        DownloadStatus.QUEUED -> {
            if (item.downloadedBytes > 0L && item.totalBytes > 0L) {
                val pct = (item.progress * 100).toInt()
                val bytesStr = "${formatBytesShort(item.downloadedBytes)} de ${formatBytesShort(item.totalBytes)}"
                "Na fila • $pct% • $bytesStr"
            } else {
                "Na fila"
            }
        }
        DownloadStatus.FAILED -> item.errorMessage ?: "Falha no download"
        else -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Poster / Thumbnail
            Box(
                modifier = Modifier
                    .width(58.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                if (!item.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.posterUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Title
                Text(
                    text = displayTitle,
                    style = ArflixTypography.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!item.subtitle.isNullOrBlank() && item.subtitle != item.title) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.subtitle,
                        style = ArflixTypography.caption.copy(fontSize = 12.sp),
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Status line
                Text(
                    text = statusLine,
                    style = ArflixTypography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = if (item.status == DownloadStatus.FAILED) Color(0xFFE53935) else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Full-width progress bar
                if (item.status != DownloadStatus.FAILED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = resolveAccentColor(Pink),
                        trackColor = Color.White.copy(alpha = 0.12f)
                    )
                }
            }
        }

        // Card Actions (side-by-side, equal width)
        CardActions {
            when (item.status) {
                DownloadStatus.DOWNLOADING -> {
                    PrimaryAction(
                        label = "Pausar",
                        icon = Icons.Default.Pause,
                        onClick = onPause
                    )
                    SecondaryAction(
                        label = "Cancelar",
                        icon = Icons.Default.Close,
                        danger = true,
                        onClick = onCancel
                    )
                }
                DownloadStatus.PAUSED -> {
                    PrimaryAction(
                        label = "Continuar",
                        icon = Icons.Default.PlayArrow,
                        onClick = onResume
                    )
                    SecondaryAction(
                        label = "Cancelar",
                        icon = Icons.Default.Close,
                        danger = true,
                        onClick = onCancel
                    )
                }
                DownloadStatus.QUEUED -> {
                    PrimaryAction(
                        label = "Baixar agora",
                        icon = Icons.Default.PlayArrow,
                        onClick = onResume
                    )
                    SecondaryAction(
                        label = "Cancelar",
                        icon = Icons.Default.Close,
                        danger = true,
                        onClick = onCancel
                    )
                }
                DownloadStatus.FAILED -> {
                    PrimaryAction(
                        label = "Tentar novamente",
                        icon = Icons.Default.Refresh,
                        onClick = onResume
                    )
                    SecondaryAction(
                        label = "Cancelar",
                        icon = Icons.Default.Close,
                        danger = true,
                        onClick = onCancel
                    )
                }
                else -> {
                    SecondaryAction(
                        label = "Cancelar download",
                        icon = Icons.Default.Close,
                        danger = true,
                        onClick = onCancel
                    )
                }
            }
        }
    }
}

/**
 * TMPlayer-inspired DownloadCard (Completed item):
 * Clean row with poster + info + badges. Tap plays immediately.
 * No bulky bottom action buttons — secondary actions (Delete) live in a subtle dropdown menu.
 */
@Composable
private fun CompletedDownloadCard(
    item: DownloadItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    val displayTitle = remember(item.title, item.seasonNumber, item.episodeNumber) {
        if (item.seasonNumber != null && item.episodeNumber != null) {
            "T${item.seasonNumber}:E${item.episodeNumber} - ${item.title}"
        } else {
            item.title
        }
    }

    val displaySubtitle = remember(item.subtitle, item.title, item.mediaType, item.seasonNumber, item.episodeNumber) {
        if (item.mediaType == MediaType.TV && item.seasonNumber != null && item.episodeNumber != null) {
            val sub = item.subtitle?.trim()
            if (!sub.isNullOrBlank() && sub != item.title) sub else null
        } else {
            null // Do not show redundant subtitle for movies
        }
    }

    val resolvedAudio = remember(item) {
        item.audioLabel ?: DownloadItem.parseAudioLabel(item.sourceTitle ?: item.subtitle ?: item.title)
    }
    val resolvedQuality = remember(item) {
        DownloadItem.parseQualityLabel(item.quality, item.sourceTitle)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .clickable(onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Poster / Thumbnail
        Box(
            modifier = Modifier
                .width(58.dp)
                .height(84.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.posterUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.FileDownload,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Center Details
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayTitle,
                style = ArflixTypography.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!displaySubtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = displaySubtitle,
                    style = ArflixTypography.caption.copy(fontSize = 12.sp),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Badges row: Quality, Dublado/Legendado/Nacional, and Size
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (resolvedQuality.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = resolvedQuality,
                            style = ArflixTypography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }
                }

                if (!resolvedAudio.isNullOrBlank()) {
                    val (badgeBg, badgeText) = when (resolvedAudio.uppercase()) {
                        "DUBLADO" -> Color(0xFF2E7D32).copy(alpha = 0.35f) to Color(0xFF81C784)
                        "LEGENDADO" -> Color(0xFFE65100).copy(alpha = 0.35f) to Color(0xFFFFB74D)
                        "NACIONAL" -> Color(0xFF1B5E20).copy(alpha = 0.40f) to Color(0xFFA5D6A7)
                        "DUAL ÁUDIO" -> Color(0xFF4A148C).copy(alpha = 0.35f) to Color(0xFFCE93D8)
                        else -> Color(0xFF00695C).copy(alpha = 0.35f) to Color(0xFF80CBC4)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .border(0.8.dp, badgeText.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = resolvedAudio,
                            style = ArflixTypography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = badgeText
                        )
                    }
                }

                val sizeBytes = if (item.totalBytes > 0L) item.totalBytes else item.downloadedBytes
                if (sizeBytes > 0L) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = formatBytesShort(sizeBytes),
                            style = ArflixTypography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Options Menu Button (⋮)
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Opções",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.background(Color(0xFF22222A))
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.download_delete),
                            color = Color(0xFFEF5350)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = Color(0xFFEF5350),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun CardActions(content: @Composable RowScope.() -> Unit) {
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun RowScope.PrimaryAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(resolveAccentColor(Pink).copy(alpha = 0.18f))
            .border(1.dp, resolveAccentColor(Pink).copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = resolveAccentColor(Pink),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = ArflixTypography.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = resolveAccentColor(Pink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RowScope.SecondaryAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    val tint = if (danger) Color(0xFFEF5350) else TextSecondary
    val bg = if (danger) Color(0xFFE53935).copy(alpha = 0.12f) else Color.White.copy(alpha = 0.06f)
    val border = if (danger) Color(0xFFE53935).copy(alpha = 0.3f) else Color.White.copy(alpha = 0.12f)

    Box(
        modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = ArflixTypography.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StorageUsageCard(
    totalDownloadsFormatted: String,
    availableSpaceFormatted: String,
    downloadsBytes: Long,
    freeBytes: Long
) {
    val totalCalculated = downloadsBytes + freeBytes
    val usageRatio = if (totalCalculated > 0L) {
        (downloadsBytes.toFloat() / totalCalculated.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(resolveAccentColor(Pink).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = resolveAccentColor(Pink),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = "Armazenamento",
                        style = ArflixTypography.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.download_storage_info,
                            totalDownloadsFormatted,
                            availableSpaceFormatted
                        ),
                        style = ArflixTypography.caption.copy(fontSize = 12.sp),
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { usageRatio.coerceAtLeast(0.01f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = resolveAccentColor(Pink),
                trackColor = Color.White.copy(alpha = 0.08f)
            )
        }
    }
}

private fun formatBytesShort(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        val gb = mb / 1024.0
        String.format(java.util.Locale.US, "%.1f GB", gb)
    } else {
        String.format(java.util.Locale.US, "%.0f MB", mb)
    }
}
