package cn.qingliao.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.RippleDrawable
import android.view.*
import android.widget.*

/** Persistent native navigation: move one selection surface, keep labels and touch targets still. */
internal class HomeNavigation(context:Context,val themeColor:Int,private val changed:(Int)->Unit):LinearLayout(context){
    private val p=ThemePalette(themeColor)
    private val u=FormUi(context,p)
    private val compact=CompactUi.enabled(context)
    private val items=mutableListOf<LinearLayout>()
    private val icons=mutableListOf<Glyph>()
    private val labels=mutableListOf<TextView>()
    private var selected=0
    val badge=u.text("",9f,Color.WHITE,true).apply{
        gravity=Gravity.CENTER;minWidth=u.dp(17);minimumHeight=u.dp(17);setPadding(u.dp(4),u.dp(1),u.dp(4),u.dp(1))
        background=BrandUi.rounded(context,0xFFC14149.toInt(),10);visibility=View.GONE
    }
    init {
        tag="home.navigation";orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        setPadding(u.dp(4),u.dp(3),u.dp(4),u.dp(3));setBackgroundColor(p.surface);clipChildren=false
        listOf("chat" to "消息","contacts" to "联系人","moments" to "动态","me" to "我的").forEachIndexed{index,(kind,title)->
            val item=u.col().apply{
                tag="home.nav.$index";contentDescription=title;gravity=Gravity.CENTER;isFocusable=true
                background=RippleDrawable(ColorStateList.valueOf(p.ripple),null,BrandUi.rounded(context,Color.WHITE,16))
                setOnClickListener{if(index!=selected)changed(index)}
            }
            val mark=FrameLayout(context).apply{clipChildren=false}
            val glyph=Glyph(context,kind,p.muted);icons.add(glyph);mark.addView(glyph,FrameLayout.LayoutParams(u.dp(21),u.dp(21),Gravity.CENTER))
            if(index==0)mark.addView(badge,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
            item.addView(mark,u.lp(32,25));u.gap(item,3)
            val label=u.text(title,if(compact)10f else 11f,p.muted).apply{gravity=Gravity.CENTER;maxLines=1;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO};labels.add(label);item.addView(label,u.lp())
            items.add(item);addView(item,LayoutParams(0,u.dp(if(compact)48 else 56),1f))
        }
        select(0,false)
    }
    fun select(index:Int,animate:Boolean){
        require(index in 0..3);selected=index
        items.forEachIndexed{n,item->val active=n==index;item.isSelected=active;icons[n].color=if(active)p.accent else p.ink;if(n==0)icons[n].variant(if(active)"chatFill"else"chat");icons[n].invalidate();labels[n].setTextColor(if(active)p.accent else p.ink);labels[n].setTypeface(null,Typeface.NORMAL)}
        if(animate)Motion.reveal(icons[index],y=0f,duration=140,from=.65f)
    }
    override fun onDetachedFromWindow(){icons.forEach{Motion.rest(it)};super.onDetachedFromWindow()}
}
