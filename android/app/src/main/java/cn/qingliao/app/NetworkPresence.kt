package cn.qingliao.app

import android.content.Context
import android.net.*
import android.os.Build
import android.telephony.*

/** Coarse active connection only; never reads SSID, addresses, carrier name, phone number or SIM identity. */
object NetworkPresence {
    private val kinds=setOf("","wifi","2g","3g","4g","5g","mobile","ethernet","vpn","other")
    var kind="";private set
    private var manager:TelephonyManager?=null
    private var callback:Any?=null
    private var subscription=Int.MIN_VALUE
    private var displayBase=0;private var displayOverride=0
    fun label(value:String=kind)=when(value){"wifi"->"Wi-Fi";"2g"->"2G";"3g"->"3G";"4g"->"4G";"5g"->"5G";"mobile"->"移动网络";"ethernet"->"有线网络";"vpn"->"VPN";"other"->"网络";else->""}
    fun online(value:String)=label(value).let{if(it.isEmpty())"在线"else"在线 · $it"}
    fun cellular(base:Int,override:Int)=when {
        base==20||override in setOf(3,4,5)->"5g"
        base in setOf(13,19)->"4g"
        base in setOf(3,5,6,8,9,10,12,14,15,17)->"3g"
        base in setOf(1,2,4,7,11,16)->"2g"
        else->"mobile"
    }
    private class Display(private val changed:(TelephonyDisplayInfo)->Unit):TelephonyCallback(),TelephonyCallback.DisplayInfoListener{
        override fun onDisplayInfoChanged(info:TelephonyDisplayInfo){changed(info)}
    }
    private fun bindRadio(){
        if(Build.VERSION.SDK_INT<31)return
        val id=runCatching{SubscriptionManager.getActiveDataSubscriptionId().takeIf{it>=0}?:SubscriptionManager.getDefaultDataSubscriptionId()}.getOrDefault(-1)
        if(subscription==id)return
        val old=callback;if(old is TelephonyCallback)runCatching{manager?.unregisterTelephonyCallback(old)}
        subscription=id;displayBase=0;displayOverride=0;callback=null
        val service=Hub.app.getSystemService(TelephonyManager::class.java)?:return
        manager=if(id>=0)service.createForSubscriptionId(id)else service
        val listener=Display{info->if(subscription==id){displayBase=info.networkType;displayOverride=info.overrideNetworkType;refresh()}}
        runCatching{manager?.registerTelephonyCallback({r->Hub.main.post(r)},listener);callback=listener}
    }
    @Suppress("DEPRECATION","MissingPermission") fun refresh(){
        bindRadio()
        val connectivity=Hub.app.getSystemService(ConnectivityManager::class.java)
        val caps=connectivity.activeNetwork?.let{connectivity.getNetworkCapabilities(it)}
        val value=when {
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)!=true->""
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)->"wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)->{
                val base=if(Build.VERSION.SDK_INT>=33)runCatching{Hub.app.getSystemService(TelephonyManager::class.java).dataNetworkType}.getOrDefault(displayBase)
                    else if(Build.VERSION.SDK_INT<31)runCatching{connectivity.activeNetworkInfo?.subtype?:0}.getOrDefault(0)else displayBase
                cellular(base,if(displayBase==base)displayOverride else 0)
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)->"ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)->"vpn"
            else->"other"
        }
        if(value!=kind){kind=value;Hub.localNetworkChanged()}
    }
    /** Report value is allowlisted even if a future platform supplies an unexpected classification. */
    fun packet()=org.json.JSONObject().put("event","presence.network").put("network",kind.takeIf{it in kinds}.orEmpty())
}
