package cn.qingliao.app

import android.app.*
import android.content.DialogInterface
import android.net.Uri
import android.os.SystemClock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** At most two running/four queued uploads. Cancellation never deletes the user's chosen file. */
class AttachmentUpload(private val activity:Activity,private val uri:Uri,private val name:String,private val mime:String,
    private val kind:String,private val peer:String,private val caption:String,private val showProgress:Boolean=true,private val guard:()->Boolean={true},private val done:(JSONObject?,String?)->Unit) {
    companion object {
        private val executor=ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(4))
        private val timers=Executors.newSingleThreadScheduledExecutor()
    }
    private val session=Hub.token
    private val owner=Hub.me.optString("id")
    private val state=AtomicInteger(0)
    private var reason="已取消发送"
    @Volatile private var call:Call?=null
    @Volatile private var input:InputStream?=null
    private var lastUpdate=0L
    private var shownStage=""
    private val displayName=name.take(180).map{if(it.isISOControl()||it=='/'||it=='\\'||it in '\u202a'..'\u202e'||it in '\u2066'..'\u2069')'_' else it}.joinToString("").ifBlank{"文件"}
    val finished get()=state.get()!=0
    val dialog=ProgressDialog(activity).apply{
        setTitle(if(kind=="voice")"发送语音" else if(kind=="image")"发送图片" else "发送文件")
        setMessage(displayName);setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);max=100;isIndeterminate=true
        setCancelable(true);setCanceledOnTouchOutside(false);setOnCancelListener{cancel()}
        setButton(DialogInterface.BUTTON_NEGATIVE,"取消"){_,_->cancel()}
    }
    fun start() {
        if(showProgress)dialog.show()
        try{executor.execute{run()}}catch(_:RejectedExecutionException){state.set(1);dialog.dismiss();done(null,"正在发送的附件较多，请稍后再试")}
    }
    fun cancel(message:String="已取消发送") {
        if(!state.compareAndSet(0,1))return
        reason=message;call?.cancel()
        // Closing a document-provider stream may block, so never close it on the UI thread.
        timers.execute{runCatching{input?.close()}}
    }
    private fun checkActive(){check(state.get()==0){reason};check(session.isNotEmpty()&&owner.isNotEmpty()&&Hub.token==session&&Hub.me.optString("id")==owner){"登录状态已改变"};check(guard()){"原消息或转发页面已不可用"}}
    private fun progress(stage:String,count:Long,total:Long) {
        val now=SystemClock.elapsedRealtime();if(now-lastUpdate<120&&count!=total)return;lastUpdate=now
        Hub.main.post{if(!activity.isDestroyed&&Hub.token==session&&state.get()==0){if(shownStage!=stage){shownStage=stage;dialog.setMessage(stage+"\n"+displayName)};if(dialog.isIndeterminate!=(total<=0))dialog.isIndeterminate=total<=0;if(total>0){val value=(count*100/total).toInt().coerceIn(0,100);if(dialog.progress!=value)dialog.progress=value}}}
    }
    private fun run() {
        var encrypted:File?=null;var uploaded="";var queuedClient="";var result:JSONObject?=null;var error:String?=null
        val deadline=timers.schedule({cancel("发送超时，请检查网络后重试")},180,TimeUnit.SECONDS)
        try {
            checkActive();ChatCrypto.ready();checkActive()
            val key=ChatCrypto.fileKey();val context="qingliao:attachment:1:"+UUID.randomUUID()
            val staging=File.createTempFile("upload-",".bin",File(activity.cacheDir,"crypto-upload").apply{mkdirs()});encrypted=staging
            var size=0L
            val source=activity.contentResolver.openInputStream(uri)?:throw IOException("无法读取所选文件");input=source
            source.use{original->
                val counted=object:FilterInputStream(original){override fun read(buffer:ByteArray,offset:Int,length:Int):Int{
                    checkActive();val count=super.read(buffer,offset,length);if(count>0){size+=count;progress("正在加密附件",size,0)};return count
                }}
                staging.outputStream().use{ChatCrypto.encryptFile(counted,it,key,context)}
            };input=null;checkActive()
            val file=staging
            val body=object:RequestBody(){
                override fun contentType()="application/x-qingliao-encrypted".toMediaType()
                override fun contentLength()=file.length()
                override fun writeTo(sink:okio.BufferedSink){file.inputStream().use{stream->val buffer=ByteArray(65536);var sent=0L
                    while(true){checkActive();val count=stream.read(buffer);if(count<0)break;sink.write(buffer,0,count);sent+=count;progress("正在上传",sent,file.length())}
                }}
            }
            val request=Request.Builder().url(Hub.SERVER+"/api/uploads?recipient="+Uri.encode(peer)+"&name=encrypted.bin").header("Authorization","Bearer $session").post(body).build()
            val active=Hub.http.newCall(request);call=active
            val remote=active.execute().use{response->val value=JSONObject(response.body?.string()?:"{}");if(!response.isSuccessful)throw ApiFailure(response.code,value.optString("error","上传失败"));value}
            call=null;uploaded=remote.getString("id");checkActive()
            val safeName=displayName
            val declared=mime.substringBefore(';').trim().lowercase(Locale.ROOT)
            val safeMime=declared.takeIf{it.length<=100&&it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))}?:"application/octet-stream"
            val metadata=JSONObject().put("id",uploaded).put("name",safeName).put("mime",safeMime).put("size",size).put("crypto",key).put("context",context)
            val client=UUID.randomUUID().toString();queuedClient=client
            val payload=JSONObject().put("recipient",peer).put("type",kind).put("text",caption).put("file_id",uploaded).put("private_file",metadata).put("client_id",client)
            result=Outbox.queue(payload,session,owner)
            check(state.compareAndSet(0,2)){reason}
        }catch(e:Exception){error=if(state.get()==1)reason else e.message?:"附件发送失败"}
        finally {
            deadline.cancel(false);call=null;runCatching{input?.close()};input=null;encrypted?.delete()
            if(state.get()!=2) {
                if(queuedClient.isNotEmpty())LocalData.remove(owner,queuedClient)
                if(uploaded.isNotEmpty())runCatching{Hub.rawApi("DELETE","/api/storage/files/$uploaded",session=session)}
            }
            state.compareAndSet(0,1)
            Hub.main.post{dialog.dismiss();done(if(state.get()==2)result else null,error)}
        }
    }
}
