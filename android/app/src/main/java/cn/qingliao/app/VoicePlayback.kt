package cn.qingliao.app

import android.content.Context
import android.media.*
import android.os.*
import java.io.File

/** Foreground, single-source speech playback. Progress memory is account/session-local, never uploaded. */
internal class VoicePlayback(context:Context,private val changed:(String)->Unit){
    data class State(val key:String,val phase:String="idle",val position:Int=0,val duration:Int=0,val speed:Float=1f,val error:String="")
    private val audio=context.getSystemService(AudioManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({value->if(value!=AudioManager.AUDIOFOCUS_GAIN)pause()},Hub.main).build()
    private val memory=linkedMapOf<String,State>()
    private var current=State("")
    private var valid:()->Boolean={false}
    private var tag:Any?=null
    @Volatile private var sequence=0L
    var media:MediaPlayer?=null;private set
    val activeKey get()=current.key
    private var prepared=false
    private var seeking=false
    private var requestedPosition=0
    private var focusHeld=false
    private val poll=object:Runnable{override fun run(){
        if(!valid()){stop();return}
        if(current.phase=="playing"){
            sample();changed(current.key);Hub.main.postDelayed(this,100)
        }
    }}
    fun state(key:String):State=if(current.key==key){sample();current}else memory[key]?.copy(phase="saved")?:State(key)
    private fun remember(){
        if(current.key.isEmpty())return
        memory.remove(current.key);memory[current.key]=current.copy(phase=if(current.position>0)"paused"else"idle",error="")
        while(memory.size>100)memory.remove(memory.keys.first())
    }
    private fun sample(){
        if(prepared&&!seeking)media?.let{p->runCatching{current=current.copy(position=p.currentPosition.coerceIn(0,current.duration.coerceAtLeast(0)))} }
    }
    fun toggle(key:String,scope:()->Boolean,load:(Any,()->Boolean)->File){
        if(!scope())return
        if(current.key==key){
            when(current.phase){"loading"->{stop();return};"playing"->{pause();return};"paused"->{if(prepared){start();return}};"ended"->{if(prepared){seek(0);start();return}}}
        }
        stop();val old=memory[key]?:State(key)
        valid=scope;current=old.copy(phase="loading",error="");val version=++sequence;val requestTag=Any();tag=requestTag
        changed(key)
        Hub.io.execute{
            try{
                val file=load(requestTag){sequence!=version||!scope()};LocalAudio.requireContainer(file)
                Hub.main.post{
                    if(sequence!=version||!scope()){if(sequence==version)stop();return@post}
                    try{
                        val player=MediaPlayer();media=player;prepared=false;player.setAudioAttributes(attributes);file.inputStream().use{player.setDataSource(it.fd,0,file.length())}
                        player.setOnPreparedListener{p->
                            if(sequence!=version||media!==p||!scope()){if(media===p)stop();return@setOnPreparedListener}
                            prepared=true;current=current.copy(duration=p.duration.coerceAtLeast(0),phase="paused")
                            val position=old.position.takeIf{it<current.duration-250}?:0
                            if(position>0)seek(position)
                            start()
                        }
                        player.setOnSeekCompleteListener{p->if(sequence==version&&media===p){seeking=false;sample();changed(key)}}
                        player.setOnCompletionListener{p->if(sequence==version&&media===p){current=current.copy(phase="ended",position=0);memory.remove(key);Hub.main.removeCallbacks(poll);abandon();changed(key)}}
                        player.setOnErrorListener{p,_,_->if(sequence==version&&media===p)fail("无法播放，点按重试");true}
                        player.prepareAsync()
                    }catch(_:Exception){if(sequence==version)fail("无法播放，点按重试")}
                }
            }catch(_:Exception){Hub.main.post{if(sequence==version){if(scope())fail("加载失败，点按重试")else stop()}}}
        }
    }
    private fun start(){
        if(!prepared||!valid())return
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){current=current.copy(phase="paused",error="音频暂被占用，点按重试");changed(current.key);return}
        focusHeld=true
        try{
            media!!.playbackParams=PlaybackParams().setSpeed(current.speed).setPitch(1f)
            media!!.start();current=current.copy(phase="playing",error="");Hub.main.removeCallbacks(poll);Hub.main.post(poll);changed(current.key)
        }catch(_:Exception){fail("无法播放，点按重试")}
    }
    fun pause(){
        if(current.phase!="playing"||!prepared)return
        sample();runCatching{media?.pause()};current=current.copy(phase="paused");remember();Hub.main.removeCallbacks(poll);abandon();changed(current.key)
    }
    fun seek(position:Int){
        if(!prepared||!valid()||current.duration<=0)return
        requestedPosition=position.coerceIn(0,(current.duration-1).coerceAtLeast(0));seeking=true
        current=current.copy(position=requestedPosition,phase=if(current.phase=="ended")"paused"else current.phase)
        runCatching{media?.seekTo(requestedPosition.toLong(),MediaPlayer.SEEK_CLOSEST)}.onFailure{seeking=false}
        remember();changed(current.key)
    }
    fun cycleSpeed(){
        if(!prepared||!valid())return
        val next=when(current.speed){1f->1.5f;1.5f->2f;else->1f}
        try{
            val wasPlaying=current.phase=="playing"
            if(wasPlaying)media!!.playbackParams=PlaybackParams().setSpeed(next).setPitch(1f)
            current=current.copy(speed=next);remember();changed(current.key)
        }catch(_:Exception){current=current.copy(error="当前音频暂不支持倍速");changed(current.key)}
    }
    fun stop(keepPosition:Boolean=true){
        val key=current.key;sequence++;sample();if(keepPosition&&current.phase!="ended")remember()
        Hub.main.removeCallbacks(poll);val old=tag;tag=null
        if(old!=null)(Hub.http.dispatcher.runningCalls()+Hub.http.dispatcher.queuedCalls()).filter{it.request().tag()===old}.forEach{it.cancel()}
        runCatching{media?.release()};media=null;prepared=false;seeking=false;valid={false};abandon();current=State("")
        if(key.isNotEmpty())changed(key)
    }
    private fun abandon(){if(focusHeld){audio.abandonAudioFocusRequest(focus);focusHeld=false}}
    private fun fail(message:String){val key=current.key;val value=current;stop();current=value.copy(phase="error",error=message);changed(key)}
    fun forget(key:String){if(current.key==key)stop(false);memory.remove(key)}
    fun dispose(){stop(false);memory.clear()}
}
