package moe.reimu.nekoassistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.reimu.nekoassistant.ai.ChatMessage
import moe.reimu.nekoassistant.ai.InferenceStatus
import java.util.Date

@Composable
fun ChatBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    val isUser = message.fromUser
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser) {
            Text(
                text = message.sender,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
            )
        }
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            // ponytail: absolute cap, swap for a width fraction if tablets matter
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    // Agent turns are machine output (JSON plans, action lines), so they
                    // read better monospaced than wrapped as prose.
                    fontFamily = if (isUser) FontFamily.Default else FontFamily.Monospace,
                )
                Text(
                    text = remember(message.timestamp, context) {
                        formatTimestamp(context, message.timestamp)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Time of day, plus the date once the turn is older than today — otherwise a transcript
 * picked up the next morning reads as if it just happened.
 *
 * ponytail: no test, the formatters need a Context and there is no Robolectric here.
 */
private fun formatTimestamp(context: Context, timestamp: Long): String {
    val instant = Date(timestamp)
    val time = DateFormat.getTimeFormat(context).format(instant)
    if (DateUtils.isToday(timestamp)) {
        return time
    }
    return "${DateFormat.getDateFormat(context).format(instant)}, $time"
}

@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isRunning: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Tell the agent what to do") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
            )
            FilledIconButton(
                onClick = if (isRunning) onStop else onSend,
                enabled = isRunning || canSend,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isRunning) "Stop" else "Send",
                )
            }
        }
    }
}

/** The "typing…" line: whichever agents are mid-inference, or that one of them failed. */
@Composable
fun AgentStatusRow(statuses: Map<String, InferenceStatus>, modifier: Modifier = Modifier) {
    val active = statuses.filterValues { it != InferenceStatus.IDLE }
    if (active.isEmpty()) {
        return
    }
    val failed = active.values.any { it == InferenceStatus.ERROR }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (failed) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        Text(
            text = active.entries.joinToString(", ") { "${it.key} ${it.value.name.lowercase()}" },
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * The virtual display, pinned above the composer. It redraws as fast as frames arrive, so
 * it stays a thumbnail and opens fullscreen on tap instead of reflowing the transcript.
 */
@Composable
fun LiveScreenPreview(bitmap: Bitmap, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                bitmap = image,
                contentDescription = "Virtual display preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(112.dp)
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = true },
            )
            Text(
                text = "Live screen",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Image(
                bitmap = image,
                contentDescription = "Virtual display preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .clickable { expanded = false },
            )
        }
    }
}
