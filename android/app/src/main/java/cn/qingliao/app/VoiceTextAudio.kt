package cn.qingliao.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes only an authenticated local attachment. No microphone or network is opened here. */
internal object VoiceTextAudio {
    const val MAX_BYTES=5*1024*1024L
    data class Encoded(val bytes:ByteArray,val samples:Int)
    /** Some old MPEG4 extractors mix the movie and audio time scales for AAC priming.
     * Correct only an implausible delay backed by the selected track's simple, short edit.
     * Never discard or bypass a long/complex edit that could intentionally hide source audio. */
    private fun correctAacPriming(file:File,format:MediaFormat){
        if(Build.VERSION.SDK_INT>28||!format.containsKey(MediaFormat.KEY_ENCODER_DELAY))return
        val rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if(format.getInteger(MediaFormat.KEY_ENCODER_DELAY)<=rate/2)return
        val bytes=file.readBytes();val b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        data class Box(val type:String,val body:Int,val end:Int)
        fun boxes(start:Int,end:Int):List<Box>{
            val found=mutableListOf<Box>();var at=start
            while(at<end){require(end-at>=8&&found.size<1024);var size=b.getInt(at).toLong() and 0xffffffffL;var header=8
                if(size==1L){require(end-at>=16);size=b.getLong(at+8);header=16}else if(size==0L)size=(end-at).toLong()
                require(size>=header&&size<=end-at);found.add(Box(String(bytes,at+4,4,Charsets.US_ASCII),at+header,at+size.toInt()));at+=size.toInt()
            };return found
        }
        fun child(box:Box,type:String)=boxes(box.body,box.end).firstOrNull{it.type==type}
        val moov=boxes(0,bytes.size).firstOrNull{it.type=="moov"}?:return
        for(track in boxes(moov.body,moov.end).filter{it.type=="trak"}){
            val tkhd=child(track,"tkhd")?:continue;val offset=if(bytes[tkhd.body].toInt()==1)20 else 12
            require(tkhd.body+offset+4<=tkhd.end)
            if(format.containsKey("track-id")&&b.getInt(tkhd.body+offset)!=format.getInteger("track-id"))continue
            val mdia=child(track,"mdia")?:continue;val hdlr=child(mdia,"hdlr")?:continue
            require(hdlr.body+12<=hdlr.end);if(String(bytes,hdlr.body+8,4,Charsets.US_ASCII)!="soun")continue
            val mdhd=child(mdia,"mdhd")?:return;val mdOffset=if(bytes[mdhd.body].toInt()==1)20 else 12
            require(mdhd.body+mdOffset+4<=mdhd.end);val scale=b.getInt(mdhd.body+mdOffset).toLong() and 0xffffffffL;require(scale>0)
            val edit=child(track,"edts")?.let{child(it,"elst")}?:return
            require(edit.body+8<=edit.end);val version=bytes[edit.body].toInt();require(version in 0..1)
            require(b.getInt(edit.body+4)==1){"这条语音使用了复杂裁剪，暂不能转文字"}
            val timeOffset=edit.body+if(version==0)12 else 16;val mediaTime=if(version==0){require(timeOffset+8<=edit.end);b.getInt(timeOffset).toLong()}else{require(timeOffset+12<=edit.end);b.getLong(timeOffset)}
            val rateOffset=timeOffset+if(version==0)4 else 8
            require(b.getInt(rateOffset)==65536){"这条语音的裁剪速率暂不支持"}
            require(mediaTime>=0&&mediaTime<=scale/5){"这条语音使用了较长裁剪，暂不能转文字"}
            val delay=mediaTime*rate/scale
            require(delay in 0..rate/5){"这条语音的裁剪格式暂不支持"}
            format.setInteger(MediaFormat.KEY_ENCODER_DELAY,delay.toInt());return
        }
    }
    private class Pcm(private val check:()->Unit) {
        val out=ByteArrayOutputStream().apply{write(WetypeWire.HEADER)}
        private val encoder=DictationCodec.encoder(true).apply{setUseVBR(false)}
        private val frame=ShortArray(320)
        private var at=0;var samples=0;private var input=0L;private var next=0L;private var previous=0.0
        private var rate=0;private var channels=0;private var encoding=0
        fun format(f:MediaFormat){
            val r=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);val c=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val e=if(f.containsKey(MediaFormat.KEY_PCM_ENCODING))f.getInteger(MediaFormat.KEY_PCM_ENCODING)else AudioFormat.ENCODING_PCM_16BIT
            require(r in 8000..96000&&c in 1..2&&e in listOf(AudioFormat.ENCODING_PCM_16BIT,AudioFormat.ENCODING_PCM_FLOAT)){"这条语音的音频格式暂不支持"}
            require(rate==0||r==rate&&c==channels&&e==encoding){"语音格式发生变化"};rate=r;channels=c;encoding=e
        }
        private fun emit(value:Double){
            require(samples<16000*61){"语音超过60秒，暂不能转文字"}
            frame[at++]=(value.coerceIn(-1.0,1.0)*32767).toInt().toShort();samples++
            if(at==320){out.write(DictationCodec.packet(encoder,frame));at=0;require(out.size()<=256*1024){"语音编码过大"}}
        }
        fun push(buffer:ByteBuffer){
            check();buffer.order(ByteOrder.nativeOrder());val width=if(encoding==AudioFormat.ENCODING_PCM_FLOAT)4 else 2
            require(rate>0&&buffer.remaining()%(channels*width)==0){"语音数据不完整"}
            while(buffer.remaining()>=channels*width){
                if(input%2048L==0L)check()
                var mono=0.0
                repeat(channels){mono+=if(width==4)buffer.float.toDouble().also{require(it.isFinite())}else buffer.short.toDouble()/32768}
                mono/=channels
                require(input<rate*61L){"语音超过60秒，暂不能转文字"}
                while(next<=input*16000L){val fraction=if(input==0L)1.0 else (next-(input-1)*16000L)/16000.0;emit(previous+(mono-previous)*fraction);next+=rate}
                previous=mono;input++
            }
        }
        fun finish():Encoded {check();require(samples in 1600..16000*60+4800){"未找到有效的60秒以内语音"};if(at>0){java.util.Arrays.fill(frame,at,320,0.toShort());out.write(DictationCodec.packet(encoder,frame))};return Encoded(out.toByteArray(),samples)}
    }
    fun encode(file:File,check:()->Unit={}):Encoded {
        check();require(file.isFile&&file.length() in 1..MAX_BYTES){"转文字支持5MB以内的语音"};LocalAudio.requireContainer(file)
        val started=SystemClock.elapsedRealtime()
        val bounded={check();require(SystemClock.elapsedRealtime()-started<20000){"语音解码超时，请重试"}}
        val extractor=MediaExtractor();var codec:MediaCodec?=null
        try{
            file.inputStream().use{extractor.setDataSource(it.fd,0,file.length())}
            require(extractor.trackCount in 1..8){"语音文件无法读取"}
            val track=(0 until extractor.trackCount).firstOrNull{extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}?:throw IllegalArgumentException("文件中没有语音")
            val format=extractor.getTrackFormat(track);val mime=format.getString(MediaFormat.KEY_MIME)?:error("Missing audio type")
            require(mime in listOf("audio/raw","audio/mp4a-latm","audio/opus","audio/vorbis","audio/mpeg")){"这条语音的格式暂不支持"}
            require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) in 8000..96000&&format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) in 1..2){"这条语音的采样格式暂不支持"}
            if(format.containsKey(MediaFormat.KEY_DURATION))require(format.getLong(MediaFormat.KEY_DURATION)<=60_300_000){"语音超过60秒，暂不能转文字"}
            extractor.selectTrack(track);val pcm=Pcm(bounded)
            if(mime=="audio/raw"){
                pcm.format(format);val buffer=ByteBuffer.allocate(256*1024)
                while(true){bounded();buffer.clear();val n=extractor.readSampleData(buffer,0);if(n<0)break;require(n<=buffer.capacity());buffer.position(0);buffer.limit(n);pcm.push(buffer);extractor.advance()}
            }else{
                if(mime=="audio/mp4a-latm")correctAacPriming(file,format)
                val decoder=MediaCodec.createDecoderByType(mime);codec=decoder;decoder.configure(format,null,null,0);decoder.start()
                var ended=false;var inputEnded=false;val info=MediaCodec.BufferInfo()
                while(!ended){
                    bounded()
                    if(!inputEnded){val ix=decoder.dequeueInputBuffer(10000);if(ix>=0){val b=decoder.getInputBuffer(ix)?:error("No audio input");b.clear();val n=extractor.readSampleData(b,0);if(n<0){decoder.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}else{decoder.queueInputBuffer(ix,0,n,extractor.sampleTime,0);extractor.advance()}}}
                    val ix=decoder.dequeueOutputBuffer(info,10000)
                    if(ix==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)pcm.format(decoder.outputFormat)
                    else if(ix>=0){try{if(info.size>0){val b=decoder.getOutputBuffer(ix)?:error("No audio output");b.position(info.offset);b.limit(info.offset+info.size);pcm.push(b)};ended=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0}finally{decoder.releaseOutputBuffer(ix,false)}}
                }
            }
            return pcm.finish()
        }finally{runCatching{codec?.stop()};codec?.release();extractor.release()}
    }
}
