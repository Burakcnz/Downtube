package com.downtube.app.engine

/**
 * Reads yt-dlp's console lines and turns them into [Progress]: which stage it is in, which stream
 * (video 1/2, audio 2/2) is downloading, and the size / speed / ETA it prints.
 */
class YtDlpProgressParser(private val audioOnly: Boolean) {
    private var stage = Stage.PREPARING
    private var part = 0
    private var parts = 1
    private var last = Progress(Stage.PREPARING)

    val downloading: Boolean get() = stage == Stage.DOWNLOADING

    /** Tags an externally measured progress with the current stream (video 1/2, audio 2/2). */
    fun withPart(p: Progress): Progress? = if (downloading) emit(p) else null

    fun parse(line: String): Progress? {
        when {
            line.startsWith("[download] Downloading item") -> { part = 0; parts = 1 }
            line.startsWith("[info]") && line.contains("Downloading") && line.contains("format(s):") -> {
                val ids = line.substringAfter("format(s):").trim().substringBefore(',')
                parts = ids.count { it == '+' } + 1
            }
            line.startsWith("[download] Destination:") || line.contains("[download] Resuming download") -> {
                if (line.startsWith("[download] Destination:")) part = (part + 1).coerceAtMost(parts)
                stage = Stage.DOWNLOADING
                return emit(Progress(Stage.DOWNLOADING, 0f))
            }
            MERGE_TAGS.any { line.startsWith(it) } -> { stage = Stage.MERGING; return emit(Progress(Stage.MERGING)) }
            CONVERT_TAGS.any { line.startsWith(it) } -> {
                stage = if (audioOnly) Stage.CONVERTING else Stage.MERGING
                return emit(Progress(stage))
            }
        }
        if (stage != Stage.DOWNLOADING && stage != Stage.PREPARING) return null

        NATIVE.find(line)?.let { m ->
            val (pct, size, sizeUnit, speed, speedUnit, eta) = m.destructured
            val total = bytes(size, sizeUnit)
            val p = pct.toFloat()
            return emit(
                Progress(
                    Stage.DOWNLOADING, p,
                    doneBytes = if (total > 0) (total * p / 100).toLong() else -1, totalBytes = total,
                    speedBps = if (speed.isNotEmpty()) bytes(speed, speedUnit) else -1,
                    etaSec = parseClock(eta),
                )
            )
        }
        ARIA.find(line)?.let { m ->
            val (done, doneUnit, total, totalUnit, pct, speed, speedUnit, eta) = m.destructured
            return emit(
                Progress(
                    Stage.DOWNLOADING, pct.toFloat(),
                    doneBytes = bytes(done, doneUnit), totalBytes = bytes(total, totalUnit),
                    speedBps = bytes(speed, speedUnit), etaSec = parseAriaEta(eta),
                )
            )
        }
        return null
    }

    private fun emit(p: Progress): Progress {
        val cur = part.coerceAtLeast(1)
        // two streams: the video takes most of the bar, the audio the rest
        val (from, to) = when {
            parts < 2 -> 0f to 100f
            cur == 1 -> 0f to 85f
            else -> 85f to 100f
        }
        val scaled = if (p.stage == Stage.DOWNLOADING && p.percent >= 0) from + p.percent * (to - from) / 100f else p.percent
        last = p.copy(percent = scaled, part = cur, parts = parts, audioPart = parts >= 2 && cur >= 2)
        return last
    }

    companion object {
        private val MERGE_TAGS = listOf("[Merger]", "[VideoRemuxer]", "[VideoConvertor]", "[FixupM4a]", "[FixupM3u8]", "[FixupStretched]")
        private val CONVERT_TAGS = listOf("[ExtractAudio]", "[EmbedThumbnail]", "[Metadata]", "[ThumbnailsConvertor]")

        // [download]  45.3% of ~ 123.45MiB at    2.34MiB/s ETA 00:41 (frag 3/120)
        private val NATIVE = Regex(
            """\[download]\s+([\d.]+)%\s+of\s+~?\s*([\d.]+)([KMGT]?i?B)(?:\s+at\s+([\d.]+)([KMGT]?i?B)/s)?(?:\s+ETA\s+([\d:]+))?"""
        )

        // [#2089b0 12MiB/120MiB(10%) CN:8 DL:5.2MiB ETA:20s]
        private val ARIA = Regex(
            """\[#\w+\s+([\d.]+)([KMGT]?i?B)/([\d.]+)([KMGT]?i?B)\((\d+)%\).*?DL:([\d.]+)([KMGT]?i?B)(?:\s+ETA:(\w+))?"""
        )

        fun bytes(value: String, unit: String): Long {
            val v = value.toDoubleOrNull() ?: return -1
            val mul = when (unit.uppercase().firstOrNull()) {
                'K' -> 1024.0
                'M' -> 1024.0 * 1024
                'G' -> 1024.0 * 1024 * 1024
                'T' -> 1024.0 * 1024 * 1024 * 1024
                else -> 1.0
            }
            return (v * mul).toLong()
        }

        private fun parseClock(s: String): Long {
            if (s.isBlank()) return -1
            return s.split(':').fold(0L) { acc, part -> acc * 60 + (part.toLongOrNull() ?: return -1) }
        }

        private fun parseAriaEta(s: String): Long {
            if (s.isBlank()) return -1
            var total = 0L
            Regex("""(\d+)([hms])""").findAll(s).forEach { m ->
                val n = m.groupValues[1].toLong()
                total += when (m.groupValues[2]) { "h" -> n * 3600; "m" -> n * 60; else -> n }
            }
            return total
        }
    }
}
