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
            item{InfoCard("Source") {
                val source=snapshot.optJSONObject("source")
                Text(source?.optString("title")?.takeIf{it.isNotBlank()}?:"No song selected",style=MaterialTheme.typography.titleMedium)
                Text(source?.optString("quality")?.takeIf{it.isNotBlank()}?:"Source format not reported")
                if(source!=null)Text(channels(source.optInt("channels")))
                InfoLine("Playback",snapshot.optString("status","Unknown"))
            }}
            item{InfoCard("DSP") {
                val pcm=snapshot.optJSONObject("processing")
                Text(if(pcm==null)"No decoded PCM reported yet" else
                    "${rate(pcm.optInt("sampleRateHz"))} · ${channels(pcm.optInt("channelCount"))}\n${encoding(pcm.optInt("inputEncoding"))} → ${encoding(pcm.optInt("outputEncoding"))}")
                InfoLine("EQ",if(snapshot.optBoolean("eqEnabled"))"On · ${snapshot.optString("preset")}" else "Off")
                InfoLine("ReplayGain",snapshot.optString("replayGain","OFF").lowercase().replaceFirstChar{it.uppercase()})
                snapshot.optString("appResampling").takeIf{it.isNotBlank()&&it!="null"}?.let{InfoLine("App resampling",it)}
                Text("EQ and ReplayGain use Double arithmetic. Float PCM has about 24 significant bits of precision.",style=MaterialTheme.typography.bodySmall)
            }}
            item{InfoCard("Output requested") {
                val outputReq=snapshot.optString("outputRequested").takeIf{it.isNotBlank()}
                    ?: (snapshot.optJSONObject("processing")?.let{"${rate(it.optInt("sampleRateHz"))} · stereo · float PCM"} ?: "stereo · float PCM")
                Text(outputReq,style=MaterialTheme.typography.titleMedium)
                InfoLine("Output mode",if(snapshot.optBoolean("preferAndroidUsb"))"Android USB preference enabled" else "Normal")
                Text("Upstream precision sink outputs float PCM.",style=MaterialTheme.typography.bodySmall)
            }}
            item{InfoCard("Android accepted") {
                val accepted=snapshot.optString("androidAccepted").takeIf{it.isNotBlank()}
                    ?: "Standard Android route"
                Text(accepted,style=MaterialTheme.typography.titleMedium)
                val strategy=snapshot.optString("routeStrategy").takeIf{it.isNotBlank()}
                    ?: snapshot.optJSONObject("client")?.optString("strategy")
                if(strategy!=null)InfoLine("Route strategy",strategy)
                val fallback=snapshot.optString("fallbackReason").takeIf{it.isNotBlank()&&it!="null"}
                    ?: snapshot.optJSONObject("client")?.optString("fallbackReason")?.takeIf{it.isNotBlank()&&it!="null"}
                if(fallback!=null)InfoLine("Fallback reason",fallback)
                val client=snapshot.optJSONObject("client")
                if(client!=null&&client.optInt("audioTrackSampleRateHz")>0){
                    InfoLine("AudioTrack client format","${rate(client.optInt("audioTrackSampleRateHz"))} · ${channels(client.optInt("channelCount"))} · ${encoding(client.optInt("audioTrackEncoding"))}")
                }
                Text("Client format is what Android AudioTrack accepted. Active mixer output or hardware rate is not directly measured.",style=MaterialTheme.typography.bodySmall)
            }}
            item{InfoCard("Routed device") {
                val routed=snapshot.optString("routedDevice").takeIf{it.isNotBlank()}
                    ?: snapshot.optJSONObject("client")?.optString("routedDevice")?.takeIf{it.isNotBlank()}
                    ?: "Waiting for playback"
                Text(routed,style=MaterialTheme.typography.titleMedium)
                Text("An accepted mixer configuration and an observed routed device are separate facts. A route may be unavailable while paused or stopped.",style=MaterialTheme.typography.bodySmall)
            }}
            item{InfoCard("What this confirms") {
                Text("Source metadata, app processing, and Android audio configuration. Processing and route values describe the current or last opened stream. Refresh after playback starts.")
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
