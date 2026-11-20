package com.localmusic.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Matches the system starting window; readiness, rather than a timer, ends it. */
@Composable
fun PlayerLaunchScreen() {
    val view=LocalView.current
    BoxWithConstraints(Modifier.fillMaxSize().background(colorResource(R.color.player_launch_background)),
        contentAlignment=Alignment.Center) {
        Image(painterResource(R.drawable.ic_launch_logo),"Local Music logo",
            Modifier.size(minOf(288.dp,maxWidth,maxHeight)).layout { measurable,constraints ->
                val image=measurable.measure(constraints)
                layout(image.width,image.height) {
                    // The system preview centers in the whole window. Some Android
                    // versions inset Compose below the status bar; match that same center.
                    val origin=IntArray(2);val windowOrigin=IntArray(2)
                    view.getLocationInWindow(origin);view.rootView.getLocationInWindow(windowOrigin)
                    image.place((view.rootView.width-view.width)/2-origin[0]+windowOrigin[0],
                        (view.rootView.height-view.height)/2-origin[1]+windowOrigin[1])
                }
            })
    }
}
