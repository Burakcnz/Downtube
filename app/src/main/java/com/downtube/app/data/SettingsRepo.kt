package com.downtube.app.data

import android.content.Context
import android.content.SharedPreferences
import com.downtube.app.engine.EngineChain
import com.downtube.app.engine.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val quickMode: Boolean = false,
    val defaultType: MediaType = MediaType.MP4,
    val defaultHeight: Int = 1080,
    val defaultKbps: Int = 320,
    val compatMode: Boolean = false,
    // aria2c buffers its progress until the end, so the native downloader (live progress) is the default
    val useAria2: Boolean = false,
    val engineOrder: List<String> = EngineChain.DEFAULT_ORDER,
    val disabledEngines: Set<String> = emptySet(),
    val cobaltUrl: String = "",
    val cobaltKey: String = "",
    val lastYtdlpUpdate: Long = 0,
)

class SettingsRepo(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<AppSettings> = _flow

    fun current(): AppSettings = _flow.value

    private fun read(): AppSettings {
        val d = AppSettings()
        val order = prefs.getString("order", null)?.split(',')?.filter { it.isNotBlank() } ?: d.engineOrder
        return AppSettings(
            quickMode = prefs.getBoolean("quick", d.quickMode),
            defaultType = prefs.getString("type", null)?.let { runCatching { MediaType.valueOf(it) }.getOrNull() } ?: d.defaultType,
            defaultHeight = prefs.getInt("height", d.defaultHeight),
            defaultKbps = prefs.getInt("kbps", d.defaultKbps),
            compatMode = prefs.getBoolean("compat", d.compatMode),
            useAria2 = prefs.getBoolean("aria2c", d.useAria2),
            // engines added in later versions get appended to a saved order
            engineOrder = order + EngineChain.DEFAULT_ORDER.filter { it !in order },
            disabledEngines = prefs.getString("disabled", "")!!.split(',').filter { it.isNotBlank() }.toSet(),
            cobaltUrl = prefs.getString("cobalt_url", d.cobaltUrl)!!,
            cobaltKey = prefs.getString("cobalt_key", d.cobaltKey)!!,
            lastYtdlpUpdate = prefs.getLong("ytdlp_update", d.lastYtdlpUpdate),
        )
    }

    @Synchronized
    fun update(block: (AppSettings) -> AppSettings) {
        val n = block(current())
        prefs.edit()
            .putBoolean("quick", n.quickMode)
            .putString("type", n.defaultType.name)
            .putInt("height", n.defaultHeight)
            .putInt("kbps", n.defaultKbps)
            .putBoolean("compat", n.compatMode)
            .putBoolean("aria2c", n.useAria2)
            .putString("order", n.engineOrder.joinToString(","))
            .putString("disabled", n.disabledEngines.joinToString(","))
            .putString("cobalt_url", n.cobaltUrl.trim())
            .putString("cobalt_key", n.cobaltKey.trim())
            .putLong("ytdlp_update", n.lastYtdlpUpdate)
            .apply()
        _flow.value = n
    }
}
