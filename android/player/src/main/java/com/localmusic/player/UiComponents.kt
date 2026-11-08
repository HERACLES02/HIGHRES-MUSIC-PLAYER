package com.localmusic.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

fun countLabel(count: Int, singular: String, plural: String = "${singular}s") =
    "$count ${if (count == 1) singular else plural}"

/** Display only: stored folder keys and permission identities stay untouched. */
fun folderDisplayPath(value:String):String=value.replaceFirst(Regex("^(primary|[0-9a-fA-F]{4}-[0-9a-fA-F]{4}):"),"")
    .trim('/').ifBlank{"Selected folder"}

fun collectionDisplayLabel(value:String):String {
    val prefix=listOf("Folder · ","Folder: ").firstOrNull(value::startsWith)
    return if(prefix==null)value.replace("Listening collection","Library")else "Folder · ${folderDisplayPath(value.removePrefix(prefix))}"
}

@Composable
fun MarqueeText(text: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null, autoScroll:Boolean=false) {
    Text(text, modifier.fillMaxWidth().then(if(autoScroll)Modifier.basicMarquee(iterations = Int.MAX_VALUE,
        initialDelayMillis = 1600, repeatDelayMillis = 1800, velocity = 30.dp)else Modifier),
        maxLines = 1, softWrap = false, overflow = if(autoScroll)TextOverflow.Clip else TextOverflow.Ellipsis,
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
    var visible by rememberSaveable(message) { mutableStateOf(true) }
    LaunchedEffect(message) { delay(8000); visible=false }
    var expanded by remember(message) { mutableStateOf(false) }
    AnimatedVisibility(visible,enter=fadeIn(),exit=fadeOut()+shrinkVertically()) {
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
                TextButton(onClick = { visible=false;dismiss?.invoke() }) { Text("Dismiss") }
            }
        }
    }
    }
}

@Composable
fun MusicSearchField(value:String,change:(String)->Unit,hint:String,modifier:Modifier=Modifier) {
    OutlinedTextField(value,change,singleLine=true,shape=RoundedCornerShape(28.dp),
        textStyle=MaterialTheme.typography.bodyMedium,
        placeholder={Text(hint,maxLines=1,overflow=TextOverflow.Ellipsis)},
        trailingIcon={if(value.isNotEmpty())IconButton(onClick={change("")}){Icon(PlayerGlyph.Close,"Clear search")}},
        modifier=modifier.fillMaxWidth().semantics{contentDescription=hint})
}
