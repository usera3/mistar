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
object ChatFlowTests {
    fun run(i:Instrumentation,layout:String):String {
        com.google.crypto.tink.hybrid.HybridConfig.register();com.google.crypto.tink.signature.SignatureConfig.register();com.google.crypto.tink.streamingaead.StreamingAeadConfig.register()
        val folder=File(i.targetContext.getExternalFilesDir(null),"chat-flow-test").apply{mkdirs()}
        val owner="84000001";val peer="84000002";val other="84000003";val session="synthetic-chat-flow-session"
        val identities=ConcurrentHashMap<String,JSONObject>();val files=ConcurrentHashMap<String,ByteArray>();val uploaded=ConcurrentHashMap<String,ByteArray>();val delivered=CopyOnWriteArrayList<JSONObject>()
        val uploadGate=AtomicReference<CountDownLatch?>(null);val uploadStarted=AtomicInteger()
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
        fun ui(f:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())f()else{var error:Throwable?=null;i.runOnMainSync{try{f()}catch(e:Throwable){error=e}};error?.let{throw it}}}
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
                    path=="/api/uploads"->{uploadStarted.incrementAndGet();val gate=uploadGate.get();while(gate!=null&&gate.count>0&&!chain.call().isCanceled()){gate.await(50,TimeUnit.MILLISECONDS)};if(chain.call().isCanceled())throw IOException("Cancelled synthetic transfer");if(failUpload.getAndSet(false)){status=503;JSONObject().put("error","Synthetic retry")}else{val id=UUID.randomUUID().toString().replace("-","");uploaded[id]=raw;JSONObject().put("id",id)}}
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
        val temp=File(i.targetContext.cacheDir,"ux-flow-source.txt");val clear="UX_PRIVATE_ATTACHMENT_CONTENT_".repeat(200).toByteArray();temp.writeBytes(clear)
        fun readCount()=requests.count{it=="/api/read/$peer"}
        fun latest()=field(a,"latestMessage") as TextView
        fun scroll()=field(a,"messageScroll") as ScrollView
        fun upload(file:File){invoke(a,"uploadFile",android.net.Uri.fromFile(file),file.name,"text/plain","file",peer,"",null,null,null)}
        try {
            check(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk",true)||Build.HARDWARE in setOf("ranchu","goldfish"))
            ui{Hub.me.remove("admin_password_change_required")}
            val history=(0 until 45).map{n->message("text","合成聊天记录 $n：阅读旧消息时，新消息不应把画面拉走。").put("created",System.currentTimeMillis()-200000+n*1000)}
            show(history);Thread.sleep(320);check(readCount()==0);checks++;ui{Hub.connected=true};invoke(a,"updateLatestButton");await("initial tail read receipt"){readCount()>0};checks++
            ui{scroll().scrollTo(0,0)};await("manual history shortcut"){latest().visibility==View.VISIBLE};check(latest().text.contains("回到最新"));checks++
            val initialY=scroll().scrollY;val before=readCount();val first=message("text","合成新消息一");val second=message("text","合成新消息二")
            invoke(a,"onEvent",JSONObject().put("event","message").put("message",first));invoke(a,"onEvent",JSONObject().put("event","message").put("message",second));invoke(a,"onEvent",JSONObject().put("event","message").put("message",second));Thread.sleep(450)
            check(scroll().scrollY==initialY&&latest().text.contains("2条新消息")&&readCount()==before);checks++
            capture("new-message-count")
            ui{latest().performClick()};await("latest visible and read acknowledged"){latest().visibility==View.GONE&&readCount()>before};checks++
            // A cached search window never acknowledges newly arriving messages just for being open.
            ui{set(a,"searchWindow",true)};val searchReads=readCount();invoke(a,"onEvent",JSONObject().put("event","message").put("message",message("text","合成搜索窗口外的新消息")));Thread.sleep(400)
            check(latest().text.contains("1条新消息")&&readCount()==searchReads);checks++
            ui{set(a,"searchWindow",false)};show(history);ui{scroll().scrollTo(0,0)}
            // Durable failed message: one click retries using real Tink and original client id.
            val payload=JSONObject().put("recipient",peer).put("client_id",UUID.randomUUID().toString()).put("type","text").put("text","合成重试消息")
            val stored=LocalData.queue(owner,payload);LocalData.state(owner,payload.getString("client_id"),"blocked",5,0,"synthetic failure")
            val pending=Outbox.pending(owner,LocalData.outgoing(owner,payload.getString("client_id"))!!);invoke(a,"appendMessage",pending)
            await("direct retry target visible"){find("chat.retry.${payload.getString("client_id")}")!=null};val retry=find("chat.retry.${payload.getString("client_id")}")!!
            val sentBefore=delivered.size;ui{retry.performClick();retry.performClick()};await("retry delivered once"){delivered.any{it.optString("client_id")==payload.getString("client_id")}};Thread.sleep(250)
            check(delivered.count{it.optString("client_id")==payload.getString("client_id")}==1&&delivered.size==sentBefore+1);check(field(a,"messageMenu")==null);checks++
            ui{retry.performClick()};Thread.sleep(120);check(delivered.size==sentBefore+1);checks++
            show(history.takeLast(4));val editor=editor();val gate=CountDownLatch(1);uploadGate.set(gate);val upBefore=uploadStarted.get();upload(temp)
            await("inline upload waiting for response"){uploadStarted.get()>upBefore&&find("chat.uploads")?.visibility==View.VISIBLE};checks++
            @Suppress("UNCHECKED_CAST") val tasks=field(a,"activeUploads") as Set<AttachmentUpload>;val task=tasks.first()
            check(!(field(task,"dialog\$delegate") as Lazy<*>).isInitialized());ui{editor.setText("上传时仍然可以聊天")};check(editor()===editor);capture("inline-upload");checks++
            invoke(a,"sendText");await("text sent during attachment upload"){delivered.any{it.optString("text")=="上传时仍然可以聊天"}};check(find("chat.uploads")?.visibility==View.VISIBLE);checks++
            invoke(a,"openChat",user(other,"山海"));await("other chat editor"){(field(a,"peer") as JSONObject).optString("id")==other&&(field(a,"uploadTray") as View).visibility==View.GONE};checks++
            gate.countDown();await("attachment completes to original recipient"){delivered.any{it.optString("type")=="file"}};val sent=delivered.last{it.optString("type")=="file"};check(sent.getString("recipient")==peer);checks++
            val info=sent.getJSONObject("file");val sealed=uploaded[info.getString("id")]!!;check(!String(sealed,Charsets.ISO_8859_1).contains("UX_PRIVATE_ATTACHMENT_CONTENT"));val output=ByteArrayOutputStream();ChatCrypto.decryptFile(sealed.inputStream(),output,info.getString("crypto"),info.getString("context"));check(output.toByteArray().contentEquals(clear));checks++
            invoke(a,"openChat",user(peer,"小桃"));val cancelGate=CountDownLatch(1);uploadGate.set(cancelGate);val count=uploadStarted.get();val deliveredFiles=delivered.count{it.optString("type")=="file"};upload(temp)
            await("cancelable inline transfer"){uploadStarted.get()>count&&walk(a.window.decorView).any{it.tag?.toString()?.startsWith("chat.upload.cancel.")==true}}
            val cancel=walk(a.window.decorView).first{it.tag?.toString()?.startsWith("chat.upload.cancel.")==true};ui{cancel.performClick()};await("cancel removes local transfer row"){find("chat.uploads")?.visibility==View.GONE};cancelGate.countDown();await("cancel cleanup finishes"){(field(a,"activeUploads") as Set<*>).isEmpty()}
            check(temp.readBytes().contentEquals(clear)&&delivered.count{it.optString("type")=="file"}==deliveredFiles);checks++
            // Old controls cannot operate after the account/session changes.
            ui{Hub.token="different-account-session";Hub.me=user("84000999","另一个合成账号");cancel.performClick();retry.performClick()};Thread.sleep(150);check(delivered.count{it.optString("type")=="file"}==deliveredFiles);checks++
            val digest=MessageDigest.getInstance("SHA-256").digest(File(i.targetContext.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
            File(folder,"result.json").writeText(JSONObject().put("passed",true).put("checks",checks).put("api",Build.VERSION.SDK_INT).put("apkSha256",digest).put("countedIncomingMessages",true).put("historyNotInterrupted",true).put("readDeferredUntilLatest",true).put("directRetryDeduplicated",true).put("nonmodalUpload",true).put("typingDuringUpload",true).put("originalRecipient",true).put("realEncryptedAttachment",true).put("cancelPreservesOriginalFile",true).put("staleControlsRejected",true).put("realRequests",0).put("physicalDevices",false).toString(2))
            return "PASS chat flow UX and real encrypted/cancel-safe transfers: $checks checks"
        }catch(e:Throwable){runCatching{capture("failure")};File(folder,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally{uploadGate.get()?.countDown();ui{a.finish();Hub.service?.stopSelf();Outbox.stop();Hub.token="";Hub.me=JSONObject()};val end=SystemClock.elapsedRealtime()+8000
            while(SystemClock.elapsedRealtime()<end&&(field(a,"activeUploads") as Set<*>).isNotEmpty())Thread.sleep(60)
            check((field(a,"activeUploads") as Set<*>).isEmpty()){"Synthetic transfers did not quiesce; retain mocked transport"}
            ui{Hub.http=originalHttp;AppUpdates.transport=originalUpdate};temp.delete()}
    }
}
