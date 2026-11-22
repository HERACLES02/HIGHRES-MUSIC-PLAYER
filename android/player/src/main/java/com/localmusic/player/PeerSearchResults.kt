package com.localmusic.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmusic.core.PeerAudioFormat
import com.localmusic.core.PeerSearchOptions
import com.localmusic.core.PeerSearchSort
import com.localmusic.core.UNKNOWN_PEER_QUALITY

internal const val PEER_SEARCH_PAGE_SIZE = 30

@Composable
internal fun PeerSearchResults(
    collected: List<FixtureTrack>,
    options: PeerSearchOptions,
    onOptions: (PeerSearchOptions) -> Unit,
    limit: Int,
    onLoadMore: () -> Unit,
    enabled: Boolean,
    inspect: (FixtureTrack) -> Unit,
) {
    var showFilters by remember { mutableStateOf(false) }
    val matches = remember(collected, options) { options.matching(collected) }
    val visible = remember(matches, limit) { matches.take(limit) }
    PlayerActions {
        TextButton(onClick = { showFilters = !showFilters }) {
            Text(if (showFilters) "Hide filters" else "Filters")
        }
        SearchMenu("Sort · ${options.sort.label()}",
            PeerSearchSort.entries.map { it.label() to { onOptions(options.copy(sort = it)) } })
    }
    if (showFilters) PeerFilters(collected, options, onOptions)
    Text(
        "${visible.size} of ${collected.size} collected results shown" +
            if (matches.size == collected.size) "" else " · ${matches.size} match filters",
        style = MaterialTheme.typography.bodySmall,
    )
    Column {
        visible.forEach { track ->
            Column(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Inspect folder") {
                        inspect(track)
                    }
                    .padding(vertical = 3.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(track.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TrackDetails(track)
                Text("${track.user} · ${track.folder.ifEmpty { "Shared folder" }}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (visible.size < matches.size) TextButton(onClick = onLoadMore) { Text("Load more") }
}

@Composable
private fun PeerFilters(
    collected: List<FixtureTrack>,
    options: PeerSearchOptions,
    onOptions: (PeerSearchOptions) -> Unit,
) {
    val qualities = remember(collected, options.format, options.quality) {
        (collected.asSequence().filter { options.format == null || it.format == options.format }
            .mapNotNull { it.quality }.toSet() + listOfNotNull(options.quality))
            .filter { it != UNKNOWN_PEER_QUALITY }.sorted()
    }
    PlayerActions {
        SearchMenu("Format · ${options.format ?: "Any"}",
            listOf(null, *PeerAudioFormat.entries.toTypedArray()).map { format ->
                (format?.name ?: "Any format") to {
                    onOptions(options.copy(format = format, quality = null, includeUnknown = false))
                }
            })
        SearchMenu("Quality · ${options.quality ?: "Any"}",
            (listOf(null, UNKNOWN_PEER_QUALITY) + qualities).map { quality ->
                (quality ?: "Any quality") to { onOptions(options.copy(quality = quality, includeUnknown = false)) }
            })
    }
    if (options.quality != null && options.quality != UNKNOWN_PEER_QUALITY) {
        FilterChip(options.includeUnknown, { onOptions(options.copy(includeUnknown = !options.includeUnknown)) },
            label = { Text("Include Unknown") })
    }
    Text("Size range", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(options.minMb, { onOptions(options.copy(minMb = it)) }, Modifier.weight(1f),
            label = { Text("Min MB") }, singleLine = true, isError = options.invalidSizeRange,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(options.maxMb, { onOptions(options.copy(maxMb = it)) }, Modifier.weight(1f),
            label = { Text("Max MB") }, singleLine = true, isError = options.invalidSizeRange,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
    if (options.invalidSizeRange) Text("Enter a valid size range.", color = MaterialTheme.colorScheme.error)
    OutlinedTextField(options.hideName, { onOptions(options.copy(hideName = it)) }, Modifier.fillMaxWidth(),
        label = { Text("Hide names containing") }, singleLine = true)
    TextButton(onClick = { onOptions(PeerSearchOptions(sort = options.sort)) }) { Text("Clear filters") }
}

@Composable
private fun SearchMenu(label: String, entries: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { (name, select) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; select() })
            }
        }
    }
}

@Composable
internal fun TrackDetails(track: FixtureTrack) {
    Text("${track.format ?: "Unknown"} · ${track.sizeLabel} · ${track.quality ?: UNKNOWN_PEER_QUALITY}",
        style = MaterialTheme.typography.bodySmall)
}

private fun PeerSearchSort.label() = when (this) {
    PeerSearchSort.NAME -> "Name A–Z"
    PeerSearchSort.SIZE_ASC -> "Smallest first"
    PeerSearchSort.SIZE_DESC -> "Largest first"
}
