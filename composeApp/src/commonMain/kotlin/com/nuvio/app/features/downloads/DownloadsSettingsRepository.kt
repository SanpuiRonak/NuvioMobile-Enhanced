package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device-level download preferences. Not synced across profiles/devices: whether the
 * current device is fine using mobile data for downloads, and where downloads are saved,
 * are inherently per-device facts, not per-profile ones.
 */
object DownloadsSettingsRepository {
    private val _allowMobileDataDownloads = MutableStateFlow(false)
    val allowMobileDataDownloads: StateFlow<Boolean> = _allowMobileDataDownloads.asStateFlow()

    private val _showDownloadButton = MutableStateFlow(true)
    val showDownloadButton: StateFlow<Boolean> = _showDownloadButton.asStateFlow()

    private val _downloadLocationUri = MutableStateFlow<String?>(null)
    val downloadLocationUri: StateFlow<String?> = _downloadLocationUri.asStateFlow()

    private val _preferredResolution = MutableStateFlow(DownloadPreferredResolution.DEFAULT)
    val preferredResolution: StateFlow<DownloadPreferredResolution> = _preferredResolution.asStateFlow()

    const val DEFAULT_MAX_DOWNLOAD_CONCURRENCY = 3
    const val DEFAULT_MAX_SERIES_EPISODE_CONCURRENCY = 1
    const val DEFAULT_MAX_CONCURRENT_SHOWS = 1

    private val _maxDownloadConcurrency = MutableStateFlow(DEFAULT_MAX_DOWNLOAD_CONCURRENCY)
    val maxDownloadConcurrency: StateFlow<Int> = _maxDownloadConcurrency.asStateFlow()

    private val _maxSeriesEpisodeConcurrency = MutableStateFlow(DEFAULT_MAX_SERIES_EPISODE_CONCURRENCY)
    val maxSeriesEpisodeConcurrency: StateFlow<Int> = _maxSeriesEpisodeConcurrency.asStateFlow()

    private val _maxConcurrentShows = MutableStateFlow(DEFAULT_MAX_CONCURRENT_SHOWS)
    val maxConcurrentShows: StateFlow<Int> = _maxConcurrentShows.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _allowMobileDataDownloads.value = DownloadsSettingsStorage.loadAllowMobileDataDownloads() ?: false
        _showDownloadButton.value = DownloadsSettingsStorage.loadShowDownloadButton() ?: true
        _preferredResolution.value = DownloadsSettingsStorage.loadPreferredResolution() ?: DownloadPreferredResolution.DEFAULT
        _maxDownloadConcurrency.value = DownloadsSettingsStorage.loadMaxDownloadConcurrency() ?: DEFAULT_MAX_DOWNLOAD_CONCURRENCY
        _maxSeriesEpisodeConcurrency.value = DownloadsSettingsStorage.loadMaxSeriesEpisodeConcurrency() ?: DEFAULT_MAX_SERIES_EPISODE_CONCURRENCY
        _maxConcurrentShows.value = DownloadsSettingsStorage.loadMaxConcurrentShows() ?: DEFAULT_MAX_CONCURRENT_SHOWS
        _downloadLocationUri.value = DownloadsStorage.getDownloadLocationUri()
    }

    fun setAllowMobileDataDownloads(enabled: Boolean) {
        ensureLoaded()
        if (_allowMobileDataDownloads.value == enabled) return
        _allowMobileDataDownloads.value = enabled
        DownloadsSettingsStorage.saveAllowMobileDataDownloads(enabled)
    }

    fun setShowDownloadButton(enabled: Boolean) {
        ensureLoaded()
        if (_showDownloadButton.value == enabled) return
        _showDownloadButton.value = enabled
        DownloadsSettingsStorage.saveShowDownloadButton(enabled)
    }

    fun setPreferredResolution(resolution: DownloadPreferredResolution) {
        ensureLoaded()
        if (_preferredResolution.value == resolution) return
        _preferredResolution.value = resolution
        DownloadsSettingsStorage.savePreferredResolution(resolution)
    }

    fun setMaxDownloadConcurrency(count: Int) {
        ensureLoaded()
        val clamped = count.coerceIn(1, 10)
        if (_maxDownloadConcurrency.value == clamped) return
        _maxDownloadConcurrency.value = clamped
        DownloadsSettingsStorage.saveMaxDownloadConcurrency(clamped)
        DownloadsRepository.advanceDownloadQueue()
    }

    fun setMaxSeriesEpisodeConcurrency(count: Int) {
        ensureLoaded()
        val clamped = count.coerceIn(1, 10)
        if (_maxSeriesEpisodeConcurrency.value == clamped) return
        _maxSeriesEpisodeConcurrency.value = clamped
        DownloadsSettingsStorage.saveMaxSeriesEpisodeConcurrency(clamped)
        DownloadsRepository.advanceDownloadQueue()
    }

    fun setMaxConcurrentShows(count: Int) {
        ensureLoaded()
        val clamped = count.coerceIn(1, 10)
        if (_maxConcurrentShows.value == clamped) return
        _maxConcurrentShows.value = clamped
        DownloadsSettingsStorage.saveMaxConcurrentShows(clamped)
        DownloadsRepository.advanceDownloadQueue()
    }

    fun setDownloadLocationUri(uri: String?) {
        ensureLoaded()
        _downloadLocationUri.value = uri
        DownloadsStorage.setDownloadLocationUri(uri)
    }

    // No-op: called from DownloadsRepository.onProfileChanged() for symmetry with the other
    // per-profile repositories it resets, but these settings are device-level (see class doc)
    // and already loaded once for the whole process lifetime, so there's nothing to reload.
    fun onProfileChanged() = Unit
}
