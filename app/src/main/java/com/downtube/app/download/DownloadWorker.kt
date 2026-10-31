package com.downtube.app.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.downtube.app.DownTubeApp
import com.downtube.app.R
import com.downtube.app.data.DownloadItem
import com.downtube.app.data.Status
import com.downtube.app.engine.CancelToken
import com.downtube.app.engine.DownloadSpec
import com.downtube.app.engine.Progress
import com.downtube.app.engine.Stage
import com.downtube.app.engine.formatBytes
import com.downtube.app.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.util.UUID

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext as DownTubeApp
    private val id = params.inputData.getString(KEY_ID) ?: ""
    private val notifId = id.hashCode()
    private var lastTick = 0L

    override suspend fun doWork(): Result {
        val item = app.store.get(id) ?: return Result.failure()
        app.store.upsert(item.copy(status = Status.RUNNING, progress = 0f, error = null, live = Progress(Stage.PREPARING)))
        runCatching { setForeground(foreground(app.store.get(id) ?: item)) }

        val cancel = CancelToken()
        currentCoroutineContext()[Job]?.invokeOnCompletion { if (it is CancellationException) cancel.cancel() }
        val workRoot = File(applicationContext.cacheDir, "jobs/$id")

        return try {
            ensureSpace(item)
            val spec = DownloadSpec(item.url, item.type, item.height, item.kbps, item.playlist, item.ref, item.title)
            val (files, engineId) = app.engines.download(
                spec, item.preferredEngine, workRoot, cancel,
                onEngine = { e ->
                    app.store.update(id) { it.copy(usedEngine = e.id) }
                    app.store.resetLive(id)
                    tick(Progress(Stage.PREPARING))
                },
            ) { tick(it) }

            // copying a multi-GB 4K file into the gallery takes a while, so it reports too
            val total = files.sumOf { it.length() }
            var copiedBefore = 0L
            tick(Progress(Stage.SAVING, 0f, doneBytes = 0, totalBytes = total))
            val uris = files.map { f ->
                val size = f.length() // the saver deletes the source afterwards
                val uri = MediaStoreSaver.save(applicationContext, f, item.type) { copied ->
                    val done = copiedBefore + copied
                    tick(Progress(Stage.SAVING, if (total > 0) done * 100f / total else -1f, doneBytes = done, totalBytes = total))
                }
                copiedBefore += size
                uri.toString()
            }
            // quick mode queues without fetching info, so the URL stands in for the title until now
            val title = if (item.title == item.url) files.first().nameWithoutExtension else item.title
            app.store.update(id) { it.copy(status = Status.DONE, progress = 100f, outputs = uris, usedEngine = engineId, title = title, live = null) }
            notifyFinished(app.store.get(id) ?: item, true)
            Result.success()
        } catch (c: CancellationException) {
            cancel.cancel()
            app.store.update(id) { it.copy(status = Status.CANCELED) }
            throw c
        } catch (t: Throwable) {
            app.store.update(id) { it.copy(status = Status.ERROR, error = t.message?.take(600) ?: t.javaClass.simpleName) }
            notifyFinished(app.store.get(id) ?: item, false)
            Result.failure()
        } finally {
            workRoot.deleteRecursively()
        }
    }

    private fun ensureSpace(item: DownloadItem) {
        // 8K files reach several GB; fail early instead of halfway through
        val free = StatFs(applicationContext.cacheDir.absolutePath).availableBytes
        val need = when {
            item.type.name == "MP3" -> 100L shl 20
            item.height >= 4320 -> 6L shl 30
            item.height >= 2160 -> 3L shl 30
            else -> 500L shl 20
        }
        if (free < need) throw IllegalStateException(applicationContext.getString(R.string.not_enough_space, formatBytes(need)))
    }

    private var lastStage: Stage? = null

    /** Called from engine threads, so it must not suspend. */
    private fun tick(p: Progress) {
        app.store.setLive(id, p)
        val now = System.currentTimeMillis()
        // stage changes go out at once, percent updates at most ~1/s (Android throttles faster updates anyway)
        if (p.stage == lastStage && now - lastTick < 1000) return
        lastTick = now
        lastStage = p.stage
        app.store.get(id)?.let { runCatching { setForegroundAsync(foreground(it)) } }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun foreground(item: DownloadItem): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(getId())
        val step = ProgressText.step(applicationContext, item)
        val detail = ProgressText.detail(applicationContext, item)
        val n = builder()
            .setContentTitle(item.title)
            .setContentText(step)
            .setSubText(item.qualityLabel)
            .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(step, detail).joinToString("\n")))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .setProgress(100, item.progress.toInt(), ProgressText.indeterminate(item))
            .addAction(Notification.Action.Builder(null, applicationContext.getString(R.string.cancel), cancel).build())
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notifId, n)
    }

    private fun notifyFinished(item: DownloadItem, ok: Boolean) {
        val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        val n = builder()
            .setContentTitle(applicationContext.getString(if (ok) R.string.notif_done else R.string.notif_failed))
            .setContentText(item.title)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        // a different id than the foreground one, which WorkManager removes when the job ends
        runCatching { nm.notify(notifId + 1, n) }
    }

    @Suppress("DEPRECATION")
    private fun builder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(applicationContext, DownTubeApp.CHANNEL)
        else Notification.Builder(applicationContext)

    companion object {
        private const val KEY_ID = "id"

        fun enqueue(context: Context, item: DownloadItem) {
            val app = context.applicationContext as DownTubeApp
            app.store.upsert(item)
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_ID to item.id))
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(item.id, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork(id)
        }

        fun retry(context: Context, item: DownloadItem) =
            enqueue(context, item.copy(status = Status.QUEUED, progress = 0f, error = null, outputs = emptyList()))

        fun newId(): String = UUID.randomUUID().toString()

        private const val TAG = "download"
    }
}
