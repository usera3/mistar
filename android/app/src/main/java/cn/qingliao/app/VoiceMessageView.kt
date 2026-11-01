package cn.qingliao.app

import android.content.Context
import android.content.res.ColorStateList
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*

/** Inline controls reflect measured player duration/position. Audio bars are decorative, not sampled amplitude. */
internal class VoiceMessageView(context:Context,p:ThemePalette,private val key:String,private val caption:String,
    color:Int,secondary:Int,widthPx:Int,private val toggle:()->Unit,seek:(Int)->Unit,rate:()->Unit):LinearLayout(context){
    private val u=FormUi(context,p)
    private val maximum=widthPx
    private val hintedDuration=captionMillis(caption)
    private val control=FrameLayout(context).apply{contentDescription="播放语音";tag="voice.play:$key";isFocusable=true;setOnClickListener{toggle()}}
    private val glyph=Glyph(context,"play",color)
    private val label=u.text(caption.ifBlank{"语音"},13f,secondary)
    private val details=u.col()
    private val progress=SeekBar(context).apply{tag="voice.seek:$key";contentDescription="语音播放进度";max=1;isEnabled=false;progressTintList=ColorStateList.valueOf(color);thumbTintList=ColorStateList.valueOf(color);minimumHeight=u.dp(40);setPadding(u.dp(2),0,u.dp(2),0)}
    private val time=u.text("",10f,secondary)
    private val speed=u.text("1×",11f,color,true).apply{tag="voice.speed:$key";gravity=Gravity.CENTER;minimumHeight=u.dp(40);minWidth=u.dp(44);isFocusable=true;setOnClickListener{rate()}}
    private val note=u.text("",11f,secondary).apply{tag="voice.note:$key";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;visibility=View.GONE}
    private var dragging=false
    private var shownKind="play"
    private var currentState=VoicePlayback.State(key)
    init{
        orientation=VERTICAL;tag="voice.widget:$key";layoutParams=LinearLayout.LayoutParams(widthPx,-2)
        val main=u.row();control.addView(glyph,FrameLayout.LayoutParams(u.dp(17),u.dp(17),Gravity.CENTER));main.addView(control,u.lp(32,32))
        main.addView(VoiceBars(context,color),LinearLayout.LayoutParams(0,u.dp(14),1f).apply{marginStart=u.dp(3);marginEnd=u.dp(7)})
        label.maxLines=1;label.ellipsize=android.text.TextUtils.TruncateAt.END;label.maxWidth=(widthPx-u.dp(78)).coerceAtLeast(u.dp(38));main.addView(label,u.lp(-2,-2));addView(main,u.lp())
        val bottom=u.row();time.maxLines=1;time.ellipsize=android.text.TextUtils.TruncateAt.END;bottom.addView(time,u.lp(34,32));bottom.addView(progress,LinearLayout.LayoutParams(0,u.dp(32),1f));speed.minimumHeight=u.dp(32);speed.minWidth=u.dp(36);bottom.addView(speed,u.lp(36,32));details.addView(bottom,u.lp());details.visibility=View.GONE;addView(details,u.lp());note.maxLines=2;addView(note,u.lp())
        progress.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(bar:SeekBar){dragging=true;parent?.requestDisallowInterceptTouchEvent(true)}
            override fun onProgressChanged(bar:SeekBar,value:Int,user:Boolean){if(user)time.text=clock(value)}
            override fun onStopTrackingTouch(bar:SeekBar){dragging=false;parent?.requestDisallowInterceptTouchEvent(false);seek(bar.progress)}
        })
        control.accessibilityDelegate=object:View.AccessibilityDelegate(){override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(host,info);info.className=Button::class.java.name}}
        progress.accessibilityDelegate=object:View.AccessibilityDelegate(){
            override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(host,info);info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,0f,currentState.duration/1000f,currentState.position/1000f);info.contentDescription="语音进度，"+clock(currentState.position)+"，总时长 "+clock(currentState.duration)}
            override fun performAccessibilityAction(host:View,action:Int,args:android.os.Bundle?):Boolean{
                if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id&&progress.isEnabled&&args!=null){seek((args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)*1000).toInt());return true};return super.performAccessibilityAction(host,action,args)
            }
        }
        setOnClickListener{toggle()}
    }
    fun render(state:VoicePlayback.State){
        currentState=state
        val playing=state.phase=="playing"
        val kind=if(playing)"pause"else if(state.phase=="loading")"close"else"play"
        if(kind!=shownKind){shownKind=kind;control.removeAllViews();control.addView(Glyph(context,kind,glyph.color),FrameLayout.LayoutParams(u.dp(19),u.dp(19),Gravity.CENTER))}
        control.contentDescription=when(state.phase){"playing"->"暂停语音";"loading"->"取消加载语音";"paused"->"继续播放语音";else->"播放语音"}
        // Idle/saved/ended messages stay as one short bubble. Only the active player expands one row.
        val saved=state.phase=="saved"&&state.position>0
        val duration=if(state.duration>0)state.duration else hintedDuration
        val value=if(duration>0)if(duration<60000)"${(duration+999)/1000}″"else clock(duration)else"语音";if(label.text.toString()!=value)label.text=value
        label.contentDescription=if(saved)"从 "+clock(state.position)+" 继续，总时长 "+clock(state.duration)else "总时长 $value"
        val expanded=state.duration>0&&state.phase in setOf("playing","paused")
        val base=u.dp(if(u.tiny)80 else 92);val cap=minOf(maximum,u.dp(224));val seconds=(duration/1000f).coerceIn(0f,60f)
        val collapsed=(base+(cap-base).coerceAtLeast(0)*seconds/60f).toInt().coerceAtMost(maximum)
        val width=(if(expanded)maxOf(collapsed,u.dp(160))else collapsed).coerceAtMost(maximum)
        if(minimumWidth!=width||layoutParams.width!=width){minimumWidth=width;layoutParams=layoutParams.apply{this.width=width}}
        label.maxWidth=(width-u.dp(56)).coerceAtLeast(u.dp(24))
        details.visibility=if(expanded)View.VISIBLE else View.GONE;progress.isEnabled=expanded
        if(!dragging){progress.max=state.duration.coerceAtLeast(1);progress.progress=state.position;val t=clock(state.position);if(time.text.toString()!=t)time.text=t}
        speed.text=when(state.speed){1.5f->"1.5×";2f->"2×";else->"1×"};speed.contentDescription="播放速度 ${speed.text}，点击切换";speed.isEnabled=progress.isEnabled
        val hint=if(state.error.isNotEmpty())state.error else if(state.phase=="loading")"正在加载，点按取消"else""
        if(note.text.toString()!=hint)note.text=hint;note.visibility=if(hint.isEmpty())View.GONE else View.VISIBLE
    }
    companion object{
        fun captionMillis(value:String):Int{val seconds=Regex("^\\s*([0-9]{1,4})\\s*[″\"秒s]").find(value)?.groupValues?.get(1)?.toIntOrNull();if(seconds!=null)return seconds.coerceIn(0,3600)*1000;return 0}
        fun clock(ms:Int):String="%d:%02d".format(java.util.Locale.ROOT,(ms.coerceAtLeast(0)/60000),(ms.coerceAtLeast(0)/1000)%60)
    }
}
