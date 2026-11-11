package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"
    private const val offlineMetadataKey = "downloads_offline_metadata"
    private const val downloadLocationUriKey = "download_location_uri"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }

    actual fun loadOfflineMetadataPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(offlineMetadataKey))

    actual fun saveOfflineMetadataPayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(offlineMetadataKey))
    }

    actual fun getDownloadLocationUri(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(downloadLocationUriKey))

    actual fun setDownloadLocationUri(uri: String?) {
        if (uri == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(downloadLocationUriKey))
        } else {
            NSUserDefaults.standardUserDefaults.setObject(uri, forKey = ProfileScopedKey.of(downloadLocationUriKey))
        }
    }
}
