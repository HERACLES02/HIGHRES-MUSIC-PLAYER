package com.localmusic.core

fun themeStyleChecks() {
    fun verify(ok: Boolean, label: String) = check(ok) { "Theme style: $label" }
    verify(ThemeStyles.appearances == listOf("System", "Light", "Dark", "AMOLED"), "allowed appearances are exactly System, Light, Dark, AMOLED")

    val accent = ThemeAccent.OCEAN
    verify(ThemeStyles.resolve("System", accent, systemDark = true).let { it.dark && !it.trueBlack }, "System follows a dark phone")
    verify(ThemeStyles.resolve("System", accent, systemDark = false).let { !it.dark && !it.trueBlack }, "System follows a light phone")
    for (systemDark in listOf(true, false)) {
        verify(ThemeStyles.resolve("Light", accent, systemDark).let { !it.dark && !it.trueBlack }, "Light stays light (system dark=$systemDark)")
        verify(ThemeStyles.resolve("Dark", accent, systemDark).let { it.dark && !it.trueBlack }, "Dark stays dark (system dark=$systemDark)")
        verify(ThemeStyles.resolve("AMOLED", accent, systemDark).let { it.dark && it.trueBlack }, "AMOLED is dark and true black (system dark=$systemDark)")
    }
    for (appearance in ThemeStyles.appearances) for (systemDark in listOf(true, false)) for (chosen in ThemeAccent.entries) {
        val style = ThemeStyles.resolve(appearance, chosen, systemDark)
        verify(style.accent == chosen, "$appearance keeps the chosen accent $chosen")
        verify(style.font == ThemeFont.DEFAULT && style.corners == ThemeCorners.ROUNDED, "$appearance uses the default font and rounded corners")
    }
}
