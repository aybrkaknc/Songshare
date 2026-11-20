package com.aybrkaknc.songshare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

// Bars inside the selected range use the primary color; the white-ish line is the preview position.
// A tap moves the preview position to that point. Dragging moves the start or end edge, whichever is nearer the touch.
@Composable
internal fun Waveform(
    peaks: FloatArray,
    durationMs: Long,
    start: Long,
    end: Long,
    playhead: Float,
    minClipMs: Long,
    onSeek: (Long) -> Unit,
    onRangeChange: (Long, Long) -> Unit,
) {
    val inside = MaterialTheme.colorScheme.primary
    val outside = MaterialTheme.colorScheme.outlineVariant
    val cursor = MaterialTheme.colorScheme.onSurface
    val currentStart by rememberUpdatedState(start)
    val currentEnd by rememberUpdatedState(end)
    Canvas(
        Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 10.dp).pointerInput(durationMs, minClipMs) {
            if (durationMs <= 0) return@pointerInput
            fun xOf(ms: Long) = ms.toFloat() / durationMs * size.width
            fun msOf(x: Float) = (x / size.width * durationMs).toLong().coerceIn(0L, durationMs)
            awaitEachGesture {
                val down = awaitFirstDown()
                val movingStart = abs(down.position.x - xOf(currentStart)) <= abs(down.position.x - xOf(currentEnd))
                val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                if (slop == null) {
                    onSeek(msOf(down.position.x))
                } else {
                    drag(slop.id) { change ->
                        change.consume()
                        val t = msOf(change.position.x)
                        if (movingStart) {
                            onRangeChange(t.coerceIn(0L, currentEnd - minClipMs), currentEnd)
                        } else {
                            onRangeChange(currentStart, t.coerceIn(currentStart + minClipMs, durationMs))
                        }
                    }
                }
            }
        },
    ) {
        if (peaks.isEmpty() || durationMs <= 0) return@Canvas
        val bars = minOf(peaks.size, (size.width / 3.dp.toPx()).toInt()).coerceAtLeast(1)
        val barW = size.width / bars
        for (b in 0 until bars) {
            val from = b * peaks.size / bars
            val to = maxOf(from + 1, (b + 1) * peaks.size / bars)
            var v = 0f
            for (i in from until to) v = maxOf(v, peaks[i])
            val x = b * barW
            val t = (x + barW / 2) / size.width * durationMs
            val h = maxOf(2.dp.toPx(), v * size.height)
            drawRect(
                color = if (t >= start && t <= end) inside else outside,
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(barW * 0.7f, h),
            )
        }
        // The edges, so the user can see what to drag.
        val startX = start.toFloat() / durationMs * size.width
        val endX = end.toFloat() / durationMs * size.width
        drawLine(inside, Offset(startX, 0f), Offset(startX, size.height), strokeWidth = 3.dp.toPx())
        drawLine(inside, Offset(endX, 0f), Offset(endX, size.height), strokeWidth = 3.dp.toPx())
        if (playhead >= 0) {
            val x = playhead / durationMs * size.width
            drawLine(cursor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}
