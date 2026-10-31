package com.downtube.app.data

import android.content.Context
import com.downtube.app.engine.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Status { QUEUED, RUNNING, PROCESSING, DONE, ERROR, CANCELED }

data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val type: MediaType,
    val height: Int,
    val kbps: Int,
    val playlist: Boolean,
    val ref: String?,
    val preferredEngine: String?,
    val usedEngine: String? = null,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val error: String? = null,
    val outputs: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** What the job is doing right now (stage, speed, ETA). Memory only, never written to disk. */
    val live: com.downtube.app.engine.Progress? = null,
) {
    val qualityLabel: String
        get() = if (type == MediaType.MP3) "MP3 · ${kbps} kbps" else "MP4 · " + com.downtube.app.engine.heightLabel(height)
}

/** Small JSON-file backed list; downloads are few and this keeps the app free of a DB schema. */
class DownloadStore(context: Context) {
    private val file = File(context.filesDir, "downloads.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DownloadItem>> = _items

    fun get(id: String) = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(item: DownloadItem) {
        val list = _items.value.toMutableList()
        val i = list.indexOfFirst { it.id == item.id }
        if (i >= 0) list[i] = item else list.add(0, item)
        _items.value = list
        save(list)
    }

    @Synchronized
    fun update(id: String, block: (DownloadItem) -> DownloadItem) {
        get(id)?.let { upsert(block(it)) }
    }

    @Synchronized
    fun remove(id: String) {
        val list = _items.value.filterNot { it.id == id }
        _items.value = list
        save(list)
    }

    /**
     * Progress ticks only touch memory; the file is rewritten on state changes. The overall bar value only
     * grows so it never jumps back (e.g. when the audio stream starts after the video).
     */
    @Synchronized
    fun setLive(id: String, p: com.downtube.app.engine.Progress) {
        val status = when (p.stage) {
            com.downtube.app.engine.Stage.MERGING, com.downtube.app.engine.Stage.CONVERTING,
            com.downtube.app.engine.Stage.SAVING -> Status.PROCESSING
            else -> Status.RUNNING
        }
        _items.value = _items.value.map {
            if (it.id != id) it
            else it.copy(live = p, status = status, progress = if (p.overall >= 0) maxOf(it.progress, p.overall) else it.progress)
        }
    }

    /** A fallback engine starts the job over, so its bar starts over too. */
    @Synchronized
    fun resetLive(id: String) {
        _items.value = _items.value.map { if (it.id == id) it.copy(progress = 0f, live = null) else it }
    }

    private fun load(): List<DownloadItem> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            // a process death mid download leaves RUNNING rows behind
            .map { if (it.status in setOf(Status.RUNNING, Status.PROCESSING, Status.QUEUED)) it.copy(status = Status.ERROR, error = "Interrupted") else it }
    }.getOrDefault(emptyList())

    private fun save(list: List<DownloadItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private fun toJson(d: DownloadItem) = JSONObject().apply {
        put("id", d.id); put("url", d.url); put("title", d.title); put("thumbnail", d.thumbnail)
        put("type", d.type.name); put("height", d.height); put("kbps", d.kbps); put("playlist", d.playlist)
        put("ref", d.ref); put("preferredEngine", d.preferredEngine); put("usedEngine", d.usedEngine)
        put("status", d.status.name); put("progress", d.progress.toDouble()); put("error", d.error)
        put("outputs", JSONArray(d.outputs)); put("createdAt", d.createdAt)
    }

    private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k)

    private fun fromJson(j: JSONObject) = DownloadItem(
        id = j.getString("id"), url = j.getString("url"), title = j.optString("title"), thumbnail = j.str("thumbnail"),
        type = MediaType.valueOf(j.getString("type")), height = j.optInt("height"), kbps = j.optInt("kbps"),
        playlist = j.optBoolean("playlist"), ref = j.str("ref"), preferredEngine = j.str("preferredEngine"),
        usedEngine = j.str("usedEngine"), status = Status.valueOf(j.getString("status")),
        progress = j.optDouble("progress").toFloat(), error = j.str("error"),
        outputs = j.optJSONArray("outputs")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        createdAt = j.optLong("createdAt"),
    )
}
