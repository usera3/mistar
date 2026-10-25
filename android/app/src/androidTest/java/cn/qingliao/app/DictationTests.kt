package cn.qingliao.app

import android.app.Instrumentation
import android.widget.EditText
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.json.JSONArray
import org.concentus.OpusDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/** Isolated protocol server; never reaches a network or a real chat account. */
internal object WetypeFixtures {
    data class Fake(val client:WetypeClient,val requests:AtomicInteger,val audio:ByteArrayOutputStream)
    fun server(status:Int=200,apiStatus:Int=status,beforeRequest:(Request)->Unit={}):Fake {
        val requests=AtomicInteger();val audio=ByteArrayOutputStream();var shared="";var polls=0
        val http=OkHttpClient.Builder().addInterceptor{chain->
            val r=chain.request();requests.incrementAndGet();beforeRequest(r);check(r.url.host=="wetype.weixin.qq.com"&&r.header("Authorization")==null&&r.header("Cookie")==null)
            val buffer=okio.Buffer();r.body!!.writeTo(buffer);val raw=buffer.readByteArray();val path=r.url.encodedPath
            val encrypted=when(path){
                "/oauth_pubkey_v2"->{val fs=WetypeWire.Fields(WetypeWire.crypt(raw,WetypeWire.BOOTSTRAP,false));val scalar=BigInteger.valueOf(7);shared=WetypeWire.sharedKey(scalar,fs.text(1));check(fs.text(4).startsWith("AND"));WetypeWire.crypt(WetypeWire.integer(1,0)+WetypeWire.string(2,WetypeWire.publicKey(scalar))+WetypeWire.string(4,"fixture-registration-only"),WetypeWire.BOOTSTRAP,true)}
                "/gen_uin_v2"->{check(WetypeWire.Fields(WetypeWire.crypt(raw,shared,false)).text(4).isNotEmpty());WetypeWire.crypt(WetypeWire.integer(1,0)+WetypeWire.integer(2,1234567),shared,true)}
                "/api_v2"->{val fs=WetypeWire.Fields(WetypeWire.Fields(WetypeWire.crypt(raw,shared,false)).bytes(1));val end=fs.int(6)>0;val seq=fs.int(7);audio.write(fs.bytes(4));if(end&&seq==0)polls++
                    val result=WetypeWire.blob(1,WetypeWire.integer(1,0))+WetypeWire.string(2,fs.text(2))+WetypeWire.integer(3,seq.toLong())+WetypeWire.string(4,"你|好")+WetypeWire.integer(5,if(end)1 else 0)+WetypeWire.integer(6,30)+WetypeWire.integer(7,3960)+WetypeWire.string(13,"你好。")+WetypeWire.integer(16,if(end)1 else 0)+WetypeWire.integer(17,if(end)1 else 2)+WetypeWire.integer(18,if(end)1 else 2)+WetypeWire.integer(19,if(polls>=2)1 else 2)+WetypeWire.integer(20,1777777777777+requests.get())+(if(polls>=2)WetypeWire.string(15,"你好，弥星。") else byteArrayOf())
                    WetypeWire.crypt(WetypeWire.blob(1,result),shared,true)
                }
                else->error("Unknown dictation path")
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(if(path=="/api_v2")apiStatus else status).message("Local isolated speech fixture").header("kb-taskid",r.header("Kb-TaskId")!!).body(encrypted.toResponseBody()).build()
        }.build()
        return Fake(WetypeClient(http),requests,audio)
    }
}
internal object DictationTests {
    fun samples(i:Instrumentation):ShortArray {
        val b=i.context.assets.open("dictation/funasr_zh.wav").use{it.readBytes()};check(String(b,0,4)=="RIFF"&&String(b,8,4)=="WAVE")
        var at=12
        while(at+8<=b.size){val n=ByteBuffer.wrap(b,at+4,4).order(ByteOrder.LITTLE_ENDIAN).int;check(n>=0&&at+8+n<=b.size)
            if(String(b,at,4)=="data"){val pcm=ByteBuffer.wrap(b,at+8,n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();return ShortArray(pcm.remaining()).also{pcm.get(it)}};at+=8+n+(n and 1)
        };error("No PCM")
    }
    fun run(i:Instrumentation,live:Boolean):String {
        val checks=JSONArray();val fixture=JSONObject(i.context.assets.open("dictation/python-fixtures.json").bufferedReader().use{it.readText()})
        val crypto=fixture.getJSONArray("crypto")
        for(n in 0 until crypto.length()){val c=crypto.getJSONObject(n);val key=BigInteger(c.getString("private"));check(WetypeWire.publicKey(key)==c.getString("public"));check(WetypeWire.sharedKey(key,c.getString("peer"))==c.getString("shared"))}
        val aes=fixture.getJSONArray("aes")
        for(n in 0 until aes.length()){val c=aes.getJSONObject(n);val plain=WetypeWire.unhex(c.getString("plain"));val cipher=WetypeWire.unhex(c.getString("cipher"));check(WetypeWire.crypt(plain,c.getString("key"),true).contentEquals(cipher));check(WetypeWire.crypt(cipher,c.getString("key"),false).contentEquals(plain))}
        val hs=fixture.getJSONArray("headers")
        for(n in 0 until hs.length()){val c=hs.getJSONObject(n);val headers=WetypeWire.headers(c.getString("kind"),WetypeWire.unhex(c.getString("body")),c.getInt("task"),BigInteger("123456789"),"0123456789ABCDEF0123456789ABCDEF", "fixture-registration-token",1777777777777,"abcdefghijklmnop");val expected=c.getJSONObject("expected");expected.keys().forEach{check(headers[it]==expected.getString(it)){"Header mismatch $it"}}}
        val vs=fixture.getJSONArray("voice")
        for(n in 0 until vs.length()){val c=vs.getJSONObject(n);check(WetypeWire.hex(WetypeWire.voice("voice-fixture",c.getInt("seq"),WetypeWire.unhex(c.getString("audio")),c.getBoolean("end"),c.getInt("total"))).equals(c.getString("expected"),true))}
        check(WetypeWire.hex(WetypeWire.dh(crypto.getJSONObject(0).getString("public"),"AND"+"x".repeat(61),"Browser ASR")).equals(fixture.getString("dh"),true))
        check(WetypeWire.hex(WetypeWire.uin("AND"+"x".repeat(61),crypto.getJSONObject(0).getString("public"),"fixture-installation")).equals(fixture.getString("uin"),true))
        fun rejected(work:()->Unit){var failed=false;try{work()}catch(_:Exception){failed=true};check(failed)}
        rejected{WetypeWire.sharedKey(BigInteger.ONE,"04"+"0".repeat(64))};rejected{WetypeWire.Fields(ByteArray(11){255.toByte()})};rejected{WetypeWire.snappy(byteArrayOf(127))}
        check(WetypeWire.snappy(byteArrayOf(3,8,97,98,99)).contentEquals("abc".toByteArray()));checks.put("extension crypto/header/protobuf vectors, invalid points/varints and bounded Snappy")
        val encoded=ByteArrayOutputStream();encoded.write(WetypeWire.HEADER);val samples=samples(i);val encoder=DictationCodec.encoder(true);val decoder=OpusDecoder(16000,1);var decoded=0;var energy=0L
        val began=System.nanoTime()
        for(at in samples.indices step 320){val frame=ShortArray(320);samples.copyInto(frame,0,at,minOf(samples.size,at+320));val packet=DictationCodec.packet(encoder,frame);encoded.write(packet);val output=ShortArray(320);decoded+=decoder.decode(packet,2,packet.size-2,output,0,320,false);output.forEach{energy+=it.toLong()*it}}
        val encodeMs=(System.nanoTime()-began)/1000000;check(decoded>=samples.size&&energy>1000000);checks.put("pure Java 16kHz Opus encoder/decoder on actual Android; no API29 encoder needed")
        val fake=WetypeFixtures.server();fake.client.handshake();val stream=fake.client.Stream(0);stream.changeLevel(2);stream.push(encoded.toByteArray());stream.finish();check(stream.run()=="你好，弥星。");check(fake.audio.toByteArray().contentEquals(encoded.toByteArray()));fake.client.cancel();checks.put("encrypted fake service roundtrip, negotiated packet queue, final selected version, exact audio and no credentials")
        val failed=WetypeFixtures.server(405);try{failed.client.handshake();error("HTTP405 accepted")}catch(e:WetypeClient.Failure){check(e.status==405)}finally{failed.client.cancel()}
        val overloaded=WetypeFixtures.server();overloaded.client.handshake();val overflow=overloaded.client.Stream();rejected{overflow.push(ByteArray(256*1024+1))};rejected{overloaded.client.check()};overloaded.client.cancel();checks.put("HTTP failure, bounded queue and cancellation")
        i.runOnMainSync{
            val input=EditText(i.targetContext);input.setText("前面的文字");input.setSelection(1,3);var valid=true;val snapshot=DictationDraft(input){valid};check(snapshot.insert("识别结果")==null);check(input.text.toString()=="前识别结果文字");check(snapshot.insert("重复")!=null)
            val changed=DictationDraft(input){valid};input.append("新草稿");check(changed.insert("迟到")!=null)
            val invalid=DictationDraft(input){valid};valid=false;check(invalid.insert("其他会话")!=null)
            input.setText("字".repeat(5000));input.setSelection(input.length());check(DictationDraft(input){true}.insert("超限")!=null)
        };checks.put("native selection replacement, one insert, changed draft/session rejection and 5000-character bound")
        var recognized=""
        val variants=JSONArray()
        if(live){for(level in 0..2){val client=WetypeClient();try{client.handshake();val s=client.Stream(level);s.push(encoded.toByteArray());s.finish();val text=s.run();check(text.contains("识别")&&text.length>10);variants.put(JSONObject().put("level",level).put("text",text));if(level==1)recognized=text}finally{client.cancel()}};checks.put("real raw/basic/enhanced ASR of bundled public sample encoded by native Java codec")}
        val result=JSONObject().put("passed",true).put("api",android.os.Build.VERSION.SDK_INT).put("livePublicSample",live).put("noPrivateAudio",true).put("checks",checks).put("encodedBytes",encoded.size()).put("encodingMilliseconds",encodeMs).put("recognizedPublicSample",recognized).put("realVariants",variants)
        File(i.targetContext.getExternalFilesDir(null),if(live)"dictation-live.json" else "dictation-wire.json").writeText(result.toString(2))
        return "PASS native dictation protocol, codec, cancellation and draft safety"+if(live)"; real public ASR" else ""
    }
}
