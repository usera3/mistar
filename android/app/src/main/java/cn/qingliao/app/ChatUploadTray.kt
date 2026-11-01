package cn.qingliao.app

import android.content.Context
import android.view.*
import android.widget.*
import java.util.Locale

/** Transient, current-chat transfer status. Never blocks the editor or pretends to be a sent message. */
class ChatUploadTray(context:Context,private val p:ThemePalette):LinearLayout(context){
    data class Item(val id:String,val name:String,val update:AttachmentUpload.Update,val cancel:()->Unit)
    private val u=FormUi(context,p)
    private var signature=""
    private val progressViews=mutableMapOf<String,Pair<TextView,View>>()
    init{orientation=VERTICAL;tag="chat.uploads";visibility=GONE;setPadding(u.dp(6),u.dp(2),u.dp(6),u.dp(4))}
    fun display(items:List<Item>){
        val key=items.joinToString("|"){it.id+it.name}
        if(signature==key){items.forEach{update(it)};return};signature=key;removeAllViews();progressViews.clear();visibility=if(items.isEmpty())GONE else VISIBLE
        if(items.isEmpty())return
        // Bounded compact scroll: concurrent transfers cannot take over the conversation.
        val list=u.col();val scroll=ScrollView(context).apply{isVerticalScrollBarEnabled=false;addView(list)}
        for(item in items){
            val row=u.row().apply{tag="chat.upload.${item.id}";background=BrandUi.rounded(context,p.soft,10);setPadding(u.dp(9),u.dp(3),0,u.dp(3))}
            row.addView(Glyph(context,"file",p.accent),u.lp(18,18));val texts=u.col().apply{setPadding(u.dp(8),0,u.dp(3),0)}
            texts.addView(u.text(item.name,if(u.tiny)10f else 12f,p.ink).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},u.lp())
            val progress=u.text("",if(u.tiny)9f else 10f,p.muted).apply{tag="chat.upload.progress";maxLines=1}
            texts.addView(progress,u.lp());row.addView(texts,LayoutParams(0,-2,1f))
            val cancel=u.icon("close","取消发送 ${item.name}"){item.cancel()}.apply{tag="chat.upload.cancel.${item.id}"}
            progressViews[item.id]=progress to cancel;update(item);row.addView(cancel,u.lp(40,40));list.addView(row,u.lp().apply{bottomMargin=u.dp(3)})
        }
        addView(scroll,u.lp(-1,if(items.size==1)50 else if(u.tiny)68 else 100))
    }
    private fun update(item:Item){val pair=progressViews[item.id]?:return;val v=item.update;val suffix=if(v.total>0)" · ${(v.count*100/v.total).coerceIn(0,100)}%"else if(v.count>0)" · "+bytes(v.count)else"";pair.first.text=v.stage+suffix;pair.second.isEnabled=v.cancelable;pair.second.alpha=if(v.cancelable)1f else .35f}
    private fun bytes(n:Long)=if(n>=1048576)String.format(Locale.CHINA,"%.1f MB",n/1048576.0)else"${(n/1024).coerceAtLeast(1)} KB"
}
