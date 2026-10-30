package cn.qingliao.app

import android.app.Activity
import android.content.Intent
import android.graphics.Point
import android.os.SystemClock
import android.view.WindowManager
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max

/** In-memory, one-use grants bound to this account, call and exact media engine. */
internal object ScreenSharing {
    data class Request internal constructor(val id:String,internal val owner:String,internal val session:String,
        internal val callId:String,internal val engine:RtcEngine,internal val created:Long,internal val wholeDisplay:Boolean)
    private data class Grant(val request:Request,val data:Intent)
    private data class Active(val request:Request,val service:ScreenShareService)
    private var requested:Request?=null
    private var granted:Grant?=null
    private var active:Active?=null
    fun dimensions(width:Int,height:Int):Pair<Int,Int>{
        val w=width.coerceIn(16,16384);val h=height.coerceIn(16,16384);val scale=minOf(1.0,1280.0/max(w,h))
        return (max(16,((w*scale).toInt()/2)*2) to max(16,((h*scale).toInt()/2)*2))
    }
    @Suppress("DEPRECATION") fun displaySize():Pair<Int,Int>{val p=Point();Hub.app.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(p);return dimensions(p.x,p.y)}
    private fun current(r:Request)=Hub.token.isNotEmpty()&&Hub.token==r.session&&Hub.me.optString("id")==r.owner&&
        Hub.call?.optString("id")==r.callId&&Hub.call?.optBoolean("video")==true&&Hub.call?.optBoolean("accepted")==true&&Hub.rtc===r.engine
    fun request(wholeDisplay:Boolean=false):Request?{
        val engine=Hub.rtc?:return null
        if(!Hub.foreground||Hub.phase!="connected"||!engine.canShareScreen||engine.sharingScreen||active!=null||requested!=null||granted!=null)return null
        val call=Hub.call?:return null
        return Request(UUID.randomUUID().toString(),Hub.me.optString("id"),Hub.token,call.optString("id"),engine,SystemClock.elapsedRealtime(),wholeDisplay).takeIf{current(it)}?.also{requested=it}
    }
    fun cancelRequest(r:Request){if(requested===r)requested=null;if(granted?.request===r)granted=null}
    fun approve(r:Request,result:Int,data:Intent?):Boolean{
        if(requested!==r)return false
        requested=null
        if(result!=Activity.RESULT_OK||data==null||!current(r)||!Hub.foreground||Hub.phase!="connected"||!r.engine.canShareScreen||SystemClock.elapsedRealtime()-r.created>120000)return false
        granted=Grant(r,Intent(data))
        return try{Hub.app.startForegroundService(Intent(Hub.app,ScreenShareService::class.java).setAction(ScreenShareService.START).putExtra("grant",r.id));true}
        catch(_:Exception){granted=null;error("无法启动屏幕共享，请返回通话后重试");false}
    }
    internal fun start(service:ScreenShareService,id:String):Boolean{
        val grant=granted?.takeIf{it.request.id==id}?:return false
        granted=null;val r=grant.request
        if(!current(r)||!Hub.foreground||Hub.phase!="connected"||!r.engine.canShareScreen||active!=null||SystemClock.elapsedRealtime()-r.created>120000)return false
        try{
            service.beginForeground(r.id,r.owner,r.callId)
            val size=displaySize();r.engine.startScreen(grant.data,size.first,size.second)
            active=Active(r,service);r.engine.assist.sharingChanged();return true
        }catch(_:Exception){service.finish(r.id);error("屏幕共享未能开始，通话仍可继续");return false}
    }
    fun stop(engine:RtcEngine?=Hub.rtc){
        val value=active?:return
        if(value.request.engine!==engine)return
        active=null;value.request.engine.assist.sharingChanged();value.request.engine.stopScreen();value.service.finish(value.request.id)
    }
    internal fun stopAction(id:String){val value=active?:return;if(value.request.id==id&&current(value.request))stop(value.request.engine)}
    internal fun owns(service:ScreenShareService)=active?.service===service
    internal fun serviceDestroyed(service:ScreenShareService){if(active?.service===service){val value=active!!;active=null;value.request.engine.assist.sharingChanged();value.request.engine.stopScreen()}}
    internal fun controlShareId(engine:RtcEngine):String?=active?.takeIf{it.request.engine===engine&&current(it.request)&&engine.sharingScreen&&(android.os.Build.VERSION.SDK_INT<34||it.request.wholeDisplay)&&engine.capturedWholeDisplay()}?.request?.id
    internal fun engineReleased(engine:RtcEngine){
        if(requested?.engine===engine)requested=null
        if(granted?.request?.engine===engine)granted=null
        active?.takeIf{it.request.engine===engine}?.let{active=null;it.service.finish(it.request.id)}
    }
    internal fun resized(service:ScreenShareService){val value=active?:return;if(value.service===service){if(current(value.request)){val size=displaySize();value.request.engine.resizeScreen(size.first,size.second)}else stop(value.request.engine)}}
    private fun error(message:String){Hub.emit(JSONObject().put("event","error").put("error",message))}
}
