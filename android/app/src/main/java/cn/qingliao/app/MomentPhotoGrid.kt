package cn.qingliao.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import java.lang.ref.WeakReference
import org.json.JSONArray

/** Measure against the actual card width, including in landscape and split-screen. */
class MomentPhotoGrid(activity:Activity,private val palette:ThemePalette,private val photos:JSONArray,open:(Int)->Unit):ViewGroup(activity) {
    private val count=photos.length()
    private val columns=if(count==1)1 else if(count==2||count==4)2 else 3
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private var cell=0
    private var tileHeight=0
    init { for(i in 0 until count)addView(PhotoTile(activity,palette,photos.getJSONObject(i).getString("id"),i+1,if(count==1)960 else 480) { open(i) }) }
    override fun onMeasure(w:Int,h:Int) {
        val available=MeasureSpec.getSize(w).coerceAtLeast(1)
        val gridWidth=minOf(available,dp(if(count==1)340 else 640))
        cell=((gridWidth-dp(5)*(columns-1))/columns).coerceAtLeast(1)
        val ratio=if(count==1)photos.getJSONObject(0).optInt("height",1).toFloat()/photos.getJSONObject(0).optInt("width",1).coerceAtLeast(1) else 1f
        tileHeight=if(count==1)minOf(dp(360),(cell*ratio.coerceIn(.65f,1.35f)).toInt()) else cell
        for(i in 0 until count)getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cell,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(tileHeight,MeasureSpec.EXACTLY))
        val rows=(count+columns-1)/columns
        setMeasuredDimension(resolveSize(gridWidth,w),resolveSize(rows*tileHeight+(rows-1)*dp(5),h))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        for(i in 0 until count){val x=(i%columns)*(cell+dp(5));val y=(i/columns)*(tileHeight+dp(5));getChildAt(i).layout(x,y,x+cell,y+tileHeight)}
    }
}

private class PhotoTile(activity:Activity,palette:ThemePalette,private val id:String,index:Int,private val limit:Int,open:()->Unit):FrameLayout(activity) {
    private val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val spinner=ProgressBar(activity).apply { isIndeterminate=true;indeterminateTintList=ColorStateList.valueOf(palette.accent) }
    private val retry=TextView(activity).apply { text="重新加载";textSize=12f;setTextColor(palette.accent);gravity=Gravity.CENTER;visibility=GONE;contentDescription="重新加载动态图片 $index" }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    init {
        background=GradientDrawable().apply { setColor(palette.canvas);cornerRadius=dp(10).toFloat() };clipToOutline=true
        contentDescription="查看动态图片 $index";isClickable=true;isFocusable=true;setOnClickListener { open() }
        addView(image,LayoutParams(-1,-1));addView(spinner,LayoutParams(dp(22),dp(22),Gravity.CENTER));addView(retry,LayoutParams(-1,-1))
        val reference=WeakReference(this)
        MomentsImages.lazyLoad(id,image,limit,{ ok -> reference.get()?.showResult(ok) },{ reference.get()?.showLoading() })
        retry.setOnClickListener { showLoading();MomentsImages.load(id,image,{ ok -> reference.get()?.showResult(ok) },limit) }
    }
    private fun showResult(ok:Boolean){spinner.visibility=GONE;retry.visibility=if(ok)GONE else VISIBLE}
    private fun showLoading(){retry.visibility=GONE;spinner.visibility=VISIBLE}
}
