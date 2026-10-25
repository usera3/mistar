package cn.qingliao.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

/** Only a user-approved, current-call grant can start capture. Never restart after process death. */
class ScreenShareService:Service() {
    companion object {const val START="cn.qingliao.app.SHARE_START";const val STOP="cn.qingliao.app.SHARE_STOP";private const val NOTIFICATION=108}
    private var grantId=""
    private val displays by lazy{getSystemService(DisplayManager::class.java)}
    private val displayListener=object:DisplayManager.DisplayListener{
        override fun onDisplayAdded(id:Int){}
        override fun onDisplayRemoved(id:Int){}
        override fun onDisplayChanged(id:Int){if(Build.VERSION.SDK_INT<34)ScreenSharing.resized(this@ScreenShareService)}
    }
    private val screenOff=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action==Intent.ACTION_SCREEN_OFF)ScreenSharing.stop()}}
    override fun onCreate(){
        super.onCreate()
        val filter=IntentFilter(Intent.ACTION_SCREEN_OFF)
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenOff,filter,RECEIVER_NOT_EXPORTED)else registerReceiver(screenOff,filter)
        displays.registerDisplayListener(displayListener,Hub.main)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            START->if(!ScreenSharing.start(this,intent.getStringExtra("grant")?:"")){if(!ScreenSharing.owns(this))stopSelfResult(startId)}
            STOP->{ScreenSharing.stopAction(intent.getStringExtra("grant")?:"");if(!ScreenSharing.owns(this))stopSelfResult(startId)}
            else->if(!ScreenSharing.owns(this))stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }
    internal fun beginForeground(id:String,owner:String,callId:String){
        grantId=id
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("screen-sharing","屏幕共享",NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null);enableVibration(false);setShowBadge(false)})
        val stop=Intent(this,ScreenShareService::class.java).setAction(STOP).setData(Uri.parse("qingliao://share/$owner/$callId/$id")).putExtra("grant",id)
        val pendingStop=PendingIntent.getService(this,id.hashCode(),stop,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,id.hashCode(),Intent(this,MainActivity::class.java).putExtra("owner",owner).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(this,"screen-sharing").setSmallIcon(R.drawable.ic_notification).setContentTitle("正在共享屏幕")
            .setContentText("对方可看到你选择的屏幕内容").setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open).addAction(R.drawable.ic_notification,"停止共享",pendingStop).build()
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or if(Build.VERSION.SDK_INT>=30)ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        else startForeground(NOTIFICATION,notification)
    }
    internal fun finish(id:String){if(grantId!=id)return;grantId="";stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){ScreenSharing.serviceDestroyed(this);runCatching{unregisterReceiver(screenOff)};displays.unregisterDisplayListener(displayListener);super.onDestroy()}
    override fun onBind(intent:Intent?)=null
}
