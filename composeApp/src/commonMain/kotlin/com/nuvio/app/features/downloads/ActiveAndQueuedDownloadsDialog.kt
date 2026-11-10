package com.nuvio.app.features.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private enum class DownloadsDialogFilter {
    ACTIVE_QUEUED,
    ALL,
}

@Composable
fun ActiveAndQueuedDownloadsDialog(
    onDismissRequest: () -> Unit,
    onOpenDownload: ((DownloadItem) -> Unit)? = null,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()

    var filterMode by remember { mutableStateOf(DownloadsDialogFilter.ACTIVE_QUEUED) }
    var selectedItemIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeleteIds by remember { mutableStateOf<List<String>?>(null) }
    val collapsedFolders = remember { mutableStateMapOf<String, Boolean>() }

    val displayedItems = remember(uiState.items, filterMode) {
        val raw = if (filterMode == DownloadsDialogFilter.ACTIVE_QUEUED) {
            uiState.items.filter { it.status != DownloadStatus.Completed }
        } else {
            uiState.items
        }
        raw.sortedWith(
            compareBy<DownloadItem> { item ->
                when (item.status) {
                    DownloadStatus.Downloading -> 0
                    DownloadStatus.Queued -> 1
                    DownloadStatus.Paused -> 2
                    DownloadStatus.Failed -> 3
                    DownloadStatus.Completed -> 4
                }
            }.thenBy { it.createdAtEpochMs },
        )
    }

    // Keep selected IDs in sync with displayed items
    val availableIds = remember(displayedItems) { displayedItems.map { it.id }.toSet() }
    val validSelectedIds = remember(selectedItemIds, availableIds) {
        selectedItemIds.intersect(availableIds)
    }

    val downloadingCount = displayedItems.count { it.status == DownloadStatus.Downloading }
    val queuedCount = displayedItems.count { it.status == DownloadStatus.Queued }
    val pausedCount = displayedItems.count { it.status == DownloadStatus.Paused }
    val failedCount = displayedItems.count { it.status == DownloadStatus.Failed }

    // Grouping: Series and Movies
    val seriesGroups: Map<String, Map<Int, List<DownloadItem>>> = remember(displayedItems) {
        displayedItems
            .filter { it.isEpisode }
            .groupBy { it.parentMetaId }
            .mapValues { (_, episodes) ->
                episodes.groupBy { it.seasonNumber ?: 0 }
                    .entries
                    .sortedBy { it.key }
                    .associate { it.key to it.value }
            }
    }

    val movieItems = remember(displayedItems) {
        displayedItems.filterNot { it.isEpisode }
    }

    val selectedItems = remember(displayedItems, validSelectedIds) {
        displayedItems.filter { it.id in validSelectedIds }
    }

    val selectedToPause = remember(selectedItems) {
        selectedItems.filter { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }
    }
    val selectedToResume = remember(selectedItems) {
        selectedItems.filter { it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed }
    }
    val selectedToRetry = remember(selectedItems) {
        selectedItems.filter { it.status == DownloadStatus.Failed }
    }

    val hasGlobalActive = downloadingCount > 0 || queuedCount > 0
    val hasGlobalPaused = pausedCount > 0
    val hasGlobalFailed = failedCount > 0

    DialogSurface(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.padding(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(Res.string.downloads_show_active_queued),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val subtitle = if (displayedItems.isEmpty()) {
                        stringResource(Res.string.downloads_view_empty)
                    } else {
                        downloadActivitySummary(downloadingCount, queuedCount, pausedCount, failedCount)
                            ?: stringResource(Res.string.downloads_summary_completed, displayedItems.size)
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (downloadingCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.action_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Tabs / Filters
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChipPill(
                    label = stringResource(Res.string.downloads_section_active_queued),
                    selected = filterMode == DownloadsDialogFilter.ACTIVE_QUEUED,
                    onClick = { filterMode = DownloadsDialogFilter.ACTIVE_QUEUED },
                )
                FilterChipPill(
                    label = stringResource(Res.string.downloads_filter_all),
                    selected = filterMode == DownloadsDialogFilter.ALL,
                    onClick = { filterMode = DownloadsDialogFilter.ALL },
                )
            }

            // Bulk Actions Bar or Global Actions
            if (validSelectedIds.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(Res.string.downloads_selected_count, validSelectedIds.size),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (selectedToPause.isNotEmpty()) {
                                ActionPillButton(
                                    icon = Icons.Rounded.Pause,
                                    label = stringResource(Res.string.downloads_action_pause_count, selectedToPause.size),
                                    onClick = {
                                        DownloadsRepository.pauseDownloads(selectedToPause.map { it.id })
                                    },
                                )
                            }
                            if (selectedToResume.isNotEmpty()) {
                                ActionPillButton(
                                    icon = Icons.Rounded.PlayArrow,
                                    label = stringResource(Res.string.downloads_action_resume_count, selectedToResume.size),
                                    onClick = {
                                        DownloadsRepository.resumeDownloads(selectedToResume.map { it.id })
                                    },
                                )
                            }
                            if (selectedToRetry.isNotEmpty()) {
                                ActionPillButton(
                                    icon = Icons.Rounded.Refresh,
                                    label = stringResource(Res.string.downloads_action_retry_count, selectedToRetry.size),
                                    onClick = {
                                        DownloadsRepository.retryDownloads(selectedToRetry.map { it.id })
                                    },
                                )
                            }
                            ActionPillButton(
                                icon = Icons.Rounded.Delete,
                                label = stringResource(Res.string.action_delete),
                                isDestructive = true,
                                onClick = {
                                    pendingDeleteIds = validSelectedIds.toList()
                                },
                            )
                            ActionPillButton(
                                icon = Icons.Rounded.Close,
                                label = stringResource(Res.string.downloads_action_deselect),
                                onClick = { selectedItemIds = emptySet() },
                            )
                        }
                    }
                }
            } else if (displayedItems.isNotEmpty()) {
                // Global Quick Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionPillButton(
                        icon = Icons.Rounded.Check,
                        label = stringResource(Res.string.downloads_select_all),
                        modifier = Modifier.weight(1f),
                        onClick = { selectedItemIds = availableIds },
                    )
                    if (hasGlobalActive) {
                        ActionPillButton(
                            icon = Icons.Rounded.Pause,
                            label = stringResource(Res.string.downloads_action_pause_all),
                            modifier = Modifier.weight(1f),
                            onClick = { DownloadsRepository.pauseActiveDownloads() },
                        )
                    }
                    if (hasGlobalPaused) {
                        ActionPillButton(
                            icon = Icons.Rounded.PlayArrow,
                            label = stringResource(Res.string.downloads_action_resume_all),
                            modifier = Modifier.weight(1f),
                            onClick = { DownloadsRepository.resumeAllPausedDownloads() },
                        )
                    }
                    if (hasGlobalFailed) {
                        ActionPillButton(
                            icon = Icons.Rounded.Refresh,
                            label = stringResource(Res.string.downloads_action_retry_failed),
                            modifier = Modifier.weight(1f),
                            onClick = { DownloadsRepository.retryAllFailedDownloads() },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Items List
            if (displayedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (filterMode == DownloadsDialogFilter.ACTIVE_QUEUED) {
                            stringResource(Res.string.downloads_empty_active)
                        } else {
                            stringResource(Res.string.downloads_empty_title)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val showFallbackTitle = stringResource(Res.string.downloads_show_fallback_title)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Series Section
                    seriesGroups.forEach { (showId, seasonsMap) ->
                        val showTitle = seasonsMap.values.firstOrNull()?.firstOrNull()?.title ?: showFallbackTitle
                        val allShowEpisodes = seasonsMap.values.flatten()
                        val allShowIds = allShowEpisodes.map { it.id }.toSet()
                        val isShowFullySelected = allShowIds.isNotEmpty() && allShowIds.all { it in validSelectedIds }
                        val isShowPartiallySelected = !isShowFullySelected && allShowIds.any { it in validSelectedIds }

                        item(key = "show_header_$showId") {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        TriStateCheckbox(
                                            state = when {
                                                isShowFullySelected -> ToggleableState.On
                                                isShowPartiallySelected -> ToggleableState.Indeterminate
                                                else -> ToggleableState.Off
                                            },
                                            onClick = {
                                                selectedItemIds = if (isShowFullySelected) {
                                                    selectedItemIds - allShowIds
                                                } else {
                                                    selectedItemIds + allShowIds
                                                }
                                            },
                                        )
                                        Text(
                                            text = showTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    Text(
                                        text = episodeCountText(allShowEpisodes.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // Season Folders
                        seasonsMap.forEach { (seasonNum, seasonEpisodes) ->
                            val folderKey = "folder_${showId}_s$seasonNum"
                            val isCollapsed = collapsedFolders[folderKey] == true
                            val seasonEpisodeIds = seasonEpisodes.map { it.id }.toSet()
                            val isFolderFullySelected = seasonEpisodeIds.isNotEmpty() && seasonEpisodeIds.all { it in validSelectedIds }
                            val isFolderPartiallySelected = !isFolderFullySelected && seasonEpisodeIds.any { it in validSelectedIds }

                            item(key = "season_folder_${showId}_$seasonNum") {
                                val seasonLabel = if (seasonNum > 0) {
                                    stringResource(Res.string.episodes_season, seasonNum)
                                } else {
                                    stringResource(Res.string.episodes_specials)
                                }
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            collapsedFolders[folderKey] = !isCollapsed
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            TriStateCheckbox(
                                                state = when {
                                                    isFolderFullySelected -> ToggleableState.On
                                                    isFolderPartiallySelected -> ToggleableState.Indeterminate
                                                    else -> ToggleableState.Off
                                                },
                                                onClick = {
                                                    selectedItemIds = if (isFolderFullySelected) {
                                                        selectedItemIds - seasonEpisodeIds
                                                    } else {
                                                        selectedItemIds + seasonEpisodeIds
                                                    }
                                                },
                                            )
                                            Icon(
                                                imageVector = Icons.Rounded.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                Text(
                                                    text = seasonLabel,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                val sActiveCount = seasonEpisodes.count { it.status == DownloadStatus.Downloading }
                                                val sQueuedCount = seasonEpisodes.count { it.status == DownloadStatus.Queued }
                                                val sPausedCount = seasonEpisodes.count { it.status == DownloadStatus.Paused }
                                                val sFailedCount = seasonEpisodes.count { it.status == DownloadStatus.Failed }
                                                val folderStatus = downloadActivitySummary(sActiveCount, sQueuedCount, sPausedCount, sFailedCount)
                                                    ?.let { "$it • ${episodeCountText(seasonEpisodes.size)}" }
                                                    ?: stringResource(Res.string.downloads_summary_completed, seasonEpisodes.size)
                                                Text(
                                                    text = folderStatus,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = if (sActiveCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (isCollapsed) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                                                contentDescription = stringResource(if (isCollapsed) Res.string.action_expand else Res.string.action_collapse),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            // Episodes inside folder
                            if (!isCollapsed) {
                                items(
                                    items = seasonEpisodes,
                                    key = { it.id },
                                ) { episodeItem ->
                                    Box(modifier = Modifier.padding(start = 24.dp)) {
                                        ActiveDownloadQueueRow(
                                            item = episodeItem,
                                            isSelected = episodeItem.id in validSelectedIds,
                                            onToggleSelect = { checked ->
                                                selectedItemIds = if (checked) {
                                                    selectedItemIds + episodeItem.id
                                                } else {
                                                    selectedItemIds - episodeItem.id
                                                }
                                            },
                                            onOpen = { onOpenDownload?.invoke(episodeItem) },
                                            onPause = { DownloadsRepository.pauseDownload(episodeItem.id) },
                                            onResume = { DownloadsRepository.resumeDownload(episodeItem.id) },
                                            onRetry = { DownloadsRepository.retryDownload(episodeItem.id) },
                                            onDelete = { pendingDeleteIds = listOf(episodeItem.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Movies Section
                    if (movieItems.isNotEmpty()) {
                        item(key = "movies_section_header") {
                            Text(
                                text = stringResource(Res.string.downloads_section_movies),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }

                        items(
                            items = movieItems,
                            key = { it.id },
                        ) { movieItem ->
                            ActiveDownloadQueueRow(
                                item = movieItem,
                                isSelected = movieItem.id in validSelectedIds,
                                onToggleSelect = { checked ->
                                    selectedItemIds = if (checked) {
                                        selectedItemIds + movieItem.id
                                    } else {
                                        selectedItemIds - movieItem.id
                                    }
                                },
                                onOpen = { onOpenDownload?.invoke(movieItem) },
                                onPause = { DownloadsRepository.pauseDownload(movieItem.id) },
                                onResume = { DownloadsRepository.resumeDownload(movieItem.id) },
                                onRetry = { DownloadsRepository.retryDownload(movieItem.id) },
                                onDelete = { pendingDeleteIds = listOf(movieItem.id) },
                            )
                        }
                    }
                }
            }

            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.action_close),
                    onClick = onDismissRequest,
                    style = DialogButtonStyle.Secondary,
                )
            }
        }
    }

    // Delete confirmation modal
    val toDelete = pendingDeleteIds
    if (!toDelete.isNullOrEmpty()) {
        NuvioStatusModal(
            title = stringResource(Res.string.action_delete_confirm_title),
            message = if (toDelete.size > 1) {
                stringResource(Res.string.downloads_delete_selected_confirm, toDelete.size)
            } else {
                stringResource(Res.string.action_delete_confirm_message)
            },
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.cancelDownloads(toDelete)
                selectedItemIds = selectedItemIds - toDelete.toSet()
                pendingDeleteIds = null
            },
            onDismiss = { pendingDeleteIds = null },
        )
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ActionPillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isDestructive) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ActiveDownloadQueueRow(
    item: DownloadItem,
    isSelected: Boolean,
    onToggleSelect: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onToggleSelect(!isSelected) },
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        val animatedProgress by animateFloatAsState(
            targetValue = item.progressFraction.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 400),
            label = "dialogDownloadProgress",
        )
        val animatedPercentage = (animatedProgress * 100).toInt().coerceIn(0, 100)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Checkbox
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onToggleSelect,
                    modifier = Modifier.size(24.dp),
                )

                // Thumbnail / Icon
                val posterUrl = item.episodeThumbnail ?: item.poster
                if (!posterUrl.isNullOrBlank()) {
                    val revealFraction = when {
                        item.status == DownloadStatus.Downloading -> animatedProgress
                        item.status == DownloadStatus.Paused -> animatedProgress
                        else -> 0f
                    }
                    val grayscaleMatrix = remember { ColorMatrix().apply { setToSaturation(0f) } }

                    BoxWithConstraints(
                        modifier = Modifier
                            .size(width = 40.dp, height = 40.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    ) {
                        val totalWidth = maxWidth
                        val totalHeight = maxHeight

                        // Grayscale base
                        AsyncImage(
                            model = posterUrl,
                            contentDescription = item.title,
                            colorFilter = ColorFilter.colorMatrix(grayscaleMatrix),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = if (item.status == DownloadStatus.Paused) 0.5f else 0.35f)),
                        )

                        // Full color reveal descending
                        if (revealFraction > 0.001f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(totalHeight * revealFraction)
                                    .clipToBounds(),
                            ) {
                                AsyncImage(
                                    model = posterUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .width(totalWidth)
                                        .height(totalHeight),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }

                        // Center percentage badge on thumbnail
                        if (item.status == DownloadStatus.Downloading || item.status == DownloadStatus.Paused) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color.Black.copy(alpha = 0.72f),
                                modifier = Modifier.align(Alignment.Center),
                            ) {
                                Text(
                                    text = "$animatedPercentage%",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (item.status == DownloadStatus.Queued) Icons.Rounded.Schedule else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Info
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    val titleText = if (item.isEpisode && item.episodeNumber != null) {
                        val seasonStr = item.seasonNumber?.let { "S$it:" } ?: ""
                        "$seasonStr${"E${item.episodeNumber}"} • ${item.episodeTitle?.takeIf { it.isNotBlank() } ?: item.displayTitle()}"
                    } else {
                        item.displayTitle()
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val episodeDetail = item.streamTitle ?: item.providerName.orEmpty()
                    if (episodeDetail.isNotBlank()) {
                        Text(
                            text = episodeDetail,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Status line
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (item.status == DownloadStatus.Queued) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        val statusText = downloadStatusText(item)
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = when (item.status) {
                                DownloadStatus.Downloading, DownloadStatus.Queued -> MaterialTheme.colorScheme.primary
                                DownloadStatus.Failed -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Individual Action Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    when (item.status) {
                        DownloadStatus.Downloading, DownloadStatus.Queued -> {
                            IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = stringResource(Res.string.compose_action_pause),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DownloadStatus.Paused -> {
                            IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_resume),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DownloadStatus.Failed -> {
                            IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(Res.string.action_retry),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DownloadStatus.Completed -> {
                            IconButton(onClick = onOpen, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_play),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(Res.string.action_delete),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Progress bar
            if (item.status == DownloadStatus.Downloading) {
                if (item.totalBytes != null && item.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}
