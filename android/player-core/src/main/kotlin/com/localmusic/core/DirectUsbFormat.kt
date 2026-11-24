package com.localmusic.core

object DirectUsbFormat {
    fun supported(audio: AudioProperties): Boolean=audio.mimeType in setOf("audio/flac","audio/x-flac") && audio.channels==2 &&
        audio.sampleRateHz in setOf(44100,48000,88200,96000) && audio.meaningfulBits in setOf(16,24)
}
