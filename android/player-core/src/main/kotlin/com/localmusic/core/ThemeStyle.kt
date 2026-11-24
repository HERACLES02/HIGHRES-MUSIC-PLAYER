package com.localmusic.core

enum class ThemeFont { DEFAULT }
enum class ThemeCorners { ROUNDED }

data class ThemeStyle(val accent:ThemeAccent,val dark:Boolean,val trueBlack:Boolean,val font:ThemeFont,val corners:ThemeCorners)

/** The one owner of the allowed appearance names and what each one looks like. */
object ThemeStyles {
    val appearances=listOf("System","Light","Dark","AMOLED")
    /** Unknown names fall back to System, as the app always did. */
    fun resolve(appearance:String,accent:ThemeAccent,systemDark:Boolean)=ThemeStyle(accent,
        dark=when(appearance){"Light"->false;"Dark","AMOLED"->true;else->systemDark},
        trueBlack=appearance=="AMOLED",font=ThemeFont.DEFAULT,corners=ThemeCorners.ROUNDED)
}
