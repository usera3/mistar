package cn.qingliao.app

import android.app.PendingIntent
import android.content.*
import android.net.Uri
import android.os.SystemClock
import java.security.SecureRandom

/** Notification capabilities are bound to the current account, call, phase and random nonce. */
object CallActions {
    private var callId=""
    private var owner=""
    private var nonce=""
    private var session=""
    fun clear(){callId="";owner="";nonce="";session=""}
    private fun intent(action:String):Intent {
        val active=Hub.call?:throw IllegalStateException()
        val uid=Hub.me.optString("id");val id=active.optString("id")
        if(callId!=id||owner!=uid||session!=Hub.token){callId=id;owner=uid;session=Hub.token;nonce=ByteArray(24).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)}}
        return Intent(Hub.app,if(action in listOf("reject","hangup","mute"))CallActionReceiver::class.java else MainActivity::class.java)
            .setAction("cn.qingliao.app.CALL_"+action.uppercase()).setData(Uri.parse("qingliao://call/$uid/$id/$action"))
            .putExtra("owner",uid).putExtra("call_id",id).putExtra("nonce",nonce)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    fun pending(action:String):PendingIntent {
        val value=intent(action);val code=(owner+":"+callId+":"+action).hashCode()
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if(action in listOf("reject","hangup","mute"))PendingIntent.getBroadcast(Hub.app,code,value,flags) else PendingIntent.getActivity(Hub.app,code,value,flags)
    }
    fun valid(value:Intent):Boolean = current(value)&&Hub.phase=="incoming"&&Hub.ringUntil>SystemClock.elapsedRealtime()
    fun validActive(value:Intent):Boolean=current(value)&&Hub.phase in listOf("outgoing","connecting","connected","reconnecting")
    private fun current(value:Intent):Boolean = Hub.token.isNotEmpty()&&Hub.token==session&&
        owner.isNotEmpty()&&nonce.isNotEmpty()&&value.getStringExtra("owner")==owner&&owner==Hub.me.optString("id")&&
        value.getStringExtra("call_id")==callId&&callId==Hub.call?.optString("id")&&value.getStringExtra("nonce")==nonce
}

class CallActionReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action=="cn.qingliao.app.CALL_REJECT"&&CallActions.valid(intent))Hub.rejectIncoming()
        if(!CallActions.validActive(intent))return
        when(intent.action){
            "cn.qingliao.app.CALL_HANGUP"->Hub.hangup()
            "cn.qingliao.app.CALL_MUTE"->{Hub.rtc?.toggleMute();Hub.emit(org.json.JSONObject().put("event","call.audio"));Hub.service?.update()}
        }
    }
}
