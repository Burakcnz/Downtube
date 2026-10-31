package com.downtube.app.engine

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

interface Engine {
    val id: String
    val displayName: String

    suspend fun canHandle(url: String): Boolean

    suspend fun info(url: String): MediaInfo

    /** Downloads into [workDir] (empty, private to this job) and returns the finished files. */
    suspend fun download(spec: DownloadSpec, workDir: File, progress: ProgressFn, cancel: CancelToken): List<File>
}

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Lets blocking work (child processes, HTTP streams) be torn down when a job is cancelled. */
class CancelToken {
    @Volatile var isCancelled = false
        private set
    private val hooks = CopyOnWriteArrayList<() -> Unit>()

    fun onCancel(hook: () -> Unit) {
        hooks += hook
        if (isCancelled) hook()
    }

    fun cancel() {
        isCancelled = true
        hooks.forEach { runCatching(it) }
    }

    fun throwIfCancelled() {
        if (isCancelled) throw java.util.concurrent.CancellationException("cancelled")
    }
}

fun isHttpUrl(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

private val URL_REGEX = Regex("""https?://[^\s<>"'“”]+""", RegexOption.IGNORE_CASE)

fun extractUrl(text: String?): String? =
    text?.let { URL_REGEX.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?') }
