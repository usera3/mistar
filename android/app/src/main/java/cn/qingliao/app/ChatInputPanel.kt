package cn.qingliao.app

import android.content.Context
import android.graphics.Typeface
import android.view.*
import android.widget.*

/** Small native input drawer. Cursor insertions remain in the current account/chat's original editor. */
internal class ChatInputPanel(context:Context,p:ThemePalette,private val input:EditText,private val valid:()->Boolean,
    private val actions:List<Action>,private val changed:()->Unit):LinearLayout(context){
    data class Action(val kind:String,val title:String,val enabled:()->Boolean={true},val run:()->Unit)
    private val u=FormUi(context,p)
    private val content=u.col()
    private val note=u.text("",11f,p.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;visibility=View.GONE;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;tag="composer.panel.note"}
    private var mode=""
    private val tabs=mutableMapOf<String,TextView>()
    val opened get()=mode.isNotEmpty()
    val currentMode get()=mode
    init{
        orientation=VERTICAL;visibility=View.GONE;tag="composer.panel";background=BrandUi.rounded(context,p.canvas,18)
        val header=u.row()
        listOf("more" to "功能","emoji" to "表情").forEach{(key,title)->
            val tab=u.link(title,"composer.tab.$key"){show(key)}.apply{minWidth=u.dp(52)};tabs[key]=tab;header.addView(tab,u.lp(-2,if(u.compact)40 else 44))
        }
        header.addView(View(context),LinearLayout.LayoutParams(0,1,1f));header.addView(u.icon("close","收起输入面板"){hide()}.apply{tag="composer.panel.close"},u.lp(40,40));addView(header,u.lp())
        addView(ScrollView(context).apply{isVerticalScrollBarEnabled=false;addView(content)},u.lp(-1,if(u.compact)110 else 170));addView(note,u.lp())
    }
    fun toggle(next:String){if(mode==next&&opened)hide()else show(next)}
    fun hide(){if(mode.isEmpty()&&visibility==View.GONE)return;mode="";visibility=View.GONE;content.removeAllViews();note.visibility=View.GONE;changed()}
    fun show(next:String){
        if(!valid())return
        mode=next;visibility=View.VISIBLE;content.removeAllViews();note.visibility=View.GONE
        tabs.forEach{(key,v)->v.setTextColor(if(key==next)u.p.accent else u.p.muted);v.setTypeface(v.typeface,if(key==next)Typeface.BOLD else Typeface.NORMAL)}
        if(next=="emoji")emojis()else tools()
        changed();Motion.reveal(content,y=3f,duration=140)
    }
    private fun tools(){
        val columns=if(u.compact)2 else 3
        actions.chunked(columns).forEach{group->
            val row=u.row()
            for(n in 0 until columns){val action=group.getOrNull(n)
                val cell=u.col().apply{gravity=Gravity.CENTER;minimumHeight=u.dp(if(u.compact)64 else 76);setPadding(u.dp(3),u.dp(6),u.dp(3),u.dp(6))}
                if(action!=null){
                    cell.tag="composer.action.${action.kind}";cell.contentDescription=action.title;cell.isFocusable=true;u.touch(cell);cell.background=BrandUi.rounded(context,u.p.surface,16)
                    cell.addView(Glyph(context,action.kind,u.p.accent),u.lp(22,22));u.gap(cell,6);cell.addView(u.text(action.title,12f,u.p.ink).apply{gravity=Gravity.CENTER;maxLines=2},u.lp())
                    cell.isEnabled=action.enabled();cell.alpha=if(cell.isEnabled)1f else .4f
                    cell.setOnClickListener{if(valid()&&action.enabled()){hide();action.run()}}
                };row.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(u.dp(3),u.dp(3),u.dp(3),u.dp(3))})
            };content.addView(row,u.lp())
        }
    }
    private fun insert(value:String){
        if(!valid())return
        val a=minOf(input.selectionStart,input.selectionEnd).coerceIn(0,input.length());val b=maxOf(input.selectionStart,input.selectionEnd).coerceIn(a,input.length())
        if(input.length()-(b-a)+value.length>5000){note.text="已达到消息长度上限";note.visibility=View.VISIBLE;return}
        input.text.replace(a,b,value);input.setSelection(a+value.length);note.visibility=View.GONE
    }
    private fun emojis(){
        val values=listOf("😊","🥰","😂","🥺","😎","🤔","😴","😭","👍","❤️","🌷","☀️","🌙","🍀","✨","🎉","👋","🙏","🤗","😅","🥳","🙈","💜","⭐","🐱","🌊","☕","🍰","🎵","💬","🚀","🏝️")
        val columns=if(u.compact)4 else 8
        values.chunked(columns).forEach{group->val row=u.row();group.forEach{emoji->row.addView(u.text(emoji,26f).apply{gravity=Gravity.CENTER;minimumHeight=u.dp(48);contentDescription="插入表情 $emoji";tag="composer.emoji.$emoji";isFocusable=true;setOnClickListener{insert(emoji)};Motion.press(this)},LinearLayout.LayoutParams(0,-2,1f))};content.addView(row,u.lp())}
        content.addView(u.link("⌫  删除","composer.emoji.delete"){
            if(valid()){input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DEL));input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DEL))}
        },u.lp())
    }
}
