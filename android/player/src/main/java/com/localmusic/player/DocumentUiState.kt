package com.localmusic.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.localmusic.core.Track

/** Keep picker payloads through recreation without putting large reports in a Bundle. */
class DocumentUiState : ViewModel() {
    var lyricTarget: Track? = null
    var importedLyrics by mutableStateOf<Pair<Track, String>?>(null)
    var pendingLyricExport: String? = null
    var pendingReport: String? = null
}
