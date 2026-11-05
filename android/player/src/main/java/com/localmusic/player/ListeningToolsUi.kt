@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.localmusic.player

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localmusic.core.*
import com.localmusic.data.*
import kotlinx.coroutines.*

/** Drafts and pending reviews survive Activity recreation; no Activity is retained. */
class ListeningToolsUi:ViewModel(){
    var message by mutableStateOf("")
    var fileBusy by mutableStateOf(false)
    var importPreset by mutableStateOf<EqPreset?>(null)
    var importNotice by mutableStateOf("")
    var pendingExport:String?=null
    var lookupBusy by mutableStateOf(false)
    var lookupError by mutableStateOf("")
    var artists by mutableStateOf(emptyList<ArtistCandidate>())
    var releases by mutableStateOf(emptyList<ReleaseCandidate>())
    var artistReview by mutableStateOf<ArtistReview?>(null)
    var coverReview by mutableStateOf<CoverReview?>(null)
    var artistTarget by mutableStateOf<String?>(null)
    var coverTargets by mutableStateOf<List<Track>?>(null)
    private var lookupJob:Job?=null
    private var lookupGeneration=0L
    fun resetLookup(){lookupGeneration++;lookupJob?.cancel();lookupBusy=false;lookupError="";artists=emptyList();releases=emptyList();artistReview=null;coverReview=null}
    fun lookup(action:suspend()->Unit){
        val token=++lookupGeneration
        lookupJob?.cancel();lookupJob=viewModelScope.launch{lookupBusy=true;lookupError="";try{action()}catch(e:CancellationException){throw e}catch(e:Exception){if(token==lookupGeneration)lookupError=e.message?:"Lookup failed. Saved data is unchanged."}finally{if(token==lookupGeneration)lookupBusy=false}}
    }
    fun readPreset(context:Context,uri:Uri){
        if(fileBusy)return
        val app=context.applicationContext;fileBusy=true;importPreset=null;importNotice=""
        viewModelScope.launch{try{val imported=withContext(Dispatchers.IO){val bytes=requireNotNull(app.contentResolver.openInputStream(uri)).use{readLimited(it,AudioCodec.MAX_BYTES)}
            val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();AudioCodec.importPreset(text)}
            importNotice=imported.notice;importPreset=imported.preset
            message="Preset validated. Review before applying."
        }catch(e:CancellationException){throw e}catch(e:Exception){message="Import failed: ${e.message}. Current EQ is unchanged."}finally{fileBusy=false}}
    }
    fun savePreset(context:Context,uri:Uri?){
        val text=pendingExport;pendingExport=null;if(uri==null||text==null)return
        val app=context.applicationContext;fileBusy=true
        viewModelScope.launch{try{withContext(Dispatchers.IO){requireNotNull(app.contentResolver.openOutputStream(uri,"wt")).use{it.write(text.toByteArray(Charsets.UTF_8))}
            val saved=requireNotNull(app.contentResolver.openInputStream(uri)).use{readLimited(it,AudioCodec.MAX_BYTES).toString(Charsets.UTF_8)}
            check(AudioCodec.preset(saved)==AudioCodec.preset(text))};message="EQ JSON saved and verified."
        }catch(e:CancellationException){throw e}catch(e:Exception){message="Export failed: ${e.message}"}finally{fileBusy=false}}
    }
}

private fun audioArgs(value:AudioSettings)=Bundle().apply{putString("settings",AudioCodec.settings(value))}

@Composable
fun AudioControls(settings:AudioSettings,ready:Boolean,store:MusicStore?,track:Track?,send:(String,Bundle)->Unit,openEq:()->Unit){
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Text("Find your sound.",style=MaterialTheme.typography.headlineMedium);Text("Normal playback",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{ElevatedCard(onClick=openEq){Column(Modifier.fillMaxWidth().padding(18.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Equalizer",style=MaterialTheme.typography.titleLarge);Text(settings.preset.name)}
                Switch(settings.eqEnabled,{send(PlaybackService.AUDIO_SETTINGS,audioArgs(settings.copy(eqEnabled=it)))},enabled=ready, modifier=Modifier.semantics { contentDescription="Equalizer enabled" })}
            TextButton(onClick=openEq){Text("Edit EQ · import / export JSON")}
        }}}
        item{Text("Volume leveling",style=MaterialTheme.typography.titleLarge);Text("ReplayGain uses gain and peak tags stored in each file.")
            PlayerActions(){
                ReplayGainMode.entries.forEach{mode->FilterChip(settings.replayGain==mode,{send(PlaybackService.AUDIO_SETTINGS,audioArgs(settings.copy(replayGain=mode)))},enabled=ready,label={Text(mode.name.lowercase().replaceFirstChar{it.uppercase()})})}
            }
            Text("Album keeps relative track loudness. Missing gain tags leave volume unchanged.",style=MaterialTheme.typography.bodySmall)
            val detail=track?.let{store?.state?.value?.details?.get(it.id)}
            if(detail?.replayGainScanned==false){Text("Scan once to read ReplayGain tags from your existing library.");TextButton(onClick={send(PlaybackService.SCAN,Bundle.EMPTY)},enabled=ready){Text("Read library tags")}}
            else if(track!=null){val tags=ReplayGainInfo.parse(detail?.replayGainTags.orEmpty());Text("Current track: ${tags.trackGainDb?.let{"$it dB"}?:"no track gain"} · album: ${tags.albumGainDb?.let{"$it dB"}?:"no album gain"}",style=MaterialTheme.typography.bodySmall)}
        }
        item{HorizontalDivider();Text("Crossfade",style=MaterialTheme.typography.titleLarge);Text("Blend the end of one track into the next.")
            PlayerActions(){listOf(0,2,4,6).forEach{seconds->FilterChip(settings.crossfadeSeconds==seconds,{send(PlaybackService.AUDIO_SETTINGS,audioArgs(settings.copy(crossfadeSeconds=seconds)))},enabled=ready,label={Text(if(seconds==0)"Off" else "${seconds}s")})}}
            Text("Off keeps gapless transitions. Repeat one and end-of-track sleep do not crossfade. Short tracks use a shorter blend.",style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun EqualizerPanel(ui:ListeningToolsUi,settings:AudioSettings,ready:Boolean,send:(String,Bundle)->Unit){
    var deletingPreset by remember { mutableStateOf<EqPreset?>(null) }
    val context=LocalContext.current
    var draft by remember(settings.preset){mutableStateOf(settings.preset)}
    var naming by rememberSaveable{mutableStateOf(false)};var name by rememberSaveable{mutableStateOf("")}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)ui.readPreset(context,uri)}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->ui.savePreset(context,uri)}
    fun apply(value:EqPreset=draft){send(PlaybackService.AUDIO_SETTINGS,audioArgs(settings.copy(preset=value)))}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Your EQ, your preset.",style=MaterialTheme.typography.headlineSmall);Text(draft.name,color=MaterialTheme.colorScheme.primary)}
            Switch(settings.eqEnabled,{send(PlaybackService.AUDIO_SETTINGS,audioArgs(settings.copy(eqEnabled=it,preset=draft)))},enabled=ready, modifier=Modifier.semantics { contentDescription="Equalizer enabled" })}}
        item{val color=MaterialTheme.colorScheme.primary;val line=MaterialTheme.colorScheme.outlineVariant
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer){Column(Modifier.padding(16.dp)){
                Text("Band gains",style=MaterialTheme.typography.labelMedium)
                Canvas(Modifier.fillMaxWidth().height(72.dp).padding(top=12.dp)){drawLine(line,Offset(0f,size.height/2),Offset(size.width,size.height/2),1f)
                    for(i in 0..8)drawLine(color,Offset(size.width*i/9,size.height*(.5f-draft.gains[i].toFloat()/30)),Offset(size.width*(i+1)/9,size.height*(.5f-draft.gains[i+1].toFloat()/30)),3f)}
            }}
        }
        item{Text("Swipe bands sideways · gains from −12 to +12 dB",style=MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){EqPreset.FREQUENCIES.forEachIndexed{i,freq->
                Column(Modifier.width(52.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Text("%+.1f".format(draft.gains[i]),style=MaterialTheme.typography.labelSmall)
                    Box(Modifier.height(184.dp).width(48.dp),contentAlignment=Alignment.Center){
                        Slider(draft.gains[i].toFloat(),{v->draft=draft.copy(name="Custom",gains=draft.gains.mapIndexed{j,g->if(i==j)v.toDouble()else g})},
                            modifier=Modifier.requiredWidth(174.dp).height(48.dp).graphicsLayer{rotationZ= -90f}.semantics{contentDescription="$freq hertz gain"},
                            enabled=ready,valueRange= -12f..12f,steps=47,onValueChangeFinished={apply()})
                    }
                    Text(if(freq>=1000)"${freq/1000}k" else "$freq",style=MaterialTheme.typography.labelSmall)
                }
            }}
        }
        item{Text("Preamp · ${"%.1f".format(draft.preampDb)} dB",style=MaterialTheme.typography.titleMedium)
            Slider(draft.preampDb.toFloat(),{draft=draft.copy(name="Custom",preampDb=it.toDouble())},enabled=ready,valueRange= -24f..0f,steps=47,
                onValueChangeFinished={apply()},modifier=Modifier.semantics{contentDescription="Preamp decibels"})
            Text("Reduce preamp when boosting bands to avoid clipping.",style=MaterialTheme.typography.bodySmall)
        }
        item{PlayerActions(){
            FilledTonalButton(enabled=ready&&!ui.fileBusy,onClick={picker.launch(arrayOf("application/json","text/plain","application/octet-stream"))}){Text("Import JSON")}
            OutlinedButton(enabled=ready&&!ui.fileBusy,onClick={ui.pendingExport=AudioCodec.preset(draft);exporter.launch("local-music-eq.json")}){Text("Export JSON")}
        };Text("Import Local Music JSON or a single standard 10-band graphic export. Export saves Local Music JSON v1.",style=MaterialTheme.typography.bodySmall)
            if(ui.fileBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(ui.message.isNotBlank())Text(ui.message)
        }
        item{PlayerActions(){
            Button(enabled=ready,onClick={name=draft.name;naming=true}){Text("Save preset")}
            OutlinedButton(enabled=ready,onClick={draft=EqPreset();apply(draft)}){Text("Reset to flat")}
        };if(settings.presets.isNotEmpty())Text("Saved presets",Modifier.padding(top=16.dp),style=MaterialTheme.typography.titleLarge)}
        items(settings.presets,key={it.name}){preset->ListItem(headlineContent={Text(preset.name,maxLines=2,overflow=TextOverflow.Ellipsis)},modifier=Modifier.clickable(enabled=ready){draft=preset;apply(preset)},
            trailingContent={IconButton(enabled=ready,onClick={deletingPreset=preset}){Icon(PlayerGlyph.Close,"Delete preset ${preset.name}")}})}
    }
    deletingPreset?.let { preset -> AlertDialog(onDismissRequest={deletingPreset=null}, title={Text("Delete this preset?")},
        text={Text("Remove ${preset.name} from saved presets? The current sound settings stay as selected.")},
        confirmButton={TextButton(enabled=ready,onClick={send(PlaybackService.AUDIO_SETTINGS,audioArgs(settings.copy(presets=settings.presets.filter{it.name!=preset.name})));deletingPreset=null},
            colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("Delete")}},
        dismissButton={TextButton(onClick={deletingPreset=null}){Text("Cancel")}}) }
    if(naming)AlertDialog(onDismissRequest={naming=false},title={Text("Save EQ preset")},text={OutlinedTextField(name,{name=it.take(80)},label={Text("Preset name")},singleLine=true)},
        confirmButton={TextButton(enabled=name.isNotBlank()&&(settings.presets.size<100||settings.presets.any{it.name.equals(name.trim(),true)}),onClick={
            val saved=draft.copy(name=name.trim());val bank=settings.presets.filterNot{it.name.equals(saved.name,true)}+saved
            send(PlaybackService.AUDIO_SETTINGS,audioArgs(settings.copy(preset=saved,presets=bank)));naming=false
        }){Text("Save")}},dismissButton={TextButton(onClick={naming=false}){Text("Cancel")}})
    ui.importPreset?.let{incoming->AlertDialog(onDismissRequest={ui.importPreset=null},title={Text("Review EQ preset")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){
        Text(incoming.name,style=MaterialTheme.typography.titleLarge);Text(ui.importNotice);Text("10 bands\nPreamp: ${incoming.preampDb} dB")
        incoming.gains.forEachIndexed{i,g->Row(Modifier.fillMaxWidth().padding(vertical=3.dp)){Text("${EqPreset.FREQUENCIES[i]} Hz",Modifier.weight(1f));Text("%+.1f dB".format(g))}}
        Text("Only EQ changes. ReplayGain and crossfade stay as selected.")
        if(incoming.gains.any{it>0})Text("This preset boosts bands. Lower the preamp if you hear distortion.")
    }},confirmButton={TextButton(enabled=ready,onClick={val bank=settings.presets.filterNot{it.name.equals(incoming.name,true)}+incoming
        if(bank.size>100){ui.message="The saved preset bank is full. Delete a preset first.";return@TextButton}
        send(PlaybackService.AUDIO_SETTINGS,audioArgs(settings.copy(eqEnabled=true,preset=incoming,presets=bank)));ui.importPreset=null;ui.message=""
    }){Text("Save & use")}},dismissButton={TextButton(onClick={ui.importPreset=null;ui.message="Import cancelled. Current EQ unchanged."}){Text("Cancel")}})}
}

@Composable
fun WidgetSettingsPanel(value:WidgetSettings,ready:Boolean,track:Track?,art:String?,send:(String,Bundle)->Unit){
    val context=LocalContext.current;var compact by rememberSaveable{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    fun change(theme:String=value.theme,artwork:Boolean=value.artwork){send(PlaybackService.WIDGET_SETTINGS,Bundle().apply{putString("theme",theme);putBoolean("artwork",artwork)})}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Text("Music, a tap away.",style=MaterialTheme.typography.headlineMedium);Text("Choose a layout for your home screen.")}
        item{val background=when(value.theme){"Light"->Color(0xffe3e8dc);"Dark"->Color(0xff26352b);else->MaterialTheme.colorScheme.secondaryContainer}
            val foreground=when(value.theme){"Light"->Color(0xff242d24);"Dark"->Color(0xfff0f1e9);else->MaterialTheme.colorScheme.onSecondaryContainer}
            Surface(shape=MaterialTheme.shapes.extraLarge,color=background,contentColor=foreground){Column(Modifier.fillMaxWidth().padding(16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){CoverArt(if(value.artwork)art else null,track?.tags?.title?:"Local Music",Modifier.size(if(compact)48.dp else 72.dp))
                Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(track?.tags?.title?:"Local Music",maxLines=1,overflow=TextOverflow.Ellipsis);Text(track?.tags?.artists?.joinToString(", ")?:"Choose your music",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)}
                if(compact){Icon(PlayerGlyph.Play,"Play preview");Spacer(Modifier.width(16.dp));Icon(PlayerGlyph.Next,"Next preview")}}
            if(!compact)Row(Modifier.fillMaxWidth().padding(top=16.dp),horizontalArrangement=Arrangement.SpaceEvenly){Icon(PlayerGlyph.Previous,"Previous preview");Icon(PlayerGlyph.Play,"Play preview");Icon(PlayerGlyph.Next,"Next preview")}
        }}}
        item{Text("Layout",style=MaterialTheme.typography.titleLarge);PlayerActions(){FilterChip(!compact,{compact=false},label={Text("Artwork · 4 × 2")});FilterChip(compact,{compact=true},label={Text("Compact · 4 × 1")})}}
        item{Text("Appearance",style=MaterialTheme.typography.titleLarge);PlayerActions(){
            listOf("Match app","Light","Dark").forEach{theme->FilterChip(value.theme==theme,{change(theme=theme)},enabled=ready,label={Text(theme)})}}
            SettingSwitch("Show album artwork", value.artwork, ready) { change(artwork = it) }
        }
        item{Button(enabled=ready,onClick={val manager=AppWidgetManager.getInstance(context)
            if(manager.isRequestPinAppWidgetSupported){val provider=if(compact)CompactWidget::class.java else ArtworkWidget::class.java
                if(!manager.requestPinAppWidget(ComponentName(context,provider),null,null))message="Open your launcher's Widgets menu and choose Local Music."
                else message="Confirm placement in Android. If no dialog appears, use the launcher's Widgets menu."
            }else message="Long-press your home screen, choose Widgets, then Local Music."
        }){Text("Add widget")};if(message.isNotBlank())Text(message);Text("You can also add either layout from Android's widget picker. Controls use the same playing queue.",style=MaterialTheme.typography.bodySmall)}
    }
}
