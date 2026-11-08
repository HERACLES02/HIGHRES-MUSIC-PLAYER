package com.localmusic.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import com.localmusic.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** Only deletes the exact reviewed document, under an existing writable folder grant. */
class SongFileRemoval(private val context:Context,private val store:MusicStore) {
    data class Document(val name:String,val size:Long,val modified:Long?,val flags:Int)
    data class Preview(val token:String,val id:TrackId,val source:TrackSource,val document:Document,val location:String,val sha:String) {
        fun json()=JSONObject().put("token",token).put("name",document.name).put("location",location).put("size",document.size)
    }
    private val resolver get()=context.contentResolver
    private suspend fun checkEdits(id:TrackId) {
        require(store.repository().edits().none{it.trackId==id.value.toString() && it.phase in
            setOf("COPYING","PREPARED","WRITING","UNDO_WRITING","NEEDS_RECOVERY")}) {
            "Finish or discard this song's tag edit or recovery before deleting its file."
        }
    }
    private fun document(uri:Uri):Document {
        require(Docs.isDocumentUri(context,uri)){"Choose the song's folder in Settings before deleting this file."}
        val columns=arrayOf(Docs.Document.COLUMN_DISPLAY_NAME,Docs.Document.COLUMN_SIZE,
            Docs.Document.COLUMN_LAST_MODIFIED,Docs.Document.COLUMN_FLAGS,Docs.Document.COLUMN_MIME_TYPE)
        return requireNotNull(resolver.query(uri,columns,null,null,null)){"File is unavailable."}.use { c ->
            require(c.moveToFirst() && c.getString(4)!=Docs.Document.MIME_TYPE_DIR){"The song file is unavailable."}
            require(!c.isNull(1)){"This provider cannot verify the file size for deletion."}
            val flags=c.getInt(3)
            require(flags and Docs.Document.FLAG_SUPPORTS_DELETE!=0){"This storage provider does not allow file deletion."}
            Document(c.getString(0).orEmpty(),c.getLong(1),if(c.isNull(2))null else c.getLong(2),flags)
        }
    }
    private suspend fun writable(source:TrackSource):Boolean {
        val folder=store.repository().folderGrants().firstOrNull{it.id==source.folderGrantId}?:return false
        return source.kind==SourceKind.DOCUMENT && resolver.persistedUriPermissions.any{
            it.uri.toString()==folder.treeUri && it.isReadPermission && it.isWritePermission
        }
    }
    private fun fingerprint(uri:Uri)=requireNotNull(resolver.openInputStream(uri)){"Cannot read the song file."}.use{FileEdits.sha(it)}
    suspend fun prepare(id:TrackId):Preview=withContext(Dispatchers.IO) {
        checkEdits(id)
        val repo=store.repository()
        val track=requireNotNull(repo.track(id)){"Song is no longer in the library."}
        require(track.availability==Availability.AVAILABLE){"The song file is unavailable. Check storage access and rescan."}
        val source=track.sources.firstOrNull{writable(it)}
            ?:error("Allow read and write access to this music folder in Settings, then try again.")
        val uri=Uri.parse(source.uri)
        val before=document(uri)
        require(before.size==source.stamp.sizeBytes && (source.stamp.modifiedMs==null || before.modified==source.stamp.modifiedMs)) {
            "The file changed since the last scan. Rescan the folder and review deletion again."
        }
        val hash=fingerprint(uri)
        require(document(uri)==before){"The file changed while checking it. Review deletion again."}
        val location=repo.extra(id).canonical[source.uri] ?: source.uri
        Preview(UUID.randomUUID().toString(),id,source,before,location,hash)
    }
    /** Caller holds the store operation mutex and has closed playback for this identity. */
    suspend fun delete(preview:Preview)=withContext(Dispatchers.IO) {
        checkEdits(preview.id)
        val repo=store.repository()
        val track=requireNotNull(repo.track(preview.id))
        require(preview.source in track.sources && writable(preview.source)){"File access changed. Review deletion again."}
        val uri=Uri.parse(preview.source.uri)
        require(document(uri)==preview.document && fingerprint(uri)==preview.sha && document(uri)==preview.document) {
            "The file changed after the preview. Nothing was deleted. Review it again."
        }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val wasHidden=repo.extra(preview.id).hidden
            // Persist removal before the provider call, so a process death cannot redisplay a deleted file.
            repo.updateExtra(preview.id){it.copy(hidden=true)}
            try { check(Docs.deleteDocument(resolver,uri)){"The storage provider did not delete the file."} }
            catch(e:Exception){repo.updateExtra(preview.id){it.copy(hidden=wasHidden)};throw e}
            repo.markUnavailable(preview.id,Availability.MISSING)
        }
    }
}
