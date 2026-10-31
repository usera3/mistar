package cn.qingliao.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject

/** Explicit foreground start only. No boot/sticky restart, background-location permission or logs. */
class LocationShareService:Service(){
    companion object{var instance:LocationShareService?=null;private set;const val NOTIFICATION=0x4d5319}
    private var owner="";private var session="";private var share:JSONObject?=null;private var source:LocationSource?=null
    private var fix:Location?=null;private var fixApproximate=true;@Volatile private var active=false;private var sending=false;private var seq=0
    private var firstAt=0L;private var successAt=0L;@Volatile private var generation=0;private var error="正在取得位置"
    val shareId get()=share?.optString("id").orEmpty()
    val peer get()=share?.optString("peer").orEmpty()
    private val tick=object:Runnable{override fun run(){
        if(!active)return
        if(Hub.token!=session||Hub.me.optString("id")!=owner||System.currentTimeMillis()>=share!!.getLong("until")||source?.permitted()!=true){end();return}
        val now=SystemClock.elapsedRealtime()
        if(now-(if(successAt>0)successAt else firstAt)>60000){end();return}
        if(fix!=null&&source!!.approximate!=fixApproximate){fix=null;source!!.start()}
        val latest=fix
        if(!sending&&latest!=null&&source!!.fresh(latest))publish(Location(latest))
        else if(latest==null||!source!!.fresh(latest)){error="定位信号较弱，暂未更新";notification()}
        Hub.main.postDelayed(this,10000)
    }}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action=="stop"){
            if(active&&intent.getStringExtra("share_id")==shareId&&intent.getStringExtra("owner")==owner)end()
            else if(!active)stopSelf()
            return START_NOT_STICKY
        }
        if(active||intent?.action!="start"){if(!active)stopSelf();return START_NOT_STICKY}
        val opening=intent.getStringExtra("opening").orEmpty();val who=intent.getStringExtra("owner").orEmpty()
        val value=runCatching{JSONObject(intent.getStringExtra("share").orEmpty())}.getOrNull()
        if(opening.isEmpty()||Hub.token!=opening||Hub.me.optString("id")!=who||value?.optString("owner")!=who||value.optLong("until")<=System.currentTimeMillis()){stopSelf();return START_NOT_STICKY}
        session=opening;owner=who;share=value;seq=value.optInt("seq");active=true;instance=this;firstAt=SystemClock.elapsedRealtime();generation++
        try{
            val nm=getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel("location-sharing","实时位置共享",NotificationManager.IMPORTANCE_LOW).apply{setShowBadge(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE})
            ServiceCompat.startForeground(this,NOTIFICATION,buildNotification(),if(Build.VERSION.SDK_INT>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            source=LocationSource(this,{point->fix=point;fixApproximate=source!!.approximate;error="正在共享位置"},{message->error=message;notification()});source!!.start()
            Hub.main.post(tick)
        }catch(_:Exception){end()}
        return START_NOT_STICKY
    }
    private fun buildNotification():Notification {
        val id=shareId;val target=Intent(this,LocationActivity::class.java).putExtra("live",id).putExtra("peer",peer)
        val open=PendingIntent.getActivity(this,id.hashCode(),target,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,id.hashCode(),Intent(this,LocationShareService::class.java).setAction("stop").setData(android.net.Uri.parse("mistar-location:stop:$id")).putExtra("share_id",id).putExtra("owner",owner),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"location-sharing").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("弥星 · 实时位置共享中").setContentText(error).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).addAction(0,"停止共享",stop).build()
    }
    private fun notification(){if(active)runCatching{getSystemService(NotificationManager::class.java).notify(NOTIFICATION,buildNotification())}}
    private fun publish(point:Location){
        val expected=generation;val metadata=JSONObject(share!!.toString());val n=++seq;val approx=fixApproximate;sending=true
        Hub.io.execute{try{
            check(active&&generation==expected&&Hub.token==session&&Hub.me.optString("id")==owner)
            val location=LocationPayload.from(point,approx).put("live_id",metadata.getString("id")).put("until",metadata.getLong("until"));val wire=ChatCrypto.sealLocation(metadata,n,location)
            check(active&&generation==expected&&Hub.token==session&&source?.permitted()==true&&source?.approximate==approx)
            Hub.rawApi("PUT","/api/locations/"+metadata.getString("id"),JSONObject().put("seq",n).put("wire",wire),session,requestTag="location:$owner:${metadata.getString("id")}")
            Hub.main.post{if(active&&generation==expected){sending=false;successAt=SystemClock.elapsedRealtime();error="位置已加密共享 · 可随时停止";metadata.put("seq",n);LocationSessions.remember(JSONObject().put("share",metadata).put("location",location));notification()}}
        }catch(e:Exception){Hub.main.post{if(active&&generation==expected){sending=false;if(e is ApiFailure&&e.status in listOf(401,403,404,409)){end()}else{error="连接不稳定，暂未更新";notification()}}}}}
    }
    fun end(notifyServer:Boolean=true){
        if(!active){stopSelf();return};val id=shareId;val token=session
        active=false;generation++;Hub.main.removeCallbacks(tick);source?.stop();source=null;fix=null
        (Hub.http.dispatcher.runningCalls()+Hub.http.dispatcher.queuedCalls()).filter{it.request().tag()=="location:$owner:$id"}.forEach{it.cancel()}
        if(instance===this)instance=null
        LocationSessions.close(id)
        if(notifyServer&&token.isNotEmpty())Hub.io.execute{runCatching{Hub.rawApi("DELETE","/api/locations/$id",session=token)}}
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onTaskRemoved(rootIntent:Intent?){end();super.onTaskRemoved(rootIntent)}
    override fun onDestroy(){if(active)end();if(instance===this)instance=null;super.onDestroy()}
}
