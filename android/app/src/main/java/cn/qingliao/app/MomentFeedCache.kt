package cn.qingliao.app

import org.json.JSONArray

/** A small, session-only first-page snapshot to avoid blank tab returns. */
object MomentFeedCache {
    data class Page(val json:String,val next:String,val saved:Long)
    private val pages=linkedMapOf<String,Page>()
    fun clear()=pages.clear()
    fun get(owner:String,scope:String):Page?=pages["$owner:$scope"]?.takeIf { android.os.SystemClock.elapsedRealtime()-it.saved<90000 }
    fun put(owner:String,scope:String,items:List<org.json.JSONObject>,next:String) {
        if(owner.isEmpty()||Hub.token.isEmpty())return
        val first=items.take(20)
        val cursor=if(items.size>20)first.last().let { "${it.optLong("created")}:${it.optString("id")}" } else next
        pages["$owner:$scope"]=Page(JSONArray(first).toString(),cursor,android.os.SystemClock.elapsedRealtime())
        while(pages.size>3)pages.remove(pages.keys.first())
    }
}
