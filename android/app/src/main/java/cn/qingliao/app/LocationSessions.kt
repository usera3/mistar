package cn.qingliao.app

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Live fixes stay in bounded, account-scoped RAM. The ordinary initial card uses encrypted cache. */
object LocationSessions {
    private var owner="";private var session=""
    private val entries=linkedMapOf<String,JSONObject>();private val ended=linkedSetOf<String>()
    val listeners=CopyOnWriteArraySet<()->Unit>()
    private fun scope(){if(owner!=Hub.me.optString("id")||session!=Hub.token){entries.clear();ended.clear();owner=Hub.me.optString("id");session=Hub.token}}
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
        while(entries.size>=128&&!entries.containsKey(id))entries.remove(entries.keys.first())
        entries[id]=JSONObject(value.toString()).put("received",android.os.SystemClock.elapsedRealtime());changed()
    }
    fun close(id:String){scope();entries.remove(id);ended.add(id);while(ended.size>256)ended.remove(ended.first());LocationShareService.instance?.takeIf{it.shareId==id}?.end(false);changed()}
    fun clear(){entries.clear();ended.clear();owner="";session="";changed()}
    fun disconnected(){scope();entries.values.forEach{it.put("disconnected",true)};changed()}
    fun accept(raw:JSONObject,expectedSession:String,expectedOwner:String){
        if(Hub.token!=expectedSession||Hub.me.optString("id")!=expectedOwner)return
        val opened=ChatCrypto.openLocation(raw)
        Hub.main.post{if(Hub.token==expectedSession&&Hub.me.optString("id")==expectedOwner)remember(opened)}
    }
}
