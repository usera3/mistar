package cn.qingliao.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PresenceText {
    fun offline(lastSeen: Long, now: Long = System.currentTimeMillis()): String {
        if (lastSeen <= 0L) return "离线 · 可留言"
        val seconds = ((now - lastSeen).coerceAtLeast(0L)) / 1000
        return when {
            seconds < 60 -> "刚刚在线"
            seconds < 3600 -> "${seconds / 60} 分钟前在线"
            seconds < 86400 -> "${seconds / 3600} 小时前在线"
            seconds < 7 * 86400 -> "${seconds / 86400} 天前在线"
            else -> SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date(lastSeen)) + " 在线"
        }
    }
}
