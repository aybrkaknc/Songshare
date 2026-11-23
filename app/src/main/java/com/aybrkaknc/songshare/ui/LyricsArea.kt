package com.aybrkaknc.songshare.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.lyrics.LyricsRepository
import com.aybrkaknc.songshare.lyrics.LyricsVersion
import com.aybrkaknc.songshare.lyrics.SongLyrics
import com.aybrkaknc.songshare.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private const val LOOKUP_DELAY_MS = 600L
private const val MAX_OFFSET_MS = 60_000L
private const val NUDGE_MS = 500L
// How much the active lyric line grows.
private const val ACTIVE_SCALE = 1.15f
// A lyrics version whose length differs from the song by more than this is flagged.
private const val DURATION_WARN_MS = 10_000L

private fun lengthDiffers(version: LyricsVersion, songMs: Long) =
    version.durationSec > 0 && abs(version.durationSec * 1000 - songMs) > DURATION_WARN_MS
// The list never gets shorter than this, even on small screens.
private val MIN_AREA_HEIGHT = 160.dp

// Lyrics under the waveform, kept in step with the clip.
// Playing scrolls the list to the line being sung. A tap moves the clip start to that line; while playing, playback restarts there.
// A long press shows checkboxes: the checked lines go on the card and the clip covers them.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricsArea(
    artist: String,
    title: String,
    durationMs: Long,
    playheadMs: Long?,
    startMs: Long,
    onRange: (startMs: Long, endMs: Long) -> Unit,
    onStart: (Long) -> Unit,
    // Checked lines with their time in the song (offset applied); the time is null for untimed lyrics.
    onSelectedText: (List<Pair<String, Long?>>) -> Unit,
    // Checked lines when the area opens again (e.g. back from the share page), and the reporting of changes.
    initialSelected: Set<Int> = emptySet(),
    onSelectionChange: (Set<Int>) -> Unit = {},
    // Give it a bounded height (e.g. weight); the list takes what is left after the sync controls.
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var song by remember { mutableStateOf<SongLyrics?>(null) }
    var loading by remember { mutableStateOf(true) }

    // Edits to the name fields settle before a new lookup starts.
    LaunchedEffect(artist, title) {
        loading = true
        delay(LOOKUP_DELAY_MS)
        song = LyricsRepository.load(context, artist, title, durationMs)
        loading = false
    }

    fun persist(s: SongLyrics) {
        song = s
        scope.launch(Dispatchers.IO) { LyricsRepository.save(context, s) }
    }

    val lines = remember(song) { song?.lines.orEmpty() }
    // Timing correction, kept with the song (its saved lyrics record), so it is there again when the song is opened later.
    var offset by remember(song?.key) { mutableStateOf(song?.offsetMs ?: 0L) }
    fun saveOffset(value: Long) {
        offset = value.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        song?.let { persist(it.copy(offsetMs = offset)) }
    }
    val times = remember(lines, offset) { lines.map { line -> line.timeMs?.let { (it + offset).coerceIn(0L, durationMs) } } }
    val timed = song?.manual == null && times.any { it != null }

    var selected by remember(lines) { mutableStateOf(initialSelected) }
    // Selection mode starts with a long press; in it a tap picks or drops a line. It ends when no line is picked.
    var selecting by remember(lines) { mutableStateOf(false) }
    LaunchedEffect(selected) { onSelectionChange(selected) }
    // The clip runs from the first checked line to the line after the last one.
    LaunchedEffect(selected, lines, times) {
        val picked = selected.sorted()
        onSelectedText(picked.map { lines[it].text to if (timed) times[it] else null })
        if (timed && picked.isNotEmpty()) {
            onRange(times[picked.first()] ?: 0L, times.getOrNull(picked.last() + 1) ?: durationMs)
        }
    }

    val listState = rememberLazyListState()

    fun indexAt(ms: Long): Int = times.indexOfLast { it != null && it <= ms }

    // Scrolls so the line sits in the middle of the area; offset 0 would put it right below the top padding.
    suspend fun bringToMiddle(index: Int) {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: info.visibleItemsInfo.firstOrNull()?.size ?: 0
        listState.animateScrollToItem(index, info.beforeContentPadding - (info.viewportSize.height - item) / 2)
    }

    // Playing: the sung line is highlighted and kept in the middle.
    val current = if (timed && playheadMs != null) indexAt(playheadMs) else -1
    // The highlighted line is the playhead while playing, and the clip start while stopped.
    val marked = if (playheadMs != null) current else if (timed) indexAt(startMs) else -1
    LaunchedEffect(current) { if (current >= 0) bringToMiddle(current) }

    // A new clip start (from the waveform or the time fields) brings its line into view, only when it is off screen.
    LaunchedEffect(startMs, timed) {
        if (!timed || playheadMs != null || listState.isScrollInProgress) return@LaunchedEffect
        val index = indexAt(startMs)
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) bringToMiddle(index)
    }

    // In sync mode a double tap on a line moves that line to the playing position (or the clip start) by changing the offset,
    // then flashes it (the line and, through onAligned, the edge) so the change is easy to see.
    var syncMode by remember { mutableStateOf(false) }
    val lineFlash = remember { Animatable(0f) }
    var flashIndex by remember { mutableStateOf(-1) }
    // Until the first successful alignment ever, the first line keeps showing a tap ring while sync is open.
    val prefs = remember { Prefs(context) }
    var hintDone by remember { mutableStateOf(prefs.syncHintDone) }
    val ring = remember { Animatable(0f) }
    LaunchedEffect(syncMode, hintDone) {
        if (!syncMode || hintDone) return@LaunchedEffect
        while (true) {
            ring.snapTo(0f)
            ring.animateTo(1f, tween(700))
            delay(400)
        }
    }
    suspend fun alignLine(index: Int) {
        val rawTime = lines.getOrNull(index)?.timeMs
        if (rawTime == null || song?.manual != null) {
            Toast.makeText(context, R.string.sync_no_timed, Toast.LENGTH_SHORT).show()
            return
        }
        // While the song plays, the line is synced to the moment it is playing now; stopped, to the clip start.
        saveOffset((playheadMs ?: startMs) - rawTime)
        if (!hintDone) {
            prefs.syncHintDone = true
            hintDone = true
        }
        Toast.makeText(context, R.string.sync_aligned, Toast.LENGTH_SHORT).show()
        flashIndex = index
        bringToMiddle(index)
        repeat(3) {
            lineFlash.animateTo(1f, tween(140))
            lineFlash.animateTo(0f, tween(140))
        }
    }

    val firstTimed = lines.indexOfFirst { it.timeMs != null }
    val primaryColor = MaterialTheme.colorScheme.primary

    fun toggle(i: Int) {
        selected = if (i in selected) selected - i else selected + i
        if (selected.isEmpty() && selecting) {
            // Leaving selection mode puts the clip back to the whole song, from the start.
            selecting = false
            onRange(0L, durationMs)
        }
    }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = MIN_AREA_HEIGHT)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            when {
                loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.searching_lyrics), style = MaterialTheme.typography.bodyMedium)
                }
                lines.isEmpty() -> ManualLyrics { text ->
                    val base = song ?: SongLyrics(LyricsRepository.keyFor(artist, title), emptyList(), null, 0L, null)
                    persist(base.copy(manual = text))
                }
                else -> LazyColumn(
                    state = listState,
                    // The bottom leaves room for the versions button, so the last line can scroll clear of it.
                    contentPadding = PaddingValues(top = 25.dp, bottom = 48.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(lines.size) { i ->
                        val isSelected = i in selected
                        val now = i == marked
                        val flashAlpha = if (i == flashIndex) lineFlash.value else 0f
                        // The line being sung (or picked) zooms in, the others go back to normal size.
                        val scale by animateFloatAsState(if (now) ACTIVE_SCALE else 1f, tween(250), label = "lineScale")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                // The layout width leaves room for the zoom, so the active line fills the width.
                                .fillMaxWidth(1f / ACTIVE_SCALE)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                }
                                .padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f * flashAlpha))
                                .then(
                                    if (syncMode && !hintDone && i == firstTimed) {
                                        Modifier.drawBehind {
                                            val p = ring.value
                                            if (p > 0f) {
                                                val grow = p * 8.dp.toPx()
                                                drawRoundRect(
                                                    color = primaryColor.copy(alpha = 1f - p),
                                                    topLeft = Offset(-grow, -grow),
                                                    size = Size(size.width + 2 * grow, size.height + 2 * grow),
                                                    cornerRadius = CornerRadius(8.dp.toPx() + grow),
                                                    style = Stroke(width = 2.dp.toPx()),
                                                )
                                            }
                                        }
                                    } else {
                                        Modifier
                                    },
                                )
                                .combinedClickable(
                                    // Outside selection mode a tap moves the clip start to the line; a long press starts selection.
                                    onClick = { if (selecting) toggle(i) else times[i]?.let(onStart) },
                                    onDoubleClick = {
                                        if (syncMode && !selecting && times[i] != null) scope.launch { alignLine(i) }
                                    },
                                    onLongClick = {
                                        selecting = true
                                        toggle(i)
                                    },
                                )
                                // 14 dp at the end balances the arrow slot at the start, so long lines wrap before the edge.
                                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                        ) {
                            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                                if (i == marked) {
                                    Icon(
                                        painterResource(R.drawable.ic_pointer_right),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            // The box takes the free width; the text inside keeps its own width, so the highlight stays narrow.
                            Box(Modifier.weight(1f)) {
                                Text(
                                    lines[i].text,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (isSelected || now) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        isSelected -> Color.Black
                                        now -> MaterialTheme.colorScheme.onSurface
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            // The toggle sits on a rounded dark pill; the sync section opens under it with its own gradient, sliding in together.
            if (!loading && timed) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TextButton(
                        onClick = { syncMode = !syncMode },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = Color.Black.copy(alpha = 0.9f)),
                    ) {
                        Text(
                            stringResource(if (syncMode) R.string.sync_exit else R.string.sync_enter),
                            color = Color.White,
                        )
                    }
                    // Slides up from under the toggle; the strip grows with it, so the gradient moves along.
                    AnimatedVisibility(
                        visible = syncMode,
                        enter = slideInVertically(tween(300)) { it } + fadeIn(tween(300)) + expandVertically(tween(300)),
                        exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(300)) + shrinkVertically(tween(300)),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.6f to Color.Black.copy(alpha = 0.9f),
                                        1f to Color.Black.copy(alpha = 0.9f),
                                    ),
                                )
                                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(R.string.sync_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                            )
                        // Fine timing correction for lyrics that run early or late, kept with the song, on one line:
                        // the title on the left, then - / slider / +, and the value on the right. Tapping the value puts the correction back to 0.
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.sync_title),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                modifier = Modifier.widthIn(min = 52.dp),
                            )
                            TextButton(
                                onClick = { saveOffset(offset - NUDGE_MS) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.width(36.dp),
                            ) { Text("-", color = Color.White) }
                            Slider(
                                value = offset.toFloat(),
                                onValueChange = { offset = (it / 100f).roundToLong() * 100 },
                                // Dragging only updates the screen; the value is saved with the song when the finger lifts.
                                onValueChangeFinished = { saveOffset(offset) },
                                valueRange = -MAX_OFFSET_MS.toFloat()..MAX_OFFSET_MS.toFloat(),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { saveOffset(offset + NUDGE_MS) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.width(36.dp),
                            ) { Text("+", color = Color.White) }
                            Text(
                                stringResource(R.string.sync_seconds, String.format(Locale.ROOT, "%+.1f", offset / 1000.0)),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(56.dp).clickable { saveOffset(0L) }.padding(vertical = 8.dp),
                            )
                        }
                        }
                    }
                }
            }
            val s = song
            if (!loading && s != null && s.versions.size > 1) {
                VersionPicker(s, durationMs, Modifier.align(Alignment.TopEnd)) { id -> persist(s.copy(chosenId = id, manual = null)) }
            }
            // The chosen lyrics run for a clearly different length than the song, so their timing is likely off.
            val chosenVersion = song?.chosen
            if (!loading && chosenVersion != null && lengthDiffers(chosenVersion, durationMs)) {
                Text(
                    stringResource(R.string.version_duration_off),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 16.dp),
                )
            }
        }

    }
}

// Shown when no lyrics were found: the user can paste them. They have no timing, but lines can still go on the card.
@Composable
private fun ManualLyrics(onUse: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            stringResource(R.string.lyrics_not_found),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.paste_lyrics)) },
            minLines = 3,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        TextButton(onClick = { onUse(text) }, enabled = text.isNotBlank(), modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.use_lyrics))
        }
    }
}

// Other uploads of the same song; a different one can be picked when the timing or the words are off.
@Composable
private fun VersionPicker(song: SongLyrics, songMs: Long, modifier: Modifier, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_list), stringResource(R.string.lyrics_versions))
        }
    }
    // A small window in the middle: one row per version, the chosen one highlighted; a tap picks it and closes the window.
    if (open) {
        AppAlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.lyrics_versions)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(song.versions.size) { i ->
                        val v = song.versions[i]
                        val chosen = v.id == song.chosen?.id
                        val length = Audio.formatMs((v.durationSec * 1000).toLong()).substringBefore('.')
                        val kind = stringResource(if (v.isSynced) R.string.version_synced else R.string.version_plain)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable {
                                    open = false
                                    onPick(v.id)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(v.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                // Artist and album under the title; an empty album leaves its line out.
                                Text(
                                    listOf(v.artist, v.album).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    kind,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (v.isSynced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // A length far from the song's: the timing of this version is probably off.
                                if (lengthDiffers(v, songMs)) {
                                    Text(
                                        stringResource(R.string.version_duration_off),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Text(length, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}
