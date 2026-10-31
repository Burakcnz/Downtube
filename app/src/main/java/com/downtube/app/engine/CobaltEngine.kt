package com.downtube.app.engine

import com.downtube.app.DownTubeApp
import com.downtube.app.data.SettingsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/**
 * Optional fallback through a cobalt (github.com/imputnet/cobalt) instance the user configures.
 * Good at Instagram / TikTok / X / Reddit links when extractors break.
 */
class CobaltEngine(private val settings: SettingsRepo) : Engine {
    override val id = "cobalt"
    override val displayName = "Cobalt"

    override suspend fun canHandle(url: String) = isHttpUrl(url) && settings.current().cobaltUrl.isNotBlank()

    private data class Result(val url: String, val filename: String?)

    private fun call(url: String, type: MediaType, height: Int, kbps: Int): Result {
        val s = settings.current()
        val body = JSONObject()
            .put("url", url)
            .put("videoQuality", if (height <= 0) "max" else height.toString())
            .put("downloadMode", if (type == MediaType.MP3) "audio" else "auto")
            .put("audioFormat", "mp3")
            .put("audioBitrate", COBALT_BITRATES.firstOrNull { it <= kbps }?.toString() ?: "128")
            .put("filenameStyle", "basic")
        val req = Request.Builder().url(s.cobaltUrl.trimEnd('/') + "/")
            .header("Accept", "application/json")
            .apply { if (s.cobaltKey.isNotBlank()) header("Authorization", "Api-Key ${s.cobaltKey}") }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val j = Http.client.newCall(req).execute().use { JSONObject(it.body!!.string()) }
        return when (j.optString("status")) {
            "tunnel", "redirect" -> Result(j.getString("url"), j.optString("filename").ifBlank { null })
            "picker" -> {
                val first = j.getJSONArray("picker").getJSONObject(0)
                Result(first.getString("url"), null)
            }
            else -> throw EngineException("Cobalt: " + (j.optJSONObject("error")?.optString("code") ?: j.toString().take(120)))
        }
    }

    override suspend fun info(url: String): MediaInfo = withContext(Dispatchers.IO) {
        val r = call(url, MediaType.MP4, 0, 320)
        MediaInfo(
            url = url,
            title = r.filename?.substringBeforeLast('.') ?: url,
            // cobalt does not list formats; it picks the best stream at or below the requested height
            videoOptions = STANDARD_HEIGHTS.map { VideoOption(it) },
            engineId = id,
        )
    }

    override suspend fun download(spec: DownloadSpec, workDir: File, progress: ProgressFn, cancel: CancelToken): List<File> =
        withContext(Dispatchers.IO) {
            DownTubeApp.awaitReady()
            val r = call(spec.url, spec.type, spec.height, spec.kbps)
            val name = safeName(spec.title ?: r.filename?.substringBeforeLast('.') ?: "cobalt")
            val ext = r.filename?.substringAfterLast('.', "")?.ifBlank { null } ?: if (spec.type == MediaType.MP3) "mp3" else "mp4"
            val raw = File(workDir, "raw.$ext")
            Http.download(r.url, raw, cancel = cancel, onBytes = httpProgress(progress))
            val out = File(workDir, "$name.${if (spec.type == MediaType.MP3) "mp3" else "mp4"}")
            when {
                spec.type == MediaType.MP3 && ext != "mp3" -> {
                    FfmpegRunner.toMp3(raw.absolutePath, spec.kbps, out, 0, cancel, progress); raw.delete()
                }
                spec.type == MediaType.MP4 && ext != "mp4" -> {
                    FfmpegRunner.mergeToMp4(raw.absolutePath, null, out, 0, cancel, progress); raw.delete()
                }
                else -> raw.renameTo(out)
            }
            listOf(out)
        }

    companion object {
        private val COBALT_BITRATES = listOf(320, 256, 128, 96, 64, 8)
    }
}
