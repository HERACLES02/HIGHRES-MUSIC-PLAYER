package com.localmusic.player

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WidgetSnapshot(val id:String="",val title:String="Local Music",val artist:String="Tap to open your music",val artwork:String?=null,val playing:Boolean=false)

/** A projection of the service; never a second queue or playback authority. */
class WidgetUpdates(private val context:Context,scope:CoroutineScope){
    private val pending=Channel<WidgetSnapshot>(Channel.CONFLATED)
    private var last:WidgetSnapshot?=null
    fun publish(snapshot:WidgetSnapshot){if(last!=snapshot){last=snapshot;active=snapshot;pending.trySend(snapshot)}}
    fun invalidate(){last=null}
    init{scope.launch(Dispatchers.IO){for(snapshot in pending)try{
        val json=JSONObject().put("id",snapshot.id).put("title",snapshot.title).put("artist",snapshot.artist).put("artwork",snapshot.artwork)
        val file=AtomicFile(File(context.filesDir,"widget-state"));val output=file.startWrite()
        try{output.write(json.toString().toByteArray());file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}
        refresh(context,snapshot)
    }catch(e:CancellationException){throw e}catch(_:Exception){/* Widget failure must not stop music. */}}}
    companion object{
        @Volatile var active:WidgetSnapshot?=null
        private fun cached(context:Context)=runCatching{
            val o=AtomicFile(File(context.filesDir,"widget-state")).openRead().bufferedReader().use{JSONObject(it.readText())}
            WidgetSnapshot(o.optString("id"),o.optString("title","Local Music"),o.optString("artist"),o.optString("artwork").takeIf{it!="null"&&it.isNotBlank()},false)
        }.getOrDefault(WidgetSnapshot())
        suspend fun refresh(context:Context,snapshot:WidgetSnapshot=active?:cached(context)){
            val prefs=(context.applicationContext as MusicApplication).storage.await().backupPreferences()
            val dark=when(prefs.widgets.theme){"Light"->false;"Dark"->true;else->when(prefs.appearance){"Light"->false;"Dark","AMOLED"->true;else->context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES}}
            val color=Color.parseColor(if(dark)"#f0f1e9" else "#242d24")
            val art=if(prefs.widgets.artwork)snapshot.artwork?.let{path->runCatching{
                val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(path,options)
                require(options.outWidth>0&&options.outHeight>0)
                var sample=1;while(maxOf(options.outWidth,options.outHeight)/sample>256)sample*=2
                BitmapFactory.decodeFile(path,BitmapFactory.Options().apply{inSampleSize=sample})
            }.getOrNull()}else null
            val manager=AppWidgetManager.getInstance(context)
            try{for((provider,layout)in listOf(ArtworkWidget::class.java to R.layout.widget_artwork,CompactWidget::class.java to R.layout.widget_compact)){
                for(id in manager.getAppWidgetIds(ComponentName(context,provider))){
                    val views=RemoteViews(context.packageName,layout)
                    views.setInt(R.id.widget_root,"setBackgroundResource",if(dark)R.drawable.widget_background_dark else R.drawable.widget_background_light)
                    views.setTextViewText(R.id.widget_title,snapshot.title);views.setTextViewText(R.id.widget_artist,snapshot.artist)
                    views.setTextColor(R.id.widget_title,color);views.setTextColor(R.id.widget_artist,color)
                    if(art!=null)views.setImageViewBitmap(R.id.widget_cover,art)else views.setImageViewResource(R.id.widget_cover,R.drawable.ic_local_music)
                    val open=PendingIntent.getActivity(context,id,Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    views.setOnClickPendingIntent(R.id.widget_open,open)
                    for((view,action,res)in listOf(Triple(R.id.widget_play,"toggle",if(snapshot.playing)android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),Triple(R.id.widget_next,"next",android.R.drawable.ic_media_next),Triple(R.id.widget_previous,"previous",android.R.drawable.ic_media_previous))){
                        if(provider==CompactWidget::class.java&&view==R.id.widget_previous)continue
                        val drawable=requireNotNull(context.getDrawable(res)).mutate().apply{setTint(color);setBounds(0,0,48,48)}
                        val bitmap=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);drawable.draw(Canvas(bitmap));views.setImageViewBitmap(view,bitmap)
                        views.setContentDescription(view,if(action=="toggle")if(snapshot.playing)"Pause" else "Play" else action)
                        val intent=Intent(context,WidgetControlReceiver::class.java).setAction("com.localmusic.player.WIDGET_CONTROL").setData(Uri.parse("localmusic://widget/$id/$action")).putExtra("control",action)
                        views.setOnClickPendingIntent(view,if(snapshot.id.isBlank())open else PendingIntent.getBroadcast(context,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    }
                    manager.updateAppWidget(id,views)
                }
            }}finally{art?.recycle()}
        }
    }
}
open class PlaybackWidget:AppWidgetProvider(){
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){update(context)}
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,options:Bundle){update(context)}
    private fun update(context:Context){val completion=goAsync();val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);scope.launch{try{WidgetUpdates.refresh(context)}catch(e:CancellationException){throw e}catch(_:Exception){}finally{completion.finish();scope.cancel()}}}
}
class ArtworkWidget:PlaybackWidget()
class CompactWidget:PlaybackWidget()
class WidgetControlReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val control=intent.getStringExtra("control")?:return
        if(intent.action!="com.localmusic.player.WIDGET_CONTROL"||control!in setOf("toggle","next","previous"))return
        val completion=goAsync();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        scope.launch{
            val connection=MediaController.Builder(context.applicationContext,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()
            var controller:MediaController?=null
            try{withTimeout(8000){
                controller=suspendCancellableCoroutine{continuation->connection.addListener({try{val connected=connection.get();if(continuation.isActive)continuation.resume(connected)else connected.release()}catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}},ContextCompat.getMainExecutor(context))}
                val connected=requireNotNull(controller)
                while(!runCatching{JSONObject(connected.sessionExtras.getString("state").orEmpty()).optBoolean("ready")}.getOrDefault(false))delay(30)
                val result=connected.sendCustomCommand(SessionCommand(PlaybackService.WIDGET_CONTROL,Bundle.EMPTY),Bundle().apply{putString("control",control)})
                suspendCancellableCoroutine<Unit>{continuation->result.addListener({try{result.get();if(continuation.isActive)continuation.resume(Unit)}catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}},ContextCompat.getMainExecutor(context))}
            }}catch(e:CancellationException){throw e}catch(_:Exception){}
            finally{controller?.release();if(controller==null)connection.cancel(false);completion.finish();scope.cancel()}
        }
    }
}
