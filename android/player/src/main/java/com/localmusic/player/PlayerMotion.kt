package com.localmusic.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Scrollable content yields downward pulls at its top; the toolbar is a direct drag target. */
@Composable
fun PlayerMotionFrame(enabled:Boolean,onDismiss:()->Unit,modifier:Modifier=Modifier,content:@Composable (Modifier)->Unit) {
    val scope=rememberCoroutineScope()
    var offset by remember{mutableFloatStateOf(0f)}
    var settling by remember{mutableStateOf(false)}
    var epoch by remember{mutableIntStateOf(0)}
    val dismiss by rememberUpdatedState(onDismiss)
    val active by rememberUpdatedState(enabled)
    BoxWithConstraints(modifier) {
        val height=with(LocalDensity.current){maxHeight.toPx()}
        val fling=with(LocalDensity.current){900.dp.toPx()}
        suspend fun settle(velocity:Float) {
            if(settling || offset==0f)return
            settling=true
            val started=epoch
            try {
                val close=offset>height*.18f || velocity>fling
                animate(offset,if(close)height else 0f,initialVelocity=velocity,
                    animationSpec=spring(dampingRatio=.9f,stiffness=500f)){value,_->if(started==epoch)offset=value}
                if(close && active && started==epoch)dismiss()
                // AnimatedVisibility can retain this child after the callback.
                // Leave a dismissed frame below the viewport until it is disposed.
                if(!close && started==epoch)offset=0f
            } finally {settling=false}
        }
        val latestSettle by rememberUpdatedState<suspend (Float)->Unit>({settle(it)})
        val connection=remember(height) {
            object:NestedScrollConnection {
                override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset {
                    if(!active || settling || offset<=0f || available.y>=0f)return Offset.Zero
                    val used=available.y.coerceAtLeast(-offset);offset+=used
                    return Offset(0f,used)
                }
                override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource):Offset {
                    if(!active || settling || source!=NestedScrollSource.UserInput || available.y<=0f)return Offset.Zero
                    offset=(offset+available.y).coerceAtMost(height)
                    return Offset(0f,available.y)
                }
                override suspend fun onPreFling(available:Velocity):Velocity {
                    if(!active || offset<=0f)return Velocity.Zero
                    latestSettle(available.y);return available
                }
            }
        }
        LaunchedEffect(enabled,height){epoch++;offset=0f}
        val handle=if(enabled)Modifier.draggable(rememberDraggableState { delta ->
            if(!settling)offset=(offset+delta).coerceIn(0f,height)
        },orientation=Orientation.Vertical,onDragStopped={velocity->scope.launch{latestSettle(velocity)}})else Modifier
        // Direct drags cover a body that fits without scrolling. Nested scroll covers overflow.
        Box(Modifier.fillMaxSize().nestedScroll(connection).then(handle).graphicsLayer{translationY=if(enabled)offset else 0f}) {
            content(Modifier)
        }
    }
}
