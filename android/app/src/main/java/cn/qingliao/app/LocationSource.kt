package cn.qingliao.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.*

/** Android system providers only; every listener is owned by a visible picker or an explicit share. */
class LocationSource(private val context:Context,private val changed:(Location)->Unit,private val problem:(String)->Unit):LocationListener {
    private val manager=context.getSystemService(LocationManager::class.java)
    private val handler=Handler(Looper.getMainLooper());private var active=false
    private var timeout:Runnable?=null
    val approximate get()=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED
    fun permitted()=context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||!approximate
    fun fresh(fix:Location):Boolean {
        val age=(SystemClock.elapsedRealtimeNanos()-fix.elapsedRealtimeNanos)/1000000
        return fix.latitude.isFinite()&&fix.longitude.isFinite()&&fix.latitude in -90.0..90.0&&fix.longitude in -180.0..180.0&&fix.hasAccuracy()&&fix.accuracy.isFinite()&&fix.accuracy in 0f..50000f&&age in 0..120000&&fix.time in (System.currentTimeMillis()-180000)..(System.currentTimeMillis()+10000)
    }
    fun start(){
        stop();if(!permitted()){problem("请允许定位权限后重试");return};active=true
        try{
            val providers=listOf(LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER).filter{(it!=LocationManager.GPS_PROVIDER||!approximate)&&manager.isProviderEnabled(it)}
            if(providers.isEmpty()){stop();problem("系统定位未开启或设备没有可用定位服务");return}
            val cached=providers.mapNotNull{runCatching{manager.getLastKnownLocation(it)}.getOrNull()}.filter{fresh(it)}.minByOrNull{it.accuracy}
            cached?.let{changed(Location(it))}
            for(provider in providers)manager.requestLocationUpdates(provider,3000L,3f,this,Looper.getMainLooper())
            timeout=Runnable{if(active)problem("暂未取得新的位置，请到开阔处重试")}.also{handler.postDelayed(it,30000)}
        }catch(_:SecurityException){stop();problem("定位权限已被撤销，请重新允许")}
        catch(_:Exception){stop();problem("定位服务暂不可用，请重试")}
    }
    override fun onLocationChanged(location:Location){if(active&&permitted()&&(!approximate||location.provider!=LocationManager.GPS_PROVIDER)&&fresh(location)){timeout?.let{handler.removeCallbacks(it)};timeout=null;changed(Location(location))}}
    override fun onProviderEnabled(provider:String){}
    override fun onProviderDisabled(provider:String){if(active)problem("定位服务已关闭，请重新开启")}
    @Deprecated("Legacy Android callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
    fun stop(){active=false;timeout?.let{handler.removeCallbacks(it)};timeout=null;runCatching{manager.removeUpdates(this)}}
}
