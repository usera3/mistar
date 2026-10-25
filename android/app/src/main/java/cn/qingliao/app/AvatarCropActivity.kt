package cn.qingliao.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class AvatarCropActivity : Activity() {
    override fun finish(){super.finish();Motion.activityClose(this)}
    private lateinit var crop: AvatarCropView
    private var photo: Bitmap?=null
    private var finished=false
    private var saving=false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val palette=ThemeStore.current(this)
        window.statusBarColor=palette.callBottom; window.navigationBarColor=palette.callBottom
        window.decorView.systemUiVisibility=0
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(palette.callBottom) }
        fun dp(x: Int)=(x*resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { v,insets -> v.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom); insets }
        fun label(value: String,color: Int=Color.WHITE)=TextView(this).apply { text=value; textSize=15f; setTextColor(color); gravity=Gravity.CENTER; setPadding(dp(16),dp(16),dp(16),dp(16)) }
        val top=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        top.addView(label("取消").apply { setOnClickListener { if(!saving)finish() } })
        top.addView(label("调整头像").apply { setTypeface(typeface,Typeface.BOLD) },LinearLayout.LayoutParams(0,-2,1f))
        val use=label("使用",palette.onPrimary).apply { contentDescription="使用头像"; isEnabled=false; background=android.graphics.drawable.GradientDrawable().apply { setColor(palette.primary); cornerRadius=dp(12).toFloat() } }
        top.addView(use); root.addView(top)
        crop=AvatarCropView(this); root.addView(crop,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(label("拖动图片或双指缩放，调整圆框内的画面",palette.callMuted))
        val tools=LinearLayout(this).apply { gravity=Gravity.CENTER; setPadding(0,0,0,dp(16)) }
        listOf("旋转" to { crop.rotate() },"缩小" to { crop.zoom(.8f) },"放大" to { crop.zoom(1.25f) }).forEach { (name,action) ->
            tools.addView(label(name).apply { setOnClickListener { if(!saving)action() } },LinearLayout.LayoutParams(0,-2,1f))
        }
        root.addView(tools); setContentView(root); root.requestApplyInsets()
        val uri=intent.data ?: run { finish(); return }
        Hub.io.execute {
            try {
                val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) }
                if(options.outWidth<=0 || options.outHeight<=0)throw Exception("无法读取图片")
                var sample=1; while(max(options.outWidth,options.outHeight)/sample>1600)sample*=2
                var image=contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample }) } ?: throw Exception("无法读取图片")
                val orientation=try { contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) } } catch(_:Exception) { ExifInterface.ORIENTATION_NORMAL }
                val matrix=Matrix()
                when(orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f,1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f,-1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f,1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f,1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
                }
                if(!matrix.isIdentity) { val transformed=Bitmap.createBitmap(image,0,0,image.width,image.height,matrix,true); if(transformed!==image)image.recycle(); image=transformed }
                runOnUiThread { if(finished || isFinishing)image.recycle() else { photo=image; crop.setPhoto(image); use.isEnabled=true } }
            } catch(_:Exception) { runOnUiThread { if(!finished) { Toast.makeText(this,"无法读取图片，请换一张",Toast.LENGTH_LONG).show(); finish() } } }
        }
        use.setOnClickListener {
            if(saving || photo==null)return@setOnClickListener
            val output=crop.result() ?: return@setOnClickListener
            saving=true; use.isEnabled=false; use.text="保存中…"
            Hub.io.execute {
                val file=File(File(cacheDir,"shared").apply { mkdirs() },"avatar-crop-${UUID.randomUUID()}.jpg")
                try {
                    file.outputStream().use { output.compress(Bitmap.CompressFormat.JPEG,90,it) }; output.recycle()
                    runOnUiThread {
                        if(finished)file.delete() else {
                            val result=FileProvider.getUriForFile(this,"cn.qingliao.app.files",file)
                            setResult(RESULT_OK,Intent().setData(result).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); finish()
                        }
                    }
                } catch(_:Exception) { output.recycle(); file.delete(); runOnUiThread { saving=false; use.isEnabled=true; use.text="使用"; Toast.makeText(this,"保存失败，请重试",Toast.LENGTH_SHORT).show() } }
            }
        }
    }
    override fun onDestroy() { finished=true; crop.release(); photo=null; super.onDestroy() }
}

class AvatarCropView(context: Context) : View(context) {
    private var image: Bitmap?=null
    private val area=RectF()
    private var scale=1f
    private var minimum=1f
    private var tx=0f; private var ty=0f
    private var lastX=0f; private var lastY=0f
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix=Matrix()
    private val detector=ScaleGestureDetector(context,object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { applyZoom(d.scaleFactor,d.focusX,d.focusY); return true }
    })
    init { contentDescription="头像裁剪区域" }
    fun setPhoto(value: Bitmap) { image=value; reset(); invalidate() }
    fun release() { image?.recycle(); image=null }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { val side=(min(w,h)-48*resources.displayMetrics.density).coerceAtLeast(1f); area.set((w-side)/2f,(h-side)/2f,(w+side)/2f,(h+side)/2f); reset() }
    private fun reset() { image?.let { minimum=max(area.width()/it.width,area.height()/it.height); scale=minimum; tx=area.centerX()-it.width*scale/2; ty=area.centerY()-it.height*scale/2; clamp() } }
    private fun clamp() { image?.let { tx=tx.coerceAtLeast(area.right-it.width*scale).coerceAtMost(area.left); ty=ty.coerceAtLeast(area.bottom-it.height*scale).coerceAtMost(area.top) } }
    private fun applyZoom(factor: Float,x: Float,y: Float) { if(image==null)return; val next=(scale*factor).coerceIn(minimum,minimum*6); val ratio=next/scale; tx=x-(x-tx)*ratio; ty=y-(y-ty)*ratio; scale=next; clamp(); invalidate() }
    fun zoom(factor: Float) { applyZoom(factor,area.centerX(),area.centerY()) }
    fun rotate() { image?.let { old -> val next=Bitmap.createBitmap(old,0,0,old.width,old.height,Matrix().apply { postRotate(90f) },true); image=next; if(next!==old)old.recycle(); reset(); invalidate() } }
    private fun imageMatrix(): Matrix { matrix.setScale(scale,scale); matrix.postTranslate(tx,ty); return matrix }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        image?.let { canvas.drawBitmap(it,imageMatrix(),paint) }
        val shade=Path().apply { fillType=Path.FillType.EVEN_ODD; addRect(0f,0f,width.toFloat(),height.toFloat(),Path.Direction.CW); addCircle(area.centerX(),area.centerY(),area.width()/2,Path.Direction.CW) }
        canvas.drawPath(shade,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.argb(170,0,0,0) })
        canvas.drawCircle(area.centerX(),area.centerY(),area.width()/2,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; style=Paint.Style.STROKE; strokeWidth=2*resources.displayMetrics.density })
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX=event.x; lastY=event.y; return true }
            MotionEvent.ACTION_MOVE -> { if(!detector.isInProgress && event.pointerCount==1) { tx+=event.x-lastX; ty+=event.y-lastY; clamp(); invalidate() }; lastX=event.x; lastY=event.y }
            MotionEvent.ACTION_POINTER_UP -> { val index=if(event.actionIndex==0)1 else 0; lastX=event.getX(index); lastY=event.getY(index) }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun result(): Bitmap? {
        val source=image ?: return null
        if(area.width()<=0)return null
        val output=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
        Canvas(output).apply { drawColor(Color.WHITE); scale(512/area.width(),512/area.height()); translate(-area.left,-area.top); drawBitmap(source,imageMatrix(),paint) }
        return output
    }
}
