package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamFacts
import com.nuvio.app.features.debrid.DebridStreamMetadata
import com.nuvio.app.features.debrid.DebridStreamPreferences
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.downloads.DownloadPreferredResolution
import com.nuvio.app.features.downloads.isSupportedDownloadUrl

internal data class StreamSelectionResult(
    val stream: StreamItem,
    val facts: DebridStreamFacts,
    val meetsPreference: Boolean,
)

/**
 * Reusable selector for stream prefill and suggested download selection.
 *
 * Requirements:
 * - Eligibility limited to download-capable streams.
 * - Normal case: smallest known size at-or-above preferred resolution; known size beats unknown; tie: input order.
 * - Fallback: highest known lower resolution then smallest size (known size beats unknown; tie: input order).
 * - If no known resolution: smallest size, then first in input order.
 * - [StreamSelectionResult.meetsPreference] is true only for the normal case (chosen resolution >= preferred).
 */
internal object StreamSourcePrefillSelector {

    private const val MIN_VALID_FILE_SIZE_BYTES = 10_000_000L
    private const val MAX_VALID_FILE_SIZE_BYTES = 250_000_000_000L

    fun select(
        streams: List<StreamItem>,
        preferredResolution: DownloadPreferredResolution = DownloadPreferredResolution.DEFAULT,
        includeUnresolvedResolverStreams: Boolean = false,
    ): StreamSelectionResult? = select(
        streams = streams,
        preferredResolution = preferredResolution.resolution,
        includeUnresolvedResolverStreams = includeUnresolvedResolverStreams,
    )

    fun selectForBatch(
        streams: List<StreamItem>,
        preferredResolution: DownloadPreferredResolution = DownloadPreferredResolution.DEFAULT,
    ): StreamSelectionResult? = select(
        streams = streams,
        preferredResolution = preferredResolution.resolution,
        includeUnresolvedResolverStreams = true,
    )

    fun selectForBatch(
        streams: List<StreamItem>,
        preferredResolution: Int,
    ): StreamSelectionResult? = select(
        streams = streams,
        preferredResolution = preferredResolution,
        includeUnresolvedResolverStreams = true,
    )

    fun select(
        streams: List<StreamItem>,
        preferredResolution: Int,
        includeUnresolvedResolverStreams: Boolean = false,
    ): StreamSelectionResult? {
        val eligible = streams.filter { stream ->
            if (includeUnresolvedResolverStreams) {
                val direct = stream.playableDirectUrl?.trim()?.takeIf { it.isNotBlank() }
                if (direct != null) {
                    direct.isSupportedDownloadUrl()
                } else {
                    stream.isResolverDependent() || stream.isDownloadCapable()
                }
            } else {
                stream.isDownloadCapable()
            }
        }
        if (eligible.isEmpty()) return null

        val candidates = eligible.mapIndexed { index, stream ->
            IndexedCandidate(
                index = index,
                stream = stream,
                facts = DebridStreamMetadata.facts(stream, DebridStreamPreferences()),
                fileSizeBytes = stream.clientResolve?.stream?.raw?.size
                    ?.takeIf(::isPlausibleFileSize)
                    ?: stream.behaviorHints.videoSize?.takeIf(::isPlausibleFileSize),
            )
        }

        // 1. Normal case: smallest known size at-or-above preferred resolution
        val atOrAbove = candidates.filter { it.resolutionValue >= preferredResolution && it.resolutionValue > 0 }
        if (atOrAbove.isNotEmpty()) {
            val selected = atOrAbove.minWithOrNull(
                compareBy<IndexedCandidate> { it.size == null }
                    .thenBy { it.size ?: Long.MAX_VALUE }
                    .thenBy { it.index },
            ) ?: return null
            return StreamSelectionResult(
                stream = selected.stream,
                facts = selected.facts,
                meetsPreference = true,
            )
        }

        // 2. Fallback: highest known lower resolution then smallest size
        val lowerResolutions = candidates.filter { it.resolutionValue in 1 until preferredResolution }
        if (lowerResolutions.isNotEmpty()) {
            val selected = lowerResolutions.minWithOrNull(
                compareByDescending<IndexedCandidate> { it.resolutionValue }
                    .thenBy { it.size == null }
                    .thenBy { it.size ?: Long.MAX_VALUE }
                    .thenBy { it.index },
            ) ?: return null
            return StreamSelectionResult(
                stream = selected.stream,
                facts = selected.facts,
                meetsPreference = false,
            )
        }

        // 3. No known resolution: smallest size, then first in input order
        val selected = candidates.minWithOrNull(
            compareBy<IndexedCandidate> { it.size == null }
                .thenBy { it.size ?: Long.MAX_VALUE }
                .thenBy { it.index },
        ) ?: return null

        return StreamSelectionResult(
            stream = selected.stream,
            facts = selected.facts,
            meetsPreference = false,
        )
    }

    private data class IndexedCandidate(
        val index: Int,
        val stream: StreamItem,
        val facts: DebridStreamFacts,
        val fileSizeBytes: Long?,
    ) {
        val resolutionValue: Int get() = facts.resolution.value
        val size: Long? get() = fileSizeBytes
    }

    private fun isPlausibleFileSize(size: Long): Boolean =
        size in MIN_VALID_FILE_SIZE_BYTES..MAX_VALID_FILE_SIZE_BYTES
}

fun StreamItem.isResolverDependent(): Boolean =
    isDirectDebridStream || needsLocalDebridResolve

/** Checks whether the stream can be downloaded. */
fun StreamItem.isDownloadCapable(): Boolean {
    if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(this)) return true
    val direct = playableDirectUrl?.trim()?.takeIf { it.isNotBlank() } ?: return false
    return direct.isSupportedDownloadUrl()
}
