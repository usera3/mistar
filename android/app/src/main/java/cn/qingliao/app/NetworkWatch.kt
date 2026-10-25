package cn.qingliao.app

import android.content.Context
import android.net.*

/** Coalesce route/capability callbacks; inspect the current default, not a stale lost network. */
class NetworkWatch(context:Context) {
    private val manager=context.getSystemService(ConnectivityManager::class.java)
    private var previous:Network?=manager.activeNetwork
    private val update=Runnable {
        val active=manager.activeNetwork
        val available=active?.let{manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}==true
        val changed=active!=previous;previous=active
        Hub.networkChanged(available,changed)
    }
    private val callback=object:ConnectivityManager.NetworkCallback(){
        private fun changed(){Hub.main.removeCallbacks(update);Hub.main.postDelayed(update,450)}
        override fun onAvailable(network:Network){changed()}
        override fun onLost(network:Network){changed()}
        override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities){changed()}
    }
    fun start(){runCatching{manager.registerDefaultNetworkCallback(callback)}}
}
