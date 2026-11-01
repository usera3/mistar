package cn.qingliao.app

import android.content.Context
import android.location.*
import android.os.*
import java.util.*
import java.util.concurrent.*

/** Device geocoder only. No silent third-party public geocoding API or fabricated POIs. */
class LocationPlaces(private val context:Context,private val valid:()->Boolean){
    data class Place(val name:String,val address:String,val lat:Double,val lon:Double){
        fun distance(lat0:Double,lon0:Double):Float=FloatArray(1).also{Location.distanceBetween(lat0,lon0,lat,lon,it)}[0]
    }
    interface Backend {fun available():Boolean;fun lookup(context:Context,query:String,lat:Double,lon:Double):List<Place>}
    companion object {
        @Volatile var backend:Backend=object:Backend {
            override fun available()=Geocoder.isPresent()
            @Suppress("DEPRECATION") override fun lookup(context:Context,query:String,lat:Double,lon:Double):List<Place>{
                val coder=Geocoder(context,Locale.getDefault());val values=if(query.isEmpty())coder.getFromLocation(lat,lon,8)else coder.getFromLocationName(query,8)
                fun clean(s:String?)=s.orEmpty().filterNot{it.isISOControl()||it in '\u202a'..'\u202e'||it in '\u2066'..'\u2069'}.trim()
                return values.orEmpty().filter{it.hasLatitude()&&it.hasLongitude()&&it.latitude.isFinite()&&it.longitude.isFinite()&&it.latitude in -90.0..90.0&&it.longitude in -180.0..180.0}.map{a->
                    val address=clean(if(a.maxAddressLineIndex>=0)a.getAddressLine(0)else listOfNotNull(a.adminArea,a.locality,a.subLocality,a.thoroughfare,a.subThoroughfare).joinToString(" ")).take(180)
                    val name=clean(a.premises?:a.featureName?:a.thoroughfare).ifBlank{address.ifBlank{"所选位置"}}.take(60)
                    Place(name,address,a.latitude,a.longitude)
                }.distinctBy{listOf(it.name,it.lat,it.lon)}.take(8)
            }
        }
        private val executor=ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,ArrayBlockingQueue(8))
    }
    private var revision=0;private var timer:Runnable?=null;private val cache=linkedMapOf<String,List<Place>>()
    fun find(query:String,lat:Double,lon:Double,done:(List<Place>,String)->Unit){
        cancel();if(!valid())return;val q=query.trim().take(80);val key=q+":"+String.format(Locale.ROOT,"%.4f:%.4f",lat,lon)
        cache[key]?.let{done(it,"");return}
        val provider=backend;if(!provider.available()){done(emptyList(),"系统未提供地点检索，可拖动地图选点并命名");return}
        val stamp=revision
        timer=Runnable{if(stamp==revision&&valid()){revision++;done(emptyList(),"地点服务暂未响应，可继续在地图上选点")}}.also{Hub.main.postDelayed(it,12000)}
        try{executor.execute{
            try{if(!valid()||stamp!=revision)return@execute;val result=provider.lookup(context,q,lat,lon)
                Hub.main.post{if(valid()&&stamp==revision){timer?.let{Hub.main.removeCallbacks(it)};timer=null;while(cache.size>=32)cache.remove(cache.keys.first());cache[key]=result;done(result,if(result.isEmpty())"没有找到匹配地点，可拖动地图选点"else"")}}
            }catch(_:Exception){Hub.main.post{if(valid()&&stamp==revision){timer?.let{Hub.main.removeCallbacks(it)};timer=null;done(emptyList(),"地点服务暂不可用，可继续在地图上选点")}}}
        }}catch(_:Exception){timer?.let{Hub.main.removeCallbacks(it)};timer=null;done(emptyList(),"地点服务繁忙，请稍后重试")}
    }
    fun cancel(){revision++;timer?.let{Hub.main.removeCallbacks(it)};timer=null}
    fun close(){cancel();cache.clear()}
}
