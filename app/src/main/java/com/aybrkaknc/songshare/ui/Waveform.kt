package com.aybrkaknc.songshare.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.audio.Audio
import kotlin.math.abs
import kotlin.math.roundToInt

private const val WAVE_REVEAL_MS = 700
// The page margin on each side; the waveform's gesture exclusion reaches past it to the screen edges.
private val SIDE_PAD = 20.dp
// How long the waveform has to be held before the pick popup opens.
private const val PICK_PRESS_MS = 800L
// The colors of the waveform: a grey back, a dark plate behind the clip, blue bars inside it, grey bars outside,
// and yellow for the frame around the clip.
private val BACK = Color(0xFF3A3A3A)
private val PLATE = Color(0xFF101010)
private val INSIDE_BAR = Color(0xFF1E90FF)
private val OUTSIDE_BAR = Color(0xFF666666)
private val FRAME = Color(0xFFFFC21A)
private val GRIP = Color(0xFF8A6A00)
private val PLAY_LINE = Color(0xFF3DDBD9)

// The waveform with the play button on top of it. A long press opens a small popup that moves a clip edge there.
@Composable
internal fun Waveform(
    peaks: FloatArray,
    durationMs: Long,
    start: Long,
    end: Long,
    playhead: Float,
    minClipMs: Long,
    onRangeChange: (Long, Long) -> Unit,
    onSeek: (Long) -> Unit,
    playing: Boolean,
    canPlay: Boolean,
    onPlayToggle: () -> Unit,
    playheadVisible: Boolean,
) {
    // While an edge is dragged the play button fades, so the edge is easy to see and move.
    var dragging by remember { mutableStateOf(false) }
    // A long press on the waveform: the time and the touch x of the pick popup, which moves a clip edge.
    var pick by remember { mutableStateOf<Pair<Long, Float>?>(null) }
    val controlsAlpha by animateFloatAsState(if (dragging) 0.25f else 1f, tween(150), label = "controlsAlpha")
    // The area is excluded from the system back gesture, so the handles near the screen edge can be grabbed.
    // The long press that opens the pick popup is held a bit longer than the system default, so it does not open by accident.
    val viewConfiguration = LocalViewConfiguration.current
    val slowPress = remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            override val longPressTimeoutMillis: Long = PICK_PRESS_MS
        }
    }
    Column(Modifier.fillMaxWidth()) {
    // The exclusion spans the whole screen width (the screen padding is outside this box), so the edge handles
    // are not taken by the system back gesture. The inner box keeps the same margins as the rest of the page.
    Box(
        Modifier
            .layout { measurable, constraints ->
                // Measured wider than the parent by the margin on both sides, then placed back by the left margin.
                val extra = SIDE_PAD.roundToPx() * 2
                val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-SIDE_PAD.roundToPx(), 0) }
            }
            .height(120.dp)
            .systemGestureExclusion(),
    ) {
    Box(Modifier.fillMaxSize().padding(horizontal = SIDE_PAD), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalViewConfiguration provides slowPress) {
            WaveformBars(
                peaks = peaks,
                durationMs = durationMs,
                start = start,
                end = end,
                playhead = playhead,
                minClipMs = minClipMs,
                onRangeChange = onRangeChange,
                onTap = {
                    pick = null
                    onSeek(it)
                },
                onScrub = onSeek,
                onLongPress = { ms, x -> pick = ms to x },
                playheadVisible = playheadVisible,
                onDragging = { dragging = it },
            )
        }
        // Small popup at the long press: the play icon moves the clip start there, the square moves the clip end.
        pick?.let { (ms, x) ->
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset((x - 40.dp.toPx()).roundToInt().coerceAtLeast(0), 0) }
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.9f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PickButton(R.drawable.ic_play, 24.dp, stringResource(R.string.trim_start)) {
                    onRangeChange(ms.coerceIn(0L, end - minClipMs), end)
                    pick = null
                }
                PickButton(R.drawable.ic_stop, 20.dp, stringResource(R.string.trim_end)) {
                    onRangeChange(start, ms.coerceIn(start + minClipMs, durationMs))
                    pick = null
                }
            }
        }
    }
    }
    // Play sits centred under the waveform, the times are right-aligned on the same row.
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(40.dp)) {
        Box(
            Modifier
                .align(Alignment.Center)
                .alpha(controlsAlpha)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black)
                .clickable(enabled = canPlay, role = Role.Button, onClick = onPlayToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play),
                contentDescription = stringResource(if (playing) R.string.stop else R.string.preview),
                modifier = Modifier.size(20.dp),
                tint = Color.White,
            )
        }
        Text(
            "${Audio.formatMs(playhead.toLong()).substringBefore('.')} / ${Audio.formatMs(durationMs).substringBefore('.')}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
    }
}

// A round pick button: scissors with a small play or stop badge at its bottom-right corner.
@Composable
private fun PickButton(badge: Int, badgeSize: Dp, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_scissors), contentDescription = null, modifier = Modifier.size(22.dp), tint = Color.White)
        Icon(
            painterResource(badge),
            contentDescription = label,
            modifier = Modifier.align(Alignment.BottomEnd).size(badgeSize),
            tint = FRAME,
        )
    }
}

// The bars sit between two yellow handles that join into a frame around the clip. Inside the clip the bars are blue
// on a dark plate; outside they are grey on a grey back. The waveform is inset by the handle width, so the handles
// stay inside the canvas. Dragging moves the nearer handle; a tap moves the play position.
@Composable
private fun WaveformBars(
    peaks: FloatArray,
    durationMs: Long,
    start: Long,
    end: Long,
    playhead: Float,
    minClipMs: Long,
    onRangeChange: (Long, Long) -> Unit,
    onTap: (Long) -> Unit,
    // A drag away from the edges moves the play position.
    onScrub: (Long) -> Unit,
    // Long press gives the time and the touch x; the pick popup opens there.
    onLongPress: (Long, Float) -> Unit,
    playheadVisible: Boolean,
    // True while an edge is being dragged, so the play button on top can fade out of the way.
    onDragging: (Boolean) -> Unit,
) {
    val currentStart by rememberUpdatedState(start)
    val currentEnd by rememberUpdatedState(end)
    // The bars grow from the left when the waveform arrives.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(peaks) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(WAVE_REVEAL_MS))
    }
    Canvas(
        Modifier.fillMaxSize().pointerInput(durationMs, minClipMs) {
            if (durationMs <= 0) return@pointerInput
            val inset = HANDLE_W.toPx()
            val areaW = size.width - 2 * inset
            fun xOf(ms: Long) = inset + ms.toFloat() / durationMs * areaW
            fun msOf(x: Float) = ((x - inset) / areaW * durationMs).toLong().coerceIn(0L, durationMs)
            awaitEachGesture {
                val down = awaitFirstDown()
                val movingStart = abs(down.position.x - xOf(currentStart)) <= abs(down.position.x - xOf(currentEnd))
                val grab = 24.dp.toPx()
                val pressedAt = System.currentTimeMillis()
                // Within the hold time a move drags the nearer edge; a release is a tap; a hold without a move opens the pick popup.
                val slop = withTimeoutOrNull(PICK_PRESS_MS) { awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } }
                if (slop == null && System.currentTimeMillis() - pressedAt >= PICK_PRESS_MS) {
                    onLongPress(msOf(down.position.x), down.position.x)
                    waitForUpOrCancellation()
                } else if (slop == null) {
                    // A tap close to a clip edge is a missed grab of the edge, so it does not move the play position.
                    if (abs(down.position.x - xOf(currentStart)) > grab && abs(down.position.x - xOf(currentEnd)) > grab) {
                        onTap(msOf(down.position.x))
                    }
                } else if (abs(down.position.x - xOf(currentStart)) <= grab || abs(down.position.x - xOf(currentEnd)) <= grab) {
                    // Touch on or near an edge: the nearer edge follows the finger.
                    onDragging(true)
                    try {
                        drag(slop.id) { change ->
                            change.consume()
                            val t = msOf(change.position.x)
                            if (movingStart) {
                                onRangeChange(t.coerceIn(0L, currentEnd - minClipMs), currentEnd)
                            } else {
                                onRangeChange(currentStart, t.coerceIn(currentStart + minClipMs, durationMs))
                            }
                        }
                    } finally {
                        onDragging(false)
                    }
                } else {
                    // Touch elsewhere on the waveform: the play position follows the finger.
                    drag(slop.id) { change ->
                        change.consume()
                        onScrub(msOf(change.position.x))
                    }
                }
            }
        },
    ) {
        if (peaks.isEmpty() || durationMs <= 0) return@Canvas
        val handleW = HANDLE_W.toPx()
        val areaW = size.width - 2 * handleW
        fun xOf(ms: Long) = handleW + ms.toFloat() / durationMs * areaW
        val startX = xOf(start)
        val endX = xOf(end)
        val frameT = 3.dp.toPx()
        // The grey back, then the dark plate behind the clip.
        drawRoundRect(BACK, Offset.Zero, size, CornerRadius(8.dp.toPx()))
        drawRect(PLATE, Offset(startX, 0f), Size(endX - startX, size.height))
        // The bars keep clear of the top and bottom frame lines.
        val pad = 12.dp.toPx()
        val bars = minOf(peaks.size, (areaW / 4.dp.toPx()).toInt()).coerceAtLeast(1)
        val barW = areaW / bars
        for (b in 0 until bars) {
            val from = b * peaks.size / bars
            val to = maxOf(from + 1, (b + 1) * peaks.size / bars)
            var v = 0f
            for (i in from until to) v = maxOf(v, peaks[i])
            val x = handleW + b * barW
            val t = ((x + barW / 2 - handleW) / areaW * durationMs).toLong()
            // Each bar starts a little after the previous one, so the wave seems to draw itself.
            val grow = ((reveal.value - b.toFloat() / bars * 0.6f) / 0.4f).coerceIn(0f, 1f)
            val h = maxOf(4.dp.toPx(), v * (size.height - 2 * pad)) * grow
            val w = barW * 0.62f
            // Centered on the middle line.
            val color = if (t < start || t > end) OUTSIDE_BAR else INSIDE_BAR
            drawRoundRect(color, Offset(x, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2, w / 2))
        }
        // The frame: a line along the top and the bottom of the clip, and the two handles with rounded outer corners.
        drawRect(FRAME, Offset(startX, 0f), Size(endX - startX, frameT))
        drawRect(FRAME, Offset(startX, size.height - frameT), Size(endX - startX, frameT))
        val corner = CornerRadius(6.dp.toPx())
        // Left handle: just left of the clip start; the inner (right) half is drawn square.
        drawRoundRect(FRAME, Offset(startX - handleW, 0f), Size(handleW, size.height), corner)
        drawRect(FRAME, Offset(startX - handleW / 2, 0f), Size(handleW / 2, size.height))
        // Right handle: just right of the clip end; the inner (left) half is drawn square.
        drawRoundRect(FRAME, Offset(endX, 0f), Size(handleW, size.height), corner)
        drawRect(FRAME, Offset(endX, 0f), Size(handleW / 2, size.height))
        // A small grip mark on each handle.
        val gripW = 2.dp.toPx()
        val gripH = 14.dp.toPx()
        for (cx in listOf(startX - handleW / 2, endX + handleW / 2)) {
            drawRoundRect(GRIP, Offset(cx - gripW / 2, (size.height - gripH) / 2), Size(gripW, gripH), CornerRadius(gripW / 2))
        }
        // The play position, only as tall as the bars area; shown after a tap or once playback has started.
        if (playheadVisible) {
            val playW = 2.dp.toPx()
            val playX = xOf(playhead.toLong()).coerceIn(handleW, size.width - handleW)
            drawLine(PLAY_LINE, Offset(playX, frameT), Offset(playX, size.height - frameT), strokeWidth = playW)
        }
    }
}

// The handle width; the waveform is inset by it on both sides.
private val HANDLE_W = 10.dp
