package cn.qingliao.app

import android.content.*

/** Boot only restores remoteMessaging. Microphone/camera still require accepting a call. */
class BootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        val prefs=context.getSharedPreferences("background",Context.MODE_PRIVATE)
        if(Hub.token.isEmpty()||!prefs.getBoolean("restore_on_boot",true))return
        prefs.edit().putLong("last_boot_restore",System.currentTimeMillis()).apply()
        Hub.startService()
    }
}
