package com.localmusic.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Private generated fixtures only; registered only in .selftest. */
class SourceFixtureProvider: ContentProvider() {
    class Gate { val entered=CountDownLatch(1);val release=CountDownLatch(1) }
    companion object { val nextOpen=AtomicReference<Gate?>(null) }
    override fun onCreate()=BuildConfig.USB_SELF_TEST
    override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor {
        check(BuildConfig.USB_SELF_TEST && mode=="r")
        nextOpen.getAndSet(null)?.let { it.entered.countDown();check(it.release.await(15,TimeUnit.SECONDS)) { "Fixture open gate timed out" } }
        if(uri.lastPathSegment=="pipe") { val pair=ParcelFileDescriptor.createPipe();pair[1].close();return pair[0] }
        val name=uri.lastPathSegment.orEmpty()
        check(uri.pathSegments.size==1 && name.matches(Regex("stereo-(16|24)-(44100|48000|88200|96000)\\.(flac|wav)")))
        return ParcelFileDescriptor.open(File(requireNotNull(context).filesDir,"source-fixtures/$name"),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri)="application/octet-stream"
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor?=null
    override fun insert(uri: Uri,values: ContentValues?): Uri?=null
    override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?)=0
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?)=0
}
