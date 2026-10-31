package com.downtube.app.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val CHUNK = 8L * 1024 * 1024

    data class Head(val length: Long, val contentType: String?, val fileName: String?)

    fun head(url: String, headers: Map<String, String> = emptyMap()): Head? = runCatching {
        // Some CDNs reject HEAD, a one byte ranged GET answers the same question
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", "bytes=0-0")
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: r.header("Content-Length")?.toLongOrNull() ?: -1
            val name = r.header("Content-Disposition")
                ?.let { Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""").find(it)?.groupValues?.get(1) }
            Head(total, r.header("Content-Type"), name)
        }
    }.getOrNull()

    /**
     * Downloads [url] into [dest] using ranged chunks (sites like YouTube throttle long single requests).
     * Resumes from an existing partial file.
     */
    fun download(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        cancel: CancelToken,
        onBytes: (done: Long, total: Long) -> Unit,
    ) {
        val total = head(url, headers)?.length ?: -1
        var pos = if (dest.exists()) dest.length() else 0L
        if (total in 1..pos) return
        RandomAccessFile(dest, "rw").use { raf ->
            raf.seek(pos)
            val buf = ByteArray(64 * 1024)
            while (total < 0 || pos < total) {
                cancel.throwIfCancelled()
                val end = if (total > 0) minOf(pos + CHUNK, total) - 1 else -1
                val range = if (end >= 0) "bytes=$pos-$end" else "bytes=$pos-"
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", range)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                val call = client.newCall(req)
                cancel.onCancel { call.cancel() }
                call.execute().use { r ->
                    if (!r.isSuccessful) throw EngineException("HTTP ${r.code}")
                    // Server ignored the range header and restarted from zero
                    if (r.code == 200 && pos > 0) { pos = 0; raf.setLength(0); raf.seek(0) }
                    val body = r.body!!.byteStream()
                    var read = 0L
                    while (true) {
                        val n = body.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        pos += n; read += n
                        onBytes(pos, total)
                    }
                    // 200 = whole body sent, open range = rest of file sent, 0 bytes = nothing left
                    if (r.code == 200 || end < 0 || read == 0L) return
                }
            }
        }
    }
}
