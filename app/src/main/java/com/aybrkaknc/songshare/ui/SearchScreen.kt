package com.aybrkaknc.songshare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aybrkaknc.songshare.R
import com.aybrkaknc.songshare.Step
import com.aybrkaknc.songshare.audio.Audio
import com.aybrkaknc.songshare.ytdl.Candidate

// The search page has two states that share the search box at the bottom: before a search (the box alone)
// and from the moment a search starts (a results area above the box). The panel wraps this content,
// so a few results make a short panel and many results fill it and scroll.
@Composable
fun ColumnScope.SearchScreen(
    step: Step.Search,
    onSearch: (String) -> Unit,
    onPick: (Candidate) -> Unit,
    onClear: () -> Unit,
) {
    var query by rememberSaveable(step.query) { mutableStateOf(step.query) }
    val idle = !step.loading && step.candidates.isEmpty() && step.error == null
    if (!idle) ResultsArea(step, onPick)

    // The keyboard space sits outside the box, so it moves the box up without resizing anything else.
    Box(Modifier.fillMaxWidth().imePadding()) {
        SearchBox(
            query = query,
            // Emptying the field leaves the search and goes back to the home page.
            onQueryChange = {
                query = it
                if (it.isBlank()) onClear()
            },
            searchEnabled = query.isNotBlank() && !step.loading,
            onSearch = onSearch,
        )
    }
}

// Spinner while searching, a message when nothing came back, otherwise the scrolling list of results.
@Composable
private fun ColumnScope.ResultsArea(step: Step.Search, onPick: (Candidate) -> Unit) {
    when {
        step.loading -> Column(
            Modifier.fillMaxWidth().padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodyMedium)
        }
        step.candidates.isEmpty() -> Text(
            stringResource(step.error ?: R.string.error_no_results),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp),
        )
        else -> LazyColumn(
            Modifier.weight(1f, fill = false).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            // No stretch at the ends: a leftover drag goes to the panel instead.
            overscrollEffect = null,
        ) {
            items(step.candidates, key = { it.url }) { c ->
                ResultCard(c, Modifier.animateItem()) { onPick(c) }
            }
        }
    }
}

@Composable
private fun SearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    searchEnabled: Boolean,
    onSearch: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.search_query)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onSearch(query) },
            enabled = searchEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.search)) }
    }
}

// Picture on the left with the duration as a badge on it; the title (two lines at most) and the channel on the right.
@Composable
private fun ResultCard(candidate: Candidate, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(8.dp))) {
                Thumbnail(candidate.thumb, Modifier.fillMaxSize())
                if (candidate.durationSec > 0) {
                    Text(
                        Audio.formatMs(candidate.durationSec * 1000L).substringBefore('.'),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    candidate.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    candidate.channel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
