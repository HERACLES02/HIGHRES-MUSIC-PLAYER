package com.localmusic.data

import com.localmusic.core.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*

fun backupArchiveChecks() {
    var count=0
    fun verify(ok:Boolean,label:String){count++;check(ok){"Backup archive: $label"}}
    fun rejects(label:String,action:()->Unit){var rejected=false;try{action()}catch(_:Exception){rejected=true};verify(rejected,label)}
    val legacyPreferences = """{"version":1,"appearance":"System","autoScan":false,"homeGrid":true}"""
    verify(PreferenceCodec.decode(legacyPreferences).accent==ThemeAccent.SAGE,"older preferences retain the original Sage accent")
    for(accent in ThemeAccent.entries){
        val preference=BackupPreferences(appearance="AMOLED",autoScan=true,homeGrid=false,accent=accent)
        verify(PreferenceCodec.decode(PreferenceCodec.encode(preference))==preference,"accent and existing appearance choices survive persistence: $accent")
    }
    var invalidAccent=false
    try{PreferenceCodec.decode(legacyPreferences.dropLast(1)+",\"accent\":\"UNKNOWN\"}")}catch(_:IllegalArgumentException){invalidAccent=true}
    verify(invalidAccent,"invalid accent cannot silently alter restored appearance")
    fun encodedArtwork(text: String) = Json.parseToJsonElement(PreferenceCodec.encode(PreferenceCodec.decode(text))).jsonObject["artworkSize"]?.jsonPrimitive?.content
    verify(encodedArtwork(legacyPreferences) == "Large", "legacy settings default to large artwork")
    val compactPreferences = legacyPreferences.dropLast(1) + ",\"artworkSize\":\"Compact\"}"
    verify(encodedArtwork(compactPreferences) == "Compact", "artwork size survives preference round-trip")
    rejects("invalid artwork size rejected") { PreferenceCodec.decode(legacyPreferences.dropLast(1) + ",\"artworkSize\":\"huge\"}") }
    val id=TrackId(UUID(0,101));val pid=PlaylistId(UUID(0,102));val lyricId=UUID(0,103)
    val track=Track(id,MusicTags("বাংলা",listOf("Artist")),AudioProperties(100000,"audio/flac",96000,2,24),
        listOf(TrackSource("content://media/external/audio/media/101",SourceKind.MEDIA_STORE,ContentStamp(1024,2))),Availability.AVAILABLE,1,true)
    val entries=(0..1).map{PlaylistEntry(EntryId.new(),pid,id,it.toLong())}
    val q=ListeningQueue().apply{start(listOf(id,id),1,origin="Playlist · Mix");updatePosition(23000)}
    val backup=AppBackup(listOf(track),listOf(BackupPlaylist(Playlist(pid,"Mix"),entries)),
        listOf(SavedLyrics(lyricId,id,"[00:01]বাংলা",LyricsOrigin.USER_SELECTED,250)),mapOf(id to lyricId),
        mapOf(id to LibraryExtras(tagFields=mapOf("TITLE" to "App title"),lyricsAdjustmentMs=250)),
        listOf(ResumeRow(id.value.toString(),23000,false,4)),session=q.snapshot(),preferences=BackupPreferences("AMOLED",false,false))
    fun encode(b:BackupBundle)=ByteArrayOutputStream().also{BackupArchive.write(b,it)}.toByteArray()
    val bytes=encode(BackupBundle(backup))
    verify(BackupArchive.read(ByteArrayInputStream(bytes)).state==backup,"Unicode, identities, duplicate order, lyrics, preferences and session round-trip")
    verify(BackupArchive.read(ByteArrayInputStream(encode(BackupBundle(AppBackup())))).state==AppBackup(),"empty archive round-trip")
    val art=byteArrayOf(1,3,5,7);val name="art/${BackupArchive.sha(art)}.img"
    val withArt=backup.copy(extras=mapOf(id to LibraryExtras(artworkOverride=name)))
    val decodedArt=BackupArchive.read(encode(BackupBundle(withArt,mapOf(name to art))).inputStream())
    verify(decodedArt.state==withArt&&decodedArt.assets.getValue(name).contentEquals(art),"portable chosen artwork round-trip")
    rejects("missing chosen artwork rejected"){encode(BackupBundle(withArt))}
    rejects("unreferenced artwork rejected"){encode(BackupBundle(backup,mapOf(name to art)))}
    rejects("truncated central directory rejected"){BackupArchive.read(bytes.copyOf(bytes.size-22).inputStream())}
    val contents=linkedMapOf<String,ByteArray>()
    ZipInputStream(bytes.inputStream()).use{zip->while(true){val e=zip.nextEntry?:break;contents[e.name]=zip.readBytes();zip.closeEntry()}}
    val changed=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{zip->contents.forEach{(key,value)->
        zip.putNextEntry(ZipEntry(key));zip.write(if(key=="data.json") value+byteArrayOf(32) else value);zip.closeEntry()
    }}}.toByteArray()
    rejects("metadata checksum detects valid ZIP tampering"){BackupArchive.read(changed.inputStream())}
    rejects("truncated archive rejected"){BackupArchive.read(ByteArrayInputStream(bytes.copyOf(bytes.size/2)))}
    rejects("binary input rejected"){BackupArchive.read(ByteArrayInputStream(byteArrayOf(1,2,3)))}
    fun raw(name:String)=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{it.putNextEntry(ZipEntry(name));it.write(byteArrayOf(1));it.closeEntry()}}.toByteArray()
    rejects("path traversal rejected"){BackupArchive.read(ByteArrayInputStream(raw("../data.json")))}
    rejects("unknown archive entry rejected"){BackupArchive.read(ByteArrayInputStream(raw("unexpected.bin")))}
    rejects("duplicate track identities rejected"){encode(BackupBundle(backup.copy(tracks=listOf(track,track))))}
    rejects("dangling lyric selection rejected"){encode(BackupBundle(backup.copy(selections=mapOf(id to UUID.randomUUID()))))}
    rejects("duplicate occurrence IDs rejected"){encode(BackupBundle(backup.copy(playlists=listOf(BackupPlaylist(Playlist(pid,"Mix"),listOf(entries[0],entries[0]))))))}
    rejects("artwork absolute path rejected"){encode(BackupBundle(backup.copy(extras=mapOf(id to LibraryExtras(artworkOverride="/private/arbitrary.jpg")))))}
    rejects("oversized asset rejected"){encode(BackupBundle(backup,mapOf("art/${"a".repeat(64)}.img" to ByteArray(BackupArchive.MAX_ASSET+1))))}
    rejects("invalid preference rejected"){encode(BackupBundle(backup.copy(preferences=BackupPreferences("unrecognized"))))}
    val oldData=Json.parseToJsonElement(contents.getValue("data.json").toString(Charsets.UTF_8)).jsonObject.toMutableMap()
    oldData["version"]=JsonPrimitive(1)
    oldData["preferences"]=JsonObject(oldData.getValue("preferences").jsonObject.filterKeys{it in setOf("appearance","autoScan","homeGrid")})
    val legacyData=JsonObject(oldData).toString().toByteArray()
    val legacyManifest=buildJsonObject{put("format","local-music-backup");put("version",1);put("entries",buildJsonArray{add(buildJsonObject{put("name","data.json");put("length",legacyData.size);put("sha256",BackupArchive.sha(legacyData))})})}.toString().toByteArray()
    val legacy=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{zip->for((n,v)in mapOf("manifest.json" to legacyManifest,"data.json" to legacyData)){zip.putNextEntry(ZipEntry(n));zip.write(v);zip.closeEntry()}}}.toByteArray()
    verify(BackupArchive.read(legacy.inputStream()).state==backup,"version 1 backups restore with effects off and no invented profiles")
    val artist=com.localmusic.core.ArtistProfile("00000000-0000-0000-0000-000000000005","Mira",biography="Offline biography",imagePath=name,imageCredit="Test creator",savedAtMs=123)
    val prefs=backup.preferences.copy(audio=AudioSettings(true,EqPreset("Test",-2.0),listOf(EqPreset("Saved")),ReplayGainMode.ALBUM,4),artists=mapOf("mira" to artist),widgets=WidgetSettings("Light",false),accent=ThemeAccent.OCEAN)
    val modern=backup.copy(preferences=prefs)
    verify(BackupArchive.read(encode(BackupBundle(modern,mapOf(name to art))).inputStream()).state==modern,"v2 saves effects, preset bank, widget choice and credited artist with artwork")
    val converted=checkNotNull(AudioCodec::class.java.getResourceAsStream("/eq/external-graphic.json")).bufferedReader(Charsets.UTF_8).use{AudioCodec.importPreset(it.readText()).preset}
    val independent=modern.copy(preferences=prefs.copy(artists=mapOf("mira" to artist.copy(providerId="wikipedia:123",biographyUrl="https://en.wikipedia.org/?curid=123")),
        audio=prefs.audio.copy(preset=converted,presets=listOf(converted))),
        lyrics=modern.lyrics.map{it.copy(providerId="lyrics.ovh:fixture",sourceUrl="https://api.lyrics.ovh/v1/Fixture/Title")})
    verify(BackupArchive.read(encode(BackupBundle(independent,mapOf(name to art))).inputStream()).state==independent,"v2 archive preserves converted EQ, independent Wikipedia identity and alternate lyric provenance")
    val storage=java.nio.file.Files.createTempDirectory("backup-artwork-").toFile()
    try {
        val missing=java.io.File(storage,"missing.jpg")
        val state=modern.copy(preferences=prefs.copy(artists=mapOf("mira" to artist.copy(imagePath=missing.path))))
        val captured=BackupArtwork.capture(state,storage)
        val restored=BackupArchive.read(encode(captured.bundle).inputStream())
        verify(captured.missingFiles==1,"missing artist image is explicitly reported")
        verify(restored.state==state.copy(preferences=state.preferences.copy(artists=mapOf("mira" to artist.copy(imagePath=null)))),"missing image does not lose artist text, playlist, queue, settings or other data")
        verify(state.preferences.artists.getValue("mira").imagePath==missing.path,"export does not repair live artist references")
        val both=state.copy(extras=state.extras+(id to state.extras.getValue(id).copy(artworkOverride=missing.path)))
        val absent=BackupArtwork.capture(both,storage)
        verify(absent.missingFiles==1&&absent.bundle.state.extras.getValue(id).artworkOverride==null,"missing album image is omitted and shared missing path counted once")
        BackupArchive.read(encode(absent.bundle).inputStream())
        val present=java.io.File(storage,"present.jpg").apply{writeBytes(art)}
        val available=both.copy(extras=both.extras+(id to both.extras.getValue(id).copy(artworkOverride=present.path)),
            preferences=both.preferences.copy(artists=mapOf("mira" to artist.copy(imagePath=present.path))))
        val included=BackupArtwork.capture(available,storage)
        verify(included.missingFiles==0&&included.bundle.assets.size==1&&included.bundle.assets.getValue(name).contentEquals(art),"existing shared artwork is included exactly once")
        verify(BackupArchive.read(encode(included.bundle).inputStream()).state==included.bundle.state,"included artwork still passes strict archive validation")
        fun withPath(path:String)=state.copy(preferences=state.preferences.copy(artists=mapOf("mira" to artist.copy(imagePath=path))))
        rejects("outside-storage path remains an error even when missing"){BackupArtwork.capture(withPath(java.io.File(storage.parentFile,"outside-art.jpg").path),storage)}
        rejects("unreadable directory is not treated as a missing image"){BackupArtwork.capture(withPath(storage.path),storage)}
        val large=java.io.File(storage,"large.jpg").apply{java.io.RandomAccessFile(this,"rw").use{it.setLength(BackupArchive.MAX_ASSET+1L)}}
        rejects("oversized artwork still fails"){BackupArtwork.capture(withPath(large.path),storage)}
    } finally { storage.deleteRecursively() }
    println("Backup archive checks passed: $count assertions.")
}
