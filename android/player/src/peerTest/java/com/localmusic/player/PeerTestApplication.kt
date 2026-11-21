package com.localmusic.player

class PeerTestApplication : MusicApplication() {
    internal override val soulseekTab: ControlledSoulseekTab? by lazy {
        if (SoulseekFixture.available) ControlledSoulseekTab(this) else null
    }
}
