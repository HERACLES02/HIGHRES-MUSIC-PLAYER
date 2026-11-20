@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import org.json.JSONObject
import java.math.BigDecimal

/** On-demand snapshots only; opening/refreshing never starts or reconfigures audio. */
@Composable
fun AudioInfoPanel(controller:MediaController?,currentId:String) {
    val context=LocalContext.current
    var refresh by remember(currentId){mutableIntStateOf(0)}
    var info by remember(currentId){mutableStateOf<JSONObject?>(null)}
    var failure by remember(currentId){mutableStateOf<String?>(null)}
    DisposableEffect(controller,currentId,refresh) {
        var attached=true
        info=null;failure=null
        val request=controller?.sendCustomCommand(SessionCommand(PlaybackService.AUDIO_INFO,Bundle.EMPTY),Bundle.EMPTY)
        if(request==null)failure="Connect to the player to view audio info."
        request?.addListener({
            if(attached)try {
                val result=request.get()
                check(result.resultCode==SessionResult.RESULT_SUCCESS)
                info=JSONObject(requireNotNull(result.extras.getString("audioInfo")))
            }catch(_:Exception){failure="Audio info is unavailable. Try Refresh."}
        },ContextCompat.getMainExecutor(context))
        onDispose{attached=false;request?.cancel(false)}
    }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            Text("Your audio path",style=MaterialTheme.typography.headlineSmall)
            Text("A snapshot of the selected song and the latest app / Android client reports.",style=MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick={refresh++},enabled=controller!=null){Text("Refresh")}
        }
        val snapshot=info
        if(snapshot==null)item{Text(failure?:"Reading audio info…")}
        else {
            item{InfoCard("Selected song") {
                val source=snapshot.optJSONObject("source")
                Text(source?.optString("title")?.takeIf{it.isNotBlank()}?:"No song selected",style=MaterialTheme.typography.titleMedium)
                Text(source?.optString("quality")?.takeIf{it.isNotBlank()}?:"Source format not reported")
                if(source!=null)Text(channels(source.optInt("channels")))
                InfoLine("Playback",snapshot.optString("status","Unknown"))
            }}
            item{InfoCard("Selected output mode") {
                Text(if(snapshot.optBoolean("preferAndroidUsb"))"Android USB preference · above 48 kHz" else "Normal")
                Text("This is your setting, not confirmation of the active mixer or DAC format.",style=MaterialTheme.typography.bodySmall)
            }}
            item{InfoCard("App processing · last reported") {
                val pcm=snapshot.optJSONObject("processing")
                Text(if(pcm==null)"No decoded PCM reported yet" else
                    "${encoding(pcm.optInt("inputEncoding"))} → ${encoding(pcm.optInt("outputEncoding"))}\n${rate(pcm.optInt("sampleRateHz"))} · ${channels(pcm.optInt("channelCount"))}")
                InfoLine("EQ",if(snapshot.optBoolean("eqEnabled"))"On · ${snapshot.optString("preset")}" else "Off")
                InfoLine("ReplayGain",snapshot.optString("replayGain","OFF").lowercase().replaceFirstChar{it.uppercase()})
                Text("EQ and ReplayGain use Double arithmetic. Float PCM has about 24 significant bits of precision.",style=MaterialTheme.typography.bodySmall)
            }}
            item{InfoCard("Android client · last opened") {
                val client=snapshot.optJSONObject("client")
                Text(if(client==null)"No AudioTrack client reported yet" else
                    "${rate(client.optInt("audioTrackSampleRateHz"))} · ${channels(client.optInt("channelCount"))} · ${encoding(client.optInt("audioTrackEncoding"))}")
                if(client!=null)InfoLine("Buffer strategy",client.optString("strategy","Not reported"))
                InfoLine("Android-reported routed device",client?.optJSONObject("routedDevice")?.optString("product")?.takeIf{it.isNotBlank()}?:"Not reported")
                Text("Client format can differ from the mixer's output. A route may be unavailable while paused or stopped.",style=MaterialTheme.typography.bodySmall)
            }}
            item{InfoCard("What this confirms") {
                Text("Source metadata, app processing and Android client reports. Processing and client values may describe the previous stream while switching tracks. Refresh after playback starts.")
                InfoLine("Active mixer / HAL format","Not measured here")
                InfoLine("Physical DAC format / bit-perfect output","Not measured")
                Text("Snapshot: ${snapshot.optString("capturedAt")}",style=MaterialTheme.typography.bodySmall)
            }}
        }
    }
}

@Composable
private fun InfoCard(title:String,content:@Composable ColumnScope.()->Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@Composable
private fun InfoLine(label:String,value:String) {
    Column {Text(label,style=MaterialTheme.typography.labelMedium);Text(value)}
}
private fun channels(value:Int)=when(value){1->"mono";2->"stereo";in 3..32->"$value channels";else->"channels unknown"}
private fun rate(value:Int)=if(value>0)BigDecimal(value).divide(BigDecimal(1000)).stripTrailingZeros().toPlainString()+" kHz" else "rate unknown"
private fun encoding(value:Int)=when(value) {
    C.ENCODING_PCM_8BIT->"8-bit PCM"
    C.ENCODING_PCM_16BIT,C.ENCODING_PCM_16BIT_BIG_ENDIAN->"16-bit PCM"
    C.ENCODING_PCM_24BIT,C.ENCODING_PCM_24BIT_BIG_ENDIAN->"24-bit PCM"
    C.ENCODING_PCM_32BIT,C.ENCODING_PCM_32BIT_BIG_ENDIAN->"32-bit integer PCM"
    C.ENCODING_PCM_FLOAT->"float PCM"
    else->"encoding unknown"
}
