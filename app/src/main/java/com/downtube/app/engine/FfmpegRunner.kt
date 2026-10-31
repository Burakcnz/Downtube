package com.downtube.app.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File

/**
 * Runs the ffmpeg binary that ships with youtubedl-android directly, so the non yt-dlp engines can
 * merge DASH streams, remux HLS and transcode to MP3. Mirrors the environment YoutubeDL.execute sets up.
 */
object FfmpegRunner {
    private lateinit var binary: File
    private lateinit var libPath: String

    fun init(context: Context) {
        val bin = File(context.applicationInfo.nativeLibraryDir)
        binary = File(bin, "libffmpeg.so")
        val packages = File(File(context.noBackupFilesDir, YoutubeDL.baseName), "packages")
        libPath = listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
    }

    private val timeRegex = Regex("""time=(\d+):(\d+):(\d+(?:\.\d+)?)""")

    /**
     * @param durationSec used to turn ffmpeg's `time=` output into a percentage; 0 = unknown.
     */
    fun run(args: List<String>, durationSec: Long, cancel: CancelToken, progress: ProgressFn, stage: Stage) {
        progress(Progress(stage))
        val cmd = listOf(binary.absolutePath, "-hide_banner", "-y", "-nostdin") + args
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = libPath
        val proc = pb.start()
        cancel.onCancel { proc.destroy() }
        val tail = ArrayDeque<String>()
        proc.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (tail.size > 15) tail.removeFirst()
                tail.addLast(line)
                val m = timeRegex.find(line) ?: return@forEach
                val (h, mi, s) = m.destructured
                val t = h.toLong() * 3600 + mi.toLong() * 60 + s.toDouble()
                progress(Progress(stage, if (durationSec > 0) (t / durationSec * 100).toFloat().coerceIn(0f, 100f) else -1f))
            }
        }
        val code = proc.waitFor()
        cancel.throwIfCancelled()
        if (code != 0) throw EngineException("ffmpeg ($code): " + tail.joinToString("\n").takeLast(400))
    }

    fun headerArgs(headers: Map<String, String>): List<String> {
        if (headers.isEmpty()) return emptyList()
        val ua = headers["User-Agent"]
        val rest = headers.filterKeys { it != "User-Agent" }
        return buildList {
            if (ua != null) { add("-user_agent"); add(ua) }
            if (rest.isNotEmpty()) { add("-headers"); add(rest.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }) }
        }
    }

    /**
     * Video+audio (or a single muxed stream) → MP4 without re-encoding. When the input is a remote stream the
     * work is really a download, so callers pass [Stage.DOWNLOADING].
     */
    fun mergeToMp4(
        video: String, audio: String?, out: File, durationSec: Long, cancel: CancelToken, progress: ProgressFn,
        headers: Map<String, String> = emptyMap(), stage: Stage = Stage.MERGING,
    ) {
        val args = buildList {
            addAll(headerArgs(headers)); add("-i"); add(video)
            if (audio != null) { addAll(headerArgs(headers)); add("-i"); add(audio) }
            add("-map"); add("0:v:0")
            add("-map"); add(if (audio != null) "1:a:0" else "0:a:0?")
            addAll(listOf("-c", "copy", "-strict", "experimental", "-movflags", "+faststart", out.absolutePath))
        }
        run(args, durationSec, cancel, progress, stage)
    }

    fun toMp3(
        input: String, kbps: Int, out: File, durationSec: Long, cancel: CancelToken, progress: ProgressFn,
        headers: Map<String, String> = emptyMap(), stage: Stage = Stage.CONVERTING,
    ) {
        val args = headerArgs(headers) + listOf("-i", input, "-vn", "-c:a", "libmp3lame", "-b:a", "${kbps}k", out.absolutePath)
        run(args, durationSec, cancel, progress, stage)
    }
}
