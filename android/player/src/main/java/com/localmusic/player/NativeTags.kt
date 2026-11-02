package com.localmusic.player

object NativeTags {
    init { System.loadLibrary("localmusic_tags") }
    external fun read(path: String, artworkPath: String): String
    external fun edit(path: String, keys: Array<String>, values: Array<String>, coverMode: Int, coverPath: String, mime: String)
}
