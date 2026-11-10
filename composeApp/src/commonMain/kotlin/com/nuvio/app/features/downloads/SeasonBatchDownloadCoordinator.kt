package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
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
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episodes_season
import nuvio.composeapp.generated.resources.episodes_specials
import org.jetbrains.compose.resources.getString
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class BatchStopReason {
    Completed,
    UserStopped,
    MissingApiKey,
    WifiRequired,
    ProfileChanged,
    Error,
}

data class SeasonBatchProgress(
    val parentMetaId: String,
    val season: Int,
    val seasonLabel: String,
    val totalEligibleEpisodes: Int,
    val initialSkippedCount: Int,
    val toProcessCount: Int,
    val enqueuedCount: Int = 0,
    val failedCount: Int = 0,
    val skippedRaceCount: Int = 0,
    val episodeFailures: Map<Int, String> = emptyMap(),
    val currentResolvingEpisodes: Set<Int> = emptySet(),
    val isRunning: Boolean = true,
    val isFinished: Boolean = false,
    val stopReason: BatchStopReason? = null,
) {
    val batchKey: String get() = batchKey(parentMetaId, season)

    val completedOrSkippedCount: Int
        get() = enqueuedCount + failedCount + skippedRaceCount

    val progressFraction: Float
        get() {
            if (toProcessCount <= 0) return 1f
            return (completedOrSkippedCount.toFloat() / toProcessCount.toFloat()).coerceIn(0f, 1f)
        }
}

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
    val seasonLabel: String,
    val episodes: List<PlannedEpisodeItem> = emptyList(),
    val isPlanning: Boolean = false,
) {
    val readyToDownloadCount: Int
        get() = episodes.count { it.status == EpisodePlanStatus.Ready && it.streamCandidate != null }
}

data class SeasonBatchPlan(
    val meta: MetaDetails,
    val season: Int,
    val seasonLabel: String,
    val preferredResolutionLabel: String,
    val episodes: List<PlannedEpisodeItem> = emptyList(),
    val isPlanning: Boolean = false,
    val allSeasons: List<Int> = listOf(season),
    val episodesBySeason: Map<Int, List<MetaVideo>> = emptyMap(),
    val selectedEpisodeKeys: Set<String> = emptySet(),
    val watchedEpisodeKeys: Set<String> = emptySet(),
    val onlyUnwatched: Boolean = true,
    val step: BatchModalStep = BatchModalStep.EpisodeSelection,
    val activeSeasons: List<Int> = listOf(season),
    val currentPickerSeason: Int = season,
    val seasonPlans: Map<Int, PlannedSeasonEpisodes> = emptyMap(),
    val isSingleMedia: Boolean = false,
) {
    val readyToDownloadCount: Int
        get() = when (step) {
            is BatchModalStep.SourceSelection -> currentSeasonPlan?.readyToDownloadCount ?: episodes.count { it.status == EpisodePlanStatus.Ready && it.streamCandidate != null }
            else -> episodes.count { it.status == EpisodePlanStatus.Ready && it.streamCandidate != null }
        }

    val alreadyDownloadedCount: Int
        get() = episodes.count { it.isAlreadyDownloaded }

    val totalSelectedCount: Int
        get() = selectedEpisodeKeys.size

    val currentSeasonPlan: PlannedSeasonEpisodes?
        get() = when (step) {
            is BatchModalStep.SourceSelection -> {
                val s = activeSeasons.getOrNull(step.seasonIndex)
                s?.let { seasonPlans[it] }
            }
            else -> null
        }
}

data class SeasonBatchConfirmationInfo(
    val season: Int,
    val totalReleasedNumberedEpisodes: Int,
    val toSkipCount: Int,
    val toDownloadCount: Int,
    val seasonLabel: String,
    val preferredResolutionLabel: String,
)

fun batchKey(parentMetaId: String, season: Int): String =
    "${parentMetaId.trim()}:$season"

fun batchEpisodeKey(season: Int, episode: Int?): String =
    if (episode != null) "${season}_$episode" else "${season}_single"

fun isSingleMediaDownloaded(
    currentDownloads: List<DownloadItem>,
    metaId: String,
): Boolean {
    val cleanId = metaId.trim()
    val existing = currentDownloads.firstOrNull {
        it.logicalContentKey == cleanId ||
            (it.parentMetaId.trim() == cleanId && it.seasonNumber == null && it.episodeNumber == null)
    }
    return existing != null && (
        existing.status == DownloadStatus.Completed ||
        existing.status == DownloadStatus.Downloading ||
        existing.status == DownloadStatus.Paused ||
        existing.status == DownloadStatus.Queued
    )
}

fun isBatchEpisodeDownloaded(
    currentDownloads: List<DownloadItem>,
    metaId: String,
    ep: MetaVideo,
    fallbackSeason: Int,
): Boolean {
    val epSeason = ep.season ?: fallbackSeason
    val epNum = ep.episode ?: return isSingleMediaDownloaded(currentDownloads, metaId)
    val logicalKey = "${metaId.trim()}|$epSeason|$epNum"
    val existing = currentDownloads.firstOrNull { it.logicalContentKey == logicalKey }
    return existing != null && (
        existing.status == DownloadStatus.Completed ||
        existing.status == DownloadStatus.Downloading ||
        existing.status == DownloadStatus.Paused ||
        existing.status == DownloadStatus.Queued
    )
}

object SeasonBatchDownloadCoordinator {
    private val log = Logger.withTag("SeasonBatchCoordinator")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var planningJob: Job? = null
    private val _activePlan = MutableStateFlow<SeasonBatchPlan?>(null)
    val activePlan: StateFlow<SeasonBatchPlan?> = _activePlan.asStateFlow()

    private val _batches = MutableStateFlow<Map<String, SeasonBatchProgress>>(emptyMap())
    val batches: StateFlow<Map<String, SeasonBatchProgress>> = _batches.asStateFlow()

    private val activeJobs = mutableMapOf<String, Job>()
    private var activeGeneration = 0L

    fun progress(parentMetaId: String, season: Int): SeasonBatchProgress? =
        _batches.value[batchKey(parentMetaId, season)]

    fun isBatchRunning(parentMetaId: String, season: Int): Boolean =
        progress(parentMetaId, season)?.isRunning == true

    fun calculateConfirmationInfo(
        meta: MetaDetails,
        season: Int,
        seasonLabel: String,
    ): SeasonBatchConfirmationInfo {
        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val allSeasonEpisodes = meta.releasedEpisodesForSeason(season, todayIsoDate)
        val numberedEpisodes = allSeasonEpisodes
            .filter { it.episode != null }
            .distinctBy { Pair(normalizeSeasonNumber(it.season), it.episode) }

        DownloadsRepository.ensureLoaded()
        DownloadsSettingsRepository.ensureLoaded()
        val currentDownloads = DownloadsRepository.uiState.value.items

        var toSkipCount = 0
        for (ep in numberedEpisodes) {
            val epSeason = ep.season ?: season
            if (isBatchEpisodeDownloaded(currentDownloads, meta.id, ep, epSeason)) {
                toSkipCount++
            }
        }

        return SeasonBatchConfirmationInfo(
            season = season,
            totalReleasedNumberedEpisodes = numberedEpisodes.size,
            toSkipCount = toSkipCount,
            toDownloadCount = (numberedEpisodes.size - toSkipCount).coerceAtLeast(0),
            seasonLabel = seasonLabel,
            preferredResolutionLabel = DownloadsSettingsRepository.preferredResolution.value.label,
        )
    }

    fun prepareBatchPlan(
        meta: MetaDetails,
        season: Int = 1,
        seasonLabel: String? = null,
        watchedKeys: Set<String> = emptySet(),
        isEpisodeWatched: (MetaVideo) -> Boolean = { false },
    ) {
        planningJob?.cancel()
        DownloadsSettingsRepository.ensureLoaded()
        DownloadsRepository.ensureLoaded()

        val preferredResolution = DownloadsSettingsRepository.preferredResolution.value
        val todayIsoDate = CurrentDateProvider.todayIsoDate()

        val isMovie = meta.type.equals("movie", ignoreCase = true)
        val hasEpisodes = meta.videos.any { it.season != null || it.episode != null }
        val totalNumberedEpisodes = meta.videos.count { it.episode != null }
        val isSingleMedia = isMovie || !hasEpisodes || totalNumberedEpisodes <= 1

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
            val effectiveSeasonLabel = seasonLabel ?: meta.name
            val singleKey = batchEpisodeKey(targetSeason, singleVideo.episode)

            val initialPlan = SeasonBatchPlan(
                meta = meta,
                season = targetSeason,
                seasonLabel = effectiveSeasonLabel,
                preferredResolutionLabel = preferredResolution.label,
                allSeasons = listOf(targetSeason),
                episodesBySeason = mapOf(targetSeason to listOf(singleVideo)),
                selectedEpisodeKeys = setOf(singleKey),
                watchedEpisodeKeys = watchedKeys,
                onlyUnwatched = false,
                step = BatchModalStep.SourceSelection(seasonIndex = 0),
                activeSeasons = listOf(targetSeason),
                currentPickerSeason = targetSeason,
                isSingleMedia = true,
            )

            _activePlan.value = initialPlan
            loadSourcesForSeason(targetSeason)
            return
        }

        // 1. Discover all seasons
        val allSeasonNumbers = meta.videos
            .mapNotNull { it.season }
            .distinct()
            .sortedBy(::seasonSortKey)
            .ifEmpty { listOf(season) }

        val episodesBySeason = mutableMapOf<Int, List<MetaVideo>>()
        for (s in allSeasonNumbers) {
            val seasonEps = meta.releasedEpisodesForSeason(s, todayIsoDate)
                .filter { it.episode != null }
                .distinctBy { Pair(normalizeSeasonNumber(it.season), it.episode) }
                .sortedBy { it.episode }
            episodesBySeason[s] = seasonEps
        }

        val effectiveSeasonLabel = seasonLabel ?: if (season == 0) "Specials" else "Season $season"
        val initialPickerSeason = if (season in allSeasonNumbers) season else allSeasonNumbers.first()

        val initialPlan = SeasonBatchPlan(
            meta = meta,
            season = season,
            seasonLabel = effectiveSeasonLabel,
            preferredResolutionLabel = preferredResolution.label,
            allSeasons = allSeasonNumbers,
            episodesBySeason = episodesBySeason,
            selectedEpisodeKeys = emptySet(),
            watchedEpisodeKeys = watchedKeys,
            onlyUnwatched = false,
            step = BatchModalStep.EpisodeSelection,
            activeSeasons = allSeasonNumbers,
            currentPickerSeason = initialPickerSeason,
            isSingleMedia = false,
        )

        _activePlan.value = initialPlan
    }

    fun setPickerSeason(season: Int) {
        _activePlan.update { it?.copy(currentPickerSeason = season) }
    }

    fun toggleEpisodeSelection(season: Int, episodeNumber: Int) {
        val key = batchEpisodeKey(season, episodeNumber)
        _activePlan.update { current ->
            if (current == null) return@update null
            val updatedKeys = if (key in current.selectedEpisodeKeys) {
                current.selectedEpisodeKeys - key
            } else {
                current.selectedEpisodeKeys + key
            }
            current.copy(selectedEpisodeKeys = updatedKeys)
        }
    }

    fun selectAllForSeason(season: Int) {
        _activePlan.update { current ->
            if (current == null) return@update null
            DownloadsRepository.ensureLoaded()
            val currentDownloads = DownloadsRepository.uiState.value.items
            val seasonEpisodes = current.episodesBySeason[season].orEmpty()
            val newKeys = current.selectedEpisodeKeys.toMutableSet()
            for (ep in seasonEpisodes) {
                val epNum = ep.episode ?: continue
                if (!isBatchEpisodeDownloaded(currentDownloads, current.meta.id, ep, season)) {
                    newKeys.add(batchEpisodeKey(season, epNum))
                }
            }
            current.copy(selectedEpisodeKeys = newKeys)
        }
    }

    fun selectUnwatchedForSeason(
        season: Int,
        isEpisodeWatched: (MetaVideo) -> Boolean = { false },
    ) {
        _activePlan.update { current ->
            if (current == null) return@update null
            DownloadsRepository.ensureLoaded()
            val currentDownloads = DownloadsRepository.uiState.value.items
            val seasonEpisodes = current.episodesBySeason[season].orEmpty()
            val newKeys = current.selectedEpisodeKeys.toMutableSet()
            for (ep in seasonEpisodes) {
                val epNum = ep.episode ?: continue
                val key = batchEpisodeKey(season, epNum)
                val downloaded = isBatchEpisodeDownloaded(currentDownloads, current.meta.id, ep, season)
                val watched = isEpisodeWatched(ep) ||
                    "${current.meta.id}:$season:$epNum" in current.watchedEpisodeKeys ||
                    ep.id in current.watchedEpisodeKeys
                if (!downloaded && !watched) {
                    newKeys.add(key)
                } else {
                    newKeys.remove(key)
                }
            }
            current.copy(selectedEpisodeKeys = newKeys)
        }
    }

    fun deselectAllForSeason(season: Int) {
        _activePlan.update { current ->
            if (current == null) return@update null
            val seasonEpisodes = current.episodesBySeason[season].orEmpty()
            val newKeys = current.selectedEpisodeKeys.toMutableSet()
            for (ep in seasonEpisodes) {
                val epNum = ep.episode ?: continue
                newKeys.remove(batchEpisodeKey(season, epNum))
            }
            current.copy(selectedEpisodeKeys = newKeys)
        }
    }

    fun continueToSourceSelection() {
        val plan = _activePlan.value ?: return
        val activeSeasons = plan.allSeasons.filter { s ->
            plan.episodesBySeason[s].orEmpty().any { ep ->
                batchEpisodeKey(s, ep.episode ?: 0) in plan.selectedEpisodeKeys
            }
        }
        if (activeSeasons.isEmpty()) return

        _activePlan.update {
            it?.copy(
                activeSeasons = activeSeasons,
                step = BatchModalStep.SourceSelection(seasonIndex = 0),
            )
        }

        loadSourcesForSeason(activeSeasons[0])
    }

    fun loadSourcesForSeason(season: Int) {
        val plan = _activePlan.value ?: return
        val existingPlan = plan.seasonPlans[season]
        if (existingPlan != null && !existingPlan.isPlanning && existingPlan.episodes.isNotEmpty()) {
            return
        }

        val seasonEpisodes = plan.episodesBySeason[season].orEmpty()
        val selectedEpisodes = seasonEpisodes.filter { ep ->
            if (plan.isSingleMedia) {
                true
            } else {
                batchEpisodeKey(season, ep.episode ?: 0) in plan.selectedEpisodeKeys
            }
        }
        if (selectedEpisodes.isEmpty()) return

        DownloadsRepository.ensureLoaded()
        val currentDownloads = DownloadsRepository.uiState.value.items

        val initialItems = selectedEpisodes.map { ep ->
            val isDownloaded = if (plan.isSingleMedia && ep.episode == null) {
                isSingleMediaDownloaded(currentDownloads, plan.meta.id)
            } else {
                isBatchEpisodeDownloaded(currentDownloads, plan.meta.id, ep, season)
            }
            PlannedEpisodeItem(
                episode = ep,
                isAlreadyDownloaded = isDownloaded,
                status = if (isDownloaded) EpisodePlanStatus.AlreadyDownloaded else EpisodePlanStatus.Loading,
            )
        }

        val effectiveSeasonLabel = if (plan.isSingleMedia) {
            plan.meta.name
        } else if (season == 0) {
            "Specials"
        } else {
            "Season $season"
        }
        _activePlan.update { current ->
            if (current == null) return@update null
            val updatedMap = current.seasonPlans + (season to PlannedSeasonEpisodes(
                season = season,
                seasonLabel = effectiveSeasonLabel,
                episodes = initialItems,
                isPlanning = true,
            ))
            current.copy(seasonPlans = updatedMap)
        }

        scope.launch {
            val preferredResolution = DownloadsSettingsRepository.preferredResolution.value
            val semaphore = Semaphore(3)
            val loadJobs = selectedEpisodes.map { ep ->
                launch(Dispatchers.Default) {
                    semaphore.withPermit {
                        if (!isActive) return@withPermit
                        val isMovieOrSingleWithoutEpisode = plan.isSingleMedia && ep.episode == null
                        val epSeason = if (isMovieOrSingleWithoutEpisode) null else (ep.season ?: season)
                        val epNumber = ep.episode
                        val playbackVideoId = if (isMovieOrSingleWithoutEpisode) {
                            plan.meta.id
                        } else {
                            buildPlaybackVideoId(
                                parentMetaId = plan.meta.id,
                                seasonNumber = epSeason ?: season,
                                episodeNumber = epNumber ?: 1,
                                fallbackVideoId = ep.id,
                            )
                        }
                        val streamVideoId = if (isMovieOrSingleWithoutEpisode) {
                            ep.id.takeIf { it.isNotBlank() && it != plan.meta.name } ?: plan.meta.id
                        } else {
                            ep.id.takeIf { it.isNotBlank() } ?: playbackVideoId
                        }
                        val embeddedStreams = StreamSourceLoader.findEmbeddedStreams(plan.meta, streamVideoId)
                        val streams = runCatching {
                            StreamSourceLoader.loadStreams(
                                type = plan.meta.type,
                                videoId = streamVideoId,
                                season = epSeason,
                                episode = epNumber,
                                embeddedStreams = embeddedStreams,
                                forceRefresh = false,
                                timeoutMs = 75_000L,
                            )
                        }.getOrElse { emptyList() }

                        val candidateSelection = StreamSourcePrefillSelector.selectForBatch(
                            streams = streams,
                            preferredResolution = preferredResolution,
                        )

                        _activePlan.update { current ->
                            if (current == null) return@update null
                            val currentSeasonPlan = current.seasonPlans[season] ?: return@update current
                            val updatedEpisodes = currentSeasonPlan.episodes.map { epItem ->
                                val matches = if (isMovieOrSingleWithoutEpisode) {
                                    epItem.episode.id == ep.id || epItem.episode.episode == null
                                } else {
                                    epItem.episode.episode == epNumber
                                }
                                if (matches) {
                                    if (candidateSelection != null) {
                                        val facts = candidateSelection.facts
                                        val res = facts.resolution.label.takeUnless { it == "Unknown" }
                                        val quality = facts.quality.name.replace("_", "-").takeUnless { it == "UNKNOWN" }
                                        val badge = listOfNotNull(res, quality).firstOrNull() ?: facts.resolution.label.takeUnless { it == "Unknown" }
                                        epItem.copy(
                                            streamCandidate = candidateSelection.stream,
                                            allAvailableStreams = streams,
                                            resolutionLabel = badge,
                                            sizeBytes = facts.size?.takeIf { it > 0 },
                                            status = EpisodePlanStatus.Ready,
                                        )
                                    } else {
                                        epItem.copy(
                                            allAvailableStreams = streams,
                                            status = EpisodePlanStatus.NoSourceFound,
                                        )
                                    }
                                } else {
                                    epItem
                                }
                            }
                            val updatedSeasonPlans = current.seasonPlans + (season to currentSeasonPlan.copy(episodes = updatedEpisodes))
                            current.copy(seasonPlans = updatedSeasonPlans)
                        }
                    }
                }
            }
            loadJobs.joinAll()
            _activePlan.update { current ->
                if (current == null) return@update null
                val currentSeasonPlan = current.seasonPlans[season] ?: return@update current
                val updatedSeasonPlans = current.seasonPlans + (season to currentSeasonPlan.copy(isPlanning = false))
                current.copy(seasonPlans = updatedSeasonPlans)
            }
        }
    }

    fun overrideEpisodeStream(season: Int, episodeNumber: Int?, stream: StreamItem) {
        val facts = DebridStreamMetadata.facts(stream, DebridStreamPreferences())
        val res = facts.resolution.label.takeUnless { it == "Unknown" }
        val quality = facts.quality.name.replace("_", "-").takeUnless { it == "UNKNOWN" }
        val badge = listOfNotNull(res, quality).firstOrNull() ?: facts.resolution.label.takeUnless { it == "Unknown" }

        _activePlan.update { current ->
            if (current == null) return@update null
            val currentSeasonPlan = current.seasonPlans[season] ?: return@update current
            val updatedEpisodes = currentSeasonPlan.episodes.map { epItem ->
                val matches = if (current.isSingleMedia && episodeNumber == null) {
                    true
                } else {
                    epItem.episode.episode == episodeNumber
                }
                if (matches) {
                    epItem.copy(
                        streamCandidate = stream,
                        resolutionLabel = badge,
                        sizeBytes = facts.size?.takeIf { it > 0 },
                        status = EpisodePlanStatus.Ready,
                    )
                } else {
                    epItem
                }
            }
            current.copy(
                seasonPlans = current.seasonPlans + (season to currentSeasonPlan.copy(episodes = updatedEpisodes)),
            )
        }
    }

    fun goToSeasonIndex(index: Int) {
        val plan = _activePlan.value ?: return
        if (index in plan.activeSeasons.indices) {
            _activePlan.update { it?.copy(step = BatchModalStep.SourceSelection(seasonIndex = index)) }
            loadSourcesForSeason(plan.activeSeasons[index])
        }
    }

    fun goToEpisodeSelection() {
        _activePlan.update { it?.copy(step = BatchModalStep.EpisodeSelection) }
    }

    fun queueConfirmedBatch(): Boolean {
        val plan = _activePlan.value ?: return false
        val allActiveSeasons = plan.activeSeasons

        val itemsToQueue = mutableListOf<Pair<MetaVideo, StreamItem>>()
        for (s in allActiveSeasons) {
            val seasonPlan = plan.seasonPlans[s] ?: continue
            for (epItem in seasonPlan.episodes) {
                val stream = epItem.streamCandidate
                if (stream != null) {
                    itemsToQueue.add(epItem.episode to stream)
                }
            }
        }

        if (itemsToQueue.isEmpty()) {
            cancelBatchPlan()
            return false
        }

        DownloadsSettingsRepository.ensureLoaded()
        val allowMobileData = DownloadsSettingsRepository.allowMobileDataDownloads.value
        val isOnWifi = DownloadNetworkGuard.isOnWifi()
        if (!allowMobileData && !isOnWifi) {
            cancelBatchPlan()
            return false
        }

        // Sort chronologically: season, then episode number
        itemsToQueue.sortWith(
            compareBy<Pair<MetaVideo, StreamItem>> { it.first.season ?: 0 }
                .thenBy { it.first.episode ?: 0 }
        )

        cancelBatchPlan()

        scope.launch(Dispatchers.Default) {
            for ((episode, stream) in itemsToQueue) {
                val isSingle = plan.isSingleMedia && episode.episode == null
                val epSeason = if (isSingle) null else (episode.season ?: 1)
                val epNumber = if (isSingle) null else (episode.episode ?: 1)
                val playbackVideoId = if (isSingle) {
                    plan.meta.id
                } else {
                    buildPlaybackVideoId(
                        parentMetaId = plan.meta.id,
                        seasonNumber = epSeason ?: 1,
                        episodeNumber = epNumber ?: 1,
                        fallbackVideoId = episode.id,
                    )
                }
                val streamVideoId = if (isSingle) {
                    episode.id.takeIf { it.isNotBlank() && it != plan.meta.name } ?: plan.meta.id
                } else {
                    episode.id.takeIf { it.isNotBlank() } ?: playbackVideoId
                }

                val resolvedResult = resolveSingleCandidate(stream, epSeason, epNumber)
                val streamToEnqueue = if (resolvedResult is StreamCandidateResolution.Success) {
                    resolvedResult.stream
                } else {
                    stream
                }

                withContext(Dispatchers.Main) {
                    DownloadsRepository.enqueueFromStream(
                        contentType = plan.meta.type,
                        videoId = streamVideoId,
                        parentMetaId = plan.meta.id,
                        parentMetaType = plan.meta.type,
                        title = plan.meta.name,
                        logo = plan.meta.logo,
                        poster = plan.meta.poster,
                        background = plan.meta.background,
                        seasonNumber = epSeason,
                        episodeNumber = epNumber,
                        episodeTitle = if (isSingle) null else episode.title,
                        episodeThumbnail = if (isSingle) null else episode.thumbnail,
                        stream = streamToEnqueue,
                        episodeOverview = if (isSingle) null else episode.overview,
                        episodeReleased = if (isSingle) null else episode.released,
                        episodeRuntime = if (isSingle) null else episode.runtime,
                        episodeImdbRating = if (isSingle) null else episode.imdbRating,
                        episodeTmdbRating = if (isSingle) null else episode.tmdbRating,
                        episodeRating = if (isSingle) null else (episode.rating ?: episode.tmdbRating ?: episode.imdbRating),
                        seasonPoster = if (isSingle) null else (episode.seasonPoster ?: epSeason?.let { plan.meta.seasonPosters[it] }),
                        showDescription = plan.meta.description,
                        showGenres = plan.meta.genres,
                        showReleaseInfo = plan.meta.releaseInfo,
                        showImdbRating = plan.meta.imdbRating,
                        showStatus = plan.meta.status,
                    )
                }
            }
        }
        return true
    }

    fun cancelBatchPlan() {
        planningJob?.cancel()
        planningJob = null
        _activePlan.value = null
    }

    fun startBatchFromPlan(plan: SeasonBatchPlan): Boolean {
        return queueConfirmedBatch()
    }

    fun startBatch(
        meta: MetaDetails,
        season: Int,
        seasonLabel: String,
        plannedStreams: Map<Int, StreamItem>? = null,
    ): Boolean {
        val key = batchKey(meta.id, season)

        if (activeJobs[key]?.isActive == true || _batches.value[key]?.isRunning == true) {
            return true
        }

        DownloadsSettingsRepository.ensureLoaded()
        val allowMobileData = DownloadsSettingsRepository.allowMobileDataDownloads.value
        val isOnWifi = DownloadNetworkGuard.isOnWifi()
        if (!allowMobileData && !isOnWifi) {
            InAppLogger.warn("Downloads/Batch", "Cannot start season batch: WiFi required")
            return false
        }

        val preferredResolution = DownloadsSettingsRepository.preferredResolution.value
        val startingProfileId = ProfileRepository.activeProfileId
        val todayIsoDate = CurrentDateProvider.todayIsoDate()

        val allSeasonEpisodes = meta.releasedEpisodesForSeason(season, todayIsoDate)
        val numberedEpisodes = allSeasonEpisodes
            .filter { it.episode != null }
            .distinctBy { Pair(normalizeSeasonNumber(it.season), it.episode) }

        DownloadsRepository.ensureLoaded()
        val currentDownloads = DownloadsRepository.uiState.value.items

        val episodesToProcess = mutableListOf<MetaVideo>()
        var initialSkippedCount = 0

        for (ep in numberedEpisodes) {
            val epSeason = ep.season ?: season
            val epNumber = ep.episode!!
            val logicalKey = "${meta.id.trim()}|$epSeason|$epNumber"
            val existing = currentDownloads.firstOrNull { it.logicalContentKey == logicalKey }
            if (existing != null && (
                existing.status == DownloadStatus.Completed ||
                existing.status == DownloadStatus.Downloading ||
                existing.status == DownloadStatus.Paused ||
                existing.status == DownloadStatus.Queued
            )) {
                initialSkippedCount++
            } else if (plannedStreams == null || epNumber in plannedStreams) {
                episodesToProcess.add(ep)
            }
        }

        val totalEligible = numberedEpisodes.size
        val toProcessCount = episodesToProcess.size

        if (toProcessCount == 0) {
            _batches.update { current ->
                current + (key to SeasonBatchProgress(
                    parentMetaId = meta.id,
                    season = season,
                    seasonLabel = seasonLabel,
                    totalEligibleEpisodes = totalEligible,
                    initialSkippedCount = initialSkippedCount,
                    toProcessCount = 0,
                    isRunning = false,
                    isFinished = true,
                    stopReason = BatchStopReason.Completed,
                ))
            }
            return true
        }

        val initialProgress = SeasonBatchProgress(
            parentMetaId = meta.id,
            season = season,
            seasonLabel = seasonLabel,
            totalEligibleEpisodes = totalEligible,
            initialSkippedCount = initialSkippedCount,
            toProcessCount = toProcessCount,
            isRunning = true,
        )
        _batches.update { it + (key to initialProgress) }

        val batchGeneration = activeGeneration
        val runnerJob = launchBatchRunner(
            meta = meta,
            season = season,
            seasonLabel = seasonLabel,
            episodesToProcess = episodesToProcess,
            preferredResolution = preferredResolution,
            startingProfileId = startingProfileId,
            key = key,
            batchGeneration = batchGeneration,
            plannedStreams = plannedStreams,
        )
        activeJobs[key] = runnerJob
        return true
    }

    private fun launchBatchRunner(
        meta: MetaDetails,
        season: Int,
        seasonLabel: String,
        episodesToProcess: List<MetaVideo>,
        preferredResolution: DownloadPreferredResolution,
        startingProfileId: Int,
        key: String,
        batchGeneration: Long,
        plannedStreams: Map<Int, StreamItem>? = null,
    ): Job = scope.launch {
        InAppLogger.info(
            "Downloads/Batch",
            "Started batch for ${meta.name} S$season (${episodesToProcess.size} episodes)",
        )

        val batchDownloadIds = mutableSetOf<String>()
        val resolvingEpisodes = mutableSetOf<Int>()
        val remainingEpisodes = ArrayDeque(episodesToProcess)
        val trigger = Channel<Unit>(Channel.CONFLATED)

        val downloadsMonitorJob = launch {
            DownloadsRepository.uiState.collect {
                trigger.trySend(Unit)
            }
        }

        var stopReason: BatchStopReason? = null

        try {
            while (remainingEpisodes.isNotEmpty() || resolvingEpisodes.isNotEmpty()) {
                if (batchGeneration != activeGeneration) {
                    break
                }

                if (ProfileRepository.activeProfileId != startingProfileId) {
                    stopReason = BatchStopReason.ProfileChanged
                    break
                }

                val allowData = DownloadsSettingsRepository.allowMobileDataDownloads.value
                val onWifi = DownloadNetworkGuard.isOnWifi()
                if (!allowData && !onWifi) {
                    stopReason = BatchStopReason.WifiRequired
                    break
                }

                val currentDownloads = DownloadsRepository.uiState.value.items
                val activeBatchDownloads = currentDownloads.filter { it.id in batchDownloadIds }
                val downloadingOrPausedCount = activeBatchDownloads.count {
                    it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Paused
                }
                val usedSlots = resolvingEpisodes.size + downloadingOrPausedCount

                if (usedSlots < 2 && remainingEpisodes.isNotEmpty()) {
                    val nextEpisode = remainingEpisodes.removeFirst()
                    val epNumber = nextEpisode.episode!!
                    resolvingEpisodes.add(epNumber)

                    _batches.update { current ->
                        val existing = current[key] ?: return@update current
                        current + (key to existing.copy(
                            currentResolvingEpisodes = resolvingEpisodes.toSet(),
                        ))
                    }

                    launch {
                        try {
                            processEpisode(
                                meta = meta,
                                season = season,
                                episode = nextEpisode,
                                preferredResolution = preferredResolution,
                                startingProfileId = startingProfileId,
                                batchGeneration = batchGeneration,
                                isBatchStillRunning = {
                                    batchGeneration == activeGeneration && stopReason == null
                                },
                                batchDownloadIds = batchDownloadIds,
                                plannedStream = plannedStreams?.get(epNumber),
                                onEnqueued = {
                                    _batches.update { current ->
                                        val existing = current[key] ?: return@update current
                                        current + (key to existing.copy(
                                            enqueuedCount = existing.enqueuedCount + 1,
                                        ))
                                    }
                                },
                                onSkippedRace = {
                                    _batches.update { current ->
                                        val existing = current[key] ?: return@update current
                                        current + (key to existing.copy(
                                            skippedRaceCount = existing.skippedRaceCount + 1,
                                        ))
                                    }
                                },
                                onFailed = { reason ->
                                    _batches.update { current ->
                                        val existing = current[key] ?: return@update current
                                        current + (key to existing.copy(
                                            failedCount = existing.failedCount + 1,
                                            episodeFailures = existing.episodeFailures + (epNumber to reason),
                                        ))
                                    }
                                },
                                onStopBatch = { reason ->
                                    stopReason = reason
                                    trigger.trySend(Unit)
                                },
                            )
                        } finally {
                            resolvingEpisodes.remove(epNumber)
                            if (batchGeneration == activeGeneration) {
                                _batches.update { current ->
                                    val existing = current[key] ?: return@update current
                                    current + (key to existing.copy(
                                        currentResolvingEpisodes = resolvingEpisodes.toSet(),
                                    ))
                                }
                            }
                            trigger.trySend(Unit)
                        }
                    }
                    continue
                }

                if (stopReason != null) {
                    break
                }

                trigger.receive()
            }
        } catch (e: CancellationException) {
            stopReason = stopReason ?: BatchStopReason.UserStopped
            throw e
        } catch (e: Throwable) {
            InAppLogger.warn("Downloads/Batch", "Batch runner error: ${InAppLogger.throwableSummary(e)}")
            stopReason = BatchStopReason.Error
        } finally {
            downloadsMonitorJob.cancel()
            if (activeJobs[key] === coroutineContext[Job]) {
                activeJobs.remove(key)
            }
            if (batchGeneration == activeGeneration) {
                val finalReason = stopReason ?: BatchStopReason.Completed
                _batches.update { current ->
                    val existing = current[key] ?: return@update current
                    current + (key to existing.copy(
                        isRunning = false,
                        isFinished = true,
                        stopReason = finalReason,
                        currentResolvingEpisodes = emptySet(),
                    ))
                }
                InAppLogger.info(
                    "Downloads/Batch",
                    "Finished batch for ${meta.name} S$season with reason=$finalReason",
                )
            }
        }
    }

    sealed class StreamCandidateResolution {
        data class Success(val stream: StreamItem) : StreamCandidateResolution()
        data object MissingApiKey : StreamCandidateResolution()
        data class Failed(val reason: String) : StreamCandidateResolution()
    }

    private suspend fun processEpisode(
        meta: MetaDetails,
        season: Int,
        episode: MetaVideo,
        preferredResolution: DownloadPreferredResolution,
        startingProfileId: Int,
        batchGeneration: Long,
        isBatchStillRunning: () -> Boolean,
        batchDownloadIds: MutableSet<String>,
        plannedStream: StreamItem? = null,
        onEnqueued: () -> Unit,
        onSkippedRace: () -> Unit,
        onFailed: (String) -> Unit,
        onStopBatch: (BatchStopReason) -> Unit,
    ) {
        val epSeason = episode.season ?: season
        val epNumber = episode.episode!!
        val playbackVideoId = buildPlaybackVideoId(
            parentMetaId = meta.id,
            seasonNumber = epSeason,
            episodeNumber = epNumber,
            fallbackVideoId = episode.id,
        )
        val streamVideoId = episode.id.takeIf { it.isNotBlank() } ?: playbackVideoId

        InAppLogger.info("Downloads/Batch", "Processing S${epSeason}E${epNumber} ($streamVideoId)")

        val resolution = withContext(Dispatchers.Default) {
            if (plannedStream != null) {
                resolveSingleCandidate(
                    candidate = plannedStream,
                    epSeason = epSeason,
                    epNumber = epNumber,
                )
            } else {
                val embeddedStreams = StreamSourceLoader.findEmbeddedStreams(meta, streamVideoId)
                val streams = StreamSourceLoader.loadStreams(
                    type = meta.type,
                    videoId = streamVideoId,
                    season = epSeason,
                    episode = epNumber,
                    embeddedStreams = embeddedStreams,
                    forceRefresh = false,
                    timeoutMs = 75_000L,
                )

                if (streams.isEmpty()) {
                    return@withContext StreamCandidateResolution.Failed("No sources found")
                }

                resolveCandidate(
                    streams = streams,
                    preferredResolution = preferredResolution,
                    epSeason = epSeason,
                    epNumber = epNumber,
                )
            }
        }

        if (batchGeneration != activeGeneration || !coroutineContext.isActive || !isBatchStillRunning()) {
            return
        }

        when (resolution) {
            is StreamCandidateResolution.MissingApiKey -> {
                InAppLogger.warn("Downloads/Batch", "Missing debrid API key for S${epSeason}E${epNumber}, stopping batch")
                onStopBatch(BatchStopReason.MissingApiKey)
                return
            }
            is StreamCandidateResolution.Failed -> {
                InAppLogger.warn("Downloads/Batch", "Could not resolve stream for S${epSeason}E${epNumber}: ${resolution.reason}")
                onFailed(resolution.reason)
                return
            }
            is StreamCandidateResolution.Success -> {}
        }

        val resolvedPlayableStream = (resolution as StreamCandidateResolution.Success).stream

        if (ProfileRepository.activeProfileId != startingProfileId) {
            InAppLogger.warn("Downloads/Batch", "Active profile changed before enqueue, stopping batch")
            onStopBatch(BatchStopReason.ProfileChanged)
            return
        }

        val allowData = DownloadsSettingsRepository.allowMobileDataDownloads.value
        val onWifi = DownloadNetworkGuard.isOnWifi()
        if (!allowData && !onWifi) {
            InAppLogger.warn("Downloads/Batch", "Wi-Fi lost before enqueue, stopping batch")
            onStopBatch(BatchStopReason.WifiRequired)
            return
        }

        val logicalKey = "${meta.id.trim()}|$epSeason|$epNumber"
        val existing = DownloadsRepository.uiState.value.items.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null && (
            existing.status == DownloadStatus.Completed ||
            existing.status == DownloadStatus.Downloading ||
            existing.status == DownloadStatus.Paused ||
            existing.status == DownloadStatus.Queued
        )) {
            InAppLogger.info("Downloads/Batch", "Active row appeared before enqueue for S${epSeason}E${epNumber}, skipping")
            onSkippedRace()
            return
        }

        val result = DownloadsRepository.enqueueFromStream(
            contentType = meta.type,
            parentMetaId = meta.id,
            parentMetaType = meta.type,
            videoId = streamVideoId,
            title = meta.name,
            logo = meta.logo,
            poster = meta.poster,
            background = meta.background,
            seasonNumber = epSeason,
            episodeNumber = epNumber,
            episodeTitle = episode.title,
            episodeThumbnail = episode.thumbnail,
            stream = resolvedPlayableStream,
            episodeOverview = episode.overview,
            episodeReleased = episode.released,
            episodeRuntime = episode.runtime,
            episodeImdbRating = episode.imdbRating,
            episodeTmdbRating = episode.tmdbRating,
            episodeRating = episode.rating ?: episode.tmdbRating ?: episode.imdbRating,
            seasonPoster = episode.seasonPoster ?: meta.seasonPosters[epSeason],
            showDescription = meta.description,
            showGenres = meta.genres,
            showReleaseInfo = meta.releaseInfo,
            showImdbRating = meta.imdbRating,
            showStatus = meta.status,
        )

        when (result) {
            DownloadEnqueueResult.Started,
            DownloadEnqueueResult.Replaced -> {
                val newlyEnqueued = DownloadsRepository.uiState.value.items.firstOrNull { it.logicalContentKey == logicalKey }
                if (newlyEnqueued != null) {
                    batchDownloadIds.add(newlyEnqueued.id)
                }
                InAppLogger.info("Downloads/Batch", "Enqueued S${epSeason}E${epNumber} (id=${newlyEnqueued?.id})")
                onEnqueued()
            }
            DownloadEnqueueResult.RequiresWifi -> {
                onStopBatch(BatchStopReason.WifiRequired)
            }
            DownloadEnqueueResult.MissingUrl,
            DownloadEnqueueResult.UnsupportedFormat -> {
                InAppLogger.warn("Downloads/Batch", "Enqueue returned ${result.name} for S${epSeason}E${epNumber}")
                onFailed("Enqueue returned ${result.name}")
            }
        }
    }

    private suspend fun resolveCandidate(
        streams: List<StreamItem>,
        preferredResolution: DownloadPreferredResolution,
        epSeason: Int,
        epNumber: Int,
    ): StreamCandidateResolution {
        val triedCandidates = mutableSetOf<StreamItem>()
        val debridSettings = DebridSettingsRepository.snapshot()

        for (attempt in 1..3) {
            val candidateSelection = StreamSourcePrefillSelector.selectForBatch(
                streams = streams.filter { it !in triedCandidates },
                preferredResolution = preferredResolution,
            ) ?: break

            val candidate = candidateSelection.stream
            triedCandidates.add(candidate)

            val directUrl = candidate.playableDirectUrl?.trim().orEmpty()
            if (directUrl.isNotBlank()) {
                if (directUrl.isSupportedDownloadUrl()) {
                    return StreamCandidateResolution.Success(candidate)
                } else {
                    continue
                }
            }

            if (candidate.isResolverDependent()) {
                val providerId = DebridProviders.byId(candidate.clientResolve?.service)?.id
                    ?: debridSettings.activeResolverProviderId
                val apiKey = debridSettings.apiKeyFor(providerId)
                val hasMissingKey = apiKey.isBlank() ||
                    !debridSettings.hasResolverProvider ||
                    !debridSettings.canResolvePlayableLinks

                if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(candidate)) {
                    when (val resolvedResult = DirectDebridPlaybackResolver.resolveToPlayableStream(candidate, epSeason, epNumber)) {
                        is DirectDebridPlayableResult.MissingApiKey -> {
                            return StreamCandidateResolution.MissingApiKey
                        }
                        is DirectDebridPlayableResult.Success -> {
                            val resolvedDirectUrl = resolvedResult.stream.playableDirectUrl?.trim().orEmpty()
                            if (resolvedDirectUrl.isNotBlank() && resolvedDirectUrl.isSupportedDownloadUrl()) {
                                return StreamCandidateResolution.Success(resolvedResult.stream)
                            } else {
                                continue
                            }
                        }
                        is DirectDebridPlayableResult.Error,
                        is DirectDebridPlayableResult.NotCached,
                        is DirectDebridPlayableResult.Stale -> {
                            continue
                        }
                    }
                } else if (hasMissingKey) {
                    InAppLogger.warn(
                        "Downloads/Batch",
                        "Missing debrid API key for resolver-dependent candidate: ${candidate.name.orEmpty()}",
                    )
                    return StreamCandidateResolution.MissingApiKey
                } else {
                    continue
                }
            } else {
                continue
            }
        }

        return StreamCandidateResolution.Failed("Could not resolve downloadable stream")
    }

    suspend fun resolveSingleCandidate(
        candidate: StreamItem,
        epSeason: Int?,
        epNumber: Int?,
    ): StreamCandidateResolution {
        val directUrl = candidate.playableDirectUrl?.trim().orEmpty()
        if (directUrl.isNotBlank() && directUrl.isSupportedDownloadUrl()) {
            return StreamCandidateResolution.Success(candidate)
        }

        if (candidate.isResolverDependent()) {
            val debridSettings = DebridSettingsRepository.snapshot()
            val providerId = DebridProviders.byId(candidate.clientResolve?.service)?.id
                ?: debridSettings.activeResolverProviderId
            val apiKey = debridSettings.apiKeyFor(providerId)
            val hasMissingKey = apiKey.isBlank() ||
                !debridSettings.hasResolverProvider ||
                !debridSettings.canResolvePlayableLinks

            if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(candidate)) {
                when (val resolvedResult = DirectDebridPlaybackResolver.resolveToPlayableStream(candidate, epSeason, epNumber)) {
                    is DirectDebridPlayableResult.MissingApiKey -> {
                        return StreamCandidateResolution.MissingApiKey
                    }
                    is DirectDebridPlayableResult.Success -> {
                        val resolvedDirectUrl = resolvedResult.stream.playableDirectUrl?.trim().orEmpty()
                        if (resolvedDirectUrl.isNotBlank() && resolvedDirectUrl.isSupportedDownloadUrl()) {
                            return StreamCandidateResolution.Success(resolvedResult.stream)
                        } else {
                            return StreamCandidateResolution.Failed("Resolved link not downloadable")
                        }
                    }
                    is DirectDebridPlayableResult.Error -> {
                        return StreamCandidateResolution.Failed("Failed to resolve stream link")
                    }
                    is DirectDebridPlayableResult.NotCached -> {
                        return StreamCandidateResolution.Failed("Stream is no longer cached")
                    }
                    is DirectDebridPlayableResult.Stale -> {
                        return StreamCandidateResolution.Failed("Stream link is stale")
                    }
                }
            } else if (hasMissingKey) {
                InAppLogger.warn(
                    "Downloads/Batch",
                    "Missing debrid API key for resolver-dependent candidate: ${candidate.name.orEmpty()}",
                )
                return StreamCandidateResolution.MissingApiKey
            } else {
                return StreamCandidateResolution.Failed("Stream resolver not available")
            }
        }

        return StreamCandidateResolution.Failed("Could not resolve downloadable stream")
    }

    fun stopBatch(parentMetaId: String, season: Int) {
        val key = batchKey(parentMetaId, season)
        activeJobs.remove(key)?.cancel()
        _batches.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to existing.copy(
                isRunning = false,
                isFinished = true,
                stopReason = BatchStopReason.UserStopped,
                currentResolvingEpisodes = emptySet(),
            ))
        }
    }

    fun onProfileChanged() {
        stopAllBatches(BatchStopReason.ProfileChanged)
    }

    fun clearLocalState() {
        activeGeneration++
        planningJob?.cancel()
        planningJob = null
        _activePlan.value = null
        val jobs = activeJobs.values.toList()
        activeJobs.clear()
        jobs.forEach { it.cancel() }
        _batches.value = emptyMap()
    }

    fun stopAllBatches(reason: BatchStopReason = BatchStopReason.UserStopped) {
        activeGeneration++
        planningJob?.cancel()
        planningJob = null
        _activePlan.value = null
        val jobs = activeJobs.values.toList()
        activeJobs.clear()
        jobs.forEach { it.cancel() }
        _batches.update { current ->
            current.mapValues { (_, batch) ->
                if (batch.isRunning) {
                    batch.copy(
                        isRunning = false,
                        isFinished = true,
                        stopReason = reason,
                        currentResolvingEpisodes = emptySet(),
                    )
                } else {
                    batch
                }
            }
        }
    }

    fun dismissBatch(parentMetaId: String, season: Int) {
        val key = batchKey(parentMetaId, season)
        activeJobs.remove(key)?.cancel()
        _batches.update { it - key }
    }
}
