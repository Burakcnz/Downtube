package com.downtube.app.download

import android.content.Context
import com.downtube.app.R
import com.downtube.app.data.DownloadItem
import com.downtube.app.data.Status
import com.downtube.app.engine.MediaType
import com.downtube.app.engine.Progress
import com.downtube.app.engine.Stage
import com.downtube.app.engine.formatBytes

/** Human text for a running job, shared by the list row and the notification. */
object ProgressText {

    /** "Video indiriliyor (1/2)", "Birleştiriliyor", … */
    fun step(context: Context, item: DownloadItem): String {
        val p = item.live
        if (item.status == Status.QUEUED || p == null) {
            return context.getString(if (item.status == Status.QUEUED) R.string.step_queued else R.string.step_preparing)
        }
        return when (p.stage) {
            Stage.QUEUED -> context.getString(R.string.step_queued)
            Stage.PREPARING -> context.getString(R.string.step_preparing)
            Stage.DOWNLOADING -> when {
                p.parts >= 2 && p.audioPart -> context.getString(R.string.step_audio_part, p.part, p.parts)
                p.parts >= 2 -> context.getString(R.string.step_video_part, p.part, p.parts)
                item.type == MediaType.MP3 -> context.getString(R.string.step_audio)
                else -> context.getString(R.string.step_video)
            }
            Stage.MERGING -> context.getString(R.string.step_merging)
            Stage.CONVERTING -> context.getString(R.string.step_converting)
            Stage.SAVING -> context.getString(
                if (item.type == MediaType.MP3) R.string.step_saving_music else R.string.step_saving_video
            )
        }
    }

    /** "%45 · 54.2 / 120.0 MB · 2.3 MB/s · 0:41 kaldı", or null when there is nothing measurable yet. */
    fun detail(context: Context, item: DownloadItem): String? {
        val p = item.live ?: return null
        val parts = buildList {
            if (item.progress > 0) add(context.getString(R.string.percent_fmt, item.progress.toInt()))
            // speed and time left first: on a narrow row they are what gets cut off otherwise
            if (p.stage == Stage.DOWNLOADING) {
                formatBytes(p.speedBps)?.let { add("$it/s") }
                if (p.etaSec >= 0) add(context.getString(R.string.time_left, clock(p.etaSec)))
            }
            if (p.stage == Stage.DOWNLOADING || p.stage == Stage.SAVING) {
                val done = formatBytes(p.doneBytes)
                val total = formatBytes(p.totalBytes)
                when {
                    done != null && total != null -> add("$done / $total")
                    total != null -> add(total)
                    done != null -> add(done)
                }
            }
        }
        return parts.joinToString(" · ").ifBlank { null }
    }

    /** True while the bar has nothing real to show and should animate instead. */
    fun indeterminate(item: DownloadItem): Boolean {
        val p: Progress = item.live ?: return true
        return item.status == Status.QUEUED || p.stage == Stage.PREPARING || (item.progress <= 0f && p.overall < 0)
    }

    fun clock(sec: Long): String =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
}
