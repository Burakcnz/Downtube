package com.downtube.app.engine

enum class Stage { QUEUED, PREPARING, DOWNLOADING, MERGING, CONVERTING, SAVING }

/**
 * Live status of a job. [percent] is the progress of the current [stage] (0..100, -1 = unknown);
 * [overall] folds the stages into one bar that only moves forward.
 */
data class Progress(
    val stage: Stage,
    val percent: Float = -1f,
    /** For downloads made of several streams: video is part 1, audio part 2. */
    val part: Int = 1,
    val parts: Int = 1,
    /** True when the current part is the audio stream of a video download. */
    val audioPart: Boolean = false,
    val doneBytes: Long = -1,
    val totalBytes: Long = -1,
    val speedBps: Long = -1,
    val etaSec: Long = -1,
) {
    /** One 0..100 value for the whole job, or -1 while nothing measurable has happened yet. */
    val overall: Float
        get() = when (stage) {
            Stage.QUEUED, Stage.PREPARING -> -1f
            Stage.DOWNLOADING -> if (percent < 0) -1f else percent * DOWNLOAD_SHARE
            Stage.MERGING, Stage.CONVERTING -> DOWNLOAD_SHARE * 100 + (percent.coerceAtLeast(0f) * POST_SHARE)
            Stage.SAVING -> (DOWNLOAD_SHARE + POST_SHARE) * 100 + percent.coerceAtLeast(0f) * SAVE_SHARE
        }

    companion object {
        private const val DOWNLOAD_SHARE = 0.90f
        private const val POST_SHARE = 0.07f
        private const val SAVE_SHARE = 0.03f
    }
}

typealias ProgressFn = (Progress) -> Unit

/**
 * Maps a sub-download's own 0..100 onto [from]..[to] of the download stage, so video + audio fill one bar
 * instead of running 0→100 twice.
 */
fun ProgressFn.slice(from: Float, to: Float, part: Int, parts: Int, audio: Boolean = false): ProgressFn = { p ->
    this(
        p.copy(
            percent = if (p.stage == Stage.DOWNLOADING && p.percent >= 0) from + p.percent * (to - from) / 100f else p.percent,
            part = part, parts = parts, audioPart = audio,
        )
    )
}

/** Turns raw byte counts into speed and time left, smoothed over the last few seconds. */
class SpeedMeter {
    private val samples = ArrayDeque<Pair<Long, Long>>() // time ms to bytes
    private var lastEmit = 0L

    fun progress(done: Long, total: Long): Progress? {
        val now = System.currentTimeMillis()
        samples.addLast(now to done)
        while (samples.size > 2 && now - samples.first().first > WINDOW_MS) samples.removeFirst()
        // Emitting on every 64 KB buffer would flood the UI; 4 times a second is plenty
        if (now - lastEmit < 250 && done != total) return null
        lastEmit = now
        val (t0, b0) = samples.first()
        val speed = if (now > t0) (done - b0) * 1000 / (now - t0) else -1
        val eta = if (speed > 0 && total > 0) (total - done) / speed else -1
        return Progress(
            stage = Stage.DOWNLOADING,
            percent = if (total > 0) done * 100f / total else -1f,
            doneBytes = done, totalBytes = total, speedBps = speed, etaSec = eta,
        )
    }

    companion object {
        private const val WINDOW_MS = 4_000
    }
}

/** HTTP byte callback that reports through [progress]. */
fun httpProgress(progress: ProgressFn): (Long, Long) -> Unit {
    val meter = SpeedMeter()
    return { done, total -> meter.progress(done, total)?.let(progress) }
}
