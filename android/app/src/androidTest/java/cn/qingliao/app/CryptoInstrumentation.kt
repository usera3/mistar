package cn.qingliao.app

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.content.Context
import com.google.crypto.tink.*
import org.json.JSONObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID

/** Runs only in the separately installed test APK. No credentials or test endpoints in the app. */
class CryptoInstrumentation:Instrumentation() {
    private var peer=""
    private var mode=""
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);peer=arguments?.getString("peer")?:"";mode=arguments?.getString("mode")?:"";start() }
    override fun onStart() {
        try {
            waitForIdleSync()
            if(mode=="location"){val result=LocationTests.run(this,peer);finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="groups"){val result=GroupsTests.run(this,peer);finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="codeLogin"){val result=CodeLoginTests.run(this,peer);finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="visual"){val result=VisualTests.run(this,peer);finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="remoteAssist"){val result=RemoteAssistTests.run(this,peer);finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="callUi"){val result=CallUiTests.run(this,peer);finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="chatPolish"){val result=ChatPolishTests.run(this,peer);finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="conversations"){val result=ConversationTests.run(this,peer);finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="authDeveloper"){val result=AuthDeveloperTests.run(this);finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="membership"){val result=MembershipTests.run(this);finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="brandSeed"||mode=="brandVerify"){val result=BrandingTests.run(this,mode=="brandSeed");finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="messageInteractions"){val result=MessageInteractionTests.run(this);finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="updates"||mode=="updatesInstall"){val result=UpdateTests.run(this,mode=="updatesInstall");finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="screenShare"){val result=ScreenShareTests.run(this);finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="voiceText"||mode=="voiceTextLive"){val result=VoiceTextTests.run(this,mode=="voiceTextLive");finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="adminUi"){val result=AdminTests.run(this,peer.ifEmpty{"phone"});finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="dictationWire"||mode=="dictationLive"){val result=DictationTests.run(this,mode=="dictationLive");finish(Activity.RESULT_OK,Bundle().apply{putString("result",result)});return}
            if(mode=="payload") {
                var cases=0
                val fid="a".repeat(32)
                val file=JSONObject().put("id",fid).put("name","中文 photo.png").put("mime","image/png").put("size",1024)
                    .put("crypto",ChatCrypto.fileKey()).put("context","qingliao:attachment:1:"+UUID.randomUUID())
                val good=JSONObject().put("type","image").put("text","").put("file",file)
                PayloadRules.validate(good,fid);cases++
                val bad=mutableListOf<Pair<JSONObject,String>>()
                for(name in listOf("../private","a\\b","a\u0000b","a\u202eb", "x".repeat(181)))bad.add(JSONObject(good.toString()).apply{getJSONObject("file").put("name",name)} to fid)
                for(size in listOf(-1L,0L,25L*1024*1024+1,1.5))bad.add(JSONObject(good.toString()).apply{getJSONObject("file").put("size",size)} to fid)
                for(mime in listOf("text/html\r\nX: value","invalid","x".repeat(101)))bad.add(JSONObject(good.toString()).apply{getJSONObject("file").put("mime",mime)} to fid)
                bad.add(JSONObject(good.toString()).apply{getJSONObject("file").put("id","../../outside")} to fid)
                bad.add(JSONObject(good.toString()).apply{getJSONObject("file").put("context","wrong-context")} to fid)
                bad.add(JSONObject().put("type","text").put("text",JSONObject()) to "")
                bad.add(JSONObject().put("type","text").put("text","x".repeat(5001)) to "")
                bad.add(JSONObject().put("type","text").put("text","attached").put("file",file) to fid)
                for((value,id) in bad){var rejected=false;try{PayloadRules.validate(value,id)}catch(_:Exception){rejected=true};check(rejected);cases++}
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS round 24: $cases authenticated-payload bounds, path, control-character, type and context checks")});return
            }
            if(mode=="cache") {
                val before=ChatCrypto.ready().getString("key_id");val session=Hub.token
                val dir=java.io.File(Hub.app.cacheDir,"shared").apply{mkdirs()}
                java.io.File(dir,"owned-cache-probe").writeBytes(ByteArray(4096))
                check(Hub.localCacheBytes()>=4096)
                runOnMainSync{Hub.clearLocalCache()}
                check(Hub.localCacheBytes()==0L&&Hub.token==session&&ChatCrypto.ready().getString("key_id")==before)
                var rejected=false;try{Hub.cachedFile(JSONObject().put("id","../outside").put("name","x").put("size",0))}catch(_:Exception){rejected=true};check(rejected)
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS round 15: cache cleared; session/Keystore identity retained; cache path traversal rejected")});return
            }
            if(mode=="pending") {
                val before=ChatCrypto.ready().getString("key_id");val session=Hub.token
                var rejected=false;try{ChatCrypto.restartIdentity(JSONObject().put("password","invalid-test-password").put("code","invalid"))}catch(_:Exception){rejected=true};check(rejected)
                val prefs=Hub.app.getSharedPreferences("chat_crypto",Context.MODE_PRIVATE)
                val pending=prefs.getString("pending:"+Hub.me.getString("id"),null)
                check(pending!=null&&!pending.contains("PrivateKey"))
                ChatCrypto.clear();check(ChatCrypto.ready().getString("key_id")==before&&Hub.token==session)
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS round 16: private candidate saved before rejected request; active identity retained; encrypted pending state survives cache reset")});return
            }
            if(mode=="notifications") {
                val manager=Hub.app.getSystemService(android.app.NotificationManager::class.java)
                runOnMainSync{Hub.notify("Sensitive sender","Sensitive plaintext must never be displayed",peer,false)}
                android.os.SystemClock.sleep(300)
                val notice=manager.activeNotifications.first{it.id==peer.hashCode()}.notification
                check(notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()=="你收到一条新消息")
                check(!notice.extras.toString().contains("Sensitive"))
                check(notice.publicVersion!=null&&notice.visibility==android.app.Notification.VISIBILITY_PRIVATE)
                runOnMainSync{Hub.logout()};android.os.SystemClock.sleep(300)
                check(manager.activeNotifications.isEmpty()&&Hub.token.isEmpty())
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS round 23: generic/private notification with redacted lock screen; logout removes notifications and authenticated session")});return
            }
            if(mode=="historySnapshot") {
                ChatCrypto.ready();val raw=Hub.rawApi("GET","/api/messages/$peer")
                check(ChatCrypto.transform(JSONObject(raw.toString())).getJSONArray("messages").let{a->(0 until a.length()).any{a.getJSONObject(it).optBoolean("e2ee")}})
                java.io.File(targetContext.getExternalFilesDir(null),"qa-old-history.json").writeText(raw.toString())
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS owned encrypted history captured for identity restart regression")});return
            }
            if(mode=="callActions") {
                check(Hub.call==null)
                val oldPhase=Hub.phase;var pending:android.app.PendingIntent?=null
                try {
                    runOnMainSync{
                        Hub.call=JSONObject().put("id",UUID.randomUUID().toString());Hub.phase="incoming";Hub.ringUntil=android.os.SystemClock.elapsedRealtime()+45000
                        pending=CallActions.pending("reject")
                        check(!CallActions.valid(android.content.Intent().putExtra("owner",Hub.me.optString("id")).putExtra("call_id",Hub.call!!.getString("id"))))
                        Hub.call=JSONObject().put("id",UUID.randomUUID().toString())
                    }
                    val newer=Hub.call!!.getString("id");pending!!.send();android.os.SystemClock.sleep(400);waitForIdleSync();check(Hub.call?.optString("id")==newer)
                    runOnMainSync{pending=CallActions.pending("reject");Hub.ringUntil=android.os.SystemClock.elapsedRealtime()-1}
                    pending!!.send();android.os.SystemClock.sleep(400);waitForIdleSync();check(Hub.call?.optString("id")==newer)
                }finally{runOnMainSync{CallActions.clear();Hub.call=null;Hub.phase=oldPhase;Hub.ringUntil=0}}
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS round 33 safety: real PendingIntent broadcast ignores previous-call and expired actions; forged intent without nonce rejected")});return
            }
            if(mode=="localSeed") {
                val owner=Hub.me.getString("id");require(Hub.token.isNotEmpty()&&peer.length==8)
                val marker="LOCAL_VAULT_PRIVATE_"+UUID.randomUUID()
                val request=JSONObject().put("recipient",peer).put("type","text").put("text",marker).put("client_id",UUID.randomUUID().toString())
                val queued=Outbox.queue(request);check(queued.optBoolean("pending"))
                val expired=JSONObject(request.toString()).put("client_id",UUID.randomUUID().toString()).put("text",marker+"_old")
                LocalData.queue(owner,expired)
                val database=Hub.app.openOrCreateDatabase("chat-local.sqlite",Context.MODE_PRIVATE,null)
                database.execSQL("UPDATE outbox SET created=? WHERE owner=? AND client=?",arrayOf(System.currentTimeMillis()-8*86400000L,owner,expired.getString("client_id")))
                val broken=JSONObject(request.toString()).put("client_id",UUID.randomUUID().toString());LocalData.queue(owner,broken)
                database.execSQL("UPDATE outbox SET body=randomblob(45) WHERE owner=? AND client=?",arrayOf(owner,broken.getString("client_id")))
                check(LocalData.outgoing(owner,broken.getString("client_id"))!!.optBoolean("local_unreadable"));var rejected=false
                try{Outbox.retry(broken.getString("client_id"))}catch(_:Exception){rejected=true};check(rejected);LocalData.remove(owner,broken.getString("client_id"))
                val fakePeer="00000001";val first=UUID.randomUUID().toString();val second=UUID.randomUUID().toString()
                database.execSQL("DELETE FROM clears WHERE owner=? AND peer=?",arrayOf(owner,fakePeer));database.execSQL("DELETE FROM cache WHERE owner=? AND peer=?",arrayOf(owner,fakePeer))
                val cached=JSONObject().put("id",first).put("sender",owner).put("recipient",fakePeer).put("type","text").put("text",marker).put("created",System.currentTimeMillis())
                LocalData.put(owner,cached);LocalData.put(owner,JSONObject(cached.toString()).put("id",second))
                database.execSQL("UPDATE cache SET body=(SELECT body FROM cache WHERE owner=? AND mid=?) WHERE owner=? AND mid=?",arrayOf(owner,first,owner,second))
                check(LocalData.messages(owner,fakePeer).length()==1)
                rejected=false;try{LocalData.messages("00000000",fakePeer)}catch(_:Exception){rejected=true};check(rejected)
                LocalData.clear(owner,fakePeer,Long.MAX_VALUE)
                for(suffix in listOf("","-wal")){val file=java.io.File(Hub.app.getDatabasePath("chat-local.sqlite").path+suffix);if(file.exists())check(!String(file.readBytes(),Charsets.ISO_8859_1).contains(marker))}
                database.close()
                java.io.File(targetContext.getExternalFilesDir(null),"qa-local-probe.json").writeText(JSONObject().put("client",request.getString("client_id")).put("expired",expired.getString("client_id")).put("marker",marker).put("peer",peer).toString())
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS rounds 38/40 security: persistent encrypted queue/cache, cross-account denial, AAD record-swap rejection, corrupt outbox quarantine and no plaintext in SQLite/WAL")});return
            }
            if(mode=="localRestore"||mode=="localDelivered") {
                val fixture=JSONObject(java.io.File(targetContext.getExternalFilesDir(null),"qa-local-probe.json").readText());val owner=Hub.me.getString("id")
                if(mode=="localRestore")check(LocalData.outgoing(owner,fixture.getString("client"))?.getString("text")==fixture.getString("marker"))
                else {check(LocalData.outgoing(owner,fixture.getString("client"))==null);check(LocalData.outgoing(owner,fixture.getString("expired"))?.getString("local_state")=="blocked");LocalData.remove(owner,fixture.getString("expired"))}
                finish(Activity.RESULT_OK,Bundle().apply{putString("result",if(mode=="localRestore")"PASS round 38: a new Android process restores the same queued content and client identifier while offline" else "PASS round 39: delivered row removed and eight-day stale queue stays blocked pending explicit retry")});return
            }
            if(mode=="workScope") {
                val originalToken=Hub.token;val originalMe=Hub.me;val started=java.util.concurrent.CountDownLatch(4);val release=java.util.concurrent.CountDownLatch(1);val mutated=java.util.concurrent.atomic.AtomicBoolean(false)
                repeat(4){Hub.io.execute{started.countDown();release.await()}}
                check(started.await(5,java.util.concurrent.TimeUnit.SECONDS))
                Hub.job({mutated.set(true);JSONObject()},{mutated.set(true)},{mutated.set(true)})
                runOnMainSync{Hub.token="owned-stale-scope-probe";Hub.me=JSONObject().put("id","00000000")};release.countDown();android.os.SystemClock.sleep(400)
                check(!mutated.get());runOnMainSync{Hub.token=originalToken;Hub.me=originalMe}
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS queued account-scoped work and callbacks do not run after the active session changes")});return
            }
            if(mode=="quoteFixtures"||mode=="longLayout") {
                require(peer.length==8&&Hub.token.isNotEmpty())
                if(mode=="quoteFixtures") {
                    val sent=Hub.api("POST","/api/messages",JSONObject().put("recipient",peer).put("type","text").put("text","Quote_original_round42").put("client_id",UUID.randomUUID().toString()))
                    check(sent.optBoolean("e2ee"))
                    java.io.File(targetContext.getExternalFilesDir(null),"qa-quote-original.json").writeText(sent.toString())
                } else {
                    val value="Long_layout_round43\n"+"无空格长句".repeat(160)+"\n"+"W".repeat(900)+"\n放大字体后仍应保留左右头像、完整文字与清晰回执。"
                    check(Hub.api("POST","/api/messages",JSONObject().put("recipient",peer).put("type","text").put("text",value).put("client_id",UUID.randomUUID().toString())).optBoolean("e2ee"))
                }
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS owned encrypted chat layout fixture sent")});return
            }
            if(mode=="mediaFixtures") {
                val resolver=Hub.app.contentResolver;val stamp=System.currentTimeMillis();val records=JSONObject()
                fun create(name:String,mime:String,write:(java.io.OutputStream)->Unit) {
                    val uri=resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply{put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name);put(android.provider.MediaStore.MediaColumns.MIME_TYPE,mime);put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/");put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)})!!
                    resolver.openOutputStream(uri)!!.use(write);resolver.update(uri,android.content.ContentValues().apply{put(android.provider.MediaStore.MediaColumns.IS_PENDING,0)},null,null)
                    records.put(if(mime=="image/png")"image" else "file",JSONObject().put("uri",uri.toString()).put("name",name))
                }
                create("iteration50-cancel-$stamp.bin","application/octet-stream"){output->val bytes=ByteArray(1024*1024);repeat(25){output.write(bytes)}}
                create("iteration50-image-$stamp.png","image/png"){output->val bitmap=android.graphics.Bitmap.createBitmap(640,480,android.graphics.Bitmap.Config.ARGB_8888);val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.rgb(38,91,159));canvas.drawCircle(420f,170f,110f,android.graphics.Paint().apply{color=android.graphics.Color.rgb(247,192,86)});canvas.drawRect(0f,340f,640f,480f,android.graphics.Paint().apply{color=android.graphics.Color.rgb(62,143,124)});bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);bitmap.recycle()}
                java.io.File(targetContext.getExternalFilesDir(null),"qa-media-fixtures.json").writeText(records.toString())
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS owned MediaStore test files created with scoped storage")});return
            }
            if(mode=="smallFile") {
                val resolver=Hub.app.contentResolver;val name="iteration50-file-"+System.currentTimeMillis()+".txt"
                val uri=resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply{put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name);put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"text/plain");put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/");put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)})!!
                resolver.openOutputStream(uri)!!.use{it.write("Owned private file transfer round 49".toByteArray())};resolver.update(uri,android.content.ContentValues().apply{put(android.provider.MediaStore.MediaColumns.IS_PENDING,0)},null,null)
                val record=java.io.File(targetContext.getExternalFilesDir(null),"qa-media-fixtures.json");val data=JSONObject(record.readText());data.put("small",JSONObject().put("uri",uri.toString()).put("name",name));record.writeText(data.toString())
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS small owned file fixture created")});return
            }
            if(mode=="cleanupFixtures") {
                val file=java.io.File(targetContext.getExternalFilesDir(null),"qa-media-fixtures.json")
                if(file.exists()) {
                    val data=JSONObject(file.readText())
                    data.keys().forEach{key->val record=data.getJSONObject(key);check(record.getString("name").startsWith("iteration50-"));Hub.app.contentResolver.delete(android.net.Uri.parse(record.getString("uri")),null,null)}
                }
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS only owned iteration50 MediaStore fixtures removed")});return
            }
            if(mode=="previewA"||mode=="previewB") {
                if(mode=="previewA") {
                    Hub.api("POST","/api/messages",JSONObject().put("recipient",peer).put("type","text").put("text","傍晚路过海边，想把这一刻分享给你。").put("client_id",UUID.randomUUID().toString()))
                    val fixture=JSONObject(java.io.File(targetContext.getExternalFilesDir(null),"qa-media-fixtures.json").readText())
                    val bytes=Hub.app.contentResolver.openInputStream(android.net.Uri.parse(fixture.getJSONObject("image").getString("uri")))!!.use{it.readBytes()}
                    val key=ChatCrypto.fileKey();val context="qingliao:attachment:1:"+UUID.randomUUID();val encrypted=ByteArrayOutputStream();ChatCrypto.encryptFile(ByteArrayInputStream(bytes),encrypted,key,context)
                    val request=Request.Builder().url(Hub.SERVER+"/api/uploads?recipient=$peer&name=encrypted.bin").header("Authorization","Bearer "+Hub.token).post(encrypted.toByteArray().toRequestBody("application/x-qingliao-encrypted".toMediaType())).build()
                    val f=Hub.http.newCall(request).execute().use{r->check(r.isSuccessful);JSONObject(r.body!!.string())}
                    val meta=JSONObject().put("id",f.getString("id")).put("name","海边.png").put("mime","image/png").put("size",bytes.size).put("crypto",key).put("context",context)
                    Hub.api("POST","/api/messages",JSONObject().put("recipient",peer).put("type","image").put("text","").put("file_id",f.getString("id")).put("private_file",meta).put("client_id",UUID.randomUUID().toString()))
                }else {
                    val list=Hub.api("GET","/api/messages/$peer").getJSONArray("messages");val original=(0 until list.length()).map{list.getJSONObject(it)}.first{it.optString("type")=="text"}
                    val quote=JSONObject().put("id",original.getString("id")).put("sender",peer).put("type","text").put("excerpt",original.getString("text"))
                    Hub.api("POST","/api/messages",JSONObject().put("recipient",peer).put("type","text").put("text","这个颜色好温柔，周末一起去走走吧 ☀").put("quote",quote).put("client_id",UUID.randomUUID().toString()))
                }
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS owned preview conversation prepared; no demo data is bundled with app")});return
            }
            if(mode=="logoutPrivacy") {
                val activity=startActivitySync(android.content.Intent(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val monitor=addMonitor(AvatarCropActivity::class.java.name,null,false)
                try {
                    runOnMainSync{
                        MainActivity::class.java.getDeclaredMethod("showHtml",String::class.java).apply{isAccessible=true}.invoke(activity,"<b>Owned private modal content</b>")
                        MainActivity::class.java.getDeclaredField("avatarPickerSession").apply{isAccessible=true}.set(activity,Hub.token)
                        Hub.logout()
                        val dialogs=MainActivity::class.java.getDeclaredField("dialogs").apply{isAccessible=true}.get(activity) as Set<*>
                        check(dialogs.none{(it as android.app.Dialog).isShowing})
                        MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,android.content.Intent::class.java).apply{isAccessible=true}.invoke(activity,63,Activity.RESULT_OK,android.content.Intent().setData(android.net.Uri.parse("content://owned-expired-picker/probe")))
                    }
                    android.os.SystemClock.sleep(400);check(monitor.hits==0&&Hub.token.isEmpty())
                }finally{removeMonitor(monitor);runOnMainSync{activity.finish()}}
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS logout closes private modal windows and stale file-picker result cannot open an avatar or send under another session")});return
            }
            if(mode=="mediaRepeat") {
                val history=Hub.api("GET","/api/messages/$peer").getJSONArray("messages")
                val source=(0 until history.length()).map{history.getJSONObject(it)}.last{it.optString("type")=="image"&&it.optString("sender")==Hub.me.optString("id")}
                repeat(5){Hub.api("POST","/api/messages",JSONObject().put("recipient",peer).put("type","image").put("text","").put("file_id",source.getString("file_id")).put("private_file",source.getJSONObject("file")).put("client_id",UUID.randomUUID().toString()));android.os.SystemClock.sleep(650)}
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS owned encrypted image repeat fixture")});return
            }
            if(mode=="imageLifecycle") {
                val user=Hub.api("GET","/api/contacts").getJSONArray("contacts").let{array->(0 until array.length()).map{array.getJSONObject(it)}.first{it.optString("id")==peer}}
                val activity=startActivitySync(android.content.Intent(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
                fun images():List<*> {var result:List<*> = emptyList<Any>();runOnMainSync{result=(field("chatImages") as List<*>).toList()};return result}
                fun bitmapCount():Int {var result=0;runOnMainSync{result=(field("chatImages") as List<*>).count{it!!.javaClass.getDeclaredField("bitmap").apply{isAccessible=true}.get(it)!=null}};return result}
                try {
                    runOnMainSync{MainActivity::class.java.getDeclaredMethod("openChat",JSONObject::class.java).apply{isAccessible=true}.invoke(activity,user)}
                    val end=android.os.SystemClock.elapsedRealtime()+15000
                    while(bitmapCount()==0&&android.os.SystemClock.elapsedRealtime()<end)android.os.SystemClock.sleep(200)
                    check(images().size>=5&&bitmapCount() in 1..4)
                    runOnMainSync{(field("messageScroll") as android.widget.ScrollView).scrollTo(0,0)};android.os.SystemClock.sleep(500)
                    check(bitmapCount()==0)
                    runOnMainSync{activity.onBackPressed()};waitForIdleSync();check(images().isEmpty())
                    check(!Motion.enabled())
                    val newer=startActivitySync(android.content.Intent(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                    val listener=Hub.listener;runOnMainSync{activity.finish()};waitForIdleSync();check(Hub.listener===listener)
                    runOnMainSync{newer.finish()}
                }finally{runOnMainSync{activity.finish()}}
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS rounds 45/47: only visible images retain decoded bitmaps, scrolling/navigation release previews, disabled animation respected and old Activity cannot clear current listener")});return
            }
            if(mode=="noAutoMedia") {
                require(Hub.call==null&&Hub.rtc==null)
                val handle=Hub::class.java.getDeclaredMethod("handle",JSONObject::class.java).apply{isAccessible=true}
                val incoming=JSONObject().put("id",UUID.randomUUID().toString()).put("caller",peer).put("callee",Hub.me.getString("id")).put("video",true).put("created",System.currentTimeMillis()).put("accepted",false)
                runOnMainSync{
                    Hub.foreground=true
                    handle.invoke(Hub,JSONObject().put("event","call.ringing").put("call",incoming).put("user",JSONObject().put("id",peer).put("nickname","Owned QA")))
                    handle.invoke(Hub,JSONObject().put("event","call.accepted").put("call",JSONObject(incoming.toString()).put("accepted",true)))
                    check(Hub.rtc==null&&Hub.call==null)
                    val outgoing=JSONObject(incoming.toString()).put("id",UUID.randomUUID().toString()).put("caller",Hub.me.getString("id")).put("callee",peer).put("invite_id","not-generated-on-this-device")
                    handle.invoke(Hub,JSONObject().put("event","call.waiting").put("call",outgoing).put("user",JSONObject().put("id",peer)))
                    check(Hub.rtc==null&&Hub.call==null);Hub.foreground=false
                }
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS round 48: another device acceptance or unmatched invitation cannot create this device microphone/camera engine")});return
            }
            if(mode=="quoteVerify") {
                val history=Hub.api("GET","/api/messages/$peer").getJSONArray("messages")
                val quoted=(0 until history.length()).map{history.getJSONObject(it)}.first{it.optString("text")=="Reply_round42"}
                check(quoted.getJSONObject("quote").getString("excerpt")=="Quote_original_round42"&&quoted.optBoolean("e2ee"))
                val raw=Hub.rawApi("GET","/api/messages/$peer").toString();check(!raw.contains("Quote_original_round42")&&!raw.contains("Reply_round42"))
                val request=JSONObject().put("recipient",peer).put("type","text").put("text","outer-field-spoof-probe").put("client_id",UUID.randomUUID().toString())
                val sent=Hub.rawApi("POST","/api/messages",ChatCrypto.outgoing(request))
                val fake=JSONObject(sent.toString()).put("quote",JSONObject().put("excerpt","forged outer quote")).put("pending",true)
                val decoded=ChatCrypto.incoming(fake);check(!decoded.has("quote")&&!decoded.has("pending")&&decoded.getString("text")=="outer-field-spoof-probe")
                for(q in listOf(JSONObject().put("id","invalid").put("sender",peer).put("type","text").put("excerpt","x"),JSONObject().put("id",UUID.randomUUID().toString()).put("sender","00000000").put("type","text").put("excerpt","x"))) {
                    var rejected=false;try{PayloadRules.validate(JSONObject().put("type","text").put("text","x").put("quote",q),"",setOf(Hub.me.getString("id"),peer))}catch(_:Exception){rejected=true};check(rejected)
                }
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS round 42: quoted context decrypts correctly; server sees only ciphertext; untrusted outer quote/pending fields and invalid reference participants rejected")});return
            }
            if(mode=="audioResources") {
                val activity=startActivitySync(android.content.Intent(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                val manager=Hub.app.getSystemService(android.media.AudioManager::class.java);val originalMode=manager.mode
                val audio=CallAudio(Hub.app){}
                var other:android.media.AudioFocusRequest?=null
                try {
                    runOnMainSync{audio.start(false)};check(audio.wakeHeld&&audio.ready)
                    other=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                        .setOnAudioFocusChangeListener({},Hub.main).build()
                    runOnMainSync{check(manager.requestAudioFocus(other!!)==android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)}
                    android.os.SystemClock.sleep(400);check(!audio.ready)
                    runOnMainSync{manager.abandonAudioFocusRequest(other!!)};android.os.SystemClock.sleep(400);check(audio.ready)
                    runOnMainSync{audio.release()};check(!audio.wakeHeld&&manager.mode==originalMode)
                    val speaker=android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;val ear=android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;val wired=android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET;val bluetooth=android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    check(AudioRoutes.choose(setOf(speaker,ear),false)==ear)
                    check(AudioRoutes.choose(setOf(speaker,ear,wired),false)==wired)
                    check(AudioRoutes.choose(setOf(speaker,ear,bluetooth),false)==bluetooth)
                    check(AudioRoutes.choose(setOf(speaker,wired),true)==speaker)
                    check(AudioRoutes.choose(setOf(speaker),false)==null)
                }finally{runOnMainSync{other?.let{manager.abandonAudioFocusRequest(it)};audio.release();activity.finish()}}
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS rounds 35/36: real Android focus loss/regain and bounded wake-lock release; audio mode restored; private headset/speaker/removal routing policy")});return
            }
            if(mode=="accountScope") {
                val original=Hub.token
                val activity=startActivitySync(android.content.Intent(targetContext,AccountActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as AccountActivity
                fun all(view:android.view.View):List<android.view.View> = listOf(view)+if(view is android.view.ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))} else emptyList()
                fun privateValue(name:String):Any?=AccountActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
                fun awaitCondition(test:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+20000;while(!test()){check(android.os.SystemClock.elapsedRealtime()<end){"Account UI timed out"};android.os.SystemClock.sleep(100)}}
                try {
                    waitForIdleSync();awaitCondition{privateValue("busy")==false}
                    val views=all(activity.window.decorView)
                    val phrase=views.filterIsInstance<android.widget.EditText>().first{it.hint.toString().startsWith("迁移口令")}
                    val export=views.filterIsInstance<android.widget.TextView>().first{it.text.toString()=="导出加密身份备份"}
                    runOnMainSync{phrase.setText("short");export.performClick()};awaitCondition{privateValue("busy")==false}
                    check(privateValue("exportData")=="")
                    repeat(2) {
                        runOnMainSync{phrase.setText("qa-safe-cancel-passphrase");export.performClick()}
                        awaitCondition{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
                        check(uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                        awaitCondition{uiAutomation.rootInActiveWindow?.packageName?.toString()=="cn.qingliao.app"}
                        waitForIdleSync();check(privateValue("exportData")==""&&privateValue("busy")==false)
                    }
                    val import=views.filterIsInstance<android.widget.TextView>().first{it.text.toString()=="导入原设备身份备份"}
                    runOnMainSync{phrase.setText("qa-safe-cancel-passphrase");import.performClick()}
                    awaitCondition{uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
                    check(uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                    awaitCondition{uiAutomation.rootInActiveWindow?.packageName?.toString()=="cn.qingliao.app"};waitForIdleSync()
                    check(privateValue("importPassword")==""&&Hub.token==original)
                    runOnMainSync{Hub.token="owned-invalid-session-probe";callActivityOnResume(activity)}
                    check(activity.isFinishing)
                    finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS rounds 22/25: real document-picker export cancel/retry and import cancel erase transient secrets; invalid passphrase recoverable; account screen closes on session change")})
                }finally{runOnMainSync{Hub.token=original;activity.finish()}}
                return
            }
            if(mode=="restart") {
                val proofFile=java.io.File(targetContext.getExternalFilesDir(null),"qa-restart-proof.json")
                val proof=JSONObject(proofFile.readText());proofFile.delete()
                val before=Hub.rawApi("GET","/api/crypto/identity/"+Hub.me.getString("id")).getString("key_id")
                // Simulate a lost original identity while keeping the already protected pending candidate.
                Hub.app.getSharedPreferences("chat_crypto",Context.MODE_PRIVATE).edit().remove("identity:"+Hub.me.getString("id")).commit();ChatCrypto.clear()
                val result=ChatCrypto.restartIdentity(proof);check(result.getString("key_id")!=before)
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS rounds 18/20: owned lost-identity restart uses persisted private candidate and verified proof; server requires relogin")});return
            }
            if(mode=="historyVerify") {
                val raw=JSONObject(java.io.File(targetContext.getExternalFilesDir(null),"qa-old-history.json").readText())
                val messages=ChatCrypto.transform(raw).getJSONArray("messages")
                check((0 until messages.length()).any{messages.getJSONObject(it).optString("sender")==peer&&messages.getJSONObject(it).optBoolean("e2ee")&&!messages.getJSONObject(it).optBoolean("decrypt_failed")})
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS round 17: original participant verifies/decrypts archived identity messages after peer restart")});return
            }
            if(mode=="adoptPending") {
                val current=ChatCrypto.ready().getString("key_id");val uid=Hub.me.getString("id")
                check(Hub.app.getSharedPreferences("chat_crypto",Context.MODE_PRIVATE).getString("pending:$uid",null)==null)
                val raw=Hub.rawApi("GET","/api/messages/$peer").getJSONArray("messages")
                check((0 until raw.length()).any { i -> val m=raw.getJSONObject(i);m.optString("type")=="encrypted"&&m.getJSONObject("envelope").optString(if(m.optString("sender")==uid)"sender_key" else "recipient_key")!=current&&ChatCrypto.incoming(m).optBoolean("decrypt_failed") })
                finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS rounds 16/20: relogin adopts exactly the committed pending identity; missing old private key cannot recover old ciphertext")});return
            }
            if(mode=="adopt"||mode=="newIdentitySend") {
                val session=Hub.token;if(mode=="adopt")ChatCrypto.adoptPeerAfterFriendAction(peer,session)
                val sent=Hub.api("POST","/api/messages",JSONObject().put("recipient",peer).put("type","text").put("text","Readded identity round 50").put("client_id",UUID.randomUUID().toString()))
                check(sent.optBoolean("e2ee"));finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS round 19: explicit user friend action accepts new identity; encrypted chat works")});return
            }
            if(mode=="export") {
                val file=java.io.File(targetContext.getExternalFilesDir(null),"qa-transfer.qingkey")
                file.writeText(ChatCrypto.exportIdentity("qa-migration-passphrase-unique"))
                finish(Activity.RESULT_OK,Bundle().apply { putString("result","PASS encrypted owned-QA migration export") });return
            }
            require(Hub.token.isNotEmpty() && peer.isNotEmpty()) { "Login both owned QA devices first" }
            ChatCrypto.ready()
            var checks=0
            fun checkCase(pass:Boolean){check(pass) { "Crypto assertion #$checks" };checks++}
            for(kind in listOf("text","html")) {
                val id=UUID.randomUUID().toString();val value=if(kind=="html")"<b>Encrypted HTML 中文 🌿</b>" else "Private text 中文 🌿 $id"
                val request=JSONObject().put("recipient",peer).put("type",kind).put("text",value).put("client_id",id)
                val encrypted=ChatCrypto.outgoing(request)
                checkCase(!encrypted.toString().contains(value))
                val sent=Hub.rawApi("POST","/api/messages",encrypted)
                checkCase(sent.getString("type")=="encrypted" && sent.getString("text")=="")
                checkCase(ChatCrypto.incoming(sent).getString("text")==value)
                val e=sent.getJSONObject("envelope")
                val bad=JSONObject(sent.toString());val signature=e.getString("signature")
                bad.getJSONObject("envelope").put("signature",(if(signature[0]=='A')"B" else "A")+signature.drop(1))
                checkCase(ChatCrypto.incoming(bad).optBoolean("decrypt_failed"))
                val wrongContext=JSONObject(sent.toString());wrongContext.put("client_id","swapped")
                checkCase(ChatCrypto.incoming(wrongContext).optBoolean("decrypt_failed"))
                val wrongCipher=JSONObject(sent.toString());wrongCipher.getJSONObject("envelope").put("recipient_cipher",e.getString("sender_cipher"))
                checkCase(ChatCrypto.incoming(wrongCipher).optBoolean("decrypt_failed"))
            }
            val data=ByteArray(2*1024*1024+73).also { SecureRandom().nextBytes(it) }
            val key=ChatCrypto.fileKey();val context="qingliao:attachment:1:"+UUID.randomUUID().toString()
            val out=ByteArrayOutputStream();ChatCrypto.encryptFile(ByteArrayInputStream(data),out,key,context);val ciphertext=out.toByteArray()
            val plain=ByteArrayOutputStream();ChatCrypto.decryptFile(ByteArrayInputStream(ciphertext),plain,key,context)
            checkCase(data.contentEquals(plain.toByteArray()))
            for(variant in listOf(ciphertext.copyOf(ciphertext.size-16),ciphertext.clone().also { it[it.size/2]=(it[it.size/2].toInt() xor 1).toByte() })) {
                var rejected=false;try { ChatCrypto.decryptFile(ByteArrayInputStream(variant),ByteArrayOutputStream(),key,context) }catch(_:Exception){rejected=true};checkCase(rejected)
            }
            var rejected=false;try{ChatCrypto.decryptFile(ByteArrayInputStream(ciphertext),ByteArrayOutputStream(),key,context+"wrong")}catch(_:Exception){rejected=true};checkCase(rejected)
            val request=Request.Builder().url(Hub.SERVER+"/api/uploads?recipient="+peer+"&name=encrypted.bin").header("Authorization","Bearer "+Hub.token).post(ciphertext.toRequestBody("application/x-qingliao-encrypted".toMediaType())).build()
            val f=Hub.http.newCall(request).execute().use { r -> check(r.isSuccessful);JSONObject(r.body!!.string()) }
            checkCase(f.getString("name")=="encrypted.bin")
            val innerFile=JSONObject().put("id",f.getString("id")).put("name","private-crypto-test.bin").put("mime","application/octet-stream").put("size",data.size).put("crypto",key).put("context",context)
            val sent=Hub.api("POST","/api/messages",JSONObject().put("recipient",peer).put("type","file").put("text","").put("file_id",f.getString("id")).put("private_file",innerFile).put("client_id",UUID.randomUUID().toString()))
            checkCase(Hub.cachedFile(sent.getJSONObject("file")).readBytes().contentEquals(data))
            val remote=Hub.rawApi("GET","/api/messages/"+peer)
            checkCase(!remote.toString().contains("private-crypto-test.bin") && !remote.toString().contains(key))
            // A backup must survive a valid import; an incorrect passphrase must leave identity intact.
            val before=ChatCrypto.ready().getString("key_id")
            val preferences=Hub.app.getSharedPreferences("chat_crypto",Context.MODE_PRIVATE)
            val pinName="peer:"+Hub.me.getString("id")+":"+peer
            val pinned=preferences.getString(pinName,null)
            val knownName="known:"+Hub.me.getString("id")+":"+peer+":"+"a".repeat(64)
            preferences.edit().putBoolean(knownName,true).commit()
            val backup=ChatCrypto.exportIdentity("qa-migration-passphrase-unique")
            checkCase(!backup.contains("HpkePrivateKey"))
            checkCase(JSONObject(backup).getInt("v")==2)
            rejected=false;try{ChatCrypto.importIdentity(backup,"wrong-password-long")}catch(_:Exception){rejected=true};checkCase(rejected)
            rejected=false;try{ChatCrypto.importIdentity(JSONObject(backup).put("v",99).toString(),"qa-migration-passphrase-unique")}catch(_:Exception){rejected=true};checkCase(rejected)
            preferences.edit().remove(pinName).remove(knownName).commit()
            ChatCrypto.importIdentity(backup,"qa-migration-passphrase-unique")
            checkCase(ChatCrypto.ready().getString("key_id")==before)
            checkCase(pinned!=null && preferences.getString(pinName,null)==pinned)
            checkCase(preferences.getBoolean(knownName,false))
            val local=Hub.app.getSharedPreferences("chat_crypto",Context.MODE_PRIVATE).getString("identity:"+Hub.me.getString("id"),"")!!
            checkCase(!local.contains("HpkePrivateKey") && !local.contains("EcdsaPrivateKey"))
            finish(Activity.RESULT_OK,Bundle().apply { putString("result","PASS $checks checks: HPKE/signature/context tamper; streaming multi-segment AEAD; ciphertext storage; attachment roundtrip; backup/import; Keystore wrapping") })
        }catch(e:Throwable){if(mode=="screenShare")java.io.File(targetContext.getExternalFilesDir(null),"screen-share-failure.txt").writeText(e.stackTraceToString());if(mode.startsWith("voiceText"))java.io.File(targetContext.getExternalFilesDir(null),"voice-text-failure.txt").writeText(e.stackTraceToString());finish(Activity.RESULT_CANCELED,Bundle().apply { putString("result","FAIL "+e.javaClass.simpleName+": "+e.message?.take(160)) })}
    }
}
