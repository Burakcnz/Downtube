package com.downtube.app.engine

enum class MediaType { MP4, MP3 }

data class VideoOption(
    val height: Int,
    val fps: Int = 0,
    val hdr: Boolean = false,
    val sizeBytes: Long? = null,
    /** Engine specific stream reference (only meaningful to the engine that produced it). */
    val ref: String? = null,
    val labelOverride: String? = null,
) {
    val label: String
        get() = labelOverride ?: heightLabel(height)
    val badge: String?
        get() = when {
            height >= 4320 -> "8K"
            height >= 2160 -> "4K"
            height >= 1440 -> "2K"
            height >= 1080 -> "FHD"
            height >= 720 -> "HD"
            else -> null
        }
}

data class AudioOption(val kbps: Int, val sizeBytes: Long? = null)

data class MediaInfo(
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val durationSec: Long = 0,
    val uploader: String? = null,
    val playlistCount: Int = 0,
    val videoOptions: List<VideoOption>,
    val audioOptions: List<AudioOption> = defaultAudioOptions(durationSec),
    val engineId: String,
) {
    val isPlaylist get() = playlistCount > 0
}

data class DownloadSpec(
    val url: String,
    val type: MediaType,
    /** Wanted video height; 0 means "best available". */
    val height: Int,
    val kbps: Int,
    val playlist: Boolean = false,
    val ref: String? = null,
    val title: String? = null,
)

fun heightLabel(h: Int): String = when {
    h <= 0 -> "Orijinal"
    h >= 4320 -> "8K (${h}p)"
    h >= 2160 -> "4K (${h}p)"
    h >= 1440 -> "2K (${h}p)"
    else -> "${h}p"
}

val STANDARD_HEIGHTS = listOf(4320, 2160, 1440, 1080, 720, 480, 360, 240, 144)
val MP3_BITRATES = listOf(320, 256, 192, 128)

fun defaultAudioOptions(durationSec: Long) =
    MP3_BITRATES.map { AudioOption(it, if (durationSec > 0) durationSec * it * 125 else null) }

/** Snap an arbitrary stream height (e.g. 1012 for a cropped 1080p) to the nearest standard ladder step. */
fun normalizeHeight(h: Int): Int =
    STANDARD_HEIGHTS.firstOrNull { h >= it * 0.85 } ?: h

fun formatBytes(b: Long?): String? {
    if (b == null || b <= 0) return null
    val units = listOf("B", "KB", "MB", "GB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i >= 2) "%.1f %s".format(v, units[i]) else "%.0f %s".format(v, units[i])
}
