package cn.qingliao.app

import org.json.JSONObject

/** Conversation IDs are distinct from immutable eight-digit person IDs. */
internal object ChatAddress {
    fun group(value:String)=value.matches(Regex("g_[a-f0-9]{32}"))
    fun person(value:String)=value.matches(Regex("[0-9]{8}"))
    fun valid(value:String)=person(value)||group(value)
    fun peer(message:JSONObject,owner:String):String {
        val gid=message.optString("group_id")
        if(gid.isNotEmpty())return gid.takeIf{group(it)}.orEmpty()
        val value=if(message.optString("sender")==owner)message.optString("recipient")else message.optString("sender")
        return value.takeIf{valid(it)}.orEmpty()
    }
}
