package com.downtube.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.downtube.app.DownTubeApp
import com.downtube.app.R
import com.downtube.app.engine.extractUrl
import kotlinx.coroutines.launch

/** Share target: Share → DownTube opens the quality popup over the current app. */
class ShareActivity : ComponentActivity() {
    private var url by mutableStateOf<String?>(null)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!handle(intent)) return
        askNotificationPermission()

        setContent {
            DownTubeTheme {
                val current = url ?: return@DownTubeTheme
                // a new share while the popup is open restarts it for the new link
                key(current) {
                    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val scope = rememberCoroutineScope()
                    ModalBottomSheet(onDismissRequest = { finish() }, sheetState = sheet) {
                        DownloadSheet(current) {
                            Toast.makeText(this@ShareActivity, R.string.download_started, Toast.LENGTH_SHORT).show()
                            scope.launch { sheet.hide() }.invokeOnCompletion { finish() }
                        }
                    }
                }
            }
        }
    }

    // singleTask: a second share reuses this instance instead of only bringing the old task forward
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** @return false when the activity finished (no link, or quick mode queued it). */
    private fun handle(intent: Intent): Boolean {
        val link = linkFrom(intent)
        if (link == null) {
            Toast.makeText(this, R.string.no_link, Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        val app = application as DownTubeApp
        if (app.settings.current().quickMode) {
            quickEnqueue(app, link)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        url = link
        return true
    }

    private fun linkFrom(intent: Intent): String? = when (intent.action) {
        Intent.ACTION_SEND -> extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            ?: extractUrl(intent.getStringExtra(Intent.EXTRA_SUBJECT))
        Intent.ACTION_VIEW -> intent.dataString
        else -> null
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
