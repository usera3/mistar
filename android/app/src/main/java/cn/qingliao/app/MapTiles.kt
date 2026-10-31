package cn.qingliao.app

import android.graphics.*
import okhttp3.*
import org.json.JSONObject
import java.io.*
import java.util.concurrent.*
import kotlin.math.*

/** Only the visible viewport. Independent HTTPS client: no messaging credentials or position query. */
object MapTiles {
    val executor=ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,ArrayBlockingQueue(16))
    @Volatile var client:OkHttpClient=OkHttpClient.Builder().cache(Cache(File(Hub.app.cacheDir,"location-map-tiles"),12L*1024*1024)).followRedirects(false).followSslRedirects(false).connectTimeout(8,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(12,TimeUnit.SECONDS).build()
    fun url(z:Int,x:Int,y:Int):String {require(z in 0..18&&x in 0 until (1 shl z)&&y in 0 until (1 shl z));return "https://api.claudcode.xyz/xingyu/map-tiles/$z/$x/$y.png"}
    fun parse(uri:android.net.Uri):Triple<Int,Int,Int>? {
        if(uri.scheme!="https"||uri.host!="api.claudcode.xyz"||uri.port !in listOf(-1,443)||uri.query!=null||uri.fragment!=null)return null
        val m=Regex("/xingyu/map-tiles/([0-9]{1,2})/([0-9]{1,6})/([0-9]{1,6})\\.png").matchEntire(uri.encodedPath.orEmpty())?:return null
        return runCatching{val z=m.groupValues[1].toInt();val x=m.groupValues[2].toInt();val y=m.groupValues[3].toInt();url(z,x,y);Triple(z,x,y)}.getOrNull()
    }
    fun fetch(z:Int,x:Int,y:Int,tag:Any,allowed:()->Boolean):ByteArray {
        check(allowed());val request=Request.Builder().url(url(z,x,y)).header("User-Agent","Mistar/${BuildConfig.VERSION_NAME} (+https://github.com/usera3/mistar)").tag(tag).build()
        client.newCall(request).execute().use{response->
            check(allowed()&&response.isSuccessful&&response.header("Content-Type").orEmpty().startsWith("image/png")){"地图暂不可用"}
            val body=response.body?:throw IOException("地图暂不可用");require(body.contentLength()<=1024*1024)
            val output=ByteArrayOutputStream();body.byteStream().use{input->val buffer=ByteArray(8192);while(true){check(allowed());val n=input.read(buffer);if(n<0)break;require(output.size()+n<=1024*1024);output.write(buffer,0,n)}}
            val bytes=output.toByteArray();require(bytes.size>=8&&bytes.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)))
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds);require(bounds.outWidth==256&&bounds.outHeight==256)
            return bytes
        }
    }
    fun cancel(tag:Any){(client.dispatcher.runningCalls()+client.dispatcher.queuedCalls()).filter{it.request().tag()===tag}.forEach{it.cancel()}}
    fun clear(){client.dispatcher.cancelAll();runCatching{client.cache?.evictAll()}}
    fun zoom(p:JSONObject)=if(p.optBoolean("approximate")||p.getDouble("accuracy")>1000)12 else 15
    fun thumbnail(p:JSONObject,tag:Any,allowed:()->Boolean,accent:Int):Bitmap {
        LocationPayload.validate(p);val z=zoom(p);val n=1 shl z;val size=256.0*n
        val latitude=p.getDouble("lat").coerceIn(-85.05112878,85.05112878);val sin=sin(Math.toRadians(latitude))
        val cx=(p.getDouble("lon")+180)/360*size;val cy=(.5-ln((1+sin)/(1-sin))/(4*PI))*size
        val width=512;val height=256;val left=cx-width/2;val top=cy-height/2
        val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val canvas=Canvas(image);canvas.drawColor(Color.rgb(238,240,244))
        try{
            for(ty in floor(top/256).toInt()..floor((top+height-1)/256).toInt())for(tx in floor(left/256).toInt()..floor((left+width-1)/256).toInt()){
                check(allowed());if(ty !in 0 until n)continue
                val data=fetch(z,((tx%n)+n)%n,ty,tag,allowed)
                val tile=BitmapFactory.decodeByteArray(data,0,data.size)?:throw IOException("地图图片无法读取")
                try{require(tile.width==256&&tile.height==256);canvas.drawBitmap(tile,(tx*256-left).toFloat(),(ty*256-top).toFloat(),null)}finally{tile.recycle()}
            }
            val pen=Paint(Paint.ANTI_ALIAS_FLAG);val radius=(p.getDouble("accuracy")/(156543.03392*cos(Math.toRadians(latitude))/(1 shl z))).toFloat().coerceIn(8f,100f)
            pen.color=accent;pen.alpha=45;canvas.drawCircle(width/2f,height/2f,radius,pen);pen.alpha=255;pen.color=Color.WHITE;canvas.drawCircle(width/2f,height/2f,13f,pen);pen.color=accent;canvas.drawCircle(width/2f,height/2f,9f,pen)
            return image
        }catch(e:Exception){image.recycle();throw e}
    }
}
