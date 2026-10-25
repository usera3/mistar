package cn.qingliao.app

import android.app.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.view.*
import android.widget.*
import org.json.JSONArray
import kotlin.math.abs

object MomentGallery {
    fun show(activity:Activity,photos:JSONArray,start:Int,save:((String)->Unit)?=null)=open(activity,photos.length(),start,save?.let { action -> { index:Int -> action(photos.getJSONObject(index).getString("id")) } }) { index,image,done ->
        MomentsImages.load(photos.getJSONObject(index).getString("id"),image,done)
    }
    fun showLocal(activity:Activity,photos:List<Uri>,start:Int)=open(activity,photos.size,start,null) { index,image,done ->
        val selected=photos[index];image.tag=selected
        Hub.io.execute {
            val bitmap=try { MomentsImages.decode(activity,selected,1600) }catch(_:Exception){null}
            Hub.main.post { if(image.tag==selected){if(bitmap!=null)image.setImageBitmap(bitmap);done(bitmap!=null)} }
        }
    }
    private fun open(activity:Activity,total:Int,start:Int,save:((Int)->Unit)?,load:(Int,ImageView,(Boolean)->Unit)->Unit):Dialog {
        require(total>0)
        fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
        val dialog=Dialog(activity,android.R.style.Theme_Material_NoActionBar)
        var index=start.coerceIn(0,total-1);var revision=0
        val dark=Color.rgb(18,21,27)
        val root=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(dark) }
        root.setOnApplyWindowInsetsListener { v,insets ->
            val b=if(Build.VERSION.SDK_INT>=30)insets.getInsets(WindowInsets.Type.systemBars()) else null
            v.setPadding((b?.left?:insets.systemWindowInsetLeft)+dp(8),(b?.top?:insets.systemWindowInsetTop)+dp(4),(b?.right?:insets.systemWindowInsetRight)+dp(8),(b?.bottom?:insets.systemWindowInsetBottom)+dp(10));insets
        }
        fun icon(kind:String,title:String,click:()->Unit)=FrameLayout(activity).apply { contentDescription=title;isFocusable=true;Motion.press(this);setOnClickListener { click() };addView(Glyph(activity,kind,Color.WHITE),FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER)) }
        fun text(value:String)=TextView(activity).apply { text=value;textSize=13f;setTextColor(Color.rgb(206,213,225));gravity=Gravity.CENTER }
        val top=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
        top.addView(icon("close","关闭图片预览") { dialog.dismiss() },LinearLayout.LayoutParams(dp(48),dp(48)))
        val count=text("").apply { textSize=16f;setTextColor(Color.WHITE) };top.addView(count,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(if(save!=null)icon("download","保存动态图片") { dialog.dismiss();save(index) } else View(activity),LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(top)
        val image=ZoomPhotoView(activity)
        val previous=ImageView(activity).apply { scaleType=ImageView.ScaleType.MATRIX;visibility=View.GONE }
        val stage=FrameLayout(activity);stage.addView(previous,FrameLayout.LayoutParams(-1,-1));stage.addView(image,FrameLayout.LayoutParams(-1,-1))
        val spinner=ProgressBar(activity);stage.addView(spinner,FrameLayout.LayoutParams(dp(30),dp(30),Gravity.CENTER))
        val retry=text("图片暂时无法加载\n点按重试").apply { visibility=View.GONE;setPadding(dp(28),dp(24),dp(28),dp(24));contentDescription="重新加载大图" };stage.addView(retry,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        root.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        var previousButton:TextView?=null;var nextButton:TextView?=null
        fun update(direction:Int=0) {
            val expected=++revision;Motion.rest(image);Motion.rest(previous)
            image.drawable?.let { previous.setImageDrawable(it);previous.imageMatrix=Matrix(image.imageMatrix);previous.visibility=View.VISIBLE }
            image.tag=null;image.setImageDrawable(null);image.resetZoom();spinner.visibility=View.VISIBLE;retry.visibility=View.GONE
            count.text="${index+1} / $total"
            previousButton?.apply { isEnabled=index>0;alpha=if(isEnabled)1f else .35f };nextButton?.apply { isEnabled=index<total-1;alpha=if(isEnabled)1f else .35f }
            load(index,image) { ok -> if(expected==revision){spinner.visibility=View.GONE;retry.visibility=if(ok)View.GONE else View.VISIBLE;if(ok)Motion.photo(image,previous,direction)else { previous.setImageDrawable(null);previous.visibility=View.GONE }} }
        }
        fun step(delta:Int){val next=index+delta;if(next in 0 until total){index=next;update(delta)}}
        image.swipe={delta -> step(delta)};retry.setOnClickListener { update() }
        val tools=LinearLayout(activity).apply { gravity=Gravity.CENTER }
        tools.addView(icon("minus","缩小图片") { image.zoomBy(.65f) },LinearLayout.LayoutParams(dp(48),dp(44)))
        tools.addView(text("还原").apply { contentDescription="还原图片";setOnClickListener { image.resetZoom() } },LinearLayout.LayoutParams(dp(72),dp(44)))
        tools.addView(icon("add","放大图片") { image.zoomBy(1.6f) },LinearLayout.LayoutParams(dp(48),dp(44)));root.addView(tools)
        if(total>1) {
            val nav=LinearLayout(activity).apply { gravity=Gravity.CENTER }
            previousButton=text("上一张").apply { Motion.press(this);setOnClickListener { step(-1) } };nav.addView(previousButton,LinearLayout.LayoutParams(dp(86),dp(44)))
            nav.addView(text("左右滑动切图").apply { textSize=11f;setTextColor(Color.rgb(149,160,179)) },LinearLayout.LayoutParams(0,dp(44),1f))
            nextButton=text("下一张").apply { Motion.press(this);setOnClickListener { step(1) } };nav.addView(nextButton,LinearLayout.LayoutParams(dp(86),dp(44)));root.addView(nav)
        } else root.addView(text("双指或双击缩放图片").apply { textSize=11f },LinearLayout.LayoutParams(-1,dp(32)))
        dialog.setContentView(root)
        dialog.setOnDismissListener { revision++;Motion.rest(image);Motion.rest(previous);image.tag=null;image.setImageDrawable(null);previous.setImageDrawable(null) }
        dialog.window?.apply { statusBarColor=dark;navigationBarColor=dark;decorView.systemUiVisibility=0;if(Build.VERSION.SDK_INT>=30)setDecorFitsSystemWindows(false) }
        Motion.gallery(dialog.window);dialog.show();dialog.window?.apply {
            setLayout(-1,-1)
            if(Build.VERSION.SDK_INT>=29)isNavigationBarContrastEnforced=false
            if(Build.VERSION.SDK_INT>=30)insetsController?.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        };root.requestApplyInsets();update();return dialog
    }
}

/** Bounded matrix gestures; a zoomed photo pans instead of changing pages. */
private class ZoomPhotoView(context:Context):ImageView(context) {
    var swipe:((Int)->Unit)?=null
    private var zoom=1f
    private var panX=0f
    private var panY=0f
    private var multiple=false
    private val transform=Matrix()
    init { scaleType=ScaleType.MATRIX;contentDescription="动态大图";isFocusable=true }
    override fun setImageBitmap(bitmap:Bitmap?){super.setImageBitmap(bitmap);resetZoom()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);fit()}
    fun resetZoom(){zoom=1f;panX=0f;panY=0f;fit()}
    fun zoomBy(factor:Float){scale(factor,width/2f,height/2f)}
    private fun scale(factor:Float,x:Float,y:Float) {
        val next=(zoom*factor).coerceIn(1f,5f);val ratio=next/zoom
        panX=(x-width/2f)-(x-width/2f-panX)*ratio;panY=(y-height/2f)-(y-height/2f-panY)*ratio;zoom=next;fit()
    }
    private fun fit() {
        val picture=drawable?:return
        val w=picture.intrinsicWidth.toFloat();val h=picture.intrinsicHeight.toFloat();if(w<=0||h<=0||width==0||height==0)return
        val scale=minOf(width/w,height/h)*zoom
        val maxX=maxOf(0f,(w*scale-width)/2);val maxY=maxOf(0f,(h*scale-height)/2)
        panX=panX.coerceIn(-maxX,maxX);panY=panY.coerceIn(-maxY,maxY)
        transform.setScale(scale,scale);transform.postTranslate((width-w*scale)/2+panX,(height-h*scale)/2+panY);imageMatrix=transform
        contentDescription=if(zoom>1.02f)"动态大图 · 已放大 ${(zoom*100).toInt()}%" else "动态大图"
    }
    private val detector=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e:MotionEvent)=true
        override fun onSingleTapConfirmed(e:MotionEvent):Boolean { performClick();return true }
        override fun onDoubleTap(e:MotionEvent):Boolean { scale(if(zoom>1.1f)1f/zoom else 2.5f,e.x,e.y);return true }
        override fun onScroll(e1:MotionEvent?,e2:MotionEvent,dx:Float,dy:Float):Boolean { if(zoom>1.02f&&!scaler.isInProgress){panX-=dx;panY-=dy;fit()};return true }
        override fun onFling(e1:MotionEvent?,e2:MotionEvent,vx:Float,vy:Float):Boolean {
            if(e1==null||multiple||zoom>1.02f)return false
            val dx=e2.x-e1.x;val dy=e2.y-e1.y
            if(abs(dx)>40*resources.displayMetrics.density&&abs(dx)>abs(dy)*1.3f&&abs(vx)>400){swipe?.invoke(if(dx<0)1 else -1);return true};return false
        }
    })
    private val scaler=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(d:ScaleGestureDetector):Boolean { scale(d.scaleFactor,d.focusX,d.focusY);return true }})
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN)multiple=false
        if(event.pointerCount>1)multiple=true
        scaler.onTouchEvent(event);detector.onTouchEvent(event);return true
    }
    override fun performClick():Boolean { super.performClick();return true }
}
