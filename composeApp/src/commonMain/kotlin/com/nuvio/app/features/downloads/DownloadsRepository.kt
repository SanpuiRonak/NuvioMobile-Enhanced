package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.SubtitleRequestExtras
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
    private val offlineMetadata = OfflineShowMetadataStore()
    private var hasLoaded = false
    private var isObservingInputs = false
    private var nextDownloadOrdinal = 0L

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
        observeInputs()
    }

    /**
     * Restarts queued downloads when a limit is raised or the network allows them again,
     * and keeps offline details current whenever a downloaded title's details are opened.
     */
    private fun observeInputs() {
        if (isObservingInputs) return
        isObservingInputs = true
        combine(
            DownloadsSettingsRepository.concurrencyLimits,
            DownloadsSettingsRepository.allowMobileDataDownloads,
            NetworkStatusRepository.uiState,
        ) { _, _, _ -> }
            .onEach {
                if (!hasLoaded) return@onEach
                if (!isDownloadNetworkAllowed()) {
                    _uiState.value.items.filter { it.status == DownloadStatus.Downloading }.forEach { item ->
                        activeHandles.remove(item.id)?.cancel()
                        mutateItem(item.id) { current ->
                            if (current.status == DownloadStatus.Downloading) current.copy(status = DownloadStatus.Queued) else current
                        }
                    }
                    persist()
                }
                advanceDownloadQueue()
            }
            .launchIn(scope)
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
        offlineMetadata.clear()
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun getShowMetadata(showId: String): StoredDownloadedShowMeta? {
        ensureLoaded()
        return offlineMetadata[showId]
    }

    fun getSavedMetaDetails(showId: String): MetaDetails? {
        ensureLoaded()
        val showItems = _uiState.value.items.filter { it.parentMetaId == showId }
        return offlineMetadata[showId]?.toMetaDetails(showItems)
            ?: showItems.takeIf { it.isNotEmpty() }?.toOfflineMetaDetails(showId)
    }

    /** Keeps the offline copy of [meta] current, but only for titles that have downloads. */
    fun onMetaLoaded(meta: MetaDetails) {
        if (!hasLoaded) return
        val hasDownloads = _uiState.value.items.any { it.parentMetaId == meta.id }
        if (!hasDownloads && offlineMetadata[meta.id] == null) return
        if (offlineMetadata.merge(meta)) persist()
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
        showMeta: MetaDetails? = null,
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

        val meta = showMeta ?: MetaDetailsRepository.peek(parentMetaType, parentMetaId)
        val resolvedEpisodeThumbnail = episodeThumbnail ?: meta?.videos
            ?.firstOrNull { it.season == seasonNumber && it.episode == episodeNumber }
            ?.thumbnail
        if (meta != null) {
            offlineMetadata.merge(meta)
        } else {
            offlineMetadata.recordBasic(
                showId = parentMetaId,
                type = parentMetaType,
                title = title,
                logo = logo,
                poster = poster,
                background = background,
                episode = if (seasonNumber != null && episodeNumber != null) {
                    StoredDownloadedVideoMeta(
                        id = videoId,
                        title = episodeTitle.orEmpty(),
                        thumbnail = resolvedEpisodeThumbnail,
                        season = seasonNumber,
                        episode = episodeNumber,
                        overview = episodeOverview,
                    )
                } else {
                    null
                },
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

        val queuedItem = DownloadItem(
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
            status = DownloadStatus.Queued,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        val canStart = DownloadQueuePolicy.current().canStart(
            showKey = queuedItem.showKey,
            downloading = currentItems.filter { it.status == DownloadStatus.Downloading },
        )
        val item = if (canStart) queuedItem.copy(status = DownloadStatus.Downloading) else queuedItem

        currentItems.add(0, item)
        publish(currentItems)
        persist()
        if (item.status == DownloadStatus.Downloading) {
            startDownload(item)
        }

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    /** Starts queued downloads until the concurrency limits or network policy stop it. */
    fun advanceDownloadQueue() {
        ensureLoaded()
        val policy = DownloadQueuePolicy.current()
        while (true) {
            val next = policy.nextToStart(_uiState.value.items) ?: break
            mutateItem(next.id) { current ->
                current.copy(
                    status = DownloadStatus.Downloading,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    errorMessage = null,
                )
            }
            _uiState.value.items.firstOrNull { it.id == next.id }?.let(::startDownload)
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

    /** Pauses the download if it is running or queued; otherwise resumes or retries it. */
    fun togglePause(downloadId: String) {
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.isActive) pauseDownload(downloadId) else resumeDownload(downloadId)
    }

    /** Pauses every active episode of [showId], or resumes them all when none is active. */
    fun toggleShowPause(showId: String) {
        val isAnyActive = _uiState.value.items.any { it.parentMetaId == showId && it.isActive }
        if (isAnyActive) pauseSeriesDownloads(showId) else resumeOrRetrySeriesDownloads(showId)
    }

    private fun pauseSeriesDownloads(seriesId: String) {
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

    /** Resumes paused episodes of [seriesId] and retries its failed ones. */
    private fun resumeOrRetrySeriesDownloads(seriesId: String) {
        ensureLoaded()
        _uiState.value.items
            .filter { it.parentMetaId == seriesId && it.isResumable }
            .sortedForSeriesDownloads()
            .forEach { resumeDownload(it.id) }
    }

    /** Resumes every paused download and retries every failed one. */
    fun resumeOrRetryAllDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.isResumable }
            .map { it.id }
            .forEach(::resumeDownload)
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (!item.isResumable) return

        val canStart = DownloadQueuePolicy.current().canStart(
            showKey = item.showKey,
            downloading = _uiState.value.items.filter { it.status == DownloadStatus.Downloading && it.id != downloadId },
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
        pauseActiveDownloadsMatching { it.id in downloadIds }
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

        val remainingItems = _uiState.value.items.filterNot { it.id == downloadId }
        if (remainingItems.none { it.parentMetaId == item.parentMetaId }) {
            offlineMetadata.remove(item.parentMetaId)
        }
        publish(remainingItems)
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
            offlineMetadata.clear()
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }

        val decoded = DownloadsCodec.decodePayload(payload)
        offlineMetadata.replaceAll(decoded.showMetadata)

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

        val restorePolicy = DownloadQueuePolicy.current()
        val keptDownloading = mutableListOf<DownloadItem>()

        val queuedNormalized = normalized.map { item ->
            if (item.status == DownloadStatus.Downloading) {
                if (restorePolicy.canStart(item.showKey, keptDownloading)) {
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
        queuedNormalized
            .filter { it.status == DownloadStatus.Downloading && it.id !in activeHandles }
            .forEach(::startDownload)
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
            DownloadsCodec.encodePayload(_uiState.value.items, offlineMetadata.snapshot()),
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

    fun encodePayload(
        items: Collection<DownloadItem>,
        showMetadata: Map<String, StoredDownloadedShowMeta>,
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
