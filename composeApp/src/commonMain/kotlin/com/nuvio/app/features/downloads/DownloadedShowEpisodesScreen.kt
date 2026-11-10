package com.nuvio.app.features.downloads

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.i18n.localizedEpisodeTitle
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.NuvioAnimatedWatchedBadge
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.glass.ambientLiquidGlass
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioCardDepth
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.SeasonViewMode
import com.nuvio.app.features.details.SeasonViewModeStorage
import com.nuvio.app.features.details.components.ExpandableDescription
import com.nuvio.app.features.details.formatRuntimeFromMinutes
import com.nuvio.app.features.details.seasonSortKey
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.episodePlaybackId
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.library.LibraryDownloadsButton
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

enum class DownloadedEpisodeSort {
    EPISODE_ASC,
    EPISODE_DESC,
    UNWATCHED_FIRST,
    WATCHED_FIRST,
    COMPLETED_FIRST,
}

@Composable
fun DownloadedShowEpisodesScreen(
    showId: String,
    allSeriesEpisodes: List<DownloadItem>,
    onBack: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onDeleteDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val showEpisodes = remember(allSeriesEpisodes, showId) {
        allSeriesEpisodes
            .filter { it.parentMetaId == showId }
            .sortedForSeriesDownloads()
    }

    val storedShowMeta = remember(showId) {
        DownloadsRepository.getShowMetadata(showId)
    }
    val liveMeta = remember(showId, showEpisodes) {
        MetaDetailsRepository.peek(
            showEpisodes.firstOrNull()?.parentMetaType ?: "series",
            showId,
        )
    }

    val meta = remember(showEpisodes, storedShowMeta, liveMeta) {
        val offlineMeta = showEpisodes.toOfflineMetaDetails(showId, storedShowMeta)
        if (liveMeta != null) {
            offlineMeta.copy(
                name = liveMeta.name.ifBlank { offlineMeta.name },
                poster = liveMeta.poster ?: offlineMeta.poster,
                background = liveMeta.background ?: offlineMeta.background,
                logo = liveMeta.logo ?: offlineMeta.logo,
                description = liveMeta.description ?: offlineMeta.description,
                releaseInfo = liveMeta.releaseInfo ?: offlineMeta.releaseInfo,
                status = liveMeta.status ?: offlineMeta.status,
                imdbRating = liveMeta.imdbRating ?: offlineMeta.imdbRating,
                genres = liveMeta.genres.takeIf { it.isNotEmpty() } ?: offlineMeta.genres,
                seasonPosters = offlineMeta.seasonPosters + liveMeta.seasonPosters,
            )
        } else {
            offlineMeta
        }
    }

    // Background enrichment when connected online
    val networkStatus by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(showId, networkStatus.isOfflineLike) {
        if (!networkStatus.isOfflineLike) {
            val fetched = runCatching {
                MetaDetailsRepository.fetch(meta.type, showId)
            }.getOrNull()
            if (fetched != null) {
                DownloadsRepository.updateShowMetadataFromMetaDetails(fetched)
            }
        }
    }

    val watchProgressUiState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()

    val progressByVideoId = remember(watchProgressUiState.entries, showId) {
        watchProgressUiState.byVideoIdForContent(showId)
    }

    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedKeys = watchedUiState.watchedKeys

    val metaScreenSettings by remember {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    var cardStyle by rememberSaveable(metaScreenSettings.episodeCardStyle) {
        mutableStateOf(metaScreenSettings.episodeCardStyle)
    }

    val seasons = remember(showEpisodes) {
        showEpisodes
            .groupBy { it.seasonNumber ?: 0 }
            .keys
            .sortedBy(::seasonSortKey)
    }

    val defaultSeason = remember(seasons, showEpisodes, progressByVideoId, watchedKeys) {
        val firstUnwatched = showEpisodes.firstOrNull { ep ->
            val vid = ep.toMetaVideo()
            val videoId = buildPlaybackVideoId(showId, vid.season, vid.episode, vid.id)
            val isCompleted = progressByVideoId[videoId]?.isEffectivelyCompleted == true ||
                WatchingState.isEpisodeWatched(watchedKeys, meta.type, showId, vid)
            !isCompleted
        }
        firstUnwatched?.seasonNumber?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1
    }

    var selectedSeason by rememberSaveable(showId) { mutableStateOf<Int?>(null) }
    val currentSeason = (selectedSeason ?: defaultSeason).takeIf { it in seasons } ?: seasons.firstOrNull() ?: 0

    var searchQuery by rememberSaveable(showId) { mutableStateOf("") }
    var sortOption by rememberSaveable(showId) { mutableStateOf(DownloadedEpisodeSort.EPISODE_ASC) }

    var episodeForActionSheet by remember { mutableStateOf<DownloadItem?>(null) }
    var pendingDeletionEpisodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteAllShowConfirm by rememberSaveable { mutableStateOf(false) }
    var showActiveQueuedDialog by rememberSaveable { mutableStateOf(false) }

    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)

    // Season episodes
    val seasonEpisodes = remember(showEpisodes, currentSeason) {
        showEpisodes.filter { (it.seasonNumber ?: 0) == currentSeason }
    }

    val displayedEpisodes = remember(seasonEpisodes, searchQuery, sortOption, progressByVideoId, watchedKeys) {
        val filtered = if (searchQuery.isBlank()) {
            seasonEpisodes
        } else {
            val q = searchQuery.trim().lowercase()
            seasonEpisodes.filter { ep ->
                val epTitle = ep.displayTitle().lowercase()
                val epCode = "e${ep.episodeNumber}"
                val epNum = ep.episodeNumber?.toString() ?: ""
                epTitle.contains(q) || epCode == q || epNum == q
            }
        }

        val isWatched: (DownloadItem) -> Boolean = { ep ->
            val vid = ep.toMetaVideo()
            val videoId = buildPlaybackVideoId(showId, vid.season, vid.episode, vid.id)
            progressByVideoId[videoId]?.isEffectivelyCompleted == true ||
                WatchingState.isEpisodeWatched(watchedKeys, meta.type, showId, vid)
        }

        when (sortOption) {
            DownloadedEpisodeSort.EPISODE_ASC -> filtered.sortedBy { it.episodeNumber ?: Int.MAX_VALUE }
            DownloadedEpisodeSort.EPISODE_DESC -> filtered.sortedByDescending { it.episodeNumber ?: -1 }
            DownloadedEpisodeSort.UNWATCHED_FIRST -> filtered.sortedWith(
                compareBy<DownloadItem> { isWatched(it) }
                    .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
            )
            DownloadedEpisodeSort.WATCHED_FIRST -> filtered.sortedWith(
                compareByDescending<DownloadItem> { isWatched(it) }
                    .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
            )
            DownloadedEpisodeSort.COMPLETED_FIRST -> filtered.sortedWith(
                compareByDescending<DownloadItem> { it.status == DownloadStatus.Completed }
                    .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
            )
        }
    }

    // Up Next / Resume episode calculation
    val upNextEpisode = remember(showEpisodes, progressByVideoId, watchedKeys) {
        val inProgress = showEpisodes.firstOrNull { ep ->
            val videoId = buildPlaybackVideoId(showId, ep.seasonNumber, ep.episodeNumber, ep.videoId)
            val entry = progressByVideoId[videoId]
            entry != null && entry.isResumable && !entry.isEffectivelyCompleted && ep.isPlayable
        }
        if (inProgress != null) return@remember inProgress

        val firstUnwatched = showEpisodes.firstOrNull { ep ->
            val vid = ep.toMetaVideo()
            val videoId = buildPlaybackVideoId(showId, vid.season, vid.episode, vid.id)
            val isCompleted = progressByVideoId[videoId]?.isEffectivelyCompleted == true ||
                WatchingState.isEpisodeWatched(watchedKeys, meta.type, showId, vid)
            !isCompleted && ep.isPlayable
        }
        firstUnwatched ?: showEpisodes.firstOrNull { it.isPlayable }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = nuvioSafeBottomPadding(32.dp)),
        ) {
            item(key = "show_hero") {
                DownloadedShowHero(
                    meta = meta,
                    episodes = showEpisodes,
                    upNextEpisode = upNextEpisode,
                    cardStyle = cardStyle,
                    onBack = onBack,
                    onToggleCardStyle = {
                        cardStyle = if (cardStyle == MetaEpisodeCardStyle.Horizontal) {
                            MetaEpisodeCardStyle.List
                        } else {
                            MetaEpisodeCardStyle.Horizontal
                        }
                    },
                    onOpenDirectory = {
                        if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                            NuvioToastController.show(openDownloadsDirectoryFailedText)
                        }
                    },
                    onDeleteAllShow = { pendingDeleteAllShowConfirm = true },
                    onPlayUpNext = { ep -> onOpenDownload(ep) },
                    onOpenActiveQueued = { showActiveQueuedDialog = true },
                )
            }

            if (seasons.size > 1) {
                item(key = "season_selector") {
                    DownloadedSeasonSelector(
                        seasons = seasons,
                        currentSeason = currentSeason,
                        episodes = showEpisodes,
                        seasonPosters = meta.seasonPosters,
                        onSelectSeason = { selectedSeason = it },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            item(key = "navigator_bar") {
                DownloadedEpisodeNavigatorBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    sortOption = sortOption,
                    onSortOptionChange = { sortOption = it },
                    shownCount = displayedEpisodes.size,
                    totalCount = seasonEpisodes.size,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            if (displayedEpisodes.isEmpty()) {
                item(key = "empty_season_episodes") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp, horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                stringResource(Res.string.episode_navigator_no_match, searchQuery.trim())
                            } else {
                                stringResource(Res.string.downloads_empty_episodes)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                if (cardStyle == MetaEpisodeCardStyle.Horizontal) {
                    item(key = "horizontal_episodes_carousel") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(
                                items = displayedEpisodes,
                                key = { it.id },
                            ) { episode ->
                                val video = episode.toMetaVideo()
                                val episodeVideoId = buildPlaybackVideoId(showId, episode.seasonNumber, episode.episodeNumber, episode.videoId)
                                val progressEntry = progressByVideoId[episodeVideoId]
                                val isWatched = progressEntry?.isEffectivelyCompleted == true ||
                                    WatchingState.isEpisodeWatched(watchedKeys, meta.type, showId, video)

                                DownloadedEpisodeHorizontalCard(
                                    item = episode,
                                    video = video,
                                    progressEntry = progressEntry,
                                    isWatched = isWatched,
                                    fallbackImage = meta.background ?: meta.poster,
                                    onClick = {
                                        if (episode.isPlayable) {
                                            onOpenDownload(episode)
                                        } else if (episode.status == DownloadStatus.Paused) {
                                            DownloadsRepository.resumeDownload(episode.id)
                                        } else if (episode.status == DownloadStatus.Downloading) {
                                            DownloadsRepository.pauseDownload(episode.id)
                                        } else if (episode.status == DownloadStatus.Failed) {
                                            DownloadsRepository.retryDownload(episode.id)
                                        }
                                    },
                                    onLongPress = { episodeForActionSheet = episode },
                                    onPause = { DownloadsRepository.pauseDownload(episode.id) },
                                    onResume = { DownloadsRepository.resumeDownload(episode.id) },
                                    onRetry = { DownloadsRepository.retryDownload(episode.id) },
                                    onDelete = { pendingDeletionEpisodeId = episode.id },
                                    modifier = Modifier.width(320.dp),
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = displayedEpisodes,
                        key = { it.id },
                    ) { episode ->
                        val video = episode.toMetaVideo()
                        val episodeVideoId = buildPlaybackVideoId(showId, episode.seasonNumber, episode.episodeNumber, episode.videoId)
                        val progressEntry = progressByVideoId[episodeVideoId]
                        val isWatched = progressEntry?.isEffectivelyCompleted == true ||
                            WatchingState.isEpisodeWatched(watchedKeys, meta.type, showId, video)

                        DownloadedEpisodeListCard(
                            item = episode,
                            video = video,
                            progressEntry = progressEntry,
                            isWatched = isWatched,
                            fallbackImage = meta.background ?: meta.poster,
                            onClick = {
                                if (episode.isPlayable) {
                                    onOpenDownload(episode)
                                } else if (episode.status == DownloadStatus.Paused) {
                                    DownloadsRepository.resumeDownload(episode.id)
                                } else if (episode.status == DownloadStatus.Downloading) {
                                    DownloadsRepository.pauseDownload(episode.id)
                                } else if (episode.status == DownloadStatus.Failed) {
                                    DownloadsRepository.retryDownload(episode.id)
                                }
                            },
                            onLongPress = { episodeForActionSheet = episode },
                            onPause = { DownloadsRepository.pauseDownload(episode.id) },
                            onResume = { DownloadsRepository.resumeDownload(episode.id) },
                            onRetry = { DownloadsRepository.retryDownload(episode.id) },
                            onDelete = { pendingDeletionEpisodeId = episode.id },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }

    // Episode Action Sheet
    val currentSheetEpisode = episodeForActionSheet
    if (currentSheetEpisode != null) {
        val video = currentSheetEpisode.toMetaVideo()
        val episodeVideoId = buildPlaybackVideoId(showId, currentSheetEpisode.seasonNumber, currentSheetEpisode.episodeNumber, currentSheetEpisode.videoId)
        val isWatched = progressByVideoId[episodeVideoId]?.isEffectivelyCompleted == true ||
            WatchingState.isEpisodeWatched(watchedKeys, meta.type, showId, video)

        DownloadedEpisodeActionSheet(
            item = currentSheetEpisode,
            video = video,
            isWatched = isWatched,
            onDismiss = { episodeForActionSheet = null },
            onPlay = {
                episodeForActionSheet = null
                onOpenDownload(currentSheetEpisode)
            },
            onToggleWatched = {
                WatchingActions.toggleEpisodeWatched(
                    meta = meta,
                    episode = video,
                    isCurrentlyWatched = isWatched,
                )
            },
            onToggleSeasonWatched = {
                val allSeasonVideos = seasonEpisodes.map { it.toMetaVideo() }
                WatchingActions.toggleSeasonWatched(
                    meta = meta,
                    episodes = allSeasonVideos,
                    areCurrentlyWatched = isWatched,
                )
            },
            onDelete = {
                episodeForActionSheet = null
                pendingDeletionEpisodeId = currentSheetEpisode.id
            },
        )
    }

    // Delete single episode confirmation
    val deleteId = pendingDeletionEpisodeId
    if (deleteId != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.action_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                onDeleteDownload(deleteId)
                pendingDeletionEpisodeId = null
            },
            onDismiss = { pendingDeletionEpisodeId = null },
        )
    }

    // Delete entire show confirmation
    if (pendingDeleteAllShowConfirm) {
        NuvioStatusModal(
            title = stringResource(Res.string.action_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                showEpisodes.forEach { ep ->
                    onDeleteDownload(ep.id)
                }
                pendingDeleteAllShowConfirm = false
                onBack()
            },
            onDismiss = { pendingDeleteAllShowConfirm = false },
        )
    }

    if (showActiveQueuedDialog) {
        ActiveAndQueuedDownloadsDialog(
            onDismissRequest = { showActiveQueuedDialog = false },
            onOpenDownload = { item ->
                showActiveQueuedDialog = false
                onOpenDownload(item)
            },
        )
    }
}

@Composable
private fun DownloadedShowHero(
    meta: MetaDetails,
    episodes: List<DownloadItem>,
    upNextEpisode: DownloadItem?,
    cardStyle: MetaEpisodeCardStyle,
    onBack: () -> Unit,
    onToggleCardStyle: () -> Unit,
    onOpenDirectory: () -> Unit,
    onDeleteAllShow: () -> Unit,
    onPlayUpNext: (DownloadItem) -> Unit,
    onOpenActiveQueued: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val backdropUrl = meta.background ?: meta.poster

    val completedCount = remember(episodes) { episodes.count { it.status == DownloadStatus.Completed } }
    val totalBytes = remember(episodes) {
        episodes.sumOf { it.totalBytes ?: it.downloadedBytes }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 340.dp),
    ) {
        // Backdrop Image
        if (backdropUrl != null) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = meta.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        // Top Gradient for Status Bar & Back Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.40f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // Bottom Gradient to Screen Background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            tokens.colors.background.copy(alpha = 0.65f),
                            tokens.colors.background.copy(alpha = 0.95f),
                            tokens.colors.background,
                        ),
                    ),
                ),
        )

        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusBarTop + 8.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .ambientLiquidGlass(
                        shape = CircleShape,
                        tint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        fallback = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(Res.string.action_back),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Layout mode toggle (Horizontal vs List)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .ambientLiquidGlass(
                            shape = CircleShape,
                            tint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            fallback = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        .clickable(onClick = onToggleCardStyle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (cardStyle == MetaEpisodeCardStyle.Horizontal) {
                            Icons.Rounded.ViewAgenda
                        } else {
                            Icons.Rounded.GridView
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }

                // Active & Queued Downloads Dialog
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .ambientLiquidGlass(
                            shape = CircleShape,
                            tint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            fallback = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    LibraryDownloadsButton(
                        onClick = onOpenActiveQueued,
                        modifier = Modifier.size(40.dp),
                        tint = Color.White,
                    )
                }

                // Open Directory
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .ambientLiquidGlass(
                            shape = CircleShape,
                            tint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            fallback = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        .clickable(onClick = onOpenDirectory),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = stringResource(Res.string.downloads_open_directory),
                        tint = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }

                // Pause / Resume entire series if active
                val isAnyActive = episodes.any { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }
                val isAnyPaused = episodes.any { it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed }
                if (isAnyActive || isAnyPaused) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .ambientLiquidGlass(
                                shape = CircleShape,
                                tint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                fallback = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                            .clickable {
                                if (isAnyActive) {
                                    DownloadsRepository.pauseSeriesDownloads(meta.id)
                                } else {
                                    DownloadsRepository.resumeSeriesDownloads(meta.id)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isAnyActive) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isAnyActive) "Pause Series" else "Resume Series",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }

                // Delete entire show
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .ambientLiquidGlass(
                            shape = CircleShape,
                            tint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            fallback = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        .clickable(onClick = onDeleteAllShow),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(Res.string.action_delete),
                        tint = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        // Hero Info Body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Show Poster
                if (meta.poster != null) {
                    Box(
                        modifier = Modifier
                            .width(88.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp))
                            .nuvioCardDepth(shape = RoundedCornerShape(10.dp), surface = NuvioCardDepthSurface.EpisodeCards)
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
                    ) {
                        AsyncImage(
                            model = meta.poster,
                            contentDescription = meta.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }

                // Show Titles & Metadata
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = meta.name,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 30.sp,
                        ),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Badges row
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        meta.releaseInfo?.takeIf { it.isNotBlank() }?.let { year ->
                            HeroBadge(text = year)
                        }

                        meta.imdbRating?.takeIf { it.isNotBlank() }?.let { rating ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFF5C518).copy(alpha = 0.25f))
                                    .border(1.dp, Color(0xFFF5C518).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFF5C518),
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = rating,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                )
                            }
                        }

                        meta.status?.takeIf { it.isNotBlank() }?.let { status ->
                            HeroBadge(text = status)
                        }

                        val showStats = remember(episodes) { calculateSeriesDownloadStats(episodes) }
                        HeroBadge(
                            text = if (totalBytes > 0L) {
                                "Episode $completedCount out of ${episodes.size} downloaded • ${showStats.formattedStats}"
                            } else {
                                "Episode $completedCount out of ${episodes.size} downloaded"
                            },
                            accent = true,
                        )
                    }

                    if (meta.genres.isNotEmpty()) {
                        Text(
                            text = meta.genres.take(3).joinToString(" • "),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Overview / Description
            if (!meta.description.isNullOrBlank()) {
                ExpandableDescription(
                    text = meta.description,
                    collapsedMaxLines = 3,
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                )
            }

            // Quick Play / Resume Primary Button
            if (upNextEpisode != null) {
                val epTitle = upNextEpisode.displayTitle()
                val code = localizedSeasonEpisodeCode(upNextEpisode.seasonNumber, upNextEpisode.episodeNumber) ?: "E${upNextEpisode.episodeNumber}"
                val buttonLabel = "$code • $epTitle"

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPlayUpNext(upNextEpisode) },
                    shape = RoundedCornerShape(12.dp),
                    color = tokens.colors.accent,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(Res.string.action_play),
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.action_play) + " " + buttonLabel,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBadge(
    text: String,
    accent: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (accent) tokens.colors.accent.copy(alpha = 0.25f)
                else Color.White.copy(alpha = 0.15f),
            )
            .border(
                1.dp,
                if (accent) tokens.colors.accent.copy(alpha = 0.6f)
                else Color.White.copy(alpha = 0.25f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (accent) tokens.colors.accent else Color.White,
        )
    }
}

@Composable
private fun DownloadedSeasonSelector(
    seasons: List<Int>,
    currentSeason: Int,
    episodes: List<DownloadItem>,
    seasonPosters: Map<Int, String>,
    onSelectSeason: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    var seasonViewMode by remember {
        mutableStateOf(SeasonViewModeStorage.load() ?: SeasonViewMode.Text)
    }
    val hasPosters = remember(seasons, seasonPosters) {
        seasons.any { s -> seasonPosters.containsKey(s) }
    }

    Column(
        modifier = modifier.animateContentSize(animationSpec = tween(240)),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.details_seasons),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = tokens.colors.textPrimary,
            )

            if (hasPosters) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .ambientLiquidGlass(
                            shape = RoundedCornerShape(8.dp),
                            tint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            fallback = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .clickable {
                            val next = seasonViewMode.toggled()
                            seasonViewMode = next
                            SeasonViewModeStorage.save(next)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = if (seasonViewMode == SeasonViewMode.Posters) {
                            stringResource(Res.string.details_season_view_text)
                        } else {
                            stringResource(Res.string.details_season_view_posters)
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = tokens.colors.textSecondary,
                    )
                }
            }
        }

        if (hasPosters && seasonViewMode == SeasonViewMode.Posters) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(seasons, key = { it }) { s ->
                    val isSelected = s == currentSeason
                    val sLabel = if (s == 0) stringResource(Res.string.episodes_specials) else stringResource(Res.string.episodes_season, s)
                    val sPoster = seasonPosters[s]
                    val sCount = episodes.count { (it.seasonNumber ?: 0) == s }

                    Column(
                        modifier = Modifier
                            .width(110.dp)
                            .clickable { onSelectSeason(s) },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) tokens.colors.accent else Color.White.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(10.dp),
                                ),
                        ) {
                            if (sPoster != null) {
                                AsyncImage(
                                    model = sPoster,
                                    contentDescription = sLabel,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                        Text(
                            text = sLabel,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            ),
                            color = if (isSelected) tokens.colors.textPrimary else tokens.colors.textMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(seasons, key = { it }) { s ->
                    val isSelected = s == currentSeason
                    val sLabel = if (s == 0) stringResource(Res.string.episodes_specials) else stringResource(Res.string.episodes_season, s)
                    val sCount = episodes.count { (it.seasonNumber ?: 0) == s }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (isSelected) {
                                    Modifier.ambientLiquidGlass(
                                        shape = RoundedCornerShape(12.dp),
                                        tint = tokens.colors.accent.copy(alpha = 0.25f),
                                        fallback = tokens.colors.accent.copy(alpha = 0.25f),
                                    ).border(1.5.dp, tokens.colors.accent, RoundedCornerShape(12.dp))
                                } else {
                                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(12.dp))
                                },
                            )
                            .clickable { onSelectSeason(s) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = sLabel,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                ),
                                color = if (isSelected) tokens.colors.textPrimary else tokens.colors.textSecondary,
                            )
                            Text(
                                text = "($sCount)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) tokens.colors.accent else tokens.colors.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedEpisodeNavigatorBar(
    query: String,
    onQueryChange: (String) -> Unit,
    sortOption: DownloadedEpisodeSort,
    onSortOptionChange: (DownloadedEpisodeSort) -> Unit,
    shownCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Search Input
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(16.dp),
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.episode_navigator_search_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = tokens.colors.textPrimary),
                        cursorBrush = SolidColor(tokens.colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Res.string.episode_navigator_clear_search),
                        tint = tokens.colors.textMuted,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onQueryChange("") },
                    )
                }
            }

            // Sort Dropdown
            val sortOptions = listOf(
                NuvioDropdownOption(key = DownloadedEpisodeSort.EPISODE_ASC.name, label = "Episode: 1 → 9"),
                NuvioDropdownOption(key = DownloadedEpisodeSort.EPISODE_DESC.name, label = "Episode: 9 → 1"),
                NuvioDropdownOption(key = DownloadedEpisodeSort.UNWATCHED_FIRST.name, label = "Unwatched first"),
                NuvioDropdownOption(key = DownloadedEpisodeSort.WATCHED_FIRST.name, label = "Watched first"),
                NuvioDropdownOption(key = DownloadedEpisodeSort.COMPLETED_FIRST.name, label = "Completed first"),
            )
            val currentSortLabel = when (sortOption) {
                DownloadedEpisodeSort.EPISODE_ASC -> "1 → 9"
                DownloadedEpisodeSort.EPISODE_DESC -> "9 → 1"
                DownloadedEpisodeSort.UNWATCHED_FIRST -> "Unwatched"
                DownloadedEpisodeSort.WATCHED_FIRST -> "Watched"
                DownloadedEpisodeSort.COMPLETED_FIRST -> "Completed"
            }
            NuvioDropdownChip(
                title = stringResource(Res.string.library_filter_sort),
                label = currentSortLabel,
                selectedKey = sortOption.name,
                options = sortOptions,
                onSelected = { option ->
                    DownloadedEpisodeSort.entries.firstOrNull { it.name == option.key }?.let(onSortOptionChange)
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.episode_navigator_count, shownCount, totalCount),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedEpisodeHorizontalCard(
    item: DownloadItem,
    video: MetaVideo,
    progressEntry: WatchProgressEntry?,
    isWatched: Boolean,
    fallbackImage: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val cardShape = RoundedCornerShape(14.dp)
    val imageUrl = item.episodeThumbnail ?: video.thumbnail ?: fallbackImage
    val formattedDate = remember(item.episodeReleased) {
        item.episodeReleased?.let { formatReleaseDateForDisplay(it) }
    }
    val runtimeLabel = remember(item.episodeRuntime) {
        item.episodeRuntime?.takeIf { it > 0 }?.let { formatRuntimeFromMinutes(it) }
    }
    val ratingLabel = remember(item.episodeRating, item.episodeImdbRating, item.episodeTmdbRating) {
        val score = item.episodeRating ?: item.episodeTmdbRating ?: item.episodeImdbRating
        score?.let { ((it * 10.0).toInt() / 10.0).toString() }
    }

    Box(
        modifier = modifier
            .height(240.dp)
            .clip(cardShape)
            .nuvioCardDepth(shape = cardShape, surface = NuvioCardDepthSurface.EpisodeCards)
            .border(1.dp, Color.White.copy(alpha = 0.12f), cardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
    ) {
        // Thumbnail Image
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = video.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        // Dark gradient overlays
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.45f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.96f),
                        ),
                    ),
                ),
        )

        // Top badges: Episode code on left, Status/Watched on right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            val epCode = localizedSeasonEpisodeCode(item.seasonNumber, item.episodeNumber) ?: "E${item.episodeNumber ?: 0}"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = epCode,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioAnimatedWatchedBadge(
                    isVisible = isWatched,
                )

                // Download status pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when (item.status) {
                                DownloadStatus.Completed -> tokens.colors.accent.copy(alpha = 0.3f)
                                DownloadStatus.Downloading -> tokens.colors.accent.copy(alpha = 0.2f)
                                DownloadStatus.Paused -> Color(0xFFFFB74D).copy(alpha = 0.3f)
                                DownloadStatus.Failed -> Color(0xFFEF5350).copy(alpha = 0.3f)
                                DownloadStatus.Queued -> Color.White.copy(alpha = 0.2f)
                            },
                        )
                        .border(
                            1.dp,
                            when (item.status) {
                                DownloadStatus.Completed -> tokens.colors.accent
                                DownloadStatus.Downloading -> tokens.colors.accent
                                DownloadStatus.Paused -> Color(0xFFFFB74D)
                                DownloadStatus.Failed -> Color(0xFFEF5350)
                                DownloadStatus.Queued -> Color.White.copy(alpha = 0.4f)
                            },
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = when (item.status) {
                            DownloadStatus.Completed -> formatBytes(item.totalBytes ?: item.downloadedBytes)
                            DownloadStatus.Downloading -> "${(item.progressFraction * 100).toInt()}%"
                            DownloadStatus.Paused -> stringResource(Res.string.downloads_status_paused, "")
                            DownloadStatus.Failed -> stringResource(Res.string.downloads_status_failed)
                            DownloadStatus.Queued -> stringResource(Res.string.downloads_status_queued)
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
            }
        }

        // Bottom episode content
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val titleText = localizedEpisodeTitle(item.displayTitle(), item.episodeNumber) ?: item.displayTitle()
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (!item.episodeOverview.isNullOrBlank()) {
                Text(
                    text = item.episodeOverview,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Info row: Runtime, Rating, Air date, and Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    runtimeLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }

                    ratingLabel?.let {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = null,
                                tint = Color(0xFFF5C518),
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }

                    formattedDate?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }

                // Action buttons on card
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (item.status) {
                        DownloadStatus.Completed -> {
                            IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_play),
                                    tint = tokens.colors.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        DownloadStatus.Downloading -> {
                            IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = stringResource(Res.string.compose_action_pause),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DownloadStatus.Paused -> {
                            IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_resume),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DownloadStatus.Failed -> {
                            IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(Res.string.action_retry),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DownloadStatus.Queued -> {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = tokens.colors.accent,
                                modifier = Modifier.size(16.dp).padding(end = 4.dp),
                            )
                        }
                    }

                    IconButton(onClick = onLongPress, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Downloading progress bar
            if (item.status == DownloadStatus.Downloading) {
                if (item.totalBytes != null && item.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { item.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp)),
                        color = tokens.colors.accent,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp)),
                        color = tokens.colors.accent,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                }
            }
        }

        // Watch progress indicator
        if (progressEntry != null && progressEntry.progressFraction > 0.02f && !progressEntry.isCompleted) {
            NuvioProgressBar(
                progress = progressEntry.progressFraction,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
                height = 3.dp,
                trackColor = Color.Black.copy(alpha = 0.3f),
                fillColor = tokens.colors.accent,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedEpisodeListCard(
    item: DownloadItem,
    video: MetaVideo,
    progressEntry: WatchProgressEntry?,
    isWatched: Boolean,
    fallbackImage: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val cardShape = RoundedCornerShape(14.dp)
    val imageUrl = item.episodeThumbnail ?: video.thumbnail ?: fallbackImage
    val formattedDate = remember(item.episodeReleased) {
        item.episodeReleased?.let { formatReleaseDateForDisplay(it) }
    }
    val runtimeLabel = remember(item.episodeRuntime) {
        item.episodeRuntime?.takeIf { it > 0 }?.let { formatRuntimeFromMinutes(it) }
    }
    val ratingLabel = remember(item.episodeRating, item.episodeImdbRating, item.episodeTmdbRating) {
        val score = item.episodeRating ?: item.episodeTmdbRating ?: item.episodeImdbRating
        score?.let { ((it * 10.0).toInt() / 10.0).toString() }
    }

    Surface(
        modifier = modifier
            .clip(cardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left 16:9 Thumbnail
                Box(
                    modifier = Modifier
                        .width(128.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (imageUrl != null) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = video.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }

                    // S1:E1 badge
                    val epCode = localizedSeasonEpisodeCode(item.seasonNumber, item.episodeNumber) ?: "E${item.episodeNumber ?: 0}"
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = epCode,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }

                    // Watched badge
                    NuvioAnimatedWatchedBadge(
                        isVisible = isWatched,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                    )

                    // Runtime badge
                    runtimeLabel?.let { runtime ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = runtime,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = Color.White,
                            )
                        }
                    }

                    // Watch progress bar
                    if (progressEntry != null && progressEntry.progressFraction > 0.02f && !progressEntry.isCompleted) {
                        NuvioProgressBar(
                            progress = progressEntry.progressFraction,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(),
                            height = 2.dp,
                            trackColor = Color.Black.copy(alpha = 0.3f),
                            fillColor = tokens.colors.accent,
                        )
                    }
                }

                // Right Episode Information
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    val titleText = localizedEpisodeTitle(item.displayTitle(), item.episodeNumber) ?: item.displayTitle()
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (!item.episodeOverview.isNullOrBlank()) {
                        Text(
                            text = item.episodeOverview,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ratingLabel?.let {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFF5C518),
                                    modifier = Modifier.size(10.dp),
                                )
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    color = tokens.colors.textSecondary,
                                )
                            }
                        }

                        formattedDate?.let {
                            Text(
                                text = "•  $it",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = tokens.colors.textMuted,
                            )
                        }

                        val sizeLabel = formatBytes(item.totalBytes ?: item.downloadedBytes)
                        Text(
                            text = "•  $sizeLabel",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = tokens.colors.accent,
                        )
                    }
                }

                // Actions Column
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (item.status) {
                        DownloadStatus.Completed -> {
                            IconButton(onClick = onClick) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_play),
                                    tint = tokens.colors.accent,
                                )
                            }
                        }
                        DownloadStatus.Downloading -> {
                            IconButton(onClick = onPause) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = stringResource(Res.string.compose_action_pause),
                                    tint = tokens.colors.textPrimary,
                                )
                            }
                        }
                        DownloadStatus.Paused -> {
                            IconButton(onClick = onResume) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_resume),
                                    tint = tokens.colors.textPrimary,
                                )
                            }
                        }
                        DownloadStatus.Failed -> {
                            IconButton(onClick = onRetry) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(Res.string.action_retry),
                                    tint = tokens.colors.textPrimary,
                                )
                            }
                        }
                        DownloadStatus.Queued -> {
                            IconButton(onClick = onPause) {
                                Icon(
                                    imageVector = Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    tint = tokens.colors.accent,
                                )
                            }
                        }
                    }

                    IconButton(onClick = onLongPress) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = null,
                            tint = tokens.colors.textMuted,
                        )
                    }
                }
            }

            // Downloading progress bar in list view
            if (item.status == DownloadStatus.Downloading) {
                LinearProgressIndicator(
                    progress = { item.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp)),
                    color = tokens.colors.accent,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadedEpisodeActionSheet(
    item: DownloadItem,
    video: MetaVideo,
    isWatched: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleSeasonWatched: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val tokens = MaterialTheme.nuvio

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val imageUrl = item.episodeThumbnail ?: video.thumbnail ?: item.background ?: item.poster
                if (imageUrl != null) {
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = video.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    val code = localizedSeasonEpisodeCode(item.seasonNumber, item.episodeNumber) ?: "E${item.episodeNumber ?: 0}"
                    Text(
                        text = code + " • " + (localizedEpisodeTitle(item.displayTitle(), item.episodeNumber) ?: item.displayTitle()),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatBytes(item.totalBytes ?: item.downloadedBytes) + " • " + item.status.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textSecondary,
                    )
                }
            }

            NuvioBottomSheetDivider()

            if (item.isPlayable) {
                NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.PlayArrow,
                    title = stringResource(Res.string.action_play),
                    onClick = {
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onPlay)
                        }
                    },
                )
            }

            NuvioBottomSheetActionRow(
                icon = Icons.Default.CheckCircle,
                title = if (isWatched) {
                    stringResource(Res.string.episode_mark_unwatched)
                } else {
                    stringResource(Res.string.episode_mark_watched)
                },
                onClick = {
                    onToggleWatched()
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                    }
                },
            )

            NuvioBottomSheetActionRow(
                icon = Icons.Default.DoneAll,
                title = if (isWatched) {
                    stringResource(Res.string.episode_mark_season_unwatched)
                } else {
                    stringResource(Res.string.episode_mark_season_watched)
                },
                onClick = {
                    onToggleSeasonWatched()
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                    }
                },
            )

            NuvioBottomSheetDivider()

            NuvioBottomSheetActionRow(
                icon = Icons.Rounded.Delete,
                title = stringResource(Res.string.action_delete),
                onClick = {
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDelete)
                    }
                },
            )
        }
    }
}
