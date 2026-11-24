package com.localmusic.core

enum class ThemeFont { DEFAULT }
enum class ThemeCorners { ROUNDED }

/** [cyber] is set only for the Cyberpunk appearance; its palette then replaces [accent]'s. */
data class ThemeStyle(val accent:ThemeAccent,val cyber:CyberAccent?,val dark:Boolean,val trueBlack:Boolean,val font:ThemeFont,val corners:ThemeCorners)

/** The one owner of the allowed appearance names and what each one looks like. */
object ThemeStyles {
    val appearances=listOf("System","Light","Dark","AMOLED","Cyberpunk")
    /** Unknown names fall back to System, as the app always did. Cyberpunk ignores the phone's dark mode. */
    fun resolve(appearance:String,accent:ThemeAccent,cyberAccent:CyberAccent,systemDark:Boolean)=
        if(appearance=="Cyberpunk")ThemeStyle(accent,cyberAccent,dark=cyberAccent!=CyberAccent.HAZARD,
            trueBlack=cyberAccent==CyberAccent.REDLINE,font=ThemeFont.DEFAULT,corners=ThemeCorners.ROUNDED)
        else ThemeStyle(accent,null,
            dark=when(appearance){"Light"->false;"Dark","AMOLED"->true;else->systemDark},
            trueBlack=appearance=="AMOLED",font=ThemeFont.DEFAULT,corners=ThemeCorners.ROUNDED)
}
