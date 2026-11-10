package com.nuvio.app.features.downloads

internal expect object DownloadsSettingsStorage {
    fun loadAllowMobileDataDownloads(): Boolean?
    fun saveAllowMobileDataDownloads(enabled: Boolean)
    fun loadShowDownloadButton(): Boolean?
    fun saveShowDownloadButton(enabled: Boolean)
    fun loadPreferredResolution(): String?
    fun savePreferredResolution(storageValue: String)
    fun loadInt(key: String): Int?
    fun saveInt(key: String, value: Int)
}
