package cn.qingliao.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Public sample and locally intercepted test accounts only; no real chats or microphone. */
internal object VoiceTextTests {
    fun run(i:Instrumentation,live:Boolean):String {
        val owner="97007001";val other="97007002";val session="synthetic-voice-transcription-session"
        val checks=JSONArray();val sent=AtomicInteger();val downloads=AtomicInteger()
        val folder=File(i.targetContext.getExternalFilesDir(null),if(live)"voice-text-live"else "voice-text-fake").apply{mkdirs()}
        val wav=File(i.targetContext.cacheDir,"public-voice.wav").apply{writeBytes(i.context.assets.open("dictation/funasr_zh.wav").use{it.readBytes()})}
        val aac=File(i.targetContext.cacheDir,"public-voice.m4a").apply{writeBytes(i.context.assets.open("voice-text/public-speech.m4a").use{it.readBytes()})}
        fun rejected(work:()->Unit){var caught=false;try{work()}catch(_:Exception){caught=true};check(caught)}
        val w=VoiceTextAudio.encode(wav);val a=VoiceTextAudio.encode(aac)
        check(kotlin.math.abs(w.samples-a.samples)<16000/3&&w.samples>16000*5&&a.bytes.size<256*1024){"Decoded PCM mismatch: WAV=${w.samples}, AAC=${a.samples}, encoded=${a.bytes.size}"}
        val oversized=File(i.targetContext.cacheDir,"oversized-voice").apply{java.io.RandomAccessFile(this,"rw").use{it.setLength(VoiceTextAudio.MAX_BYTES+1)}}
        rejected{VoiceTextAudio.encode(oversized)};oversized.delete()
        val bad=File(i.targetContext.cacheDir,"bad-voice").apply{writeText("not a recording")};rejected{VoiceTextAudio.encode(bad)};bad.delete()
        rejected{VoiceTextAudio.encode(aac){throw WetypeClient.Failure("cancelled")}}
        if(android.os.Build.VERSION.SDK_INT<=28){
            val edited=aac.readBytes();val index=String(edited,Charsets.ISO_8859_1).indexOf("elst");check(index>0)
            java.nio.ByteBuffer.wrap(edited).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(index+16,44100)
            val trimmed=File(i.targetContext.cacheDir,"long-edit.m4a").apply{writeBytes(edited)};rejected{VoiceTextAudio.encode(trimmed)};trimmed.delete()
        }
        checks.put("native WAV and 44.1kHz stereo AAC decode/resample/Opus, malformed/oversize/cancellation bounds")
        fun user(id:String)=JSONObject().put("id",id).put("nickname",if(id==owner)"我"else "语音测试").put("username","local_fixture").put("friend",true).put("admin_password_change_required",true)
        fun message(id:String="a".repeat(32),mine:Boolean=false)=JSONObject().put("id",id).put("sender",if(mine)owner else other).put("recipient",if(mine)other else owner)
            .put("type","voice").put("text","6″").put("created",System.currentTimeMillis()).put("file_id","b".repeat(32)).put("attachment_available",true)
            .put("file",JSONObject().put("id","b".repeat(32)).put("name","public-speech.m4a").put("mime","audio/mp4").put("size",aac.length()))
        fun ui(f:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())f()else i.runOnMainSync{f()}}
        fun field(o:Any,name:String)=o.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(o)
        fun set(o:Any,name:String,value:Any?){o.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(o,value)}
        fun invoke(o:Any,name:String,vararg args:Any?){val m=o.javaClass.declaredMethods.first{it.name==name&&it.parameterCount==args.size}.apply{isAccessible=true};ui{m.invoke(o,*args)}}
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun views(a:Activity):List<View>{var v=listOf<View>();ui{v=walk(a.window.decorView)};return v}
        fun await(predicate:()->Boolean){val end=SystemClock.elapsedRealtime()+90000;while(!predicate()){check(SystemClock.elapsedRealtime()<end){"Voice text native scenario timed out"};Thread.sleep(30)};i.waitForIdleSync()}
        fun capture(name:String){Thread.sleep(200);val b=i.uiAutomation.takeScreenshot();File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        ui{
            Hub.token=session;Hub.me=user(owner);Hub.connected=false
            Hub.http=OkHttpClient.Builder().addInterceptor{chain->val r=chain.request();val path=r.url.encodedPath
                if(r.method=="POST"&&(path=="/api/messages"||path.startsWith("/api/uploads")))sent.incrementAndGet()
                val bytes=if(path.startsWith("/api/files/")){check(r.header("Authorization")=="Bearer $session");downloads.incrementAndGet();aac.readBytes()}else JSONObject().put("contacts",JSONArray()).put("conversations",JSONArray()).put("messages",JSONArray()).toString().toByteArray()
                Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("Local voice fixture").body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
            }.build()
        }
        LocalData.resetIdentity(owner)
        val activity=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val m=message()
        @Suppress("UNCHECKED_CAST") val messages=field(activity,"messages") as MutableList<JSONObject>
        fun show(m:JSONObject){ui{set(activity,"peer",user(other));messages.clear();messages.add(m)};invoke(activity,"showChat");LocalData.worker.submit{}.get();i.waitForIdleSync()}
        fun menu(m:JSONObject):MessageActionMenu{invoke(activity,"messageActions",m);return field(activity,"messageMenu") as MessageActionMenu}
        fun click(dialog:MessageActionMenu,label:String){ui{walk(dialog.contentView).first{it is TextView&&it.text.toString()==label}.let{v->var target=v;while(!target.isClickable&&target.parent is View)target=target.parent as View;target.performClick()}}}
        fun noPopup(){ @Suppress("UNCHECKED_CAST") val ds=field(activity,"dialogs") as Set<Dialog>;check(ds.none{it.isShowing});check((field(activity,"actionSheet") as? Dialog)?.isShowing!=true);check((field(activity,"messageMenu") as? MessageActionMenu)?.isShowing!=true) }
        fun inlineClick(label:String){ui{views(activity).filterIsInstance<TextView>().first{it.text.toString()==label}.performClick()}}
        fun startFake(m:JSONObject,client:WetypeClient,saved:AtomicBoolean=AtomicBoolean(false)):VoiceTranscription {
            val stamp=LocalData.epoch(owner);lateinit var job:VoiceTranscription
            ui{
                invoke(activity,"setVoiceTextState",m.getString("id"),true,"")
                job=VoiceTranscription(m.getString("id"),m.getString("file_id"),m.getJSONObject("file"),{Hub.token==session&&Hub.me.optString("id")==owner&&Hub.foreground&&LocalData.epoch(owner)==stamp},
                    {value->LocalData.putVoiceText(owner,other,m,value,stamp);saved.set(true)},
                    {value->invoke(activity,"completeVoiceText",m.getString("id"),m.getString("file_id"),value)},
                    {value->invoke(activity,"setVoiceTextState",m.getString("id"),true,value)},
                    {reason->set(activity,"voiceTextJob",null);invoke(activity,"setVoiceTextState",m.getString("id"),false,reason)},client)
                set(activity,"voiceTextJob",job);job.start()
            };return job
        }
        try{
            show(m);check(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            activity.getSharedPreferences("dictation",0).edit().putBoolean("voice_consent",false).putBoolean("consent",false).commit()
            ui{views(activity).first{it.contentDescription=="播放语音"}.performLongClick()}
            val actions=field(activity,"messageMenu") as MessageActionMenu;check(walk(actions.contentView).filterIsInstance<TextView>().any{it.text.toString()=="转文字"});capture("long-press-menu")
            val editor=field(activity,"composer") as android.widget.EditText
            ui{
                editor.setText("保留我的草稿");click(actions,"转文字");noPopup()
                check(views(activity).filterIsInstance<TextView>().any{it.text.toString()=="正在转文字…"})
                check(field(activity,"composer")===editor&&editor.text.toString()=="保留我的草稿")
                views(activity).filterIsInstance<TextView>().first{it.text.toString()=="取消"}.performClick()
                check(field(activity,"voiceTextJob")==null)
            }
            checks.put("actual long-press action immediately starts inline recognition with both old consent flags false; zero follow-up dialogs; inline cancel preserves composer")
            val fake=WetypeFixtures.server();val saved=AtomicBoolean(false);val bubble=views(activity).first{it.contentDescription=="播放语音"}
            startFake(m,fake.client,saved);await{saved.get()&&field(activity,"voiceTextJob")==null};check(fake.audio.size()>0&&fake.requests.get()>2)
            check(views(activity).filterIsInstance<TextView>().any{it.text.toString()=="你好。"});check(views(activity).any{it===bubble});capture("transcript");noPopup()
            val result=LocalData.voiceTexts(owner,other);check(result.getJSONObject(m.getString("id")).getString("text")=="你好。")
            val cached=menu(m);check(walk(cached.contentView).any{it.contentDescription=="复制识别文字"});click(cached,"收起文字")
            check(views(activity).filterIsInstance<TextView>().none{it.text.toString()=="语音转文字 · 仅自己可见"});click(menu(m),"显示文字")
            checks.put("authenticated attachment/fake encrypted ASR, inline result/copy/fold/show without rebuilding the message bubble or composer")
            val db=android.database.sqlite.SQLiteDatabase.openDatabase(i.targetContext.getDatabasePath("chat-local.sqlite").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            db.rawQuery("SELECT body FROM voice_text WHERE owner=?",arrayOf(owner)).use{c->check(c.moveToFirst());val blob=c.getBlob(0);check(!String(blob,Charsets.UTF_8).contains("你好。"));val damaged=blob.clone().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()};db.execSQL("UPDATE voice_text SET body=? WHERE owner=?",arrayOf(damaged,owner));check(LocalData.voiceTexts(owner,other).length()==0);db.execSQL("UPDATE voice_text SET body=? WHERE owner=?",arrayOf(blob,owner))};db.close()
            rejected{LocalData.voiceTexts("97007003",other)}
            val stamp=LocalData.epoch(owner);LocalData.changed(owner);rejected{LocalData.putVoiceText(owner,other,m,"迟到结果",stamp)}
            LocalData.hide(owner,m.getString("id"));check(LocalData.voiceTexts(owner,other).length()==0)
            val self=message("c".repeat(32),true);show(self);val selfMenu=menu(self);check(walk(selfMenu.contentView).filterIsInstance<TextView>().any{it.text.toString()=="转文字"});ui{selfMenu.dismiss()}
            val current=LocalData.epoch(owner);LocalData.putVoiceText(owner,other,self,"本机测试文字",current);LocalData.clear(owner,other,Long.MAX_VALUE);check(LocalData.voiceTexts(owner,other).length()==0)
            checks.put("sender support, authenticated encrypted cache/tamper/cross-owner/late-epoch rejection and delete/clear cleanup")
            LocalData.resetIdentity(owner)
            val failedSaved=AtomicBoolean(false);startFake(self,WetypeFixtures.server(apiStatus=503).client,failedSaved)
            await{views(activity).filterIsInstance<TextView>().any{it.text.toString().contains("正忙")}};check(!failedSaved.get());noPopup();capture("inline-error")
            ui{
                views(activity).filterIsInstance<TextView>().first{it.text.toString()=="重试"}.performClick();noPopup()
                check(views(activity).filterIsInstance<TextView>().any{it.text.toString()=="正在转文字…"})
                views(activity).filterIsInstance<TextView>().first{it.text.toString()=="取消"}.performClick()
            }
            checks.put("503 feedback and retry stay inside the voice row; no partial text saved; retry starts immediately without confirmations")
            val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val pausedSaved=AtomicBoolean(false)
            val slow=WetypeFixtures.server(beforeRequest={entered.countDown();check(release.await(15,java.util.concurrent.TimeUnit.SECONDS))})
            val pausedJob=startFake(self,slow.client,pausedSaved);check(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));noPopup()
            ui{(field(activity,"composer") as android.widget.EditText).setText("转文字时继续输入")};capture("inline-progress")
            val theme=i.startActivitySync(Intent(i.targetContext,ThemeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            check(!(field(pausedJob,"active") as AtomicBoolean).get());release.countDown();ui{theme.finish()};await{Hub.foreground};Thread.sleep(200);check(!pausedSaved.get())
            checks.put("chat remains usable while transcribing; actual activity background cancels request and rejects late callback")
            var recognized=""
            if(live){
                val real=message("d".repeat(32));show(real);activity.getSharedPreferences("dictation",0).edit().putBoolean("voice_consent",false).commit()
                ui{click(menu(real),"转文字");noPopup();check(views(activity).filterIsInstance<TextView>().any{it.text.toString()=="正在转文字…"})}
                await{LocalData.voiceTexts(owner,other).has(real.getString("id"))};recognized=LocalData.voiceTexts(owner,other).getJSONObject(real.getString("id")).getString("text")
                check(recognized.contains("识别")&&recognized.length>10);await{views(activity).filterIsInstance<TextView>().any{it.text.toString()==recognized}};noPopup();capture("public-sample-real-result")
                checks.put("real WeType public AAC recognition starts on one context-menu click, first-use included, with zero confirmation/progress dialogs")
            }
            check(sent.get()==0&&activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            val data=JSONObject().put("passed",true).put("api",android.os.Build.VERSION.SDK_INT).put("livePublicSample",live).put("privateAudioUsed",false).put("automaticChatSends",sent.get()).put("microphonePermissionGranted",false).put("confirmationDialogs",0).put("blockingProgressDialogs",0).put("inlineProgressRetryCancel",true).put("composerRemainsUsable",true).put("wavSamples",w.samples).put("aacSamples",a.samples).put("recognizedPublicSample",recognized).put("checks",checks)
            File(folder,"result.json").writeText(data.toString(2));return "PASS native voice-message transcription: "+data.toString()
        }finally{ui{invoke(activity,"cancelVoiceText",false);Hub.logout();activity.finish()};wav.delete();aac.delete()}
    }
}
