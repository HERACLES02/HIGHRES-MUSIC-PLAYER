package com.localmusic.player

import android.content.Context
import android.content.res.Configuration
import com.localmusic.core.CyberAccent
import com.localmusic.core.ThemeAccent
import com.localmusic.core.ThemeStyles

/** A tiny launch-only cache. DataStore and restored backups remain authoritative. */
object StartupAppearance {
    fun hasSaved(context:Context)=context.getSharedPreferences("launch-appearance",Context.MODE_PRIVATE).contains("appearance")
    fun read(context:Context):MusicPreferences {
        val saved=context.getSharedPreferences("launch-appearance",Context.MODE_PRIVATE)
        return MusicPreferences(appearance=saved.getString("appearance","System") ?: "System",
            accent=runCatching{ThemeAccent.valueOf(saved.getString("accent","SAGE") ?: "SAGE")}.getOrDefault(ThemeAccent.SAGE),
            cyberAccent=runCatching{CyberAccent.valueOf(saved.getString("cyberAccent","NEON_STATIC") ?: "NEON_STATIC")}.getOrDefault(CyberAccent.NEON_STATIC))
    }
    fun write(context:Context,value:MusicPreferences) {
        context.getSharedPreferences("launch-appearance",Context.MODE_PRIVATE).edit()
            .putString("appearance",value.appearance).putString("accent",value.accent.name)
            .putString("cyberAccent",value.cyberAccent.name).apply()
    }
    fun style(context:Context,value:MusicPreferences)=ThemeStyles.resolve(value.appearance,value.accent,value.cyberAccent,
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
}
