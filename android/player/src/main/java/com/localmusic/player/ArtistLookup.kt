package com.localmusic.player

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.localmusic.core.*
import com.localmusic.data.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ArtistReview(val receipt:String,val profile:ArtistProfile,val image:String?)
data class CoverReview(val receipt:String,val release:ReleaseCandidate,val image:String,val sourceUrl:String,val credit:String)

/** Downloads are explicit previews. Only service commands persist a user's selection. */
class ArtistLookup(private val context:Context,private val store:MusicStore){
    private fun cooldown(name:String)=object:LyricsCooldown{
        private val file=AtomicFile(File(context.filesDir,name))
        override fun read():Long=try{file.openRead().bufferedReader().use{it.readText().toLong()}}catch(_:java.io.FileNotFoundException){0}
        override fun write(untilMs:Long){val output=file.startWrite();try{output.write(untilMs.toString().toByteArray());file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}}
    }
    private val client=EnrichmentClient(cooldown("artist-provider-cooldown"))
    private val wikipediaClient=EnrichmentClient(cooldown("wikipedia-provider-cooldown"))
    private val artists=ConcurrentHashMap<String,ArtistCandidate>()
    private val releases=ConcurrentHashMap<String,ReleaseCandidate>()
    private val artistReviews=ConcurrentHashMap<String,ArtistReview>()
    private val coverReviews=ConcurrentHashMap<String,CoverReview>()
    suspend fun searchArtists(query:String,wikipedia:Boolean=false):List<ArtistCandidate> = withContext(Dispatchers.IO){
        val found=runInterruptible{if(wikipedia)wikipediaClient.searchWikipediaArtists(query)else client.searchArtists(query)};artists.clear();artists.putAll(found.associateBy{it.id});found
    }
    suspend fun searchCovers(artist:String,album:String):List<ReleaseCandidate> = withContext(Dispatchers.IO){
        val found=runInterruptible{client.searchReleases(artist,album)};releases.clear();releases.putAll(found.associateBy{it.id});found
    }
    private fun previewImage(bytes:ByteArray):String {
        val file=File.createTempFile("provider-image-",".tmp",context.cacheDir)
        try{
            file.writeBytes(bytes);val path=LocalArtwork(context).resize(file,false,preview=true)
            val directory=File(context.cacheDir,"artist-previews").canonicalFile
            val retained=(artistReviews.values.mapNotNull{it.image}+coverReviews.values.map{it.image}+path).toSet()
            var total=directory.listFiles().orEmpty().sumOf{it.length()}
            for(old in directory.listFiles().orEmpty().sortedBy{it.lastModified()}){
                if(total<=32L*1024*1024)break
                if(old.canonicalFile.parentFile==directory&&old.absolutePath !in retained){val size=old.length();if(old.delete())total-=size}
            }
            require(total<=32L*1024*1024){"Artist preview cache is full. Close a preview and try again."}
            return path
        }finally{file.delete()}
    }
    suspend fun previewArtist(id:String):ArtistReview=withContext(Dispatchers.IO){
        val loaded=runInterruptible{(if(id.startsWith("wikipedia:"))wikipediaClient else client).artist(requireNotNull(artists[id]){"Artist result expired. Search again."})}
        val review=ArtistReview(UUID.randomUUID().toString(),loaded.profile,loaded.image?.let(::previewImage))
        if(artistReviews.size>=4)artistReviews.clear();artistReviews[review.receipt]=review;review
    }
    suspend fun previewCover(id:String):CoverReview=withContext(Dispatchers.IO){
        val loaded=runInterruptible{client.cover(requireNotNull(releases[id]){"Release result expired. Search again."})}
        val review=CoverReview(UUID.randomUUID().toString(),loaded.release,previewImage(loaded.image),loaded.sourceUrl,loaded.credit)
        if(coverReviews.size>=4)coverReviews.clear();coverReviews[review.receipt]=review;review
    }
    private fun durableArtistImage(path:String):String {
        val source=File(path);require(source.canonicalFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        val directory=File(context.filesDir,"artist-images").apply{mkdirs()}
        val target=File(directory,source.name)
        if(!target.exists()){
            require(directory.listFiles().orEmpty().sumOf{it.length()}+source.length()<=32L*1024*1024){"Artist image storage is full. Remove an unused saved artist match."}
            val staged=File.createTempFile("chosen-",".tmp",directory)
            try{source.inputStream().use{input->java.io.FileOutputStream(staged).use{out->input.copyTo(out);out.fd.sync()}};check(staged.renameTo(target))}finally{staged.delete()}
        }
        return target.absolutePath
    }
    private suspend fun tidyArtistImages(){
        val retained=store.backupPreferences().artists.values.mapNotNull{it.imagePath}.toSet()
        val directory=File(context.filesDir,"artist-images").canonicalFile
        directory.listFiles().orEmpty().filter{it.canonicalFile.parentFile==directory&&it.absolutePath !in retained}.forEach{it.delete()}
    }
    suspend fun saveArtist(name:String,receipt:String)=withContext(Dispatchers.IO){
        val review=requireNotNull(artistReviews[receipt]){"Preview expired. Review the artist again."}
        val profile=review.profile.copy(imagePath=review.image?.let(::durableArtistImage),savedAtMs=System.currentTimeMillis())
        store.setArtist(name,profile);artistReviews.remove(receipt);tidyArtistImages()
    }
    suspend fun removeArtist(name:String)=withContext(Dispatchers.IO){store.setArtist(name,null);tidyArtistImages()}
    suspend fun saveNote(name:String,text:String)=withContext(Dispatchers.IO){
        require(text.length<=6000)
        val old=store.backupPreferences().artists[ArtistProfile.key(name)]?:ArtistProfile(UUID(0,0).toString(),name)
        store.setArtist(name,old.copy(biography=text,biographyLicense=old.biographyLicense?.take(1800)?.plus(" · edited")?:"Your note",savedAtMs=System.currentTimeMillis()))
    }
    suspend fun savePhoto(name:String,uri:Uri)=withContext(Dispatchers.IO){
        val path=LocalArtwork(context).import(uri,false,durable=false,preview=true)
        run{
            val chosen=durableArtistImage(path)
            val old=store.backupPreferences().artists[ArtistProfile.key(name)]?:ArtistProfile(UUID(0,0).toString(),name)
            store.setArtist(name,old.copy(imagePath=chosen,imageUrl=null,imageCredit="Your selected image",imageLicense="Local image",imageSourceUrl=null,savedAtMs=System.currentTimeMillis()))
            tidyArtistImages()
        }
    }
    suspend fun saveCover(ids:List<TrackId>,receipt:String)=withContext(Dispatchers.IO){
        val review=requireNotNull(coverReviews[receipt]){"Cover preview expired. Review it again."}
        require(ids.isNotEmpty()&&ids.size<=5000&&ids.distinct().size==ids.size)
        val repo=store.repository();require(ids.all{repo.track(it)!=null}){"A song is no longer in this library."}
        val path=LocalArtwork(context).resize(File(review.image),true)
        repo.saveAppArtwork(ids,path,review.sourceUrl,review.credit)
        coverReviews.remove(receipt);store.refresh()
    }
}
