package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.*
import android.media.*
import android.os.*
import android.view.*
import android.widget.*
import com.google.crypto.tink.*
import com.google.crypto.tink.signature.SignatureKeyTemplates
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.*
import java.io.*
import java.nio.*
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Owned emulator only: synthetic media, actual native gestures/decoders/Tink, fully intercepted HTTP. */
object ChatPolishTests {
    fun run(i:Instrumentation,layout:String):String {
        com.google.crypto.tink.hybrid.HybridConfig.register();com.google.crypto.tink.signature.SignatureConfig.register();com.google.crypto.tink.streamingaead.StreamingAeadConfig.register()
        val folder=File(i.targetContext.getExternalFilesDir(null),"chat-polish-test").apply{mkdirs()}
        val owner="84000001";val peer="84000002";val other="84000003";val session="synthetic-chat-polish-session"
        val identities=ConcurrentHashMap<String,JSONObject>();val files=ConcurrentHashMap<String,ByteArray>();val uploaded=ConcurrentHashMap<String,ByteArray>();val delivered=CopyOnWriteArrayList<JSONObject>()
        val failUpload=AtomicBoolean();val requests=CopyOnWriteArrayList<String>();val originalHttp=Hub.http;val originalUpdate=AppUpdates.transport
        fun user(id:String,name:String)=JSONObject().put("id",id).put("nickname",name).put("username","qa_$id").put("state","accepted").put("friend",true)
        fun identity():JSONObject {
            fun serialize(k:KeysetHandle):String{val out=ByteArrayOutputStream();k.writeNoSecret(JsonKeysetWriter.withOutputStream(out));return out.toString("UTF-8")}
            val enc=serialize(KeysetHandle.generateNew(KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM")).publicKeysetHandle)
            val sig=serialize(KeysetHandle.generateNew(SignatureKeyTemplates.ECDSA_P256).publicKeysetHandle)
            return JSONObject().put("enc",enc).put("sig",sig).put("key_id",MessageDigest.getInstance("SHA-256").digest((enc+"\n"+sig).toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)})
        }
        identities[peer]=identity();identities[other]=identity()
        fun message(kind:String,value:String)=JSONObject().put("id",UUID.randomUUID().toString()).put("sender",peer).put("recipient",owner).put("type",kind).put("text",value).put("created",System.currentTimeMillis()-5000)
        fun attachment(bytes:ByteArray,name:String):JSONObject {
            val id=UUID.randomUUID().toString().replace("-","");val key=ChatCrypto.fileKey();val context="qingliao:attachment:1:"+UUID.randomUUID()
            val out=ByteArrayOutputStream();ChatCrypto.encryptFile(bytes.inputStream(),out,key,context);files[id]=out.toByteArray()
            return message("voice","24″").put("file_id",id).put("file",JSONObject().put("id",id).put("name",name).put("mime","audio/wav").put("size",bytes.size).put("crypto",key).put("context",context))
        }
        val wav=ByteBuffer.allocate(44+16000*2*24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(capacity()-8);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(16000);putInt(32000);putShort(2);putShort(16);put("data".toByteArray());putInt(capacity()-44)
            repeat(16000*24){putShort((kotlin.math.sin(it*2*Math.PI*440/16000)*1200).toInt().toShort())}
        }.array()
        val text=message("text","周末一起看星星吧，记得带相机。")
        val voice=attachment(wav,"synthetic-tone.wav");val invalid=attachment("#EXTM3U\nhttps://127.0.0.1:9/forbidden\n".toByteArray(),"invalid-playlist.wav")
        fun ui(f:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())f()else i.runOnMainSync(f)}
        fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        fun set(o:Any,n:String,v:Any?){o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
        fun invoke(o:Any,n:String,vararg args:Any?){ui{o.javaClass.declaredMethods.first{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(o,*args)}}
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun await(label:String,timeout:Long=20000,p:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(true){var yes=false;ui{yes=p()};if(yes){i.waitForIdleSync();return};check(SystemClock.elapsedRealtime()<end){"Timed out: $label"};Thread.sleep(40)}}
        fun capture(name:String){i.waitForIdleSync();Thread.sleep(250);val b=i.uiAutomation.takeScreenshot();File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        ui {
            Hub.token=session;Hub.me=user(owner,"星光旅人").put("admin_password_change_required",true);Hub.connected=false;ChatCrypto.clear()
            Hub.http=OkHttpClient.Builder().addInterceptor{chain->
                val r=chain.request();check(r.url.host in setOf("47.245.2.254","api.claudcode.xyz"));val path=r.url.encodedPath;requests.add(path)
                val buffer=Buffer();r.body?.writeTo(buffer);val raw=buffer.readByteArray();var binary:ByteArray?=null;var status=200
                val body=if(r.method=="POST"&&path!="/api/uploads")runCatching{JSONObject(String(raw))}.getOrDefault(JSONObject())else JSONObject()
                val result=when {
                    path.startsWith("/api/crypto/identity/")->identities[path.substringAfterLast('/')]?:JSONObject()
                    path=="/api/crypto/identity"->{identities[owner]=body;JSONObject().put("ok",true)}
                    path=="/api/contacts"->JSONObject().put("contacts",JSONArray().put(user(peer,"小桃")).put(user(other,"山海")))
                    path.startsWith("/api/relationships/")->user(path.substringAfterLast('/'),"小桃")
                    path=="/api/conversations"->JSONObject().put("conversations",JSONArray())
                    path.startsWith("/api/messages/")->JSONObject().put("messages",JSONArray()).put("has_more",false)
                    path.startsWith("/api/files/")->{check(r.header("Authorization")=="Bearer $session");binary=files[path.substringAfterLast('/')]?:error("Unknown synthetic file");JSONObject()}
                    path=="/api/uploads"->{if(failUpload.getAndSet(false)){status=503;JSONObject().put("error","Synthetic retry")}else{val id=UUID.randomUUID().toString().replace("-","");uploaded[id]=raw;JSONObject().put("id",id)}}
                    path=="/api/messages"&&r.method=="POST"->{check(body.getString("type")=="encrypted");val wire=JSONObject(body.toString()).put("id",UUID.randomUUID().toString()).put("sender",owner).put("recipient",peer).put("created",System.currentTimeMillis());val opened=ChatCrypto.incoming(wire);check(!opened.optBoolean("decrypt_failed"));delivered.add(opened);wire}
                    else->JSONObject().put("ok",true)
                }
                Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic native chat fixture").body((binary?:result.toString().toByteArray()).toResponseBody((if(binary!=null)"application/octet-stream"else"application/json").toMediaType())).build()
            }.build();AppUpdates.transport=Hub.http
        }
        LocalData.homePart(owner,"contacts",JSONArray().put(user(peer,"小桃")).put(user(other,"山海")))
        for(m in listOf(text,voice,invalid))LocalData.put(owner,m)
        val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var checks=0
        fun find(tag:String)=walk(a.window.decorView).firstOrNull{it.tag==tag}
        fun editor()=field(a,"composer") as EditText
        fun show(items:List<JSONObject> = listOf(text,voice)){
            ui{Hub.connected=false;set(a,"peer",user(peer,"小桃"));@Suppress("UNCHECKED_CAST") val messages=field(a,"messages") as MutableList<JSONObject>;messages.clear();messages.addAll(items)}
            invoke(a,"showChat");await("composer"){find("chat.composer")!=null};Thread.sleep(200)
        }
        fun panel()=field(a,"inputPanel") as ChatInputPanel
        fun controller()=field(a,"voicePlayback") as VoicePlayback
        fun state(m:JSONObject=voice)=controller().state(m.getString("id")+"|"+m.getString("file_id"))
        fun click(tag:String){ui{checkNotNull(find(tag)){"Missing $tag"}.performClick()}}
        fun swipe(view:View,dx:Float,dy:Float,cancel:Boolean=false){
            var x=0f;var y=0f;ui{view.requestRectangleOnScreen(Rect(0,0,view.width,view.height),true)};Thread.sleep(150)
            ui{val rect=Rect();check(view.getGlobalVisibleRect(rect));x=rect.left+rect.width()*.25f;y=rect.top+rect.height()*.5f}
            val down=SystemClock.uptimeMillis();fun event(action:Int,px:Float,py:Float){val e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,px,py,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;i.sendPointerSync(e);e.recycle()}
            event(android.view.MotionEvent.ACTION_DOWN,x,y);for(n in 1..10){event(android.view.MotionEvent.ACTION_MOVE,x+dx*n/10,y+dy*n/10);Thread.sleep(12)};event(if(cancel)android.view.MotionEvent.ACTION_CANCEL else android.view.MotionEvent.ACTION_UP,x+dx,y+dy);i.waitForIdleSync();Thread.sleep(200)
        }
        fun bubble(m:JSONObject):View { @Suppress("UNCHECKED_CAST") val map=field(a,"messageBubbles") as Map<String,View>;return map[m.getString("id")]!! }
        try {
            ui{Hub.me.remove("admin_password_change_required")};show()
            val cfg=a.resources.configuration
            check(if(layout=="phone")!CompactUi.enabled(a)&&cfg.screenWidthDp>=360&&cfg.screenHeightDp>=600 else CompactUi.enabled(a));check(a.window.decorView.width==if(layout=="phone")1080 else 410);checks++
            val source=editor();val root=field(a,"root")
            invoke(a,"sendMenu");check(panel().opened&&find("composer.action.image")!=null&&editor()===source&&field(a,"root")===root);check((field(a,"actionSheet") as? Dialog)?.isShowing!=true);capture("input-tools");checks++
            ui{source.setText("你好AB");source.setSelection(2,3)};click("composer.tab.emoji");click("composer.emoji.😊");check(source.text.toString()=="你好😊B"&&panel().opened);checks++
            ui{source.setText("好".repeat(4999));source.setSelection(source.length())};click("composer.emoji.😊");check(source.length()==4999);checks++
            ui{source.setText("你好😊");source.setSelection(source.length())};click("composer.emoji.delete");check(source.text.toString()=="你好");capture("input-emoji");checks++
            ui{a.onBackPressed()};check(!panel().opened&&field(a,"peer")!=null&&source.text.toString()=="你好");checks++
            invoke(a,"sendMenu");val stale=find("composer.action.image")!!;val filter=IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);addDataType("image/*")};val monitor=i.addMonitor(filter,Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true)
            invoke(a,"openChat",user(other,"山海"));ui{stale.performClick()};check(monitor.hits==0);i.removeMonitor(monitor);checks++
            show(listOf(text));ui{editor().setText("")};val density=a.resources.displayMetrics.density
            swipe(bubble(text),12*density,0f);check(field(a,"replyTarget")==null);swipe(bubble(text),64*density,0f,true);check(field(a,"replyTarget")==null);checks+=2
            swipe(bubble(text),64*density,0f);check((field(a,"replyTarget") as JSONObject).getString("id")==text.getString("id"));invoke(a,"hideKeyboard");capture("swipe-reply");checks++
            invoke(a,"beginMessageSelection",text);swipe(bubble(text),64*density,0f);check(field(a,"selecting")==true);invoke(a,"endMessageSelection");checks++
            show();val stableEditor=editor();val stableBubble=bubble(voice)
            invoke(a,"playVoice",voice);await("voice playing"){state().phase=="playing"};check(state().duration in 23500..24500);Thread.sleep(350)
            invoke(a,"playVoice",voice);check(state().phase=="paused");val paused=state().position;Thread.sleep(300);check(kotlin.math.abs(state().position-paused)<100);checks++
            ui{controller().seek(8000)};await("seek complete"){field(controller(),"seeking")==false&&kotlin.math.abs(state().position-8000)<300};checks++
            ui{controller().cycleSpeed()};check(state().speed==1.5f&&state().phase=="paused"&&!controller().media!!.isPlaying);checks++
            invoke(a,"playVoice",voice);await("resume at speed"){state().phase=="playing"};check(controller().media!!.playbackParams.speed==1.5f);ui{controller().cycleSpeed()};check(state().speed==2f);ui{controller().cycleSpeed()};check(state().speed==1f&&editor()===stableEditor&&bubble(voice)===stableBubble);capture("voice-player");checks++
            val manager=a.getSystemService(AudioManager::class.java);val competing=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).setOnAudioFocusChangeListener({ },Hub.main).build()
            ui{check(manager.requestAudioFocus(competing)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)};await("focus pauses playback"){state().phase=="paused"};ui{manager.abandonAudioFocusRequest(competing)};Thread.sleep(250);check(!controller().media!!.isPlaying);checks++
            val seek=find("voice.seek:${voice.getString("id")}|${voice.getString("file_id")}")!!;val quoteBefore=field(a,"replyTarget");swipe(seek,55*density,0f);check(field(a,"replyTarget")===quoteBefore);checks++
            val remembered=state().position;invoke(a,"stopPlayer");check(controller().media==null);invoke(a,"playVoice",voice);await("remembered playback"){state().phase=="playing"};check(state().position>=remembered-300);checks++
            ui{invoke(a,"onPause")};check(controller().media==null);ui{invoke(a,"onResume")};Thread.sleep(250);check(controller().media==null);checks++
            show(listOf(invalid));val invalidEditor=editor();invoke(a,"playVoice",invalid);await("playlist rejected"){state(invalid).phase=="error"};check(controller().media==null&&editor()===invalidEditor);checks++
            show();invoke(a,"selectReply",text);invoke(a,"startRecording");await("record started"){field(a,"recording")!=null};check((field(a,"inputRowView") as View).visibility==View.GONE);Thread.sleep(1700)
            click("composer.record.preview");await("pending recording"){field(a,"readyVoice")!=null};val clip=field(a,"readyVoice")!!;val raw=field(clip,"file") as File;check(raw.isFile&&field(clip,"duration") as Int>=1000&&delivered.isEmpty());LocalAudio.requireContainer(raw);capture("voice-preview");checks++
            click("composer.voiceDraft.play");await("preview audition"){controller().media?.isPlaying==true};check(delivered.isEmpty());click("composer.voiceDraft.play");checks++
            failUpload.set(true);click("composer.voiceDraft.send");await("failed voice preserved"){field(a,"readyVoice")===clip};check(raw.isFile&&delivered.isEmpty());checks++
            click("composer.voiceDraft.send");await("encrypted voice queued",60000){delivered.size==1};check(!raw.exists());val sent=delivered.single();check(sent.getString("type")=="voice"&&sent.getJSONObject("quote").getString("id")==text.getString("id"));val metadata=sent.getJSONObject("file");val decrypted=ByteArrayOutputStream();ChatCrypto.decryptFile(uploaded[metadata.getString("id")]!!.inputStream(),decrypted,metadata.getString("crypto"),metadata.getString("context"));check(decrypted.size()>0&&String(decrypted.toByteArray(),4,4)=="ftyp");checks++
            invoke(a,"startRecording");await("second recording"){field(a,"recording")!=null};Thread.sleep(1700);ui{set(a,"recordStart",SystemClock.elapsedRealtime()-61000);(field(a,"tick") as Runnable).run()};await("limit preview"){field(a,"readyVoice")!=null};check(delivered.size==1);val limited=field(field(a,"readyVoice")!!,"file") as File;click("composer.voiceDraft.discard");check(!limited.exists()&&field(a,"readyVoice")==null);checks++
            invoke(a,"startRecording");await("background recording"){field(a,"recording")!=null};Thread.sleep(1700);ui{invoke(a,"onPause")};check(field(a,"recording")==null&&field(a,"readyVoice")!=null);ui{invoke(a,"onResume")};check(controller().media==null);val abandoned=field(field(a,"readyVoice")!!,"file") as File;invoke(a,"openChat",user(other,"山海"));check(!abandoned.exists()&&field(a,"readyVoice")==null);checks++
            val count=AtomicInteger();ui{set(a,"resumed",false);Hub.foreground=false;set(a,"permissionAction",{count.incrementAndGet();Unit});set(a,"permissionSession",session);a.onRequestPermissionsResult(51,arrayOf(android.Manifest.permission.RECORD_AUDIO),intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))};check(count.get()==0);ui{invoke(a,"onResume")};check(count.get()==1);checks++
            ui{set(a,"resumed",false);Hub.foreground=false;set(a,"permissionAction",{count.incrementAndGet();Unit});set(a,"permissionSession",session);a.onRequestPermissionsResult(51,arrayOf(android.Manifest.permission.RECORD_AUDIO),intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))};Thread.sleep(1150);ui{invoke(a,"onResume")};check(count.get()==1);checks++
            val sha=MessageDigest.getInstance("SHA-256").digest(File(i.targetContext.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
            File(folder,"result.json").writeText(JSONObject().put("passed",true).put("checks",checks).put("api",Build.VERSION.SDK_INT).put("apkSha256",sha).put("widthDp",cfg.screenWidthDp).put("heightDp",cfg.screenHeightDp).put("phoneLayout",!CompactUi.enabled(a)).put("rootWidthPx",a.window.decorView.width).put("rootHeightPx",a.window.decorView.height).put("realNetworkRequests",0).put("syntheticAudioOnly",true).put("physicalDevices",false).put("nativeSeekAndSpeed",true).put("recordLimitNeverSends",true).put("encryptedVoiceQuoteAndRetry",true).put("playlistRejected",true).toString(2))
            return "PASS native chat polish API${Build.VERSION.SDK_INT}: $checks checks"
        }catch(e:Throwable){capture("failure");File(folder,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally {
            ui{a.finish();Hub.service?.stopSelf();Outbox.stop()};Thread.sleep(300)
            val end=SystemClock.elapsedRealtime()+10000;while((field(Outbox,"running") as AtomicBoolean).get()&&SystemClock.elapsedRealtime()<end)Thread.sleep(40)
            ui{Hub.listener=null;Hub.http.dispatcher.cancelAll();Hub.token="";Hub.me=JSONObject();Hub.http=originalHttp;AppUpdates.transport=originalUpdate}
        }
    }
}
