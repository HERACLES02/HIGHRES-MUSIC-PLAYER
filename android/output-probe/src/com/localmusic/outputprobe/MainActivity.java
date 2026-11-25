package com.localmusic.outputprobe;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Permission-free, foreground-only Android AudioTrack route experiment.
 * Writes digital zeroes with per-track volume zero. No music, USB-host or network APIs. */
public final class MainActivity extends Activity {
    private volatile boolean cancelled;
    private boolean running;
    private TextView status;
    private Button start;
    private JSONObject report;
    private String runId;
    private final AudioAttributes attributes=new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int padding=(int)(20*getResources().getDisplayMetrics().density); content.setPadding(padding,padding,padding,padding);
        TextView title=new TextView(this); title.setText("Android output route check");title.setTextSize(24);content.addView(title);
        TextView description=new TextView(this);
        description.setText("Pause Local Music and other players. Connect the KZ M2, then start.\n\nFive muted tests take about one minute. Stay on this screen. Only silence is generated; no music files or system volume settings are used.\n\nClient format and direct-support hints do not prove a high-resolution HAL route. A separate Android mixer capture checks that.");
        content.addView(description);
        start=new Button(this);start.setText("Start silent route tests");content.addView(start);
        Button stop=new Button(this);stop.setText("Stop");content.addView(stop);
        status=new TextView(this);status.setText("Ready. No output opened.");content.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);setContentView(scroll);
        start.setOnClickListener(v->{if(!running){running=true;cancelled=false;runId=UUID.randomUUID().toString().replace("-","");start.setEnabled(false);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            new Thread(this::runChecks,"android-output-probe").start();}});
        stop.setOnClickListener(v->cancelled=true);
    }
    @Override protected void onStop(){cancelled=true;super.onStop();}

    private AudioDeviceInfo m2() {
        AudioDeviceInfo found=null;
        for(AudioDeviceInfo d:getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if((d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET||d.getType()==AudioDeviceInfo.TYPE_USB_DEVICE)
                &&d.getProductName().toString().contains("KZ Acoustics M2")) {
                if(found!=null)throw new IllegalStateException("Multiple M2 devices; test not started");
                found=d;
            }
        }
        if(found==null)throw new IllegalStateException("Connect the KZ M2 before starting");
        return found;
    }
    private JSONObject device(AudioDeviceInfo d)throws Exception {
        return d==null?null:new JSONObject().put("id",d.getId()).put("type",d.getType())
            .put("product",d.getProductName().toString());
    }
    private void write(String name,String value)throws Exception {
        AtomicFile file=new AtomicFile(new File(getFilesDir(),name));FileOutputStream out=null;
        try {out=file.startWrite();out.write(value.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    private void phase(String value)throws Exception {write("phase.txt",runId+":"+value+"\n");}
    private void save()throws Exception {write("report.json",report.toString(2)+"\n");}
    private void show(String value){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())status.setText(value);});}
    private void runChecks() {
        try {
            phase("starting");
            AudioDeviceInfo target=m2();
            report=new JSONObject().put("schemaVersion",1).put("probeVersion","1.0").put("runId",runId)
                .put("model",Build.MODEL).put("sdk",Build.VERSION.SDK_INT).put("target",device(target))
                .put("scope","Silent public AudioTrack tests. No physical DAC or bit-perfect measurement.")
                .put("directHintScope","On Android 11 this query considers available system paths, not just the selected M2.");
            JSONArray hints=new JSONArray();
            for(int encoding:new int[]{AudioFormat.ENCODING_PCM_FLOAT,AudioFormat.ENCODING_PCM_16BIT})
                for(int rate:new int[]{44100,48000,88200,96000}) {
                    JSONObject hint=new JSONObject().put("encoding",encoding).put("sampleRateHz",rate);
                    try {hint.put("supported",AudioTrack.isDirectPlaybackSupported(format(encoding,rate),attributes));}
                    catch(Exception e){hint.put("error",e.toString());}hints.put(hint);
                }
            report.put("directHints",hints);JSONArray cases=new JSONArray();report.put("cases",cases);save();
            for(int i=0;i<5&&!cancelled;i++) {
                JSONObject result=new JSONObject().put("id","case-"+i);cases.put(result);
                phase("preparing-"+i);
                try {runCase(i,target,result);}catch(Exception e){result.put("error",e.toString());}
                phase("between");save();
            }
            report.put("cancelled",cancelled).put("complete",!cancelled);save();
            phase(cancelled?"cancelled":"complete");
            show(cancelled?"Stopped. Results saved on this helper only.":"Complete. Reconnect the phone to the PC so the results can be collected.");
        }catch(Exception e){show("Test stopped: "+e.getMessage());try{phase("error");}catch(Exception ignored){}}
        finally {runOnUiThread(()->{running=false;if(!isDestroyed()){start.setEnabled(true);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}});}
    }
    private AudioFormat format(int encoding,int rate){return new AudioFormat.Builder().setSampleRate(rate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(encoding).build();}

    private void runCase(int i,AudioDeviceInfo target,JSONObject result)throws Exception {
        if(m2().getId()!=target.getId())throw new IllegalStateException("M2 was reconnected; restart this test");
        int encoding=i==4?AudioFormat.ENCODING_PCM_16BIT:AudioFormat.ENCODING_PCM_FLOAT;
        int frameBytes=i==4?4:8;
        int performance=i==3?AudioTrack.PERFORMANCE_MODE_LOW_LATENCY:AudioTrack.PERFORMANCE_MODE_NONE;
        boolean preferred=i!=0;
        int minimum=AudioTrack.getMinBufferSize(96000,AudioFormat.CHANNEL_OUT_STEREO,encoding);
        int buffer=ProbeBuffer.bytes(minimum,96000,frameBytes,i<2);
        result.put("requestedSampleRateHz",96000).put("requestedEncoding",encoding).put("bufferBytes",buffer)
            .put("minimumBufferBytes",minimum).put("requestedPerformanceMode",performance).put("requestM2",preferred);
        AudioTrack track=null;
        try {
            track=new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format(encoding,96000))
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buffer).setPerformanceMode(performance).build();
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("AudioTrack not initialized");
            if(track.setVolume(0f)!=AudioTrack.SUCCESS)throw new IllegalStateException("Could not mute diagnostic stream");
            if(preferred){boolean accepted=track.setPreferredDevice(target);result.put("preferredDeviceAccepted",accepted);
                if(!accepted)throw new IllegalStateException("Android rejected the preferred M2 device");}
            result.put("clientSampleRateHz",track.getSampleRate()).put("clientEncoding",track.getAudioFormat())
                .put("clientCapacityFrames",track.getBufferCapacityInFrames()).put("actualPerformanceMode",track.getPerformanceMode());
            ByteBuffer zeros=ByteBuffer.allocateDirect(frameBytes*960);
            track.play();long started=SystemClock.elapsedRealtime(),written=0;boolean marked=false;
            while(!cancelled&&SystemClock.elapsedRealtime()-started<12000) {
                AudioDeviceInfo routed=track.getRoutedDevice();
                if(routed!=null&&routed.getId()!=target.getId())throw new IllegalStateException("Stream routed away from M2");
                zeros.clear();int n=track.write(zeros,zeros.remaining(),AudioTrack.WRITE_NON_BLOCKING);
                if(n<0)throw new IllegalStateException("AudioTrack write error "+n);written+=n;
                if(!marked&&routed!=null&&written>0){result.put("routedDevice",device(routed));
                    result.put("startedAtUtc",java.time.Instant.now().toString());phase("case-"+i);marked=true;
                    show("Silent test "+(i+1)+" of 5. Stay on this screen.\n"+(i==4?"16-bit control":"Float / 96 kHz")+"; preferred M2="+preferred+"; buffer="+buffer);}
                if(!marked&&SystemClock.elapsedRealtime()-started>2000)throw new IllegalStateException("Active M2 route not confirmed");
                Thread.sleep(10);
            }
            result.put("writtenFrames",written/frameBytes).put("playbackHeadFrames",track.getPlaybackHeadPosition())
                .put("underruns",track.getUnderrunCount()).put("finalRoutedDevice",device(track.getRoutedDevice()))
                .put("elapsedMs",SystemClock.elapsedRealtime()-started);
        }finally{if(track!=null){try{track.pause();track.flush();}finally{track.release();}}}
    }
}
