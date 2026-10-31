package com.downtube.app.engine

import android.content.Context
import android.util.Log
import com.downtube.app.data.SettingsRepo
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Tries the enabled engines in the user's order. The engine that produced the info screen is tried first
 * when downloading; if it fails, the rest of the chain takes over with the same height / bitrate wish.
 */
class EngineChain(context: Context, private val settings: SettingsRepo) {
    private val ytdlp = YtDlpEngine(settings)

    val all: List<Engine> = listOf(
        ytdlp,
        NewPipeEngine(settings),
        CobaltEngine(settings),
        DirectEngine(),
        WebSnifferEngine(context, ytdlp),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    fun ordered(): List<Engine> {
        val s = settings.current()
        return s.engineOrder.mapNotNull { byId(it) }.filter { it.id !in s.disabledEngines }
    }

    suspend fun info(url: String, onTrying: (Engine) -> Unit = {}): MediaInfo {
        val errors = mutableListOf<String>()
        val order = ordered()
        fastInfo(url, order, onTrying)?.let { return it }
        for (e in order) {
            if (!runCatching { e.canHandle(url) }.getOrDefault(false)) continue
            onTrying(e)
            try {
                val info = e.info(url)
                if (info.videoOptions.isNotEmpty()) return info
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "${e.id} info failed", t)
                errors += "${e.displayName}: ${t.message?.take(160)}"
            }
        }
        throw EngineException(errors.joinToString("\n").ifBlank { "No engine can handle this link" })
    }

    /**
     * yt-dlp needs 15-60 s to describe a YouTube video on a phone (Python + JS challenge solving), NewPipe does it in
     * a few seconds. When both are enabled and NewPipe knows the site, show its quality list but keep yt-dlp as the
     * downloader if it ranks first; both select by height so the choice carries over.
     */
    private suspend fun fastInfo(url: String, order: List<Engine>, onTrying: (Engine) -> Unit): MediaInfo? {
        val np = order.firstOrNull { it.id == "newpipe" } ?: return null
        val yt = order.firstOrNull { it.id == "ytdlp" } ?: return null
        if (!runCatching { np.canHandle(url) }.getOrDefault(false)) return null
        onTrying(np)
        val info = try {
            np.info(url)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "newpipe fast info failed", t)
            return null
        }
        val downloader = if (order.indexOf(yt) < order.indexOf(np)) yt.id else np.id
        return info.copy(engineId = downloader)
    }

    /** @return the files plus the id of the engine that succeeded. */
    suspend fun download(
        spec: DownloadSpec,
        preferred: String?,
        workRoot: File,
        cancel: CancelToken,
        onEngine: (Engine) -> Unit,
        progress: ProgressFn,
    ): Pair<List<File>, String> {
        val chain = ordered().let { list ->
            val first = list.firstOrNull { it.id == preferred }
            if (first != null) listOf(first) + (list - first) else list
        }
        val errors = mutableListOf<String>()
        for (e in chain) {
            cancel.throwIfCancelled()
            if (!runCatching { e.canHandle(spec.url) }.getOrDefault(false)) continue
            // The stream ref only means something to the engine that created it
            val s = if (e.id == preferred) spec else spec.copy(ref = null)
            val dir = File(workRoot, e.id).apply { deleteRecursively(); mkdirs() }
            onEngine(e)
            try {
                return e.download(s, dir, progress, cancel) to e.id
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                cancel.throwIfCancelled()
                Log.w(TAG, "${e.id} download failed", t)
                errors += "${e.displayName}: ${t.message?.take(200)}"
                dir.deleteRecursively()
            }
        }
        throw EngineException(errors.joinToString("\n").ifBlank { "No engine can handle this link" })
    }

    companion object {
        private const val TAG = "EngineChain"
        val DEFAULT_ORDER = listOf("ytdlp", "newpipe", "cobalt", "direct", "sniffer")
    }
}
