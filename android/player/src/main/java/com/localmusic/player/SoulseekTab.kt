package com.localmusic.player

import androidx.compose.runtime.Composable
import com.localmusic.core.TrackId

internal const val SOULSEEK_DOWNLOADS = "com.localmusic.player.soulseek.DOWNLOADS"

internal interface SoulseekTab {
    val enabled: Boolean

    @Composable
    fun Settings()

    @Composable
    fun Content(downloadsRequest: Int, openInLibrary: (TrackId) -> Unit)

    fun setVisible(visible: Boolean)
}
