package com.localmusic.flacsourceprobe;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity {
    static { System.loadLibrary("flac_source_probe"); }
    static native long create();
    static native void destroy(long handle);
    static native long start(long handle,int fd,int rate,int bits,long first);
    static native void stop(long handle);
    private static native boolean retired(long handle,long source);
    private static native boolean expectFailed(long handle,long source);
    static native boolean drain(long handle,long source,byte[] reference,int width,long first,boolean md5);
    static native int take(long handle,long source,byte[] reference,int width,long first);
    static native boolean heldStale(long handle);
    static native boolean isStopped(long handle);
    static native long consumed(long handle);
    static native boolean allocator(String path);
    static native long[] stream(long handle,long source,byte[] pattern,long first,long wanted,boolean paced);
    private final JSONObject report=new JSONObject();
    private final JSONArray checks=new JSONArray();
    private TextView text;
    private Uri media,document;
    private File generatedFolder;
    private String displayName,documentId;
    private byte[] flac,pcm;
    private final String tag=UUID.randomUUID().toString().replace("-","");
    private void require(boolean value,String name){if(!value)throw new IllegalStateException(name);}
    private void pass(String name)throws Exception{checks.put(name);write("running",null);}
    private void write(String state,Throwable error)throws Exception {
        report.put("state",state).put("checks",checks).put("model",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT);
        if(error!=null)report.put("error",error.toString());
        try(FileOutputStream out=openFileOutput("report.json",MODE_PRIVATE)){out.write(report.toString(2).getBytes("UTF-8"));}
        runOnUiThread(()->text.setText("Silent FLAC file test\n"+state+"\n"+checks.length()+" checks\n"+(error==null?"Generated fixtures only. No audio output.":error.toString())));
    }
    private byte[] asset(String name)throws Exception {
        try(InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] chunk=new byte[8192];int n;
            while((n=in.read(chunk))!=-1){require(out.size()+n<=1024*1024,"fixture size cap");out.write(chunk,0,n);}return out.toByteArray();
        }
    }
    @Override public void onCreate(Bundle saved){super.onCreate(saved);text=new TextView(this);text.setPadding(24,24,24,24);setContentView(text);
        new Thread(()->{try{initial();}catch(Throwable error){finishReport(error);}},"FlacFileProbe").start();}

    private void decode(Uri uri,int rate,int bits,byte[] reference,long first,boolean md5)throws Exception {
        long handle=create();
        try {
            long source;
            try(ParcelFileDescriptor caller=getContentResolver().openFileDescriptor(uri,"r")){
                require(caller!=null,"provider descriptor");source=start(handle,caller.getFd(),rate,bits,first);
            }
            require(drain(handle,source,reference,bits/8*2,first,md5),"exact provider PCM/seek/MD5 after caller closes");
        } finally {destroy(handle);}
    }
    private void initial()throws Exception {
        flac=asset("24-96000.flac");pcm=asset("24-96000.pcm");write("running",null);
        File local=new File(getFilesDir(),"generated.flac");
        try(OutputStream out=new FileOutputStream(local)){out.write(flac);}
        long handle=create();
        try {
            long source;
            try(ParcelFileDescriptor caller=ParcelFileDescriptor.open(local,ParcelFileDescriptor.MODE_READ_ONLY)){source=start(handle,caller.getFd(),96000,24,0);}
            require(drain(handle,source,pcm,6,0,true),"private regular file descriptor");
        } finally {destroy(handle);require(local.delete(),"remove private generated file");}
        pass("internal-file-caller-close-exact-pcm");
        proxy(false,false);proxy(true,false);proxy(false,true);pipe();
        StorageManager storage=getSystemService(StorageManager.class);StorageVolume sd=null;
        for(StorageVolume volume:storage.getStorageVolumes())if(volume.isRemovable() && Environment.MEDIA_MOUNTED.equals(volume.getState())){sd=volume;break;}
        require(sd!=null && sd.getDirectory()!=null && sd.getMediaStoreVolumeName()!=null && sd.getUuid()!=null,"mounted removable SD volume");
        String folder="FlacSourceProbe-"+tag;displayName="generated-flac-"+tag+".bin";
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/"+folder+"/");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        media=getContentResolver().insert(MediaStore.Downloads.getContentUri(sd.getMediaStoreVolumeName()),values);require(media!=null,"create own pending SD fixture");
        generatedFolder=new File(sd.getDirectory(),"Download/"+folder);
        try(OutputStream out=getContentResolver().openOutputStream(media,"w")){require(out!=null,"write SD fixture");out.write(flac);}
        report.put("generatedFolder",generatedFolder.getAbsolutePath()).put("generatedName",displayName).put("pendingMediaUri",media.toString());
        decode(media,96000,24,pcm,0,true);pass("sd-mediastore-provider-caller-close-exact-pcm");
        decode(media,96000,24,pcm,5001,false);pass("sd-mediastore-provider-sample-exact-seek");
        documentId=sd.getUuid()+":Download/"+folder+"/"+displayName;
        document=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",documentId);
        boolean denied=false;
        try(ParcelFileDescriptor ignored=getContentResolver().openFileDescriptor(document,"r")){}
        catch(SecurityException expected){denied=true;}
        require(denied,"document permission missing before picker grant");pass("missing-document-grant-rejected");
        ContentValues visible=new ContentValues();visible.put(MediaStore.MediaColumns.IS_PENDING,0);
        require(getContentResolver().update(media,visible,null,null)==1,"publish generic binary fixture to picker");
        Uri directory=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",sd.getUuid()+":Download/"+folder);
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(DocumentsContract.EXTRA_INITIAL_URI,directory);
        write("awaiting_generated_file_picker",null);runOnUiThread(()->startActivityForResult(picker,42));
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request!=42)return;
        new Thread(()->{try{
            require(result==RESULT_OK && data!=null && data.getData()!=null,"generated document selection");Uri selected=data.getData();
            require("com.android.externalstorage.documents".equals(selected.getAuthority()) && documentId.equals(DocumentsContract.getDocumentId(selected)),"only generated SD document may be opened");
            try(Cursor cursor=getContentResolver().query(selected,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
                require(cursor!=null && cursor.moveToFirst() && displayName.equals(cursor.getString(0)),"selected fixture identity");}
            decode(selected,96000,24,pcm,0,true);pass("sd-external-documents-provider-grant-exact-pcm");
            decode(selected,96000,24,pcm,5001,false);pass("sd-external-documents-provider-grant-exact-seek");
            finishReport(null);
        }catch(Throwable error){finishReport(error);}},"FlacDocumentProbe").start();
    }
    private final class Proxy extends ProxyFileDescriptorCallback {
        final boolean delayed,error;final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
        final AtomicInteger releases=new AtomicInteger();
        Proxy(boolean delayed,boolean error){this.delayed=delayed;this.error=error;}
        @Override public long onGetSize(){return flac.length;}
        @Override public int onRead(long offset,int size,byte[] output)throws ErrnoException {
            if(error)throw new ErrnoException("generated EIO",OsConstants.EIO);
            if(delayed){entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new ErrnoException("gate timeout",OsConstants.ETIMEDOUT);}catch(InterruptedException e){throw new ErrnoException("interrupted",OsConstants.EINTR);}}
            int count=(int)Math.min(size,Math.max(0,flac.length-offset));if(count>0)System.arraycopy(flac,(int)offset,output,0,count);return count;
        }
        @Override public void onRelease(){releases.incrementAndGet();closed.countDown();}
    }
    private void proxy(boolean delayed,boolean error)throws Exception {
        HandlerThread handler=new HandlerThread("GeneratedProxyFD");handler.start();Proxy proxy=new Proxy(delayed,error);long handle=create();Thread stopper=null;
        try {
            long source;
            try(ParcelFileDescriptor caller=getSystemService(StorageManager.class).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,proxy,new Handler(handler.getLooper()))){
                source=start(handle,caller.getFd(),96000,24,0);
            }
            if(delayed){
                require(proxy.entered.await(5,TimeUnit.SECONDS),"actual proxy read entered");require(proxy.releases.get()==0,"native duplicate retains provider after caller close");
                AtomicReference<Throwable> stopError=new AtomicReference<>();CountDownLatch stopping=new CountDownLatch(1),done=new CountDownLatch(1);
                stopper=new Thread(()->{stopping.countDown();try{stop(handle);}catch(Throwable t){stopError.set(t);}finally{done.countDown();}},"StopBlockedSource");stopper.start();
                require(stopping.await(2,TimeUnit.SECONDS) && retired(handle,source),"stop invalidates while actual seekable provider read is pending");
                require(done.getCount()==1 && proxy.releases.get()==0,"join waits without closing descriptor during read");
                proxy.release.countDown();stopper.join(5000);require(!stopper.isAlive() && stopError.get()==null,"stop completes after provider read returns");
            }else if(error)require(expectFailed(handle,source),"provider EIO maps to Failed");
            else require(drain(handle,source,pcm,6,0,true),"seekable proxy exact PCM");
            require(proxy.closed.await(5,TimeUnit.SECONDS) && proxy.releases.get()==1,"exactly one final provider release");
        }finally{proxy.release.countDown();if(stopper!=null)stopper.join(5000);destroy(handle);handler.quitSafely();handler.join(5000);}
        pass(delayed?"actual-seekable-proxy-blocked-read-stop-join-release":error?"actual-proxy-eio-fails-cleans-up":"actual-seekable-proxy-caller-close-exact-pcm");
    }
    private void pipe()throws Exception {
        ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();long handle=create();
        try{long source=start(handle,pipe[0].getFd(),96000,24,0);pipe[0].close();require(expectFailed(handle,source),"pipe rejected without reading withheld data");}
        finally{destroy(handle);pipe[0].close();pipe[1].close();}
        pass("nonseekable-withheld-data-provider-rejected-without-block");
    }
    private void finishReport(Throwable error){
        try{
            if(media!=null){int removed=getContentResolver().delete(media,null,null);require(removed==1,"remove generated pending media row");media=null;report.put("generatedMediaDeleted",true);}
            if(generatedFolder!=null){File file=new File(generatedFolder,displayName);require(!file.exists(),"generated SD file removed");generatedFolder.delete();report.put("generatedFileAbsent",true);}
            write(error==null?"passed":"failed",error);
        }catch(Throwable cleanup){try{write("failed",cleanup);}catch(Exception ignored){}}
    }
}
