package com.downtube.app.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.downtube.app.DownTubeApp
import com.downtube.app.R
import com.downtube.app.data.DownloadItem
import com.downtube.app.data.Status
import com.downtube.app.download.DownloadWorker
import com.downtube.app.download.MediaStoreSaver
import com.downtube.app.engine.MediaType
import com.downtube.app.engine.extractUrl
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { DownTubeTheme { Root() } }
    }
}

@Composable
private fun Root() {
    var settingsOpen by remember { mutableStateOf(false) }
    BackHandler(settingsOpen) { settingsOpen = false }
    if (settingsOpen) SettingsScreen { settingsOpen = false } else HomeScreen { settingsOpen = true }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(openSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DownTubeApp
    val items by app.store.items.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // a new download is added on top; bring it into view instead of leaving the list scrolled away
    val newestId = items.firstOrNull()?.id
    LaunchedEffect(newestId) { if (newestId != null) listState.animateScrollToItem(0) }
    var input by remember { mutableStateOf("") }
    var sheetUrl by remember { mutableStateOf<String?>(null) }
    var clipUrl by remember { mutableStateOf<String?>(null) }

    // Clipboard can only be read while the window has focus (Android 10+)
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        if (focused) clipUrl = readClipboardUrl(context)?.takeIf { u -> items.none { it.url == u } }
    }

    fun open(text: String) {
        val u = extractUrl(text) ?: return
        if (app.settings.current().quickMode) quickEnqueue(app, u) else sheetUrl = u
        input = ""
        clipUrl = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painterResource(R.drawable.logo), null,
                            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("DownTube", fontWeight = FontWeight.Bold)
                    }
                },
                actions = { IconButton(openSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.paste_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        IconButton({ readClipboardUrl(context)?.let { input = it } }) {
                            Icon(Icons.Default.ContentPaste, stringResource(R.string.paste))
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { open(input) }, enabled = extractUrl(input) != null,
                    modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.fetch), fontWeight = FontWeight.SemiBold) }
                clipUrl?.let { u ->
                    Spacer(Modifier.height(4.dp))
                    AssistChip(
                        onClick = { open(u) },
                        label = { Text(stringResource(R.string.clipboard_suggest), maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.ContentPaste, null, Modifier.size(16.dp)) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.downloads), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (items.isEmpty()) item {
                Text(
                    stringResource(R.string.no_downloads), Modifier.fillMaxWidth().padding(top = 48.dp),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(items, key = { it.id }) { DownloadRow(it) }
        }
    }

    sheetUrl?.let { url ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        ModalBottomSheet(onDismissRequest = { sheetUrl = null }, sheetState = sheet) {
            DownloadSheet(url) { scope.launch { sheet.hide() }.invokeOnCompletion { sheetUrl = null } }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(item: DownloadItem) {
    val context = LocalContext.current
    val app = context.applicationContext as DownTubeApp
    var menu by remember { mutableStateOf(false) }
    val first = item.outputs.firstOrNull()?.let(Uri::parse)
    val mime = if (item.type == MediaType.MP3) "audio/*" else "video/*"

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(
                onClick = { if (item.status == Status.DONE && first != null) openUri(context, first, mime) },
                onLongClick = { menu = true },
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 96.dp, height = 56.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (item.thumbnail != null) AsyncImage(item.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(if (item.type == MediaType.MP3) Icons.Default.MusicNote else Icons.Default.Movie, null)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            val active = item.status == Status.RUNNING || item.status == Status.PROCESSING || item.status == Status.QUEUED
            val statusText = when (item.status) {
                Status.DONE -> stringResource(R.string.status_done)
                Status.ERROR -> stringResource(R.string.status_error)
                Status.CANCELED -> stringResource(R.string.status_canceled)
                else -> null // the live block below says what is happening
            }
            val color = when (item.status) {
                Status.DONE -> Color(0xFF5BD68A)
                Status.ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                listOfNotNull(item.qualityLabel, statusText, item.usedEngine?.let(::engineName)).joinToString(" · "),
                fontSize = 12.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (active) {
                Spacer(Modifier.height(6.dp))
                LiveProgress(item)
            }
            if (item.status == Status.ERROR && item.error != null) {
                Text(item.error, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        when (item.status) {
            Status.RUNNING, Status.PROCESSING, Status.QUEUED ->
                IconButton({ DownloadWorker.cancel(context, item.id) }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
            Status.ERROR, Status.CANCELED ->
                IconButton({ DownloadWorker.retry(context, item) }) { Icon(Icons.Default.Refresh, stringResource(R.string.retry)) }
            Status.DONE -> Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, null) }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            if (first != null && item.status == Status.DONE) {
                DropdownMenuItem({ Text(stringResource(R.string.open)) }, { menu = false; openUri(context, first, mime) })
                DropdownMenuItem({ Text(stringResource(R.string.share)) }, {
                    menu = false
                    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, first)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, null))
                })
            }
            DropdownMenuItem({ Text(stringResource(R.string.delete)) }, {
                menu = false
                DownloadWorker.cancel(context, item.id)
                app.store.remove(item.id)
            })
            if (item.outputs.isNotEmpty()) {
                DropdownMenuItem({ Text(stringResource(R.string.delete_file)) }, {
                    menu = false
                    item.outputs.forEach { MediaStoreSaver.delete(context, Uri.parse(it)) }
                    app.store.remove(item.id)
                })
            }
        }
    }
}

private fun openUri(context: Context, uri: Uri, mime: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

private fun readClipboardUrl(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return extractUrl(clip.getItemAt(0).coerceToText(context)?.toString())
}

@Composable
internal fun BackBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
