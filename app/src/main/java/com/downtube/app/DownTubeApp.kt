package com.downtube.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.downtube.app.data.DownloadStore
import com.downtube.app.data.SettingsRepo
import com.downtube.app.engine.EngineChain
import com.downtube.app.engine.FfmpegRunner
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DownTubeApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: SettingsRepo
        private set
    lateinit var store: DownloadStore
        private set
    lateinit var engines: EngineChain
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepo(this)
        store = DownloadStore(this)
        engines = EngineChain(this, settings)
        createChannel()

        // Unpacking python + ffmpeg takes a few seconds on first launch, so do it off the main thread
        scope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.init(this@DownTubeApp)
                FFmpeg.init(this@DownTubeApp)
                Aria2c.init(this@DownTubeApp)
                FfmpegRunner.init(this@DownTubeApp)
                ready.complete(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "init failed", t)
                ready.completeExceptionally(t)
                return@launch
            }
            autoUpdateYtDlp()
        }
    }

    private suspend fun autoUpdateYtDlp() {
        val last = settings.current().lastYtdlpUpdate
        if (System.currentTimeMillis() - last < 24 * 3600_000L) return
        runCatching { YoutubeDL.updateYoutubeDL(this, YoutubeDL.UpdateChannel.STABLE) }
            .onSuccess { settings.update { it.copy(lastYtdlpUpdate = System.currentTimeMillis()) } }
            .onFailure { Log.w(TAG, "yt-dlp update failed", it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val TAG = "DownTube"
        const val CHANNEL = "downloads"
        lateinit var instance: DownTubeApp
            private set
        private val ready = CompletableDeferred<Unit>()

        suspend fun awaitReady() = ready.await()
    }
}
