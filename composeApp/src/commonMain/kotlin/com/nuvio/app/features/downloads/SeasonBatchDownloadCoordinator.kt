package com.nuvio.app.features.downloads

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamFacts
import com.nuvio.app.features.debrid.DebridStreamMetadata
import com.nuvio.app.features.debrid.DebridStreamPreferences
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.findEmbeddedStreams
import com.nuvio.app.features.details.normalizeSeasonNumber
import com.nuvio.app.features.details.seasonSortKey
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamSourceLoader
import com.nuvio.app.features.streams.StreamSourcePrefillSelector
import com.nuvio.app.features.streams.isResolverDependent
import com.nuvio.app.features.watched.releasedEpisodesForSeason
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class EpisodePlanStatus {
    AlreadyDownloaded,
    Loading,
    Ready,
    NoSourceFound,
}

data class PlannedEpisodeItem(
    val episode: MetaVideo,
    val isAlreadyDownloaded: Boolean,
    val streamCandidate: StreamItem? = null,
    val allAvailableStreams: List<StreamItem> = emptyList(),
    val resolutionLabel: String? = null,
    val sizeBytes: Long? = null,
    val status: EpisodePlanStatus = if (isAlreadyDownloaded) EpisodePlanStatus.AlreadyDownloaded else EpisodePlanStatus.Loading,
)

sealed interface BatchModalStep {
    data object EpisodeSelection : BatchModalStep
    data class SourceSelection(val seasonIndex: Int) : BatchModalStep
}

data class PlannedSeasonEpisodes(
    val season: Int,
    val episodes: List<PlannedEpisodeItem> = emptyList(),
    val isPlanning: Boolean = false,
) {
    val readyToDownloadCount: Int
        get() = episodes.count { it.status == EpisodePlanStatus.Ready && it.streamCandidate != null }
}

data class SeasonBatchPlan(
    val meta: MetaDetails,
    val season: Int,
    val allSeasons: List<Int> = listOf(season),
    val episodesBySeason: Map<Int, List<MetaVideo>> = emptyMap(),
    val selectedEpisodeKeys: Set<String> = emptySet(),
    val watchedEpisodeKeys: Set<String> = emptySet(),
    val step: BatchModalStep = BatchModalStep.EpisodeSelection,
    val activeSeasons: List<Int> = listOf(season),
    val currentPickerSeason: Int = season,
    val seasonPlans: Map<Int, PlannedSeasonEpisodes> = emptyMap(),
    val isSingleMedia: Boolean = false,
) {
    val totalSelectedCount: Int
        get() = selectedEpisodeKeys.size

    fun isSeasonSelected(season: Int): Boolean =
        episodesBySeason[season].orEmpty().any { batchEpisodeKey(season, it.episode ?: 0) in selectedEpisodeKeys }

    fun isEpisodeWatched(
        episode: MetaVideo,
        season: Int,
        isEpisodeWatched: (MetaVideo) -> Boolean,
    ): Boolean = isEpisodeWatched(episode) ||
        "${meta.id}:$season:${episode.episode}" in watchedEpisodeKeys ||
        episode.id in watchedEpisodeKeys
}

/** Result of [SeasonBatchDownloadCoordinator.queueConfirmedBatch]. */
enum class BatchQueueResult {
    Queued,
    NothingToQueue,
    RequiresWifi,
}

fun batchEpisodeKey(season: Int, episode: Int?): String =
    if (episode != null) "${season}_$episode" else "${season}_single"

/** True when the content already has a download that is finished, running, paused or waiting. */
private fun List<DownloadItem>.hasDownloadFor(logicalKey: String): Boolean =
    any { it.logicalContentKey == logicalKey && it.status != DownloadStatus.Failed }

fun isBatchEpisodeDownloaded(
    currentDownloads: List<DownloadItem>,
    metaId: String,
    episode: MetaVideo,
    season: Int,
): Boolean = currentDownloads.hasDownloadFor(
    buildLogicalKey(
        parentMetaId = metaId,
        seasonNumber = episode.episode?.let { episode.season ?: season },
        episodeNumber = episode.episode,
    ),
)

/** Short quality label for a stream, e.g. "1080p" or "WEB-DL"; null when nothing is known. */
internal fun DebridStreamFacts.qualityBadge(): String? =
    resolution.label.takeUnless { it == "Unknown" }
        ?: quality.name.replace("_", "-").takeUnless { it == "UNKNOWN" }

/** The ids needed to load and download one planned episode (or a movie). */
private data class BatchEpisodeTarget(
    val season: Int?,
    val episode: Int?,
    val streamVideoId: String,
) {
    val isSingleMedia: Boolean get() = episode == null
}

private fun MetaDetails.batchTargetFor(video: MetaVideo, planSeason: Int): BatchEpisodeTarget {
    val episode = video.episode
        ?: return BatchEpisodeTarget(
            season = null,
            episode = null,
            streamVideoId = video.id.takeIf { it.isNotBlank() && it != name } ?: id,
        )
    val season = video.season ?: planSeason
    val streamVideoId = video.id.takeIf { it.isNotBlank() } ?: buildPlaybackVideoId(
        parentMetaId = id,
        seasonNumber = season,
        episodeNumber = episode,
        fallbackVideoId = video.id,
    )
    return BatchEpisodeTarget(season = season, episode = episode, streamVideoId = streamVideoId)
}

/**
 * Plans and queues multi-episode (or single-title) downloads from the batch download dialog.
 */
object SeasonBatchDownloadCoordinator {
    private const val SOURCE_LOAD_PARALLELISM = 3
    private const val SOURCE_LOAD_TIMEOUT_MS = 75_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _activePlan = MutableStateFlow<SeasonBatchPlan?>(null)
    val activePlan: StateFlow<SeasonBatchPlan?> = _activePlan.asStateFlow()

    /** Parent of the open plan's source loading; cancelled when the plan closes. */
    private var planJob: Job? = null

    /** Parent of every running queue job; replaced when queued work must stop. */
    private var queueJob = SupervisorJob()

    fun prepareBatchPlan(
        meta: MetaDetails,
        season: Int = 1,
        watchedKeys: Set<String> = emptySet(),
    ) {
        cancelBatchPlan()
        planJob = SupervisorJob(scope.coroutineContext[Job])

        val isMovie = meta.type.equals("movie", ignoreCase = true)
        val hasEpisodes = meta.videos.any { it.season != null || it.episode != null }
        val isSingleMedia = isMovie || !hasEpisodes || meta.videos.count { it.episode != null } <= 1

        if (isSingleMedia) {
            val singleVideo = meta.videos.firstOrNull { it.episode != null }
                ?: meta.videos.firstOrNull()
                ?: MetaVideo(
                    id = meta.id,
                    title = meta.name,
                    thumbnail = meta.background ?: meta.poster,
                    season = null,
                    episode = null,
                )
            val targetSeason = singleVideo.season ?: season
            _activePlan.value = SeasonBatchPlan(
                meta = meta,
                season = targetSeason,
                episodesBySeason = mapOf(targetSeason to listOf(singleVideo)),
                selectedEpisodeKeys = setOf(batchEpisodeKey(targetSeason, singleVideo.episode)),
                watchedEpisodeKeys = watchedKeys,
                step = BatchModalStep.SourceSelection(seasonIndex = 0),
                isSingleMedia = true,
            )
            loadSourcesForSeason(targetSeason)
            return
        }

        val allSeasonNumbers = meta.videos
            .mapNotNull { it.season }
            .distinct()
            .sortedBy(::seasonSortKey)
            .ifEmpty { listOf(season) }

        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val episodesBySeason = allSeasonNumbers.associateWith { s ->
            meta.releasedEpisodesForSeason(s, todayIsoDate)
                .filter { it.episode != null }
                .distinctBy { Pair(normalizeSeasonNumber(it.season), it.episode) }
                .sortedBy { it.episode }
        }

        _activePlan.value = SeasonBatchPlan(
            meta = meta,
            season = season,
            allSeasons = allSeasonNumbers,
            episodesBySeason = episodesBySeason,
            watchedEpisodeKeys = watchedKeys,
            activeSeasons = allSeasonNumbers,
            currentPickerSeason = if (season in allSeasonNumbers) season else allSeasonNumbers.first(),
        )
    }

    fun setPickerSeason(season: Int) {
        _activePlan.update { it?.copy(currentPickerSeason = season) }
    }

    fun toggleEpisodeSelection(season: Int, episodeNumber: Int) {
        val key = batchEpisodeKey(season, episodeNumber)
        _activePlan.update { current ->
            current?.copy(
                selectedEpisodeKeys = if (key in current.selectedEpisodeKeys) {
                    current.selectedEpisodeKeys - key
                } else {
                    current.selectedEpisodeKeys + key
                },
            )
        }
    }

    fun selectAllForSeason(season: Int) {
        updateSeasonSelection(season) { _, isDownloaded -> !isDownloaded }
    }

    fun selectUnwatchedForSeason(
        season: Int,
        isEpisodeWatched: (MetaVideo) -> Boolean = { false },
    ) {
        _activePlan.value?.let { plan ->
            updateSeasonSelection(season) { episode, isDownloaded ->
                !isDownloaded && !plan.isEpisodeWatched(episode, season, isEpisodeWatched)
            }
        }
    }

    fun deselectAllForSeason(season: Int) {
        updateSeasonSelection(season) { _, _ -> false }
    }

    /** Sets each episode of [season] selected or not according to [shouldSelect]. */
    private fun updateSeasonSelection(
        season: Int,
        shouldSelect: (episode: MetaVideo, isDownloaded: Boolean) -> Boolean,
    ) {
        DownloadsRepository.ensureLoaded()
        val currentDownloads = DownloadsRepository.uiState.value.items
        _activePlan.update { current ->
            if (current == null) return@update null
            val keys = current.selectedEpisodeKeys.toMutableSet()
            for (episode in current.episodesBySeason[season].orEmpty()) {
                val key = batchEpisodeKey(season, episode.episode ?: continue)
                val isDownloaded = isBatchEpisodeDownloaded(currentDownloads, current.meta.id, episode, season)
                if (shouldSelect(episode, isDownloaded)) keys += key else keys -= key
            }
            current.copy(selectedEpisodeKeys = keys)
        }
    }

    fun continueToSourceSelection() {
        val plan = _activePlan.value ?: return
        val activeSeasons = plan.allSeasons.filter(plan::isSeasonSelected)
        if (activeSeasons.isEmpty()) return

        _activePlan.update {
            it?.copy(
                activeSeasons = activeSeasons,
                step = BatchModalStep.SourceSelection(seasonIndex = 0),
            )
        }
        loadSourcesForSeason(activeSeasons.first())
    }

    private fun loadSourcesForSeason(season: Int) {
        val plan = _activePlan.value ?: return
        val parentJob = planJob ?: return
        val existingPlan = plan.seasonPlans[season]
        if (existingPlan != null && !existingPlan.isPlanning && existingPlan.episodes.isNotEmpty()) return

        val selectedEpisodes = plan.episodesBySeason[season].orEmpty().filter { ep ->
            plan.isSingleMedia || batchEpisodeKey(season, ep.episode ?: 0) in plan.selectedEpisodeKeys
        }
        if (selectedEpisodes.isEmpty()) return

        DownloadsRepository.ensureLoaded()
        val currentDownloads = DownloadsRepository.uiState.value.items
        val initialItems = selectedEpisodes.map { ep ->
            PlannedEpisodeItem(
                episode = ep,
                isAlreadyDownloaded = isBatchEpisodeDownloaded(currentDownloads, plan.meta.id, ep, season),
            )
        }
        updateSeasonPlan(plan.meta.id, season) {
            PlannedSeasonEpisodes(season = season, episodes = initialItems, isPlanning = true)
        }

        scope.launch(parentJob) {
            val preferredResolution = DownloadsSettingsRepository.preferredResolution.value
            val semaphore = Semaphore(SOURCE_LOAD_PARALLELISM)
            selectedEpisodes.map { ep ->
                launch(Dispatchers.Default) {
                    semaphore.withPermit {
                        val target = plan.meta.batchTargetFor(ep, season)
                        val streams = runCatching {
                            StreamSourceLoader.loadStreams(
                                type = plan.meta.type,
                                videoId = target.streamVideoId,
                                season = target.season,
                                episode = target.episode,
                                embeddedStreams = plan.meta.findEmbeddedStreams(target.streamVideoId),
                                timeoutMs = SOURCE_LOAD_TIMEOUT_MS,
                            )
                        }.getOrElse { emptyList() }
                        val selection = StreamSourcePrefillSelector.selectForBatch(streams, preferredResolution)

                        updateEpisode(plan.meta.id, season, ep) { item ->
                            if (selection == null) {
                                item.copy(allAvailableStreams = streams, status = EpisodePlanStatus.NoSourceFound)
                            } else {
                                item.withSelectedStream(selection.stream, selection.facts)
                                    .copy(allAvailableStreams = streams)
                            }
                        }
                    }
                }
            }.joinAll()
            updateSeasonPlan(plan.meta.id, season) { it?.copy(isPlanning = false) }
        }
    }

    fun overrideEpisodeStream(season: Int, episode: MetaVideo, stream: StreamItem) {
        val plan = _activePlan.value ?: return
        val facts = DebridStreamMetadata.facts(stream, DebridStreamPreferences())
        updateEpisode(plan.meta.id, season, episode) { it.withSelectedStream(stream, facts) }
    }

    private fun PlannedEpisodeItem.withSelectedStream(stream: StreamItem, facts: DebridStreamFacts) = copy(
        streamCandidate = stream,
        resolutionLabel = facts.qualityBadge(),
        sizeBytes = facts.size?.takeIf { it > 0 },
        status = EpisodePlanStatus.Ready,
    )

    /**
     * Updates one season of the open plan. Ignored when the open plan is for another title,
     * so late results from a closed plan can't land in a new one.
     */
    private fun updateSeasonPlan(
        metaId: String,
        season: Int,
        transform: (PlannedSeasonEpisodes?) -> PlannedSeasonEpisodes?,
    ) {
        _activePlan.update { current ->
            if (current == null || current.meta.id != metaId) return@update current
            val updated = transform(current.seasonPlans[season]) ?: return@update current
            current.copy(seasonPlans = current.seasonPlans + (season to updated))
        }
    }

    private fun updateEpisode(
        metaId: String,
        season: Int,
        episode: MetaVideo,
        transform: (PlannedEpisodeItem) -> PlannedEpisodeItem,
    ) {
        updateSeasonPlan(metaId, season) { seasonPlan ->
            seasonPlan?.copy(
                episodes = seasonPlan.episodes.map { if (it.episode == episode) transform(it) else it },
            )
        }
    }

    fun goToSeasonIndex(index: Int) {
        val plan = _activePlan.value ?: return
        val season = plan.activeSeasons.getOrNull(index) ?: return
        _activePlan.update { it?.copy(step = BatchModalStep.SourceSelection(seasonIndex = index)) }
        loadSourcesForSeason(season)
    }

    fun goToEpisodeSelection() {
        _activePlan.update { it?.copy(step = BatchModalStep.EpisodeSelection) }
    }

    /** Closes the dialog and queues every planned episode that has a source. */
    fun queueConfirmedBatch(): BatchQueueResult {
        val plan = _activePlan.value ?: return BatchQueueResult.NothingToQueue
        val itemsToQueue = plan.activeSeasons
            .flatMap { season ->
                plan.seasonPlans[season]?.episodes.orEmpty().mapNotNull { item ->
                    item.streamCandidate?.let { stream -> Triple(season, item.episode, stream) }
                }
            }
            .sortedWith(compareBy({ it.second.season ?: 0 }, { it.second.episode ?: 0 }))

        cancelBatchPlan()
        if (itemsToQueue.isEmpty()) return BatchQueueResult.NothingToQueue

        DownloadsSettingsRepository.ensureLoaded()
        if (!DownloadsSettingsRepository.allowMobileDataDownloads.value && !DownloadNetworkGuard.isOnWifi()) {
            return BatchQueueResult.RequiresWifi
        }

        val startingProfileId = ProfileRepository.activeProfileId
        scope.launch(queueJob + Dispatchers.Default) {
            for ((season, episode, stream) in itemsToQueue) {
                val target = plan.meta.batchTargetFor(episode, season)
                val streamToEnqueue = when (val resolved = resolveDownloadableStream(stream, target)) {
                    is StreamCandidateResolution.Success -> resolved.stream
                    StreamCandidateResolution.MissingApiKey -> {
                        // Every remaining episode needs the same resolver, so stop the batch.
                        InAppLogger.warn("Downloads/Batch", "Missing debrid API key, stopping batch queue")
                        break
                    }
                    is StreamCandidateResolution.Failed -> {
                        InAppLogger.warn("Downloads/Batch", "Skipping S${target.season}E${target.episode}: ${resolved.reason}")
                        continue
                    }
                }

                withContext(Dispatchers.Main) {
                    if (ProfileRepository.activeProfileId != startingProfileId) return@withContext
                    enqueue(plan.meta, episode, target, streamToEnqueue)
                }
            }
        }
        return BatchQueueResult.Queued
    }

    private fun enqueue(meta: MetaDetails, episode: MetaVideo, target: BatchEpisodeTarget, stream: StreamItem) {
        val episodeDetails = episode.takeUnless { target.isSingleMedia }
        val result = DownloadsRepository.enqueueFromStream(
            contentType = meta.type,
            videoId = target.streamVideoId,
            parentMetaId = meta.id,
            parentMetaType = meta.type,
            title = meta.name,
            logo = meta.logo,
            poster = meta.poster,
            background = meta.background,
            seasonNumber = target.season,
            episodeNumber = target.episode,
            episodeTitle = episodeDetails?.title,
            episodeThumbnail = episodeDetails?.thumbnail,
            stream = stream,
            episodeOverview = episodeDetails?.overview,
            episodeReleased = episodeDetails?.released,
            episodeRuntime = episodeDetails?.runtime,
            episodeImdbRating = episodeDetails?.imdbRating,
            episodeTmdbRating = episodeDetails?.tmdbRating,
            episodeRating = episodeDetails?.let { it.rating ?: it.tmdbRating ?: it.imdbRating },
            seasonPoster = episodeDetails?.let { it.seasonPoster ?: target.season?.let(meta.seasonPosters::get) },
            showDescription = meta.description,
            showGenres = meta.genres,
            showReleaseInfo = meta.releaseInfo,
            showImdbRating = meta.imdbRating,
            showStatus = meta.status,
        )
        if (result != DownloadEnqueueResult.Started && result != DownloadEnqueueResult.Replaced) {
            InAppLogger.warn("Downloads/Batch", "Enqueue returned ${result.name} for S${target.season}E${target.episode}")
        }
    }

    private sealed interface StreamCandidateResolution {
        data class Success(val stream: StreamItem) : StreamCandidateResolution
        data object MissingApiKey : StreamCandidateResolution
        data class Failed(val reason: String) : StreamCandidateResolution
    }

    /** Turns a planned source into a direct, downloadable link, resolving debrid links if needed. */
    private suspend fun resolveDownloadableStream(
        candidate: StreamItem,
        target: BatchEpisodeTarget,
    ): StreamCandidateResolution {
        if (candidate.isDownloadableDirectLink()) return StreamCandidateResolution.Success(candidate)
        if (!candidate.isResolverDependent()) return StreamCandidateResolution.Failed("Could not resolve downloadable stream")

        if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(candidate)) {
            return when (val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(candidate, target.season, target.episode)) {
                is DirectDebridPlayableResult.MissingApiKey -> StreamCandidateResolution.MissingApiKey
                is DirectDebridPlayableResult.Success -> if (resolved.stream.isDownloadableDirectLink()) {
                    StreamCandidateResolution.Success(resolved.stream)
                } else {
                    StreamCandidateResolution.Failed("Resolved link not downloadable")
                }
                is DirectDebridPlayableResult.Error -> StreamCandidateResolution.Failed("Failed to resolve stream link")
                is DirectDebridPlayableResult.NotCached -> StreamCandidateResolution.Failed("Stream is no longer cached")
                is DirectDebridPlayableResult.Stale -> StreamCandidateResolution.Failed("Stream link is stale")
            }
        }

        val debridSettings = DebridSettingsRepository.snapshot()
        val providerId = DebridProviders.byId(candidate.clientResolve?.service)?.id
            ?: debridSettings.activeResolverProviderId
        val hasMissingKey = debridSettings.apiKeyFor(providerId).isBlank() ||
            !debridSettings.hasResolverProvider ||
            !debridSettings.canResolvePlayableLinks
        return if (hasMissingKey) {
            StreamCandidateResolution.MissingApiKey
        } else {
            StreamCandidateResolution.Failed("Stream resolver not available")
        }
    }

    private fun StreamItem.isDownloadableDirectLink(): Boolean =
        playableDirectUrl?.trim()?.takeIf { it.isNotBlank() }?.isSupportedDownloadUrl() == true

    fun cancelBatchPlan() {
        planJob?.cancel()
        planJob = null
        _activePlan.value = null
    }

    fun onProfileChanged() {
        clearLocalState()
    }

    fun clearLocalState() {
        cancelBatchPlan()
        queueJob.cancel()
        queueJob = SupervisorJob()
    }
}
