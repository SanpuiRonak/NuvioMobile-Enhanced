package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamPresentation
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.plugins.PluginsUiState
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.servers.ServerStreamSource
import com.nuvio.app.features.servers.ServerStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_failed_to_load_scraper
import org.jetbrains.compose.resources.getString

internal data class StreamSourceTargets(
    val serverSources: List<ServerStreamSource>,
    val streamAddons: List<InstalledStreamAddonTarget>,
    val pluginProviderGroups: List<PluginProviderGroup>,
    val preferredGroupIds: Set<String>,
    val installedAddonOrder: List<String>,
    val installedAddonIds: Set<String>,
    /** Enabled addons before filtering by stream support; zero means none are installed. */
    val enabledAddonCount: Int,
    val pluginScraperCount: Int,
)

internal object StreamSourceLoader {
    private val log = Logger.withTag("StreamSourceLoader")

    fun resolveTargets(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean = false,
        pluginUiState: PluginsUiState = if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.initialize()
            PluginRepository.uiState.value
        } else {
            PluginsUiState(pluginsEnabled = false)
        },
    ): StreamSourceTargets {
        val isNativeServerRequest = ServerStreams.isNativeRequest(videoId)
        val serverSources = ServerStreams.sources(type, videoId, season, episode, forceRefresh)
        val preferredGroupIds = serverSources.filter { it.preferred }.mapTo(mutableSetOf()) { it.addonId }
        val installedAddons = if (isNativeServerRequest) emptyList() else AddonRepository.uiState.value.addons.enabledAddons()
        val pluginScrapers = if (AppFeaturePolicy.pluginsEnabled && !isNativeServerRequest) {
            PluginRepository.getEnabledScrapersForType(type)
        } else {
            emptyList()
        }
        val pluginProviderGroups = pluginScrapers.toPluginProviderGroups(
            repositories = pluginUiState.repositories,
            groupByRepository = pluginUiState.groupStreamsByRepository,
        )

        val streamAddons = installedAddons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            if (!manifest.supportsStream(type, videoId)) return@mapNotNull null

            InstalledStreamAddonTarget(
                addonName = addon.displayTitle.ifBlank { manifest.name },
                addonId = addon.streamAddonInstanceId(manifest.id),
                manifest = manifest,
            )
        }

        val installedAddonOrder = streamAddons.map { it.addonName }
        val installedAddonIds = streamAddons.map { it.addonId }.toSet()

        return StreamSourceTargets(
            serverSources = serverSources,
            streamAddons = streamAddons,
            pluginProviderGroups = pluginProviderGroups,
            preferredGroupIds = preferredGroupIds,
            installedAddonOrder = installedAddonOrder,
            installedAddonIds = installedAddonIds,
            enabledAddonCount = installedAddons.size,
            pluginScraperCount = pluginScrapers.size,
        )
    }

    suspend fun fetchAddonGroup(
        addon: InstalledStreamAddonTarget,
        type: String,
        videoId: String,
        forceRefresh: Boolean,
    ): AddonStreamGroup {
        val url = buildAddonResourceUrl(
            manifestUrl = addon.manifest.transportUrl,
            resource = "stream",
            type = type,
            id = videoId,
        )
        val displayName = addon.addonName
        InAppLogger.info(
            "Streams/AddonFetch",
            "Fetching streams addon=$displayName addonId=${addon.addonId} url=${InAppLogger.redactUrl(url)}",
        )
        return runCatchingUnlessCancelled {
            val payload = fetchAddonResponseText(
                url = url,
                forceRefresh = forceRefresh,
            )
            StreamParser.parse(
                payload = payload,
                addonName = displayName,
                addonId = addon.addonId,
                addonLogo = addon.manifest.logoUrl,
            )
        }.fold(
            onSuccess = { streams ->
                InAppLogger.info("Streams/AddonFetch", "Loaded streams addon=$displayName count=${streams.size}")
                AddonStreamGroup(
                    addonName = displayName,
                    addonId = addon.addonId,
                    streams = streams,
                    isLoading = false,
                )
            },
            onFailure = { err ->
                InAppLogger.warn("Streams/AddonFetch", "Failed addon=$displayName error=${InAppLogger.throwableSummary(err)}")
                AddonStreamGroup(
                    addonName = displayName,
                    addonId = addon.addonId,
                    streams = emptyList(),
                    isLoading = false,
                    error = err.message,
                )
            },
        )
    }

    suspend fun executePluginScraper(
        scraper: PluginScraper,
        providerGroup: PluginProviderGroup,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        includeScraperNameInSubtitle: Boolean = false,
    ): StreamLoadCompletion.PluginScraper {
        InAppLogger.info(
            "Streams/PluginFetch",
            "Executing scraper=${scraper.name} provider=${providerGroup.addonName} type=$type id=$videoId season=${season ?: -1} episode=${episode ?: -1}",
        )
        return PluginRepository.executeScraper(
            scraper = scraper,
            tmdbId = pluginContentId(
                videoId = videoId,
                season = season,
                episode = episode,
            ),
            mediaType = type,
            season = season,
            episode = episode,
        ).fold(
            onSuccess = { results ->
                InAppLogger.info("Streams/PluginFetch", "Scraper loaded scraper=${scraper.name} provider=${providerGroup.addonName} count=${results.size}")
                StreamLoadCompletion.PluginScraper(
                    addonId = providerGroup.addonId,
                    streams = results.map { result ->
                        result.toStreamItem(
                            scraper = scraper,
                            addonName = providerGroup.addonName,
                            addonId = providerGroup.addonId,
                            includeScraperNameInSubtitle = includeScraperNameInSubtitle,
                        )
                    },
                    error = null,
                )
            },
            onFailure = { error ->
                InAppLogger.warn("Streams/PluginFetch", "Scraper failed scraper=${scraper.name} provider=${providerGroup.addonName} error=${InAppLogger.throwableSummary(error)}")
                StreamLoadCompletion.PluginScraper(
                    addonId = providerGroup.addonId,
                    streams = emptyList(),
                    error = error.message ?: getString(Res.string.streams_failed_to_load_scraper, scraper.name),
                )
            },
        )
    }

    fun presentStreamGroup(
        group: AddonStreamGroup,
        streamBadgeRules: StreamBadgeRules,
        debridSettings: DebridSettings,
    ): AddonStreamGroup {
        val badgeGroup = StreamBadgePresentation.apply(
            groups = listOf(group),
            rules = streamBadgeRules,
        ).firstOrNull() ?: group
        return DebridStreamPresentation.apply(
            groups = listOf(badgeGroup),
            settings = debridSettings,
        ).firstOrNull() ?: badgeGroup
    }

    suspend fun checkDebridCache(
        group: AddonStreamGroup,
        eligibleGroupIds: Set<String>,
    ): AddonStreamGroup {
        if (group.addonId !in eligibleGroupIds || group.streams.isEmpty()) {
            return group
        }

        val shouldWaitForCacheCheck = LocalDebridAvailabilityService.hasPendingCacheCheck(
            groups = listOf(group),
            eligibleGroupIds = eligibleGroupIds,
        )
        if (!shouldWaitForCacheCheck) {
            return group
        }

        val checkingGroup = LocalDebridAvailabilityService.markChecking(
            groups = listOf(group),
            eligibleGroupIds = eligibleGroupIds,
        ).firstOrNull() ?: group

        return LocalDebridAvailabilityService.annotateCachedAvailability(
            groups = listOf(checkingGroup),
            eligibleGroupIds = eligibleGroupIds,
        ).firstOrNull() ?: checkingGroup
    }

    private fun failedGroup(addonName: String, addonId: String, error: Throwable) = AddonStreamGroup(
        addonName = addonName,
        addonId = addonId,
        streams = emptyList(),
        isLoading = false,
        error = error.message,
    )

    suspend fun loadStreams(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        embeddedStreams: List<StreamItem> = emptyList(),
        forceRefresh: Boolean = false,
        timeoutMs: Long = 75_000L,
    ): List<StreamItem> {
        val streamBadgeRules = StreamBadgeSettingsRepository.snapshot()
        val debridSettings = DebridSettingsRepository.snapshot()

        if (embeddedStreams.isNotEmpty()) {
            val group = AddonStreamGroup(
                addonName = embeddedStreams.first().addonName,
                addonId = "embedded",
                streams = embeddedStreams,
                isLoading = false,
            )
            val presented = presentStreamGroup(group, streamBadgeRules, debridSettings)
            return presented.streams
        }

        val targets = resolveTargets(
            type = type,
            videoId = videoId,
            season = season,
            episode = episode,
            forceRefresh = forceRefresh,
        )

        val totalSources = targets.streamAddons.size + targets.pluginProviderGroups.size + targets.serverSources.size
        if (totalSources == 0) {
            return emptyList()
        }

        val resultsChannel = Channel<Pair<Int, AddonStreamGroup>>(Channel.UNLIMITED)
        val arrivedByIndex = mutableMapOf<Int, AddonStreamGroup>()

        try {
            supervisorScope {
                var targetIndex = 0

                targets.streamAddons.forEach { addon ->
                    val index = targetIndex++
                    launch {
                        try {
                            val rawGroup = fetchAddonGroup(addon, type, videoId, forceRefresh)
                            val cachedGroup = checkDebridCache(rawGroup, targets.installedAddonIds)
                            resultsChannel.send(index to presentStreamGroup(cachedGroup, streamBadgeRules, debridSettings))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            resultsChannel.send(index to failedGroup(addon.addonName, addon.addonId, e))
                        }
                    }
                }

                targets.pluginProviderGroups.forEach { providerGroup ->
                    val index = targetIndex++
                    launch {
                        try {
                            val scraperResults = providerGroup.scrapers.map { scraper ->
                                async {
                                    executePluginScraper(
                                        scraper = scraper,
                                        providerGroup = providerGroup,
                                        type = type,
                                        videoId = videoId,
                                        season = season,
                                        episode = episode,
                                        includeScraperNameInSubtitle = false,
                                    )
                                }
                            }.awaitAll()

                            val allPluginStreams = scraperResults.flatMap { it.streams }
                            val mergedStreams = StreamBadgePresentation.applyToStreams(allPluginStreams, streamBadgeRules)
                                .sortedForGroupedDisplay()
                            val group = AddonStreamGroup(
                                addonName = providerGroup.addonName,
                                addonId = providerGroup.addonId,
                                streams = mergedStreams,
                                isLoading = false,
                            )
                            resultsChannel.send(index to presentStreamGroup(group, streamBadgeRules, debridSettings))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            resultsChannel.send(index to failedGroup(providerGroup.addonName, providerGroup.addonId, e))
                        }
                    }
                }

                targets.serverSources.forEach { source ->
                    val index = targetIndex++
                    launch {
                        try {
                            val rawGroup = source.load()
                            resultsChannel.send(index to presentStreamGroup(rawGroup, streamBadgeRules, debridSettings))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            resultsChannel.send(index to failedGroup(source.addonName, source.addonId, e))
                        }
                    }
                }

                withTimeoutOrNull(timeoutMs) {
                    repeat(totalSources) {
                        val (index, group) = resultsChannel.receive()
                        arrivedByIndex[index] = group
                    }
                }
                coroutineContext[Job]?.children?.forEach { it.cancel() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w(e) { "Stream loading error: ${e.message}" }
        }

        val arrivedGroups = (0 until totalSources).mapNotNull { arrivedByIndex[it] }
        if (arrivedGroups.isEmpty()) return emptyList()

        val orderedGroups = StreamAutoPlaySelector.orderAddonStreams(
            groups = arrivedGroups,
            installedOrder = targets.installedAddonOrder,
            preferredGroupIds = targets.preferredGroupIds,
        )
        return orderedGroups.flatMap { it.streams }
    }
}
