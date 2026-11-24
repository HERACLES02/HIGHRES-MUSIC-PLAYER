package com.localmusic.core

fun themeStyleChecks() {
    fun verify(ok: Boolean, label: String) = check(ok) { "Theme style: $label" }
    verify(ThemeStyles.appearances == listOf("System", "Light", "Dark", "AMOLED", "Cyberpunk"), "allowed appearances are exactly System, Light, Dark, AMOLED, Cyberpunk")

    val accent = ThemeAccent.OCEAN
    val cyber = CyberAccent.NEON_STATIC
    verify(ThemeStyles.resolve("System", accent, cyber, systemDark = true).let { it.dark && !it.trueBlack }, "System follows a dark phone")
    verify(ThemeStyles.resolve("System", accent, cyber, systemDark = false).let { !it.dark && !it.trueBlack }, "System follows a light phone")
    for (systemDark in listOf(true, false)) {
        verify(ThemeStyles.resolve("Light", accent, cyber, systemDark).let { !it.dark && !it.trueBlack }, "Light stays light (system dark=$systemDark)")
        verify(ThemeStyles.resolve("Dark", accent, cyber, systemDark).let { it.dark && !it.trueBlack }, "Dark stays dark (system dark=$systemDark)")
        verify(ThemeStyles.resolve("AMOLED", accent, cyber, systemDark).let { it.dark && it.trueBlack }, "AMOLED is dark and true black (system dark=$systemDark)")
        val neon = ThemeStyles.resolve("Cyberpunk", accent, CyberAccent.NEON_STATIC, systemDark)
        verify(neon.cyber == CyberAccent.NEON_STATIC && neon.dark && !neon.trueBlack, "Cyberpunk + Neon Static is dark, not true black (system dark=$systemDark)")
        verify(neon.font == ThemeFont.DEFAULT && neon.corners == ThemeCorners.ROUNDED, "Neon Static keeps the default font and rounded corners for now")
    }
    for (appearance in ThemeStyles.appearances - "Cyberpunk") for (systemDark in listOf(true, false)) for (chosen in ThemeAccent.entries) {
        val style = ThemeStyles.resolve(appearance, chosen, cyber, systemDark)
        verify(style.accent == chosen && style.cyber == null, "$appearance uses the chosen normal accent $chosen")
        verify(style.font == ThemeFont.DEFAULT && style.corners == ThemeCorners.ROUNDED, "$appearance uses the default font and rounded corners")
    }
}
