package cn.qingliao.app

import android.app.Activity
import android.net.Uri
import org.json.JSONObject
import java.util.UUID

/** Sequential forwarding: new message IDs, attachment keys and recipient-bound uploads, never a file-ID copy. */
class MessageForwarder(private val activity:Activity,items:List<JSONObject>,private val recipient:String,
    private val valid:()->Boolean,private val progress:(Int,Int,String)->Unit,private val done:(Int,String?)->Unit) {
    companion object {
        const val MAX_MESSAGES=20
        fun eligible(m:JSONObject)=!m.optBoolean("withdrawn")&&!m.optBoolean("decrypt_failed")&&!m.optBoolean("pending")&&m.optString("type") in setOf("text","html","image","voice","file")&&
            (m.optString("type") in setOf("text","html")||m.optBoolean("attachment_available",true)&&m.optJSONObject("file")?.optBoolean("available",true)==true)
        fun validate(items:List<JSONObject>){
            require(items.size in 1..MAX_MESSAGES&&items.all{eligible(it)}){"请选择1至20条可用消息"}
            var total=0L
            for(item in items){item.optJSONObject("file")?.let{file->val size=file.optLong("size");require(size in 1..25L*1024*1024){"原附件大小不正确"};require(total<=100L*1024*1024-size){"一次转发的附件合计不能超过100MB"};total+=size}}
        }
    }
    private val snapshots=items.map{JSONObject(it.toString())}
    private val session=Hub.token
    private val owner=Hub.me.optString("id")
    @Volatile private var active=true
    private var upload:AttachmentUpload?=null
    private var completed=0
    private var finished=false
    private val tag=Any()
    private val commitLock=Any()
    val running get()=active&&!finished
    private fun allowed()=active&&!activity.isDestroyed&&Hub.token==session&&Hub.me.optString("id")==owner&&valid()
    fun start(){validate(snapshots);check(session.isNotEmpty()&&owner.isNotEmpty());next()}
    fun cancel(){synchronized(commitLock){active=false};upload?.cancel("已停止转发");(Hub.http.dispatcher.runningCalls()+Hub.http.dispatcher.queuedCalls()).filter{it.request().tag()===tag}.forEach{it.cancel()};finish("已停止，已加入待发的消息仍会发送；停止前正在处理的一条可能已经送达")}
    private fun finish(error:String?){if(finished)return;finished=true;active=false;done(completed,error)}
    private fun next(){
        if(!allowed()){finish("转发已停止");return}
        if(completed==snapshots.size){finish(null);return}
        val item=snapshots[completed];progress(completed,snapshots.size,if(item.has("file"))"正在准备附件"else"正在保存待发消息")
        if(item.optString("type") in setOf("text","html")){
            val p=JSONObject().put("recipient",recipient).put("type",item.getString("type")).put("text",item.optString("text")).put("client_id",UUID.randomUUID().toString())
            // Queue persistence and encryption are shared with ordinary user-initiated sending.
            LocalData.worker.execute{try{
                synchronized(commitLock){check(allowed());Outbox.queue(p,session,owner);completed++}
                Hub.main.post{if(!finished)next()}
            }catch(e:Exception){Hub.main.post{finish(e.message?:"转发未能保存")}}}
        }else{
            val file=item.getJSONObject("file")
            Hub.io.execute{try{
                check(allowed());val local=Hub.cachedFile(file,tag){!allowed()};check(allowed())
                Hub.main.post{
                    if(!allowed()){finish("转发已停止");return@post}
                    upload=AttachmentUpload(activity,Uri.fromFile(local),file.optString("name","文件"),file.optString("mime","application/octet-stream"),item.getString("type"),recipient,item.optString("text"),false,{allowed()}){message,error->
                        upload=null;if(!finished){if(message==null)finish(error?:"附件转发失败")else{completed++;next()}}
                    }.also{it.start()}
                }
            }catch(e:Exception){Hub.main.post{finish(e.message?:"无法读取原附件")}}}
        }
    }
}
