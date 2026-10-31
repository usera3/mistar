package cn.qingliao.app

import android.location.Location
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Coordinates are a signed, encrypted text extension; older clients display the explicit text. */
object LocationPayload {
    private val fields=setOf("v","lat","lon","accuracy","at","approximate","label","live_id","until")
    fun validate(p:JSONObject){
        require(p.keys().asSequence().toSet().all{it in fields}&&p.get("v") is Int&&p.getInt("v")==1)
        for(key in listOf("lat","lon","accuracy","at"))require(p.get(key) is Number)
        require(p.getDouble("lat").isFinite()&&p.getDouble("lat") in -90.0..90.0&&p.getDouble("lon").isFinite()&&p.getDouble("lon") in -180.0..180.0)
        require(p.getDouble("accuracy").isFinite()&&p.getDouble("accuracy") in 0.0..50000.0)
        val at=p.getDouble("at");require(at==p.getLong("at").toDouble()&&p.getLong("at") in 1577836800000L..4102444800000L)
        require(p.get("approximate") is Boolean&&p.get("label") is String)
        require(p.getString("label").length in 1..60&&p.getString("label").none{it.isISOControl()||it in '\u202a'..'\u202e'||it in '\u2066'..'\u2069'})
        if(p.has("live_id")){
            val id=p.getString("live_id");require(UUID.fromString(id).toString()==id)
            require(p.get("until") is Number&&p.getDouble("until")==p.getLong("until").toDouble()&&p.getLong("until")>p.getLong("at")&&p.getLong("until")-p.getLong("at")<=9*3600000L)
        }else require(!p.has("until"))
    }
    fun from(fix:Location,approximate:Boolean,label:String="我的位置"):JSONObject=JSONObject().put("v",1).put("lat",fix.latitude).put("lon",fix.longitude).put("accuracy",fix.accuracy.toDouble()).put("at",fix.time).put("approximate",approximate).put("label",label.trim().ifEmpty{"我的位置"}).also{validate(it)}
    fun coordinates(p:JSONObject)=String.format(Locale.ROOT,"%.6f, %.6f",p.getDouble("lat"),p.getDouble("lon"))
    fun accuracy(p:JSONObject)=(if(p.optBoolean("approximate"))"大致位置 · "else"")+"精度约"+if(p.getDouble("accuracy")>=1000)String.format(Locale.ROOT,"%.1f公里",p.getDouble("accuracy")/1000)else"${p.getDouble("accuracy").toInt().coerceAtLeast(1)}米"
    fun text(p:JSONObject):String {validate(p);return (if(p.has("live_id"))"[实时位置共享] "else"[位置] ")+p.getString("label")+"\n"+coordinates(p)+"\n"+accuracy(p)+"\nhttps://www.openstreetmap.org/?mlat="+p.getDouble("lat")+"&mlon="+p.getDouble("lon")+"#map=17/"+p.getDouble("lat")+"/"+p.getDouble("lon")}
    fun packet(p:JSONObject,peer:String)=JSONObject().put("recipient",peer).put("type","text").put("text",text(p)).put("location",JSONObject(p.toString())).put("client_id",UUID.randomUUID().toString())
}
