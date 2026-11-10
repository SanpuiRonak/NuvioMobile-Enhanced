package com.nuvio.app.features.downloads

import platform.Foundation.NSUserDefaults

actual object DownloadsSettingsStorage {
    private const val allowMobileDataDownloadsKey = "allow_mobile_data_downloads"
    private const val showDownloadButtonKey = "show_download_button"
    private const val preferredResolutionKey = "preferred_download_resolution"
    private const val maxDownloadConcurrencyKey = "max_download_concurrency"
    private const val maxSeriesEpisodeConcurrencyKey = "max_series_episode_concurrency"
    private const val maxConcurrentShowsKey = "max_concurrent_shows"

    actual fun loadAllowMobileDataDownloads(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(allowMobileDataDownloadsKey) != null) {
            defaults.boolForKey(allowMobileDataDownloadsKey)
        } else {
            null
        }
    }

    actual fun saveAllowMobileDataDownloads(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = allowMobileDataDownloadsKey)
    }

    actual fun loadShowDownloadButton(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(showDownloadButtonKey) != null) {
            defaults.boolForKey(showDownloadButtonKey)
        } else {
            null
        }
    }

    actual fun saveShowDownloadButton(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = showDownloadButtonKey)
    }

    actual fun loadPreferredResolution(): DownloadPreferredResolution? {
        val raw = NSUserDefaults.standardUserDefaults.stringForKey(preferredResolutionKey)
        return raw?.let(DownloadPreferredResolution::fromStorage)
    }

    actual fun savePreferredResolution(resolution: DownloadPreferredResolution) {
        NSUserDefaults.standardUserDefaults.setObject(resolution.name, forKey = preferredResolutionKey)
    }

    actual fun loadMaxDownloadConcurrency(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(maxDownloadConcurrencyKey) != null) {
            defaults.integerForKey(maxDownloadConcurrencyKey).toInt()
        } else {
            null
        }
    }

    actual fun saveMaxDownloadConcurrency(count: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(count.toLong(), forKey = maxDownloadConcurrencyKey)
    }

    actual fun loadMaxSeriesEpisodeConcurrency(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(maxSeriesEpisodeConcurrencyKey) != null) {
            defaults.integerForKey(maxSeriesEpisodeConcurrencyKey).toInt()
        } else {
            null
        }
    }

    actual fun saveMaxSeriesEpisodeConcurrency(count: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(count.toLong(), forKey = maxSeriesEpisodeConcurrencyKey)
    }

    actual fun loadMaxConcurrentShows(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(maxConcurrentShowsKey) != null) {
            defaults.integerForKey(maxConcurrentShowsKey).toInt()
        } else {
            null
        }
    }

    actual fun saveMaxConcurrentShows(count: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(count.toLong(), forKey = maxConcurrentShowsKey)
    }
}
