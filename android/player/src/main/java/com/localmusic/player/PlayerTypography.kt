package com.localmusic.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.localmusic.core.ThemeFont

private val ChakraPetch=FontFamily(Font(R.font.chakra_petch_regular,FontWeight.Normal),Font(R.font.chakra_petch_medium,FontWeight.Medium),
    Font(R.font.chakra_petch_semibold,FontWeight.SemiBold),Font(R.font.chakra_petch_bold,FontWeight.Bold))
private val Rajdhani=FontFamily(Font(R.font.rajdhani_regular,FontWeight.Normal),Font(R.font.rajdhani_medium,FontWeight.Medium),
    Font(R.font.rajdhani_semibold,FontWeight.SemiBold),Font(R.font.rajdhani_bold,FontWeight.Bold))
private val ShareTechMono=FontFamily(Font(R.font.share_tech_mono_regular))

/** Headings and labels take the Cyberpunk accent's font; body text keeps the default font except on Redline,
 *  which is monospace throughout. Normal themes get Material's default typography, as before. */
fun playerTypography(font:ThemeFont):Typography {
    val base=Typography()
    val accent=when(font){ThemeFont.DEFAULT->return base;ThemeFont.CHAKRA_PETCH->ChakraPetch;ThemeFont.RAJDHANI->Rajdhani;ThemeFont.SHARE_TECH_MONO->ShareTechMono}
    fun TextStyle.inAccent()=copy(fontFamily=accent)
    fun TextStyle.body()=if(font==ThemeFont.SHARE_TECH_MONO)inAccent() else this
    return base.copy(
        displayLarge=base.displayLarge.inAccent(),displayMedium=base.displayMedium.inAccent(),displaySmall=base.displaySmall.inAccent(),
        headlineLarge=base.headlineLarge.inAccent(),headlineMedium=base.headlineMedium.inAccent(),headlineSmall=base.headlineSmall.inAccent(),
        titleLarge=base.titleLarge.inAccent(),titleMedium=base.titleMedium.inAccent(),titleSmall=base.titleSmall.inAccent(),
        labelLarge=base.labelLarge.inAccent(),labelMedium=base.labelMedium.inAccent(),labelSmall=base.labelSmall.inAccent(),
        bodyLarge=base.bodyLarge.body(),bodyMedium=base.bodyMedium.body(),bodySmall=base.bodySmall.body())
}

/** Time codes and the audio-format readout use Share Tech Mono on every Cyberpunk accent. */
@Composable
internal fun readoutStyle(style:TextStyle):TextStyle=if(LocalThemeStyle.current?.cyber!=null)style.copy(fontFamily=ShareTechMono) else style
