package com.localmusic.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

fun countLabel(count: Int, singular: String, plural: String = "${singular}s") =
    "$count ${if (count == 1) singular else plural}"

@Composable
fun MarqueeText(text: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null) {
    Text(text, modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE,
        initialDelayMillis = 1600, repeatDelayMillis = 1800, velocity = 30.dp),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        style = style, color = color, fontWeight = fontWeight)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerActions(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun SettingCheckbox(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = change)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, Modifier.weight(1f))
    }
}

@Composable
fun EmptyMusicState(title: String, description: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(PlayerGlyph.Music, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@Composable
fun PlayerMessage(message: String, dismiss: (() -> Unit)? = null) {
    var expanded by remember(message) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Column(Modifier.padding(12.dp)) {
            Text(message, if (expanded) Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()) else Modifier,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            PlayerActions {
                if (message.length > 140 || message.count { it == '\n' } > 2)
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show details") }
                if (dismiss != null) TextButton(onClick = dismiss) { Text("Dismiss") }
            }
        }
    }
}
