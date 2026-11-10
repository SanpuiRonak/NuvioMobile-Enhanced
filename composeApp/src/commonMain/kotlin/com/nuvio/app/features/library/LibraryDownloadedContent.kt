package com.nuvio.app.features.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.poster.withCustomPosterUrl
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.aspectRatio
import com.nuvio.app.core.ui.cardWidth
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioCardDepth
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.downloadActivitySummary
import com.nuvio.app.features.downloads.episodeCountText
import com.nuvio.app.features.downloads.formatDownloadBadgeProgress
import com.nuvio.app.features.downloads.sortedForSeriesDownloads
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

data class ActiveDownloadShelfItem(
    val id: String,
    val title: String,
    val poster: String?,
    val progressFraction: Float,
    val status: DownloadStatus,
    val isShow: Boolean,
    val parentMetaId: String,
    val rawItem: DownloadItem?,
    val episodeProgresses: List<Float> = emptyList(),
    val completedEpisodeCount: Int = 0,
    val totalEpisodeCount: Int = 0,
    val downloadingEpisodeCount: Int = 0,
    val queuedEpisodeCount: Int = 0,
)

internal fun buildDownloadedSections(
    savedSections: List<LibrarySection>,
    savedItems: List<LibraryItem>,
    downloadItems: List<DownloadItem>,
): List<LibrarySection> {
    if (downloadItems.isEmpty()) return emptyList()

    val movies = downloadItems.filterNot(DownloadItem::isEpisode)
    val shows = downloadItems
        .filter(DownloadItem::isEpisode)
        .groupBy { it.parentMetaId }

    val savedItemsById = savedItems.associateBy { it.id }

    val downloadedLibraryItems = buildList {
        movies.distinctBy { it.parentMetaId.ifBlank { it.id } }.forEach { movie ->
            val id = movie.parentMetaId.ifBlank { movie.id }
            val existing = savedItemsById[id] ?: savedItemsById[movie.id]
            if (existing != null) {
                if (existing.releaseInfo.isNullOrBlank() && !movie.showReleaseInfo.isNullOrBlank()) {
                    add(existing.copy(releaseInfo = movie.showReleaseInfo))
                } else {
                    add(existing)
                }
            } else {
                add(
                    LibraryItem(
                        id = id,
                        type = movie.parentMetaType.ifBlank { "movie" },
                        name = movie.title,
                        poster = movie.poster,
                        banner = movie.background,
                        logo = movie.logo,
                        description = movie.showDescription,
                        releaseInfo = movie.showReleaseInfo,
                        imdbRating = movie.showImdbRating,
                        genres = movie.showGenres,
                        posterShape = PosterShape.Poster,
                        imdbId = id.takeIf { it.startsWith("tt") },
                        savedAtEpochMs = movie.createdAtEpochMs,
                    ),
                )
            }
        }

        shows.forEach { (showId, episodes) ->
            val existing = savedItemsById[showId]
            if (existing != null) {
                if (existing.releaseInfo.isNullOrBlank()) {
                    val showMeta = DownloadsRepository.getShowMetadata(showId)
                    val resolvedReleaseInfo = showMeta?.releaseInfo?.takeIf(String::isNotBlank)
                        ?: episodes.firstNotNullOfOrNull { it.showReleaseInfo?.takeIf(String::isNotBlank) }
                    if (!resolvedReleaseInfo.isNullOrBlank()) {
                        add(existing.copy(releaseInfo = resolvedReleaseInfo))
                    } else {
                        add(existing)
                    }
                } else {
                    add(existing)
                }
            } else {
                val showMeta = DownloadsRepository.getShowMetadata(showId)
                val firstEpisode = episodes.firstOrNull()
                val latestEpoch = episodes.maxOfOrNull { it.updatedAtEpochMs }
                    ?: episodes.firstOrNull()?.createdAtEpochMs
                    ?: 0L
                if (firstEpisode != null) {
                    add(
                        LibraryItem(
                            id = showId,
                            type = showMeta?.type?.ifBlank { firstEpisode.parentMetaType.ifBlank { "series" } }
                                ?: firstEpisode.parentMetaType.ifBlank { "series" },
                            name = showMeta?.title?.ifBlank { firstEpisode.title } ?: firstEpisode.title,
                            poster = showMeta?.poster ?: firstEpisode.poster,
                            banner = showMeta?.background ?: firstEpisode.background,
                            logo = showMeta?.logo ?: firstEpisode.logo,
                            description = showMeta?.description ?: firstEpisode.showDescription,
                            releaseInfo = showMeta?.releaseInfo?.takeIf(String::isNotBlank)
                                ?: episodes.firstNotNullOfOrNull { it.showReleaseInfo?.takeIf(String::isNotBlank) }
                                ?: firstEpisode.showReleaseInfo,
                            imdbRating = showMeta?.imdbRating ?: firstEpisode.showImdbRating,
                            genres = showMeta?.genres?.takeIf { it.isNotEmpty() } ?: firstEpisode.showGenres,
                            posterShape = PosterShape.Poster,
                            imdbId = showId.takeIf { it.startsWith("tt") },
                            savedAtEpochMs = latestEpoch,
                        ),
                    )
                }
            }
        }
    }.distinctBy { it.id }

    val downloadedItemsById = downloadedLibraryItems.associateBy { it.id }

    val resultSections = mutableListOf<LibrarySection>()
    val placedIds = mutableSetOf<String>()

    // First, preserve the exact same sections from saved tab
    for (section in savedSections) {
        val matchingItems = section.items.mapNotNull { savedItem ->
            downloadedItemsById[savedItem.id]?.also { placedIds.add(it.id) }
        }
        if (matchingItems.isNotEmpty()) {
            resultSections.add(
                LibrarySection(
                    type = section.type,
                    displayTitle = section.displayTitle,
                    items = matchingItems,
                ),
            )
        }
    }

    // Place any downloaded items that were not in saved sections into type-based sections
    val unplacedItems = downloadedLibraryItems.filter { it.id !in placedIds }
    if (unplacedItems.isNotEmpty()) {
        val unplacedByType = unplacedItems.groupBy { it.type }
        for ((type, items) in unplacedByType) {
            val existingIndex = resultSections.indexOfFirst { it.type == type }
            if (existingIndex >= 0) {
                val existingSection = resultSections[existingIndex]
                val existingIds = existingSection.items.map { it.id }.toSet()
                val combined = existingSection.items + items.filter { it.id !in existingIds }
                resultSections[existingIndex] = existingSection.copy(items = combined)
            } else {
                resultSections.add(
                    LibrarySection(
                        type = type,
                        displayTitle = type.toLibraryDisplayTitle(),
                        items = items.sortedByDescending { it.savedAtEpochMs },
                    ),
                )
            }
        }
    }

    return resultSections
}

internal fun getActiveDownloadShelfItem(
    item: LibraryItem,
    allDownloads: List<DownloadItem>,
): ActiveDownloadShelfItem? {
    if (item.type == "series" || item.type == "tv") {
        val showEpisodes = allDownloads
            .filter { it.isEpisode && it.parentMetaId == item.id }
            .sortedForSeriesDownloads()
        if (showEpisodes.isEmpty()) return null
        val totalCount = showEpisodes.size
        val completedCount = showEpisodes.count { it.status == DownloadStatus.Completed }
        val downloadingCount = showEpisodes.count { it.status == DownloadStatus.Downloading }
        val queuedCount = showEpisodes.count { it.status == DownloadStatus.Queued }
        val isAnyActive = downloadingCount > 0 || queuedCount > 0 || showEpisodes.any { it.status == DownloadStatus.Paused }

        if (!isAnyActive || completedCount == totalCount) return null

        val showStatus = when {
            downloadingCount > 0 -> DownloadStatus.Downloading
            queuedCount > 0 -> DownloadStatus.Queued
            else -> DownloadStatus.Paused
        }

        val episodeProgresses = showEpisodes.map { ep ->
            when (ep.status) {
                DownloadStatus.Completed -> 1f
                DownloadStatus.Downloading, DownloadStatus.Paused -> ep.progressFraction
                else -> 0f
            }
        }
        // Total progress: (completed episodes + current in-progress episodes) / total episodes
        val inProgressSum = showEpisodes
            .filter { it.status != DownloadStatus.Completed }
            .sumOf { ep ->
                when (ep.status) {
                    DownloadStatus.Downloading, DownloadStatus.Paused -> ep.progressFraction.toDouble().coerceIn(0.0, 1.0)
                    else -> 0.0
                }
            }.toFloat()

        val overallProgress = if (totalCount > 0) {
            ((completedCount.toFloat() + inProgressSum) / totalCount.toFloat()).coerceIn(0f, 1f)
        } else 0f

        return ActiveDownloadShelfItem(
            id = "active_show_${item.id}",
            title = item.name,
            poster = item.poster,
            progressFraction = overallProgress,
            status = showStatus,
            isShow = true,
            parentMetaId = item.id,
            rawItem = showEpisodes.firstOrNull(),
            episodeProgresses = episodeProgresses,
            completedEpisodeCount = completedCount,
            totalEpisodeCount = totalCount,
            downloadingEpisodeCount = downloadingCount,
            queuedEpisodeCount = queuedCount,
        )
    } else {
        val movie = allDownloads.firstOrNull { !it.isEpisode && (it.id == item.id || it.parentMetaId == item.id) }
            ?: return null
        if (movie.status == DownloadStatus.Completed) return null

        return ActiveDownloadShelfItem(
            id = movie.id,
            title = item.name.ifBlank { movie.title },
            poster = item.poster ?: movie.poster,
            progressFraction = movie.progressFraction,
            status = movie.status,
            isShow = false,
            parentMetaId = movie.parentMetaId,
            rawItem = movie,
            episodeProgresses = listOf(movie.progressFraction),
            completedEpisodeCount = if (movie.status == DownloadStatus.Completed) 1 else 0,
            totalEpisodeCount = 1,
        )
    }
}

@Composable
internal fun ActiveDownloadPosterCard(
    item: ActiveDownloadShelfItem,
    posterPattern: String,
    onClick: () -> Unit,
    onPauseResume: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val tokens = MaterialTheme.nuvio
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadiusDp.dp)
    val cardWidth = NuvioPosterShape.Poster.cardWidth(basePosterWidthDp = posterCardStyle.widthDp)
    val effectivePoster = if (posterPattern.isNotBlank() && item.poster != null) {
        MetaPreview(id = item.id, type = if (item.isShow) "series" else "movie", name = item.title, poster = item.poster)
            .withCustomPosterUrl(posterPattern).poster
    } else item.poster

    val animatedProgress by animateFloatAsState(
        targetValue = item.progressFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "activeDownloadProgress",
    )
    val animatedPercentage = (animatedProgress * 100).toInt().coerceIn(0, 100)

    val segmentCount = if (item.isShow && item.episodeProgresses.isNotEmpty()) {
        item.episodeProgresses.size
    } else {
        1
    }

    val activeDownloadingIndex = if (item.isShow && item.episodeProgresses.isNotEmpty()) {
        val idx = item.episodeProgresses.indexOfFirst { it > 0f && it < 1f }
        if (idx >= 0) idx else item.episodeProgresses.indexOfFirst { it < 1f }.coerceAtLeast(0)
    } else {
        0
    }

    val animatedProgresses = if (item.isShow && item.episodeProgresses.isNotEmpty()) {
        item.episodeProgresses.mapIndexed { index, targetVal ->
            key(index) {
                val anim by animateFloatAsState(
                    targetValue = targetVal.coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 400),
                    label = "epSegProgress_$index",
                )
                anim
            }
        }
    } else {
        listOf(animatedProgress)
    }

    val grayscaleMatrix = remember { ColorMatrix().apply { setToSaturation(0f) } }

    val colModifier = if (fillMaxWidth) modifier.fillMaxWidth() else modifier.width(cardWidth)

    Column(
        modifier = colModifier,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(NuvioPosterShape.Poster.aspectRatio)
                .clip(cardShape)
                .background(tokens.colors.surface)
                .nuvioCardDepth(shape = cardShape, surface = NuvioCardDepthSurface.Posters)
                .clickable(onClick = onClick),
        ) {
            val totalWidth = maxWidth
            val totalHeight = maxHeight

            if (effectivePoster != null) {
                // 1. Grayscale base layer (uncompleted portion)
                AsyncImage(
                    model = effectivePoster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(grayscaleMatrix),
                    modifier = Modifier.fillMaxSize(),
                )

                // Darkening tint over grayscale base
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (item.status == DownloadStatus.Paused) 0.5f else 0.35f)),
                )

                // 2. Full-color descending reveal layer (segmented by episode without distinct separator lines)
                val segmentHeight = totalHeight / segmentCount.toFloat()
                for (i in 0 until segmentCount) {
                    val segTop = segmentHeight * i.toFloat()
                    val segProgress = animatedProgresses.getOrElse(i) { 0f }
                    val revealH = segmentHeight * segProgress.coerceIn(0f, 1f)

                    if (revealH > 0.5.dp) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .offset(y = segTop)
                                .height(revealH)
                                .clipToBounds(),
                        ) {
                            AsyncImage(
                                model = effectivePoster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(totalWidth)
                                    .height(totalHeight)
                                    .offset(y = -segTop),
                            )

                            // Glowing descending waterline boundary (only on incomplete active segments)
                            if (segProgress < 0.999f) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(2.5.dp)
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = if (item.status == DownloadStatus.Paused) {
                                                    listOf(
                                                        Color.Transparent,
                                                        MaterialTheme.colorScheme.tertiary,
                                                        Color.Transparent,
                                                    )
                                                } else {
                                                    listOf(
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                        MaterialTheme.colorScheme.primary,
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                    )
                                                },
                                            ),
                                        ),
                                )
                            }
                        }
                    } else if (item.status == DownloadStatus.Downloading && i == activeDownloadingIndex) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .offset(y = segTop)
                                .height(2.5.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                        ),
                                    ),
                                ),
                        )
                    }
                }
            } else {
                // Placeholder when no poster image exists
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (item.status == DownloadStatus.Queued) Icons.Rounded.Schedule else Icons.Rounded.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }

                val segmentHeight = totalHeight / segmentCount.toFloat()
                for (i in 0 until segmentCount) {
                    val segTop = segmentHeight * i.toFloat()
                    val segProgress = animatedProgresses.getOrElse(i) { 0f }
                    val revealH = segmentHeight * segProgress.coerceIn(0f, 1f)

                    if (revealH > 0.5.dp) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .offset(y = segTop)
                                .height(revealH)
                                .clipToBounds()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                        ) {
                            if (segProgress < 0.999f) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(2.5.dp)
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                        }
                    } else if (item.status == DownloadStatus.Downloading && i == activeDownloadingIndex) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .offset(y = segTop)
                                .height(2.5.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }

            // Top Status Badges and Controls
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
            ) {
                // Left badge (Progress and Status - merged)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (item.status) {
                        DownloadStatus.Downloading -> MaterialTheme.colorScheme.primary
                        DownloadStatus.Queued -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
                        DownloadStatus.Paused -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
                        else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
                    },
                    border = if (item.status == DownloadStatus.Queued) {
                        androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    } else null,
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when (item.status) {
                            DownloadStatus.Downloading -> {
                                if (animatedProgress > 0f) {
                                    CircularProgressIndicator(
                                        progress = { animatedProgress },
                                        modifier = Modifier.size(10.dp),
                                        strokeWidth = 1.75.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f),
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(10.dp),
                                        strokeWidth = 1.75.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                            DownloadStatus.Queued -> {
                                Icon(
                                    imageVector = Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            DownloadStatus.Paused -> {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            else -> null
                        }
                        val statusBadgeText = when (item.status) {
                            DownloadStatus.Downloading -> {
                                if (item.isShow && item.totalEpisodeCount > 0) {
                                    stringResource(Res.string.downloads_progress_episodes, item.completedEpisodeCount, item.totalEpisodeCount)
                                } else if (item.progressFraction <= 0f) {
                                    stringResource(Res.string.downloads_connecting)
                                } else {
                                    "$animatedPercentage%"
                                }
                            }
                            DownloadStatus.Queued -> {
                                if (item.isShow && item.totalEpisodeCount > 0) {
                                    "${item.completedEpisodeCount}/${item.totalEpisodeCount} ${stringResource(Res.string.downloads_status_queued)}"
                                } else {
                                    stringResource(Res.string.downloads_status_queued)
                                }
                            }
                            DownloadStatus.Paused -> {
                                if (item.isShow && item.totalEpisodeCount > 0) {
                                    "${item.completedEpisodeCount}/${item.totalEpisodeCount} ${stringResource(Res.string.downloads_badge_paused)}"
                                } else if (item.progressFraction > 0f) {
                                    "$animatedPercentage%"
                                } else {
                                    stringResource(Res.string.downloads_badge_paused)
                                }
                            }
                            DownloadStatus.Failed -> stringResource(Res.string.downloads_status_failed)
                            else -> stringResource(Res.string.downloads_section_active)
                        }
                        Text(
                            text = statusBadgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = when (item.status) {
                                DownloadStatus.Downloading -> MaterialTheme.colorScheme.onPrimary
                                DownloadStatus.Queued -> MaterialTheme.colorScheme.primary
                                DownloadStatus.Paused -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }

                // Quick Pause/Resume button on Top-Right
                if (onPauseResume != null && (item.status == DownloadStatus.Downloading || item.status == DownloadStatus.Paused || item.status == DownloadStatus.Queued)) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(26.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onPauseResume),
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.65f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (item.status == DownloadStatus.Downloading || item.status == DownloadStatus.Queued) {
                                    Icons.Rounded.Pause
                                } else {
                                    Icons.Rounded.PlayArrow
                                },
                                contentDescription = stringResource(
                                    if (item.status == DownloadStatus.Downloading) Res.string.downloads_action_pause else Res.string.action_resume,
                                ),
                                modifier = Modifier.size(14.dp),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }

            // Bottom Progress overlay for downloading (only for single items/movies)
            if (item.status == DownloadStatus.Downloading && !item.isShow) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                            ),
                        )
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    if (item.progressFraction > 0f) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }

        // Title and Subtitle below poster
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.isShow && item.totalEpisodeCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.8.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            Res.string.downloads_episodes_out_of_downloaded,
                            item.completedEpisodeCount,
                            item.totalEpisodeCount,
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = if (item.status == DownloadStatus.Queued) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = item.subtitleText(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.status == DownloadStatus.Queued) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActiveDownloadShelfItem.subtitleText(): String {
    if (isShow) {
        return downloadActivitySummary(downloading = downloadingEpisodeCount, queued = queuedEpisodeCount)
            ?: episodeCountText(totalEpisodeCount)
    }
    val movie = rawItem ?: return ""
    return when (status) {
        DownloadStatus.Downloading -> formatDownloadBadgeProgress(movie.downloadedBytes, movie.totalBytes)
        DownloadStatus.Queued -> stringResource(Res.string.downloads_status_queued)
        DownloadStatus.Paused -> stringResource(Res.string.downloads_badge_paused)
        DownloadStatus.Failed -> stringResource(Res.string.downloads_status_failed)
        DownloadStatus.Completed -> ""
    }
}
