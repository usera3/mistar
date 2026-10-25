package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.google.crypto.tink.*
import com.google.crypto.tink.signature.SignatureKeyTemplates
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.*
import java.io.*
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Real native windows/selection + real Tink encryption, with every HTTP/WS request intercepted locally. */
object MessageInteractionTests {
    fun run(i:Instrumentation):String {
        com.google.crypto.tink.hybrid.HybridConfig.register();com.google.crypto.tink.signature.SignatureConfig.register();com.google.crypto.tink.streamingaead.StreamingAeadConfig.register()
        val folder=File(i.targetContext.getExternalFilesDir(null),"message-interactions").apply{mkdirs()}
        val owner="82000001";val source="82000002";val target="82000003";val session="synthetic-message-interaction-session"
        val identities=ConcurrentHashMap<String,JSONObject>();val originals=ConcurrentHashMap<String,ByteArray>();val uploaded=ConcurrentHashMap<String,ByteArray>()
        val delivered=CopyOnWriteArrayList<JSONObject>();val deletions=CopyOnWriteArrayList<String>();val posts=CopyOnWriteArrayList<JSONObject>()
        val uploadCounter=AtomicInteger();val slow=AtomicBoolean(false);val hitSlow=AtomicBoolean(false)
        fun user(id:String,name:String)=JSONObject().put("id",id).put("username","qa_$id").put("nickname",name).put("state","accepted").put("friend",true)
        fun publicIdentity():JSONObject {
            fun serialize(k:KeysetHandle):String{val out=ByteArrayOutputStream();k.writeNoSecret(JsonKeysetWriter.withOutputStream(out));return out.toString("UTF-8")}
            val enc=serialize(KeysetHandle.generateNew(KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM")).publicKeysetHandle)
            val sig=serialize(KeysetHandle.generateNew(SignatureKeyTemplates.ECDSA_P256).publicKeysetHandle)
            return JSONObject().put("enc",enc).put("sig",sig).put("key_id",MessageDigest.getInstance("SHA-256").digest((enc+"\n"+sig).toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)})
        }
        identities[target]=publicIdentity();identities[source]=publicIdentity()
        fun base(kind:String,text:String,mine:Boolean=false)=JSONObject().put("id",UUID.randomUUID().toString()).put("client_id",UUID.randomUUID().toString()).put("sender",if(mine)owner else source).put("recipient",if(mine)source else owner).put("type",kind).put("text",text).put("created",System.currentTimeMillis()-6000).put("read",false)
        fun attachment(kind:String,name:String,mime:String,plain:ByteArray):JSONObject {
            val fid=UUID.randomUUID().toString().replace("-","");val key=ChatCrypto.fileKey();val ctx="qingliao:attachment:1:"+UUID.randomUUID()
            val out=ByteArrayOutputStream();ChatCrypto.encryptFile(plain.inputStream(),out,key,ctx);originals[fid]=out.toByteArray()
            return base(kind,if(kind=="voice")"2″"else"").put("file_id",fid).put("file",JSONObject().put("id",fid).put("name",name).put("mime",mime).put("size",plain.size).put("crypto",key).put("context",ctx))
        }
        val imageBytes=ByteArrayOutputStream().also{out->Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(89,139,215));compress(Bitmap.CompressFormat.PNG,100,out);recycle()}}.toByteArray()
        val image=attachment("image","合成图片.png","image/png",imageBytes)
        val voiceBytes=ByteArray(64044).apply{ "RIFF".toByteArray().copyInto(this);"WAVEfmt ".toByteArray().copyInto(this,8) }
        val voice=attachment("voice","synthetic.wav","audio/wav",voiceBytes)
        val documentBytes="Synthetic attachment. No private user file.".toByteArray()
        val document=attachment("file","synthetic.txt","text/plain",documentBytes)
        val text=base("text","周末去海边吧，等你有空我们再一起出发。")
        val mine=base("text","好呀，我来安排时间。",true)
        val html=base("html","<div style='padding:12px;color:#7660d5'>弥星 · 合成 HTML</div>")
        val originalHttp=Hub.http;val originalUpdate=AppUpdates.transport
        fun ui(block:()->Unit)=i.runOnMainSync(block)
        fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        fun set(o:Any,n:String,v:Any?){o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
        fun invoke(o:Any,n:String,vararg args:Any?){ui{o.javaClass.declaredMethods.first{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(o,*args)}}
        fun await(timeout:Long=30000,p:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(true){var yes=false;ui{yes=p()};if(yes){i.waitForIdleSync();return};check(SystemClock.elapsedRealtime()<end){"Native message interaction timeout"};Thread.sleep(40)}}
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun capture(name:String){i.waitForIdleSync();Thread.sleep(350);val b=i.uiAutomation.takeScreenshot();File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        ui{
            Hub.token=session;Hub.me=user(owner,"我");Hub.connected=false;ChatCrypto.clear()
            Hub.http=OkHttpClient.Builder().addInterceptor{chain->
                val r=chain.request();check(r.url.host in setOf("47.245.2.254","api.claudcode.xyz"));val path=r.url.encodedPath
                val buffer=Buffer();r.body?.writeTo(buffer);val raw=buffer.readByteArray();var binary:ByteArray?=null
                val body=if(r.method=="POST"&&path!="/api/uploads")runCatching{JSONObject(String(raw))}.getOrDefault(JSONObject())else JSONObject()
                val value=when{
                    path.startsWith("/api/crypto/identity/")->identities[path.substringAfterLast('/')]?:JSONObject()
                    path=="/api/crypto/identity"->{identities[owner]=body;JSONObject().put("ok",true)}
                    path=="/api/contacts"->JSONObject().put("contacts",JSONArray().put(user(target,"海风")).put(user(source,"小屿")))
                    path.startsWith("/api/relationships/")->user(source,"小屿")
                    path=="/api/conversations"->JSONObject().put("conversations",JSONArray())
                    path.startsWith("/api/messages/")&&r.method=="DELETE"->{deletions.add(path.substringAfterLast('/'));JSONObject().put("ok",true)}
                    path.startsWith("/api/messages/")->JSONObject().put("messages",JSONArray()).put("has_more",false)
                    path.startsWith("/api/files/")->{check(r.header("Authorization")=="Bearer $session");if(slow.get()){hitSlow.set(true);Thread.sleep(800)};binary=originals[path.substringAfterLast('/')]?:error("Unknown original attachment");JSONObject()}
                    path=="/api/uploads"->{check(r.url.queryParameter("recipient")==target);val id=("f"+uploadCounter.incrementAndGet().toString(16)).padEnd(32,'f');uploaded[id]=raw;JSONObject().put("id",id)}
                    path=="/api/messages"&&r.method=="POST"->{
                        check(r.header("Authorization")=="Bearer $session");check(body.getString("type")=="encrypted");posts.add(JSONObject(body.toString()))
                        val wire=JSONObject(body.toString()).put("id",UUID.randomUUID().toString()).put("sender",owner).put("recipient",target).put("created",System.currentTimeMillis())
                        val opened=ChatCrypto.incoming(wire);check(!opened.optBoolean("decrypt_failed"));delivered.add(opened);wire
                    }
                    else->JSONObject().put("ok",true)
                }
                Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic local interaction fixture").body((binary?:value.toString().toByteArray()).toResponseBody((if(binary!=null)"application/octet-stream"else"application/json").toMediaType())).build()
            }.build();AppUpdates.transport=Hub.http
        }
        LocalData.resetIdentity(owner)
        val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        ui{Hub.service?.stopSelf()};await{Hub.service==null}
        @Suppress("UNCHECKED_CAST") val messages=field(a,"messages") as MutableList<JSONObject>
        var checks=0
        fun show(items:List<JSONObject>){ui{Hub.connected=false;set(a,"peer",user(source,"小屿"));messages.clear();messages.addAll(items)};invoke(a,"showChat");await{field(a,"composer")!=null};i.waitForIdleSync();Thread.sleep(400)}
        @Suppress("UNCHECKED_CAST") fun bubble(m:JSONObject)=(field(a,"messageBubbles")as Map<String,View>)[m.getString("id")]!!
        fun menu(m:JSONObject):MessageActionMenu {
            ui{val v=bubble(m);v.requestRectangleOnScreen(Rect(0,0,v.width,v.height),true)};Thread.sleep(160)
            @Suppress("UNCHECKED_CAST") val selectable=(field(a,"messageTextViews")as Map<String,MessageTextView>)[m.optString("id")]
            if(selectable!=null){
                val point=IntArray(2);var x=0f;var y=0f
                ui{val visibleText=Rect();check(selectable.getLocalVisibleRect(visibleText));selectable.getLocationOnScreen(point);x=point[0]+(visibleText.left+selectable.width/3f).coerceAtMost(visibleText.right-4f);y=point[1]+(visibleText.top+selectable.lineHeight/2f).coerceAtMost(visibleText.bottom-4f)}
                val downTime=SystemClock.uptimeMillis();val down=android.view.MotionEvent.obtain(downTime,downTime,android.view.MotionEvent.ACTION_DOWN,x,y,0);down.source=InputDevice.SOURCE_TOUCHSCREEN;i.sendPointerSync(down);down.recycle();Thread.sleep(ViewConfiguration.getLongPressTimeout()+160L)
                val up=android.view.MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,x,y,0);up.source=InputDevice.SOURCE_TOUCHSCREEN;i.sendPointerSync(up);up.recycle()
            }else ui{bubble(m).performLongClick()}
            try{await{(field(a,"messageMenu")as?MessageActionMenu)?.isShowing==true}}catch(e:Throwable){capture("failed-menu-"+m.optString("type"));File(folder,"selection-diagnostic.json").writeText(JSONObject().put("mode",field(a,"textActionMode")!=null).put("id",field(a,"textSelectionMessage")).put("focused",selectable?.isFocused).put("start",selectable?.selectionStart).put("end",selectable?.selectionEnd).toString());throw e};Thread.sleep(160);return field(a,"messageMenu")as MessageActionMenu
        }
        fun click(p:MessageActionMenu,label:String){ui{walk(p.contentView).first{it.contentDescription?.toString()?.substringBefore('，')==label}.performClick()}}
        try{
            show(listOf(text,mine));val root=field(a,"root");val composer=field(a,"composer")as EditText;ui{composer.setText("留在输入框的草稿")}
            val p=menu(text);capture("floating-text");val actual=Rect();ui{val point=IntArray(2);p.contentView.getLocationOnScreen(point);actual.set(point[0],point[1],point[0]+p.contentView.width,point[1]+p.contentView.height)}
            val visible=Rect();ui{a.window.decorView.getWindowVisibleDisplayFrame(visible)}
            check(visible.contains(actual)){"Popup outside visible frame $actual / $visible"};check(field(a,"root")===root&&field(a,"composer")===composer);check((field(a,"actionSheet")as?Dialog)?.isShowing!=true)
            check(walk(p.contentView).any{it.contentDescription=="转发"}&&walk(p.contentView).any{it.contentDescription=="多选"});capture("floating-text")
            click(p,"复制文字");check(composer.text.toString()=="留在输入框的草稿");check(!p.isShowing);checks++
            val partial=menu(text);check(field(a,"textActionMode")!=null);click(partial,"部分选中")
            @Suppress("UNCHECKED_CAST") val selectedText=(field(a,"messageTextViews")as Map<String,MessageTextView>)[text.getString("id")]!!
            ui{android.text.Selection.setSelection(selectedText.text as android.text.Spannable,0,5)};await{(field(a,"messageMenu")as?MessageActionMenu)?.isShowing==true};capture("partial-selection")
            click(field(a,"messageMenu")as MessageActionMenu,"复制文字")
            ui{check(a.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()==text.getString("text").take(5))};checks++
            val reversed=menu(text);click(reversed,"部分选中");ui{android.text.Selection.setSelection(selectedText.text as android.text.Spannable,7,2)};await{(field(a,"messageMenu")as?MessageActionMenu)?.isShowing==true};click(field(a,"messageMenu")as MessageActionMenu,"复制文字");ui{check(a.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()==text.getString("text").substring(2,7))};checks++
            val backPopup=menu(text);i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);await{!backPopup.isShowing};check(field(a,"peer")!=null);checks++
            val outside=menu(text);val now=SystemClock.uptimeMillis();val x=a.resources.displayMetrics.widthPixels-4f;val y=a.resources.displayMetrics.heightPixels/2f
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){val event=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0);event.source=InputDevice.SOURCE_TOUCHSCREEN;i.uiAutomation.injectInputEvent(event,true);event.recycle();Thread.sleep(60)};await{!outside.isShowing};check(field(a,"root")===root);checks++
            if(Build.VERSION.SDK_INT>=30){
                ui{composer.requestFocus();a.getSystemService(InputMethodManager::class.java).showSoftInput(composer,InputMethodManager.SHOW_IMPLICIT)}
                await{a.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true}
                val keyboardMenu=menu(text);val point=IntArray(2);ui{keyboardMenu.contentView.getLocationOnScreen(point);a.window.decorView.getWindowVisibleDisplayFrame(visible)}
                check(point[1]+keyboardMenu.contentView.height<=visible.bottom);capture("keyboard-menu");ui{keyboardMenu.dismiss();a.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(composer.windowToken,0)};Thread.sleep(300);checks++
            }
            val q=menu(mine);check(walk(q.contentView).any{it.contentDescription?.toString()?.startsWith("撤回消息")==true});capture("floating-own");ui{q.dismiss()};checks++
            for(item in listOf(image,voice,document,html)){show(listOf(item));val pop=menu(item);check(walk(pop.contentView).any{it.contentDescription=="转发"});if(item===voice)capture("floating-voice");ui{pop.dismiss()};checks++}
            val gone=base("text","消息已撤回").put("withdrawn",true);show(listOf(gone));val goneMenu=menu(gone);check(walk(goneMenu.contentView).none{it.contentDescription=="转发"});ui{goneMenu.dismiss()};checks++
            val long=base("text",("这是一条用于测试长消息边缘定位的合成内容。\n").repeat(70));show(listOf(long));val longPopup=menu(long);val position=IntArray(2);ui{longPopup.contentView.getLocationOnScreen(position);a.window.decorView.getWindowVisibleDisplayFrame(visible)};check(position[1]>=visible.top&&position[1]+longPopup.contentView.height<=visible.bottom);capture("long-message-menu");ui{longPopup.dismiss()};checks++
            show(listOf(text,mine,image,voice,document));click(menu(text),"多选");check(field(a,"selecting")==true);check((field(a,"composerPanel")as View).visibility==View.GONE)
            @Suppress("UNCHECKED_CAST") val selected=field(a,"selectedMessages")as MutableSet<String>
            invoke(a,"toggleMessageSelection",mine);check(selected.size==2);capture("multi-select");checks++
            check((field(a,"chatHeader")as View).visibility==View.GONE);check((field(a,"messageList")as ViewGroup).getChildAt(1).height<a.resources.displayMetrics.heightPixels/2);checks++
            // Selection overlay intercepts media taps; no image viewer/voice playback is opened.
            val frame=(field(a,"messageList")as ViewGroup).findViewWithTag<View>(image.getString("id"))as ViewGroup
            ui{frame.getChildAt(1).performClick()};check(selected.size==3&&field(a,"player")==null);checks++
            invoke(a,"deleteSelectedMessages")
            @Suppress("UNCHECKED_CAST") val dialogs=field(a,"dialogs")as Set<Dialog>
            val confirm=dialogs.filterIsInstance<AlertDialog>().first{it.isShowing&&it.getButton(AlertDialog.BUTTON_POSITIVE)?.text.toString()=="删除"}
            ui{confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()};await{deletions.size==3&&field(a,"batchDeleteCancel")==null}
            check(deletions.toSet()==setOf(text.getString("id"),mine.getString("id"),image.getString("id")));check(field(a,"selecting")==false);checks++
            show((0..22).map{base("text","合成消息 $it")});invoke(a,"beginMessageSelection",messages[0]);for(n in 1..20)invoke(a,"toggleMessageSelection",messages[n]);check(selected.size==20);invoke(a,"endMessageSelection");checks++
            check(runCatching{MessageForwarder.validate(List(21){text})}.isFailure);check(runCatching{MessageForwarder.validate(listOf(gone))}.isFailure);checks+=2
            val oversized=JSONObject(document.toString()).apply{put("file",JSONObject(document.getJSONObject("file").toString()).put("size",Long.MAX_VALUE))};check(runCatching{MessageForwarder.validate(listOf(oversized,oversized))}.isFailure);checks++
            show(listOf(text));ui{Hub.connected=true};click(menu(text),"转发")
            await{(field(a,"forwardDialog")as?Dialog)?.isShowing==true}
            val chooser=field(a,"forwardDialog")as Dialog;await{walk(chooser.window!!.decorView).any{it.contentDescription=="转发给海风"}};capture("forward-recipient")
            ui{walk(chooser.window!!.decorView).first{it.contentDescription=="转发给海风"}.performClick()}
            val send=dialogs.filterIsInstance<AlertDialog>().first{it.isShowing&&it.getButton(AlertDialog.BUTTON_POSITIVE)?.text.toString()=="发送"};check(posts.isEmpty())
            ui{send.getButton(AlertDialog.BUTTON_POSITIVE).performClick()};await(60000){delivered.size==1&&field(a,"forwardJob")==null};check(delivered[0].getString("text")==text.getString("text"));checks++
            // Exercise real default worker -> cached decryption -> fresh upload -> encrypted outbox for every attachment kind.
            val finished=AtomicBoolean();var forwardError:String?="not finished";var job:MessageForwarder?=null
            ui{job=MessageForwarder(a,listOf(html,image,voice,document),target,{Hub.token==session},{_,_,_->},{count,error->check(count==4);forwardError=error;finished.set(true)});job!!.start()}
            await(60000){finished.get()&&delivered.size==5};check(forwardError==null&&uploaded.size==3)
            for((index,original)in listOf(image,voice,document).withIndex()){
                val sent=delivered[index+2].getJSONObject("file");val f=original.getJSONObject("file")
                check(sent.getString("id")!=f.getString("id")&&sent.getString("crypto")!=f.getString("crypto")&&sent.getString("context")!=f.getString("context"))
                val plain=ByteArrayOutputStream();ChatCrypto.decryptFile(uploaded[sent.getString("id")]!!.inputStream(),plain,sent.getString("crypto"),sent.getString("context"))
                check(plain.toByteArray().contentEquals(listOf(imageBytes,voiceBytes,documentBytes)[index]));checks++
            }
            check(posts.map{it.getString("client_id")}.toSet().size==5&&posts.all{it.getString("recipient")==target&&!it.has("text")});checks++
            // Cancel a blocked original fetch. No extra outgoing message may be committed afterwards.
            val uncached=attachment("file","cancel.txt","text/plain","cancel-only synthetic content".toByteArray());slow.set(true);finished.set(false);val sentBefore=posts.size
            ui{job=MessageForwarder(a,listOf(uncached),target,{Hub.token==session},{_,_,_->},{_,_->finished.set(true)});job!!.start()};await{hitSlow.get()};ui{job!!.cancel()};Thread.sleep(1200);check(finished.get()&&posts.size==sentBefore);checks++
            val gate=CountDownLatch(1);val entered=CountDownLatch(1);LocalData.worker.execute{entered.countDown();gate.await(5,TimeUnit.SECONDS)};check(entered.await(5,TimeUnit.SECONDS));finished.set(false)
            ui{job=MessageForwarder(a,listOf(text),target,{Hub.token==session},{_,_,_->},{_,_->finished.set(true)});job!!.start();Hub.token="synthetic-other-session"};gate.countDown();await{finished.get()};check(posts.size==sentBefore);checks++
            val apkHash=MessageDigest.getInstance("SHA-256");File(i.targetContext.applicationInfo.sourceDir).inputStream().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;apkHash.update(buffer,0,n)}}
            val result=JSONObject().put("passed",true).put("api",Build.VERSION.SDK_INT).put("checks",checks).put("floatingWindow",true).put("selectionLimit",20).put("realTinkForwarding",true).put("attachmentFreshKeysAndIds",3).put("onlySyntheticLocalHttp",true).put("realUserMessages",0).put("physicalDevices",false).put("version",BuildConfig.VERSION_NAME).put("versionCode",BuildConfig.VERSION_CODE).put("apkSha256",apkHash.digest().joinToString(""){"%02x".format(it.toInt()and 255)})
            File(folder,"result.json").writeText(result.toString(2));return "PASS native floating message menu, multi-select/delete/forward API${Build.VERSION.SDK_INT}, $checks checks"
        }catch(e:Throwable){File(folder,"failure.txt").writeText(e.stackTraceToString());runCatching{
            val pending=LocalData.pending(owner,target);val summary=JSONArray()
            for(n in 0 until pending.length()){val m=pending.getJSONObject(n);summary.put(JSONObject().put("state",m.optString("local_state")).put("error",m.optString("local_error")).put("type",m.optString("type")))}
            File(folder,"failure-state.json").writeText(JSONObject().put("queued",summary).put("posts",posts.size).put("delivered",delivered.size).put("resumed",field(a,"resumed")).put("sameSession",Hub.token==session).put("forwardRunning",(field(a,"forwardJob")as?MessageForwarder)?.running).toString())
        };throw e}
        finally{ui{Outbox.stop();Hub.service?.stopSelf();a.finish();Hub.token="";Hub.me=JSONObject();Hub.http=originalHttp;AppUpdates.transport=originalUpdate;ChatCrypto.clear()}}
    }
}
