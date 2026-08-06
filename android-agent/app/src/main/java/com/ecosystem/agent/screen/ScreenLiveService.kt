package com.ecosystem.agent.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.ecosystem.agent.config.AgentPreferences
import com.ecosystem.agent.service.EcosystemAgentService
import com.ecosystem.agent.net.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream

class ScreenLiveService : Service() {
    companion object { const val ACTION_START="screen.live.start"; const val ACTION_STOP="screen.live.stop"; private const val CHANNEL="screen_live"; private const val NOTIFICATION=1202 }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var socket: WebSocket? = null
    private var thread: HandlerThread? = null
    private var lastFrameAt = 0L
    private var stopping = false
    private var sessionId = ""
    private var streamStarted = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate(){super.onCreate();getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Live screen",NotificationManager.IMPORTANCE_LOW))}
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startIntent=intent?:return START_NOT_STICKY
        if(startIntent.action==ACTION_STOP){stopSelf();return START_NOT_STICKY}
        val stop=PendingIntent.getService(this,0,Intent(this,ScreenLiveService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Live screen sharing").setContentText("Streaming display to your Arch hub").setOngoing(true).addAction(android.R.drawable.ic_media_pause,"Stop",stop).build()
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)else startForeground(NOTIFICATION,note)
        @Suppress("DEPRECATION") val data=if(Build.VERSION.SDK_INT>=33)startIntent.getParcelableExtra(ScreenRecordingService.EXTRA_RESULT_DATA,Intent::class.java)else startIntent.getParcelableExtra(ScreenRecordingService.EXTRA_RESULT_DATA)
        if(data==null){stopSelf();return START_NOT_STICKY}
        val session=startIntent.getStringExtra(ScreenCaptureActivity.EXTRA_SESSION_ID)?:return START_NOT_STICKY
        sessionId=session
        runCatching{startLive(startIntent.getIntExtra(ScreenRecordingService.EXTRA_RESULT_CODE,0),data,session)}.onFailure{stopSelf()}
        return START_NOT_STICKY
    }
    private fun startLive(resultCode:Int,data:Intent,session:String){
        val metrics=DisplayMetrics();@Suppress("DEPRECATION")(getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width=((metrics.widthPixels*.55).toInt()/2)*2;val height=((metrics.heightPixels*.55).toInt()/2)*2
        val prefs=AgentPreferences(this);val base=prefs.transferBaseUrl().replace("http://","ws://").replace("https://","wss://")+"/stream"
        socket=OkHttpClient().newWebSocket(Request.Builder().url("$base/source/${prefs.deviceId()}/$session").build(),object:WebSocketListener(){override fun onOpen(webSocket:WebSocket,response:Response){createProjection(resultCode,data,width,height,metrics.densityDpi,webSocket)};override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){EcosystemAgentService.reportSessionState(session,SessionState.FAILED,t.message);stopSelf()}})
    }
    private fun createProjection(resultCode:Int,data:Intent,width:Int,height:Int,dpi:Int,ws:WebSocket){
        thread=HandlerThread("EcosystemScreenLive").also{it.start()};val handler=Handler(thread!!.looper)
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
        reader!!.setOnImageAvailableListener({source->val now=System.currentTimeMillis();val image=source.acquireLatestImage()?:return@setOnImageAvailableListener;if(now-lastFrameAt<125){image.close();return@setOnImageAvailableListener};lastFrameAt=now;try{val plane=image.planes[0];val pixelStride=plane.pixelStride;val rowStride=plane.rowStride;val paddedWidth=rowStride/pixelStride;val bitmap=Bitmap.createBitmap(paddedWidth,height,Bitmap.Config.ARGB_8888);bitmap.copyPixelsFromBuffer(plane.buffer);val cropped=Bitmap.createBitmap(bitmap,0,0,width,height);val out=ByteArrayOutputStream();cropped.compress(Bitmap.CompressFormat.JPEG,65,out);ws.send(out.toByteArray().toByteString());cropped.recycle();bitmap.recycle()}finally{image.close()}},handler)
        projection=(getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(resultCode,data)
        projection!!.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stopSelf()}},handler)
        display=projection!!.createVirtualDisplay("EcosystemScreenLive",width,height,dpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
        streamStarted=true;EcosystemAgentService.reportSessionState(sessionId,SessionState.RUNNING)
    }
    override fun onDestroy(){if(stopping)return super.onDestroy();stopping=true;reader?.setOnImageAvailableListener(null,null);display?.release();reader?.close();projection?.stop();socket?.close(1000,"stopped");thread?.quitSafely();scope.cancel();if(streamStarted)EcosystemAgentService.reportSessionState(sessionId,SessionState.COMPLETED);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
