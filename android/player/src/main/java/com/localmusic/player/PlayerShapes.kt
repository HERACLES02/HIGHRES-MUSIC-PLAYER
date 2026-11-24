package com.localmusic.player

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localmusic.core.ThemeCorners

/** A cut is half as deep as the rounded radius it replaces: a cut of the full radius reads much heavier than the curve. */
private fun cut(radius: Dp) = CutCornerShape(radius / 2)

/** Normal themes and Night Ride keep Material's default rounded shapes, exactly as before. */
fun playerShapes(corners: ThemeCorners): Shapes {
    if (corners == ThemeCorners.ROUNDED) return Shapes()
    return Shapes(extraSmall = cut(4.dp), small = cut(8.dp), medium = cut(12.dp), large = cut(16.dp), extraLarge = cut(28.dp))
}

private val cutCorners @Composable get() = LocalThemeStyle.current?.corners == ThemeCorners.CUT

/** For the few places that need a specific radius rather than one of the theme's shape sizes. */
@Composable internal fun cornerShape(radius: Dp): Shape = if (cutCorners) cut(radius) else RoundedCornerShape(radius)

/** Circles (the play button) become an octagon on cut-corner accents. */
@Composable internal fun roundShape(): Shape = if (cutCorners) CutCornerShape(percent = 30) else CircleShape

/** The seek-bar handle is square on cut-corner accents. */
@Composable internal fun handleShape(): Shape = if (cutCorners) RectangleShape else CircleShape
