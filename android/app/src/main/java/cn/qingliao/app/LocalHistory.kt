package cn.qingliao.app

import org.json.*

object LocalHistory {
    private fun persist(owner:String,session:String,task:()->Unit){LocalData.worker.execute{if(Hub.token==session&&Hub.me.optString("id")==owner)runCatching{task()}}}
    fun messages(owner:String,session:String,list:JSONArray,stamp:Long) {
        val copy=JSONArray(list.toString())
        persist(owner,session){for(i in 0 until copy.length()){val m=copy.getJSONObject(i);Outbox.acknowledge(owner,m);LocalData.put(owner,m,stamp)}}
    }
    fun event(event:JSONObject) {
        val owner=Hub.me.optString("id");val session=Hub.token;if(owner.isEmpty()||session.isEmpty())return
        when(event.optString("event")) {
            "message" -> messages(owner,session,JSONArray().put(event.getJSONObject("message")),LocalData.epoch(owner))
            "message.updated" -> {val stamp=LocalData.changed(owner);messages(owner,session,JSONArray().put(event.getJSONObject("message")),stamp)}
            "message.deleted" -> {LocalData.changed(owner);persist(owner,session){LocalData.hide(owner,event.getString("id"))}}
            "conversation.cleared" -> {LocalData.changed(owner);persist(owner,session){LocalData.clear(owner,event.getString("peer"),event.getLong("through"))}}
            "file.removed" -> {LocalData.changed(owner);persist(owner,session){LocalData.fileGone(owner,event.getString("id"))}}
        }
    }
    fun homePart(owner:String,session:String,key:String,array:JSONArray,stamp:Long=LocalData.epoch(owner)){val copy=JSONArray(array.toString());persist(owner,session){if(stamp==LocalData.epoch(owner))LocalData.homePart(owner,key,copy)}}
    fun home(owner:String):JSONObject {
        ConversationState.load(owner,Hub.token)
        return withPending(owner,LocalData.home(owner))
    }
    fun withPending(owner:String,result:JSONObject):JSONObject {
        val contacts=result.optJSONArray("contacts")?:JSONArray();val conversations=result.optJSONArray("conversations")?:JSONArray()
        val all=LocalData.pending(owner)
        for(i in 0 until all.length()) {
            val p=all.getJSONObject(i);val id=p.getString("recipient")
            val existing=(0 until conversations.length()).map{conversations.getJSONObject(it)}.find{it.getJSONObject("user").optString("id")==id}
            val user=existing?.optJSONObject("user")?:(0 until contacts.length()).map{contacts.getJSONObject(it)}.find{it.optString("id")==id}?:JSONObject().put("id",id).put("nickname","朋友")
            if(existing!=null){if(p.optLong("local_created")>=existing.getJSONObject("last").optLong("created"))existing.put("last",Outbox.pending(owner,p))}else conversations.put(JSONObject().put("user",user).put("last",Outbox.pending(owner,p)).put("unread",0))
        }
        return result.put("contacts",contacts).put("conversations",JSONArray((0 until conversations.length()).map{conversations.getJSONObject(it)}.sortedByDescending{it.getJSONObject("last").optLong("created")}))
    }
    fun synchronize() {
        val owner=Hub.me.optString("id");val session=Hub.token
        Hub.io.execute {try {
            if(Hub.token!=session)return@execute
            val state=Hub.rawApi("GET","/api/history-version?after="+LocalData.version(owner),session=session)
            if(Hub.token!=session)return@execute
            val invalid=LocalData.acceptVersion(owner,state)
            Hub.main.post{if(Hub.token==session)Hub.emit(JSONObject().put("event","history.ready").put("invalidated",invalid).put("peers",state.optJSONArray("peers")?:JSONArray()).put("reset",state.optBoolean("reset")))}
        }catch(_:Exception){}}
    }
}
