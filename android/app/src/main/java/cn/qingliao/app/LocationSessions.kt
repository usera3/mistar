package cn.qingliao.app

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Live fixes stay in bounded, account-scoped RAM. The ordinary initial card uses encrypted cache. */
object LocationSessions {
    private var owner="";private var session=""
    private val entries=linkedMapOf<String,JSONObject>();private val ended=linkedSetOf<String>()
    private val meetings=linkedMapOf<String,JSONObject>()
    val listeners=CopyOnWriteArraySet<()->Unit>()
    private fun scope(){if(owner!=Hub.me.optString("id")||session!=Hub.token){entries.clear();ended.clear();meetings.clear();owner=Hub.me.optString("id");session=Hub.token}}
    fun get(id:String):JSONObject?{scope();return entries[id]?.let{JSONObject(it.toString())}}
    fun isEnded(id:String):Boolean{scope();return id in ended}
    private fun changed(){listeners.toList().forEach{it()}}
    fun remember(value:JSONObject){
        scope();val share=value.getJSONObject("share");val id=share.getString("id")
        if(id in ended||share.getLong("until")<=System.currentTimeMillis())return
        val old=entries[id]
        if(old!=null&&listOf("owner","peer","epoch","until").any{old.getJSONObject("share").opt(it)!=share.opt(it)})return
        if(old!=null&&old.getJSONObject("share").optInt("seq")>share.optInt("seq"))return
        if(old?.optJSONObject("location")!=null&&!value.has("location"))value.put("location",old.getJSONObject("location"))
        if(!value.has("user"))old?.optJSONObject("user")?.let{value.put("user",it)}
        while(entries.size>=128&&!entries.containsKey(id))entries.remove(entries.keys.first())
        entries[id]=JSONObject(value.toString()).put("received",android.os.SystemClock.elapsedRealtime());changed()
    }
    fun close(id:String){scope();entries.remove(id);ended.add(id);while(ended.size>256)ended.remove(ended.first());LocationShareService.instance?.takeIf{it.shareId==id}?.end(false);changed()}
    fun clear(){entries.clear();ended.clear();meetings.clear();owner="";session="";changed()}
    fun disconnected(){scope();entries.values.forEach{it.put("disconnected",true)};changed()}
    private fun conversation(s:JSONObject)=if(ChatAddress.group(s.optString("peer")))s.optString("peer")else if(s.optString("owner")==owner)s.optString("peer")else s.optString("owner")
    fun room(peer:String):List<JSONObject>{scope();return entries.values.filter{conversation(it.getJSONObject("share"))==peer&&it.getJSONObject("share").optLong("until")>System.currentTimeMillis()}.map{JSONObject(it.toString())}}
    fun reconcile(peer:String,ids:Set<String>,requested:Long){scope();entries.filter{(id,v)->id !in ids&&conversation(v.getJSONObject("share"))==peer&&v.optLong("received")<=requested}.keys.toList().forEach{close(it)}}
    fun meeting(peer:String):JSONObject?{scope();return meetings[peer]?.optJSONObject("location")?.let{JSONObject(it.toString())}}
    fun removeMeeting(id:String){scope();if(id.isEmpty())return;val keys=meetings.filterValues{it.optString("id")==id}.keys.toList();if(keys.isNotEmpty()){keys.forEach{meetings.remove(it)};changed()}}
    fun observeMeeting(message:JSONObject){scope();val id=message.optString("id")
        if(message.optBoolean("withdrawn")){meetings.filterValues{it.optString("id")==id}.keys.toList().forEach{meetings.remove(it)};changed();return}
        if(!message.optBoolean("e2ee")||message.optString("location_role")!="meeting"||message.optBoolean("pending")||message.optBoolean("decrypt_failed"))return
        val peer=ChatAddress.peer(message,owner);if(!ChatAddress.valid(peer))return
        val location=message.optJSONObject("location")?:return;runCatching{LocationPayload.validate(location)}.getOrElse{return}
        if(location.has("live_id")||meetings[peer]?.optLong("created")?.let{it>message.optLong("created")}==true)return
        while(meetings.size>=100&&!meetings.containsKey(peer))meetings.remove(meetings.keys.first());meetings[peer]=JSONObject(message.toString());changed()
    }
    fun accept(raw:JSONObject,expectedSession:String,expectedOwner:String){
        if(Hub.token!=expectedSession||Hub.me.optString("id")!=expectedOwner)return
        val opened=ChatCrypto.openLocation(raw)
        Hub.main.post{if(Hub.token==expectedSession&&Hub.me.optString("id")==expectedOwner)remember(opened)}
    }
}
