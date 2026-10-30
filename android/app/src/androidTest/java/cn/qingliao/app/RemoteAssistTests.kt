package cn.qingliao.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import org.webrtc.DataChannel
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit system and app consent, real DTLS/SCTP and OS gestures; owned emulator only. */
internal object RemoteAssistTests {
    fun run(i:Instrumentation,layout:String):String {
        val automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        val folder=File(i.targetContext.getExternalFilesDir(null),"remote-assist-test").apply{mkdirs()}
        val oldHttp=Hub.http;val oldUpdates=AppUpdates.transport;val owner="86000001";val other="86000002";val session="synthetic-remote-assist-session"
        var checks=0;var fixture:AssistPeerFixture?=null;var a:MainActivity?=null;var report:JSONObject?=null
        fun ui(f:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())f()else i.runOnMainSync(f)}
        fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        fun set(o:Any,n:String,v:Any?){o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
        fun invoke(o:Any,n:String,vararg args:Any?){ui{o.javaClass.declaredMethods.first{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(o,*args)}}
        fun stage(s:String){File(folder,"progress.txt").writeText(s)}
        fun await(label:String,timeout:Long=30000,p:()->Boolean){stage(label);val end=SystemClock.elapsedRealtime()+timeout;while(!p()){check(SystemClock.elapsedRealtime()<end){"Remote assistance timeout: $label"};Thread.sleep(50)};i.waitForIdleSync()}
        fun state(p:()->Boolean):Boolean{var yes=false;ui{yes=p()};return yes}
        fun nodes(n:AccessibilityNodeInfo?,depth:Int=0):List<AccessibilityNodeInfo> = if(n==null||depth>22)emptyList()else listOf(n)+(0 until minOf(n.childCount,80)).flatMap{nodes(n.getChild(it),depth+1)}
        fun allNodes():List<AccessibilityNodeInfo> = automation.windows.flatMap{nodes(it.root)}.ifEmpty{nodes(automation.rootInActiveWindow)}
        fun clickNode(n:AccessibilityNodeInfo){var v=n;while(!v.isClickable&&v.parent!=null)v=v.parent;check(v.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
        fun capture(name:String){i.waitForIdleSync();Thread.sleep(250);val b=automation.takeScreenshot();File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        fun clickDialog(label:String){await("dialog $label"){allNodes().any{it.text?.toString()==label}};clickNode(allNodes().first{it.text?.toString()==label})}
        fun openMain(){i.targetContext.startActivity(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_USER_ACTION));await("main resumed"){a?.let{state{field(it,"resumed")==true&&!it.isInPictureInPictureMode}}==true}}
        fun target(password:Boolean=false){
            val intent=Intent().setComponent(ComponentName("cn.qingliao.app.test",if(password)AssistPasswordTargetActivity::class.java.name else AssistTargetActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.targetContext.startActivity(intent);await("synthetic target window"){allNodes().any{it.packageName?.toString()=="cn.qingliao.app.test"&&it.contentDescription?.toString()==if(password)"合成密码输入"else"合成点按目标"}}
        }
        fun targetNode(description:String)=allNodes().firstOrNull{it.packageName?.toString()=="cn.qingliao.app.test"&&it.contentDescription?.toString()?.startsWith(description)==true}
        fun point(n:AccessibilityNodeInfo):PointF{val r=Rect();n.getBoundsInScreen(r);return PointF(r.left+r.width()*.25f,r.exactCenterY())}
        fun stopChip():View?{val svc=AssistAccessibilityService::class.java.getDeclaredField("instance").apply{isAccessible=true}.get(null)?:return null;return field(svc,"chip") as? View}
        fun chipVisible()=state{stopChip()?.let{it.isShown&&it.width>0&&it.height>0}==true}
        fun localTap(p:PointF){val down=SystemClock.uptimeMillis();for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){val e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,p.x,p.y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;automation.injectInputEvent(e,true);e.recycle()};i.waitForIdleSync()}
        fun systemGrant(){
            await("system projection grant"){allNodes().any{it.viewIdResourceName=="android:id/button1"}}
            capture("projection-consent");clickNode(allNodes().first{it.viewIdResourceName=="android:id/button1"&&it.isEnabled})
        }
        fun prepareCall(id:String,fromPeer:Boolean):AssistPeerFixture {
            ui{Hub.call=JSONObject().put("id",id).put("caller",owner).put("callee",other).put("video",true).put("accepted",true);Hub.callUser=JSONObject().put("id",other).put("nickname","合成协助者");Hub.phase="connecting"}
            val h=AssistPeerFixture(i,id,fromPeer);fixture=h;h.start()
            await("local SCTP peer connected"){state{h.engine.mediaConnected&&h.control?.state()==DataChannel.State.OPEN}}
            ui{Hub.phase="connected";Hub.connectedAt=SystemClock.elapsedRealtime();invoke(a!!,"showCall")}
            await("assistance hello"){state{h.engine.assist.peerSupported}}
            return h
        }
        try {
            ui{Hub.token=session;Hub.me=JSONObject().put("id",owner).put("nickname","合成共享者").put("admin_password_change_required",true);Hub.connected=false
                Hub.http=OkHttpClient.Builder().addInterceptor{c->check(c.request().url.host in setOf("47.245.2.254","api.claudcode.xyz"));Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic assistance fixture").body(JSONObject().put("contacts",JSONArray()).put("conversations",JSONArray()).toString().toResponseBody()).build()}.build();AppUpdates.transport=Hub.http}
            a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val cfg=android.content.res.Configuration(a!!.resources.configuration)
            check(if(layout=="phone")!CompactUi.enabled(a!!)&&cfg.screenWidthDp>=360&&cfg.screenHeightDp>=600 else CompactUi.enabled(a!!));checks++
            // Geometry and parser boundaries do not depend on screen resolution or private content.
            val g=AssistWire.Geometry(400,800,0);check(g.normalized(100f,50f,800,800)==null);val center=g.normalized(400f,400f,800,800)!!;check(kotlin.math.abs(center.x-.5f)<.001&&kotlin.math.abs(center.y-.5f)<.001);checks++
            check(runCatching{AssistWire.parse(("{".repeat(30)+"}".repeat(30)).toByteArray())}.isFailure);check(runCatching{AssistWire.Input("shell")}.isFailure);check(runCatching{AssistWire.Input("tap",Float.NaN)}.isFailure);check(runCatching{AssistWire.Geometry(0,800,0)}.isFailure);checks++
            check(!AssistAccessibilityService.available());checks++
            // Enable through actual Android settings and disclosure. Do not write secure settings to grant it.
            i.targetContext.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
            await("accessibility settings active"){automation.rootInActiveWindow?.packageName?.toString()=="com.android.settings"}
            stage("find helper in accessibility settings")
            repeat(8){if(allNodes().none{it.packageName?.toString()=="com.android.settings"&&it.text?.toString()=="弥星远程协助"}){allNodes().firstOrNull{it.packageName?.toString()=="com.android.settings"&&it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(150)}}
            await("helper label"){allNodes().any{it.text?.toString()=="弥星远程协助"}};clickNode(allNodes().first{it.text?.toString()=="弥星远程协助"})
            await("service toggle"){allNodes().any{it.className?.toString()=="android.widget.Switch"}};capture("accessibility-settings");clickNode(allNodes().first{it.className?.toString()=="android.widget.Switch"})
            await("system helper consent"){allNodes().any{it.viewIdResourceName=="android:id/button1"}};capture("accessibility-consent");clickNode(allNodes().first{it.viewIdResourceName=="android:id/button1"})
            await("gesture helper bound"){AssistAccessibilityService.available()};checks++
            val manager=i.targetContext.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            val serviceInfo=manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).first{it.resolveInfo.serviceInfo.name==AssistAccessibilityService::class.java.name}
            check(serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT==0&&serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES!=0);checks++
            openMain();val host=prepareCall("synthetic-assistance-host",false)
            invoke(a!!,"toggleScreenSharing");systemGrant();await("host screen shared"){state{host.engine.sharingScreen&&ScreenSharing.controlShareId(host.engine)!=null}};checks++
            target();await("tap target"){targetNode("合成点按目标")!=null};val tapPoint=point(targetNode("合成点按目标")!!)
            host.send("input",JSONObject().put("lease","0".repeat(32)).put("ticket","1".repeat(32)).put("s",1).put("input",AssistWire.Input("tap",.25f,.3f).json()));Thread.sleep(250);check(targetNode("合成点按目标")!!.text.toString()=="点按 0");checks++
            localTap(tapPoint);await("owner still controls"){targetNode("合成点按目标")?.text?.toString()=="点按 1"};checks++
            fun approve():JSONObject {
                openMain();Thread.sleep(10500);val request=UUID.randomUUID().toString().replace("-","");host.send("request",JSONObject().put("request",request))
                await("request awaiting owner"){state{host.engine.assist.incomingRequest==request}};check(!host.engine.assist.hosting)
                invoke(a!!,"assistanceMenu");capture("per-call-consent");clickDialog("允许本次")
                await("owner explicitly granted"){state{host.engine.assist.hosting}&&host.packets.any{it.optString("t")=="grant"&&it.optString("request")==request}}
                return host.packets.last{it.optString("t")=="grant"&&it.optString("request")==request}
            }
            ui{ThemeStore.save(i.targetContext,0xFFFFC107.toInt())};host.autoAcceptGrant=false;var grant=approve();checks++
            val initialGeometry=AssistWire.geometry(grant.getJSONObject("geometry"));check(!host.engine.assist.authorizes(grant.getString("lease"),initialGeometry));host.send("accepted",JSONObject().put("lease",grant.getString("lease")));await("peer acknowledges current lease"){state{host.engine.assist.authorizes(grant.getString("lease"),initialGeometry)}};host.autoAcceptGrant=true;checks++
            automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);Thread.sleep(900);check(host.engine.assist.hosting);checks++
            target();await("external stop chip"){chipVisible()};capture("owner-stop-overlay")
            check(state{(stopChip() as TextView).currentTextColor==ThemePalette(0xFFFFC107.toInt()).onPrimary});ui{ThemeStore.save(i.targetContext,ThemeStore.DEFAULT)};checks++
            var sequence=0
            fun command(input:AssistWire.Input,token:String?=null):JSONObject {
                Thread.sleep(400);val n=++sequence;val lease=grant.getString("lease");host.send("input",JSONObject().put("lease",lease).put("ticket",token?:host.tickets[lease]?:grant.getString("ticket")).put("s",n).put("input",input.json()))
                await("gesture ack $n"){host.packets.any{it.optString("t")=="ack"&&it.optInt("s")==n&&it.optString("lease")==lease}}
                return host.packets.last{it.optString("t")=="ack"&&it.optInt("s")==n&&it.optString("lease")==lease}
            }
            fun normalized(p:PointF):PointF{val geo=AssistWire.geometry(grant.getJSONObject("geometry"));return PointF(p.x/(geo.width-1),p.y/(geo.height-1))}
            val touch=normalized(point(targetNode("合成点按目标")!!));check(command(AssistWire.Input("tap",touch.x,touch.y)).getString("result")=="ok");await("real remote tap"){targetNode("合成点按目标")?.text?.toString()=="点按 2"};checks++
            val long=normalized(point(targetNode("合成长按目标")!!));check(command(AssistWire.Input("long",long.x,long.y,duration=700)).getString("result")=="ok");await("real remote long press"){targetNode("合成长按目标")?.text?.toString()=="长按 1"};checks++
            val swipe=targetNode("合成滑动目标")!!;val area=Rect();swipe.getBoundsInScreen(area);val from=normalized(PointF(area.left+area.width()*.25f,area.top+area.height()*.8f));val to=normalized(PointF(area.left+area.width()*.25f,area.top+area.height()*.2f))
            check(command(AssistWire.Input("swipe",from.x,from.y,to.x,to.y,400)).getString("result")=="ok");await("real remote swipe"){targetNode("合成滑动目标 1")!=null};checks++
            Thread.sleep(2800);check(command(AssistWire.Input("tap",touch.x,touch.y),grant.getString("ticket")).getString("result")=="blocked");check(targetNode("合成点按目标")!!.text.toString()=="点按 2");checks++
            check(command(AssistWire.Input("tap",0f,0f)).getString("result")=="blocked");checks++
            val authorizedGeometry=AssistWire.geometry(grant.getJSONObject("geometry"));val authorizedLease=grant.getString("lease")
            ui{val old=Hub.token;try{Hub.token="another-synthetic-session";check(!host.engine.assist.authorizes(authorizedLease,authorizedGeometry))}finally{Hub.token=old}};checks++
            ui{val old=Hub.call;try{Hub.call=JSONObject(old!!.toString()).put("id","different-synthetic-call");check(!host.engine.assist.authorizes(authorizedLease,authorizedGeometry))}finally{Hub.call=old}};checks++
            ui{val old=Hub.callUser;try{Hub.callUser=JSONObject(old.toString()).put("bot",true);check(!host.engine.assist.authorizes(authorizedLease,authorizedGeometry))}finally{Hub.callUser=old}};checks++
            // Duplicate command numbers revoke instead of replaying a potentially destructive operation.
            host.send("input",JSONObject().put("lease",grant.getString("lease")).put("ticket",host.tickets[grant.getString("lease")]).put("s",sequence).put("input",AssistWire.Input("tap",touch.x,touch.y).json()))
            await("replay revokes"){!host.engine.assist.hosting};checks++
            grant=approve();target(true);await("password target"){targetNode("合成密码输入")!=null};localTap(point(targetNode("合成密码输入")!!));await("password focus revokes"){!host.engine.assist.hosting};checks++
            grant=approve();target();await("stop chip before revoke"){chipVisible()};val stopPoint=PointF();ui{val chip=stopChip()!!;val p=IntArray(2);chip.getLocationOnScreen(p);stopPoint.set(p[0]+chip.width-3f,p[1]+chip.height/2f)};localTap(stopPoint);await("owner stop revokes"){!host.engine.assist.hosting};checks++
            grant=approve();target();val notice=i.targetContext.getSystemService(NotificationManager::class.java).activeNotifications.first{it.id==108};notice.notification.actions.first{it.title.toString()=="停止共享"}.actionIntent.send()
            await("share stop revokes"){!host.engine.sharingScreen&&!host.engine.assist.hosting};checks++
            openMain();invoke(a!!,"releaseRenderers");host.close();fixture=null;ui{Hub.call=null;Hub.phase=""}
            checks+=AssistControllerTests.run(i,a!!,folder)
            val sha=MessageDigest.getInstance("SHA-256").digest(File(i.targetContext.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
            report=JSONObject().put("passed",true).put("checks",checks).put("api",Build.VERSION.SDK_INT).put("apkSha256",sha).put("widthDp",cfg.screenWidthDp).put("heightDp",cfg.screenHeightDp).put("phoneLayout",layout=="phone").put("actualSystemAccessibilityConsent",true).put("actualSystemProjectionConsent",true).put("contentRetrievalCapability",false).put("realOsTapLongSwipe",true).put("ownerStillControls",true).put("staleAndReplayRejected",true).put("localDtlsSctp",true).put("realUserCalls",0).put("physicalDevices",false)
            return "PASS remote assistance consent, native gestures and revocation: $checks checks"
        }catch(e:Throwable){runCatching{capture("failure")};File(folder,"failure.txt").writeText(e.stackTraceToString());File(folder,"ui.txt").writeText(allNodes().joinToString("\n"){"${it.packageName}|${it.viewIdResourceName}|${it.className}|${it.text}|${it.contentDescription}"});throw e}
        finally{
            ui{a?.let{invoke(it,"releaseRenderers")}};fixture?.close()
            ui{val svc=AssistAccessibilityService::class.java.getDeclaredField("instance").apply{isAccessible=true}.get(null) as? AccessibilityService;svc?.disableSelf();Hub.call=null;Hub.phase="";a?.finish();Hub.listener=null;Hub.token="";Hub.me=JSONObject();Hub.http=oldHttp;AppUpdates.transport=oldUpdates}
            await("helper disabled after fixture"){state{!AssistAccessibilityService.available()}}
            check(i.targetContext.getSystemService(android.view.accessibility.AccessibilityManager::class.java).getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).none{it.resolveInfo.serviceInfo.name==AssistAccessibilityService::class.java.name})
            report?.let{it.put("helperDisabledAfterTest",true);File(folder,"result.json").writeText(it.toString(2))}
        }
    }
}
