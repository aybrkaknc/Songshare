package com.aybrkaknc.songshare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.Song
import com.aybrkaknc.songshare.Step
import kotlinx.coroutines.delay

private val COVER_SIZE = 220.dp
private val DOT_SIZE = 14.dp

// The page while a song downloads: a large cover (a plain square with a note when there is none), the name,
// the percent and bar, and a line of steps with the name of the current one.
@Composable
fun DownloadScreen(step: Step.Downloading, song: Song?) {
    val picked = step.picked
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val cover = Modifier.size(COVER_SIZE).clip(RoundedCornerShape(20.dp))
        if (picked != null) {
            Thumbnail(picked.thumb, cover)
        } else {
            Box(cover.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_music_note),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            picked?.title ?: song?.label.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        // The conversion step only joins the line when the file really has to be converted.
        val steps = if (step.converting) {
            listOf(R.string.step_find, R.string.step_download, R.string.step_convert, R.string.step_ready)
        } else {
            listOf(R.string.step_find, R.string.step_download, R.string.step_ready)
        }
        val active = when {
            step.converting -> 2
            step.progress >= 0 -> 1
            else -> 0
        }
        // The line is the progress bar: empty while the song is being found, filled by the percent, full while converting.
        val fraction = when {
            step.converting -> 1f
            step.progress >= 0 -> step.progress / 100f
            else -> 0f
        }
        val percent = if (step.progress >= 0 && !step.converting) stringResource(R.string.progress_percent, step.progress.toInt()) else null
        StepLine(steps, active, fraction, percent)
    }
}

// The first name grows to the right from its dot, the last to the left, so none leaves the screen.
private fun labelAlign(i: Int, last: Int): Alignment.Horizontal = when (i) {
    0 -> Alignment.Start
    last -> Alignment.End
    else -> Alignment.CenterHorizontally
}

// One line through all the dots: it fills with the progress, the percent sits at the end of the filled part, and the
// finished and current dots are filled. Only the current step's name is written, under its dot.
@Composable
private fun StepLine(steps: List<Int>, active: Int, fraction: Float, percent: String?) {
    val done = MaterialTheme.colorScheme.primary
    val todo = MaterialTheme.colorScheme.outlineVariant
    // As wide as the cover above it, so the bar and its dots line up with the cover's edges.
    Column(Modifier.width(COVER_SIZE)) {
        // Over the right half the percent ends at the filled part, over the left half it starts there, so it never leaves the screen.
        Row(Modifier.fillMaxWidth().height(18.dp)) {
            val f = fraction.coerceIn(0.001f, 0.999f)
            val label: @Composable (Alignment) -> Unit = { align ->
                Box(Modifier.fillMaxSize(), contentAlignment = align) {
                    if (percent != null) Text(percent, style = MaterialTheme.typography.bodySmall, color = done)
                }
            }
            if (f > 0.5f) {
                Box(Modifier.weight(f)) { label(Alignment.CenterEnd) }
                Spacer(Modifier.weight(1f - f))
            } else {
                Spacer(Modifier.weight(f))
                Box(Modifier.weight(1f - f)) { label(Alignment.CenterStart) }
            }
        }
        Box(Modifier.fillMaxWidth().height(DOT_SIZE), contentAlignment = Alignment.CenterStart) {
            val track = RoundedCornerShape(2.dp)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(track).background(todo))
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(track).background(done))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                steps.indices.forEach { i ->
                    val dot = Modifier.size(DOT_SIZE).clip(CircleShape)
                    // The ring is filled with the page color, so the line does not show through an empty dot.
                    Box(
                        if (i <= active) dot.background(done)
                        else dot.background(MaterialTheme.colorScheme.surface).border(2.dp, todo, CircleShape),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            steps.indices.forEach { i ->
                if (i > 0) Spacer(Modifier.weight(1f))
                Box(Modifier.width(DOT_SIZE), contentAlignment = Alignment.TopCenter) {
                    if (i == active) {
                        // Dots that keep appearing and vanishing show the work goes on. The hidden ones stay as transparent
                        // text, so the name does not change width or move.
                        val shown by produceState(0) {
                            while (true) {
                                delay(400)
                                value = (value + 1) % 4
                            }
                        }
                        val name = stringResource(steps[i])
                        val label = buildAnnotatedString {
                            append(name)
                            append("." .repeat(shown))
                            withStyle(SpanStyle(color = Color.Transparent)) { append("." .repeat(3 - shown)) }
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = done,
                            softWrap = false,
                            modifier = Modifier.wrapContentWidth(labelAlign(i, steps.lastIndex), unbounded = true),
                        )
                    }
                }
            }
        }
    }
}
