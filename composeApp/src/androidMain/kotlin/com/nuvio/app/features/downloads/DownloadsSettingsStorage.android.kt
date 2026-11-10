package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences

actual object DownloadsSettingsStorage {
    private const val preferencesName = "nuvio_downloads_settings"
    private const val allowMobileDataDownloadsKey = "allow_mobile_data_downloads"
    private const val showDownloadButtonKey = "show_download_button"
    private const val preferredResolutionKey = "preferred_download_resolution"
    private const val maxDownloadConcurrencyKey = "max_download_concurrency"
    private const val maxSeriesEpisodeConcurrencyKey = "max_series_episode_concurrency"
    private const val maxConcurrentShowsKey = "max_concurrent_shows"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadAllowMobileDataDownloads(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(allowMobileDataDownloadsKey)) {
                prefs.getBoolean(allowMobileDataDownloadsKey, false)
            } else {
                null
            }
        }

    actual fun saveAllowMobileDataDownloads(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(allowMobileDataDownloadsKey, enabled)
            ?.apply()
    }

    actual fun loadShowDownloadButton(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(showDownloadButtonKey)) {
                prefs.getBoolean(showDownloadButtonKey, false)
            } else {
                null
            }
        }

    actual fun saveShowDownloadButton(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(showDownloadButtonKey, enabled)
            ?.apply()
    }

    actual fun loadPreferredResolution(): DownloadPreferredResolution? =
        preferences?.getString(preferredResolutionKey, null)?.let(DownloadPreferredResolution::fromStorage)

    actual fun savePreferredResolution(resolution: DownloadPreferredResolution) {
        preferences
            ?.edit()
            ?.putString(preferredResolutionKey, resolution.name)
            ?.apply()
    }

    actual fun loadMaxDownloadConcurrency(): Int? =
        preferences?.let { prefs ->
            if (prefs.contains(maxDownloadConcurrencyKey)) {
                prefs.getInt(maxDownloadConcurrencyKey, 3)
            } else {
                null
            }
        }

    actual fun saveMaxDownloadConcurrency(count: Int) {
        preferences
            ?.edit()
            ?.putInt(maxDownloadConcurrencyKey, count)
            ?.apply()
    }

    actual fun loadMaxSeriesEpisodeConcurrency(): Int? =
        preferences?.let { prefs ->
            if (prefs.contains(maxSeriesEpisodeConcurrencyKey)) {
                prefs.getInt(maxSeriesEpisodeConcurrencyKey, 1)
            } else {
                null
            }
        }

    actual fun saveMaxSeriesEpisodeConcurrency(count: Int) {
        preferences
            ?.edit()
            ?.putInt(maxSeriesEpisodeConcurrencyKey, count)
            ?.apply()
    }

    actual fun loadMaxConcurrentShows(): Int? =
        preferences?.let { prefs ->
            if (prefs.contains(maxConcurrentShowsKey)) {
                prefs.getInt(maxConcurrentShowsKey, 1)
            } else {
                null
            }
        }

    actual fun saveMaxConcurrentShows(count: Int) {
        preferences
            ?.edit()
            ?.putInt(maxConcurrentShowsKey, count)
            ?.apply()
    }
}
