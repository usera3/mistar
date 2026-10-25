package cn.qingliao.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Bounded private staging; server independently validates container, size and audience. */
object MomentMediaFiles {
    data class Prepared(val file:File,val mime:String,val duration:Long,val cover:ByteArray?=null)
    private fun cover(reader:MediaMetadataRetriever,duration:Long,active:()->Boolean):ByteArray {
        val width=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:0
        val height=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:0
        require(width in 1..8192&&height in 1..8192){"视频尺寸不支持"}
        // Android 8.0 has no scaled frame API; bound its full-frame allocation first.
        require(Build.VERSION.SDK_INT>=27||width.toLong()*height<=4194304){"这台设备请先将视频缩小到 400 万像素以内"}
        val limit=minOf(640,maxOf(width,height))
        var fallback:ByteArray?=null
        for(time in listOf(minOf(1000L,duration/3)*1000,duration*500,0L).distinct()) {
            check(active()){"封面生成已取消"}
            val raw=if(Build.VERSION.SDK_INT>=27)reader.getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST,limit,limit) else reader.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST)
            if(raw==null)continue
            val ratio=minOf(1f,640f/maxOf(raw.width,raw.height))
            val bitmap=if(ratio==1f)raw else Bitmap.createScaledBitmap(raw,maxOf(1,(raw.width*ratio).toInt()),maxOf(1,(raw.height*ratio).toInt()),true).also{raw.recycle()}
            try {
                val output=ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.JPEG,82,output)){"无法生成视频封面"}
                val jpeg=output.toByteArray();if(fallback==null)fallback=jpeg
                // Try a later frame if the opening is almost black. Genuine dark videos retain their first frame.
                var visible=0
                for(y in 0 until 12)for(x in 0 until 12){val color=bitmap.getPixel(x*(bitmap.width-1)/11,y*(bitmap.height-1)/11);if(maxOf(Color.red(color),Color.green(color),Color.blue(color))>35)visible++}
                if(visible>=8)return jpeg
            } finally { bitmap.recycle() }
        }
        return fallback?:error("无法提取视频画面，请换一个可正常播放的 MP4 视频")
    }
    fun maximum(kind:String)=if(kind=="video")25L*1024*1024 else 5L*1024*1024
    fun seconds(kind:String)=if(kind=="video")180 else 60
    fun prepare(context:Context,uri:Uri,kind:String,active:()->Boolean):Prepared {
        require(kind in listOf("video","voice"))
        val temp=File.createTempFile("moment-",".media",context.cacheDir)
        val deadline=SystemClock.elapsedRealtime()+120000
        try {
            require(uri.scheme in listOf("content","file")){"请选择本机媒体文件"}
            context.contentResolver.openInputStream(uri)!!.use{input->temp.outputStream().use{output->
                val buffer=ByteArray(65536);var total=0L
                while(true){check(active()&&SystemClock.elapsedRealtime()<deadline){"读取已取消或超时"};val count=input.read(buffer);if(count<0)break;total+=count;require(total<=maximum(kind)){"视频不能超过25MB，语音不能超过5MB"};output.write(buffer,0,count)}
            }}
            val header=ByteArray(12);temp.inputStream().use{require(it.read(header)==12){"媒体文件不完整"}}
            val mp4=String(header,4,4,Charsets.US_ASCII)=="ftyp"
            val wav=String(header,0,4,Charsets.US_ASCII)=="RIFF"&&String(header,8,4,Charsets.US_ASCII)=="WAVE"
            require(if(kind=="video")mp4 else mp4||wav){"视频请选择MP4，语音请选择M4A或PCM WAV"}
            val reader=MediaMetadataRetriever()
            val duration:Long
            val poster:ByteArray?
            try {
                reader.setDataSource(temp.absolutePath)
                duration=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L
                val video=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)=="yes"
                require(if(kind=="video")video else !video){"文件类型与选择不匹配"}
                require(duration in 1..seconds(kind)*1000L){"视频最长3分钟，语音最长60秒"}
                poster=if(kind=="video")cover(reader,duration,active) else null
            } finally { reader.release() }
            return Prepared(temp,if(kind=="video")"video/mp4" else if(wav)"audio/wav" else "audio/mp4",duration,poster)
        }catch(e:Throwable){temp.delete();throw e}
    }
    private fun uploadCover(jpeg:ByteArray,id:String,session:String,owner:String) {
        check(Hub.token==session&&Hub.me.optString("id")==owner){"登录状态已改变"}
        val call=Hub.http.newCall(Request.Builder().url(Hub.SERVER+"/api/post-media/$id/cover").header("Authorization","Bearer $session").post(jpeg.toRequestBody("image/jpeg".toMediaType())).build())
        call.timeout().timeout(30,TimeUnit.SECONDS)
        call.execute().use{r->check(r.isSuccessful){org.json.JSONObject(r.body?.string()?:"{}").optString("error","封面上传失败，请重试")}}
        check(Hub.token==session&&Hub.me.optString("id")==owner){"登录状态已改变"}
    }
    fun ensureCover(context:Context,uri:Uri,id:String,session:String,owner:String) {
        fun active()=session.isNotEmpty()&&Hub.token==session&&Hub.me.optString("id")==owner
        check(active()){"登录状态已改变"}
        val call=Hub.http.newCall(Request.Builder().url(Hub.SERVER+"/api/post-media/$id/cover").header("Authorization","Bearer $session").head().build())
        call.timeout().timeout(20,TimeUnit.SECONDS)
        val present=call.execute().use{r->check(r.isSuccessful||r.code==404){"无法检查视频封面，请重试"};r.isSuccessful}
        check(active()){"登录状态已改变"};if(present)return
        val media=prepare(context,uri,"video",::active)
        try{uploadCover(media.cover!!,id,session,owner)}finally{media.file.delete()}
    }
    fun upload(context:Context,uri:Uri,kind:String,session:String,owner:String,uploaded:(org.json.JSONObject)->Unit={},progress:(Int)->Unit):org.json.JSONObject {
        fun active()=session.isNotEmpty()&&Hub.token==session&&Hub.me.optString("id")==owner
        val media=prepare(context,uri,kind,::active)
        try {
            val body=object:RequestBody(){
                override fun contentType()=media.mime.toMediaType()
                override fun contentLength()=media.file.length()
                override fun writeTo(sink:BufferedSink){media.file.inputStream().use{source->
                    val buffer=ByteArray(65536);var sent=0L;var update=0L
                    while(true){check(active()){"登录状态已改变"};val count=source.read(buffer);if(count<0)break;sink.write(buffer,0,count);sent+=count
                        val now=SystemClock.elapsedRealtime();if(now-update>150||sent==contentLength()){update=now;progress((sent*100/contentLength().coerceAtLeast(1)).toInt())}
                    }
                }}
            }
            val call=Hub.http.newCall(Request.Builder().url(Hub.SERVER+"/api/post-media?kind=$kind").header("Authorization","Bearer $session").post(body).build())
            call.timeout().timeout(120,TimeUnit.SECONDS)
            val info=call.execute().use{r->val p=org.json.JSONObject(r.body?.string()?:"{}");check(r.isSuccessful){p.optString("error","媒体上传失败")};check(active()){"登录状态已改变"};p}
            // Save the video ID before the separate poster upload, so a failure never resends the video.
            uploaded(info)
            media.cover?.let{uploadCover(it,info.getString("id"),session,owner)}
            return info
        }finally{media.file.delete()}
    }
}
