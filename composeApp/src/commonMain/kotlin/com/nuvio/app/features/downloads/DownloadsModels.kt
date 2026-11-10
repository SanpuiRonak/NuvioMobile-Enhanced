package com.nuvio.app.features.downloads

import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.SubtitleAddonRequest
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.serialization.Serializable
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_enqueue_missing_url
import nuvio.composeapp.generated.resources.downloads_enqueue_replaced
import nuvio.composeapp.generated.resources.downloads_enqueue_started
import nuvio.composeapp.generated.resources.downloads_enqueue_unsupported_format
import nuvio.composeapp.generated.resources.downloads_enqueue_requires_wifi
import org.jetbrains.compose.resources.getString

@Serializable
enum class DownloadStatus {
    Queued,
    Downloading,
    Paused,
    Completed,
    Failed,
}

@Serializable
enum class DownloadPreferredResolution(
    val resolution: Int,
    val label: String,
) {
    P720(720, "720p"),
    P1080(1080, "1080p"),
    P2160(2160, "2160p (4K)");

    companion object {
        val DEFAULT = P1080

        fun fromStorage(value: String?): DownloadPreferredResolution {
            if (value.isNullOrBlank()) return DEFAULT
            val trimmed = value.trim()
            return entries.firstOrNull {
                it.name.equals(trimmed, ignoreCase = true) ||
                    it.resolution.toString() == trimmed ||
                    it.label.equals(trimmed, ignoreCase = true)
            } ?: DEFAULT
        }
    }
}


@Serializable
data class StoredDownloadedVideoMeta(
    val id: String,
    val title: String,
    val released: String? = null,
    val thumbnail: String? = null,
    val seasonPoster: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    val tmdbRating: Double? = null,
    val imdbRating: Double? = null,
    val rating: Double? = null,
)

@Serializable
data class StoredDownloadedCastMeta(
    val name: String,
    val role: String? = null,
    val photo: String? = null,
    val tmdbId: Int? = null,
)

@Serializable
data class StoredDownloadedShowMeta(
    val showId: String,
    val title: String,
    val type: String = "series",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val status: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val seasonPosters: Map<Int, String> = emptyMap(),
    val videos: List<StoredDownloadedVideoMeta> = emptyList(),
    val cast: List<StoredDownloadedCastMeta> = emptyList(),
)

@Serializable
data class DownloadItem(
    val id: String,
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val providerName: String,
    val providerAddonId: String? = null,
    val sourceUrl: String,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val sourceResponseHeaders: Map<String, String> = emptyMap(),
    val subtitleRequests: List<SubtitleAddonRequest> = emptyList(),
    val sourceSubtitles: List<StreamSubtitle> = emptyList(),
    val localFileUri: String? = null,
    val fileName: String,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val episodeOverview: String? = null,
    val episodeReleased: String? = null,
    val episodeRuntime: Int? = null,
    val episodeImdbRating: Double? = null,
    val episodeTmdbRating: Double? = null,
    val episodeRating: Double? = null,
    val seasonPoster: String? = null,
    val showDescription: String? = null,
    val showGenres: List<String> = emptyList(),
    val showReleaseInfo: String? = null,
    val showImdbRating: String? = null,
    val showStatus: String? = null,
) {
    val isEpisode: Boolean
        get() = seasonNumber != null && episodeNumber != null

    val isDownloading: Boolean
        get() = status == DownloadStatus.Downloading

    val isActive: Boolean
        get() = status == DownloadStatus.Downloading || status == DownloadStatus.Queued

    val isPlayable: Boolean
        get() = status == DownloadStatus.Completed && !localFileUri.isNullOrBlank()

    val displaySubtitle: String
        get() = episodeTitle.orEmpty()

    val progressFraction: Float
        get() {
            val total = totalBytes?.takeIf { it > 0L } ?: return 0f
            return (downloadedBytes.toDouble() / total.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }

    val logicalContentKey: String
        get() = if (isEpisode) {
            "${parentMetaId.trim()}|${seasonNumber ?: -1}|${episodeNumber ?: -1}"
        } else {
            "${parentMetaId.trim()}|movie"
        }
}

data class DownloadsUiState(
    val items: List<DownloadItem> = emptyList(),
) {
    val activeItems: List<DownloadItem>
        get() = items.filter { it.status != DownloadStatus.Completed }

    val completedItems: List<DownloadItem>
        get() = items.filter { it.status == DownloadStatus.Completed }
}

enum class DownloadEnqueueResult {
    Started,
    Replaced,
    MissingUrl,
    UnsupportedFormat,
    RequiresWifi;

    fun toastMessage(): String = runBlocking {
        when (this@DownloadEnqueueResult) {
            Started -> getString(Res.string.downloads_enqueue_started)
            Replaced -> getString(Res.string.downloads_enqueue_replaced)
            MissingUrl -> getString(Res.string.downloads_enqueue_missing_url)
            UnsupportedFormat -> getString(Res.string.downloads_enqueue_unsupported_format)
            RequiresWifi -> getString(Res.string.downloads_enqueue_requires_wifi)
        }
    }
}

internal fun List<DownloadItem>.sortedForSeriesDownloads(): List<DownloadItem> =
    sortedWith(downloadSeriesEpisodeComparator)

internal val downloadSeriesEpisodeComparator: Comparator<DownloadItem> =
    compareBy<DownloadItem> { it.seasonNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeTitle?.trim().orEmpty().lowercase() }
        .thenBy { it.title.trim().lowercase() }
        .thenBy { it.id }

internal fun DownloadItem.displayTitle(): String =
    if (isEpisode) {
        episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title
    } else {
        title
    }

fun DownloadItem.toMetaVideo(): MetaVideo =
    MetaVideo(
        id = videoId,
        title = displayTitle(),
        released = episodeReleased,
        available = true,
        thumbnail = episodeThumbnail ?: background ?: poster,
        seasonPoster = seasonPoster,
        season = seasonNumber,
        episode = episodeNumber,
        overview = episodeOverview,
        runtime = episodeRuntime,
        tmdbRating = episodeTmdbRating,
        imdbRating = episodeImdbRating,
        rating = episodeRating ?: episodeTmdbRating ?: episodeImdbRating,
    )

fun MetaVideo.toStored(): StoredDownloadedVideoMeta =
    StoredDownloadedVideoMeta(
        id = id,
        title = title,
        released = released,
        thumbnail = thumbnail,
        seasonPoster = seasonPoster,
        season = season,
        episode = episode,
        overview = overview,
        runtime = runtime,
        tmdbRating = tmdbRating,
        imdbRating = imdbRating,
        rating = rating,
    )

fun StoredDownloadedVideoMeta.toMetaVideo(): MetaVideo =
    MetaVideo(
        id = id,
        title = title,
        released = released,
        available = true,
        thumbnail = thumbnail,
        seasonPoster = seasonPoster,
        season = season,
        episode = episode,
        overview = overview,
        runtime = runtime,
        tmdbRating = tmdbRating,
        imdbRating = imdbRating,
        rating = rating ?: tmdbRating ?: imdbRating,
    )

fun MetaPerson.toStored(): StoredDownloadedCastMeta =
    StoredDownloadedCastMeta(
        name = name,
        role = role,
        photo = photo,
        tmdbId = tmdbId,
    )

fun StoredDownloadedCastMeta.toMetaPerson(): MetaPerson =
    MetaPerson(
        name = name,
        role = role,
        photo = photo,
        tmdbId = tmdbId,
    )

/** Builds offline details from the stored show metadata plus the show's downloaded items. */
fun StoredDownloadedShowMeta.toMetaDetails(showDownloads: List<DownloadItem>): MetaDetails {
    val downloadedEpisodes = showDownloads
        .filter { it.parentMetaId == showId && it.isEpisode }
        .sortedForSeriesDownloads()

    val storedVideos = videos.map { it.toMetaVideo() }
    val existingKeys = storedVideos.mapNotNull { v ->
        if (v.season != null && v.episode != null) "${v.season}:${v.episode}" else v.id.takeIf { it.isNotBlank() }
    }.toSet()

    val missingDownloaded = downloadedEpisodes
        .filter { ep ->
            val key = if (ep.seasonNumber != null && ep.episodeNumber != null) "${ep.seasonNumber}:${ep.episodeNumber}" else ep.videoId
            key !in existingKeys
        }
        .map { it.toMetaVideo() }

    val allVideos = if (storedVideos.isNotEmpty()) {
        (storedVideos + missingDownloaded).sortedWith(
            compareBy<MetaVideo> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.id }
        )
    } else {
        downloadedEpisodes.map { it.toMetaVideo() }
    }

    val mergedPosters = downloadedEpisodes.seasonPosters() + seasonPosters

    return MetaDetails(
        id = showId,
        type = type,
        name = title,
        poster = poster,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        status = status,
        imdbRating = imdbRating,
        genres = genres,
        seasonPosters = mergedPosters,
        videos = allVideos,
        cast = cast.map { it.toMetaPerson() },
    )
}

fun List<DownloadItem>.toOfflineMetaDetails(showId: String): MetaDetails {
    val showEpisodes = filter { it.parentMetaId == showId }
    val first = showEpisodes.firstOrNull()
    val showTitle = first?.title.orEmpty()
    val poster = showEpisodes.firstNotNullOfOrNull { it.poster }
    val background = showEpisodes.firstNotNullOfOrNull { it.background }
    val logo = showEpisodes.firstNotNullOfOrNull { it.logo }
    val description = showEpisodes.firstNotNullOfOrNull { it.showDescription }
    val releaseInfo = showEpisodes.firstNotNullOfOrNull { it.showReleaseInfo }
    val status = showEpisodes.firstNotNullOfOrNull { it.showStatus }
    val imdbRating = showEpisodes.firstNotNullOfOrNull { it.showImdbRating }
    val genres = showEpisodes.firstOrNull { it.showGenres.isNotEmpty() }?.showGenres ?: emptyList()

    val seasonPosters = showEpisodes.seasonPosters()

    val videos = showEpisodes.sortedForSeriesDownloads().map { it.toMetaVideo() }

    return MetaDetails(
        id = showId,
        type = first?.parentMetaType ?: "series",
        name = showTitle,
        poster = poster,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        status = status,
        imdbRating = imdbRating,
        genres = genres,
        seasonPosters = seasonPosters,
        videos = videos,
    )
}

/** The first known poster for each season among these downloads. */
private fun List<DownloadItem>.seasonPosters(): Map<Int, String> =
    asReversed()
        .mapNotNull { item -> item.seasonNumber?.let { season -> item.seasonPoster?.let { season to it } } }
        .toMap()

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val unit = ByteUnit.forBytes(bytes)
    return if (unit == ByteUnit.B) {
        "$bytes ${localizedByteUnit(unit.name)}"
    } else {
        "${unit.format(bytes)} ${localizedByteUnit(unit.name)}"
    }
}

/** "1.2/3.4 GB" when both sizes share a unit, otherwise "512.0 MB/1.2 GB". */
internal fun formatDownloadBadgeProgress(downloadedBytes: Long, totalBytes: Long?): String {
    if (totalBytes == null || totalBytes <= 0L) return formatBytes(downloadedBytes)
    val unit = ByteUnit.forBytes(totalBytes)
    return if (unit == ByteUnit.forBytes(downloadedBytes)) {
        "${unit.format(downloadedBytes)}/${unit.format(totalBytes)} ${localizedByteUnit(unit.name)}"
    } else {
        "${formatBytes(downloadedBytes)}/${formatBytes(totalBytes)}"
    }
}

private enum class ByteUnit(val bytes: Double) {
    B(1.0),
    KB(1024.0),
    MB(1024.0 * 1024.0),
    GB(1024.0 * 1024.0 * 1024.0);

    /** The value in this unit, truncated to one decimal place. */
    fun format(value: Long): Double = ((value / bytes) * 10.0).toInt() / 10.0

    companion object {
        fun forBytes(value: Long): ByteUnit = entries.last { value >= it.bytes || it == B }
    }
}

data class SeriesDownloadStats(
    val totalBytes: Long,
    val avgBytes: Long,
    val medianBytes: Long,
)

internal fun calculateSeriesDownloadStats(episodes: List<DownloadItem>): SeriesDownloadStats {
    if (episodes.isEmpty()) return SeriesDownloadStats(0L, 0L, 0L)
    val sizes = episodes.map { it.totalBytes?.takeIf { b -> b > 0L } ?: it.downloadedBytes }
    val total = sizes.sum()
    val avg = total / sizes.size
    val sorted = sizes.sorted()
    val median = if (sorted.size % 2 == 1) {
        sorted[sorted.size / 2]
    } else {
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2L
    }
    return SeriesDownloadStats(totalBytes = total, avgBytes = avg, medianBytes = median)
}

