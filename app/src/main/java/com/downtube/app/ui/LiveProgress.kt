package com.downtube.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downtube.app.data.DownloadItem
import com.downtube.app.download.ProgressText
import com.downtube.app.engine.MediaType
import com.downtube.app.engine.Stage

/** Step line ("Video indiriliyor (1/2)"), a bar that always moves, and size / speed / time left. */
@Composable
fun LiveProgress(item: DownloadItem) {
    val context = LocalContext.current
    val stage = item.live?.stage ?: Stage.PREPARING
    val step = ProgressText.step(context, item)
    val detail = ProgressText.detail(context, item)

    Column(Modifier.fillMaxWidth()) {
        AnimatedContent(step, label = "step", transitionSpec = { fadeIn() togetherWith fadeOut() }) { text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(stageIcon(stage, item), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(5.dp))
                Text(
                    text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ProgressBar(
            fraction = item.progress / 100f,
            indeterminate = ProgressText.indeterminate(item),
        )
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun stageIcon(stage: Stage, item: DownloadItem) = when (stage) {
    Stage.QUEUED, Stage.PREPARING -> Icons.Default.HourglassTop
    Stage.DOWNLOADING -> if (item.type == MediaType.MP3 || item.live?.audioPart == true) Icons.Default.MusicNote else Icons.Default.Download
    Stage.MERGING -> Icons.Default.CallMerge
    Stage.CONVERTING -> Icons.Default.AutoAwesome
    Stage.SAVING -> Icons.Default.SaveAlt
}

/**
 * Brand-gradient bar. Determinate: eases to the new value and a light sweeps across the filled part so it
 * looks alive even between updates. Indeterminate: a segment slides back and forth instead of sitting at 0.
 */
@Composable
private fun ProgressBar(fraction: Float, indeterminate: Boolean) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "fraction")
    val transition = rememberInfiniteTransition(label = "bar")
    val sweep by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    // the row itself is surfaceContainerHigh, so the track needs a lighter tone to be visible
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val fill = Brush.horizontalGradient(listOf(Brand.Blue, Brand.Cyan))

    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (indeterminate) {
            val seg = size.width * 0.3f
            val x = -seg + (size.width + seg) * sweep
            drawRoundRect(
                fill, topLeft = Offset(x.coerceAtLeast(0f), 0f),
                size = Size((minOf(x + seg, size.width) - x.coerceAtLeast(0f)).coerceAtLeast(0f), size.height),
                cornerRadius = r,
            )
            return@Canvas
        }
        val w = size.width * shown
        if (w <= 0f) return@Canvas
        drawRoundRect(fill, size = Size(w, size.height), cornerRadius = r)
        // moving highlight over the filled part
        val glow = w * 0.35f
        val gx = -glow + (w + glow) * sweep
        drawRoundRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                startX = gx, endX = gx + glow,
            ),
            size = Size(w, size.height), cornerRadius = r,
        )
    }
}
