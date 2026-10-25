package cn.qingliao.app

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.view.*
import android.widget.*
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import org.json.JSONArray

/** Authenticated photos stay in bounded memory; no public URLs or shared gallery copies. */
object MomentsImages {
    private data class Target(val view:WeakReference<ImageView>,val done:((Boolean)->Unit)?)
    private val cache=object : LruCache<String,Bitmap>(12*1024*1024) { override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount }
    private val waiting=mutableMapOf<String,MutableList<Target>>()
    private var generation=0
    fun clear() { generation++;cache.evictAll();waiting.clear() }
    fun lazyLoad(id:String,view:ImageView,limit:Int=480,done:((Boolean)->Unit)?=null,started:(()->Unit)?=null,cover:Boolean=false) {
        var requested=false
        fun visible(){
            val onscreen=view.isAttachedToWindow&&view.getGlobalVisibleRect(Rect())
            if(!requested&&onscreen){requested=true;started?.invoke();load(id,view,done,limit,cover)}
            else if(requested&&!onscreen){requested=false;view.tag=null;Motion.rest(view);view.setImageDrawable(null)}
        }
        val scroll=ViewTreeObserver.OnScrollChangedListener { visible() }
        val layout=ViewTreeObserver.OnGlobalLayoutListener { visible() }
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View){view.viewTreeObserver.addOnScrollChangedListener(scroll);view.viewTreeObserver.addOnGlobalLayoutListener(layout);visible()}
            override fun onViewDetachedFromWindow(v:View){requested=false;view.tag=null;Motion.rest(view);view.setImageDrawable(null);if(view.viewTreeObserver.isAlive){view.viewTreeObserver.removeOnScrollChangedListener(scroll);view.viewTreeObserver.removeOnGlobalLayoutListener(layout)}}
        })
    }
    fun load(id:String,view:ImageView,done:((Boolean)->Unit)?=null,limit:Int=1600,cover:Boolean=false) {
        val key=Hub.me.optString("id")+":"+(if(cover)"cover:" else "photo:")+id+":"+limit;view.tag=key
        cache.get(key)?.let { Motion.rest(view);view.setImageBitmap(it);done?.invoke(true);return }
        waiting[key]?.let { it.add(Target(WeakReference(view),done));return }
        waiting[key]=mutableListOf(Target(WeakReference(view),done));val expected=generation;val token=Hub.token
        Hub.io.execute {
            val path=if(cover)"/api/post-media/$id/cover" else "/api/post-photos/$id"
            val bitmap=try { Hub.http.newCall(Request.Builder().url(Hub.SERVER+path).header("Authorization","Bearer $token").build()).execute().use { r ->
                if(r.isSuccessful)r.body?.bytes()?.let { data ->
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(data,0,data.size,bounds)
                    var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>limit*2)sample*=2
                    val raw=BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply { inSampleSize=sample })
                    if(raw==null)null else {
                        val ratio=minOf(1f,limit.toFloat()/maxOf(raw.width,raw.height))
                        if(ratio==1f)raw else Bitmap.createScaledBitmap(raw,maxOf(1,(raw.width*ratio).toInt()),maxOf(1,(raw.height*ratio).toInt()),true).also { raw.recycle() }
                    }
                } else null
            } } catch(_:Exception) { null }
            Hub.main.post {
                if(expected!=generation){bitmap?.recycle();return@post}
                val targets=waiting.remove(key)?:return@post
                if(bitmap!=null)cache.put(key,bitmap)
                targets.forEach { target -> target.view.get()?.let { v -> if(v.tag==key){if(bitmap!=null){v.setImageBitmap(bitmap);if(limit<1600)Motion.image(v)};target.done?.invoke(bitmap!=null)} } }
            }
        }
    }
    fun decode(context:Context,uri:Uri,limit:Int):Bitmap {
        if(Build.VERSION.SDK_INT>=28)return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)) { decoder,info,_ ->
            val scale=minOf(1f,limit.toFloat()/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,bounds) }
        require(bounds.outWidth>0 && bounds.outHeight>0) { "图片无法读取" }
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>limit*2)sample*=2
        val raw=context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample }) }?:error("图片无法读取")
        val orientation=context.contentResolver.openFileDescriptor(uri,"r").use { fd -> fd?.let { ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) }?:1 }
        val matrix=Matrix().apply { when(orientation) { 2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f) } }
        val rotated=Bitmap.createBitmap(raw,0,0,raw.width,raw.height,matrix,true);if(rotated!==raw)raw.recycle()
        val scale=minOf(1f,limit.toFloat()/maxOf(rotated.width,rotated.height))
        if(scale==1f)return rotated
        return Bitmap.createScaledBitmap(rotated,maxOf(1,(rotated.width*scale).toInt()),maxOf(1,(rotated.height*scale).toInt()),true).also { rotated.recycle() }
    }
    fun prepare(context:Context,uri:Uri):ByteArray {
        val bitmap=decode(context,uri,1600)
        val flat=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE);drawBitmap(bitmap,0f,0f,null) };bitmap.recycle()
        try {
            for(quality in listOf(86,72,58)) {
                val output=ByteArrayOutputStream();flat.compress(Bitmap.CompressFormat.JPEG,quality,output)
                if(output.size()<=2*1024*1024)return output.toByteArray()
            }
            error("图片过大，请换一张图片")
        } finally { flat.recycle() }
    }
}
