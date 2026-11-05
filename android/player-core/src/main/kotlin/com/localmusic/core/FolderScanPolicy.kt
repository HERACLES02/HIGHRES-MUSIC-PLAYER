package com.localmusic.core

import java.util.UUID

/** Only document sources under explicitly selected folder grants belong in the library. */
object FolderScanPolicy {
    fun includes(track: Track, selected: Set<UUID>): Boolean = track.sources.any {
        it.kind == SourceKind.DOCUMENT && it.folderGrantId in selected
    }
}
