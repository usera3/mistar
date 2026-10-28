package cn.qingliao.app

import android.content.Context
import android.os.Bundle
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import kotlin.math.abs

/** Direction-locking reply gesture. Vertical scrolling, text selection and seek bars retain ownership. */
internal class ReplySwipeFrame(context:Context,private val content:View,private val allowed:()->Boolean,private val reply:()->Unit):FrameLayout(context){
    private val u=FormUi(context,ThemeStore.current(context))
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var x=0f;private var y=0f;private var tracking=false;private var vertical=false;private var blocked=false;private var distance=0f;private var haptic=false
    private val indicator=FrameLayout(context).apply{
        alpha=0f;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background=BrandUi.rounded(context,u.p.soft,20)
        addView(Glyph(context,"reply",u.p.accent),LayoutParams(u.dp(19),u.dp(19),Gravity.CENTER))
    }
    init{
        clipChildren=false;clipToPadding=false
        addView(indicator,LayoutParams(u.dp(34),u.dp(34),Gravity.START or Gravity.CENTER_VERTICAL).apply{marginStart=u.dp(6)})
        addView(content,LayoutParams(-1,-2))
        accessibilityDelegate=object:View.AccessibilityDelegate(){
            override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(host,info);if(allowed())info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.message_reply_action,"引用回复"))}
            override fun performAccessibilityAction(host:View,action:Int,args:Bundle?):Boolean{
                if(action==R.id.message_reply_action&&allowed()){reply();return true};return super.performAccessibilityAction(host,action,args)
            }
        }
    }
    private fun protectedTarget(v:View,px:Float,py:Float):Boolean{
        if(v.visibility!=View.VISIBLE||px<0||py<0||px>=v.width||py>=v.height)return false
        if(v is SeekBar||v is EditText)return true
        if(v is ViewGroup)for(n in v.childCount-1 downTo 0){val c=v.getChildAt(n);if(protectedTarget(c,px+v.scrollX-c.left,py+v.scrollY-c.top))return true}
        return false
    }
    private fun selectedText(v:View):Boolean{
        if(v is TextView&&v.isTextSelectable&&v.selectionStart>=0&&v.selectionStart!=v.selectionEnd)return true
        return v is ViewGroup&&(0 until v.childCount).any{selectedText(v.getChildAt(it))}
    }
    override fun onInterceptTouchEvent(e:android.view.MotionEvent):Boolean{
        when(e.actionMasked){
            android.view.MotionEvent.ACTION_DOWN->{reset(false);x=e.x;y=e.y;blocked=protectedTarget(content,e.x-content.left,e.y-content.top);return false}
            android.view.MotionEvent.ACTION_MOVE->{
                if(e.pointerCount!=1||!allowed()||blocked||vertical||selectedText(content))return false
                if(tracking)return true
                val dx=e.x-x;val dy=e.y-y
                if(abs(dy)>slop&&abs(dy)>abs(dx)){vertical=true;return false}
                if(dx>u.dp(18)&&dx>slop*1.5f&&dx>abs(dy)*1.6f){tracking=true;parent?.requestDisallowInterceptTouchEvent(true);return true}
            }
            android.view.MotionEvent.ACTION_CANCEL,android.view.MotionEvent.ACTION_UP->{if(!tracking)reset(false)}
        };return false
    }
    override fun onTouchEvent(e:android.view.MotionEvent):Boolean{
        if(!tracking)return super.onTouchEvent(e)
        when(e.actionMasked){
            android.view.MotionEvent.ACTION_MOVE->{
                if(e.pointerCount!=1||!allowed()){reset(true);return true}
                distance=(e.x-x).coerceIn(0f,u.dp(72).toFloat());content.translationX=distance
                indicator.alpha=(distance/u.dp(48)).coerceIn(0f,1f)
                if(distance>=u.dp(48)&&!haptic){haptic=true;performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)}
            }
            android.view.MotionEvent.ACTION_UP->{val commit=distance>=u.dp(48)&&allowed();reset(true);if(commit)reply()}
            android.view.MotionEvent.ACTION_CANCEL,android.view.MotionEvent.ACTION_POINTER_DOWN->reset(true)
        };return true
    }
    private fun reset(animate:Boolean){
        tracking=false;vertical=false;blocked=false;distance=0f;haptic=false;parent?.requestDisallowInterceptTouchEvent(false)
        content.animate().cancel();indicator.animate().cancel()
        if(animate&&Motion.enabled()){content.animate().translationX(0f).setDuration(170).setInterpolator(Motion.ease).start();indicator.animate().alpha(0f).setDuration(130).start()}
        else{content.translationX=0f;indicator.alpha=0f}
    }
    override fun onDetachedFromWindow(){reset(false);super.onDetachedFromWindow()}
}
