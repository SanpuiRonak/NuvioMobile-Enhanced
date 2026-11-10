package com.nuvio.app.features.downloads

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.DownloadsSettingsScreen
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    initialShowId: String? = null,
    onNavigateToShow: ((showId: String, title: String) -> Unit)? = null,
    onNavigateToMovie: ((movieId: String, title: String) -> Unit)? = null,
    onBackFromShow: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.completedItems.size) {
        DownloadsRepository.markCompletedSeen()
    }

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    var downloadPendingDeletionId by rememberSaveable { mutableStateOf<String?>(null) }
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val allSeriesEpisodes = remember(uiState.items) {
        uiState.items
            .filter { it.isEpisode }
            .sortedForSeriesDownloads()
    }

    val selectedShowTitle = remember(selectedShowId, allSeriesEpisodes) {
        selectedShowId?.let { showId ->
            allSeriesEpisodes.firstOrNull { it.parentMetaId == showId }?.title
        }
    }

    if (showSettings) {
        DownloadsSettingsScreen(
            onBack = { showSettings = false },
        )
        return
    }

    val tokens = MaterialTheme.nuvio

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = if (selectedShowId == null) {
                    stringResource(Res.string.compose_settings_root_downloads_title)
                } else {
                    selectedShowTitle ?: stringResource(Res.string.downloads_show_downloads)
                },
                onBack = {
                    if (selectedShowId != null) {
                        onBackFromShow?.invoke() ?: run { selectedShowId = null }
                    } else {
                        onBack()
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                                NuvioToastController.show(openDownloadsDirectoryFailedText)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = stringResource(Res.string.downloads_open_directory),
                            tint = tokens.colors.textPrimary,
                        )
                    }
                    if (selectedShowId == null) {
                        IconButton(onClick = { onOpenSettings?.invoke() ?: run { showSettings = true } }) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(Res.string.compose_settings_page_root),
                                tint = tokens.colors.textPrimary,
                            )
                        }
                    }
                },
            )
        }

        if (selectedShowId == null) {
            downloadsRootContent(
                uiState = uiState,
                onOpenDownload = onOpenDownload,
                onOpenShow = { showId, title ->
                    if (onNavigateToShow != null) {
                        onNavigateToShow(showId, title)
                    } else {
                        selectedShowId = showId
                    }
                },
                onOpenMovie = onNavigateToMovie,
                onDeleteDownload = { downloadPendingDeletionId = it },
            )
        } else {
            downloadsShowContent(
                showId = selectedShowId.orEmpty(),
                episodes = allSeriesEpisodes,
                onOpenDownload = onOpenDownload,
                onDeleteDownload = { downloadPendingDeletionId = it },
            )
        }
    }

    val pendingDeletionId = downloadPendingDeletionId
    if (pendingDeletionId != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.action_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.cancelDownload(pendingDeletionId)
                downloadPendingDeletionId = null
            },
            onDismiss = { downloadPendingDeletionId = null },
        )
    }
}

private fun LazyListScope.downloadsRootContent(
    uiState: DownloadsUiState,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onOpenMovie: ((movieId: String, title: String) -> Unit)? = null,
    onDeleteDownload: (String) -> Unit,
) {
    val activeItems = uiState.activeItems
    val activeMovies = activeItems.filterNot(DownloadItem::isEpisode)
    val activeShows = activeItems
        .filter(DownloadItem::isEpisode)
        .groupBy { it.parentMetaId }
        .mapNotNull { (_, episodes) ->
            episodes.firstOrNull()?.let { first ->
                first to episodes.sortedForSeriesDownloads()
            }
        }
        .sortedBy { (item, _) -> item.title.lowercase() }

    val completedMovies = uiState.completedItems.filterNot(DownloadItem::isEpisode)
    val completedShows = uiState.completedItems
        .filter(DownloadItem::isEpisode)
        .groupBy { it.parentMetaId }
        .mapNotNull { (_, episodes) ->
            episodes.firstOrNull()?.let { first ->
                first to episodes
            }
        }
        .sortedBy { (item, _) -> item.title.lowercase() }

    if (activeItems.isNotEmpty()) {
        item {
            val hasDownloading = activeItems.any { it.status == DownloadStatus.Downloading }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_section_active),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        if (hasDownloading) Res.string.downloads_action_pause_all else Res.string.downloads_action_resume_all,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable {
                            if (hasDownloading) {
                                DownloadsRepository.pauseActiveDownloads()
                            } else {
                                DownloadsRepository.resumeAllPausedDownloads()
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        if (activeMovies.isNotEmpty()) {
            items(
                items = activeMovies,
                key = { it.id },
            ) { item ->
                val movieId = item.parentMetaId.ifBlank { item.id }
                MovieDownloadRow(
                    item = item,
                    onOpen = {
                        if (onOpenMovie != null) {
                            onOpenMovie(movieId, item.title)
                        } else {
                            onOpenDownload(item)
                        }
                    },
                    onPlay = { onOpenDownload(item) },
                    onPause = { DownloadsRepository.pauseDownload(item.id) },
                    onResume = { DownloadsRepository.resumeDownload(item.id) },
                    onRetry = { DownloadsRepository.retryDownload(item.id) },
                    onDelete = { onDeleteDownload(item.id) },
                    canOpenDetails = onOpenMovie != null,
                )
            }
        }
        if (activeShows.isNotEmpty()) {
            items(
                items = activeShows,
                key = { (item, _) -> "active_${item.parentMetaId}" },
            ) { (item, episodes) ->
                val allShowEpisodes = remember(uiState.items, item.parentMetaId) {
                    uiState.items.filter { it.isEpisode && it.parentMetaId == item.parentMetaId }
                }
                val downloadingCount = episodes.count { it.status == DownloadStatus.Downloading }
                val queuedCount = episodes.count { it.status == DownloadStatus.Queued }
                val isDownloading = downloadingCount > 0
                val isQueued = queuedCount > 0
                val seriesStats = remember(allShowEpisodes) { calculateSeriesDownloadStats(allShowEpisodes) }

                val activeStatusText = downloadActivitySummary(downloading = downloadingCount, queued = queuedCount)
                    ?: stringResource(Res.string.downloads_episode_count, episodes.size)
                val seriesSubtitle = if (seriesStats.totalBytes > 0L) {
                    "$activeStatusText • ${seriesStats.sizeStatsText()}"
                } else {
                    activeStatusText
                }

                ShowDownloadRow(
                    title = item.title,
                    subtitle = seriesSubtitle,
                    onClick = { onOpenShow(item.parentMetaId, item.title) },
                    action = {
                        IconButton(
                            onClick = {
                                if (isDownloading || isQueued) {
                                    DownloadsRepository.pauseSeriesDownloads(item.parentMetaId)
                                } else {
                                    DownloadsRepository.resumeSeriesDownloads(item.parentMetaId)
                                }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = if (isDownloading || isQueued) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(
                                    if (isDownloading || isQueued) Res.string.downloads_action_pause_series else Res.string.downloads_action_resume_series,
                                ),
                                tint = if (isDownloading || isQueued) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
            }
        }
    }

    if (completedMovies.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_movies))
        }
        items(
            items = completedMovies,
            key = { it.id },
        ) { item ->
            val movieId = item.parentMetaId.ifBlank { item.id }
            MovieDownloadRow(
                item = item,
                onOpen = {
                    if (onOpenMovie != null) {
                        onOpenMovie(movieId, item.title)
                    } else {
                        onOpenDownload(item)
                    }
                },
                onPlay = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
                canOpenDetails = onOpenMovie != null,
            )
        }
    }

    if (completedShows.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_shows))
        }
        items(
            items = completedShows,
            key = { (item, _) -> item.parentMetaId },
        ) { (item, episodes) ->
            val seriesStats = remember(episodes) { calculateSeriesDownloadStats(episodes) }
            val episodeCountText = stringResource(Res.string.downloads_episode_count, episodes.size)
            val seriesSubtitle = if (episodes.size > 1) {
                "$episodeCountText • ${seriesStats.sizeStatsText()}"
            } else {
                "$episodeCountText • ${stringResource(Res.string.downloads_series_size_total, formatBytes(seriesStats.totalBytes))}"
            }

            ShowDownloadRow(
                title = item.title,
                subtitle = seriesSubtitle,
                onClick = { onOpenShow(item.parentMetaId, item.title) },
                action = {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                },
            )
        }
    }

    if (uiState.items.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    item(key = "downloads_device_storage_bar") {
        DeviceStorageBar(
            appDownloadsBytes = uiState.items.sumOf { it.downloadedBytes },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

private fun LazyListScope.downloadsShowContent(
    showId: String,
    episodes: List<DownloadItem>,
    onOpenDownload: (DownloadItem) -> Unit,
    onDeleteDownload: (String) -> Unit,
) {
    val showEpisodes = episodes
        .filter { it.parentMetaId == showId }
        .sortedForSeriesDownloads()

    val seasons = showEpisodes
        .groupBy { it.seasonNumber ?: 0 }
        .toList()
        .sortedWith(
            compareBy<Pair<Int, List<DownloadItem>>> { (season, _) ->
                if (season == 0) 0 else 1
            }.thenBy { (season, _) -> if (season == 0) 0 else season },
        )

    if (seasons.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_episodes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    seasons.forEach { (seasonNumber, entries) ->
        item {
            SectionTitle(
                if (seasonNumber == 0) {
                    stringResource(Res.string.episodes_specials)
                } else {
                    stringResource(Res.string.episodes_season, seasonNumber)
                },
            )
        }

        val sortedEpisodes = entries.sortedForSeriesDownloads()

        items(
            items = sortedEpisodes,
            key = { it.id },
        ) { item ->
            EpisodeDownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }
}

@Composable
private fun MovieDownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    canOpenDetails: Boolean = false,
) {
    val isDownloading = item.status == DownloadStatus.Downloading
    val isQueued = item.status == DownloadStatus.Queued
    val animatedProgress by animateFloatAsState(
        targetValue = item.progressFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "movieDownloadProgress",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .defaultMinSize(minHeight = 72.dp)
            .clickable(enabled = item.isPlayable || canOpenDetails, onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (isQueued) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = downloadStatusText(item),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isQueued) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when (item.status) {
                        DownloadStatus.Downloading, DownloadStatus.Queued -> {
                            IconButton(
                                onClick = onPause,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = stringResource(Res.string.compose_action_pause),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        DownloadStatus.Paused -> {
                            IconButton(
                                onClick = onResume,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_resume),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        DownloadStatus.Failed -> {
                            IconButton(
                                onClick = onRetry,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(Res.string.action_retry),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        DownloadStatus.Completed -> {
                            IconButton(
                                onClick = onPlay ?: onOpen,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_play),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(Res.string.action_delete),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                if (item.totalBytes != null && item.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowDownloadRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    action: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .defaultMinSize(minHeight = 72.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            action()
        }
    }
}

@Composable
private fun EpisodeDownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val displayTitle = item.displayTitle()
    val displaySubtitle = downloadDisplaySubtitle(
        item = item,
        displayTitle = displayTitle,
    )
    val isDownloading = item.status == DownloadStatus.Downloading
    val isQueued = item.status == DownloadStatus.Queued
    val animatedProgress by animateFloatAsState(
        targetValue = item.progressFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "episodeDownloadProgress",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(enabled = item.isPlayable, onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (displaySubtitle.isNotBlank()) {
                        Text(
                            text = displaySubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (isQueued) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = downloadStatusText(item),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isQueued) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when (item.status) {
                        DownloadStatus.Downloading, DownloadStatus.Queued -> {
                            IconButton(
                                onClick = onPause,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = stringResource(Res.string.compose_action_pause),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        DownloadStatus.Paused -> {
                            IconButton(
                                onClick = onResume,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_resume),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        DownloadStatus.Failed -> {
                            IconButton(
                                onClick = onRetry,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(Res.string.action_retry),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        DownloadStatus.Completed -> {
                            IconButton(
                                onClick = onPlay ?: onOpen,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_play),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(Res.string.action_delete),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                if (item.totalBytes != null && item.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun downloadDisplaySubtitle(
    item: DownloadItem,
    displayTitle: String,
): String {
    val seasonNumber = item.seasonNumber
    val episodeNumber = item.episodeNumber
    if (seasonNumber == null || episodeNumber == null) {
        return item.displaySubtitle
    }

    val episodeCode = stringResource(
        Res.string.compose_player_episode_code_full,
        seasonNumber,
        episodeNumber,
    )
    return listOf(
        episodeCode,
        item.episodeTitle?.trim().orEmpty().takeIf { it.isNotBlank() && it != displayTitle },
        item.title.trim().takeIf { it.isNotBlank() && it != displayTitle },
    ).filterNotNull().joinToString(" • ")
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun downloadStatusText(item: DownloadItem): String {
    val size = if (item.totalBytes != null && item.totalBytes > 0L) {
        "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
    } else {
        formatBytes(item.downloadedBytes)
    }

    return when (item.status) {
        DownloadStatus.Downloading -> stringResource(Res.string.downloads_status_downloading, size)
        DownloadStatus.Queued -> stringResource(Res.string.downloads_status_queued)
        DownloadStatus.Paused -> stringResource(Res.string.downloads_status_paused, size)
        DownloadStatus.Completed -> stringResource(
            Res.string.downloads_status_completed,
            formatBytes(item.totalBytes?.takeIf { it > 0L } ?: item.downloadedBytes),
        )
        DownloadStatus.Failed -> item.errorMessage ?: stringResource(Res.string.downloads_status_failed)
    }
}

/** "2 downloading • 1 queued"-style summary of the non-zero counts, or null when all are zero. */
@Composable
internal fun downloadActivitySummary(
    downloading: Int = 0,
    queued: Int = 0,
    paused: Int = 0,
    failed: Int = 0,
): String? = listOf(
    downloading to Res.string.downloads_summary_downloading,
    queued to Res.string.downloads_summary_queued,
    paused to Res.string.downloads_summary_paused,
    failed to Res.string.downloads_summary_failed,
).filter { (count, _) -> count > 0 }
    .map { (count, label) -> stringResource(label, count) }
    .joinToString(" • ")
    .ifEmpty { null }

@Composable
internal fun episodeCountText(count: Int): String =
    pluralStringResource(Res.plurals.downloads_episodes_count, count, count)

@Composable
private fun SeriesDownloadStats.sizeStatsText(): String =
    stringResource(
        Res.string.downloads_series_size_stats,
        formatBytes(totalBytes),
        formatBytes(avgBytes),
        formatBytes(medianBytes),
    )
