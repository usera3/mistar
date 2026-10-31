package cn.qingliao.app

import org.json.*

/** Small account/session-bound roster metadata cache; never stores plaintext or private group keys. */
internal object GroupDirectory {
    private var owner="";private var session=""
    private val groups=linkedMapOf<String,JSONObject>()
    private fun scope(){val current=Hub.me.optString("id");if(owner!=current||session!=Hub.token){groups.clear();owner=current;session=Hub.token}}
    @Synchronized fun clear(){groups.clear();owner="";session=""}
    @Synchronized fun remember(group:JSONObject,me:String=Hub.workOwner(),expected:String=Hub.workSession()){
        if(Hub.token!=expected||Hub.me.optString("id")!=me||expected.isEmpty())return
        scope();val id=group.optString("id")
        if(session.isEmpty()||!group.optBoolean("group")||!ChatAddress.group(id)||group.optInt("epoch")<1)return
        groups[id]?.let{old->if(group.optInt("epoch")<old.optInt("epoch")||old.optBoolean("left")&&group.optInt("epoch")<=old.optInt("epoch"))return}
        if(groups.size>=60&&id !in groups)groups.remove(groups.keys.first())
        groups[id]=JSONObject(group.toString())
    }
    @Synchronized fun get(id:String):JSONObject?{scope();return groups[id]?.let{JSONObject(it.toString())}}
    @Synchronized fun removed(id:String,epoch:Int=0){scope();if(!ChatAddress.group(id))return;if(groups.size>=60&&id !in groups)groups.remove(groups.keys.first());val current=groups[id]?:JSONObject().put("id",id).put("group",true);current.put("friend",false).put("state","left").put("left",true).put("epoch",maxOf(current.optInt("epoch"),epoch));groups[id]=current}
    fun observe(result:JSONObject,me:String=Hub.workOwner(),expected:String=Hub.workSession()){
        result.optJSONObject("group")?.let{remember(it,me,expected)}
        for(key in listOf("contacts","groups"))result.optJSONArray(key)?.let{array->for(i in 0 until array.length())array.optJSONObject(i)?.let{remember(it,me,expected)}}
        result.optJSONArray("conversations")?.let{array->for(i in 0 until array.length())array.optJSONObject(i)?.optJSONObject("user")?.let{remember(it,me,expected)}}
    }
    fun fetch(id:String):JSONObject {
        require(ChatAddress.group(id));val expected=Hub.workSession();val me=Hub.workOwner()
        val value=Hub.rawApi("GET","/api/groups/$id",session=expected).getJSONObject("group")
        check(Hub.token==expected&&Hub.me.optString("id")==me){"登录状态已改变"};remember(value,me,expected);return value
    }
    fun seed(payload:JSONObject){
        val id=payload.getString("recipient");if(!ChatAddress.group(id)||payload.has("group_epoch"))return
        val group=get(id)?:fetch(id);require(!group.optBoolean("left")&&group.optInt("epoch")>0){"请重新打开群聊确认成员"}
        payload.put("group_epoch",group.getInt("epoch"))
    }
}
