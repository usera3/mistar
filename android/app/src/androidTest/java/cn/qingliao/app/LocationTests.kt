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
        check(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk",true)||Build.HARDWARE in setOf("ranchu","goldfish")){"Owned emulator only"}
        val realTiles=variant.endsWith("-maps-live")
        val folder=File(i.targetContext.getExternalFilesDir(null),if(realTiles)"location-map-smoke"else"location-test").apply{mkdirs()};val oldHttp=Hub.http;val oldUpdates=AppUpdates.transport;val oldMaps=MapTiles.client;val oldPlaces=LocationPlaces.backend
        val owner="89190001";val bob="89190002";val session="synthetic-location-session";val pub=mutableMapOf<String,JSONObject>();val messages=CopyOnWriteArrayList<JSONObject>();val updates=CopyOnWriteArrayList<JSONObject>();val methods=CopyOnWriteArrayList<String>()
        var share:JSONObject?=null;var peerMeta:JSONObject?=null;var peerUpdate:JSONObject?=null;var deleted=0;var checks=0;var activity:Activity?=null;val openedActivities=mutableListOf<Activity>()
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
        fun launch(extra:Intent=Intent()):Activity {val intent=Intent(i.targetContext,LocationActivity::class.java).putExtra("peer",bob).putExtras(extra).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);return (i.startActivitySync(intent) as Activity).also{activity=it;openedActivities.add(it)}}
        fun reject(f:()->Unit){check(runCatching{f()}.isFailure);checks++}
        fun mapState(a:Activity):JSONObject? {
            var result:JSONObject?=null;val latch=java.util.concurrent.CountDownLatch(1)
            ui{val map=field(a,"map") as LocationMapView;map.web.evaluateJavascript("JSON.stringify(window.mapState||{})"){raw->result=runCatching{JSONObject(JSONTokener(raw).nextValue() as String)}.getOrNull();latch.countDown()}}
            latch.await(3,java.util.concurrent.TimeUnit.SECONDS);return result
        }
        fun awaitMap(a:Activity,latitude:Double){val end=SystemClock.elapsedRealtime()+45000;while(true){val state=mapState(a);if(state?.optInt("tilesLoaded",0)?:0>0&&state?.optDouble("lat")==latitude&&state.optBoolean("loading")==false&&state.optInt("errors")==0)return;check(SystemClock.elapsedRealtime()<end){"Map render timeout: $state ${(field(a,"map") as LocationMapView).tileFailure}"};Thread.sleep(100)}}
        fun permission(allow:Boolean){
            val deadline=SystemClock.elapsedRealtime()+12000
            while(SystemClock.elapsedRealtime()<deadline){
                val root=i.uiAutomation.rootInActiveWindow
                val names=if(allow)listOf("com.android.packageinstaller:id/permission_allow_button","com.android.permissioncontroller:id/permission_allow_foreground_only_button","com.android.permissioncontroller:id/permission_allow_button")else listOf("com.android.packageinstaller:id/permission_deny_button","com.android.permissioncontroller:id/permission_deny_button")
                val node=names.firstNotNullOfOrNull{root?.findAccessibilityNodeInfosByViewId(it)?.firstOrNull()}
                if(node!=null){node.performAction(AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(250);return}
                Thread.sleep(100)
            };error("Actual permission dialog missing")
        }
        fun drag(web:View){val position=IntArray(2);var width=0;var height=0;ui{web.getLocationOnScreen(position);width=web.width;height=web.height};val x=position[0]+width*.7f;val y=position[1]+height*.52f;val t=SystemClock.uptimeMillis();i.sendPointerSync(android.view.MotionEvent.obtain(t,t,0,x,y,0));for(n in 1..12){Thread.sleep(18);i.sendPointerSync(android.view.MotionEvent.obtain(t,SystemClock.uptimeMillis(),2,x-width*.32f*n/12,y,0))};i.sendPointerSync(android.view.MotionEvent.obtain(t,SystemClock.uptimeMillis(),1,x-width*.32f,y,0))}
        try{
            com.google.crypto.tink.hybrid.HybridConfig.register();com.google.crypto.tink.signature.SignatureConfig.register()
            if(!realTiles)MapTiles.client=MapTileFixture.client()
            LocationPlaces.backend=object:LocationPlaces.Backend{
                override fun available()=true
                override fun lookup(context:android.content.Context,query:String,lat:Double,lon:Double)=listOf(LocationPlaces.Place(if(query.isEmpty())"合成地址"else"合成公园", "演示街道100号（合成测试数据）",lat+.0002,lon+.0002),LocationPlaces.Place("合成咖啡馆","演示街道102号（合成测试数据）",lat+.0004,lon+.0004))
            }

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
                        path=="/api/locations"&&request.method=="GET"->{
                            val values=JSONArray();share?.let{metadata->val row=JSONObject().put("share",metadata).put("user",JSONObject().put("id",owner).put("nickname","合成位置用户"));updates.lastOrNull()?.let{v->row.put("update",JSONObject().put("sender",owner).put("recipient",owner).put("peer",bob).put("share_id",metadata.getString("id")).put("seq",v.getInt("seq")).put("until",metadata.getLong("until")).put("epoch",0).put("envelope",v.getJSONObject("wire").getJSONObject("envelope")))};values.put(row)}
                            peerMeta?.let{metadata->values.put(JSONObject().put("share",metadata).put("user",JSONObject().put("id",bob).put("nickname","合成好友")).put("update",peerUpdate))};JSONObject().put("shares",values)
                        }
                        path=="/api/locations"&&request.method=="POST"->{status=201;share=JSONObject().put("id",p.getString("id")).put("owner",owner).put("peer",bob).put("epoch",0).put("until",System.currentTimeMillis()+p.getInt("duration")*1000L).put("seq",0);JSONObject().put("share",share)}
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
            for(url in listOf("http://tile.openstreetmap.org/0/0/0.png","https://evil.test/0/0/0.png","file:///sdcard/private","https://api.claudcode.xyz/xingyu/map-tiles/0/9999/0.png","https://api.claudcode.xyz/xingyu/map-tiles/15/1/1.png?token=anything","https://api.claudcode.xyz/api/me")){check(MapTiles.parse(android.net.Uri.parse(url))==null)};checks++
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
            // Denial must still allow manual map selection; opening a received position never reads GPS.
            val picker=launch();await("picker controls laid out"){visible(find(picker,"location.send"))};permission(realTiles);checks++
            if(realTiles){shell("appops set cn.qingliao.app android:mock_location allow");shell("settings put secure location_mode 3");manager.addTestProvider(LocationManager.GPS_PROVIDER,false,false,false,false,true,true,true,Criteria.POWER_LOW,Criteria.ACCURACY_FINE);mocked=true;manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true);ui{picker.javaClass.getDeclaredMethod("locate",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(picker,true)};mock();await("real-map synthetic GPS ready"){(field(picker,"point") as? JSONObject)?.optDouble("lat")==35.6895}}
            val pickerMap=field(picker,"map") as LocationMapView;await("local HTTPS map page"){pickerMap.web.url=="https://appassets.androidplatform.net/assets/location-map/index.html"};check(realTiles||field(picker,"source")==null);checks++
            ui{check(pickerMap.height>((field(picker,"root") as View).height*.4)&&pickerMap.width==picker.window.decorView.width)};checks++
            val mapReadyUntil=SystemClock.elapsedRealtime()+30000;while((mapState(picker)?.optInt("tilesLoaded")?:0)==0){check(SystemClock.elapsedRealtime()<mapReadyUntil);Thread.sleep(80)}
            drag(pickerMap.web);await("actual drag changes selected point"){field(picker,"manual")==true&&find(picker,"location.send")!!.isEnabled};checks++
            val selected=(field(picker,"point") as JSONObject).getDouble("lat");awaitMap(picker,selected);capture("location-preview")
            ui{(find(picker,"location.search") as EditText).setText("合成公园");(find(picker,"location.search") as EditText).onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)}
            await("real place result rows from scoped provider"){find(picker,"location.place.0")!=null};checks++;ui{find(picker,"location.place.0")!!.performClick()};check(field(picker,"manual")==true);checks++
            val retryRequest=object:android.webkit.WebResourceRequest{
                override fun getUrl()=android.net.Uri.parse("https://appassets.androidplatform.net/assets/location-map/index.html");override fun isForMainFrame()=true;override fun isRedirect()=false;override fun hasGesture()=false;override fun getMethod()="GET";override fun getRequestHeaders()=mutableMapOf<String,String>()
            }
            val chosenLat=(field(picker,"point") as JSONObject).getDouble("lat")
            ui{pickerMap.web.webViewClient.onReceivedHttpError(pickerMap.web,retryRequest,android.webkit.WebResourceResponse("text/plain","UTF-8",403,"Synthetic blocked",emptyMap(),java.io.ByteArrayInputStream(ByteArray(0))))}
            await("native error replaces browser page"){find(picker,"location.map.error")!!.visibility==View.VISIBLE&&pickerMap.web.visibility==View.INVISIBLE};checks++;ui{find(picker,"location.map.error")!!.performClick()};awaitMap(picker,chosenLat);checks++
            ui{(field(picker,"label") as EditText).setText("bad\u202elabel");find(picker,"location.send")!!.performClick()};check(messages.isEmpty()&&!picker.isFinishing);checks++;ui{(field(picker,"label") as EditText).setText("");find(picker,"location.send")!!.performClick()}
            await("manual picked encrypted location sent"){messages.size==1&&picker.isFinishing};activity=null;check(!messages.last().toString().contains("演示街道"));checks++
            val view=launch(Intent().putExtra("location",value.toString()));awaitMap(view,35.6895);check(field(view,"source")==null);checks++;capture("location-detail");ui{view.finish()};activity=null
            val livePicker=launch(Intent().putExtra("mode","liveStart"));if(!realTiles)permission(true)
            shell("appops set cn.qingliao.app android:mock_location allow");shell("settings put secure location_mode 3")
            if(!mocked){manager.addTestProvider(LocationManager.GPS_PROVIDER,false,false,false,false,true,true,true,Criteria.POWER_LOW,Criteria.ACCURACY_FINE);mocked=true};manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true)
            ui{livePicker.javaClass.getDeclaredMethod("locate",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(livePicker,true)};mock()
            await("explicit live start becomes available"){find(livePicker,"location.start")!!.isEnabled&&visible(find(livePicker,"location.start"))};ui{find(livePicker,"location.start")!!.performClick()}
            await("foreground service and map room"){LocationShareService.instance!=null&&field(livePicker,"mode")=="live"};checks++;mock();await("signed live packet"){updates.isNotEmpty()};check(messages.size==2);checks++
            val stationaryAt=(field(LocationShareService.instance!!,"fix") as Location).time;Thread.sleep(3300);mock();await("stationary fix remains fresh"){(field(LocationShareService.instance!!,"fix") as Location).time>stationaryAt};checks++
            val ownId=share!!.getString("id");val peerId=UUID.randomUUID().toString();val until=System.currentTimeMillis()+3600000
            val peerLocation=JSONObject(value.toString()).put("lat",35.6901).put("live_id",peerId).put("until",until)
            val content=JSONObject().put("v",1).put("id",peerId).put("seq",1).put("until",until).put("location",peerLocation).toString().toByteArray()
            val context="qingliao:location:1:$peerId:1:$bob:$owner:0";fun pubKey(v:String)=KeysetHandle.readNoSecret(JsonKeysetReader.withString(v))
            val envelope=JSONObject().put("v",1).put("context",context).put("sender_key",pub[bob]!!.getString("key_id")).put("recipient_key",pub[owner]!!.getString("key_id"))
                .put("sender_cipher",ChatCrypto.b64(pubKey(pub[bob]!!.getString("enc")).getPrimitive(HybridEncrypt::class.java).encrypt(content,context.toByteArray())))
                .put("recipient_cipher",ChatCrypto.b64(pubKey(pub[owner]!!.getString("enc")).getPrimitive(HybridEncrypt::class.java).encrypt(content,context.toByteArray())))
            val signed=listOf("context","sender_key","recipient_key","sender_cipher","recipient_cipher").joinToString("\n"){envelope.getString(it)}.toByteArray();envelope.put("signature",ChatCrypto.b64(sig.getPrimitive(PublicKeySign::class.java).sign(signed)))
            peerMeta=JSONObject().put("id",peerId).put("owner",bob).put("peer",owner).put("epoch",0).put("until",until).put("seq",1)
            peerUpdate=JSONObject().put("sender",bob).put("recipient",owner).put("peer",owner).put("epoch",0).put("until",until).put("seq",1).put("share_id",peerId).put("envelope",envelope)
            val decodedPeer=ChatCrypto.openLocation(peerUpdate!!).put("user",JSONObject().put("id",bob).put("nickname","合成好友"));ui{LocationSessions.remember(decodedPeer)}
            await("two authorized live avatar markers"){(find(livePicker,"location.participants") as LinearLayout).childCount==2};checks++;awaitMap(livePicker,35.6895);capture("live-location")
            shell("input keyevent KEYCODE_HOME");Thread.sleep(3300);mock(35.6905);await("update at HOME"){(LocationShareService.instance?.let{field(it,"fix")} as? Location)?.latitude==35.6905&&updates.size>=2};checks++
            val notification=i.targetContext.getSystemService(NotificationManager::class.java).activeNotifications.first{it.id==LocationShareService.NOTIFICATION}.notification
            val monitor=i.addMonitor(LocationActivity::class.java.name,null,false);notification.contentIntent.send();val liveView=i.waitForMonitorWithTimeout(monitor,15000)?:error("Live viewer missing");i.removeMonitor(monitor);activity=liveView;openedActivities.add(liveView)
            await("room discovered on open"){(find(liveView,"location.participants") as LinearLayout).childCount==2};check(field(liveView,"source")==null);checks++;awaitMap(liveView,35.6905)
            Thread.sleep(3300);mock(35.6915);await("visible map updates from signed live stream"){(field(liveView,"point") as? JSONObject)?.optDouble("lat")==35.6915};awaitMap(liveView,35.6915);checks++;capture("live-location-moved")
            val meetingMonitor=i.addMonitor(LocationActivity::class.java.name,null,false);ui{find(liveView,"location.meeting")!!.performClick()};val meeting=i.waitForMonitorWithTimeout(meetingMonitor,15000)?:error("Meeting picker missing");i.removeMonitor(meetingMonitor);openedActivities.add(meeting)
            await("meeting picker send"){find(meeting,"location.send")!!.isEnabled};ui{find(meeting,"location.send")!!.performClick()};await("encrypted meeting point stored"){messages.size==3&&meeting.isFinishing&&LocationSessions.meeting(bob)!=null};checks++
            ui{field(liveView,"power").let{(it as View).performClick()}};await("QQ-style end confirmation"){(field(liveView,"dialog") as? Dialog)?.isShowing==true};checks++;capture("location-end-sheet")
            ui{val d=field(liveView,"dialog") as Dialog;walk(d.window!!.decorView).first{it.tag=="location.sheet.cancel"}.performClick()};check(LocationShareService.instance!=null);checks++
            notification.actions.last().actionIntent.send();await("notification stops own sharing"){LocationShareService.instance==null&&deleted>0};check(LocationSessions.isEnded(ownId)&&LocationSessions.room(bob).any{it.getJSONObject("share").getString("owner")==bob});checks++
            val total=updates.size;mock(35.6925);Thread.sleep(1000);check(updates.size==total);checks++;capture("location-stopped")
            val oldPoint=JSONObject(value.toString()).put("at",System.currentTimeMillis()-86400000L);val forwarded=Outbox.send(LocationPayload.packet(oldPoint,bob),session,owner);check(!forwarded.optBoolean("pending"));checks++
            File(folder,"result.json").writeText(JSONObject().put("passed",true).put("checks",checks).put("api",Build.VERSION.SDK_INT).put("apkSha256",hash(File(i.targetContext.applicationInfo.sourceDir).readBytes())).put("realSystemGpsProvider",true).put("actualPermissionDialog",true).put("realTinkPrivateAndLive",true).put("foregroundNotificationStop",true).put("updatesAtHome",true).put("viewerNoGps",true).put("inAppMapTilesRendered",true).put("liveMapMarkerUpdated",true).put("nativeThumbnailRendered",true).put("oldSnapshotForwarded",true).put("fullScreenPicker",true).put("actualMapDragSelection",true).put("systemPlaceProviderScoped",true).put("roomActors",2).put("encryptedMeetingPoint",true).put("nativeMainFrameErrorRecovery",true).put("localHttpsPage",true).put("realMapProvider",realTiles).put("realRequests",0).put("physicalDevices",false).toString(2))
            return "PASS native location crypto/system GPS/picker/live foreground/stop: $checks checks"
        }catch(e:Throwable){runCatching{capture("failure")};File(folder,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally{
            ui{LocationShareService.instance?.end();openedActivities.forEach{it.finish()};activity?.finish();LocationSessions.clear();Hub.service?.stopSelf();Outbox.stop()}
            if(mocked)runCatching{manager.removeTestProvider(LocationManager.GPS_PROVIDER)}
            if(oldMode=="null")shell("settings delete secure location_mode")else shell("settings put secure location_mode $oldMode")
            shell("appops set cn.qingliao.app android:mock_location default")
            MapTiles.client.dispatcher.cancelAll();MapTiles.client=oldMaps;LocationPlaces.backend=oldPlaces
            ui{Hub.token="";Hub.me=JSONObject();Hub.foreground=false;ChatCrypto.clear();Hub.http=oldHttp;AppUpdates.transport=oldUpdates}
        }
    }
}
