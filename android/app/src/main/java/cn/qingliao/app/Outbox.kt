package cn.qingliao.app

import android.net.*
import org.json.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class ApiFailure(val status:Int,message:String):Exception(message)

/** Persist before attempting delivery; client_id stays unchanged across crashes and uncertain outcomes. */
object Outbox {
    private val executor=Executors.newSingleThreadExecutor()
    private val running=AtomicBoolean(false)
    private val locks=ConcurrentHashMap<String,AtomicBoolean>()
    private var retryTask:Runnable?=null
    private fun online():Boolean {
        val manager=Hub.app.getSystemService(ConnectivityManager::class.java)
        return manager.activeNetwork?.let{manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}==true
    }
    fun pending(owner:String,p:JSONObject):JSONObject = JSONObject().put("id","local:"+p.getString("client_id")).put("client_id",p.getString("client_id"))
        .put("sender",owner).put("recipient",p.getString("recipient")).put("type",p.getString("type")).put("text",p.optString("text"))
        .put("created",p.optLong("local_created",System.currentTimeMillis())).put("pending",true).put("send_state",p.optString("local_state","queued"))
        .put("send_error",p.optString("local_error")).apply{if(ChatAddress.group(p.optString("recipient")))put("group_id",p.getString("recipient"));p.optJSONObject("private_file")?.let{put("file",it);put("file_id",it.optString("id"))};p.optJSONObject("quote")?.let{put("quote",it)}}
    private fun enqueue(p:JSONObject,session:String,owner:String,clearDraft:Boolean=false):JSONObject {
        require(Hub.token==session&&Hub.me.optString("id")==owner){"登录状态已改变"}
        val inner=JSONObject().put("type",p.getString("type")).put("text",p.optString("text"));p.optJSONObject("private_file")?.let{inner.put("file",it)}
        p.optJSONObject("quote")?.let{inner.put("quote",it)}
        GroupDirectory.seed(p)
        PayloadRules.validate(inner,p.optString("file_id"),if(ChatAddress.group(p.getString("recipient")))null else setOf(owner,p.getString("recipient")))
        return if(clearDraft)ConversationState.queueText(owner,session,JSONObject(p.toString()))else LocalData.queue(owner,JSONObject(p.toString()))
    }
    fun queue(p:JSONObject,session:String=Hub.workSession(),owner:String=Hub.workOwner()):JSONObject {val stored=enqueue(p,session,owner);kick();return pending(owner,stored)}
    fun queueAsync(p:JSONObject,done:(JSONObject)->Unit,error:(String)->Unit,clearDraft:Boolean=false) {
        val session=Hub.token;val owner=Hub.me.optString("id");val payload=JSONObject(p.toString())
        LocalData.worker.execute{try{val stored=enqueue(payload,session,owner,clearDraft);kick();val result=pending(owner,stored);Hub.main.post{if(Hub.token==session)done(result)}}catch(e:Exception){Hub.main.post{if(Hub.token==session)error(e.message?:"待发消息无法保存")}}}
    }
    fun send(p:JSONObject,session:String=Hub.workSession(),owner:String=Hub.workOwner()):JSONObject {
        val stored=enqueue(p,session,owner)
        val delivered=if(online())attempt(owner,stored,session)else null
        kick();return delivered?:pending(owner,LocalData.outgoing(owner,p.getString("client_id"))?:stored)
    }
    fun acknowledge(owner:String,message:JSONObject) {
        if(message.optString("sender")==owner&&message.optString("client_id").isNotEmpty())LocalData.remove(owner,message.getString("client_id"))
    }
    private fun notify(owner:String,session:String,peer:String,message:JSONObject?=null) {
        Hub.main.post{if(Hub.token==session&&Hub.me.optString("id")==owner)Hub.emit(JSONObject().put("event","outbox.changed").put("peer",peer).apply{if(message!=null)put("message",message)})}
    }
    private fun attempt(owner:String,p:JSONObject,session:String):JSONObject? {
        val client=p.getString("client_id");val peer=p.getString("recipient");val lock=AtomicBoolean(true)
        if(locks.putIfAbsent("$owner:$client",lock)!=null)return null
        var attempts=p.optInt("local_attempts")
        try {
            if(Hub.token!=session||Hub.me.optString("id")!=owner||!online())return null
            if(p.optBoolean("local_unreadable")){LocalData.state(owner,client,"blocked",attempts,0,"本机记录无法读取，请删除后重新输入");notify(owner,session,peer);return null}
            if(System.currentTimeMillis()-p.optLong("local_created")>7*86400000L){LocalData.state(owner,client,"blocked",attempts,0,"消息等待时间较长，请确认后重试");notify(owner,session,peer);return null}
            if(attempts>=5){LocalData.state(owner,client,"blocked",attempts,0,"暂时未能发送，请确认后重试");notify(owner,session,peer);return null}
            if(LocalData.outgoing(owner,client)==null)return null
            val uncertain=attempts>0;attempts++
            LocalData.state(owner,client,"sending",attempts,0)
            if(uncertain) {
                val statusPath=if(ChatAddress.group(peer))"/api/groups/$peer/message-status/"else"/api/message-status/"
                val status=Hub.rawApi("GET",statusPath+android.net.Uri.encode(client),session=session,requestTag="outbox:$owner:$client")
                if(status.optBoolean("acknowledged")) {
                    val value=status.optJSONObject("message")?.let{ChatCrypto.incoming(it)}
                    LocalData.remove(owner,client);if(value!=null)runCatching{LocalData.put(owner,value)}
                    notify(owner,session,peer,value);return value?:JSONObject().put("removed",true).put("client_id",client)
                }
            }
            check(Hub.token==session&&Hub.me.optString("id")==owner)
            val encrypted=ChatCrypto.outgoing(p)
            check(Hub.token==session&&LocalData.outgoing(owner,client)!=null)
            val endpoint=if(ChatAddress.group(peer))"/api/groups/$peer/messages"else"/api/messages"
            val raw=Hub.rawApi("POST",endpoint,encrypted,session,requestTag="outbox:$owner:$client")
            val result=if(raw.optBoolean("removed"))raw else ChatCrypto.incoming(raw)
            LocalData.remove(owner,client)
            if(Hub.token==session&&Hub.me.optString("id")==owner){runCatching{LocalData.put(owner,result)};notify(owner,session,peer,result)}
            return result
        }catch(e:Exception) {
            val permanent=(e is ApiFailure&&e.status in 400..499&&e.status !in listOf(408,429))||attempts>=5
            val delay=(minOf(60000L,2000L*(1L shl attempts.coerceIn(0,5)))*(.8+Math.random()*.4)).toLong()
            LocalData.state(owner,client,if(permanent)"blocked" else "failed",attempts,System.currentTimeMillis()+delay,e.message?:"暂时未能发送")
            notify(owner,session,peer)
            return null
        }finally{if(Hub.token!=session)LocalData.state(owner,client,"queued",attempts,0);locks.remove("$owner:$client",lock)}
    }
    fun kick() {
        if(Hub.token.isEmpty()||!online()||!running.compareAndSet(false,true))return
        val owner=Hub.me.optString("id");val session=Hub.token
        executor.execute {
            try {
                repeat(20){
                    if(Hub.token!=session||!online())return@execute
                    val item=LocalData.next(owner)?:return@execute
                    attempt(owner,item,session)
                    Thread.sleep(650)
                }
            }catch(_:Exception){}finally{
                running.set(false)
                val retry=runCatching{Hub.token==session&&LocalData.hasRetry(owner)}.getOrDefault(false)
                Hub.main.post{retryTask?.let{Hub.main.removeCallbacks(it)};retryTask=null
                    if(retry&&Hub.token==session&&Hub.service!=null){retryTask=Runnable{kick()}.also{Hub.main.postDelayed(it,3000)}}
                }
            }
        }
    }
    fun stop(){retryTask?.let{Hub.main.removeCallbacks(it)};retryTask=null}
    fun retry(client:String){val owner=Hub.workOwner();require(Hub.token==Hub.workSession());val pending=LocalData.outgoing(owner,client);require(pending?.optBoolean("local_unreadable")!=true){"本机记录无法读取，请删除后重新输入"};val peer=pending?.optString("recipient").orEmpty();if(ChatAddress.group(peer)){val group=GroupDirectory.get(peer)?:throw Exception("请先打开群资料获取最新成员");require(!group.optBoolean("left"));LocalData.groupEpoch(owner,client,group.getInt("epoch"))};LocalData.retry(owner,client);kick()}
    fun cancel(client:String){val owner=Hub.workOwner();val session=Hub.workSession();require(Hub.token==session);LocalData.remove(owner,client);(Hub.http.dispatcher.runningCalls()+Hub.http.dispatcher.queuedCalls()).filter{it.request().tag()=="outbox:$owner:$client"}.forEach{it.cancel()};notify(owner,session,"")}
    fun list(owner:String,peer:String):JSONArray {val all=LocalData.pending(owner,peer);return JSONArray((0 until all.length()).map{pending(owner,all.getJSONObject(it))})}
}
