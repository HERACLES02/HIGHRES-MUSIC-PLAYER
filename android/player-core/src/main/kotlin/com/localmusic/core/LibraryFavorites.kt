package com.localmusic.core

/** A favorite toggle changes one song; every other Track instance is kept so unrelated rows are not redrawn. */
object LibraryFavorites {
    fun apply(tracks: List<Track>, id: TrackId, favorite: Boolean): List<Track> =
        tracks.map { if (it.id == id) it.copy(favorite = favorite) else it }
    fun apply(byId: Map<TrackId, Track>, id: TrackId, favorite: Boolean): Map<TrackId, Track> =
        byId[id]?.let { byId + (id to it.copy(favorite = favorite)) } ?: byId
}
