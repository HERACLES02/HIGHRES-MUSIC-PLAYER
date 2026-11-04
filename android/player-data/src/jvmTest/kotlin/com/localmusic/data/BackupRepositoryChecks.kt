package com.localmusic.data

import com.localmusic.core.*
import java.nio.file.Path
import java.util.UUID

suspend fun backupRepositoryChecks(path: Path) {
    var n=0
    fun verify(ok:Boolean,label:String){n++;check(ok){"Backup repository: $label"}}
    suspend fun rejects(block:suspend()->Unit){var failed=false;try{block()}catch(_:Exception){failed=true};verify(failed,"conflict rejected")}
    val db=openPlayerDatabase(path.toString())
    try {
        val repo=PlayerRepository(db);val a=TrackId.new();val b=TrackId.new()
        fun track(id:TrackId,uri:String)=Track(id,MusicTags("Original"),AudioProperties(100000,"audio/flac",44100,2,16),listOf(TrackSource(uri,SourceKind.DOCUMENT,ContentStamp(10,1))),Availability.AVAILABLE,1)
        val original=track(a,"content://test/a");repo.saveTrack(original.copy(favorite=true))
        val p=repo.createPlaylist("Duplicates",listOf(a,a));val entries=repo.playlist(p.id)!!.second
        val l=SavedLyrics(UUID.randomUUID(),a,"Lyrics",LyricsOrigin.USER_SELECTED,123);repo.saveLyrics(l,true)
        repo.saveExtra(a,LibraryExtras(tagFields=mapOf("TITLE" to "App"),lyricsAdjustmentMs=123))
        val q=ListeningQueue().apply{start(listOf(a,a),1);updatePosition(25000)};repo.saveSession(q.snapshot(),1)
        val backup=repo.captureBackup(BackupPreferences("Dark",false,false))
        verify(backup.tracks.single().favorite&&backup.selections[a]==l.id,"capture favorites and explicit lyric selection")
        verify(backup.resumes.single().positionMs==25000L&&backup.session==q.snapshot(),"capture resume and paused session")
        repo.renamePlaylist(p.id,"Changed");repo.removePlaylistEntry(p.id,entries[0].id);repo.setFavorite(a,false)
        repo.saveTrack(original.copy(tags=MusicTags("New source tags")));repo.saveTrack(track(b,"content://test/b"))
        val newer=repo.createPlaylist("Keep newer",listOf(b))
        repo.restoreBackup(backup,emptyMap(),"receipt-1")
        verify(repo.playlist(p.id)==p to entries,"restore duplicate identities and order")
        verify(repo.playlist(newer.id)!=null&&repo.track(b)!=null,"keep unrelated newer library records")
        verify(repo.track(a)!!.tags.title=="New source tags"&&repo.track(a)!!.favorite,"preserve current source metadata and restore favorite")
        verify(repo.extra(a).tagFields["TITLE"]=="App"&&repo.selectedLyrics(a)==l,"restore app data")
        verify(repo.hasRestoreReceipt("receipt-1")&&!repo.hasRestoreReceipt("absent"),"transaction receipt identifies committed restore")
        val snapshot=(repo.loadSession() as SessionLoad.Restored).queue
        verify(!snapshot.isPlaying&&snapshot.positionMs==25000L,"restore never autoplays")
        val conflict=backup.copy(tracks=backup.tracks+track(TrackId.new(),"content://test/b"))
        rejects{repo.restoreBackup(conflict,emptyMap(),"bad")}
        verify(!repo.hasRestoreReceipt("bad")&&repo.playlist(p.id)==p to entries,"conflict rolls back all writes")
        val broken=backup.copy(tracks=backup.tracks.map{it.copy(favorite=false)},lyrics=listOf(l.copy(originalText="changed")))
        rejects{repo.restoreBackup(broken,emptyMap(),"bad-lyrics")}
        verify(repo.selectedLyrics(a)==l&&!repo.hasRestoreReceipt("bad-lyrics"),"immutable originals survive failed restore")
        verify(repo.track(a)!!.favorite,"late lyric conflict rolls back earlier favorite mutation")
        val wrongOccurrence=backup.copy(tracks=backup.tracks+repo.track(b)!!,playlists=listOf(BackupPlaylist(p,listOf(entries[0].copy(trackId=b)))))
        rejects{repo.restoreBackup(wrongOccurrence,emptyMap(),"bad-entry")}
        verify(repo.playlist(p.id)==p to entries,"occurrence identity cannot be reassigned to another track")
        val fresh=TrackId.new();val freshState=AppBackup(tracks=listOf(track(fresh,"content://test/new")))
        repo.restoreBackup(freshState,emptyMap(),"fresh")
        verify(repo.track(fresh)?.availability==Availability.MISSING,"new references require a fresh scan")
        val asset="art/${"a".repeat(64)}.img"
        repo.restoreBackup(backup.copy(extras=mapOf(a to LibraryExtras(artworkOverride=asset))),mapOf(asset to "/private/restored/image.jpg"),"art")
        verify(repo.extra(a).artworkOverride=="/private/restored/image.jpg","portable artwork maps only through staged assets")
        verify(repo.edits().isEmpty(),"backup restores do not invent file-edit recovery journals")
        repo.saveAppArtwork(listOf(a,b),"/private/chosen.jpg","https://musicbrainz.org/release/test","Test provider credit")
        verify(repo.extra(a).artworkCredit=="Test provider credit"&&repo.extra(b).artworkOverride=="/private/chosen.jpg","album artwork and provenance saved for all chosen identities")
        rejects{repo.saveAppArtwork(listOf(a,TrackId.new()),"/private/wrong.jpg","https://musicbrainz.org/release/test","New credit")}
        verify(repo.extra(a).artworkOverride=="/private/chosen.jpg","missing album identity prevents partial artwork changes")
        println("Backup repository checks passed: $n assertions.")
    } finally { db.close() }
}
