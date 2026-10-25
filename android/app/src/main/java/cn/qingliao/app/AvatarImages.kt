package cn.qingliao.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import okhttp3.Request
import java.lang.ref.WeakReference

object AvatarImages {
    private val cache=object : LruCache<String,Bitmap>(8*1024) { override fun sizeOf(key: String,value: Bitmap)=value.byteCount/1024 }
    private val waiting=mutableMapOf<String,MutableList<WeakReference<ImageView>>>()
    private var generation=0
    fun clear() { generation++; cache.evictAll(); waiting.clear() }
    fun seed(id: String, bitmap: Bitmap) { if(id.isNotEmpty())cache.put(id,bitmap) }
    fun load(id: String, view: ImageView,post: String="") {
        if(id.isEmpty())return
        view.tag=id
        cache.get(id)?.let { view.setImageBitmap(it); view.visibility=View.VISIBLE; return }
        val reference=WeakReference(view)
        val requestKey=id+":"+post
        waiting[requestKey]?.let { it.add(reference); return }
        waiting[requestKey]=mutableListOf(reference)
        val expected=generation; val token=Hub.token
        Hub.io.execute {
            val bitmap=try {
                Hub.http.newCall(Request.Builder().url(Hub.SERVER+"/api/avatars/"+id+(if(post.matches(Regex("[a-f0-9]{32}")))"?post=$post" else "")).header("Authorization","Bearer $token").build()).execute().use { response ->
                    if(response.isSuccessful)response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) } else null
                }
            } catch(_:Exception) { null }
            Hub.main.post {
                if(expected!=generation)return@post
                val targets=waiting.remove(requestKey)?:return@post
                if(bitmap!=null) {
                    cache.put(id,bitmap)
                    targets.forEach { it.get()?.let { target -> if(target.tag==id) { target.setImageBitmap(bitmap); target.visibility=View.VISIBLE;Motion.image(target) } } }
                }
            }
        }
    }
}
