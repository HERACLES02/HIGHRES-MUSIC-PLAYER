package com.localmusic.data

import com.localmusic.core.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

data class BackupPreferences(val appearance:String="System",val autoScan:Boolean=false,val homeGrid:Boolean=true,
    val audio:AudioSettings=AudioSettings(),val widgets:WidgetSettings=WidgetSettings(),val artists:Map<String,ArtistProfile> = emptyMap(),val artworkSize:String="Large",val accent:ThemeAccent=ThemeAccent.SAGE)
data class BackupPlaylist(val playlist: Playlist, val entries: List<PlaylistEntry>)
data class AppBackup(val tracks: List<Track> = emptyList(), val playlists: List<BackupPlaylist> = emptyList(),
    val lyrics: List<SavedLyrics> = emptyList(), val selections: Map<TrackId, UUID> = emptyMap(),
    val extras: Map<TrackId, LibraryExtras> = emptyMap(), val resumes: List<ResumeRow> = emptyList(),
    val folders: List<FolderGrant> = emptyList(), val session: QueueSnapshot? = null,
    val preferences: BackupPreferences = BackupPreferences())
data class BackupBundle(val state: AppBackup, val assets: Map<String, ByteArray> = emptyMap())

object BackupArchive {
    const val MAX_TOTAL = 32 * 1024 * 1024
    const val MAX_DATA = 8 * 1024 * 1024
    const val MAX_ASSET = 2 * 1024 * 1024
    fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun uuid(value:String)=UUID.fromString(value).also{require(it.toString().equals(value,true)){"Invalid UUID"}}
    private fun JsonObject.str(key:String)=getValue(key).jsonPrimitive.also{require(it.isString)}.content
    private fun JsonObject.opt(key:String)=get(key)?.takeUnless{it==JsonNull}?.jsonPrimitive?.also{require(it.isString)}?.content
    private fun JsonObject.num(key:String)=getValue(key).jsonPrimitive.also{require(!it.isString)}.long
    private fun JsonObject.flag(key:String)=getValue(key).jsonPrimitive.also{require(!it.isString)}.boolean
    private fun JsonObject.array(key:String,limit:Int)=getValue(key).jsonArray.also{require(it.size<=limit){"Too many $key"}}
    private fun localUri(value:String){require(value.length<=8192&&(value.startsWith("content://")||value.startsWith("file://"))){"Unsupported saved file location"}}
    private fun assetName(name:String)=name.matches(Regex("art/[a-f0-9]{64}\\.img"))
    fun artworkReferences(state:AppBackup):Set<String> = (state.extras.values.mapNotNull{it.artworkOverride?.takeIf(String::isNotEmpty)}+
        state.preferences.artists.values.mapNotNull{it.imagePath}).toSet()
    fun validate(state:AppBackup){
        require(state.preferences.appearance in setOf("System","Light","Dark","AMOLED")){"Unknown appearance"}
        PreferenceCodec.decode(PreferenceCodec.encode(state.preferences))
        require(state.preferences.artists.values.all{p->p.imagePath?.let(::assetName)?:true}){"Unsafe artist artwork reference"}
        require(state.tracks.size<=100000&&state.playlists.size<=5000&&state.lyrics.size<=100000)
        val ids=state.tracks.map{it.id}.toSet();require(ids.size==state.tracks.size){"Duplicate track IDs"}
        val uris=mutableSetOf<String>()
        state.tracks.forEach { t ->
            require(t.addedAtMs>=0&&t.tags.title.length<=4096&&t.tags.artists.size<=100)
            require(t.sources.size<=100)
            t.sources.forEach { source ->localUri(source.uri);require(uris.add(source.uri)){"Duplicate source ownership"} }
        }
        require(state.playlists.map{it.playlist.id}.distinct().size==state.playlists.size){"Duplicate playlist IDs"}
        val entries=mutableSetOf<EntryId>();var totalEntries=0
        state.playlists.forEach { p ->
            Playlists.requireName(p.playlist.name);require(p.entries.size<=Playlists.MAX_ENTRIES)
            require(p.entries.map{it.rank}.distinct().size==p.entries.size)
            p.entries.forEach{require(it.playlistId==p.playlist.id&&it.trackId in ids&&it.rank>=0&&entries.add(it.id)){"Invalid playlist occurrence"}}
            totalEntries+=p.entries.size;require(totalEntries<=250000)
        }
        require(state.lyrics.map{it.id}.distinct().size==state.lyrics.size){"Duplicate lyric IDs"}
        state.lyrics.forEach{require(it.trackId in ids&&it.originalText.length<=LrcParser.MAX_CHARS)}
        state.selections.forEach{(track,version)->require(state.lyrics.any{it.id==version&&it.trackId==track}){"Invalid lyric selection"}}
        state.extras.forEach{(id,extra)->
            require(id in ids);TagPatch(extra.tagFields)
            require(extra.artworkPath==null&&extra.artworkStamp==null){"Regenerable image cache must not be restored as a path"}
            extra.artworkOverride?.takeIf{it.isNotEmpty()}?.let{require(assetName(it)){"Unsafe artwork reference"}}
            extra.sidecarUri?.let(::localUri)
            listOfNotNull(extra.sidecarVersion,extra.embeddedVersion).forEach{version->require(state.lyrics.any{it.id==uuid(version)&&it.trackId==id})}
        }
        require(state.resumes.map{it.trackId}.distinct().size==state.resumes.size)
        state.resumes.forEach{require(TrackId(uuid(it.trackId)) in ids&&it.positionMs>=0&&it.updatedAtMs>=0)}
        require(state.folders.size<=10000&&state.folders.map{it.id}.distinct().size==state.folders.size&&state.folders.map{it.treeUri}.distinct().size==state.folders.size)
        state.folders.forEach{localUri(it.treeUri);require(it.grantedAtMs>=0)}
        state.session?.let{snapshot->
            require(ListeningQueue().restore(snapshot));require((snapshot.collection+snapshot.history+snapshot.manual).all{it.trackId in ids}){"Session refers to unknown songs"}
        }
    }

    private fun encode(state:AppBackup):ByteArray {
        validate(state)
        val root=buildJsonObject {
            put("version",2)
            put("tracks",JsonArray(state.tracks.map{t->buildJsonObject{
                put("id",t.id.value.toString());put("tags",Json.parseToJsonElement(ModelCodec.tags(t.tags)));put("audio",Json.parseToJsonElement(ModelCodec.audio(t.audio)))
                put("availability",t.availability.name);put("addedAt",t.addedAtMs);put("favorite",t.favorite)
                put("sources",JsonArray(t.sources.map{v->buildJsonObject{put("uri",v.uri);put("kind",v.kind.name);put("size",v.stamp.sizeBytes);put("modified",v.stamp.modifiedMs);put("sha",v.stamp.sha256);put("folder",v.folderGrantId?.toString())}}))
            }}))
            put("playlists",JsonArray(state.playlists.map{p->buildJsonObject{
                put("id",p.playlist.id.value.toString());put("name",p.playlist.name)
                put("entries",JsonArray(p.entries.map{v->buildJsonObject{put("id",v.id.value.toString());put("track",v.trackId.value.toString());put("rank",v.rank)}}))
            }}))
            put("lyrics",JsonArray(state.lyrics.map{v->buildJsonObject{put("id",v.id.toString());put("track",v.trackId.value.toString());put("text",v.originalText);put("origin",v.origin.name);put("adjustment",v.timingAdjustmentMs);put("provider",v.providerId);put("url",v.sourceUrl);put("instrumental",v.instrumental)}}))
            put("selections",JsonObject(state.selections.mapKeys{it.key.value.toString()}.mapValues{JsonPrimitive(it.value.toString())}))
            put("extras",JsonObject(state.extras.mapKeys{it.key.value.toString()}.mapValues{Json.parseToJsonElement(it.value.encode())}))
            put("resumes",JsonArray(state.resumes.map{v->buildJsonObject{put("track",v.trackId);put("position",v.positionMs);put("completed",v.completed);put("updated",v.updatedAtMs)}}))
            put("folders",JsonArray(state.folders.map{v->buildJsonObject{put("id",v.id.toString());put("uri",v.treeUri);put("flags",v.persistedFlags);put("granted",v.grantedAtMs)}}))
            put("session",state.session?.let{Json.parseToJsonElement(SessionCodec.encode(it))}?:JsonNull)
            put("preferences",Json.parseToJsonElement(PreferenceCodec.encode(state.preferences)))
        }
        return root.toString().toByteArray(Charsets.UTF_8).also{require(it.size<=MAX_DATA){"Backup metadata exceeds 8 MiB"}}
    }
    private fun json(bytes:ByteArray):JsonObject {
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        var depth=0;var quoted=false;var escaped=false
        for(c in text) {
            if(quoted) { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false }
            else when(c) { '"'->quoted=true; '{','['->{depth++;require(depth<=64){"Backup JSON nesting is too deep"}}; '}',']'->depth-- }
        }
        return Json.parseToJsonElement(text).jsonObject
    }
    private fun decode(bytes:ByteArray):AppBackup {
        val r=json(bytes);require(r.num("version") in 1L..2L){"Unsupported backup version"}
        val tracks=r.array("tracks",100000).map{v->val o=v.jsonObject
            Track(TrackId(uuid(o.str("id"))),ModelCodec.tags(o.getValue("tags").toString()),ModelCodec.audio(o.getValue("audio").toString()),
                o.array("sources",100).map{source->val s=source.jsonObject;TrackSource(s.str("uri"),SourceKind.valueOf(s.str("kind")),ContentStamp(s.num("size"),s["modified"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.long,s.opt("sha")),s.opt("folder")?.let(::uuid))},
                Availability.valueOf(o.str("availability")),o.num("addedAt"),o.flag("favorite"))
        }
        val playlists=r.array("playlists",5000).map{v->val o=v.jsonObject;val id=PlaylistId(uuid(o.str("id")))
            BackupPlaylist(Playlist(id,o.str("name")),o.array("entries",Playlists.MAX_ENTRIES).map{entry->val e=entry.jsonObject;PlaylistEntry(EntryId(uuid(e.str("id"))),id,TrackId(uuid(e.str("track"))),e.num("rank"))})}
        val lyrics=r.array("lyrics",100000).map{v->val o=v.jsonObject;SavedLyrics(uuid(o.str("id")),TrackId(uuid(o.str("track"))),o.str("text"),LyricsOrigin.valueOf(o.str("origin")),o.num("adjustment"),o.opt("provider"),o.opt("url"),o.flag("instrumental"))}
        val selections=r.getValue("selections").jsonObject.map{TrackId(uuid(it.key)) to uuid(it.value.jsonPrimitive.content)}.toMap()
        val extras=r.getValue("extras").jsonObject.map{TrackId(uuid(it.key)) to LibraryExtras.decode(it.value.toString())}.toMap()
        val resumes=r.array("resumes",100000).map{v->val o=v.jsonObject;ResumeRow(o.str("track"),o.num("position"),o.flag("completed"),o.num("updated"))}
        val folders=r.array("folders",10000).map{v->val o=v.jsonObject;FolderGrant(uuid(o.str("id")),o.str("uri"),o.num("flags").toInt(),o.num("granted"))}
        val p=r.getValue("preferences").jsonObject
        return AppBackup(tracks,playlists,lyrics,selections,extras,resumes,folders,r["session"]?.takeUnless{it==JsonNull}?.let{SessionCodec.decode(it.toString())},PreferenceCodec.decode(p.toString())).also(::validate)
    }

    fun write(bundle: BackupBundle, output: OutputStream) {
        require(bundle.assets.size<=2000)
        val files=linkedMapOf("data.json" to encode(bundle.state));var total=files.values.sumOf{it.size.toLong()}
        for((name,bytes) in bundle.assets){require(assetName(name)&&bytes.size<=MAX_ASSET&&name=="art/${sha(bytes)}.img"){"Invalid artwork asset"};files[name]=bytes;total+=bytes.size;require(total<=MAX_TOTAL){"Backup exceeds 32 MiB"}}
        val references=artworkReferences(bundle.state)
        require(references==bundle.assets.keys){"Artwork assets do not match saved overrides"}
        val manifest=buildJsonObject{put("format","local-music-backup");put("version",2);put("entries",JsonArray(files.map{(name,bytes)->buildJsonObject{put("name",name);put("length",bytes.size);put("sha256",sha(bytes))}}))}.toString().toByteArray(Charsets.UTF_8)
        ZipOutputStream(output).use{zip->for((name,bytes) in linkedMapOf("manifest.json" to manifest)+files){zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}}
    }
    private fun limited(input:InputStream,max:Int):ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(16384)
        while(true){val n=input.read(buffer);if(n<0)break;require(out.size().toLong()+n<=max){"Backup entry exceeds size limit"};out.write(buffer,0,n)}
        return out.toByteArray()
    }
    fun read(input: InputStream): BackupBundle {
        val compressed=limited(input,MAX_TOTAL+1024*1024)
        require(compressed.size>=22){"Not a complete backup ZIP"}
        // ZipInputStream alone accepts a missing central directory. Require a complete single-disk archive.
        val end=(compressed.size-22 downTo (compressed.size-65557).coerceAtLeast(0)).firstOrNull{i->
            compressed[i]==0x50.toByte()&&compressed[i+1]==0x4b.toByte()&&compressed[i+2]==5.toByte()&&compressed[i+3]==6.toByte()&&
                i+22+(ByteBuffer.wrap(compressed,i+20,2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535)==compressed.size
        }?:throw IllegalArgumentException("Truncated backup ZIP")
        val endRecord=ByteBuffer.wrap(compressed,end,22).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(offset:Int)=endRecord.getShort(end+offset).toInt() and 65535
        require(u16(4)==0&&u16(6)==0&&u16(8)==u16(10)&&u16(10) in 2..2002){"Unsupported ZIP layout"}
        val centralSize=endRecord.getInt(end+12).toLong() and 0xffffffffL
        val centralOffset=endRecord.getInt(end+16).toLong() and 0xffffffffL
        require(centralOffset+centralSize==end.toLong()){ "Invalid ZIP directory" }
        val files=linkedMapOf<String,ByteArray>();var total=0L
        ZipInputStream(ByteArrayInputStream(compressed)).use{zip->while(true){val entry=zip.nextEntry?:break;val name=entry.name
            require(!entry.isDirectory&&(name=="manifest.json"||name=="data.json"||assetName(name))&&!files.containsKey(name)&&files.size<2002){"Unsafe or duplicate archive entry"}
            val bytes=limited(zip,when(name){"manifest.json"->512*1024;"data.json"->MAX_DATA;else->MAX_ASSET});total+=bytes.size
            require(total<=MAX_TOTAL+512*1024){"Backup exceeds 32 MiB"};files[name]=bytes;zip.closeEntry()
        }}
        require(files.size==u16(10)){"Archive entry count mismatch"}
        val manifest=json(requireNotNull(files.remove("manifest.json")){"Backup manifest missing"})
        require(files.values.sumOf { it.size.toLong() } <= MAX_TOTAL) { "Backup payload exceeds 32 MiB" }
        require(manifest.str("format")=="local-music-backup"&&manifest.num("version") in 1L..2L){"Unsupported backup format/version"}
        val listed=manifest.array("entries",2001).map{it.jsonObject};require(listed.map{it.str("name")}.toSet()==files.keys&&listed.size==files.size){"Manifest entry mismatch"}
        listed.forEach{entry->val bytes=files.getValue(entry.str("name"));require(entry.num("length")==bytes.size.toLong()&&entry.str("sha256")==sha(bytes)){"Backup checksum mismatch"}}
        val data=requireNotNull(files.remove("data.json")){"Backup data missing"}
        require(json(data).num("version")==manifest.num("version")){"Backup version mismatch"}
        val state=decode(data)
        require(artworkReferences(state)==files.keys){"Artwork references do not match archive"}
        files.forEach{(name,bytes)->require(name=="art/${sha(bytes)}.img"){"Artwork checksum identity mismatch"}}
        return BackupBundle(state,files)
    }
}
