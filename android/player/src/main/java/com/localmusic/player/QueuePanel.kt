package com.localmusic.player

import android.os.Bundle
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmusic.core.*
import org.json.JSONObject

@Composable
fun QueuePanel(library:LibraryState,store:MusicStore?,current:Track?,playback:JSONObject,ready:Boolean,
    send:(String,Bundle)->Unit,openPlaying:()->Unit,clear:()->Unit,modifier:Modifier=Modifier) {
    val list=rememberLazyListState()
    fun entries(tracks:String,ids:String):List<Pair<String,Track?>> {
        val values=playback.optJSONArray(tracks);val keys=playback.optJSONArray(ids)
        return List(minOf(values?.length() ?: 0,keys?.length() ?: 0)){i->
            keys!!.getString(i) to runCatching{library.trackById[PlaybackAuthority.id(values!!.getString(i))]}.getOrNull()
        }
    }
    val manual=entries("manual","manualIds");val coming=entries("sequence","sequenceIds")
    val tracks=(manual+coming).toMap()
    fun move(id:String,index:Int)=send(PlaybackService.QUEUE_MOVE,Bundle().apply{putString("entry",id);putInt("index",index)})
    val manualOrder=rememberEntryReorder(manual.map{it.first},list,::move)
    val comingOrder=rememberEntryReorder(coming.map{it.first},list,::move)
    LazyColumn(modifier.fillMaxSize(),state=list,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item(key="current") {
            Text("Playing now",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(vertical=12.dp))
            if(current!=null)ListItem(modifier=Modifier.combinedClickable(onClick=openPlaying),
                leadingContent={CoverArt(store?.artwork(current.id),current.tags.title,Modifier.size(48.dp))},
                headlineContent={MarqueeText(current.tags.title)},
                supportingContent={Text(collectionDisplayLabel(playback.optString("collectionLabel","Library")),maxLines=2)})
        }
        item(key="manual-heading"){Text("Queued by you",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(vertical=12.dp))}
        if(manual.isEmpty())item(key="empty-manual"){Text("No manually queued songs. Use a song’s menu to add one.")}
        itemsIndexed(manualOrder.ids,key={_,id->id}){index,id->
            QueueEntryRow(tracks[id],store,ready,index,manualOrder.ids.size,
                modifier=Modifier.animateItem(placementSpec=if(manualOrder.dragging==id)null else androidx.compose.animation.core.spring()).then(manualOrder.row(id)),
                play={send(PlaybackService.QUEUE_PLAY,Bundle().apply{putString("entry",id)})},
                move={move(id,it)},remove={send(PlaybackService.QUEUE_REMOVE,Bundle().apply{putString("entry",id)})},
                handle={EntryDragHandle(id,index,manualOrder.ids.size,manualOrder,ready,::move)})
        }
        if(playback.optInt("manualCount")>0)item(key="clear"){TextButton(onClick=clear,enabled=ready){Text("Clear queue")}}
        if(playback.optInt("manualCount")>60)item(key="manual-limit"){Text("${playback.optInt("manualCount")} queued songs (first 60 shown)",style=MaterialTheme.typography.bodySmall)}
        item(key="coming-heading"){Text("Coming up",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(vertical=12.dp))}
        itemsIndexed(comingOrder.ids,key={_,id->id}){index,id->
            QueueEntryRow(tracks[id],store,ready,index,comingOrder.ids.size,
                modifier=Modifier.animateItem(placementSpec=if(comingOrder.dragging==id)null else androidx.compose.animation.core.spring()).then(comingOrder.row(id)),
                play={send(PlaybackService.QUEUE_PLAY,Bundle().apply{putString("entry",id)})},move={move(id,it)},
                handle={EntryDragHandle(id,index,comingOrder.ids.size,comingOrder,ready,::move)})
        }
        item(key="remaining"){Text(countLabel(playback.optInt("sequenceCount"),"song")+" remaining"+
            if(playback.optInt("sequenceCount")>60)" (first 60 shown)"else "",style=MaterialTheme.typography.bodySmall)}
    }
}

@Composable
private fun QueueEntryRow(track:Track?,store:MusicStore?,ready:Boolean,index:Int,total:Int,modifier:Modifier,
    play:()->Unit,move:(Int)->Unit,remove:(()->Unit)?=null,handle:@Composable ()->Unit) {
    var menu by remember{mutableStateOf(false)}
    ListItem(modifier=modifier.combinedClickable(enabled=ready,onClick={if(track?.availability==Availability.AVAILABLE)play()},
        onLongClickLabel="Queue options",onLongClick={menu=true}),
        leadingContent={CoverArt(track?.let{store?.artwork(it.id)},track?.tags?.title ?: "Unavailable song",Modifier.size(44.dp))},
        headlineContent={MarqueeText(track?.tags?.title ?: "Unavailable song")},
        supportingContent={Text(if(track==null)"Unavailable" else track.tags.artists.joinToString(", ")+
            if(track.availability!=Availability.AVAILABLE)" · Unavailable" else "",maxLines=1,overflow=TextOverflow.Ellipsis)},
        trailingContent={Row {
            Box {
                IconButton(onClick={menu=true},enabled=ready){Icon(PlayerGlyph.More,"Options for queue entry ${index+1}")}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text("Play")},enabled=track?.availability==Availability.AVAILABLE,onClick={menu=false;play()})
                    DropdownMenuItem(text={Text("Move up")},enabled=index>0,onClick={menu=false;move(index-1)})
                    DropdownMenuItem(text={Text("Move down")},enabled=index<total-1,onClick={menu=false;move(index+1)})
                    if(remove!=null)DropdownMenuItem(text={Text("Remove entry")},onClick={menu=false;remove()})
                }
            }
            handle()
        }})
}
