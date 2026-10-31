package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import com.google.crypto.tink.*
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.SignatureConfig
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.*
import java.io.*
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.*

/** Cross-actor native Tink checks plus actual create/info/chat screens, with fully intercepted HTTP. */
internal object GroupsTests {
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun public(handle:KeysetHandle)=ByteArrayOutputStream().also{handle.publicKeysetHandle.writeNoSecret(JsonKeysetWriter.withOutputStream(it))}.toString("UTF-8")
    private fun keyset(value:String)=KeysetHandle.readNoSecret(JsonKeysetReader.withString(value))
    private fun un64(value:String)=android.util.Base64.decode(value,android.util.Base64.NO_WRAP)
    private fun signed(context:String,key:String,manifest:List<JSONObject>)=(listOf("qingliao:group-envelope:1",context,key)+manifest.map{it.getString("uid")+"|"+it.getString("key_id")+"|"+it.getString("hash")}).joinToString("\n").toByteArray()
    fun run(i:Instrumentation,layout:String):String {
        HybridConfig.register();SignatureConfig.register();StreamingAeadConfig.register()
        val folder=File(i.targetContext.getExternalFilesDir(null),"groups-test").apply{mkdirs()}
        val owner="89000001";val bob="89000002";val carl="89000003";val bot="89000004";val session="synthetic-group-session"
        val unit="g_"+"1".repeat(32);val created="g_"+"2".repeat(32)
        val oldHttp=Hub.http;val oldUpdates=AppUpdates.transport
        val pub=ConcurrentHashMap<String,JSONObject>();val enc=mutableMapOf<String,KeysetHandle>();val sig=mutableMapOf<String,KeysetHandle>()
        val groups=ConcurrentHashMap<String,JSONObject>();val history=ConcurrentHashMap<String,CopyOnWriteArrayList<JSONObject>>()
        val posted=CopyOnWriteArrayList<JSONObject>();val requests=CopyOnWriteArrayList<String>()
        var checks=0;var a:MainActivity?=null;var extra:Activity?=null
        fun user(id:String,name:String)=JSONObject().put("id",id).put("nickname",name).put("username","fixture_$id").put("friend",true).put("state","accepted").put("online",true)
        val people=mapOf(owner to user(owner,"星光旅人"),bob to user(bob,"小桃"),carl to user(carl,"山海"),bot to user(bot,"合成机器人").put("bot",true))
        val contacts=JSONArray().put(people[bob]).put(people[carl]).put(people[bot])
        fun descriptor(gid:String,name:String,ids:List<String>)=JSONObject().put("id",gid).put("group",true).put("nickname",name).put("owner",owner).put("is_owner",true).put("epoch",1).put("member_count",ids.size).put("friend",true).put("state","accepted").put("members",JSONArray(ids.map{people[it]}))
        groups[unit]=descriptor(unit,"合成加密群",listOf(owner,bob,carl))
        for(uid in listOf(bob,carl)){
            val e=KeysetHandle.generateNew(KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"));val s=KeysetHandle.generateNew(KeyTemplates.get("ECDSA_P256"));enc[uid]=e;sig[uid]=s
            val ep=public(e);val sp=public(s);pub[uid]=JSONObject().put("enc",ep).put("sig",sp).put("key_id",hash((ep+"\n"+sp).toByteArray()))
        }
        fun ids(gid:String):List<String>{val values=groups[gid]!!.getJSONArray("members");return(0 until values.length()).map{values.getJSONObject(it).getString("id")}.sorted()}
        fun dto(gid:String,sender:String,wire:JSONObject,recipient:String=owner):JSONObject {
            val envelopes=wire.getJSONObject("envelopes");val own=envelopes.getJSONObject(recipient)
            val manifest=envelopes.keys().asSequence().toList().sorted().map{uid->val e=envelopes.getJSONObject(uid);JSONObject().put("uid",uid).put("key_id",e.getString("recipient_key")).put("hash",hash(un64(e.getString("cipher"))))}
            return JSONObject().put("id",UUID.randomUUID().toString()).put("group_id",gid).put("group_epoch",wire.getInt("epoch")).put("sender",sender).put("recipient",recipient).put("sender_user",people[sender]).put("client_id",wire.getString("client_id")).put("created",System.currentTimeMillis()).put("type","encrypted").put("file_id",wire.optString("file_id")).put("read_count",0).put("readers",JSONArray())
                .put("envelope",JSONObject().put("v",1).put("context",wire.getString("context")).put("sender_key",wire.getString("sender_key")).put("recipient_key",own.getString("recipient_key")).put("cipher",own.getString("cipher")).put("manifest",JSONArray(manifest)).put("signature",wire.getString("signature")))
        }
        fun fromPeer(gid:String,sender:String,inner:JSONObject,fid:String=""):JSONObject {
            val epoch=groups[gid]!!.getInt("epoch");val client=UUID.randomUUID().toString();val context="qingliao:group-message:1:$gid:$epoch:$sender:$client:$fid";val envelopes=JSONObject();val manifest=mutableListOf<JSONObject>()
            for(uid in ids(gid)){val key=pub[uid]!!;val cipher=keyset(key.getString("enc")).getPrimitive(HybridEncrypt::class.java).encrypt(inner.toString().toByteArray(),"$context:$uid".toByteArray());envelopes.put(uid,JSONObject().put("recipient_key",key.getString("key_id")).put("cipher",ChatCrypto.b64(cipher)));manifest.add(JSONObject().put("uid",uid).put("key_id",key.getString("key_id")).put("hash",hash(cipher)))}
            return JSONObject().put("v",1).put("epoch",epoch).put("client_id",client).put("file_id",fid).put("context",context).put("sender_key",pub[sender]!!.getString("key_id")).put("envelopes",envelopes).put("signature",ChatCrypto.b64(sig[sender]!!.getPrimitive(PublicKeySign::class.java).sign(signed(context,pub[sender]!!.getString("key_id"),manifest))))
        }
        fun ui(f:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())f()else{var error:Throwable?=null;i.runOnMainSync{try{f()}catch(e:Throwable){error=e}};error?.let{throw it}}}
        fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        fun set(o:Any,n:String,v:Any?){o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
        fun invoke(o:Any,n:String,vararg args:Any?){ui{o.javaClass.declaredMethods.first{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(o,*args)}}
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun find(activity:Activity,tag:String)=walk(activity.window.decorView).firstOrNull{it.tag==tag}
        fun await(label:String,p:()->Boolean){File(folder,"progress.txt").writeText(label);val until=SystemClock.elapsedRealtime()+25000;while(true){var yes=false;ui{yes=p()};if(yes){i.waitForIdleSync();return};check(SystemClock.elapsedRealtime()<until){"Groups timeout: $label"};Thread.sleep(45)}}
        fun capture(name:String){i.waitForIdleSync();Thread.sleep(300);val image=i.uiAutomation.takeScreenshot();File(folder,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
        try {
            ui{Hub.token=session;Hub.me=people[owner]!!.let{JSONObject(it.toString()).put("admin_password_change_required",true)};Hub.connected=false;ChatCrypto.clear();GroupDirectory.clear()
                Hub.http=OkHttpClient.Builder().addInterceptor{chain->
                    val request=chain.request();check(request.url.host in setOf("47.245.2.254","api.claudcode.xyz"));val path=request.url.encodedPath;requests.add(request.method+" "+path)
                    val b=Buffer();request.body?.writeTo(b);val text=b.readUtf8();val p=if(text.isEmpty())JSONObject()else JSONObject(text);var status=200
                    val value=when {
                        path=="/api/crypto/identity/$owner"->pub[owner]?:JSONObject()
                        path=="/api/crypto/identity"&&request.method=="POST"->{pub[owner]=JSONObject(p.toString());JSONObject().put("ok",true)}
                        path=="/api/contacts"->{check(request.header("X-Mistar-Groups")=="1");JSONObject().put("contacts",JSONArray(contacts.toString()).apply{groups[created]?.let{put(JSONObject(it.toString()))}})}
                        path=="/api/conversations"->{val rows=JSONArray();for(uid in listOf(bob,carl))rows.put(JSONObject().put("user",people[uid]).put("unread",if(uid==bob)5 else 0).put("last",JSONObject().put("type","text").put("text",if(uid==bob)"周末一起看星星吧。"else"晚点把旅行计划发给你。").put("created",System.currentTimeMillis()-10000)));groups[created]?.let{g->val last=history[created]?.lastOrNull()?:JSONObject().put("type","call").put("text","群聊已创建").put("created",System.currentTimeMillis());rows.put(JSONObject().put("user",JSONObject(g.toString())).put("last",last).put("unread",0))};JSONObject().put("conversations",rows)}
                        path=="/api/groups"&&request.method=="POST"->{val selected=p.getJSONArray("members");groups[created]=descriptor(created,p.getString("name"),listOf(owner)+(0 until selected.length()).map{selected.getString(it)});status=201;JSONObject().put("group",JSONObject(groups[created]!!.toString()))}
                        path.startsWith("/api/groups/")->{
                            val parts=request.url.pathSegments;val gid=parts[2];val g=groups[gid]?:throw IllegalStateException("Unexpected fixture group")
                            when{
                                parts.last()=="keys"->JSONObject().put("epoch",g.getInt("epoch")).put("recipients",JSONArray(ids(gid).map{uid->JSONObject(pub[uid]!!.toString()).put("id",uid)}))
                                parts.size>3&&parts[3]=="identities"->pub[parts[4]]!!
                                parts.last()=="messages"&&request.method=="POST"->{posted.add(JSONObject(p.toString()));val message=dto(gid,owner,p);history.getOrPut(gid){CopyOnWriteArrayList()}.add(message);message}
                                parts.last()=="messages"->JSONObject().put("messages",JSONArray(history[gid]?.toList()?:emptyList<JSONObject>())).put("has_more",false)
                                parts.size==3->{if(request.method=="PATCH")g.put("nickname",p.getString("name"));JSONObject().put("group",JSONObject(g.toString()))}
                                else->JSONObject().put("ok",true)
                            }
                        }
                        path.startsWith("/api/messages/")->JSONObject().put("messages",JSONArray()).put("has_more",false)
                        else->JSONObject().put("ok",true)
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic encrypted group fixture").body(value.toString().toResponseBody()).build()
                }.build();AppUpdates.transport=Hub.http}
            ChatCrypto.ready();GroupDirectory.remember(groups[unit]!!)
            val marker="GROUP_NATIVE_PRIVATE_"+UUID.randomUUID();val payload=JSONObject().put("recipient",unit).put("group_epoch",1).put("client_id",UUID.randomUUID().toString()).put("type","text").put("text",marker)
            val wire=ChatCrypto.outgoing(payload);check(wire.getJSONObject("envelopes").length()==3&&!wire.toString().contains(marker));checks++
            for(uid in listOf(bob,carl)){val bytes=enc[uid]!!.getPrimitive(HybridDecrypt::class.java).decrypt(un64(wire.getJSONObject("envelopes").getJSONObject(uid).getString("cipher")),"${wire.getString("context")}:$uid".toByteArray());check(JSONObject(String(bytes)).getString("text")==marker);checks++}
            val own=dto(unit,owner,wire);val opened=ChatCrypto.incoming(own);check(opened.getString("text")==marker&&opened.optBoolean("e2ee")&&!opened.has("envelope"));checks++
            LocalData.put(owner,opened);check(LocalData.messages(owner,unit).length()==1&&LocalData.messages(owner,bob).length()==0);checks++
            for(suffix in listOf("","-wal")){val file=File(i.targetContext.getDatabasePath("chat-local.sqlite").path+suffix);if(file.exists())check(!String(file.readBytes(),Charsets.ISO_8859_1).contains(marker))};checks++
            val incoming=fromPeer(unit,bob,JSONObject().put("type","text").put("text","真实签名与多成员密文验证"));val message=dto(unit,bob,incoming)
            check(ChatCrypto.incoming(message).getString("text")=="真实签名与多成员密文验证");checks++
            val bad=mutableListOf<JSONObject>()
            bad.add(JSONObject(message.toString()).put("group_epoch",2));bad.add(JSONObject(message.toString()).put("recipient",carl));bad.add(JSONObject(message.toString()).apply{getJSONObject("envelope").put("signature",ChatCrypto.b64(ByteArray(80){1}))})
            bad.add(JSONObject(message.toString()).apply{val e=getJSONObject("envelope");val bytes=un64(e.getString("cipher"));bytes[bytes.lastIndex]=(bytes.last().toInt()xor 1).toByte();e.put("cipher",ChatCrypto.b64(bytes))})
            bad.add(JSONObject(message.toString()).apply{val m=getJSONObject("envelope").getJSONArray("manifest");m.put(m.getJSONObject(0))})
            for(tampered in bad){val result=ChatCrypto.incoming(tampered);check(result.optBoolean("decrypt_failed")&&!result.has("file")&&!result.has("quote"));checks++}
            val bytes=ByteArray(4096){(it%127).toByte()};val fileKey=ChatCrypto.fileKey();val attachmentContext="qingliao:attachment:1:"+UUID.randomUUID();val encrypted=ByteArrayOutputStream();ChatCrypto.encryptFile(bytes.inputStream(),encrypted,fileKey,attachmentContext)
            val fid="a".repeat(32);val inner=JSONObject().put("type","file").put("text","").put("file",JSONObject().put("id",fid).put("name","合成文件.bin").put("mime","application/octet-stream").put("size",bytes.size).put("crypto",fileKey).put("context",attachmentContext))
            val decoded=ChatCrypto.incoming(dto(unit,bob,fromPeer(unit,bob,inner,fid)));val recovered=ByteArrayOutputStream();ChatCrypto.decryptFile(encrypted.toByteArray().inputStream(),recovered,decoded.getJSONObject("file").getString("crypto"),decoded.getJSONObject("file").getString("context"));check(recovered.toByteArray().contentEquals(bytes));checks++
            val identityPrefs=i.targetContext.getSharedPreferences("chat_crypto",android.content.Context.MODE_PRIVATE)
            val pinEdit=identityPrefs.edit();repeat(600){pinEdit.putString("peer:$owner:${91000000+it}","a".repeat(64))};check(pinEdit.commit())
            val backup=ChatCrypto.exportIdentity("synthetic migration phrase");check(backup.toByteArray().size<1024*1024);ChatCrypto.importIdentity(backup,"synthetic migration phrase");check(identityPrefs.all.keys.count{it.startsWith("peer:$owner:")}>=600);ChatCrypto.ready();checks++
            check(runCatching{ChatCrypto.importIdentity("x".repeat(1024*1024+1),"synthetic migration phrase")}.isFailure);checks++
            val secondary="g_"+"3".repeat(32);val metadata=descriptor(secondary,"缓存隔离群",listOf(owner,bob));GroupDirectory.remember(metadata);GroupDirectory.removed(secondary,2);GroupDirectory.remember(metadata);check(GroupDirectory.get(secondary)!!.optBoolean("left"));GroupDirectory.remember(JSONObject(metadata.toString()).put("epoch",3));check(!GroupDirectory.get(secondary)!!.optBoolean("left"));checks++
            ui{Hub.token="another-session";Hub.me=user("89000999","另一个合成用户")};GroupDirectory.remember(metadata,owner,session);check(GroupDirectory.get(secondary)==null);ui{Hub.token=session;Hub.me=JSONObject(people[owner]!!.toString()).put("admin_password_change_required",true)};checks++
            GroupDirectory.remember(groups[unit]!!);val pending=JSONObject(payload.toString()).put("client_id",UUID.randomUUID().toString());GroupDirectory.seed(pending);LocalData.queue(owner,pending);GroupDirectory.remember(JSONObject(groups[unit]!!.toString()).put("epoch",2));GroupDirectory.seed(pending);check(pending.getInt("group_epoch")==1);LocalData.groupEpoch(owner,pending.getString("client_id"),2);check(LocalData.outgoing(owner,pending.getString("client_id"))!!.getInt("group_epoch")==2);LocalData.remove(owner,pending.getString("client_id"));checks++
            LocalData.homePart(owner,"contacts",contacts);LocalData.homePart(owner,"conversations",JSONArray())
            a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val main=a!!;ui{Hub.me.remove("admin_password_change_required");Hub.connected=true;set(main,"contacts",contacts);set(main,"conversations",JSONObject().put("conversations",JSONArray()).getJSONArray("conversations"));invoke(main,"showHome",false)}
            invoke(main,"refresh");await("QQ profile header and flat rows"){find(main,"home.profile")?.width?.let{it>0}==true&&find(main,"conversation:$bob")!=null};check(walk(main.window.decorView).filterIsInstance<TextView>().none{it.text.toString().startsWith("最近聊天")});checks++;capture("qq-home")
            val monitor=i.addMonitor(GroupActivity::class.java.name,null,false)
            invoke(main,"homeAddMenu",find(main,"home.add")!!);ui{(field(main,"homeMenu") as PopupMenu).menu.performIdentifierAction(1,0)}
            extra=i.waitForMonitorWithTimeout(monitor,15000)?:error("Create group activity missing");i.removeMonitor(monitor)
            val creator=extra!!;await("friend picker loaded"){find(creator,"group.member.$bob")!=null};check(find(creator,"group.member.$bot")==null&&!find(creator,"group.create")!!.isEnabled);checks++
            ui{(find(creator,"group.name") as EditText).setText("周末看星星");find(creator,"group.member.$bob")!!.performClick()};check(find(creator,"group.create")!!.isEnabled);checks++;capture("create-group")
            ui{find(creator,"group.create")!!.performClick()};await("created group chat"){field(main,"peer")?.let{(it as JSONObject).optString("id")==created}==true&&find(main,"chat.composer")!=null};extra=null
            check(groups[created]!!.getJSONArray("members").length()==2);checks++
            ui{(find(main,"chat.composer") as EditText).setText("周末一起看星星，带上相机。");invoke(main,"sendText")}
            await("group ciphertext posted and decrypted"){posted.isNotEmpty()&&walk(main.window.decorView).filterIsInstance<TextView>().any{it.text.toString()=="周末一起看星星，带上相机。"}}
            check(posted.last().getJSONObject("envelopes").length()==2&&!posted.last().toString().contains("周末一起"));checks++
            val reply=dto(created,bob,fromPeer(created,bob,JSONObject().put("type","text").put("text","好呀，我来准备零食！")));history[created]!!.add(reply)
            invoke(Hub,"handle",JSONObject().put("event","message").put("message",ChatCrypto.incoming(reply)))
            await("group member reply"){walk(main.window.decorView).filterIsInstance<TextView>().any{it.text.toString()=="好呀，我来准备零食！"}};check(walk(main.window.decorView).filterIsInstance<TextView>().any{it.text.toString()=="小桃"});checks++;capture("group-chat")
            check(walk(main.window.decorView).none{it.contentDescription=="视频通话"||it.contentDescription=="语音通话"});invoke(main,"sendMenu");await("group media tools"){find(main,"composer.action.image")!=null};check(find(main,"composer.action.video")==null&&find(main,"composer.action.file")!=null);checks++;invoke(main,"hideKeyboard")
            val infoMonitor=i.addMonitor(GroupActivity::class.java.name,null,false);invoke(main,"people");extra=i.waitForMonitorWithTimeout(infoMonitor,15000)?:error("Group info missing");i.removeMonitor(infoMonitor);val info=extra!!
            await("owner group controls"){find(info,"group.rename")!=null};check(find(info,"group.invite")!=null&&find(info,"group.dissolve")!=null&&find(info,"group.leave")==null);checks++;capture("group-info")
            ui{info.finish()};extra=null;await("main resumed"){field(main,"resumed")==true}
            val sha=hash(File(i.targetContext.applicationInfo.sourceDir).readBytes())
            File(folder,"result.json").writeText(JSONObject().put("passed",true).put("checks",checks).put("api",Build.VERSION.SDK_INT).put("apkSha256",sha).put("realTinkGroupEncryption",true).put("tamperedManifestRejected",true).put("encryptedGroupAttachment",true).put("actualCreateAndReplyUi",true).put("botsExcluded",true).put("realRequests",0).put("physicalDevices",false).toString(2))
            return "PASS native group crypto, attachment, isolation and creation/chat/info UI: $checks checks"
        }catch(e:Throwable){runCatching{capture("failure")};File(folder,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally{ui{extra?.finish();a?.finish();Hub.service?.stopSelf();Outbox.stop();ConversationState.clearMemory();GroupDirectory.clear();Hub.token="";Hub.me=JSONObject();Hub.connected=false;ChatCrypto.clear();Hub.http=oldHttp;AppUpdates.transport=oldUpdates}}
    }
}
