package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import org.webrtc.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Real GPU surfaces and system PiP; all pixels/accounts synthetic, no real call or capture device. */
internal object CallUiTests {
    fun run(i:Instrumentation,layout:String):String {
        val folder=File(i.targetContext.getExternalFilesDir(null),"call-ui-test").apply{mkdirs()}
        val owner="85000001";val session="synthetic-call-ui-session"
        val oldHttp=Hub.http;val oldUpdates=AppUpdates.transport
        fun ui(f:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())f()else i.runOnMainSync(f)}
        fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        fun set(o:Any,n:String,v:Any?){o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
        fun invoke(o:Any,n:String,vararg args:Any?){ui{o.javaClass.declaredMethods.first{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(o,*args)}}
        fun await(label:String,p:()->Boolean){val end=SystemClock.elapsedRealtime()+20000;while(true){var yes=false;ui{yes=p()};if(yes){i.waitForIdleSync();return};check(SystemClock.elapsedRealtime()<end){"Call UI timeout: $label"};Thread.sleep(40)}}
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun capture(name:String):Bitmap {i.waitForIdleSync();Thread.sleep(250);return i.uiAutomation.takeScreenshot().also{b->File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}}
        fun shell(s:String){i.uiAutomation.executeShellCommand(s).use{java.io.FileInputStream(it.fileDescriptor).readBytes()}}
        ui {
            Hub.token=session;Hub.me=JSONObject().put("id",owner).put("nickname","合成测试").put("admin_password_change_required",true);Hub.connected=false
            Hub.http=OkHttpClient.Builder().addInterceptor{c->check(c.request().url.host in setOf("47.245.2.254","api.claudcode.xyz"));Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic call UI only").body(JSONObject().put("contacts",JSONArray()).put("conversations",JSONArray()).toString().toResponseBody()).build()}.build();AppUpdates.transport=Hub.http
        }
        val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var engine:RtcEngine;lateinit var localSource:VideoSource;lateinit var remoteSource:VideoSource;lateinit var remoteTrack:VideoTrack
        var ready=false;var remoteDisposed=false;var checks=0;val feeding=AtomicBoolean(true);val frames=Executors.newSingleThreadScheduledExecutor()
        val c=JSONObject().put("id","synthetic-ui-call").put("video",true).put("caller",owner).put("callee","85000002").put("accepted",true)
        fun find(tag:String)=walk(a.window.decorView).firstOrNull{it.tag==tag}
        fun stage()=field(a,"callVideoStage") as CallVideoStage
        fun tap(v:View){var x=0f;var y=0f;ui{val r=Rect();check(v.getGlobalVisibleRect(r));x=r.centerX().toFloat();y=r.centerY().toFloat()};val now=SystemClock.uptimeMillis();for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){val e=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;i.sendPointerSync(e);e.recycle()};i.waitForIdleSync()}
        fun color(name:String,blue:Boolean){val b=capture(name);val p=b.getPixel(b.width/2,b.height*3/5);b.recycle();check(if(blue)Color.blue(p)>Color.red(p)+60 else Color.red(p)>180&&Color.green(p)>150&&Color.blue(p)<100){"Wrong GPU source pixels $p in $name"}}
        fun home(){shell("input keyevent 3");await("system picture in picture"){a.isInPictureInPictureMode};Thread.sleep(800);i.waitForIdleSync()}
        fun expand(){shell("am start -n cn.qingliao.app/.MainActivity --activity-reorder-to-front --activity-single-top");await("return from PiP"){!a.isInPictureInPictureMode&&field(a,"resumed")==true}}
        try {
            ui {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(i.targetContext).createInitializationOptions())
                engine=RtcEngine(i.targetContext){};val factory=PeerConnectionFactory.builder().createPeerConnectionFactory();set(engine,"factory",factory)
                localSource=factory.createVideoSource(false);remoteSource=factory.createVideoSource(false)
                val localTrack=factory.createVideoTrack("synthetic-yellow",localSource);remoteTrack=factory.createVideoTrack("synthetic-blue",remoteSource)
                set(engine,"videoSource",localSource);set(engine,"localVideo",localTrack);set(engine,"remoteVideo",remoteTrack);set(engine,"cameraEnabled",true);set(engine,"mediaConnected",true)
                localSource.capturerObserver.onCapturerStarted(true);remoteSource.capturerObserver.onCapturerStarted(true)
                Hub.call=c;Hub.callUser=JSONObject().put("id","85000002").put("nickname","画面测试");Hub.phase="connected";Hub.connectedAt=SystemClock.elapsedRealtime();Hub.rtc=engine;ready=true
                (field(engine,"audioSession") as CallAudio).start(true)
                invoke(a,"showCall")
            }
            frames.scheduleAtFixedRate({if(feeding.get())for((source,blue) in listOf(localSource to false,remoteSource to true)){
                val b=JavaI420Buffer.allocate(320,240)
                fun fill(buffer:java.nio.ByteBuffer,value:Int){for(n in 0 until buffer.capacity())buffer.put(n,value.toByte())}
                fill(b.dataY,if(blue)41 else 210);fill(b.dataU,if(blue)240 else 16);fill(b.dataV,if(blue)110 else 146)
                val frame=VideoFrame(b,0,System.nanoTime());try{source.capturerObserver.onFrameCaptured(frame)}finally{frame.release()}
            }},0,66,TimeUnit.MILLISECONDS)
            await("full stage measured"){stage().height>0};Thread.sleep(500)
            val cfg=android.content.res.Configuration(a.resources.configuration);val rootWidth=a.window.decorView.width;val rootHeight=a.window.decorView.height
            check(if(layout=="phone")!CompactUi.enabled(a)&&cfg.screenWidthDp>=360&&cfg.screenHeightDp>=600 else CompactUi.enabled(a));check(rootWidth==if(layout=="phone")1080 else 410);checks++
            check(stage().height>=rootHeight*.95&&stage().width==rootWidth){"Video still reserves layout space ${stage().width}x${stage().height} / $rootWidth x $rootHeight"};color("video-overlay",true);checks++
            val first=stage();tap(first);await("controls hidden"){find("call.controls")!!.visibility==View.GONE};color("video-fullscreen",true);check(stage()===first);checks++
            tap(find("call.preview")!!);await("local promoted"){stage().localIsMain};Thread.sleep(200);color("video-swapped",false);check(field(engine,"localView")===field(stage(),"full")&&field(engine,"remoteView")===field(stage(),"preview"));checks++
            repeat(8){ui{find("call.preview")!!.performClick()}};check(stage().localIsMain&&stage()===first&&engine.cameraEnabled&&!engine.sharingScreen);checks++
            tap(first);await("controls restored"){find("call.controls")!!.visibility==View.VISIBLE};ui{find("call.scale")!!.performClick()};check(!stage().filled);ui{find("call.scale")!!.performClick()};check(stage().filled);checks++
            tap(find("call.preview")!!);ui{set(engine,"remoteVideoMode","screen");Hub.emit(JSONObject().put("event","call.video"))};await("remote share complete mode"){!stage().filled};check(stage()===first&&!stage().localIsMain);capture("remote-screen-fit").recycle();ui{find("call.scale")!!.performClick()};check(stage().filled);color("remote-screen-fill",true);checks++
            ui{set(engine,"sharingScreen",true);set(engine,"cameraEnabled",false);Hub.emit(JSONObject().put("event","call.video"))};await("local sharing placeholder"){(field(stage(),"miniBlank") as android.widget.TextView).text.toString().contains("共享")};tap(find("call.preview")!!);check(stage().localIsMain&&find("call.main.placeholder")!!.visibility==View.VISIBLE);check(engine.sharingScreen);capture("local-screen-state").recycle();checks++
            ui{set(engine,"sharingScreen",false);set(engine,"cameraEnabled",true);set(engine,"remoteVideoMode","off");Hub.emit(JSONObject().put("event","call.video"))};tap(find("call.preview")!!);check(!stage().localIsMain&&find("call.main.placeholder")!!.visibility==View.VISIBLE);checks++
            ui{set(engine,"remoteVideoMode","camera");Hub.emit(JSONObject().put("event","call.video"))};await("camera restored"){find("call.main.placeholder")!!.visibility==View.GONE};check(stage()===first);checks++
            val supports=a.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
            if(supports){
                val mute=CallActions.pending("mute");home();val pipImage=capture("pip-video");var outsideVideo=0
                for(y in pipImage.height/4 until pipImage.height*4/5){val p=pipImage.getPixel(pipImage.width-3,y);if(Color.red(p)>220&&Color.green(p)>220&&Color.blue(p)<60||Color.red(p)<20&&Color.green(p)<20&&Color.blue(p)<20)outsideVideo++}
                pipImage.recycle();check(outsideVideo==0){"Preview surface leaked outside PiP: $outsideVideo pixels"};check(find("call.controls")!!.visibility==View.GONE&&find("call.preview")!!.visibility==View.GONE);checks++
                ui{mute.send()};await("PiP mute action"){engine.muted};checks++
                ui{Hub.call=JSONObject(c.toString()).put("id","different-synthetic-call");CallActions.pending("mute");mute.send()};Thread.sleep(200);check(engine.muted);checks++
                ui{Hub.call=c};expand();await("expanded controls accessible"){find("call.controls")?.visibility==View.VISIBLE};checks++
                ui{Hub.call=JSONObject(c.toString()).put("video",false);invoke(a,"showCall")};home();await("audio mini status"){find("call.pip.audio")?.visibility==View.VISIBLE};capture("pip-audio").recycle();checks++
                expand();ui{Hub.phase="incoming";Hub.ringUntil=SystemClock.elapsedRealtime()+45000;invoke(a,"showCall");invoke(a,"onUserLeaveHint")};Thread.sleep(150);check(!a.isInPictureInPictureMode);checks++
                check(!CallActions.valid(Intent().putExtra("owner",owner).putExtra("call_id",c.optString("id"))));val reject=CallActions.pending("reject");ui{Hub.call=JSONObject(c.toString()).put("id","new-incoming");reject.send()};Thread.sleep(200);check(Hub.call?.optString("id")=="new-incoming");checks++
                val expired=CallActions.pending("reject");ui{Hub.ringUntil=SystemClock.elapsedRealtime()-1;expired.send()};Thread.sleep(200);check(Hub.call?.optString("id")=="new-incoming");checks++
                ui{Hub.call=null;Hub.phase="";invoke(a,"showHome",false);invoke(a,"onUserLeaveHint")};Thread.sleep(200);check(!a.isInPictureInPictureMode);checks++
                ui{Hub.phase="connected";Hub.call=c;invoke(a,"showCall")};home();feeding.set(false);frames.shutdown();check(frames.awaitTermination(5,TimeUnit.SECONDS))
                // The synthetic remote source belongs to this test factory, unlike a real receiver.
                // Dispose it before hangup closes the factory's worker threads.
                ui{invoke(a,"releaseRenderers");set(engine,"remoteVideo",null);remoteTrack.dispose();remoteSource.dispose();remoteDisposed=true;CallActions.pending("hangup").send()};await("PiP hangup closes"){a.isFinishing&&Hub.call==null};checks++
            }
            val sha=MessageDigest.getInstance("SHA-256").digest(File(i.targetContext.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
            File(folder,"result.json").writeText(JSONObject().put("passed",true).put("checks",checks).put("api",Build.VERSION.SDK_INT).put("apkSha256",sha).put("widthDp",cfg.screenWidthDp).put("heightDp",cfg.screenHeightDp).put("rootWidthPx",rootWidth).put("rootHeightPx",rootHeight).put("phoneLayout",layout=="phone").put("gpuSyntheticPixels",true).put("realSystemPip",supports).put("realNetworkRequests",0).put("physicalDevices",false).put("fullStageNoReservedBars",true).put("swapDoesNotReplaceVideoSource",true).toString(2))
            return "PASS native fullscreen video, swap and PiP: $checks checks"
        }catch(e:Throwable){capture("failure").recycle();File(folder,"failure.txt").writeText(e.stackTraceToString());File(folder,"layout-failure.txt").writeText("safe="+field(a,"callSafeInsets")+" raw="+a.window.decorView.rootWindowInsets+"\n"+walk(a.window.decorView).filter{it.tag?.toString()?.startsWith("call.")==true}.joinToString("\n"){"${it.tag} ${it.left},${it.top} ${it.width}x${it.height} visible=${it.visibility}"});throw e}
        finally {
            feeding.set(false);frames.shutdownNow();frames.awaitTermination(5,TimeUnit.SECONDS)
            ui{invoke(a,"releaseRenderers");if(ready){if(!remoteDisposed){set(engine,"remoteVideo",null);remoteTrack.dispose();remoteSource.dispose()};engine.release()};Hub.rtc=null;Hub.call=null;Hub.phase="";CallActions.clear();a.finish();Hub.listener=null;Hub.token="";Hub.me=JSONObject();Hub.http=oldHttp;AppUpdates.transport=oldUpdates}
        }
    }
}
