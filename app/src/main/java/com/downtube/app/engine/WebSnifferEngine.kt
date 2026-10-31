package com.downtube.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.downtube.app.DownTubeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Last resort for any page with a player: load it in an off-screen WebView, start playback and capture the
 * media requests (mp4 / webm / m3u8 / mpd / mp3) the page makes.
 */
class WebSnifferEngine(private val context: Context, private val ytdlp: YtDlpEngine) : Engine {
    override val id = "sniffer"
    override val displayName = "Web sniffer"

    private data class Sniffed(val title: String, val streams: List<String>, val thumbnail: String?)

    private val cache = ConcurrentHashMap<String, Sniffed>()
    private val mediaRegex = Regex("""\.(mp4|m4v|webm|m3u8|mpd|mp3|m4a)(\?|$|#)""", RegexOption.IGNORE_CASE)

    override suspend fun canHandle(url: String) = isHttpUrl(url)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniff(pageUrl: String): Sniffed {
        cache[pageUrl]?.let { return it }
        val found = ConcurrentHashMap.newKeySet<String>()
        var title = pageUrl
        var image: String? = null
        withContext(Dispatchers.Main) {
            val web = WebView(context)
            var finished = false
            var mainFrameFailed = false
            try {
                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = USER_AGENT
                }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val u = request.url.toString()
                        if (mediaRegex.containsMatchIn(u) && !u.startsWith("blob:")) found += u
                        return null
                    }

                    override fun onPageFinished(view: WebView, url: String) { finished = true }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) mainFrameFailed = true
                    }
                }
                web.loadUrl(pageUrl)
                var waited = 0
                var sinceFound = 0
                while (waited < 20_000 && sinceFound < 3_000) {
                    delay(500); waited += 500
                    if (finished) {
                        web.evaluateJavascript(PROBE_JS) { json ->
                            runCatching {
                                // evaluateJavascript hands back a JSON encoded string
                                val obj = org.json.JSONObject(JSONArray("[$json]").getString(0))
                                val arr = obj.getJSONArray("m")
                                for (i in 0 until arr.length()) {
                                    val s = arr.getString(i)
                                    if (s.startsWith("http")) found += s
                                }
                                obj.optString("i").takeIf { it.startsWith("http") }?.let { image = it }
                            }
                        }
                    }
                    if (found.isNotEmpty()) sinceFound += 500
                }
                // a bare media file has its URL (or WebView's error page) as the title; the file name reads better
                title = web.title?.takeIf { !mainFrameFailed && it.isNotBlank() && !(it.contains('/') && !it.contains(' ')) }
                    ?: pageUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.substringBeforeLast('.')?.ifBlank { null }
                    ?: pageUrl
            } finally {
                web.stopLoading()
                web.destroy()
            }
        }
        if (found.isEmpty()) throw EngineException("No playable media found on the page")
        return Sniffed(title, found.toList(), image).also { cache[pageUrl] = it }
    }

    /** Extension of the URL's last path segment only, e.g. null for `vimeo.com/video/123`. */
    private fun extOf(u: String): String? =
        u.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
            ?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }

    private fun isHls(u: String) = u.substringBefore('?').endsWith(".m3u8", true)
    private fun isDash(u: String) = u.substringBefore('?').endsWith(".mpd", true)

    /** Expands an HLS master playlist into one option per variant height. */
    private fun hlsVariants(master: String, referer: String): List<VideoOption> = runCatching {
        val req = Request.Builder().url(master).header("User-Agent", USER_AGENT).header("Referer", referer).build()
        val text = Http.client.newCall(req).execute().use { it.body!!.string() }
        val base = master.toHttpUrlOrNull() ?: return emptyList()
        val lines = text.lines()
        val out = mutableListOf<VideoOption>()
        lines.forEachIndexed { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
            val h = res?.let { minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: 0
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@forEachIndexed
            if (base.resolve(uri.trim()) == null) return@forEachIndexed
            // ref stays the master playlist; the height picks the variant and yt-dlp adds the audio group
            out += VideoOption(normalizeHeight(h), ref = master)
        }
        out.distinctBy { it.height }.sortedByDescending { it.height }
    }.getOrDefault(emptyList())

    private fun options(s: Sniffed, pageUrl: String): List<VideoOption> {
        val opts = mutableListOf<VideoOption>()
        s.streams.filter { isHls(it) }.forEach { m ->
            val variants = hlsVariants(m, pageUrl)
            opts += variants.ifEmpty { listOf(VideoOption(0, ref = m, labelOverride = "HLS akışı")) }
        }
        s.streams.filter { isDash(it) }.forEach { opts += VideoOption(0, ref = it, labelOverride = "DASH akışı") }
        var n = 0
        s.streams.filter { !isHls(it) && !isDash(it) }.forEach { u ->
            val head = Http.head(u, mapOf("Referer" to pageUrl))
            val type = head?.contentType.orEmpty()
            // og:video and <video src> often point at a player page, not a file
            val isMedia = type.startsWith("video/") || type.startsWith("audio/") || type.contains("octet-stream") ||
                (type.isEmpty() && mediaRegex.containsMatchIn(u))
            if (!isMedia) return@forEach
            n++
            val ext = extOf(u)?.uppercase() ?: type.substringAfter('/').substringBefore(';').uppercase()
            opts += VideoOption(0, sizeBytes = head?.length?.takeIf { it > 0 }, ref = u, labelOverride = "Akış $n ($ext)")
        }
        return opts.distinctBy { it.ref to it.height }
    }

    override suspend fun info(url: String): MediaInfo {
        DownTubeApp.awaitReady()
        val s = sniff(url)
        val opts = withContext(Dispatchers.IO) { options(s, url) }
        if (opts.isEmpty()) throw EngineException("No playable media found on the page")
        return MediaInfo(url = url, title = s.title, thumbnail = s.thumbnail, videoOptions = opts, engineId = id)
    }

    override suspend fun download(spec: DownloadSpec, workDir: File, progress: ProgressFn, cancel: CancelToken): List<File> {
        DownTubeApp.awaitReady()
        val s = sniff(spec.url)
        return withContext(Dispatchers.IO) {
            val stream = spec.ref ?: run {
                val opts = options(s, spec.url).ifEmpty { throw EngineException("No playable media found on the page") }
                val fit = opts.filter { spec.height <= 0 || it.height in 1..spec.height }
                (fit.maxByOrNull { it.height * 10_000L + (it.sizeBytes ?: 0) / 1_000_000 } ?: opts.first()).ref!!
            }
            val base = safeName(spec.title ?: s.title)
            val out = File(workDir, "$base.${if (spec.type == MediaType.MP3) "mp3" else "mp4"}")
            if (isHls(stream) || isDash(stream)) {
                return@withContext ytdlp.fetch(stream, spec.copy(playlist = false), workDir, progress, cancel, referer = spec.url, name = base)
            }
            val ext = extOf(stream) ?: "mp4"
            val raw = File(workDir, "raw.$ext")
            Http.download(stream, raw, mapOf("Referer" to spec.url), cancel, httpProgress(progress))
            when {
                spec.type == MediaType.MP3 && ext != "mp3" -> FfmpegRunner.toMp3(raw.absolutePath, spec.kbps, out, 0, cancel, progress)
                spec.type == MediaType.MP4 && ext != "mp4" -> FfmpegRunner.mergeToMp4(raw.absolutePath, null, out, 0, cancel, progress)
                else -> { raw.renameTo(out); return@withContext listOf(out) }
            }
            raw.delete()
            listOf(out)
        }
    }

    companion object {
        private const val PROBE_JS = """
            (function(){
              var els = document.querySelectorAll('video,audio');
              els.forEach(function(v){ try { v.muted = true; v.play(); } catch(e){} });
              var out = [];
              document.querySelectorAll('video,audio,video source,audio source').forEach(function(e){
                var s = e.currentSrc || e.src; if (s) out.push(s);
              });
              var og = document.querySelector('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"]');
              if (og && og.content) out.push(og.content);
              var img = document.querySelector('meta[property="og:image"]');
              return JSON.stringify({ m: out, i: img ? img.content : "" });
            })()
        """
    }
}
