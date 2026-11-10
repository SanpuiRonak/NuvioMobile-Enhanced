package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences

actual object DownloadsSettingsStorage {
    private const val preferencesName = "nuvio_downloads_settings"
    private const val allowMobileDataDownloadsKey = "allow_mobile_data_downloads"
    private const val showDownloadButtonKey = "show_download_button"
    private const val preferredResolutionKey = "preferred_download_resolution"

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

    actual fun loadPreferredResolution(): String? =
        preferences?.getString(preferredResolutionKey, null)

    actual fun savePreferredResolution(storageValue: String) {
        preferences
            ?.edit()
            ?.putString(preferredResolutionKey, storageValue)
            ?.apply()
    }

    actual fun loadInt(key: String): Int? =
        preferences?.takeIf { it.contains(key) }?.getInt(key, 0)

    actual fun saveInt(key: String, value: Int) {
        preferences
            ?.edit()
            ?.putInt(key, value)
            ?.apply()
    }
}
