package com.nuvio.app.features.downloads

internal expect object DownloadsSettingsStorage {
    fun loadAllowMobileDataDownloads(): Boolean?
    fun saveAllowMobileDataDownloads(enabled: Boolean)
    fun loadShowDownloadButton(): Boolean?
    fun saveShowDownloadButton(enabled: Boolean)
    fun loadPreferredResolution(): DownloadPreferredResolution?
    fun savePreferredResolution(resolution: DownloadPreferredResolution)
    fun loadMaxDownloadConcurrency(): Int?
    fun saveMaxDownloadConcurrency(count: Int)
    fun loadMaxSeriesEpisodeConcurrency(): Int?
    fun saveMaxSeriesEpisodeConcurrency(count: Int)
    fun loadMaxConcurrentShows(): Int?
    fun saveMaxConcurrentShows(count: Int)
}
