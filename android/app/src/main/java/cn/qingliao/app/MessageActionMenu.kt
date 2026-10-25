package cn.qingliao.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.*
import android.os.Build
import android.view.*
import android.widget.*

/** A contextual window attached to the selected bubble, with no navigation or copied message preview. */
class MessageActionMenu private constructor(content:View,width:Int,height:Int,focusable:Boolean):PopupWindow(content,width,height,focusable) {
    companion object {
        fun show(activity:Activity,anchor:View,actions:List<ActionSheets.Action>,textSelection:Boolean,valid:()->Boolean,onDismiss:(MessageActionMenu)->Unit):MessageActionMenu? {
            if(actions.isEmpty()||activity.isFinishing||activity.isDestroyed||!anchor.isAttachedToWindow)return null
            val theme=ThemeStore.current(activity);val density=activity.resources.displayMetrics.density
            fun dp(n:Int)=(n*density).toInt()
            val safe=Rect();activity.window.decorView.getWindowVisibleDisplayFrame(safe);safe.inset(dp(8),dp(8))
            fun screenRect(view:View,rect:Rect):Boolean{if(!view.getLocalVisibleRect(rect))return false;val point=IntArray(2);view.getLocationOnScreen(point);rect.offset(point[0],point[1]);return true}
            val selected=Rect();if(!screenRect(anchor,selected)||safe.width()<dp(100)||safe.height()<dp(70))return null
            val font=activity.resources.configuration.fontScale
            val narrow=safe.width()/density<330||font>1.35f
            val columns=minOf(actions.size,if(narrow){if(safe.width()/density>=250&&font<=1.6f)3 else 2}else 5)
            val width=minOf(safe.width(),dp(if(columns<=2)220 else if(columns==3)264 else 324))
            val menuColor=Color.rgb(88,88,88)
            val root=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;contentDescription="消息操作菜单"}
            val card=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;background=GradientDrawable().apply{setColor(menuColor);cornerRadius=dp(4).toFloat()};setPadding(dp(7),dp(7),dp(7),dp(7))}
            val grid=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
            lateinit var popup:MessageActionMenu
            actions.chunked(columns).forEachIndexed{index,group->
                if(index>0)grid.addView(View(activity).apply{setBackgroundColor(Color.argb(28,255,255,255))},LinearLayout.LayoutParams(-1,dp(1)).apply{marginStart=dp(7);marginEnd=dp(7);topMargin=dp(7);bottomMargin=dp(7)})
                val row=LinearLayout(activity)
                group.forEach{action->
                    val label=when(action.title){"复制文字"->"复制";"引用回复"->"引用";"删除这条记录"->"删除";"撤回消息"->"撤回";"查看图片"->"查看";"保存图片","保存到手机"->"保存";"用其他应用打开"->"打开";"展开 HTML 消息"->"展开";"复制 HTML 源码"->"源码";"播放语音"->"播放";"停止播放"->"停止";"复制识别文字"->"复制文字";"重试发送"->"重试";"取消发送"->"取消";else->action.title}
                    val color=Color.WHITE
                    val cell=LinearLayout(activity).apply{
                        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;minimumHeight=dp(60)
                        setPadding(dp(3),dp(5),dp(3),dp(5));isFocusable=true;contentDescription=action.title+(if(action.detail.isEmpty())""else"，"+action.detail)
                        background=RippleDrawable(ColorStateList.valueOf(Color.argb(40,255,255,255)),null,GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(4).toFloat()})
                        addView(Glyph(activity,if(action.title=="删除这条记录")"trash"else if(action.title=="引用回复")"quote"else action.icon,color),LinearLayout.LayoutParams(dp(21),dp(21)))
                        addView(TextView(activity).apply{text=label;textSize=12f;setTextColor(color);gravity=Gravity.CENTER;maxLines=2;includeFontPadding=false;setPadding(0,dp(4),0,0)},LinearLayout.LayoutParams(-1,-2))
                        setOnClickListener{val allowed=valid();popup.dismiss();if(allowed)action.run()}
                    }
                    Motion.press(cell);row.addView(cell,LinearLayout.LayoutParams(0,-2,1f))
                }
                repeat(columns-group.size){row.addView(View(activity),LinearLayout.LayoutParams(0,1,1f))};grid.addView(row)
            }
            if(actions.any{it.detail.contains("微信听写")})grid.addView(TextView(activity).apply{text="微信听写 · 仅识别所选语音";textSize=10f;setTextColor(Color.LTGRAY);gravity=Gravity.CENTER;setPadding(dp(5),dp(5),dp(5),dp(4))})
            val scroll=ScrollView(activity).apply{isVerticalScrollBarEnabled=true;addView(grid)}
            card.addView(scroll,LinearLayout.LayoutParams(-1,-2))
            card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(safe.height()-dp(8),View.MeasureSpec.AT_MOST))
            val height=minOf(safe.height(),card.measuredHeight+dp(7))
            val above=selected.top-safe.top>=height+dp(7)
            val below=!above&&safe.bottom-selected.bottom>=height+dp(7)
            val top=when{above->selected.top-height-dp(7);below->selected.bottom+dp(7);else->(selected.centerY()-height/2).coerceIn(safe.top,safe.bottom-height)}
            val x=(selected.centerX()-width/2).coerceIn(safe.left,safe.right-width)
            val arrow=object:View(activity){val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=menuColor};override fun onDraw(canvas:Canvas){
                val point=(selected.centerX()-x).coerceIn(dp(22),width-dp(22)).toFloat();val h=this.height.toFloat()
                val path=Path().apply{if(above){moveTo(point-dp(7),0f);lineTo(point+dp(7),0f);lineTo(point,h)}else{moveTo(point-dp(7),h);lineTo(point+dp(7),h);lineTo(point,0f)};close()};canvas.drawPath(path,paint)
            }}
            if(below)root.addView(arrow,LinearLayout.LayoutParams(-1,dp(7)))
            root.addView(card,LinearLayout.LayoutParams(-1,height-if(above||below)dp(7)else 0))
            if(above)root.addView(arrow,LinearLayout.LayoutParams(-1,dp(7)))
            popup=MessageActionMenu(root,width,height,!textSelection).apply{
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));isOutsideTouchable=true;isClippingEnabled=true
                inputMethodMode=INPUT_METHOD_NOT_NEEDED;softInputMode=WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                if(Build.VERSION.SDK_INT>=29){setIsClippedToScreen(true);setIsLaidOutInScreen(true)}
                animationStyle=if(Motion.enabled())R.style.QingMotionMenu else 0
            }
            val baseline=Rect(selected);val frame=Rect(safe)
            val watcher=ViewTreeObserver.OnGlobalLayoutListener{
                val current=Rect();val visible=Rect();activity.window.decorView.getWindowVisibleDisplayFrame(visible);visible.inset(dp(8),dp(8))
                if(!valid()||!screenRect(anchor,current)||current!=baseline||visible!=frame)popup.dismiss()
            }
            val detach=object:View.OnAttachStateChangeListener{override fun onViewAttachedToWindow(v:View){};override fun onViewDetachedFromWindow(v:View){popup.dismiss()}}
            popup.setOnDismissListener{anchor.removeOnAttachStateChangeListener(detach);if(activity.window.decorView.viewTreeObserver.isAlive)activity.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(watcher);onDismiss(popup)}
            try{
                popup.showAtLocation(activity.window.decorView,Gravity.TOP or Gravity.LEFT,x,top)
                activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(watcher);anchor.addOnAttachStateChangeListener(detach)
                root.announceForAccessibility("消息操作菜单");return popup
            }catch(_:RuntimeException){popup.dismiss();return null}
        }
    }
}
