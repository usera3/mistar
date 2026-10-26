package cn.qingliao.app

import org.json.*

/** Account-bound, encrypted device state. Typing updates memory immediately, then debounces disk I/O. */
internal object ConversationState {
    private data class Entry(var value:JSONObject,var revision:Long=0,var dirty:Boolean=false)
    private val cache=linkedMapOf<String,Entry>()
    private val timers=mutableMapOf<String,Runnable>()
    private var activeOwner=""
    private var activeSession=""
    private var serial=0L
    private fun scoped(owner:String,session:String=Hub.token){
        check(owner.isNotEmpty()&&session.isNotEmpty()&&Hub.token==session&&Hub.me.optString("id")==owner){"登录状态已改变"}
        if(activeOwner!=owner||activeSession!=session){clearMemory();activeOwner=owner;activeSession=session}
    }
    private fun entry(owner:String,peer:String):Entry {
        scoped(owner);require(peer.matches(Regex("[0-9]{8}")))
        if(peer !in cache&&cache.size>=400)cache.entries.firstOrNull{!it.value.dirty}?.let{cache.remove(it.key)}
        require(peer in cache||cache.size<500){"本机会话缓存已满"}
        return cache.getOrPut(peer){Entry(runCatching{LocalData.chatState(owner,peer)}.getOrElse{JSONObject().put("unreadable",true).put("muted",true)})}
    }
    @Synchronized fun clearMemory(){timers.values.forEach{Hub.main.removeCallbacks(it)};timers.clear();cache.clear();activeOwner="";activeSession="";serial++}
    @Synchronized fun load(owner:String,session:String){
        scoped(owner,session);val disk=LocalData.chatStates(owner)
        for(peer in disk.keys())if(!cache.containsKey(peer)){cache[peer]=Entry(disk.getJSONObject(peer));serial++}
    }
    @Synchronized fun revision(owner:String):Long{scoped(owner);return serial}
    @Synchronized fun state(owner:String,peer:String)=JSONObject(entry(owner,peer).value.toString())
    @Synchronized fun muted(owner:String,peer:String)=try{entry(owner,peer).value.optBoolean("muted")}catch(_:Exception){true}
    @Synchronized fun snapshot(owner:String):JSONObject {
        scoped(owner);return JSONObject().apply{cache.forEach{(peer,e)->if(e.value.length()>0)put(peer,JSONObject(e.value.toString()))}}
    }
    @Synchronized fun flag(owner:String,session:String,peer:String,key:String,enabled:Boolean){
        scoped(owner,session);require(key in setOf("muted","pinned"))
        val e=entry(owner,peer);val value=JSONObject(e.value.toString()).apply{remove("unreadable");if(key=="pinned")put("pinned_at",if(enabled)System.currentTimeMillis()else 0)else put("muted",enabled)}
        LocalData.saveChatState(owner,peer,value);e.value=value;e.revision=++serial;e.dirty=false
        timers.remove(peer)?.let{Hub.main.removeCallbacks(it)}
    }
    @Synchronized fun draft(owner:String,session:String,peer:String,text:String,quote:JSONObject?,start:Int,end:Int){
        scoped(owner,session);require(text.length<=5000)
        val e=entry(owner,peer);val old=e.value
        val normalized=quote?.let{q->
            require(q.keys().asSequence().toSet()==setOf("id","sender","type","excerpt"))
            require(q.optString("excerpt").length<=160&&q.optString("sender") in setOf(owner,peer))
            java.util.UUID.fromString(q.getString("id"));JSONObject(q.toString())
        }
        if(old.optString("draft")==text&&old.optJSONObject("quote")?.toString()==normalized?.toString()&&old.optInt("start")==start&&old.optInt("end")==end)return
        val value=JSONObject(old.toString()).apply{
            remove("unreadable");put("draft",text);put("start",start.coerceIn(0,text.length));put("end",end.coerceIn(0,text.length))
            if(normalized==null)remove("quote")else put("quote",normalized)
            if(text.isEmpty()&&normalized==null)remove("draft_updated")else put("draft_updated",System.currentTimeMillis())
        }
        e.value=value;e.revision=++serial;e.dirty=true
        timers.remove(peer)?.let{Hub.main.removeCallbacks(it)}
        val version=e.revision
        val timer=Runnable{
            LocalData.worker.execute{
                val error=runCatching{persist(owner,session,peer,version)}.exceptionOrNull()
                if(error!=null)Hub.main.post{if(Hub.token==session)Hub.emit(JSONObject().put("event","draft.error").put("peer",peer).put("error",error.message?:"草稿暂未保存"))}
            }
        };timers[peer]=timer;Hub.main.postDelayed(timer,350)
    }
    @Synchronized private fun persist(owner:String,session:String,peer:String,version:Long){
        if(Hub.token!=session||Hub.me.optString("id")!=owner||activeOwner!=owner||activeSession!=session)return
        val e=cache[peer]?:return;if(e.revision!=version||!e.dirty)return
        LocalData.saveChatState(owner,peer,e.value);e.dirty=false;timers.remove(peer)?.let{Hub.main.removeCallbacks(it)}
    }
    @Synchronized fun flush(owner:String,session:String=Hub.token){
        if(activeOwner!=owner||activeSession!=session||Hub.token!=session)return
        cache.filterValues{it.dirty}.toMap().forEach{(peer,e)->persist(owner,session,peer,e.revision)}
    }
    @Synchronized fun queueText(owner:String,session:String,payload:JSONObject):JSONObject {
        scoped(owner,session);require(payload.optString("type")=="text")
        val peer=payload.getString("recipient");val e=entry(owner,peer);val next=JSONObject(e.value.toString())
        if(next.optString("draft").trim()==payload.optString("text")&&next.optJSONObject("quote")?.toString()==payload.optJSONObject("quote")?.toString()){
            for(key in listOf("draft","quote","draft_updated","start","end"))next.remove(key)
        }
        val result=LocalData.queueWithState(owner,payload,next)
        e.value=next;e.revision=++serial;e.dirty=false;timers.remove(peer)?.let{Hub.main.removeCallbacks(it)}
        return result
    }
    fun hasDraft(s:JSONObject)=s.optString("draft").isNotEmpty()||s.has("quote")
    @Synchronized fun conversations(owner:String,contacts:JSONArray,remote:JSONArray):List<JSONObject>{
        scoped(owner);val states=JSONObject()
        cache.forEach{(peer,e)->
            val state=e.value;var preview=state.optString("draft").take(160);if(preview.lastOrNull()?.isHighSurrogate()==true)preview=preview.dropLast(1)
            states.put(peer,JSONObject().put("draft",preview).put("draft_updated",state.optLong("draft_updated")).put("pinned_at",state.optLong("pinned_at")).put("muted",state.optBoolean("muted")).apply{state.optJSONObject("quote")?.let{put("quote",JSONObject().put("excerpt",it.optString("excerpt")))}})
        }
        val rows=(0 until remote.length()).map{JSONObject(remote.getJSONObject(it).toString())}.associateByTo(linkedMapOf()){it.getJSONObject("user").getString("id")}
        for(n in 0 until contacts.length()){
            val user=contacts.getJSONObject(n);val id=user.optString("id");val state=states.optJSONObject(id)?:continue
            if(id !in rows&&user.optString("state")=="accepted"&&!user.optBoolean("blocked")&&(hasDraft(state)||state.optLong("pinned_at")>0)){
                rows[id]=JSONObject().put("user",user).put("unread",0).put("last",JSONObject().put("type","text").put("text","").put("created",state.optLong("draft_updated")))
            }
        }
        return rows.values.onEach{it.put("local",states.optJSONObject(it.getJSONObject("user").getString("id"))?:JSONObject())}
            .sortedWith(compareByDescending<JSONObject>{it.getJSONObject("local").optLong("pinned_at")}.thenByDescending{maxOf(it.getJSONObject("last").optLong("created"),it.getJSONObject("local").optLong("draft_updated"))})
    }
}
