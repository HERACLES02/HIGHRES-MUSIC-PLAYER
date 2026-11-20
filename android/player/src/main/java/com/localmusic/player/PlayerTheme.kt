package com.localmusic.player

import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import com.localmusic.core.ThemeAccent

private data class AccentPalette(val primary:Color,val onPrimary:Color,val container:Color,val onContainer:Color)
private fun palette(accent:ThemeAccent,dark:Boolean):AccentPalette {
    val colors=when(accent) {
        ThemeAccent.SAGE->if(dark)longArrayOf(0xFFB5D19C,0xFF203612,0xFF344F24,0xFFD1EDB7)
            else longArrayOf(0xFF426138,0xFFFFFFFF,0xFFD1EDB7,0xFF102004)
        ThemeAccent.OCEAN->if(dark)longArrayOf(0xFFA5C8FF,0xFF062F60,0xFF1C477A,0xFFD6E3FF)
            else longArrayOf(0xFF245FA6,0xFFFFFFFF,0xFFD6E3FF,0xFF0C2D53)
        ThemeAccent.VIOLET->if(dark)longArrayOf(0xFFD0BCFF,0xFF381E72,0xFF4F378B,0xFFEADDFF)
            else longArrayOf(0xFF6750A4,0xFFFFFFFF,0xFFEADDFF,0xFF21005D)
        ThemeAccent.ROSE->if(dark)longArrayOf(0xFFFFB1C8,0xFF5E1134,0xFF7B294A,0xFFFFD9E3)
            else longArrayOf(0xFF984061,0xFFFFFFFF,0xFFFFD9E3,0xFF3E001E)
        ThemeAccent.AMBER->if(dark)longArrayOf(0xFFEAC166,0xFF402D00,0xFF5C4300,0xFFFFE0A0)
            else longArrayOf(0xFF795900,0xFFFFFFFF,0xFFFFE0A0,0xFF271900)
    }
    return AccentPalette(Color(colors[0]),Color(colors[1]),Color(colors[2]),Color(colors[3]))
}
internal fun accentPreviewColor(accent:ThemeAccent):Color=palette(accent,false).primary

/** Neutral surfaces keep accents distinct; AMOLED retains true black base surfaces. */
fun playerColorScheme(dark:Boolean,amoled:Boolean,accent:ThemeAccent=ThemeAccent.SAGE):ColorScheme {
    val p=palette(accent,dark)
    return if(dark)darkColorScheme(
        primary=p.primary,onPrimary=p.onPrimary,primaryContainer=p.container,onPrimaryContainer=p.onContainer,
        secondary=Color(0xFFC5C6D0),onSecondary=Color(0xFF2E3038),secondaryContainer=p.container,onSecondaryContainer=p.onContainer,
        tertiary=p.primary,onTertiary=p.onPrimary,tertiaryContainer=p.container,onTertiaryContainer=p.onContainer,
        background=if(amoled)Color.Black else Color(0xFF111318),onBackground=Color(0xFFE3E2E6),
        surface=if(amoled)Color.Black else Color(0xFF111318),onSurface=Color(0xFFE3E2E6),
        onSurfaceVariant=Color(0xFFC5C5CF),surfaceVariant=Color(0xFF44464F),
        surfaceDim=if(amoled)Color.Black else Color(0xFF111318),surfaceBright=Color(0xFF37393F),
        surfaceContainerLowest=if(amoled)Color.Black else Color(0xFF0C0E13),
        surfaceContainerLow=if(amoled)Color(0xFF0D0E12)else Color(0xFF191B20),surfaceContainer=Color(0xFF1D1F25),
        surfaceContainerHigh=Color(0xFF282A30),surfaceContainerHighest=Color(0xFF33353B),
        outline=Color(0xFF8F909A),outlineVariant=Color(0xFF44464F),
        inverseSurface=Color(0xFFE3E2E6),inverseOnSurface=Color(0xFF303036),inversePrimary=palette(accent,false).primary,
        surfaceTint=p.primary
    )else lightColorScheme(
        primary=p.primary,onPrimary=p.onPrimary,primaryContainer=p.container,onPrimaryContainer=p.onContainer,
        secondary=Color(0xFF5D5E68),onSecondary=Color.White,secondaryContainer=p.container,onSecondaryContainer=p.onContainer,
        tertiary=p.primary,onTertiary=p.onPrimary,tertiaryContainer=p.container,onTertiaryContainer=p.onContainer,
        background=Color(0xFFFAF9FD),onBackground=Color(0xFF1B1B20),surface=Color(0xFFFAF9FD),onSurface=Color(0xFF1B1B20),
        onSurfaceVariant=Color(0xFF45464F),surfaceVariant=Color(0xFFE2E2EC),
        surfaceDim=Color(0xFFDAD9DE),surfaceBright=Color(0xFFFAF9FD),surfaceContainerLowest=Color.White,
        surfaceContainerLow=Color(0xFFF4F3F7),surfaceContainer=Color(0xFFEFEEF2),surfaceContainerHigh=Color(0xFFE9E8ED),surfaceContainerHighest=Color(0xFFE3E2E7),
        outline=Color(0xFF757680),outlineVariant=Color(0xFFC5C5CF),
        inverseSurface=Color(0xFF303036),inverseOnSurface=Color(0xFFF2F0F4),inversePrimary=palette(accent,true).primary,
        surfaceTint=p.primary
    )
}
