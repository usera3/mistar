package cn.qingliao.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.*
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Compact, aligned conversation rows; decorative surfaces stay outside the message preview. */
internal class ConversationRow(context:Context,p:ThemePalette,conversation:JSONObject,portrait:View,preview:String,open:()->Unit,actions:(View)->Unit):LinearLayout(context){
    init {
        val u=FormUi(context,p);val user=conversation.getJSONObject("user");val local=conversation.getJSONObject("local")
        val pinned=local.optLong("pinned_at")>0;val muted=local.optBoolean("muted");val unread=conversation.optInt("unread").coerceAtLeast(0)
        tag="conversation:${user.getString("id")}";gravity=Gravity.CENTER_VERTICAL;minimumHeight=u.dp(if(u.tiny)60 else if(u.compact)70 else 80)
        setPadding(u.dp(if(u.compact)9 else 14),u.dp(if(u.tiny)7 else 11),u.dp(if(u.compact)9 else 14),u.dp(if(u.tiny)7 else 11))
        background=RippleDrawable(ColorStateList.valueOf(p.ripple),BrandUi.rounded(context,if(pinned)p.soft else p.surface,0),null)
        isFocusable=true;isLongClickable=true;setOnClickListener{open()};setOnLongClickListener{actions(this);true}
        val photo=FrameLayout(context);val side=if(u.tiny)36 else if(u.compact)40 else 50
        photo.addView(portrait,FrameLayout.LayoutParams(u.dp(side),u.dp(side)))
        if(unread>0){
            val badge=if(muted)View(context).apply{background=BrandUi.rounded(context,p.muted,8)}else u.text(if(unread>99)"99+"else"$unread",10f,p.onPrimary,true).apply{
                gravity=Gravity.CENTER;minWidth=u.dp(18);minimumHeight=u.dp(18);setPadding(u.dp(4),u.dp(1),u.dp(4),u.dp(1));background=BrandUi.rounded(context,p.primary,12)
            }
            badge.contentDescription=if(muted)"免打扰，$unread 条未读消息"else"$unread 条未读消息"
            photo.addView(badge,FrameLayout.LayoutParams(if(muted)u.dp(8)else -2,if(muted)u.dp(8)else -2,Gravity.TOP or Gravity.END))
        }
        addView(photo,u.lp(side,side))
        val info=u.col().apply{setPadding(u.dp(if(u.compact)10 else 13),0,u.dp(8),0)}
        info.addView(u.text(user.optString("nickname")+if(user.optBoolean("bot"))" · 机器人"else"",if(u.tiny)14f else if(u.compact)15f else 16f,bold=unread>0&&!muted).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END},u.lp())
        u.gap(info,if(u.tiny)4 else 6)
        val draft=ConversationState.hasDraft(local)
        val value=if(draft)"[草稿] "+local.optString("draft").ifEmpty{"引用："+local.optJSONObject("quote")?.optString("excerpt").orEmpty()}else if(muted&&unread>0)"[$unread 条] $preview"else preview
        info.addView(u.text(value.replace('\n',' '),if(u.tiny)11f else if(u.compact)12f else 13f,p.muted).apply{
            tag="conversation.preview:${user.getString("id")}";maxLines=1;ellipsize=TextUtils.TruncateAt.END
            if(draft)text=SpannableString(value.replace('\n',' ')).apply{setSpan(ForegroundColorSpan(p.accent),0,4,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
        },u.lp());addView(info,LayoutParams(0,-2,1f))
        val meta=u.col().apply{gravity=Gravity.END}
        meta.addView(u.text(labelTime(maxOf(conversation.getJSONObject("last").optLong("created"),local.optLong("draft_updated"))),10f,p.muted).apply{maxLines=1},u.lp(-2,-2))
        u.gap(meta,8);val marks=u.row()
        if(pinned)marks.addView(Glyph(context,"pin",p.muted).apply{contentDescription="已置顶"},u.lp(13,13))
        if(muted)marks.addView(Glyph(context,"bellOff",p.muted).apply{contentDescription="消息免打扰"},u.lp(14,14).apply{marginStart=u.dp(5)})
        if(!pinned&&!muted)marks.addView(View(context),u.lp(1,14))
        meta.addView(marks);addView(meta)
        contentDescription="${user.optString("nickname")}，${if(draft)"有草稿，"else""}${if(pinned)"已置顶，"else""}${if(muted)"消息免打扰，"else""}$unread 条未读消息"
    }
    companion object {
        fun labelTime(ms:Long,now:Long=System.currentTimeMillis()):String {
            if(ms<=0)return ""
            fun day(t:Long)=Calendar.getInstance().apply{timeInMillis=t;set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)}
            val today=day(now);val date=day(ms)
            if(date.timeInMillis==today.timeInMillis)return SimpleDateFormat("HH:mm",Locale.CHINA).format(Date(ms))
            today.add(Calendar.DAY_OF_YEAR,-1);if(today.timeInMillis==date.timeInMillis)return "昨天"
            today.add(Calendar.DAY_OF_YEAR,-5);if(date.timeInMillis in today.timeInMillis..now)return SimpleDateFormat("EEE",Locale.CHINA).format(Date(ms))
            return SimpleDateFormat(if(Calendar.getInstance().apply{timeInMillis=now}.get(Calendar.YEAR)==date.get(Calendar.YEAR))"M-d"else"yyyy-MM-dd",Locale.CHINA).format(Date(ms))
        }
    }
}
