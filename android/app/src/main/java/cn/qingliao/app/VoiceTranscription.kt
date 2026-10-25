package cn.qingliao.app

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** One selected voice only. Reports inline state; never opens a window or records audio. */
internal class VoiceTranscription(val messageId:String,val fileId:String,
    private val file:JSONObject,private val valid:()->Boolean,private val save:(String)->Unit,
    private val complete:(String)->Unit,private val progress:(String)->Unit,
    private val failed:(String)->Unit,private val client:WetypeClient=WetypeClient()) {
    private val active=AtomicBoolean(true)
    private val started=AtomicBoolean(false)
    val isRunning get()=active.get()&&valid()
    private fun ui(run:()->Unit){Hub.main.post{if(isRunning)run()else cancel()}}
    private fun check(){client.check();check(isRunning){"转文字已取消"}}
    fun start(){
        if(!started.compareAndSet(false,true))return
        Thread({
            try{
                check();require(file.optBoolean("available",true)&&file.optLong("size") in 1..VoiceTextAudio.MAX_BYTES){"转文字支持5MB以内的可用语音"}
                val local=Hub.cachedFile(file,this){!isRunning};check()
                val encoded=VoiceTextAudio.encode(local,::check);check()
                client.handshake();check()
                val stream=client.Stream(1){r->val value=r.selected(1);ui{progress(value)}}
                stream.push(encoded.bytes);stream.finish();val value=stream.run().trim();check()
                require(value.isNotBlank()){"未识别到文字，请重试"};require(value.length<=5000)
                save(value);check();ui{complete(value);cancel()}
            }catch(e:Exception){ui{
                val reason=if(e is WetypeClient.Failure)e.message else e.message?.takeIf{it.any{c->c in '\u4e00'..'\u9fff'}}?.take(120)
                failed(reason?:"这条语音暂时无法识别，请重试");cancel()
            }}finally{client.cancel()}
        },"xingyu-voice-text").start()
    }
    fun cancel(){
        if(!active.compareAndSet(true,false))return
        client.cancel()
        (Hub.http.dispatcher.runningCalls()+Hub.http.dispatcher.queuedCalls()).filter{it.request().tag()===this}.forEach{it.cancel()}
    }
}
