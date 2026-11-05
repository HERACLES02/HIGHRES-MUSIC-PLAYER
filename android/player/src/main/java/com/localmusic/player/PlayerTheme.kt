package com.localmusic.player

import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color

/** Supply every surface role so cards, sheets and menus share the same palette. */
fun playerColorScheme(dark: Boolean, amoled: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(0xFFC4DDA9), onPrimary = Color(0xFF263620),
    primaryContainer = Color(0xFF344331), onPrimaryContainer = Color(0xFFC4DDA9),
    secondary = Color(0xFFB5CCD2), onSecondary = Color(0xFF20363C),
    secondaryContainer = Color(0xFF344331), onSecondaryContainer = Color(0xFFF0F1E9),
    tertiary = Color(0xFFC6D0B6), onTertiary = Color(0xFF2B3522),
    tertiaryContainer = Color(0xFF3D4933), onTertiaryContainer = Color(0xFFE2ECD1),
    background = if (amoled) Color.Black else Color(0xFF141A17), onBackground = Color(0xFFE2E6DC),
    surface = if (amoled) Color.Black else Color(0xFF141A17), onSurface = Color(0xFFE2E6DC),
    onSurfaceVariant = Color(0xFFB8C2B6), surfaceVariant = Color(0xFF3E493D),
    surfaceDim = Color(0xFF111611), surfaceBright = Color(0xFF333C33),
    surfaceContainerLowest = if (amoled) Color.Black else Color(0xFF0D130F),
    surfaceContainerLow = Color(0xFF182019), surfaceContainer = Color(0xFF1E2721),
    surfaceContainerHigh = Color(0xFF28322B), surfaceContainerHighest = Color(0xFF323D34),
    outline = Color(0xFF8A9688), outlineVariant = Color(0xFF404B3F),
    inverseSurface = Color(0xFFE2E6DC), inverseOnSurface = Color(0xFF293127), inversePrimary = Color(0xFF426138),
    surfaceTint = Color(0xFFC4DDA9)
) else lightColorScheme(
    primary = Color(0xFF426138), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EAD4), onPrimaryContainer = Color(0xFF263620),
    secondary = Color(0xFF3B6068), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0EAD4), onSecondaryContainer = Color(0xFF242D24),
    tertiary = Color(0xFF59674C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E8D6), onTertiaryContainer = Color(0xFF202D17),
    background = Color(0xFFF9F9F3), onBackground = Color(0xFF1A211B),
    surface = Color(0xFFF9F9F3), onSurface = Color(0xFF1A211B),
    onSurfaceVariant = Color(0xFF586452), surfaceVariant = Color(0xFFE0E6D9),
    surfaceDim = Color(0xFFDADDD3), surfaceBright = Color(0xFFF9F9F3),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F4EB),
    surfaceContainer = Color(0xFFEEEFE6), surfaceContainerHigh = Color(0xFFE4E8DB),
    surfaceContainerHighest = Color(0xFFDDE3D4),
    outline = Color(0xFF747C70), outlineVariant = Color(0xFFC4CBBE),
    inverseSurface = Color(0xFF2F352D), inverseOnSurface = Color(0xFFF0F3E9), inversePrimary = Color(0xFFB2D29F),
    surfaceTint = Color(0xFF426138)
)
