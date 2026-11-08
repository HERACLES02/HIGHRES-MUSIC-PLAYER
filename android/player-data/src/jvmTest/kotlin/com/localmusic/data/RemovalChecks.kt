package com.localmusic.data

import com.localmusic.core.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.UUID

suspend fun removalChecks(path:Path) {
    var count=0
    fun verify(ok:Boolean,label:String){check(ok){label};count++}
    fun hidden(detail:LibraryExtras)=Json.parseToJsonElement(detail.encode()).jsonObject["hidden"]?.jsonPrimitive?.boolean?:false
    fun change(detail:LibraryExtras,value:Boolean)=LibraryExtras.decode(JsonObject(
        Json.parseToJsonElement(detail.encode()).jsonObject+ ("hidden" to JsonPrimitive(value))).toString())
    val legacy=LibraryExtras(tagFields=mapOf("TITLE" to "My title"))
    verify(!hidden(legacy),"Older extras default to visible")
    verify(hidden(change(legacy,true)),"Removal flag must survive the extras codec")
    var db=openPlayerDatabase(path.toString())
    var repo=PlayerRepository(db)
    val id=TrackId.new()
    val track=Track(id,MusicTags("Fixture"),AudioProperties(90000,"audio/flac",44100,2,16),
        listOf(TrackSource("content://test/removal",SourceKind.DOCUMENT,ContentStamp(100,1))),Availability.AVAILABLE,1,true)
    repo.saveTrack(track);repo.saveExtra(id,legacy)
    val list=repo.createPlaylist("Keep duplicates",listOf(id,id));val entries=repo.playlist(list.id)!!.second
    val lyrics=SavedLyrics(UUID.randomUUID(),id,"Lyrics",LyricsOrigin.USER_SELECTED,0)
    repo.saveLyrics(lyrics,true)
    val queue=ListeningQueue().apply{start(listOf(id),0);updatePosition(23000)}
    repo.saveSession(queue.snapshot(),1)
    repo.updateExtra(id){change(it,true)}
    db.close();db=openPlayerDatabase(path.toString());repo=PlayerRepository(db)
    try {
        verify(hidden(repo.extra(id)),"Removal survives closing and reopening Room")
        verify(repo.track(id)==track,"Removal retains stable identity, source, metadata and favorite")
        verify(repo.playlist(list.id)!!.second==entries,"Removal preserves duplicate playlist references")
        verify(repo.selectedLyrics(id)?.id==lyrics.id,"Removal preserves chosen lyrics")
        verify((repo.loadSession() as SessionLoad.Restored).queue.positionMs==23000L,"Removal preserves paused session position")
        repo.saveTrack(track.copy(sources=track.sources.map{it.copy(stamp=ContentStamp(200,2))}))
        verify(hidden(repo.extra(id)),"Refreshing source metadata must not reset removal")
        val backup=repo.captureBackup(BackupPreferences())
        val bytes=ByteArrayOutputStream().also{BackupArchive.write(BackupBundle(backup),it)}.toByteArray()
        verify(hidden(BackupArchive.read(bytes.inputStream()).state.extras.getValue(id)),"Removal survives portable backup round-trip")
        repo.updateExtra(id){change(it,false)}
        verify(!hidden(repo.extra(id))&&repo.track(id)?.id==id,"Restore reuses the same song identity")
        verify(repo.extra(id).tagFields==legacy.tagFields,"Restore preserves app-only tags")
        val invalid=legacy.encode().dropLast(1)+",\"hidden\":\"yes\"}"
        var rejected=false
        try{LibraryExtras.decode(invalid)}catch(_:Exception){rejected=true}
        verify(rejected,"Malformed removal flags must be rejected")
        println("Library removal checks passed: $count assertions.")
    } finally {db.close()}
}
