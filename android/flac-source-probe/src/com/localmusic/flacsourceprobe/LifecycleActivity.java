package com.localmusic.flacsourceprobe;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.AtomicFile;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Test-only lifecycle: background stops; explicit command resumes. No service,
// wake lock, audio, USB, persistent permission or player interaction.
public final class LifecycleActivity extends Activity {
    private final LifecyclePolicy policy=new LifecyclePolicy();
    private final ExecutorService control=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor();
    private final String session=UUID.randomUUID().toString().replace("-","");
    private final JSONObject report=new JSONObject();
    private final JSONArray samples=new JSONArray(),checks=new JSONArray(),rows=new JSONArray();
    private volatile boolean destroyed=false;
    private String phase="initializing",folderRelative;
    private File folder;
    private long handle=0;
    private Thread consumer;
    private byte[] pcm16,pcm24,pattern;
    private TextView view;
    private int sequence=0,cycles=0;
    private boolean backgroundTest=false;
    private void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private void post(Runnable action){if(!destroyed)control.execute(()->{try{action.run();}catch(Throwable error){fail(error);}});}
    private void checked(Checked action){try{action.run();}catch(Throwable error){fail(error);}}
    private interface Checked{void run()throws Exception;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);view=new TextView(this);view.setPadding(24,24,24,24);setContentView(view);
        control.execute(()->checked(()->{
            handle=MainActivity.create();
            if(journal().exists() || saved!=null || "recover".equals(getIntent().getStringExtra("command")))recover();
            else if("prepare_kill".equals(getIntent().getStringExtra("command"))){prepare();startLong(true,"waiting_process_death");}
            else runInitial();
        }));
        sampler.scheduleAtFixedRate(()->{if(!destroyed && policy.requestSample())try{
            post(()->{try{checked(()->sample("periodic",false));}finally{policy.sampleFinished();}});
        }catch(java.util.concurrent.RejectedExecutionException ignored){policy.sampleFinished();}},1,1,TimeUnit.SECONDS);
    }
    @Override protected void onStart(){super.onStart();policy.foreground();post(()->checked(()->{
        if("background_stopped".equals(phase)){require(MainActivity.isStopped(handle),"foreground return stays stopped");checks.put("foreground-no-auto-resume");phase="resumed_still_stopped";sample("foreground_stopped",true);}
    }));}
    @Override protected void onStop(){super.onStop();if(policy.background())post(()->checked(()->{
        stopSource();require(MainActivity.isStopped(handle) && MainActivity.heldStale(handle),"background retires held source");
        checks.put("background-stop-join-stale-consumer");phase="background_stopped";backgroundTest=true;sample("background_stopped",true);
    }));}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);String command=intent.getStringExtra("command");
        if("recreate".equals(command)){recreate();return;}
        post(()->checked(()->{
            if("resume".equals(command)){require(backgroundTest && "resumed_still_stopped".equals(phase),"explicit resume follows stopped foreground");startLong(true,"paced_resumed");checks.put("explicit-resume-new-source");}
            else if("prepare_kill".equals(command)){prepare();startLong(true,"waiting_process_death");}
            else if("finish".equals(command)){stopSource();cleanup();checks.put("explicit-stop-cleanup");phase="passed";sample("finished",true);}
        }));
    }
    @Override protected void onDestroy(){destroyed=true;sampler.shutdownNow();control.execute(()->{
        try{stopSource();}catch(Exception ignored){}if(handle!=0){MainActivity.destroy(handle);handle=0;}
    });control.shutdown();super.onDestroy();}
    private File journal(){return new File(getFilesDir(),"lifecycle-journal.json");}
    private byte[] asset(String name)throws Exception {
        try(InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] bytes=new byte[16384];int n;while((n=in.read(bytes))!=-1){require(out.size()+n<=1024*1024,"small asset bound");out.write(bytes,0,n);}return out.toByteArray();}
    }
    private void saveJournal()throws Exception {
        AtomicFile target=new AtomicFile(journal());FileOutputStream out=target.startWrite();
        try{out.write(new JSONObject().put("rows",rows).put("folder",folder.getAbsolutePath()).put("relative",folderRelative).toString().getBytes("UTF-8"));target.finishWrite(out);}
        catch(Throwable error){target.failWrite(out);throw error;}
    }
    private void prepare()throws Exception {
        require(rows.length()==0 && !journal().exists(),"no unfinished generated rows");
        pcm16=asset("16-44100.pcm");pcm24=asset("24-96000.pcm");pattern=asset("long.pattern");
        report.put("fixtures",new JSONObject(new String(asset("lifecycle-fixtures.json"),"UTF-8")));
        StorageVolume sd=null;for(StorageVolume volume:getSystemService(StorageManager.class).getStorageVolumes())
            if(volume.isRemovable() && Environment.MEDIA_MOUNTED.equals(volume.getState())){sd=volume;break;}
        require(sd!=null && sd.getDirectory()!=null,"removable SD available");
        folderRelative="Download/FlacLifecycle-"+session+"/";folder=new File(sd.getDirectory(),folderRelative);
        for(String name:new String[]{"16-44100.flac","24-96000.flac","long-24-96000.flac"}){
            ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,"generated-"+name+".bin");
            values.put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");values.put(MediaStore.MediaColumns.RELATIVE_PATH,folderRelative);values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri uri=getContentResolver().insert(MediaStore.Downloads.getContentUri(sd.getMediaStoreVolumeName()),values);require(uri!=null,"create pending lifecycle fixture");rows.put(uri.toString());saveJournal();
            try(InputStream in=getAssets().open(name);OutputStream out=getContentResolver().openOutputStream(uri,"w")){
                require(out!=null,"open generated SD output");byte[] bytes=new byte[16384];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}
        }
        checks.put("three-owned-sd-provider-fixtures-streamed-no-long-pcm-array");sample("prepared",true);
    }
    private long startRow(int index,int rate,int bits,long first)throws Exception {
        require(policy.requestStart(),"no starts while backgrounded");
        long source;try(ParcelFileDescriptor caller=getContentResolver().openFileDescriptor(Uri.parse(rows.getString(index)),"r")){
            require(caller!=null,"provider caller fd");source=MainActivity.start(handle,caller.getFd(),rate,bits,first);}
        require(MainActivity.heldStale(handle),"old consumer block rejected after source replacement");return source;
    }
    private void cycle(int count)throws Exception {
        for(int i=0;i<count;++i){boolean high=cycles%2!=0;int rate=high?96000:44100,bits=high?24:16;byte[] reference=high?pcm24:pcm16;
            long first=cycles%3==0?0:5001;long source=startRow(high?1:0,rate,bits,first);
            int frames=MainActivity.take(handle,source,reference,bits/8*2,first);require(frames>0,"current independent prefix");
            require(MainActivity.drain(handle,source,reference,bits/8*2,first+frames,first==0),"full provider suffix and integrity");
            MainActivity.stop(handle);policy.stopped();require(MainActivity.heldStale(handle),"held block invalid after explicit stop");++cycles;
        }
    }
    private void runInitial()throws Exception {
        prepare();cycle(10);sample("warm10",true);cycle(50);sample("after60",true);cycle(50);checks.put("110-provider-open-seek-format-switch-stop-cycles");sample("after110",true);
        startLong(false,"long_fast");
    }
    private void startLong(boolean paced,String nextPhase)throws Exception {
        require(consumer==null || !consumer.isAlive(),"previous consumer joined");
        long source=startRow(2,96000,24,0);phase=nextPhase;sample("long_start_"+nextPhase,true);
        consumer=new Thread(()->{long[] result=MainActivity.stream(handle,source,pattern,0,96000L*1200,paced);
            if(!destroyed)post(()->checked(()->{
                if(result==null || result[0]==2)throw new IllegalStateException("long stream PCM/state failure");
                if(result[0]==0){require(result[1]==96000L*1200 && result[2]==691200000L,"complete long-source PCM count");
                    stopSource();policy.stopped();checks.put("20-minute-nonzero-source-exact-pattern-md5-bounded-stream");sample("long_complete",true);
                    startLong(true,"waiting_background");}
            }));
        },"PacedSourceConsumer");consumer.start();write();
    }
    private void stopSource()throws Exception {
        if(handle==0)return;MainActivity.stop(handle);policy.stopped();
        if(consumer!=null){consumer.join(5000);require(!consumer.isAlive(),"consumer joins after source retirement");consumer=null;}
    }
    private void recover()throws Exception {
        MainActivity.stop(handle);policy.stopped();boolean had=journal().exists();
        if(had){JSONObject saved=new JSONObject(new String(java.nio.file.Files.readAllBytes(journal().toPath()),"UTF-8"));
            JSONArray savedRows=saved.getJSONArray("rows");for(int i=0;i<savedRows.length();++i)rows.put(savedRows.getString(i));
            folder=new File(saved.getString("folder"));folderRelative=saved.getString("relative");cleanup();}
        require(MainActivity.isStopped(handle) && MainActivity.consumed(handle)==0,"new activity/process never auto-resumes native work");
        report.put("recoveredJournal",had);checks.put("recovery-cleans-generated-rows-without-auto-resume");phase="recovered_stopped";sample("recovered_stopped",true);
    }
    private void cleanup()throws Exception {
        int removed=0;
        for(int i=0;i<rows.length();++i){Uri uri=Uri.parse(rows.getString(i));require("media".equals(uri.getAuthority()) && uri.getPath().contains("/downloads/"),"journal URI scope");
            try(Cursor cursor=getContentResolver().query(uri,new String[]{MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.DISPLAY_NAME},null,null,null)){
                if(cursor!=null && cursor.moveToFirst())require(folderRelative.equals(cursor.getString(0)) && cursor.getString(1).startsWith("generated-") && cursor.getString(1).endsWith(".bin"),"journal row belongs to generated run");}
            removed+=getContentResolver().delete(uri,null,null);}
        if(folder!=null){require(folder.getName().matches("FlacLifecycle-[0-9a-f]{32}"),"generated directory name");folder.delete();require(!folder.exists(),"generated directory removed");}
        new AtomicFile(journal()).delete();while(rows.length()>0)rows.remove(rows.length()-1);report.put("cleanupRowsDeleted",removed).put("generatedFolderAbsent",folder==null || !folder.exists());
    }
    private long statusValue(String key)throws Exception {
        try(java.io.BufferedReader in=new java.io.BufferedReader(new java.io.FileReader("/proc/self/status"))){String line;while((line=in.readLine())!=null)if(line.startsWith(key+":"))return Long.parseLong(line.replaceAll("[^0-9]",""));}return 0;
    }
    private void sample(String label,boolean checkpoint)throws Exception {
        if(destroyed || handle==0)return;
        if(checkpoint){System.gc();System.runFinalization();}
        Debug.MemoryInfo memory=new Debug.MemoryInfo();Debug.getMemoryInfo(memory);Runtime runtime=Runtime.getRuntime();
        JSONObject point=new JSONObject().put("label",label).put("elapsedMs",SystemClock.elapsedRealtime()).put("cycles",cycles)
            .put("frames",MainActivity.consumed(handle)).put("stopped",MainActivity.isStopped(handle))
            .put("javaUsedBytes",runtime.totalMemory()-runtime.freeMemory()).put("javaCommittedBytes",runtime.totalMemory())
            .put("nativeDebugAllocatedBytes",Debug.getNativeHeapAllocatedSize()).put("rssKiB",statusValue("VmRSS"))
            .put("rssHighWaterKiB",statusValue("VmHWM")).put("pssKiB",memory.getTotalPss()).put("fdCount",new File("/proc/self/fd").list().length)
            .put("threadCount",new File("/proc/self/task").list().length);
        if(checkpoint){
            JSONArray descriptors=new JSONArray();String[] names=new File("/proc/self/fd").list();require(names!=null && names.length<256,"descriptor snapshot bound");
            for(String fd:names)try{descriptors.put(new JSONObject().put("fd",fd).put("target",android.system.Os.readlink("/proc/self/fd/"+fd)));}catch(android.system.ErrnoException closed){}
            point.put("descriptors",descriptors);
            String name="allocator-"+session+"-"+(sequence++)+".xml";require(MainActivity.allocator(new File(getFilesDir(),name).getAbsolutePath()),"allocator XML snapshot");point.put("allocator",name);
        }
        require(samples.length()<512,"measurement sample count cap");samples.put(point);write();
    }
    private void write()throws Exception {
        if(destroyed)return;
        report.put("state",phase).put("session",session).put("pid",android.os.Process.myPid()).put("cycles",cycles).put("checks",checks).put("samples",samples)
            .put("sourceStopped",handle==0 || MainActivity.isStopped(handle)).put("frames",handle==0?0:MainActivity.consumed(handle));
        AtomicFile target=new AtomicFile(new File(getFilesDir(),"lifecycle-report.json"));FileOutputStream out=target.startWrite();
        try{out.write(report.toString(2).getBytes("UTF-8"));target.finishWrite(out);}catch(Throwable e){target.failWrite(out);throw e;}
        runOnUiThread(()->{if(!destroyed)view.setText("Silent source lifecycle test\n"+phase+"\n"+cycles+" replacements\nNo audio output.");});
    }
    private void fail(Throwable error){try{stopSource();cleanup();report.put("error",error.toString());phase="failed";write();}catch(Throwable ignored){}}
}
