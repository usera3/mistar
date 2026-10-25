package cn.qingliao.app

import android.media.*
import android.media.audiofx.*
import org.concentus.OpusApplication
import org.concentus.OpusEncoder
import java.util.concurrent.atomic.AtomicBoolean

/** Native Android 8+ encoder; Android 9 does not require a system Opus encoder or Google service. */
internal object DictationCodec {
    fun encoder(compact:Boolean)=OpusEncoder(16000,1,OpusApplication.OPUS_APPLICATION_AUDIO).apply{bitrate=if(compact)24000 else 48000;complexity=if(compact)3 else 5}
    fun packet(encoder:OpusEncoder,pcm:ShortArray):ByteArray {
        require(pcm.size==320);val bytes=ByteArray(1500);val n=encoder.encode(pcm,0,320,bytes,0,bytes.size);require(n in 1..1500)
        return byteArrayOf(n.toByte(),(n shr 8).toByte())+bytes.copyOf(n)
    }
}
internal class DictationAudio(private val compact:Boolean,private val push:(ByteArray)->Unit,private val onLevel:(Float)->Unit,private val onEnd:()->Unit,private val onError:(Throwable)->Unit) {
    private val stopped=AtomicBoolean(false)
    @Volatile private var record:AudioRecord?=null
    fun stop(){stopped.set(true);runCatching{record?.stop()}}
    fun run(){
        val effects=mutableListOf<AudioEffect>();var current:AudioRecord?=null
        try {
            val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);require(minimum>0)
            current=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,6400))
            require(current.state==AudioRecord.STATE_INITIALIZED){"麦克风暂不可用"};record=current
            if(stopped.get())return
            if(NoiseSuppressor.isAvailable())runCatching{NoiseSuppressor.create(current.audioSessionId)?.let{it.enabled=true;effects.add(it)}}
            if(AutomaticGainControl.isAvailable())runCatching{AutomaticGainControl.create(current.audioSessionId)?.let{it.enabled=true;effects.add(it)}}
            val encoder=DictationCodec.encoder(compact);val pcm=ShortArray(320);var offset=0;var frames=0
            push(WetypeWire.HEADER);current.startRecording();require(current.recordingState==AudioRecord.RECORDSTATE_RECORDING)
            while(!stopped.get()&&frames<3000){
                val n=current.read(pcm,offset,320-offset)
                if(stopped.get())break
                require(n>0){"录音中断，请重试"};offset+=n
                if(offset==320){push(DictationCodec.packet(encoder,pcm));if(frames%5==0){var sum=0.0;pcm.forEach{sum+=it.toDouble()*it};onLevel((kotlin.math.sqrt(sum/320)/12000).toFloat().coerceIn(0f,1f))};offset=0;frames++}
            }
            if(!stopped.get()&&offset>0){java.util.Arrays.fill(pcm,offset,pcm.size,0.toShort());push(DictationCodec.packet(encoder,pcm))}
        }catch(e:Throwable){if(!stopped.get())onError(e)}finally{
            stopped.set(true);runCatching{current?.stop()};effects.forEach{runCatching{it.release()}};current?.release();record=null;onEnd()
        }
    }
}
