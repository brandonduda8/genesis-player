package com.apexforge.genesisplayer.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private data class QueueEntry(
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String
)

/**
 * BRKN Vibes wave 2: the queue sheet.
 *
 * - Lists upcoming items straight from the MediaController
 *   (mediaItemCount / getMediaItemAt from currentMediaItemIndex). The current
 *   track is highlighted and pinned; everything after it is the live queue.
 * - Long-press + drag reorders via player.moveMediaItem. The dragged row
 *   floats; the move fires once on release and the next poll re-syncs —
 *   simple, no mid-drag index bookkeeping to get wrong.
 * - Swipe a row away to remove it (player.removeMediaItem).
 * - "Clear" removes everything except the current track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(controller: MediaController?, onDismiss: () -> Unit) {
    var order by remember { mutableStateOf<List<QueueEntry>>(emptyList()) }
    var curAbs by remember { mutableIntStateOf(-1) }
    var dragging by remember { mutableStateOf(false) }
    var rowHpx by remember { mutableIntStateOf(0) }

    LaunchedEffect(controller) {
        while (true) {
            val c = controller
            if (c != null && !dragging) {
                val n = c.mediaItemCount
                val cur = c.currentMediaItemIndex
                curAbs = cur
                order = if (cur in 0 until n) {
                    (cur until n).map { i ->
                        val m = c.getMediaItemAt(i)
                        QueueEntry(
                            mediaId = m.mediaId,
                            title = (m.mediaMetadata.title ?: "Untitled").toString(),
                            artist = (m.mediaMetadata.artist ?: "").toString(),
                            artworkUrl = m.mediaMetadata.artworkUri?.toString() ?: ""
                        )
                    }
                } else emptyList()
            }
            delay(500)
        }
    }

    fun clearKeepCurrent() {
        val c = controller ?: return
        var cur = c.currentMediaItemIndex
        val n = c.mediaItemCount
        // Remove from the tail down; indices below cur shift as we go.
        for (i in n - 1 downTo 0) {
            if (i == cur) continue
            c.removeMediaItem(i)
            if (i < cur) cur--
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceDark
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Up next", style = MaterialTheme.typography.headlineSmall, color = PhoenixGold)
            Spacer(Modifier.weight(1f))
            Text("${order.size} in queue", color = TextDim, fontSize = 13.sp)
            TextButton(onClick = { clearKeepCurrent() }) {
                Text("Clear", color = EmberOrange, fontSize = 14.sp)
            }
        }
        if (order.isEmpty()) {
            Text(
                "Nothing queued — play something first.",
                color = TextDim, fontSize = 14.sp,
                modifier = Modifier.padding(24.dp)
            )
        }
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            itemsIndexed(order, key = { idx, e -> "${e.mediaId}#$idx" }) { idx, e ->
                val isCurrent = idx == 0
                QueueRow(
                    entry = e,
                    index = idx,
                    lastIndex = order.size - 1,
                    isCurrent = isCurrent,
                    rowHpx = rowHpx,
                    onRowHeight = { rowHpx = it },
                    onDragState = { dragging = it },
                    onMove = { from, to -> controller?.moveMediaItem(curAbs + from, curAbs + to) },
                    onRemove = { controller?.removeMediaItem(curAbs + idx) }
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueRow(
    entry: QueueEntry,
    index: Int,
    lastIndex: Int,
    isCurrent: Boolean,
    rowHpx: Int,
    onRowHeight: (Int) -> Unit,
    onDragState: (Boolean) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState()
    var dragAcc by remember { mutableFloatStateOf(0f) }
    var rowDragging by remember { mutableStateOf(false) }

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            onRemove()
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Delete, "Remove", tint = EmberOrange)
            }
        }
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp)
                .onSizeChanged { if (it.height > 0) onRowHeight(it.height) }
                .offset { IntOffset(0, if (rowDragging) dragAcc.roundToInt() else 0) }
                .zIndex(if (rowDragging) 1f else 0f)
                // Keyed on (mediaId, index): a reorder recomposes the row with
                // a fresh index, so the drag math below never goes stale.
                // During a drag the poll loop is paused (dragging), so the
                // index can't change mid-gesture.
                .pointerInput(entry.mediaId, index) {
                    // Long-press drag on non-current rows only; the current
                    // track stays pinned at the head.
                    if (isCurrent) return@pointerInput
                    var acc = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            rowDragging = true
                            acc = 0f
                            onDragState(true)
                        },
                        onDragEnd = {
                            // Rows are uniform height with no spacing, so the
                            // drop target is (rowTop + drag) / rowHeight.
                            val target = if (rowHpx > 0) {
                                ((index * rowHpx + acc) / rowHpx)
                                    .roundToInt().coerceIn(1, lastIndex)
                            } else -1
                            rowDragging = false
                            dragAcc = 0f
                            onDragState(false)
                            if (target in 1..lastIndex && target != index) {
                                onMove(index, target)
                            }
                        },
                        onDragCancel = {
                            rowDragging = false
                            dragAcc = 0f
                            onDragState(false)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            acc += dragAmount.y
                            dragAcc = acc
                        }
                    )
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QueueRowContent(entry, isCurrent)
        }
    }
}

@Composable
private fun QueueRowContent(entry: QueueEntry, isCurrent: Boolean) {
    if (!isCurrent) {
        Icon(Icons.Filled.DragHandle, "Drag to reorder", tint = TextDim,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
    }
    TrackArtwork(entry.mediaId, entry.artworkUrl, Modifier.size(48.dp), "Art", 8.dp)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
        Text(
            entry.title,
            color = if (isCurrent) EmberOrange else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp
        )
        Text(
            if (isCurrent && entry.artist.isNotEmpty()) "Now playing · ${entry.artist}"
            else entry.artist,
            color = TextDim, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
    if (isCurrent) {
        Box(Modifier.size(20.dp)) // alignment spacer where the handle would be
    }
}
