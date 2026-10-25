package cn.qingliao.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import java.lang.ref.WeakReference
import org.json.JSONObject

/** A static authenticated JPEG only. Video bytes and decoders are opened after a tap. */
class MomentVideoTile(context:Context,private val palette:ThemePalette,private val media:JSONObject,open:()->Unit):FrameLayout(context) {
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private val id=media.optString("id")
    private val cover=media.optJSONObject("cover")
    private val image=ImageView(context).apply{scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    private val hint=TextView(context).apply{text="正在加载封面…";textSize=12f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(dp(12),0,dp(12),0)}
    private val play=FrameLayout(context).apply{
        background=GradientDrawable().apply{setColor(0x99000000.toInt());cornerRadius=dp(27).toFloat();setStroke(dp(1),0x66FFFFFF)}
        addView(Glyph(context,"play",Color.WHITE),LayoutParams(dp(24),dp(24),Gravity.CENTER))
    }
    init {
        background=GradientDrawable().apply{setColor(Color.rgb(30,27,43));cornerRadius=dp(16).toFloat()};clipToOutline=true
        contentDescription="播放视频动态";isClickable=true;isFocusable=true;setOnClickListener{open()}
        addView(image,LayoutParams(-1,-1))
        addView(View(context).apply{background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0x88000000.toInt()))},LayoutParams(-1,dp(68),Gravity.BOTTOM))
        addView(play,LayoutParams(dp(54),dp(54),Gravity.CENTER))
        addView(hint,LayoutParams(-1,dp(38),Gravity.BOTTOM or Gravity.START).apply{marginEnd=dp(64);bottomMargin=dp(4)})
        val seconds=(media.optLong("duration")/1000).coerceAtLeast(1)
        addView(TextView(context).apply{text="%02d:%02d".format(seconds/60,seconds%60);textSize=12f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(dp(9),dp(4),dp(9),dp(4));background=GradientDrawable().apply{setColor(0x99000000.toInt());cornerRadius=dp(7).toFloat()};importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply{setMargins(dp(10),dp(10),dp(10),dp(10))})
        if(cover==null){hint.text="暂无封面 · 点按播放"}else{
            val weak=WeakReference(this)
            MomentsImages.lazyLoad(id,image,640,{ok->weak.get()?.result(ok)},{weak.get()?.loading()},cover=true)
        }
    }
    private fun loading(){hint.visibility=VISIBLE;hint.text="正在加载封面…";hint.setOnClickListener(null)}
    private fun result(ok:Boolean){
        hint.visibility=if(ok)GONE else VISIBLE
        hint.text="封面加载失败 · 重试"
        hint.contentDescription="重新加载视频封面"
        hint.setOnClickListener{loading();val weak=WeakReference(this);MomentsImages.load(id,image,{success->weak.get()?.result(success)},640,cover=true)}
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val available=MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val ratio=(cover?.optInt("height",0)?:0).toFloat()/(cover?.optInt("width",0)?:0).coerceAtLeast(1)
        val aspect=if(ratio>0)ratio.coerceIn(.4f,2.2f) else 9f/16
        val width=minOf(available,dp(340), (dp(360)/aspect).toInt()).coerceAtLeast(1)
        val height=(width*aspect).toInt().coerceIn(dp(160),dp(360))
        super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY))
    }
}
