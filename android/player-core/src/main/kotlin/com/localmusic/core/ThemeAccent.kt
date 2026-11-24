package com.localmusic.core

enum class ThemeAccent(val label:String) {
    SAGE("Sage"), OCEAN("Ocean"), VIOLET("Violet"), ROSE("Rose"), AMBER("Amber")
}

/** Saved separately from [ThemeAccent] so each is remembered while the other appearance is in use. */
enum class CyberAccent(val label:String) {
    NEON_STATIC("Neon Static")
}
