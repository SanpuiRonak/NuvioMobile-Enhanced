package com.nuvio.app.features.downloads

import android.os.Environment
import android.os.StatFs
import java.io.File

actual object DownloadsStoragePlatform {
    actual fun getDeviceStorageStats(): DeviceStorageStats {
        return runCatching {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            DeviceStorageStats(
                totalBytes = (totalBlocks * blockSize).coerceAtLeast(0L),
                freeBytes = (availableBlocks * blockSize).coerceAtLeast(0L),
            )
        }.getOrElse {
            val file = File("/")
            DeviceStorageStats(
                totalBytes = file.totalSpace.coerceAtLeast(0L),
                freeBytes = file.usableSpace.coerceAtLeast(0L),
            )
        }
    }
}
