package com.downtube.app.engine

import android.net.Uri
import com.downtube.app.DownTubeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The link already points at a media file: fetch it as is. */
class DirectEngine : Engine {
    override val id = "direct"
    override val displayName = "Direct link"

    private val mediaExt = Regex("""\.(mp4|m4v|mov|webm|mkv|3gp|mp3|m4a|aac|ogg|opus|wav|flac|m3u8)$""", RegexOption.IGNORE_CASE)

    private fun isHls(url: String, type: String?) =
        url.substringBefore('?').endsWith(".m3u8", true) || type?.contains("mpegurl", true) == true

    override suspend fun canHandle(url: String): Boolean = withContext(Dispatchers.IO) {
        if (!isHttpUrl(url)) return@withContext false
        if (mediaExt.containsMatchIn(Uri.parse(url).path.orEmpty())) return@withContext true
        val t = Http.head(url)?.contentType.orEmpty()
        t.startsWith("video/") || t.startsWith("audio/") || isHls(url, t)
    }

    override suspend fun info(url: String): MediaInfo = withContext(Dispatchers.IO) {
        val h = Http.head(url)
        val name = h?.fileName ?: Uri.parse(url).lastPathSegment ?: url
        MediaInfo(
            url = url,
            title = name.substringBeforeLast('.'),
            videoOptions = listOf(VideoOption(0, sizeBytes = h?.length?.takeIf { it > 0 })),
            engineId = id,
        )
    }

    override suspend fun download(spec: DownloadSpec, workDir: File, progress: ProgressFn, cancel: CancelToken): List<File> =
        withContext(Dispatchers.IO) {
            DownTubeApp.awaitReady()
            val h = Http.head(spec.url)
            val fileName = h?.fileName ?: Uri.parse(spec.url).lastPathSegment ?: "media"
            val base = safeName(spec.title ?: fileName.substringBeforeLast('.'))
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (isHls(spec.url, h?.contentType)) {
                val out = File(workDir, "$base.${if (spec.type == MediaType.MP3) "mp3" else "mp4"}")
                if (spec.type == MediaType.MP3) FfmpegRunner.toMp3(spec.url, spec.kbps, out, 0, cancel, progress, stage = Stage.DOWNLOADING)
                else FfmpegRunner.mergeToMp4(spec.url, null, out, 0, cancel, progress, stage = Stage.DOWNLOADING)
                return@withContext listOf(out)
            }
            val raw = File(workDir, "$base.${ext.ifBlank { "bin" }}")
            Http.download(spec.url, raw, cancel = cancel, onBytes = httpProgress(progress))
            when {
                spec.type == MediaType.MP3 && ext != "mp3" -> {
                    val out = File(workDir, "$base.mp3")
                    FfmpegRunner.toMp3(raw.absolutePath, spec.kbps, out, 0, cancel, progress)
                    raw.delete(); listOf(out)
                }
                spec.type == MediaType.MP4 && ext !in setOf("mp4", "m4v") -> {
                    val out = File(workDir, "$base.mp4")
                    runCatching { FfmpegRunner.mergeToMp4(raw.absolutePath, null, out, 0, cancel, progress); raw.delete(); listOf(out) }
                        .getOrElse { listOf(raw) } // keep the original if it cannot be remuxed
                }
                else -> listOf(raw)
            }
        }
}
