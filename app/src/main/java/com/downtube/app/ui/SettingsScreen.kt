package com.downtube.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downtube.app.BuildConfig
import com.downtube.app.DownTubeApp
import com.downtube.app.R
import com.downtube.app.data.AppSettings
import com.downtube.app.engine.MP3_BITRATES
import com.downtube.app.engine.MediaType
import com.downtube.app.engine.STANDARD_HEIGHTS
import com.downtube.app.engine.heightLabel
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DownTubeApp
    val s by app.settings.flow.collectAsState()
    val scope = rememberCoroutineScope()
    fun set(block: (AppSettings) -> AppSettings) = app.settings.update(block)

    var updateText by remember { mutableStateOf<String?>(null) }
    var cobaltUrl by remember { mutableStateOf(s.cobaltUrl) }
    var cobaltKey by remember { mutableStateOf(s.cobaltKey) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState())) {
            BackBar(stringResource(R.string.settings), onBack)
            Column(Modifier.padding(horizontal = 16.dp)) {
                ToggleRow(stringResource(R.string.set_quick), stringResource(R.string.set_quick_desc), s.quickMode) { v -> set { it.copy(quickMode = v) } }

                Section(stringResource(R.string.set_default_type))
                ChipRow(listOf(MediaType.MP4 to "MP4", MediaType.MP3 to "MP3"), s.defaultType) { v -> set { it.copy(defaultType = v) } }

                Section(stringResource(R.string.set_default_height))
                ChipRow(STANDARD_HEIGHTS.map { it to heightLabel(it) }, s.defaultHeight) { v -> set { it.copy(defaultHeight = v) } }

                Section(stringResource(R.string.set_default_kbps))
                ChipRow(MP3_BITRATES.map { it to "$it kbps" }, s.defaultKbps) { v -> set { it.copy(defaultKbps = v) } }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                ToggleRow(stringResource(R.string.set_compat), stringResource(R.string.set_compat_desc), s.compatMode) { v -> set { it.copy(compatMode = v) } }
                ToggleRow(stringResource(R.string.set_aria), stringResource(R.string.set_aria_desc), s.useAria2) { v -> set { it.copy(useAria2 = v) } }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Section(stringResource(R.string.set_engines))
                s.engineOrder.forEachIndexed { i, id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(id !in s.disabledEngines, { on ->
                            set { it.copy(disabledEngines = if (on) it.disabledEngines - id else it.disabledEngines + id) }
                        })
                        Text("${i + 1}. ${engineName(id)}", Modifier.weight(1f))
                        IconButton({ set { it.copy(engineOrder = it.engineOrder.move(i, i - 1)) } }, enabled = i > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, null)
                        }
                        IconButton({ set { it.copy(engineOrder = it.engineOrder.move(i, i + 1)) } }, enabled = i < s.engineOrder.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, null)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    cobaltUrl, { cobaltUrl = it; set { s2 -> s2.copy(cobaltUrl = it) } },
                    Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.set_cobalt_url)) }, singleLine = true,
                    placeholder = { Text("https://cobalt.example.com") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    cobaltKey, { cobaltKey = it; set { s2 -> s2.copy(cobaltKey = it) } },
                    Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.set_cobalt_key)) }, singleLine = true,
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                val updating = stringResource(R.string.set_updating)
                OutlinedButton(
                    onClick = {
                        updateText = updating
                        scope.launch {
                            updateText = withContext(Dispatchers.IO) {
                                runCatching {
                                    DownTubeApp.awaitReady()
                                    YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                                    app.settings.update { it.copy(lastYtdlpUpdate = System.currentTimeMillis()) }
                                    context.getString(R.string.set_updated, YoutubeDL.versionName(context) ?: "?")
                                }.getOrElse { it.message ?: "error" }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.set_update)) }
                updateText?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(16.dp))
                Text(
                    "DownTube " + stringResource(R.string.set_version, BuildConfig.VERSION_NAME),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(top = 12.dp, bottom = 4.dp), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ToggleRow(title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) -> FilterChip(v == selected, { onSelect(v) }, { Text(label) }) }
    }
}

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
