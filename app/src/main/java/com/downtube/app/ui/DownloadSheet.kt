package com.downtube.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.downtube.app.DownTubeApp
import com.downtube.app.R
import com.downtube.app.data.DownloadItem
import com.downtube.app.download.DownloadWorker
import com.downtube.app.engine.AudioOption
import com.downtube.app.engine.MediaInfo
import com.downtube.app.engine.MediaType
import com.downtube.app.engine.formatBytes

private sealed interface SheetState {
    data class Loading(val engine: String?) : SheetState
    data class Loaded(val info: MediaInfo) : SheetState
    data class Failed(val message: String) : SheetState
}

/**
 * Popup content: fetches the link info through the engine chain, lets the user pick MP4/MP3 and a quality,
 * then queues the download. [onQueued] is called once the job is in WorkManager.
 */
@Composable
fun DownloadSheet(url: String, onQueued: () -> Unit) {
    val app = LocalContext.current.applicationContext as DownTubeApp
    var state by remember(url) { mutableStateOf<SheetState>(SheetState.Loading(null)) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(url, attempt) {
        state = SheetState.Loading(null)
        state = try {
            SheetState.Loaded(app.engines.info(url) { e -> state = SheetState.Loading(e.displayName) })
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            SheetState.Failed(t.message ?: "")
        }
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        AnimatedContent(state, label = "sheet", contentKey = { it::class }) { s ->
            when (s) {
                is SheetState.Loading -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.fetching), style = MaterialTheme.typography.titleMedium)
                    s.engine?.let {
                        Text(stringResource(R.string.trying_engine, it), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is SheetState.Failed -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                    Text(stringResource(R.string.fetch_failed), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 8)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { attempt++ }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry)) }
                }
                is SheetState.Loaded -> Picker(s.info, onQueued)
            }
        }
    }
}

@Composable
private fun Picker(info: MediaInfo, onQueued: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DownTubeApp
    val defaults = app.settings.current()
    var type by remember { mutableStateOf(defaults.defaultType) }
    var video by remember {
        mutableStateOf(
            info.videoOptions.firstOrNull { it.height in 1..defaults.defaultHeight } ?: info.videoOptions.first()
        )
    }
    var audio by remember {
        mutableStateOf(info.audioOptions.firstOrNull { it.kbps <= defaults.defaultKbps } ?: info.audioOptions.first())
    }
    var wholePlaylist by remember { mutableStateOf(info.isPlaylist) }

    Column {
        Header(info)
        Spacer(Modifier.height(16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = type == MediaType.MP4, onClick = { type = MediaType.MP4 },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Default.Movie, null, Modifier.size(18.dp)) },
            ) { Text(stringResource(R.string.video)) }
            SegmentedButton(
                selected = type == MediaType.MP3, onClick = { type = MediaType.MP3 },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Default.MusicNote, null, Modifier.size(18.dp)) },
            ) { Text(stringResource(R.string.audio)) }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.heightIn(max = 340.dp)) {
            if (type == MediaType.MP4) {
                items(info.videoOptions, key = { "v${it.height}${it.ref}" }) { o ->
                    OptionRow(
                        selected = o == video, onClick = { video = o },
                        title = o.label, badge = o.badge,
                        detail = listOfNotNull(
                            o.fps.takeIf { it > 30 }?.let { "${it}fps" },
                            "HDR".takeIf { o.hdr },
                            formatBytes(o.sizeBytes),
                        ).joinToString(" · "),
                    )
                }
            } else {
                items(info.audioOptions, key = { "a${it.kbps}" }) { o: AudioOption ->
                    OptionRow(
                        selected = o == audio, onClick = { audio = o },
                        title = "MP3 ${o.kbps} kbps", badge = if (o.kbps >= 320) "HQ" else null,
                        detail = formatBytes(o.sizeBytes)?.let { "~$it" }.orEmpty(),
                    )
                }
            }
        }

        if (info.isPlaylist) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.whole_playlist, info.playlistCount), Modifier.weight(1f))
                Switch(wholePlaylist, { wholePlaylist = it })
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val item = DownloadItem(
                    id = DownloadWorker.newId(), url = info.url, title = info.title, thumbnail = info.thumbnail,
                    type = type, height = video.height, kbps = audio.kbps, playlist = wholePlaylist,
                    ref = video.ref.takeIf { type == MediaType.MP4 }, preferredEngine = info.engineId,
                )
                DownloadWorker.enqueue(context, item)
                onQueued()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Download, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.download), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun Header(info: MediaInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (info.thumbnail != null) {
                AsyncImage(info.thumbnail, null, Modifier.fillMaxWidth().height(68.dp), contentScale = ContentScale.Crop)
            }
            if (info.durationSec > 0) {
                Text(
                    duration(info.durationSec), color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(info.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            val sub = listOfNotNull(info.uploader, stringResource(R.string.engine_used, engineName(info.engineId)))
            Text(sub.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OptionRow(selected: Boolean, onClick: () -> Unit, title: String, badge: String?, detail: String) {
    val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.5.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick)
        Text(title, Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (detail.isNotBlank()) {
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
        }
        if (badge != null) {
            val c = when (badge) { "8K", "4K" -> Brand.Red; "2K", "FHD" -> Brand.Blue; else -> MaterialTheme.colorScheme.outline }
            Text(
                badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(c, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

fun engineName(id: String?) = when (id) {
    "ytdlp" -> "yt-dlp"
    "newpipe" -> "NewPipe"
    "cobalt" -> "Cobalt"
    "direct" -> "Direct"
    "sniffer" -> "Web sniffer"
    else -> id ?: "-"
}

private fun duration(s: Long): String =
    if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

/** Quick mode: queue straight away with the default format and quality, no popup. */
fun quickEnqueue(app: DownTubeApp, url: String) {
    val s = app.settings.current()
    val item = DownloadItem(
        id = DownloadWorker.newId(), url = url, title = url, thumbnail = null,
        type = s.defaultType, height = s.defaultHeight, kbps = s.defaultKbps, playlist = false,
        ref = null, preferredEngine = null,
    )
    DownloadWorker.enqueue(app, item)
}
