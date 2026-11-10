package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.SubtitleRequestExtras
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val _hasUnseenCompleted = MutableStateFlow(false)
    val hasUnseenCompleted: StateFlow<Boolean> = _hasUnseenCompleted.asStateFlow()

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
    private val showMetadata = mutableMapOf<String, StoredDownloadedShowMeta>()
    private var hasLoaded = false
    private var nextDownloadOrdinal = 0L

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun markCompletedSeen() {
        _hasUnseenCompleted.value = false
    }

    fun clearLocalState() {
        activeHandles.values.forEach(DownloadsTaskHandle::cancel)
        activeHandles.clear()
        hasLoaded = false
        _hasUnseenCompleted.value = false
        showMetadata.clear()
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun getShowMetadata(showId: String): StoredDownloadedShowMeta? {
        ensureLoaded()
        return showMetadata[showId]
    }

    fun getSavedMetaDetails(showId: String): MetaDetails? {
        ensureLoaded()
        val showItems = _uiState.value.items.filter { it.parentMetaId == showId }
        return showMetadata[showId]?.toMetaDetails(showItems)
            ?: showItems.takeIf { it.isNotEmpty() }?.toOfflineMetaDetails(showId)
    }

    /** Keeps the offline copy of [meta] current, but only for titles that have downloads. */
    fun refreshShowMetadataIfDownloaded(meta: MetaDetails) {
        if (hasDownloadsFor(meta.id) || getShowMetadata(meta.id) != null) {
            updateShowMetadataFromMetaDetails(meta)
        }
    }

    fun hasDownloadsFor(showId: String): Boolean {
        ensureLoaded()
        return _uiState.value.items.any { it.parentMetaId == showId }
    }

    fun findEpisodeDownload(
        parentMetaId: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()
        val normalizedVideoId = videoId?.trim().orEmpty()

        val matching = items.filter { item ->
            (normalizedVideoId.isNotBlank() && item.videoId == normalizedVideoId) ||
                (seasonNumber != null && episodeNumber != null &&
                    item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber) ||
                (seasonNumber == null && episodeNumber == null &&
                    item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null)
        }

        return matching.firstOrNull { it.status == DownloadStatus.Downloading }
            ?: matching.firstOrNull { it.status == DownloadStatus.Queued }
            ?: matching.firstOrNull { it.status == DownloadStatus.Completed && it.hasPlayableLocalFile() }
            ?: matching.firstOrNull { it.status == DownloadStatus.Completed }
            ?: matching.firstOrNull()
    }

    fun updateShowMetadataFromMetaDetails(meta: MetaDetails) {
        ensureLoaded()
        val existing = showMetadata[meta.id]
        val videosToSave = if (meta.videos.isNotEmpty()) {
            meta.videos.map { it.toStored() }
        } else {
            existing?.videos ?: emptyList()
        }
        val castToSave = if (meta.cast.isNotEmpty()) {
            meta.cast.map { it.toStored() }
        } else {
            existing?.cast ?: emptyList()
        }
        val updated = StoredDownloadedShowMeta(
            showId = meta.id,
            title = meta.name,
            type = meta.type,
            poster = meta.poster ?: existing?.poster,
            background = meta.background ?: existing?.background,
            logo = meta.logo ?: existing?.logo,
            description = meta.description ?: existing?.description,
            releaseInfo = meta.releaseInfo ?: existing?.releaseInfo,
            status = meta.status ?: existing?.status,
            imdbRating = meta.imdbRating ?: existing?.imdbRating,
            genres = meta.genres.takeIf { it.isNotEmpty() } ?: existing?.genres ?: emptyList(),
            seasonPosters = (existing?.seasonPosters.orEmpty() + meta.seasonPosters),
            videos = videosToSave,
            cast = castToSave,
        )
        showMetadata[meta.id] = updated

        var changed = false
        val currentItems = _uiState.value.items.map { item ->
            if (item.parentMetaId == meta.id && item.isEpisode) {
                val video = meta.videos.firstOrNull {
                    it.season == item.seasonNumber && it.episode == item.episodeNumber
                }
                var updatedItem = item
                if (item.showDescription == null && meta.description != null) {
                    updatedItem = updatedItem.copy(showDescription = meta.description)
                    changed = true
                }
                if (item.showGenres.isEmpty() && meta.genres.isNotEmpty()) {
                    updatedItem = updatedItem.copy(showGenres = meta.genres)
                    changed = true
                }
                if (item.showReleaseInfo == null && meta.releaseInfo != null) {
                    updatedItem = updatedItem.copy(showReleaseInfo = meta.releaseInfo)
                    changed = true
                }
                if (item.showImdbRating == null && meta.imdbRating != null) {
                    updatedItem = updatedItem.copy(showImdbRating = meta.imdbRating)
                    changed = true
                }
                if (item.showStatus == null && meta.status != null) {
                    updatedItem = updatedItem.copy(showStatus = meta.status)
                    changed = true
                }
                if (video != null) {
                    if (item.episodeOverview == null && video.overview != null) {
                        updatedItem = updatedItem.copy(episodeOverview = video.overview)
                        changed = true
                    }
                    if (item.episodeReleased == null && video.released != null) {
                        updatedItem = updatedItem.copy(episodeReleased = video.released)
                        changed = true
                    }
                    if (item.episodeRuntime == null && video.runtime != null) {
                        updatedItem = updatedItem.copy(episodeRuntime = video.runtime)
                        changed = true
                    }
                    if (item.episodeImdbRating == null && video.imdbRating != null) {
                        updatedItem = updatedItem.copy(episodeImdbRating = video.imdbRating)
                        changed = true
                    }
                    if (item.episodeTmdbRating == null && video.tmdbRating != null) {
                        updatedItem = updatedItem.copy(episodeTmdbRating = video.tmdbRating)
                        changed = true
                    }
                    if (item.episodeRating == null && video.rating != null) {
                        updatedItem = updatedItem.copy(episodeRating = video.rating)
                        changed = true
                    }
                    val seasonPoster = video.seasonPoster ?: meta.seasonPosters[item.seasonNumber]
                    if (item.seasonPoster == null && seasonPoster != null) {
                        updatedItem = updatedItem.copy(seasonPoster = seasonPoster)
                        changed = true
                    }
                    if (item.episodeThumbnail == null && video.thumbnail != null) {
                        updatedItem = updatedItem.copy(episodeThumbnail = video.thumbnail)
                        changed = true
                    }
                }
                updatedItem
            } else {
                item
            }
        }
        if (changed) {
            publish(currentItems)
        }
        persist()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (seasonNumber != null && episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    item.hasPlayableLocalFile()
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    item.hasPlayableLocalFile()
            }
        }
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            mutateItem(item.id) { current ->
                if (current.fileName == item.fileName) {
                    current.copy(
                        localFileUri = resolvedUri,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
        episodeOverview: String? = null,
        episodeReleased: String? = null,
        episodeRuntime: Int? = null,
        episodeImdbRating: Double? = null,
        episodeTmdbRating: Double? = null,
        episodeRating: Double? = null,
        seasonPoster: String? = null,
        showDescription: String? = null,
        showGenres: List<String> = emptyList(),
        showReleaseInfo: String? = null,
        showImdbRating: String? = null,
        showStatus: String? = null,
    ): DownloadEnqueueResult {
        ensureLoaded()

        val sourceUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return DownloadEnqueueResult.MissingUrl

        if (!sourceUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = buildLogicalKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        val cachedMeta = MetaDetailsRepository.peek(parentMetaType, parentMetaId)
        val cachedVideo = cachedMeta?.videos?.firstOrNull {
            it.season == seasonNumber && it.episode == episodeNumber
        }

        val resolvedEpisodeOverview = episodeOverview ?: cachedVideo?.overview
        val resolvedEpisodeReleased = episodeReleased ?: cachedVideo?.released
        val resolvedEpisodeRuntime = episodeRuntime ?: cachedVideo?.runtime
        val resolvedEpisodeImdbRating = episodeImdbRating ?: cachedVideo?.imdbRating
        val resolvedEpisodeTmdbRating = episodeTmdbRating ?: cachedVideo?.tmdbRating
        val resolvedEpisodeRating = episodeRating ?: cachedVideo?.rating ?: cachedVideo?.tmdbRating ?: cachedVideo?.imdbRating
        val resolvedSeasonPoster = seasonPoster ?: cachedVideo?.seasonPoster ?: seasonNumber?.let { cachedMeta?.seasonPosters?.get(it) }
        val resolvedEpisodeThumbnail = episodeThumbnail ?: cachedVideo?.thumbnail

        val resolvedShowDescription = showDescription ?: cachedMeta?.description
        val resolvedShowGenres = showGenres.takeIf { it.isNotEmpty() } ?: cachedMeta?.genres ?: emptyList()
        val resolvedShowReleaseInfo = showReleaseInfo ?: cachedMeta?.releaseInfo
        val resolvedShowImdbRating = showImdbRating ?: cachedMeta?.imdbRating
        val resolvedShowStatus = showStatus ?: cachedMeta?.status

        // Cache show metadata offline
        if (cachedMeta != null) {
            updateShowMetadataFromMetaDetails(cachedMeta)
        } else {
            val existing = showMetadata[parentMetaId]
            showMetadata[parentMetaId] = StoredDownloadedShowMeta(
                showId = parentMetaId,
                title = title,
                type = parentMetaType,
                poster = poster ?: existing?.poster,
                background = background ?: existing?.background,
                logo = logo ?: existing?.logo,
                description = resolvedShowDescription ?: existing?.description,
                releaseInfo = resolvedShowReleaseInfo ?: existing?.releaseInfo,
                status = resolvedShowStatus ?: existing?.status,
                imdbRating = resolvedShowImdbRating ?: existing?.imdbRating,
                genres = resolvedShowGenres.takeIf { it.isNotEmpty() } ?: existing?.genres ?: emptyList(),
                seasonPosters = existing?.seasonPosters.orEmpty() +
                    listOfNotNull(seasonNumber?.let { season -> resolvedSeasonPoster?.let { season to it } }),
                videos = existing?.videos.orEmpty(),
                cast = existing?.cast.orEmpty(),
            )
        }

        var replacedExisting = false
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            activeHandles.remove(existing.id)?.cancel()
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(existing.fileName)
            currentItems.removeAll { it.id == existing.id }
        }

        val downloadId = nextDownloadId(now)
        val fileName = buildFileName(
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            fallbackTitle = stream.streamLabel,
            sourceUrl = sourceUrl,
            downloadId = downloadId,
        )

        val showKey = parentMetaId.takeIf { seasonNumber != null && episodeNumber != null && it.isNotBlank() }
        val canStart = canStartDownload(
            showKey = showKey,
            downloading = currentItems.filter { it.status == DownloadStatus.Downloading },
            limits = currentDownloadLimits(),
        )

        val targetStatus = if (canStart) DownloadStatus.Downloading else DownloadStatus.Queued

        val item = DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = resolvedEpisodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            subtitleRequests = addonSubtitleRequests(
                type = contentType,
                videoId = videoId,
                extras = SubtitleRequestExtras.from(stream.behaviorHints, sourceUrl),
            ),
            sourceSubtitles = stream.externalSubtitles,
            localFileUri = null,
            fileName = fileName,
            status = targetStatus,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            episodeOverview = resolvedEpisodeOverview,
            episodeReleased = resolvedEpisodeReleased,
            episodeRuntime = resolvedEpisodeRuntime,
            episodeImdbRating = resolvedEpisodeImdbRating,
            episodeTmdbRating = resolvedEpisodeTmdbRating,
            episodeRating = resolvedEpisodeRating,
            seasonPoster = resolvedSeasonPoster,
            showDescription = resolvedShowDescription,
            showGenres = resolvedShowGenres,
            showReleaseInfo = resolvedShowReleaseInfo,
            showImdbRating = resolvedShowImdbRating,
            showStatus = resolvedShowStatus,
        )

        currentItems.add(0, item)
        publish(currentItems)
        persist()
        if (targetStatus == DownloadStatus.Downloading) {
            startDownload(item)
        }

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    fun advanceDownloadQueue() {
        ensureLoaded()
        val limits = currentDownloadLimits()

        while (true) {
            val currentItems = _uiState.value.items
            val currentDownloading = currentItems.filter { it.status == DownloadStatus.Downloading }
            if (currentDownloading.size >= limits.maxDownloads) break

            val queuedItems = currentItems
                .filter { it.status == DownloadStatus.Queued }
                .sortedWith(
                    compareBy<DownloadItem> { it.createdAtEpochMs }
                        .thenBy { it.seasonNumber ?: 0 }
                        .thenBy { it.episodeNumber ?: 0 }
                        .thenBy { it.id }
                )

            val nextQueued = queuedItems.firstOrNull { candidate ->
                canStartDownload(candidate.showKey, currentDownloading, limits)
            } ?: break

            mutateItem(nextQueued.id) { current ->
                current.copy(
                    status = DownloadStatus.Downloading,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    errorMessage = null,
                )
            }
            val itemToStart = _uiState.value.items.firstOrNull { it.id == nextQueued.id }
            if (itemToStart != null) {
                startDownload(itemToStart)
            }
        }
    }

    fun pauseDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading && item.status != DownloadStatus.Queued) return

        val wasDownloading = item.status == DownloadStatus.Downloading
        activeHandles.remove(downloadId)?.cancel()
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                errorMessage = null,
            )
        }
        if (wasDownloading) {
            advanceDownloadQueue()
        }
    }

    fun pauseActiveDownloads() {
        pauseActiveDownloadsMatching { true }
    }

    fun pauseSeriesDownloads(seriesId: String) {
        pauseActiveDownloadsMatching { it.parentMetaId == seriesId }
    }

    /**
     * Pauses queued items before running ones, so pausing a running download cannot
     * promote a queued item that is about to be paused as well.
     */
    private fun pauseActiveDownloadsMatching(predicate: (DownloadItem) -> Boolean) {
        ensureLoaded()
        _uiState.value.items
            .filter { it.isActive && predicate(it) }
            .sortedBy { it.status != DownloadStatus.Queued }
            .map { it.id }
            .forEach(::pauseDownload)
    }

    fun resumeSeriesDownloads(seriesId: String) {
        ensureLoaded()
        val pausedItems = _uiState.value.items
            .filter {
                it.parentMetaId == seriesId &&
                    (it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed)
            }
            .sortedForSeriesDownloads()

        pausedItems.forEach { item ->
            resumeDownload(item.id)
        }
    }

    fun resumeAllPausedDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed }
            .map { it.id }
            .forEach(::resumeDownload)
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        val canStart = canStartDownload(
            showKey = item.showKey,
            downloading = _uiState.value.items.filter { it.status == DownloadStatus.Downloading && it.id != downloadId },
            limits = currentDownloadLimits(),
        )

        val newStatus = if (canStart) DownloadStatus.Downloading else DownloadStatus.Queued

        val reset = item.copy(
            status = newStatus,
            errorMessage = null,
            localFileUri = null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persist()
        if (newStatus == DownloadStatus.Downloading) {
            startDownload(reset)
        }
        advanceDownloadQueue()
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    fun pauseDownloads(downloadIds: Collection<String>) {
        downloadIds.forEach(::pauseDownload)
    }

    fun resumeDownloads(downloadIds: Collection<String>) {
        downloadIds.forEach(::resumeDownload)
    }

    fun retryDownloads(downloadIds: Collection<String>) {
        downloadIds.forEach(::retryDownload)
    }

    fun retryAllFailedDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Failed }
            .map { it.id }
            .forEach(::retryDownload)
    }

    fun cancelDownloads(downloadIds: Collection<String>) {
        downloadIds.forEach(::cancelDownload)
    }

    internal fun reattachBackgroundDownload(downloadId: String) {
        if (!hasLoaded) return
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        activeHandles.remove(downloadId)?.cancel()
        val restored = DownloadsPlatformDownloader.restoreItem(item)
        replaceItem(restored)
        persist()
        if (restored.status == DownloadStatus.Downloading) startDownload(restored)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        val wasDownloading = item.status == DownloadStatus.Downloading

        activeHandles.remove(downloadId)?.cancel()
        DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
        DownloadsPlatformDownloader.removePartialFile(item.fileName)

        publish(_uiState.value.items.filterNot { it.id == downloadId })
        persist()

        if (wasDownloading) {
            advanceDownloadQueue()
        }
    }

    private fun loadFromDisk() {
        _hasUnseenCompleted.value = false
        hasLoaded = true
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            showMetadata.clear()
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }

        val decoded = DownloadsCodec.decodePayload(payload)
        showMetadata.clear()
        showMetadata.putAll(decoded.showMetadata)

        var shouldPersistNormalized = false
        val normalized = decoded.items
            .map { item ->
                val statusNormalized = DownloadsPlatformDownloader.restoreItem(item)

                val localUriNormalized = normalizeCompletedLocalFileUri(statusNormalized)
                if (localUriNormalized != item) {
                    shouldPersistNormalized = true
                }
                localUriNormalized
            }

        val limits = currentDownloadLimits()
        val keptDownloading = mutableListOf<DownloadItem>()

        val queuedNormalized = normalized.map { item ->
            if (item.status == DownloadStatus.Downloading) {
                if (canStartDownload(item.showKey, keptDownloading, limits)) {
                    keptDownloading += item
                    item
                } else {
                    shouldPersistNormalized = true
                    item.copy(status = DownloadStatus.Queued)
                }
            } else {
                item
            }
        }

        _uiState.value = DownloadsUiState(queuedNormalized)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) {
            persist()
        }
        val downloadingToStart = queuedNormalized.filter { it.status == DownloadStatus.Downloading && it.id !in activeHandles }
        downloadingToStart.forEach { startDownload(it) }
        advanceDownloadQueue()
    }

    private fun startDownload(item: DownloadItem) {
        val request = DownloadPlatformRequest(item)

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = { downloadedBytes, totalBytes ->
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            totalBytes = totalBytes?.takeIf { it > 0L },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            errorMessage = null,
                        )
                    }
                }
            },
            onSuccess = { localFileUri, totalBytes ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(
                        status = DownloadStatus.Completed,
                        localFileUri = localFileUri,
                        downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                            totalBytes
                        } else {
                            current.downloadedBytes
                        },
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        errorMessage = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
                advanceDownloadQueue()
            },
            onFailure = { message ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            status = DownloadStatus.Failed,
                            errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }
                advanceDownloadQueue()
            },
            onPaused = {
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(status = DownloadStatus.Paused, errorMessage = null)
                }
                advanceDownloadQueue()
            },
        )

        activeHandles[item.id] = handle
    }

    private fun mutateItem(downloadId: String, transform: (DownloadItem) -> DownloadItem) {
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }

        if (changed) {
            publish(updated)
            persist()
        }
    }

    private fun replaceItem(item: DownloadItem) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        val previousStatuses = _uiState.value.items.associate { it.id to it.status }
        if (items.any { it.status == DownloadStatus.Completed && previousStatuses[it.id].let { status -> status != null && status != DownloadStatus.Completed } }) {
            _hasUnseenCompleted.value = true
        }
        _uiState.value = DownloadsUiState(
            items = items,
        )
        notifyLiveStatusPlatform()
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun persist() {
        DownloadsStorage.savePayload(
            DownloadsCodec.encodeItems(_uiState.value.items, showMetadata),
        )
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null
}

private data class DownloadLimits(
    val maxDownloads: Int,
    val maxEpisodesPerShow: Int,
    val maxShows: Int,
)

private fun currentDownloadLimits(): DownloadLimits {
    DownloadsSettingsRepository.ensureLoaded()
    return DownloadLimits(
        maxDownloads = DownloadsSettingsRepository.maxDownloadConcurrency.value,
        maxEpisodesPerShow = DownloadsSettingsRepository.maxSeriesEpisodeConcurrency.value,
        maxShows = DownloadsSettingsRepository.maxConcurrentShows.value,
    )
}

/** The show an episode download counts against for per-show limits; null for movies. */
private val DownloadItem.showKey: String?
    get() = parentMetaId.takeIf { isEpisode && it.isNotBlank() }

/** Whether a download for [showKey] can start next to [downloading] without exceeding [limits]. */
private fun canStartDownload(
    showKey: String?,
    downloading: List<DownloadItem>,
    limits: DownloadLimits,
): Boolean {
    if (downloading.size >= limits.maxDownloads) return false
    if (showKey == null) return true
    val activeShows = downloading.mapNotNullTo(mutableSetOf()) { it.showKey }
    if (showKey !in activeShows && activeShows.size >= limits.maxShows) return false
    return downloading.count { it.showKey == showKey } < limits.maxEpisodesPerShow
}

@Serializable
private data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
    val showMetadata: Map<String, StoredDownloadedShowMeta> = emptyMap(),
)

private object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodePayload(payload: String): StoredDownloadsPayload =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload)
        }.getOrDefault(StoredDownloadsPayload())

    fun encodeItems(
        items: Collection<DownloadItem>,
        showMetadata: Map<String, StoredDownloadedShowMeta> = emptyMap(),
    ): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
                showMetadata = showMetadata,
            ),
        )
}

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

internal fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String,
    downloadId: String,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = sourceUrl.fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(downloadId)
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

internal fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
