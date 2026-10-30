package cn.qingliao.app

import android.app.*
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat

/** An invitation can open the call; it can never approve control from a notification. */
internal object AssistNotices {
    private var current=""
    fun update(request:String){
        val manager=Hub.app.getSystemService(NotificationManager::class.java)
        if(request.isEmpty()){if(current.isNotEmpty())manager.cancel(110);current="";return}
        if(request==current)return;current=request
        manager.createNotificationChannel(NotificationChannel("remote-assistance","远程协助申请",NotificationManager.IMPORTANCE_DEFAULT).apply{setSound(null,null);enableVibration(false)})
        val owner=Hub.me.optString("id");val call=Hub.call?.optString("id").orEmpty()
        val intent=Intent(Hub.app,MainActivity::class.java).setData(Uri.parse("qingliao://assist/$owner/$call/$request")).putExtra("owner",owner).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open=PendingIntent.getActivity(Hub.app,request.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val redacted=NotificationCompat.Builder(Hub.app,"remote-assistance").setSmallIcon(R.drawable.ic_notification).setContentTitle("远程协助申请").setContentText("请打开弥星查看").build()
        val notice=NotificationCompat.Builder(Hub.app,"remote-assistance").setSmallIcon(R.drawable.ic_notification).setContentTitle("远程协助申请")
            .setContentText("通话对方希望协助操作，请打开弥星确认").setContentIntent(open).setAutoCancel(true).setTimeoutAfter(120000).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(redacted).build()
        runCatching{manager.notify(110,notice)}
    }
}
