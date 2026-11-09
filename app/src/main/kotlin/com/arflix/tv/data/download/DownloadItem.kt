package com.arflix.tv.data.download

import com.arflix.tv.data.model.MediaType

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

data class DownloadItem(
    val id: String, // Unique identifier e.g. "tg_${fileId}"
    val fileId: Int, // Telegram TDLib file ID
    val fileUniqueId: String? = null, // Telegram global unique identifier
    val remoteFileId: String? = null, // Telegram persistent remote identifier (valid across TDLib restarts)
    val mediaType: MediaType,
    val mediaId: Int,
    val title: String,
    val subtitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterUrl: String? = null,
    val quality: String = "",
    val streamUrl: String = "",
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val localPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val sourceTitle: String? = null, // Raw source filename / title (e.g. "Matrix.1999.1080p.DUBLADO.mkv")
    val audioLabel: String? = null, // "Dublado", "Legendado", "Nacional", "Dual Áudio"
    val speedBytesPerSec: Long = 0L // Current download speed in bytes/second
) {
    val progress: Float
        get() = when {
            status == DownloadStatus.COMPLETED -> 1f
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

    val formattedSpeed: String
        get() = formatSpeed(speedBytesPerSec)

    companion object {
        fun formatSpeed(bytesPerSec: Long): String = when {
            bytesPerSec <= 0L -> "0 B/s"
            bytesPerSec < 1024L -> "$bytesPerSec B/s"
            bytesPerSec < 1024L * 1024L -> "%.0f KB/s".format(java.util.Locale.US, bytesPerSec / 1024.0)
            bytesPerSec < 1024L * 1024L * 1024L -> "%.1f MB/s".format(java.util.Locale.US, bytesPerSec / (1024.0 * 1024.0))
            else -> "%.2f GB/s".format(java.util.Locale.US, bytesPerSec / (1024.0 * 1024.0 * 1024.0))
        }

        fun parseAudioLabel(source: String?, description: String? = null, rawLabel: String? = null): String? {
            val blob = listOfNotNull(source, description, rawLabel).joinToString(" ").uppercase()
            return when {
                blob.contains("DUBLADO") || Regex("""\b(DUB|DUBL)\b""").containsMatchIn(blob) -> "Dublado"
                blob.contains("LEGENDADO") || Regex("""\b(LEG|LEGEN)\b""").containsMatchIn(blob) -> "Legendado"
                blob.contains("NACIONAL") -> "Nacional"
                blob.contains("DUAL") || blob.contains("DUAL AUDIO") || blob.contains("DUAL ÁUDIO") -> "Dual Áudio"
                blob.contains("MULTI") || blob.contains("MULTI-AUDIO") || blob.contains("MULTI AUDIO") -> "Multi-áudio"
                blob.contains("ORIGINAL") -> "Original"
                else -> null
            }
        }

        fun parseQualityLabel(quality: String?, source: String? = null): String {
            val blob = listOfNotNull(quality, source).joinToString(" ").uppercase()
            return when {
                blob.contains("2160P") || blob.contains("4K") || blob.contains("UHD") -> "4K"
                blob.contains("1080P") || blob.contains("FHD") -> "FHD"
                blob.contains("720P") || blob.contains("HD") -> "HD"
                blob.contains("480P") || blob.contains("SD") -> "SD"
                blob.contains("CAM") || blob.contains("HDCAM") || blob.contains("TS") -> "CAM"
                !quality.isNullOrBlank() -> when (quality.trim().uppercase()) {
                    "1080P", "FHD" -> "FHD"
                    "720P", "HD" -> "HD"
                    "480P", "SD" -> "SD"
                    "2160P", "4K" -> "4K"
                    else -> quality.trim()
                }
                else -> "HD"
            }
        }
    }
}
