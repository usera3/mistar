package cn.qingliao.app

import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Separate system-TLS client: never carries Xingyu cookies, tokens, draft or chat context. */
internal class WetypeClient(private val http:OkHttpClient=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).connectTimeout(8,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()) {
    class Failure(val category:String,val status:Int=0):Exception(if(status in listOf(429,503))"语音服务正忙，请稍后重试" else if(status>0)"语音服务暂不可用，请稍后重试" else when(category){"backpressure"->"网络积压，已停止录音，请缩短后重试";"timeout"->"语音识别超时，请稍后重试";"cancelled"->"已取消";"network"->"连接语音服务失败，请检查网络后重试";else->"语音服务暂时不可用，请稍后重试"})
    private val cancelled=AtomicBoolean(false)
    @Volatile private var call:Call?=null
    @Volatile private var key:String?=null
    @Volatile private var uin=BigInteger.ZERO
    private var task=0
    fun check(){if(cancelled.get()||Thread.currentThread().isInterrupted)throw Failure("cancelled")}
    fun cancel(){cancelled.set(true);call?.cancel();synchronized(this){key=null;uin=BigInteger.ZERO}}
    private fun exchange(kind:String,payload:ByteArray,k:String,t:Int,token:String="",timeout:Long=15000):ByteArray {
        check();val encrypted=WetypeWire.crypt(payload,k,true)
        val path=when(kind){"dh"->"/oauth_pubkey_v2";"uin"->"/gen_uin_v2";"api"->"/api_v2";else->error("Invalid endpoint")}
        val request=Request.Builder().url("https://wetype.weixin.qq.com"+path).post(encrypted.toRequestBody("application/octet-stream".toMediaType()))
        WetypeWire.headers(kind,encrypted,t,uin,k,token).forEach{(n,v)->request.header(n,v)}
        val current=http.newCall(request.build());current.timeout().timeout(timeout.coerceIn(1,15000),TimeUnit.MILLISECONDS);call=current
        try{check();return current.execute().use{r->
            check();if(!r.isSuccessful)throw Failure("http",r.code)
            if(r.header("kb-noretry")!=null||r.header("kb-taskid")?.let{it!=t.toString()}==true)throw Failure("protocol")
            val body=r.body?:throw Failure("protocol");if(body.contentLength()>2*1024*1024)throw Failure("protocol")
            val bytes=ByteArrayOutputStream();body.byteStream().use{input->val b=ByteArray(8192);while(true){check();val n=input.read(b);if(n<0)break;if(bytes.size()+n>2*1024*1024)throw Failure("protocol");bytes.write(b,0,n)}}
            val decrypted=WetypeWire.crypt(bytes.toByteArray(),k,false)
            when(r.header("kb-compressiontype")?:"1"){"0","1"->decrypted;"2"->if(kind=="dh")throw Failure("protocol") else WetypeWire.snappy(decrypted);else->throw Failure("protocol")}
        }}catch(e:Failure){throw e}catch(_:Exception){check();throw Failure("network")}finally{if(call===current)call=null}
    }
    fun handshake(){
        check();val scalar=WetypeWire.scalar();val pub=WetypeWire.publicKey(scalar);val device=WetypeWire.device()
        val fs=WetypeWire.Fields(exchange("dh",WetypeWire.dh(pub,device),WetypeWire.BOOTSTRAP,2147483646)).result()
        val peer=fs.text(2);val token=fs.text(4);require(peer.isNotEmpty()&&token.isNotEmpty()&&fs.number(5).signum()==0)
        val negotiated=WetypeWire.sharedKey(scalar,peer)
        val registered=WetypeWire.Fields(exchange("uin",WetypeWire.uin(device,peer,UUID.randomUUID().toString()),negotiated,2147483645,token)).result()
        val id=registered.number(2);require(id.signum()>0);synchronized(this){check();uin=id;key=negotiated}
    }
    data class Result(val id:String,val seq:Int,val raw:String,val basic:String,val enhanced:String,val end:Int,val poll:Int,val asrProgress:Int,val basicProgress:Int,val enhancedProgress:Int,val time:Long,val packet:Int,val interval:Int) {
        fun selected(level:Int)=when{level==2&&enhanced.isNotBlank()->enhanced;level!=0&&basic.isNotBlank()->basic;else->raw}.replace("|","")
        fun complete(level:Int):Boolean {
            if(end==0)return false
            fun done(n:Int)=n==1||n==3
            return when(level){0->asrProgress==0||done(asrProgress);1->done(basicProgress)||(basicProgress==0&&poll==0&&asrProgress!=2);else->done(enhancedProgress)||(enhancedProgress==0&&poll==1&&enhanced.isNotBlank())}
        }
    }
    inner class Stream(@Volatile var level:Int=1,private val onResult:(Result)->Unit={}) {
        private val queue=java.util.ArrayDeque<ByteArray>();private var queued=0
        private val lock=Any();private val closed=AtomicBoolean(false)
        private val id=(uin.mod(BigInteger.valueOf(1000000))).toString()+System.currentTimeMillis()+WetypeWire.letters(6)
        private var seq=0;private var total=0;private var packet=3960;private var interval=120
        @Volatile private var last:Result?=null;private var lastSend=System.nanoTime();private var deadline=Long.MAX_VALUE
        @Volatile private var finishAt=Long.MAX_VALUE
        @Volatile private var enhancedRequested=level==2
        init {require(level in 0..2&&key!=null&&uin.signum()>0)}
        fun push(bytes:ByteArray){check();synchronized(lock){if(closed.get())return;if(queued+bytes.size>256*1024){cancel();throw Failure("backpressure")};queue.add(bytes.copyOf());queued+=bytes.size}}
        fun finish(){closed.set(true);finishAt=minOf(finishAt,System.nanoTime()+TimeUnit.SECONDS.toNanos(45))}
        fun changeLevel(value:Int){require(value in 0..2);level=value;if(value==2)enhancedRequested=true;last?.let{onResult(it)}}
        private fun take():ByteArray=synchronized(lock){val out=ByteArray(minOf(packet,queued));var at=0;while(at<out.size){val first=queue.removeFirst();val n=minOf(first.size,out.size-at);first.copyInto(out,at,0,n);if(n<first.size)queue.addFirst(first.copyOfRange(n,first.size));at+=n};queued-=out.size;out}
        private fun size()=synchronized(lock){queued}
        private fun sleep(ms:Long){var remain=ms;while(remain>0){check();val n=minOf(20,remain);Thread.sleep(n);remain-=n}}
        private fun send(audio:ByteArray,end:Boolean=false,poll:Boolean=false){
            check();val k=key?:throw Failure("cancelled");val s=if(poll)0 else ++seq;total=Math.addExact(total,audio.size);require(++task<2147483645)
            val limit=minOf(deadline,finishAt);val remaining=if(limit==Long.MAX_VALUE)15000 else TimeUnit.NANOSECONDS.toMillis(limit-System.nanoTime()).coerceAtMost(15000)
            if(remaining<=0)throw Failure("timeout");lastSend=System.nanoTime()
            val fs=WetypeWire.Fields(WetypeWire.Fields(exchange("api",WetypeWire.voice(id,s,audio,end,total,enhancedRequested),k,task,timeout=remaining)).bytes(1))
            require(WetypeWire.Fields(fs.bytes(1)).number(1).signum()==0&&fs.text(2)==id)
            val previous=last;val time=fs.long(20);val receivedSeq=fs.int(3)
            if(previous!=null&&(time>0&&previous.time>0&&(time<previous.time||time==previous.time&&receivedSeq>0&&receivedSeq<previous.seq)||time==0L&&receivedSeq>0&&receivedSeq<previous.seq))return
            fun text(tag:Int,old:String)=if(fs.all.containsKey(tag))fs.text(tag) else old
            fun progress(tag:Int,old:Int)=fs.int(tag).let{if(it==0&&old==2)2 else it}
            val r=Result(id,receivedSeq,text(4,previous?.raw?:""),text(13,previous?.basic?:""),text(15,previous?.enhanced?:""),fs.int(5),fs.int(16),progress(17,previous?.asrProgress?:0),progress(18,previous?.basicProgress?:0),progress(19,previous?.enhancedProgress?:0),time,fs.int(7),fs.int(6))
            require(maxOf(r.raw.length,r.basic.length,r.enhanced.length)<=5000)
            last=r;packet=(r.packet.takeIf{it>0}?:packet).coerceIn(1,65536);interval=(r.interval.takeIf{it>0}?:interval).coerceIn(30,240);onResult(r)
        }
        fun run():String {
            try {
                while(true){check();val count=size();if(count>0&&(closed.get()||count>=packet||System.nanoTime()-lastSend>250000000)){send(take());if(last?.end!=0&&!closed.get())throw Failure("early_end");sleep(interval.toLong())}else if(closed.get()&&count==0)break else sleep(20)}
                if(total==0)return "";send(byteArrayOf(),true);deadline=minOf(finishAt,System.nanoTime()+TimeUnit.SECONDS.toNanos(20))
                while(last?.complete(level)!=true){check();if(System.nanoTime()>deadline)throw Failure("timeout");sleep(interval.toLong());send(byteArrayOf(),true,true)}
                check();return last!!.selected(level)
            }finally{synchronized(lock){queue.clear();queued=0}}
        }
    }
}
