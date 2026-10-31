package com.downtube.app.engine

import com.downtube.app.DownTubeApp
import com.downtube.app.data.SettingsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File

/** Pure-Java fallback for YouTube, SoundCloud, PeerTube, Bandcamp and media.ccc.de. */
class NewPipeEngine(private val settings: SettingsRepo) : Engine {
    override val id = "newpipe"
    override val displayName = "NewPipe"

    private object OkDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody(null)
            val b = okhttp3.Request.Builder().url(request.url())
                .method(request.httpMethod(), body ?: if (request.httpMethod() == "POST") ByteArray(0).toRequestBody(null) else null)
                .header("User-Agent", USER_AGENT)
            request.headers().forEach { (k, vs) -> b.removeHeader(k); vs.forEach { v -> b.addHeader(k, v) } }
            Http.client.newCall(b.build()).execute().use { r ->
                return Response(r.code, r.message, r.headers.toMultimap(), r.body!!.string(), r.request.url.toString())
            }
        }
    }

    init {
        synchronized(NewPipeEngine::class) {
            if (!initialized) { NewPipe.init(OkDownloader); initialized = true }
        }
    }

    private fun service(url: String): StreamingService? = runCatching {
        NewPipe.getServiceByUrl(url).takeIf { it.getLinkTypeByUrl(url) == StreamingService.LinkType.STREAM }
    }.getOrNull()

    override suspend fun canHandle(url: String) = withContext(Dispatchers.IO) { service(url) != null }

    private fun fetch(url: String): StreamInfo {
        val svc = service(url) ?: throw EngineException("NewPipe: unsupported link")
        return runCatching { StreamInfo.getInfo(svc, url) }.getOrElse { throw EngineException("NewPipe: ${it.message}", it) }
    }

    private fun VideoStream.usable() = isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && height > 0
    private fun AudioStream.usable() = isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    private fun allVideo(info: StreamInfo) = (info.videoOnlyStreams + info.videoStreams).filter { it.usable() }

    override suspend fun info(url: String): MediaInfo = withContext(Dispatchers.IO) {
        val info = fetch(url)
        val audioSize = info.audioStreams.filter { it.usable() }.maxByOrNull { it.averageBitrate }?.itagItem?.contentLength ?: 0
        val options = allVideo(info)
            .groupBy { normalizeHeight(it.height) }
            .map { (h, list) ->
                val size = list.mapNotNull { it.itagItem?.contentLength?.takeIf { s -> s > 0 } }.maxOrNull()
                VideoOption(
                    height = h,
                    fps = list.maxOf { it.fps },
                    sizeBytes = size?.let { s -> s + audioSize },
                )
            }
            .sortedByDescending { it.height }
            .ifEmpty { listOf(VideoOption(0)) }
        MediaInfo(
            url = url,
            title = info.name,
            thumbnail = info.thumbnails.maxByOrNull { it.height }?.url,
            durationSec = info.duration,
            uploader = info.uploaderName,
            videoOptions = options,
            engineId = id,
        )
    }

    override suspend fun download(spec: DownloadSpec, workDir: File, progress: ProgressFn, cancel: CancelToken): List<File> =
        withContext(Dispatchers.IO) {
            DownTubeApp.awaitReady()
            val info = fetch(spec.url)
            val base = safeName(spec.title ?: info.name)
            val audio = bestAudio(info.audioStreams.filter { it.usable() })

            when (spec.type) {
                MediaType.MP3 -> {
                    audio ?: throw EngineException("NewPipe: no audio stream")
                    val raw = File(workDir, "audio.${audio.format?.suffix ?: "m4a"}")
                    fetchFile(audio.content, raw, cancel, progress)
                    val out = File(workDir, "$base.mp3")
                    FfmpegRunner.toMp3(raw.absolutePath, spec.kbps, out, info.duration, cancel, progress)
                    raw.delete()
                    listOf(out)
                }
                MediaType.MP4 -> {
                    val compat = settings.current().compatMode
                    val video = pickVideo(allVideo(info), spec.height, compat || spec.height in 1..1080)
                        ?: throw EngineException("NewPipe: no video stream")
                    val out = File(workDir, "$base.mp4")
                    val vFile = File(workDir, "video.${video.format?.suffix ?: "mp4"}")
                    if (!video.isVideoOnly) {
                        fetchFile(video.content, vFile, cancel, progress)
                        FfmpegRunner.mergeToMp4(vFile.absolutePath, null, out, info.duration, cancel, progress)
                        vFile.delete()
                        return@withContext listOf(out)
                    }
                    audio ?: throw EngineException("NewPipe: no audio stream")
                    val aFile = File(workDir, "audio.${audio.format?.suffix ?: "m4a"}")
                    // Video is usually ~90% of the bytes, report the two downloads as one 0-100 bar
                    // Video is most of the bytes: one 0-100 bar across both streams
                    fetchFile(video.content, vFile, cancel, progress.slice(0f, 85f, 1, 2))
                    fetchFile(audio.content, aFile, cancel, progress.slice(85f, 100f, 2, 2, audio = true))
                    FfmpegRunner.mergeToMp4(vFile.absolutePath, aFile.absolutePath, out, info.duration, cancel, progress)
                    vFile.delete(); aFile.delete()
                    listOf(out)
                }
            }
        }

    private fun pickVideo(streams: List<VideoStream>, height: Int, preferMp4: Boolean): VideoStream? {
        val target = if (height <= 0) Int.MAX_VALUE else height
        val fitting = streams.filter { normalizeHeight(it.height) <= target }.ifEmpty { streams }
        val bestH = fitting.maxOf { normalizeHeight(it.height) }
        return fitting.filter { normalizeHeight(it.height) == bestH }
            .sortedWith(
                compareByDescending<VideoStream> { preferMp4 && it.format == MediaFormat.MPEG_4 }
                    .thenByDescending { it.fps }
                    .thenByDescending { it.itagItem?.contentLength ?: 0 }
            )
            .firstOrNull()
    }

    /** Prefer AAC so it can sit in MP4 next to H.264 everywhere; fall back to the highest bitrate. */
    private fun bestAudio(streams: List<AudioStream>): AudioStream? =
        streams.sortedWith(
            compareByDescending<AudioStream> { it.format == MediaFormat.M4A }.thenByDescending { it.averageBitrate }
        ).firstOrNull()

    private fun fetchFile(url: String, dest: File, cancel: CancelToken, progress: ProgressFn) {
        Http.download(url, dest, cancel = cancel, onBytes = httpProgress(progress))
    }

    companion object {
        @Volatile private var initialized = false
    }
}

fun safeName(s: String): String =
    s.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().take(120).ifBlank { "video" }
