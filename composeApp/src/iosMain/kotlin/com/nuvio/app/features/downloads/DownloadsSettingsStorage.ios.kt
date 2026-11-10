package com.nuvio.app.features.downloads

import platform.Foundation.NSUserDefaults

actual object DownloadsSettingsStorage {
    private const val allowMobileDataDownloadsKey = "allow_mobile_data_downloads"
    private const val showDownloadButtonKey = "show_download_button"
    private const val preferredResolutionKey = "preferred_download_resolution"

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

    actual fun loadPreferredResolution(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(preferredResolutionKey)

    actual fun savePreferredResolution(storageValue: String) {
        NSUserDefaults.standardUserDefaults.setObject(storageValue, forKey = preferredResolutionKey)
    }

    actual fun loadInt(key: String): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else null
    }

    actual fun saveInt(key: String, value: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(value.toLong(), forKey = key)
    }
}
