package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.location.*
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import com.google.crypto.tink.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.*
import java.io.File
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

object LocationTests {
    fun run(i:Instrumentation,variant:String):String {
        check(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk")){"Owned emulator only"}
        val folder=File(i.targetContext.getExternalFilesDir(null),"location-test").apply{mkdirs()};val oldHttp=Hub.http;val oldUpdates=AppUpdates.transport
        val owner="89190001";val bob="89190002";val session="synthetic-location-session";val pub=mutableMapOf<String,JSONObject>();val messages=CopyOnWriteArrayList<JSONObject>();val updates=CopyOnWriteArrayList<JSONObject>();val methods=CopyOnWriteArrayList<String>()
        var share:JSONObject?=null;var deleted=0;var checks=0;var activity:Activity?=null
        val manager=i.targetContext.getSystemService(LocationManager::class.java);var mocked=false
        fun shell(command:String):String=i.uiAutomation.executeShellCommand(command).use{p->android.os.ParcelFileDescriptor.AutoCloseInputStream(p).bufferedReader().readText()}
        val oldMode=shell("settings get secure location_mode").trim()
        fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
        fun public(k:KeysetHandle):String {val out=java.io.ByteArrayOutputStream();CleartextKeysetHandle.write(k.publicKeysetHandle,JsonKeysetWriter.withOutputStream(out));return out.toString("UTF-8")}
        fun ui(f:()->Unit){var error:Throwable?=null;i.runOnMainSync{try{f()}catch(e:Throwable){error=e}};error?.let{throw it}}
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun find(a:Activity,tag:String)=walk(a.window.decorView).firstOrNull{it.tag==tag}
        fun visible(v:View?)=v!=null&&v.width>0&&v.height>0&&v.getGlobalVisibleRect(android.graphics.Rect())
        fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        fun await(label:String,test:()->Boolean){File(folder,"progress.txt").writeText(label);val end=SystemClock.elapsedRealtime()+30000;while(true){var ok=false;ui{ok=test()};if(ok){i.waitForIdleSync();return};check(SystemClock.elapsedRealtime()<end){"Location timeout: $label"};Thread.sleep(60)}}
        fun capture(name:String){i.waitForIdleSync();Thread.sleep(250);i.uiAutomation.takeScreenshot().let{image->File(folder,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}}
        fun point(lat:Double=35.6895)=Location(LocationManager.GPS_PROVIDER).apply{latitude=lat;longitude=139.6917;accuracy=12f;time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos();altitude=0.0;speed=0f;bearing=0f}
        fun mock(lat:Double=35.6895){manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,point(lat))}
        fun launch(extra:Intent=Intent()):Activity {val intent=Intent(i.targetContext,LocationActivity::class.java).putExtra("peer",bob).putExtras(extra).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);return (i.startActivitySync(intent) as Activity).also{activity=it}}
        fun reject(f:()->Unit){check(runCatching{f()}.isFailure);checks++}
        try{
            com.google.crypto.tink.hybrid.HybridConfig.register();com.google.crypto.tink.signature.SignatureConfig.register()
            val e=KeysetHandle.generateNew(KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"));val sig=KeysetHandle.generateNew(KeyTemplates.get("ECDSA_P256"));val ep=public(e);val sp=public(sig);pub[bob]=JSONObject().put("enc",ep).put("sig",sp).put("key_id",hash((ep+"\n"+sp).toByteArray()))
            ui{Hub.token=session;Hub.me=JSONObject().put("id",owner).put("nickname","合成位置用户");Hub.connected=false;ChatCrypto.clear();LocationSessions.clear()
                Hub.http=OkHttpClient.Builder().addInterceptor{chain->
                    val request=chain.request();check(request.url.host in setOf("47.245.2.254","api.claudcode.xyz"));val path=request.url.encodedPath;methods.add(request.method+" "+path)
                    val buffer=Buffer();request.body?.writeTo(buffer);val raw=buffer.readUtf8();val p=if(raw.isEmpty())JSONObject()else JSONObject(raw);var status=200
                    fun dto(wire:JSONObject)=JSONObject().put("id",UUID.randomUUID().toString()).put("sender",owner).put("recipient",bob).put("client_id",wire.getString("client_id")).put("created",System.currentTimeMillis()).put("type","encrypted").put("file_id","").put("envelope",wire.getJSONObject("envelope"))
                    val value=when{
                        path=="/api/crypto/identity"&&request.method=="POST"->{pub[owner]=JSONObject(p.toString());JSONObject().put("ok",true)}
                        path.startsWith("/api/crypto/identity/")->pub[path.substringAfterLast('/')]?:JSONObject()
                        path=="/api/messages"->{messages.add(JSONObject(p.toString()));dto(p)}
                        path=="/api/locations"->{status=201;share=JSONObject().put("id",p.getString("id")).put("owner",owner).put("peer",bob).put("epoch",0).put("until",System.currentTimeMillis()+p.getInt("duration")*1000L).put("seq",0);JSONObject().put("share",share)}
                        path.startsWith("/api/locations/")&&request.method=="PUT"->{check(share!!.getString("id")==path.substringAfterLast('/'));updates.add(JSONObject(p.toString()));share!!.put("seq",p.getInt("seq"));JSONObject().put("ok",true)}
                        path.startsWith("/api/locations/")&&request.method=="DELETE"->{deleted++;JSONObject().put("ok",true)}
                        path.startsWith("/api/locations/")&&request.method=="GET"->{val latest=updates.lastOrNull();val s=share!!;JSONObject().put("share",s).apply{latest?.let{put("update",JSONObject().put("sender",owner).put("recipient",owner).put("peer",bob).put("share_id",s.getString("id")).put("seq",it.getInt("seq")).put("until",s.getLong("until")).put("epoch",0).put("envelope",it.getJSONObject("wire").getJSONObject("envelope")))}}}
                        path=="/api/conversations"->JSONObject().put("conversations",JSONArray())
                        path=="/api/contacts"->JSONObject().put("contacts",JSONArray())
                        else->JSONObject().put("ok",true)
                    };Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic location fixture").body(value.toString().toResponseBody("application/json".toMediaType())).build()
                }.build();AppUpdates.transport=Hub.http}
            ChatCrypto.ready()
            val value=LocationPayload.from(point(),false);PayloadRules.validate(JSONObject().put("type","text").put("text",LocationPayload.text(value)).put("location",value),"");checks++
            for(bad in listOf(JSONObject(value.toString()).put("lat",91),JSONObject(value.toString()).put("lon",-181),JSONObject(value.toString()).put("accuracy",-1),JSONObject(value.toString()).put("label","x\nmalicious"),JSONObject(value.toString()).put("at",0),JSONObject(value.toString()).put("extra","ignored")))reject{LocationPayload.validate(bad)}
            reject{PayloadRules.validate(JSONObject().put("type","text").put("text","tampered").put("location",value),"")}
            check(LocationMaps.web(value).getQueryParameter("coord_type")=="wgs84"&&LocationMaps.web(value).scheme=="https");checks++
            check(LocationMaps.amap(value).getQueryParameter("dev")=="1"&&LocationMaps.baidu(value).getQueryParameter("coord_type")=="wgs84");checks++
            val wire=ChatCrypto.outgoing(LocationPayload.packet(value,bob));check(!wire.toString().contains("35.6895"));checks++
            val encrypted=JSONObject().put("id",UUID.randomUUID().toString()).put("client_id",wire.getString("client_id")).put("sender",owner).put("recipient",bob).put("created",System.currentTimeMillis()).put("type","encrypted").put("envelope",wire.getJSONObject("envelope"))
            val decoded=ChatCrypto.incoming(JSONObject(encrypted.toString()).put("location",JSONObject(value.toString()).put("lat",10)));check(decoded.getJSONObject("location").getDouble("lat")==35.6895);checks++
            LocalData.put(owner,decoded);for(suffix in listOf("","-wal")){val file=File(i.targetContext.getDatabasePath("chat-local.sqlite").path+suffix);if(file.exists())check(!String(file.readBytes(),Charsets.ISO_8859_1).contains("139.6917"))};checks++
            val meta=JSONObject().put("id",UUID.randomUUID().toString()).put("owner",owner).put("peer",bob).put("epoch",0).put("until",System.currentTimeMillis()+3600000).put("seq",1)
            val liveValue=JSONObject(value.toString()).put("live_id",meta.getString("id")).put("until",meta.getLong("until"));val liveWire=ChatCrypto.sealLocation(meta,1,liveValue)
            val update=JSONObject().put("sender",owner).put("recipient",owner).put("peer",bob).put("epoch",0).put("until",meta.getLong("until")).put("share_id",meta.getString("id")).put("seq",1).put("envelope",liveWire.getJSONObject("envelope"))
            check(ChatCrypto.openLocation(update).getJSONObject("location").getDouble("lon")==139.6917);checks++
            val aad="qingliao:location:1:${meta.getString("id")}:1:$owner:$bob:0";val opened=e.getPrimitive(HybridDecrypt::class.java).decrypt(android.util.Base64.decode(liveWire.getJSONObject("envelope").getString("recipient_cipher"),android.util.Base64.DEFAULT),aad.toByteArray());check(JSONObject(String(opened)).getJSONObject("location").getDouble("lat")==35.6895);checks++
            reject{ChatCrypto.openLocation(JSONObject(update.toString()).put("seq",2))};reject{ChatCrypto.openLocation(JSONObject(update.toString()).put("until",meta.getLong("until")+1000))};reject{ChatCrypto.openLocation(JSONObject(update.toString()).put("recipient","89199999"))}
            ui{LocationSessions.remember(ChatCrypto.openLocation(update));val changed=ChatCrypto.openLocation(update);changed.getJSONObject("share").put("owner",bob).put("seq",2);LocationSessions.remember(changed);check(LocationSessions.get(meta.getString("id"))!!.getJSONObject("share").getString("owner")==owner)};checks++
            ui{LocationSessions.close(meta.getString("id"));LocationSessions.remember(ChatCrypto.openLocation(update));check(LocationSessions.get(meta.getString("id"))==null)};checks++
            ui{Hub.token="other-account-session";Hub.me=JSONObject().put("id","89199999");check(LocationSessions.get(meta.getString("id"))==null);Hub.token=session;Hub.me=JSONObject().put("id",owner)};checks++
            // Actual system permission dialog, then a named Android test GPS provider on an emulator.
            val picker=launch();await("picker has disabled send before fix"){find(picker,"location.send")!=null};check(!find(picker,"location.send")!!.isEnabled);checks++
            val deadline=SystemClock.elapsedRealtime()+10000;while(i.targetContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&SystemClock.elapsedRealtime()<deadline){
                val root=i.uiAutomation.rootInActiveWindow;val nodes=root?.findAccessibilityNodeInfosByViewId("com.android.packageinstaller:id/permission_allow_button").orEmpty();nodes.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(200)
            }
            check(i.targetContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED);checks++
            shell("appops set cn.qingliao.app android:mock_location allow");shell("settings put secure location_mode 3")
            manager.addTestProvider(LocationManager.GPS_PROVIDER,false,false,false,false,true,true,true,Criteria.POWER_LOW,Criteria.ACCURACY_FINE);mocked=true;manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true);ui{find(picker,"location.refresh")!!.performClick()};mock()
            await("real provider preview"){find(picker,"location.send")?.isEnabled==true&&visible(find(picker,"location.send"))};check(messages.isEmpty());checks++;capture("location-preview")
            ui{find(picker,"location.send")!!.performClick()};await("one-shot encrypted card sent"){messages.size==1&&picker.isFinishing};activity=null;check(!messages.last().toString().contains("139.6917"));checks++
            val view=launch(Intent().putExtra("location",value.toString()));await("read-only viewer"){find(view,"location.coordinates")?.let{(it as TextView).text.toString().contains("35.689500")}==true};check(field(view,"source")==null);checks++;capture("location-detail");ui{view.finish()};activity=null
            val livePicker=launch();mock();await("live picker ready"){find(livePicker,"location.start")?.isEnabled==true&&visible(find(livePicker,"location.start"))};ui{find(livePicker,"location.start")!!.performClick()}
            await("foreground sharing notification"){LocationShareService.instance!=null&&i.targetContext.getSystemService(NotificationManager::class.java).activeNotifications.any{it.id==LocationShareService.NOTIFICATION}};activity=null;checks++
            mock();await("first signed live packet"){updates.isNotEmpty()};check(messages.size==2&&updates.all{!it.toString().contains("139.6917")});checks++
            shell("input keyevent KEYCODE_HOME");Thread.sleep(3300);mock(35.6905);await("OS delivers new GPS fix"){(LocationShareService.instance?.let{field(it,"fix")} as? Location)?.latitude==35.6905};await("live update while at system home"){updates.size>=2};check(LocationShareService.instance!=null&&messages.size==2);checks++
            val notification=i.targetContext.getSystemService(NotificationManager::class.java).activeNotifications.first{it.id==LocationShareService.NOTIFICATION}.notification
            val monitor=i.addMonitor(LocationActivity::class.java.name,null,false);notification.contentIntent.send();val liveView=i.waitForMonitorWithTimeout(monitor,15000)?:error("Live notification viewer missing");i.removeMonitor(monitor);activity=liveView
            await("latest verified live coordinate"){find(liveView,"location.coordinates")?.let{(it as TextView).text.toString().contains("35.690500")}==true};check(field(liveView,"source")==null);checks++;capture("live-location")
            notification.actions.last().actionIntent.send();await("notification stops GPS and sharing"){LocationShareService.instance==null&&deleted>0};check(LocationSessions.isEnded(share!!.getString("id")));checks++
            val total=updates.size;mock(35.6915);Thread.sleep(1200);check(updates.size==total&&i.targetContext.getSystemService(NotificationManager::class.java).activeNotifications.none{it.id==LocationShareService.NOTIFICATION});checks++;capture("location-stopped")
            File(folder,"result.json").writeText(JSONObject().put("passed",true).put("checks",checks).put("api",Build.VERSION.SDK_INT).put("apkSha256",hash(File(i.targetContext.applicationInfo.sourceDir).readBytes())).put("realSystemGpsProvider",true).put("actualPermissionDialog",true).put("realTinkPrivateAndLive",true).put("foregroundNotificationStop",true).put("updatesAtHome",true).put("viewerNoGps",true).put("realRequests",0).put("physicalDevices",false).toString(2))
            return "PASS native location crypto/system GPS/picker/live foreground/stop: $checks checks"
        }catch(e:Throwable){runCatching{capture("failure")};File(folder,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally{
            ui{LocationShareService.instance?.end();activity?.finish();LocationSessions.clear();Hub.service?.stopSelf();Outbox.stop()}
            if(mocked)runCatching{manager.removeTestProvider(LocationManager.GPS_PROVIDER)}
            if(oldMode=="null")shell("settings delete secure location_mode")else shell("settings put secure location_mode $oldMode")
            shell("appops set cn.qingliao.app android:mock_location default")
            ui{Hub.token="";Hub.me=JSONObject();ChatCrypto.clear();Hub.http=oldHttp;AppUpdates.transport=oldUpdates}
        }
    }
}
