package com.localmusic.player

import androidx.compose.runtime.*
import org.json.JSONObject

/** Keeps the fast playback clock separate from the browse/navigation snapshot. */
@Stable
class PlaybackUiState {
    var snapshot by mutableStateOf(JSONObject());private set
    val position=mutableLongStateOf(0)
    val sleepRemaining=mutableLongStateOf(0)
    private var signature=""
    fun accept(value:JSONObject) {
        position.longValue=value.optLong("position")
        sleepRemaining.longValue=value.optLong("sleepRemaining")
        val browse=JSONObject(value.toString()).apply{remove("position");remove("sleepRemaining")}
        val next=browse.toString()
        if(next!=signature){signature=next;snapshot=browse}
    }
}
