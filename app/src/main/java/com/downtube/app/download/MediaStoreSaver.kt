package com.downtube.app.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.downtube.app.engine.MediaType
import java.io.File

/** Publishes finished files: videos to Download/DownTube, MP3s to Music/DownTube. Returns content/file URIs. */
object MediaStoreSaver {
    private const val FOLDER = "DownTube"
    private const val REPORT_EVERY = 4L shl 20

    /** @param onCopied bytes copied so far (only reported where a real copy happens). */
    fun save(context: Context, file: File, type: MediaType, onCopied: (Long) -> Unit = {}): Uri {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: if (type == MediaType.MP3) "audio/mpeg" else "video/mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveQ(context, file, type, mime, onCopied)
        else saveLegacy(context, file, type, mime)
    }

    private fun saveQ(context: Context, file: File, type: MediaType, mime: String, onCopied: (Long) -> Unit): Uri {
        val audio = type == MediaType.MP3
        val collection = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val dir = (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOWNLOADS) + "/" + FOLDER
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastReport >= REPORT_EVERY) { onCopied(copied); lastReport = copied }
                    }
                    onCopied(copied)
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        file.delete()
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File, type: MediaType, mime: String): Uri {
        val root = Environment.getExternalStoragePublicDirectory(
            if (type == MediaType.MP3) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOWNLOADS
        )
        val dir = File(root, FOLDER).apply { mkdirs() }
        var dest = File(dir, file.name)
        var n = 1
        while (dest.exists()) dest = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
        if (!file.renameTo(dest)) { file.copyTo(dest); file.delete() }
        // file:// URIs cannot be handed to other apps on API 24+, so wait for the scanner's content:// URI
        val latch = java.util.concurrent.CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime)) { _, u -> scanned = u; latch.countDown() }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        return scanned ?: Uri.fromFile(dest)
    }

    fun delete(context: Context, uri: Uri) {
        runCatching {
            if (uri.scheme == "file") File(uri.path!!).delete() else context.contentResolver.delete(uri, null, null)
        }
    }
}
