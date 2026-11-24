package com.localmusic.player

import androidx.compose.material3.*
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.localmusic.core.CyberAccent
import com.localmusic.core.ThemeAccent
import com.localmusic.core.ThemeStyle

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
internal fun cyberPreviewColor(accent:CyberAccent):Color=cyberColorScheme(accent).primary

/** Lets screens add touches that only a Cyberpunk accent asks for; null outside the themed activity content. */
val LocalThemeStyle=staticCompositionLocalOf<ThemeStyle?>{null}

fun playerColorScheme(style:ThemeStyle):ColorScheme=style.cyber?.let(::cyberColorScheme)?:playerColorScheme(style.dark,style.trueBlack,style.accent)

/** Every text role reaches 4.5:1 on the background, cards (surfaceContainer) and dialogs (surfaceContainerHigh);
 *  Night Ride, Redline and Hazard also reach it on every other surface. Where a concept colour falls short it is shifted:
 *  Neon Static's red (#FF003C is 4.2:1 on cards), Night Ride's pink (#FF2E88 is 4.5:1 only on the background) and
 *  Hazard's red text (#C4001F is 3.9:1 on cards; it stays as the white-text tag colour, primaryContainer). */
private fun cyberColorScheme(accent:CyberAccent):ColorScheme=when(accent){
    CyberAccent.NEON_STATIC->darkColorScheme(
        primary=Color(0xFFFCEE0A),onPrimary=Color(0xFF0B0C10),primaryContainer=Color(0xFF3D3A00),onPrimaryContainer=Color(0xFFFCEE0A),
        secondary=Color(0xFF00F0FF),onSecondary=Color(0xFF0B0C10),secondaryContainer=Color(0xFF00393D),onSecondaryContainer=Color(0xFF00F0FF),
        tertiary=Color(0xFF00F0FF),onTertiary=Color(0xFF0B0C10),tertiaryContainer=Color(0xFF00393D),onTertiaryContainer=Color(0xFF00F0FF),
        error=Color(0xFFFF4565),onError=Color(0xFF0B0C10),errorContainer=Color(0xFF4D0014),onErrorContainer=Color(0xFFFFD9DE),
        background=Color(0xFF0B0C10),onBackground=Color(0xFFE8E6DA),surface=Color(0xFF0B0C10),onSurface=Color(0xFFE8E6DA),
        onSurfaceVariant=Color(0xFFB4B8C5),surfaceVariant=Color(0xFF2A2E3A),
        surfaceDim=Color(0xFF0B0C10),surfaceBright=Color(0xFF323746),surfaceContainerLowest=Color(0xFF07080B),
        surfaceContainerLow=Color(0xFF14161D),surfaceContainer=Color(0xFF1C1F29),
        surfaceContainerHigh=Color(0xFF222531),surfaceContainerHighest=Color(0xFF2A2E3A),
        outline=Color(0xFF8A8F9E),outlineVariant=Color(0xFF3A3F4E),
        inverseSurface=Color(0xFFE8E6DA),inverseOnSurface=Color(0xFF1C1F29),inversePrimary=Color(0xFF5C5700),
        surfaceTint=Color(0xFFFCEE0A)
    )
    CyberAccent.NIGHT_RIDE->darkColorScheme(
        primary=Color(0xFFFF5A9E),onPrimary=Color(0xFF0E0620),primaryContainer=Color(0xFF6B0F3D),onPrimaryContainer=Color(0xFFFFD9E6),
        secondary=Color(0xFF2DE2E6),onSecondary=Color(0xFF0E0620),secondaryContainer=Color(0xFF0F4A4C),onSecondaryContainer=Color(0xFFC8FBFC),
        tertiary=Color(0xFFFFE45E),onTertiary=Color(0xFF0E0620),tertiaryContainer=Color(0xFF4D4200),onTertiaryContainer=Color(0xFFFFE45E),
        error=Color(0xFFFF7373),onError=Color(0xFF0E0620),errorContainer=Color(0xFF5C0A14),onErrorContainer=Color(0xFFFFDAD6),
        background=Color(0xFF0E0620),onBackground=Color(0xFFF3EAFF),surface=Color(0xFF0E0620),onSurface=Color(0xFFF3EAFF),
        onSurfaceVariant=Color(0xFFC9B8E8),surfaceVariant=Color(0xFF35205F),
        surfaceDim=Color(0xFF0E0620),surfaceBright=Color(0xFF35205F),surfaceContainerLowest=Color(0xFF080312),
        surfaceContainerLow=Color(0xFF1B0E38),surfaceContainer=Color(0xFF2A1850),
        surfaceContainerHigh=Color(0xFF301C59),surfaceContainerHighest=Color(0xFF35205F),
        outline=Color(0xFF9584B8),outlineVariant=Color(0xFF4A3478),
        inverseSurface=Color(0xFFF3EAFF),inverseOnSurface=Color(0xFF2A1850),inversePrimary=Color(0xFFB0005A),
        surfaceTint=Color(0xFFFF5A9E)
    )
    CyberAccent.REDLINE->darkColorScheme(
        primary=Color(0xFFFF3B3B),onPrimary=Color(0xFF000000),primaryContainer=Color(0xFF5C0A0A),onPrimaryContainer=Color(0xFFFFDAD6),
        secondary=Color(0xFFFF8A80),onSecondary=Color(0xFF000000),secondaryContainer=Color(0xFF3D0C0C),onSecondaryContainer=Color(0xFFFFDAD6),
        tertiary=Color(0xFFF2F2F2),onTertiary=Color(0xFF000000),tertiaryContainer=Color(0xFF2A2A2A),onTertiaryContainer=Color(0xFFF2F2F2),
        error=Color(0xFFFFB4AB),onError=Color(0xFF000000),errorContainer=Color(0xFF93000A),onErrorContainer=Color(0xFFFFDAD6),
        background=Color(0xFF000000),onBackground=Color(0xFFF2F2F2),surface=Color(0xFF000000),onSurface=Color(0xFFF2F2F2),
        onSurfaceVariant=Color(0xFF8A8A8A),surfaceVariant=Color(0xFF2A1010),
        surfaceDim=Color(0xFF000000),surfaceBright=Color(0xFF341414),surfaceContainerLowest=Color(0xFF000000),
        surfaceContainerLow=Color(0xFF0A0303),surfaceContainer=Color(0xFF140606),
        surfaceContainerHigh=Color(0xFF1E0A0A),surfaceContainerHighest=Color(0xFF2A1010),
        outline=Color(0xFF8A8A8A),outlineVariant=Color(0xFF3D1A1A),
        inverseSurface=Color(0xFFF2F2F2),inverseOnSurface=Color(0xFF140606),inversePrimary=Color(0xFFB3001B),
        surfaceTint=Color(0xFFFF3B3B)
    )
    // Light base: black panels (secondary, tertiary, error banner and inverse surfaces) with white text on yellow.
    CyberAccent.HAZARD->lightColorScheme(
        primary=Color(0xFF960018),onPrimary=Color(0xFFFFFFFF),primaryContainer=Color(0xFFC4001F),onPrimaryContainer=Color(0xFFFFFFFF),
        secondary=Color(0xFF0A0A0A),onSecondary=Color(0xFFFFFFFF),secondaryContainer=Color(0xFF0A0A0A),onSecondaryContainer=Color(0xFFFFFFFF),
        tertiary=Color(0xFF0A0A0A),onTertiary=Color(0xFFFFFFFF),tertiaryContainer=Color(0xFF0A0A0A),onTertiaryContainer=Color(0xFFFFFFFF),
        error=Color(0xFF93000A),onError=Color(0xFFFFFFFF),errorContainer=Color(0xFF0A0A0A),onErrorContainer=Color(0xFFFF8A80),
        background=Color(0xFFF3E600),onBackground=Color(0xFF0A0A0A),surface=Color(0xFFF3E600),onSurface=Color(0xFF0A0A0A),
        onSurfaceVariant=Color(0xFF2E2A00),surfaceVariant=Color(0xFFC8BC00),
        surfaceDim=Color(0xFFD2C600),surfaceBright=Color(0xFFFFF34D),surfaceContainerLowest=Color(0xFFFFF34D),
        surfaceContainerLow=Color(0xFFEADD00),surfaceContainer=Color(0xFFDCD000),
        surfaceContainerHigh=Color(0xFFD2C600),surfaceContainerHighest=Color(0xFFC8BC00),
        outline=Color(0xFF5C5600),outlineVariant=Color(0xFFA89E00),
        inverseSurface=Color(0xFF0A0A0A),inverseOnSurface=Color(0xFFFFFFFF),inversePrimary=Color(0xFFFF5A6E),
        surfaceTint=Color(0xFF0A0A0A)
    )
}

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
