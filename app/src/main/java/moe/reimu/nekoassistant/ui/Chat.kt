package moe.reimu.nekoassistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.reimu.nekoassistant.ai.ChatMessage
import moe.reimu.nekoassistant.ai.InferenceStatus
import java.util.Date
import kotlin.math.roundToInt

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
                // Per bubble, not around the whole transcript: "select all" then copies this
                // one turn instead of everything the agent has ever said.
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        // Agent turns are machine output (JSON plans, action lines), so they
                        // read better monospaced than wrapped as prose.
                        fontFamily = if (isUser) FontFamily.Default else FontFamily.Monospace,
                    )
                }
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
                // Inside the surface, so its colour runs to the screen edge behind the
                // navigation bar instead of stopping short of the rounded corner. Union, not
                // sum, so the keyboard inset cannot stack on top of the navigation bar's.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
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

/**
 * The agent has parked itself and is waiting for the user to do something it cannot. Pinned
 * above the composer rather than put in the transcript, since it is the one thing on screen
 * that is blocking the run.
 */
@Composable
fun TakeOverNotice(message: String, onResume: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "The agent needs you",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onResume) {
                Text("Continue")
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

/** Height of the floating preview, so the transcript can reserve room for it. */
val LiveScreenPreviewHeight = 160.dp

/** Resting gap between the floating preview and the corner it sits in. */
private val PreviewInset = 16.dp

/**
 * The virtual display, floating over the transcript. It redraws as fast as frames arrive,
 * so it stays a corner thumbnail until tapped, when it takes over the content area.
 */
@Composable
fun LiveScreenPreview(bitmap: Bitmap, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    // Back collapses the preview instead of leaving the screen while it is open.
    BackHandler(enabled = expanded) { expanded = false }
    // Drag is measured from the bottom-end corner it rests in, so it survives expanding.
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val settle = remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
    // A screen recording has no edge of its own against the transcript behind it.
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    // Both branches stay mounted through the swap, so the card grows out of its corner and
    // shrinks back into it instead of cutting.
    val appear = scaleIn(initialScale = 0.8f) + fadeIn()
    val disappear = scaleOut(targetScale = 0.8f) + fadeOut()

    // Fills the content area, but only the card and the scrim take touches, so the
    // transcript behind stays scrollable.
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
    ) {
        AnimatedVisibility(
            visible = !expanded,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(PreviewInset)
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .onSizeChanged { cardSize = it }
                .height(LiveScreenPreviewHeight)
                .aspectRatio(aspectRatio)
                .pointerInput(containerSize, cardSize) {
                    val inset = PreviewInset.toPx()
                    // x is measured from the bottom-end corner: 0 is the resting spot,
                    // negative moves left.
                    val minX = cardSize.width + inset - containerSize.width
                    val minY = cardSize.height + inset - containerSize.height
                    val snapLeft = minX + inset
                    val snapRight = 0f
                    val velocityTracker = VelocityTracker()

                    // Keep the whole card inside the content area. coerceAtMost before
                    // coerceAtLeast, so an oversized card cannot invert the range and throw.
                    fun clampX(x: Float) = x.coerceAtMost(inset).coerceAtLeast(minX)

                    fun clampY(y: Float) = y.coerceAtMost(inset).coerceAtLeast(minY)

                    detectDragGestures(
                        onDragStart = {
                            settle.value?.cancel()
                            velocityTracker.resetTracking()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            dragOffset = Offset(
                                clampX(dragOffset.x + dragAmount.x),
                                clampY(dragOffset.y + dragAmount.y),
                            )
                        },
                        onDragEnd = {
                            val velocity = velocityTracker.calculateVelocity().x
                            val target = when {
                                velocity > viewConfiguration.minimumFlingVelocity -> snapRight
                                velocity < -viewConfiguration.minimumFlingVelocity -> snapLeft
                                dragOffset.x > (snapLeft + snapRight) / 2 -> snapRight
                                else -> snapLeft
                            }
                            settle.value = scope.launch {
                                animate(dragOffset.x, target, velocity) { value, _ ->
                                    dragOffset = dragOffset.copy(x = value)
                                }
                            }
                        },
                    )
                },
            enter = appear,
            exit = disappear,
        ) {
            Surface(
                onClick = { expanded = true },
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 8.dp,
                border = border,
            ) {
                Image(
                    bitmap = image,
                    contentDescription = "Virtual display preview",
                    contentScale = ContentScale.Fit,
                )
            }
        }

        // Last, so it is the one taking touches while the card is still animating out.
        AnimatedVisibility(
            visible = expanded,
            enter = appear,
            exit = disappear,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f))
                    .clickable { expanded = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = image,
                    contentDescription = "Virtual display preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .border(border, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp)),
                )
            }
        }
    }
}
