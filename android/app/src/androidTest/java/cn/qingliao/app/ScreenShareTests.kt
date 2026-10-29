package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.*
import android.media.projection.MediaProjection
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import org.webrtc.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Owned emulators only: real system grant, capture and local DTLS-SRTP peers, synthetic pixels. */
internal object ScreenShareTests {
    fun run(i:Instrumentation):String {
        val oldHttp=Hub.http;val oldUpdates=AppUpdates.transport
        val folder=File(i.targetContext.getExternalFilesDir(null),"screen-share").apply{mkdirs()};val checks=JSONArray()
        fun stage(s:String){File(folder,"progress.txt").writeText(s)}
        fun ui(f:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())f()else i.runOnMainSync{f()}}
        fun field(o:Any,name:String)=o.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(o)
        fun invoke(o:Any,name:String,vararg args:Any?){val m=o.javaClass.declaredMethods.first{it.name==name&&it.parameterCount==args.size}.apply{isAccessible=true};ui{m.invoke(o,*args)}}
        fun await(label:String,condition:()->Boolean){stage(label);val end=SystemClock.elapsedRealtime()+60000;while(!condition()){check(SystemClock.elapsedRealtime()<end){"Screen sharing timeout: $label"};Thread.sleep(40)};i.waitForIdleSync()}
        fun capture(name:String){i.waitForIdleSync();Thread.sleep(150);val bitmap=i.uiAutomation.takeScreenshot();File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList()else listOf(n)+(0 until n.childCount).flatMap{nodes(n.getChild(it))}
        fun dump(){File(folder,"grant-tree.txt").writeText(nodes(i.uiAutomation.rootInActiveWindow).joinToString("\n"){"${it.packageName} | ${it.viewIdResourceName} | ${it.className} | ${it.text} | enabled=${it.isEnabled}"})}
        fun grant(allow:Boolean=true){
            await("waiting for Android projection permission"){nodes(i.uiAutomation.rootInActiveWindow).any{it.viewIdResourceName=="android:id/button1"||it.viewIdResourceName?.endsWith("start_button")==true}}
            dump();capture("system-grant")
            if(allow){
                val spinner=nodes(i.uiAutomation.rootInActiveWindow).firstOrNull{it.className?.toString()?.endsWith("Spinner")==true}
                if(spinner!=null){spinner.performAction(AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(250);dump()
                    nodes(i.uiAutomation.rootInActiveWindow).firstOrNull{it.text?.toString()?.let{s->s.contains("Entire screen",true)||s.contains("整个屏幕")||s.contains("全屏")}==true}?.let{item->var target=item;while(!target.isClickable&&target.parent!=null)target=target.parent;check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
                    Thread.sleep(200)
                }
            }
            val suffix=if(allow)"button1"else "button2"
            await("projection grant button enabled"){nodes(i.uiAutomation.rootInActiveWindow).any{it.isEnabled&&(it.viewIdResourceName=="android:id/$suffix"||allow&&it.viewIdResourceName?.endsWith("start_button")==true)}}
            nodes(i.uiAutomation.rootInActiveWindow).first{it.isEnabled&&(it.viewIdResourceName=="android:id/$suffix"||allow&&it.viewIdResourceName?.endsWith("start_button")==true)}.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        fun shell(command:String){i.uiAutomation.executeShellCommand(command).use{fd->java.io.FileInputStream(fd.fileDescriptor).readBytes()}}
        val localSignals=mutableListOf<JSONObject>();val remoteCandidates=mutableListOf<IceCandidate>();var remoteSet=false
        lateinit var engine:RtcEngine;var engineCreated=false;var receiver:PeerConnection?=null;var receiverFactory:PeerConnectionFactory?=null;var data:DataChannel?=null
        val count=AtomicInteger();val marked=AtomicInteger();val black=AtomicInteger();val lastW=AtomicInteger();val lastH=AtomicInteger();val frameBitmap=java.util.concurrent.atomic.AtomicReference<Bitmap?>();val receiverMode=java.util.concurrent.atomic.AtomicReference("")
        val colors=java.util.concurrent.atomic.AtomicReference("")
        val acceptFrames=java.util.concurrent.atomic.AtomicBoolean(true)
        val sink=VideoSink{frame->
            if(!acceptFrames.get())return@VideoSink
            val b=frame.buffer.toI420()?:return@VideoSink
            try{val top=b.dataY.get((b.height/3)*b.strideY+b.width/4).toInt() and 255;val bottom=b.dataY.get((b.height*3/4)*b.strideY+b.width/4).toInt() and 255
                lastW.set(b.width);lastH.set(b.height);count.incrementAndGet()
                colors.set("top=$top bottom=$bottom rotation=${frame.rotation}")
                val blueU=b.dataU.get((b.height/6)*b.strideU+b.width/8).toInt() and 255
                val yellowU=b.dataU.get((b.height*3/8)*b.strideU+b.width/8).toInt() and 255
                if(b.height>b.width&&bottom-top>60&&top in 35..130&&bottom>150&&blueU>165&&yellowU<100){
                    marked.incrementAndGet()
                    if(frameBitmap.get()==null){
                        val w=minOf(b.width,640);val h=b.height*w/b.width;val pixels=IntArray(w*h)
                        fun clamp(n:Int)=n.coerceIn(0,255)
                        for(y in 0 until h)for(x in 0 until w){val sx=x*b.width/w;val sy=y*b.height/h;val yy=(b.dataY.get(sy*b.strideY+sx).toInt() and 255)-16;val u=(b.dataU.get(sy/2*b.strideU+sx/2).toInt() and 255)-128;val v=(b.dataV.get(sy/2*b.strideV+sx/2).toInt() and 255)-128;pixels[y*w+x]=Color.rgb(clamp((298*yy+409*v+128) shr 8),clamp((298*yy-100*u-208*v+128) shr 8),clamp((298*yy+516*u+128) shr 8))}
                        frameBitmap.set(Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888))
                    }
                }
                if(top<36&&bottom<36)black.incrementAndGet()
            }finally{b.release()}
        }
        val owner="96006001";val call=JSONObject().put("id","synthetic-screen-call").put("caller",owner).put("callee","96006002").put("video",true).put("accepted",true).put("created",System.currentTimeMillis())
        ui{
            Hub.token="synthetic-screen-session";Hub.me=JSONObject().put("id",owner).put("nickname","屏幕测试").put("admin_password_change_required",true);Hub.connected=false
            Hub.http=OkHttpClient.Builder().addInterceptor{c->Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("Local screen test only").body(JSONObject().put("contacts",JSONArray()).put("conversations",JSONArray()).toString().toResponseBody()).build()}.build()
            AppUpdates.transport=Hub.http
        }
        val activity=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var marker:Activity?=null
        try{
            stage("setting up local native WebRTC peers")
            ui{
                engine=RtcEngine(i.targetContext){p->
                    if(p.optString("type")=="candidate"){
                        val c=IceCandidate(p.optString("sdpMid"),p.getInt("sdpMLineIndex"),p.getString("candidate"));if(remoteSet)receiver?.addIceCandidate(c)else remoteCandidates.add(c)
                    }else if(p.optString("type")=="offer"){
                        val pc=receiver
                        if(pc==null)localSignals.add(p)else pc.setRemoteDescription(object:SdpCallback(){override fun onSetSuccess(){Hub.main.post{
                            remoteSet=true;remoteCandidates.forEach{pc.addIceCandidate(it)};remoteCandidates.clear()
                            pc.createAnswer(object:SdpCallback(){override fun onCreateSuccess(s:SessionDescription){pc.setLocalDescription(object:SdpCallback(){override fun onSetSuccess(){Hub.main.post{engine.receive(JSONObject().put("type","answer").put("sdp",s.description))}}},s)}},MediaConstraints())
                        }}},SessionDescription(SessionDescription.Type.OFFER,p.getString("sdp")))
                    }
                }
                engineCreated=true
                Hub.call=call;Hub.callUser=JSONObject().put("id","96006002").put("nickname","屏幕共享测试");Hub.rtc=engine;Hub.phase="connecting"
                invoke(activity,"showCall")
                engine.start(true,true,JSONObject().put("iceServers",JSONArray()))
                val camera=field(activity,"cameraControl") as android.widget.LinearLayout
                check((camera.getChildAt(camera.childCount-1) as TextView).text.toString()=="镜头设置")
                receiverFactory=PeerConnectionFactory.builder().setVideoDecoderFactory(DefaultVideoDecoderFactory(engine.egl.eglBaseContext)).createPeerConnectionFactory()
                receiver=receiverFactory!!.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply{sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN},object:PeerConnection.Observer{
                    override fun onSignalingChange(s:PeerConnection.SignalingState){}
                    override fun onIceConnectionChange(s:PeerConnection.IceConnectionState){}
                    override fun onIceConnectionReceivingChange(b:Boolean){}
                    override fun onIceGatheringChange(s:PeerConnection.IceGatheringState){}
                    override fun onIceCandidate(c:IceCandidate){Hub.main.post{engine.receive(JSONObject().put("type","candidate").put("sdpMid",c.sdpMid).put("sdpMLineIndex",c.sdpMLineIndex).put("candidate",c.sdp))}}
                    override fun onIceCandidatesRemoved(c:Array<out IceCandidate>){}
                    override fun onAddStream(s:MediaStream){}
                    override fun onRemoveStream(s:MediaStream){}
                    override fun onRenegotiationNeeded(){}
                    override fun onAddTrack(r:RtpReceiver,s:Array<out MediaStream>){(r.track() as? VideoTrack)?.addSink(sink)}
                    override fun onDataChannel(c:DataChannel){data=c;c.registerObserver(object:DataChannel.Observer{override fun onBufferedAmountChange(a:Long){};override fun onStateChange(){};override fun onMessage(b:DataChannel.Buffer){val bytes=ByteArray(b.data.remaining());b.data.get(bytes);receiverMode.set(JSONObject(String(bytes)).optString("mode"))}})}
                })!!
            }
            await("native DTLS-SRTP connected and camera frames received"){engine.mediaConnected&&count.get()>4&&data?.state()==DataChannel.State.OPEN}
            ui{Hub.phase="connected";Hub.connectedAt=SystemClock.elapsedRealtime();invoke(activity,"showCall")}
            val cameraRequest=ScreenSharing.request()!!;check(!ScreenSharing.approve(cameraRequest,Activity.RESULT_CANCELED,null));check(!ScreenSharing.approve(cameraRequest,Activity.RESULT_OK,Intent()))
            val stale=ScreenSharing.request()!!;ui{Hub.call=JSONObject(call.toString()).put("id","different-call");check(!ScreenSharing.approve(stale,Activity.RESULT_OK,Intent()));Hub.call=call}
            checks.put("local native camera/DTLS-SRTP peers; cancelled, consumed and stale-call grants rejected")
            invoke(activity,"toggleScreenSharing");grant(false);await("denied grant returns to video call"){Hub.foreground};check(!engine.sharingScreen&&engine.cameraEnabled)
            fun audioBytes():Long{val latch=CountDownLatch(1);var value=0L;receiver!!.getStats{r->value=r.statsMap.values.filter{it.type=="inbound-rtp"&&it.members["kind"]=="audio"}.sumOf{(it.members["bytesReceived"] as? Number)?.toLong()?:0};latch.countDown()};check(latch.await(5,TimeUnit.SECONDS));return value}
            val originalMuted=engine.muted;val originalSpeaker=engine.speaker;val audioBefore=audioBytes()
            invoke(activity,"toggleScreenSharing");grant();await("projection service and screen video source active"){engine.sharingScreen&&receiverMode.get()=="screen"};capture("sharing-call")
            val manager=i.targetContext.getSystemService(NotificationManager::class.java)
            val stop=manager.activeNotifications.first{it.id==108}.notification.actions.first{it.title.toString()=="停止共享"}.actionIntent
            marker=i.startActivitySync(Intent(i.targetContext,ThemeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
            ui{marker!!.overridePendingTransition(0,0);marker!!.setContentView(object:View(marker){init{setWillNotDraw(false)};var tick=0;override fun onDraw(c:Canvas){c.drawColor(Color.rgb(32,64,224));c.drawRect(0f,height/2f,width.toFloat(),height.toFloat(),Paint().apply{color=Color.rgb(240,208,32)});c.drawText("SYNTHETIC SCREEN",24f,96f,Paint().apply{color=Color.WHITE;textSize=36f});c.drawRect((tick++%maxOf(1,width)).toFloat(),120f,(tick%maxOf(1,width)+8).toFloat(),140f,Paint().apply{color=Color.WHITE});postInvalidateOnAnimation()}})}
            val target=marked.get()+8;await("synthetic blue/yellow display pixels decoded by remote peer"){marked.get()>=target}
            File(folder,"remote-screen.png").outputStream().use{frameBitmap.get()!!.compress(Bitmap.CompressFormat.PNG,100,it)}
            val beforeBlack=black.get();ui{marker!!.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)};await("secure window is blank in projected video"){black.get()>beforeBlack};ui{marker!!.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);marker!!.finish()};marker=null
            await("return to sharing call"){Hub.foreground};checks.put("real Android grant/foreground projection; synthetic screen pixels reach remote decoded RTP; FLAG_SECURE stays black")
            val audioDuring=audioBytes();check(audioDuring>audioBefore&&engine.muted==originalMuted&&engine.speaker==originalSpeaker);checks.put("outgoing call audio RTP continues during screen sharing; mic/speaker choices preserved")
            val realSize=Point();ui{activity.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(realSize)}
            shell("wm size ${realSize.y}x${realSize.x}");await("screen resize without consuming a second projection grant"){lastW.get()>lastH.get()};shell("wm size reset")
            await("screen returns to portrait"){lastH.get()>lastW.get()};check(engine.sharingScreen);checks.put("resize of active projection without token reuse")
            check(!activity.isDestroyed){"Resize recreated the test activity; refresh the driver before requesting another system grant"}
            stop.send();await("notification stop ends projection"){!engine.sharingScreen&&manager.activeNotifications.none{it.id==108}};check(!engine.cameraEnabled&&Hub.call===call)
            ui{engine.toggleCamera()};await("camera explicitly reopened on original video sender"){engine.cameraEnabled&&receiverMode.get()=="camera"}
            invoke(activity,"toggleScreenSharing");grant();await("fresh second projection grant active"){engine.sharingScreen};stop.send();Thread.sleep(250);check(engine.sharingScreen)
            val cap=field(engine,"capturer") as ScreenCapturerAndroid;cap.mediaProjection!!.stop();await("system projection revocation ends sharing"){!engine.sharingScreen&&manager.activeNotifications.none{it.id==108}}
            checks.put("notification stop keeps call and leaves camera off; explicit camera resume; stale stop cannot end new share; system revoke stops capture")
            data!!.send(DataChannel.Buffer(ByteBuffer.wrap(JSONObject().put("mode","screen").put("seq",1).toString().toByteArray()),false));await("remote mode is presentation only"){engine.remoteVideoMode=="screen"};check(!engine.sharingScreen)
            data!!.send(DataChannel.Buffer(ByteBuffer.wrap(ByteArray(512){65}),false));Thread.sleep(100);check(!engine.sharingScreen)
            data!!.send(DataChannel.Buffer(ByteBuffer.wrap(JSONObject().put("mode","camera").put("seq",3).toString().toByteArray()),false));await("bounded mode metadata recovered"){engine.remoteVideoMode=="camera"};checks.put("peer metadata and oversized messages cannot start local capture")
            ui{engine.toggleCamera()};invoke(activity,"toggleScreenSharing");grant();await("third projection ready for call-page stop"){engine.sharingScreen}
            invoke(activity,"toggleScreenSharing");await("call-page button stops screen sharing"){!engine.sharingScreen&&manager.activeNotifications.none{it.id==108}}
            invoke(activity,"toggleScreenSharing");grant();await("projection ready for screen lock"){engine.sharingScreen}
            shell("input keyevent 223");await("locking screen stops screen sharing"){!engine.sharingScreen&&manager.activeNotifications.none{it.id==108}}
            shell("input keyevent 224");shell("wm dismiss-keyguard");await("call returns after wake"){Hub.foreground};check(!engine.sharingScreen&&!engine.cameraEnabled)
            checks.put("call-page stop works; screen sleep stops projection and wake never restarts it")
            invoke(activity,"toggleScreenSharing");grant();await("fresh projection ready for hangup test"){engine.sharingScreen}
            ui{Hub.endLocalCall()};await("hangup releases capture and foreground indicator"){manager.activeNotifications.none{it.id==108}&&!engine.sharingScreen}
            checks.put("hangup disposes capture and indicator; no restart or camera re-enable")
            val result=JSONObject().put("passed",true).put("api",Build.VERSION.SDK_INT).put("realProjectionGrant",true).put("syntheticPixelsOnly",true).put("localDtlsSrtp",true).put("decodedFrames",count.get()).put("markedFrames",marked.get()).put("audioBytesBefore",audioBefore).put("audioBytesDuring",audioDuring).put("checks",checks).put("physicalDeviceTests",false).put("realUserMessagesOrCalls",0)
            result.put("apkSha256",MessageDigest.getInstance("SHA-256").digest(File(i.targetContext.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)})
            File(folder,"result.json").writeText(result.toString(2));stage("PASS complete");return "PASS native screen sharing: "+result.toString()
        }finally{
            File(folder,"frame-metrics.txt").writeText("frames=${count.get()} marked=${marked.get()} black=${black.get()} size=${lastW.get()}x${lastH.get()} ${colors.get()}")
            File(folder,"activity-metrics.txt").writeText("destroyed=${activity.isDestroyed} foreground=${Hub.foreground} phase=${Hub.phase}")
            shell("wm size reset")
            acceptFrames.set(false)
            frameBitmap.getAndSet(null)?.recycle()
            ui{marker?.finish();data?.unregisterObserver();data?.dispose();receiver?.close();receiver?.dispose();receiverFactory?.dispose();invoke(activity,"releaseRenderers");if(engineCreated)engine.release();Hub.rtc=null;Hub.call=null;Hub.phase="";Hub.logout();activity.finish();Hub.http=oldHttp;AppUpdates.transport=oldUpdates}
        }
    }
}
