package com.downtube.app.engine

import com.downtube.app.BuildConfig
import com.downtube.app.DownTubeApp
import com.downtube.app.data.SettingsRepo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

class YtDlpEngine(private val settings: SettingsRepo) : Engine {
    override val id = "ytdlp"
    override val displayName = "yt-dlp"

    override suspend fun canHandle(url: String) = isHttpUrl(url)

    override suspend fun info(url: String): MediaInfo = withContext(Dispatchers.IO) {
        DownTubeApp.awaitReady()
        val req = YoutubeDLRequest(rewrite(url))
            .addOption("--dump-single-json")
            .addOption("--flat-playlist")
            .addOption("--no-warnings")
        val resp = runCatching { YoutubeDL.execute(req) }
            .getOrElse { throw EngineException(it.message?.lineSequence()?.lastOrNull { l -> l.isNotBlank() } ?: "yt-dlp", it) }
        parseInfo(url, JSONObject(resp.out))
    }

    private fun parseInfo(url: String, j: JSONObject): MediaInfo {
        val isPlaylist = j.optString("_type") == "playlist"
        val title = j.optString("title").ifBlank { url }
        val thumb = j.optString("thumbnail").ifBlank {
            j.optJSONArray("thumbnails")?.let { a -> if (a.length() > 0) a.getJSONObject(a.length() - 1).optString("url") else null }
        }
        val duration = j.optDouble("duration", 0.0).toLong()
        if (isPlaylist) {
            val count = j.optJSONArray("entries")?.length() ?: j.optInt("playlist_count")
            return MediaInfo(
                url = url, title = title, thumbnail = thumb?.ifBlank { null }, uploader = j.optString("uploader").ifBlank { null },
                playlistCount = count, engineId = id,
                videoOptions = STANDARD_HEIGHTS.map { VideoOption(it) },
            )
        }

        val formats = j.optJSONArray("formats")
        var bestAudioSize = 0L
        val byHeight = HashMap<Int, VideoOption>()
        if (formats != null) for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val size = f.optLong("filesize", 0).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0)
            val vcodec = f.optString("vcodec", "none")
            val acodec = f.optString("acodec", "none")
            if (vcodec == "none" && acodec != "none") { bestAudioSize = maxOf(bestAudioSize, size); continue }
            if (vcodec == "none") continue
            val rawH = f.optInt("height", 0)
            val w = f.optInt("width", 0)
            if (rawH <= 0) continue
            // Portrait videos report e.g. 1080x1920; quality is named after the short side
            val h = normalizeHeight(if (w in 1 until rawH) w else rawH)
            val opt = VideoOption(
                height = h,
                fps = f.optDouble("fps", 0.0).toInt(),
                hdr = f.optString("dynamic_range", "SDR").let { it.isNotBlank() && it != "SDR" && it != "null" },
                sizeBytes = size.takeIf { it > 0 },
            )
            val prev = byHeight[h]
            byHeight[h] = if (prev == null) opt else prev.copy(
                fps = maxOf(prev.fps, opt.fps), hdr = prev.hdr || opt.hdr,
                sizeBytes = listOfNotNull(prev.sizeBytes, opt.sizeBytes).maxOrNull(),
            )
        }
        var options = byHeight.values
            .map { if (it.sizeBytes != null && bestAudioSize > 0) it.copy(sizeBytes = it.sizeBytes + bestAudioSize) else it }
            .sortedByDescending { it.height }
        if (options.isEmpty()) options = listOf(VideoOption(0))
        return MediaInfo(
            // keep the link we were given: webpage_url can be a form that needs login (vimeo.com/ID)
            url = url, title = title, thumbnail = thumb?.ifBlank { null },
            durationSec = duration, uploader = j.optString("uploader").ifBlank { null },
            videoOptions = options, engineId = id,
        )
    }

    override suspend fun download(spec: DownloadSpec, workDir: File, progress: ProgressFn, cancel: CancelToken): List<File> =
        fetch(rewrite(spec.url), spec, workDir, progress, cancel)

    /**
     * Also used by the web sniffer for captured HLS/DASH manifests: yt-dlp's generic downloader handles
     * separate audio renditions and quality selection, which plain ffmpeg on one variant would miss.
     */
    suspend fun fetch(
        url: String,
        spec: DownloadSpec,
        workDir: File,
        progress: ProgressFn,
        cancel: CancelToken,
        referer: String? = null,
        name: String? = null,
    ): List<File> =
        withContext(Dispatchers.IO) {
            DownTubeApp.awaitReady()
            val s = settings.current()
            val template = if (name != null) "${safeName(name)}.%(ext)s" else "%(title).120B [%(id)s].%(ext)s"
            // first try the fast path; if yt-dlp picked a DRM format, rerun letting it probe and skip those
            for (checkFormats in listOf(false, true)) {
                try {
                    run(buildRequest(url, spec, template, workDir, referer, checkFormats), cancel, progress, spec.type == MediaType.MP3, workDir)
                    break
                } catch (e: EngineException) {
                    if (checkFormats || e.message?.contains("DRM") != true) throw e
                    workDir.listFiles()?.forEach { it.deleteRecursively() }
                }
            }
            val files = workDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.contains(".temp.") && it.length() > 0 }
                .orEmpty()
            if (files.isEmpty()) throw EngineException("yt-dlp produced no file")
            files
        }

    private fun buildRequest(
        url: String, spec: DownloadSpec, template: String, workDir: File, referer: String?, checkFormats: Boolean,
    ): YoutubeDLRequest {
        val s = settings.current()
        val req = YoutubeDLRequest(url)
            .addOption("-o", File(workDir, template).absolutePath)
            .addOption("--windows-filenames")
            .addOption("--no-mtime")
            .addOption("--no-warnings")
            .addOption(if (spec.playlist) "--yes-playlist" else "--no-playlist")
        referer?.let { req.addOption("--referer", it) }
        if (checkFormats) {
            // format probing writes temp files relative to the cwd, which is read-only "/" on Android
            req.addOption("--check-formats")
            req.addOption("-P", "temp:${workDir.absolutePath}")
        }
        if (s.useAria2) {
            req.addOption("--downloader", "libaria2c.so")
            req.addOption("--external-downloader-args", "aria2c:-x 8 -s 8 -k 1M --file-allocation=none")
            // aria2c cannot stitch fragments; keep segmented protocols on the native downloader
            req.addOption("--downloader", "dash,m3u8:native")
        } else {
            req.addOption("-N", "4")
            // ranged chunks keep YouTube from throttling a single long request
            req.addOption("--http-chunk-size", "10M")
        }

        when (spec.type) {
            MediaType.MP4 -> {
                req.addOption("-f", formatSelector(s.compatMode))
                sortOrder(spec.height, s.compatMode)?.let { req.addOption("-S", it) }
                req.addOption("--merge-output-format", "mp4")
                req.addOption("--remux-video", "mp4")
                req.addOption("--embed-metadata")
            }
            MediaType.MP3 -> {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "${spec.kbps}K")
                req.addOption("--embed-thumbnail")
                req.addOption("--embed-metadata")
            }
        }
        return req
    }

    private fun run(req: YoutubeDLRequest, cancel: CancelToken, progress: ProgressFn, audioOnly: Boolean, workDir: File) {
        val pid = UUID.randomUUID().toString()
        cancel.onCancel { YoutubeDL.destroyProcessById(pid) }
        val parser = YtDlpProgressParser(audioOnly)
        progress(Progress(Stage.PREPARING))
        val lastLine = java.util.concurrent.atomic.AtomicLong(0)
        val watcher = diskWatcher(workDir, parser, progress) { lastLine.get() }
        try {
            // the library's own percent repeats the last value on every line, so read the lines ourselves
            YoutubeDL.execute(req, pid) { _, _, line ->
                val p = parser.parse(line.trim())
                if (BuildConfig.DEBUG) android.util.Log.d("ytdlp", "$line -> $p")
                if (p != null) { lastLine.set(System.currentTimeMillis()); progress(p) }
            }
        } catch (e: YoutubeDL.CanceledException) {
            throw java.util.concurrent.CancellationException("cancelled")
        } catch (e: Exception) {
            cancel.throwIfCancelled()
            throw EngineException(e.message?.lineSequence()?.lastOrNull { it.contains("ERROR") || it.isNotBlank() } ?: "yt-dlp", e)
        } finally {
            watcher.interrupt()
        }
    }

    /**
     * External downloaders (aria2c) only flush their progress when they finish. While a download runs without
     * fresh progress lines, measure the growing files on disk so size and speed still move.
     */
    private fun diskWatcher(workDir: File, parser: YtDlpProgressParser, progress: ProgressFn, lastLine: () -> Long): Thread {
        val meter = SpeedMeter()
        return Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000)
                    if (!parser.downloading || System.currentTimeMillis() - lastLine() < 3000) continue
                    val bytes = workDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".aria2") }.sumOf { it.length() }
                    meter.progress(bytes, -1)?.let { p ->
                        parser.withPart(p.copy(percent = -1f))?.let(progress)
                    }
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
    }

    companion object {
        private val VIMEO = Regex("""^https?://(?:www\.)?vimeo\.com/(\d+)(?:/([0-9a-f]+))?""", RegexOption.IGNORE_CASE)

        /** vimeo.com/ID now demands login for the web client while the embed player URL still works anonymously. */
        fun rewrite(url: String): String {
            val m = VIMEO.find(url) ?: return url
            val (id, hash) = m.destructured
            return "https://player.vimeo.com/video/$id" + if (hash.isNotEmpty()) "?h=$hash" else ""
        }

        fun formatSelector(compat: Boolean): String =
            if (compat) "bv*[vcodec^=avc1]+ba[ext=m4a]/bv*[vcodec^=avc1]+ba/bv*+ba[ext=m4a]/bv*+ba/b"
            else "bv*+ba[ext=m4a]/bv*+ba/b"

        /**
         * yt-dlp's `res` sort key uses the short side, so "1080p" also works for portrait videos and picks the
         * best stream at or below the wanted size. Up to 1080p (or in compat mode) H.264 wins ties so the file
         * plays everywhere; above that YouTube only has VP9/AV1, which MP4 holds without re-encoding.
         */
        fun sortOrder(height: Int, compat: Boolean): String? {
            if (height <= 0) return null
            return if (compat || height <= 1080) "res:$height,vcodec:h264" else "res:$height"
        }
    }
}
