package com.localmusic.player

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/** Local ordering while dragging; only the final drop writes to the service/database. */
@Stable
class EntryReorder(private val list:LazyListState,private val edge:Float) {
    var ids by mutableStateOf(emptyList<String>());private set
    var dragging by mutableStateOf<String?>(null);private set
    private var center by mutableFloatStateOf(0f)
    private var source=emptyList<String>()
    private var startingIndex=-1
    private var direction=0f
    fun sync(next:List<String>) {
        if(source==next)return
        source=next;ids=next;dragging=null
    }
    fun begin(id:String) {
        val item=list.layoutInfo.visibleItemsInfo.firstOrNull{it.key==id} ?: return
        startingIndex=ids.indexOf(id);if(startingIndex<0)return
        dragging=id;center=item.offset+item.size/2f;direction=0f
    }
    fun drag(delta:Float) {direction=delta;center+=delta;reorder()}
    private fun reorder() {
        val id=dragging ?: return
        val from=ids.indexOf(id)
        val target=list.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val index=ids.indexOf(item.key)
            index>=0 && item.key!=id &&
                ((index>from && center>item.offset+item.size/2f) || (index<from && center<item.offset+item.size/2f))
        } ?: return
        val to=ids.indexOf(target.key)
        ids=ids.toMutableList().apply{add(to,removeAt(from))}
    }
    suspend fun autoScroll() {
        while(dragging!=null) {
            val layout=list.layoutInfo
            val delta=when {
                direction<0f && center<layout.viewportStartOffset+edge -> -((layout.viewportStartOffset+edge-center)/5).coerceIn(0f,24f)
                direction>0f && center>layout.viewportEndOffset-edge -> ((center-layout.viewportEndOffset+edge)/5).coerceIn(0f,24f)
                else->0f
            }
            if(delta!=0f){list.scrollBy(delta);reorder()}
            delay(16)
        }
    }
    fun end(commit:(String,Int)->Unit) {
        val id=dragging ?: return
        val index=ids.indexOf(id)
        dragging=null
        if(index>=0 && index!=startingIndex)commit(id,index)
    }
    fun cancel(){dragging=null;ids=source}
    fun settle(){if(dragging==null)ids=source}
    fun row(id:String):Modifier=Modifier.zIndex(if(dragging==id)1f else 0f).graphicsLayer {
        translationY=if(dragging==id) {
            list.layoutInfo.visibleItemsInfo.firstOrNull{it.key==id}?.let{center-it.offset-it.size/2f} ?: 0f
        } else 0f
        shadowElevation=if(dragging==id)12.dp.toPx() else 0f
    }
}

@Composable
fun rememberEntryReorder(ids:List<String>,list:LazyListState,commit:(String,Int)->Unit):EntryReorder {
    val edge=with(LocalDensity.current){64.dp.toPx()}
    val state=remember(list,edge){EntryReorder(list,edge)}
    SideEffect{state.sync(ids)}
    LaunchedEffect(state.dragging){if(state.dragging!=null)state.autoScroll()}
    // Reconcile a rejected/obsolete drop after the service has had time to answer.
    LaunchedEffect(state.dragging,state.ids,ids){if(state.dragging==null && state.ids!=ids){delay(1500);state.settle()}}
    return state
}

@Composable
fun EntryDragHandle(id:String,index:Int,total:Int,state:EntryReorder,enabled:Boolean=true,commit:(String,Int)->Unit) {
    val latestCommit by rememberUpdatedState(commit)
    Icon(PlayerGlyph.Drag,"Drag to reorder",tint=MaterialTheme.colorScheme.onSurfaceVariant,
        modifier=Modifier.size(48.dp).semantics {
            customActions=listOfNotNull(
                if(enabled && index>0)CustomAccessibilityAction("Move up"){latestCommit(id,index-1);true}else null,
                if(enabled && index<total-1)CustomAccessibilityAction("Move down"){latestCommit(id,index+1);true}else null)
        }.pointerInput(state,id,enabled){if(enabled)detectDragGestures(
            onDragStart={state.begin(id)},onDragCancel={state.cancel()},onDragEnd={state.end(latestCommit)},
            onDrag={change,amount->change.consume();state.drag(amount.y)})}.padding(12.dp))
}
