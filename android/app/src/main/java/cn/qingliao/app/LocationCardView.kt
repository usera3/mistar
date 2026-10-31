package cn.qingliao.app

import android.content.Context
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** No remote map tiles in a message bubble. Opening a map is a separate explicit action. */
class LocationCardView(context:Context,p:ThemePalette,private val location:JSONObject,mine:Boolean,width:Int,open:()->Unit):LinearLayout(context){
    private val u=FormUi(context,p);private val state=u.text("",11f,if(mine)p.onPrimary else p.muted)
    private val map=LocationThumbnail(context,p,location,open)
    init{
        orientation=VERTICAL;tag="message.location";LocationPayload.validate(location)
        val color=if(mine)p.onPrimary else p.ink
        addView(map,u.lp(-1,if(u.compact)72 else 100));u.gap(this,10)
        val header=u.row();header.addView(Glyph(context,"location",if(mine)p.onPrimary else p.accent),u.lp(24,28));header.addView(u.text(location.getString("label"),15f,color,true).apply{setPadding(u.dp(8),0,0,0);maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f));addView(header,u.lp())
        u.gap(this,10);addView(u.text(LocationPayload.coordinates(location),12f,color).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},u.lp())
        u.gap(this,6);addView(u.text(LocationPayload.accuracy(location),11f,color),u.lp());u.gap(this,8);addView(state,u.lp())
        layoutParams=LinearLayout.LayoutParams(width.coerceAtLeast(u.dp(100)),-2);contentDescription="查看位置：${location.getString("label")}";isFocusable=true;setOnClickListener{open()};update()
    }
    fun update(){
        map.update()
        val id=location.optString("live_id");val latest=if(id.isNotEmpty())LocationSessions.get(id)else null
        val active=id.isNotEmpty()&&location.optLong("until")>System.currentTimeMillis()&&!LocationSessions.isEnded(id)
        state.text=if(id.isEmpty())"位置快照 · "+SimpleDateFormat("HH:mm",Locale.CHINA).format(Date(location.getLong("at")))+"  ›"else if(active&&latest?.optBoolean("disconnected")!=true)"实时共享 · 点按查看更新  ›"else if(active)"暂未更新 · 点按重新连接  ›"else"实时共享已结束 · 查看快照  ›"
    }
}
