package cn.qingliao.app

import android.app.*
import android.content.Intent
import android.graphics.*
import android.os.*
import android.view.*
import org.json.*
import org.webrtc.DataChannel
import java.io.File
import java.util.UUID

/** Actual caller UI gestures -> encrypted SCTP packets against a synthetic screen-sharing peer. */
internal object AssistControllerTests {
    fun run(i:Instrumentation,a:MainActivity,folder:File):Int {
        var checks=0;val automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun ui(f:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())f()else i.runOnMainSync(f)}
        fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        fun invoke(n:String,vararg args:Any?){ui{a.javaClass.declaredMethods.first{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(a,*args)}}
        fun await(label:String,p:()->Boolean){File(folder,"progress.txt").writeText(label);val end=SystemClock.elapsedRealtime()+30000;while(true){var ok=false;ui{ok=p()};if(ok){i.waitForIdleSync();return};check(SystemClock.elapsedRealtime()<end){"Controller timeout: $label"};Thread.sleep(50)}}
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun find(tag:String)=walk(a.window.decorView).firstOrNull{it.tag==tag}
        fun capture(name:String){i.waitForIdleSync();Thread.sleep(300);val b=automation.takeScreenshot();File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        fun sendTouch(start:PointF,end:PointF=start,hold:Long=80){
            val down=SystemClock.uptimeMillis();fun event(action:Int,x:Float,y:Float){val e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;automation.injectInputEvent(e,true);e.recycle()}
            event(android.view.MotionEvent.ACTION_DOWN,start.x,start.y);Thread.sleep(hold)
            if(start!=end){for(n in 1..6){event(android.view.MotionEvent.ACTION_MOVE,start.x+(end.x-start.x)*n/6,start.y+(end.y-start.y)*n/6);Thread.sleep(25)}}
            event(android.view.MotionEvent.ACTION_UP,end.x,end.y);i.waitForIdleSync()
        }
        val id="synthetic-assistance-controller";val h=AssistPeerFixture(i,id,true)
        ui{Hub.call=JSONObject().put("id",id).put("caller","86000001").put("callee","86000002").put("video",true).put("accepted",true);Hub.callUser=JSONObject().put("id","86000002").put("nickname","合成被协助者");Hub.phase="connecting"}
        h.start()
        try {
            await("controller peer connected"){h.engine.mediaConnected&&h.control?.state()==DataChannel.State.OPEN};ui{Hub.phase="connected";Hub.connectedAt=SystemClock.elapsedRealtime()};invoke("showCall")
            await("synthetic remote screen frames"){h.engine.remoteVideoMode=="screen"&&h.engine.remoteFrameSize.first>0&&h.engine.assist.peerOffers};checks++
            val geometry=AssistWire.Geometry(480,960,0)
            invoke("assistanceMenu");await("pending request can be cancelled"){h.engine.assist.pending&&h.packets.any{it.optString("t")=="request"}}
            val cancelled=h.packets.last{it.optString("t")=="request"}.getString("request")
            ui{find("call.assist")!!.performClick()};await("cancellation pushed to peer"){!h.engine.assist.pending&&h.packets.any{it.optString("t")=="cancel"&&it.optString("request")==cancelled}};checks++
            fun grant(waitCooldown:Boolean=false):JSONObject {
                if(waitCooldown)Thread.sleep(10500)
                val before=h.packets.count{it.optString("t")=="request"}
                invoke("assistanceMenu");await("outgoing assistance request"){h.engine.assist.pending&&h.packets.count{it.optString("t")=="request"}>before}
                val request=h.packets.last{it.optString("t")=="request"}.getString("request")
                val p=JSONObject().put("request",request).put("lease",UUID.randomUUID().toString().replace("-","")).put("geometry",geometry.json()).put("ttl",0).put("ticket",UUID.randomUUID().toString().replace("-",""))
                h.send("grant",JSONObject(p.toString()).put("ttl",600000));Thread.sleep(200);check(!h.engine.assist.controlling&&h.engine.assist.pending)
                h.send("grant",p);await("controller consent result"){h.engine.assist.controlling&&h.engine.assist.canTouchPeer()};return p
            }
            val first=grant(true);val stage=field(a,"callVideoStage") as CallVideoStage
            ui{h.engine.assist.javaClass.getDeclaredField("clock").apply{isAccessible=true}.set(h.engine.assist,{SystemClock.elapsedRealtime()+86400000});(field(h.engine.assist,"tick") as Runnable).run();check(h.engine.assist.controlling);h.engine.assist.javaClass.getDeclaredField("clock").apply{isAccessible=true}.set(h.engine.assist,{SystemClock.elapsedRealtime()})};checks++
            check(!stage.localIsMain&&!stage.filled&&find("call.scale")!!.isEnabled==false);checks++
            fun freePoints():Pair<PointF,PointF>{
                val location=IntArray(2);stage.getLocationOnScreen(location);val rect=geometry.videoRect(stage.width,stage.height)
                val blocked=listOfNotNull(find("call.top"),find("call.controls"),find("call.assist.bar"),find("call.preview")).filter{it.visibility==View.VISIBLE}.map{v->Rect().also{v.getGlobalVisibleRect(it)}}
                val delta=64*stage.resources.displayMetrics.density
                for(yy in 20..80 step 2)for(xx in 20..60 step 5){val x=location[0]+rect.left+rect.width()*xx/100;val y=location[1]+rect.top+rect.height()*yy/100;val endX=x+delta
                    if(endX>=location[0]+rect.right-4)continue
                    if(blocked.none{it.contains(x.toInt(),y.toInt())||it.contains(endX.toInt(),y.toInt())})return PointF(x,y) to PointF(endX,y)
                };error("No uncovered controller video points")
            }
            var points=freePoints()
            fun packetAfter(before:Int,kind:String):JSONObject {
                await("controller $kind packet"){h.packets.count{it.optString("t")=="input"}>before}
                val p=h.packets.last{it.optString("t")=="input"};check(p.getJSONObject("input").getString("op")==kind)
                h.send("ack",JSONObject().put("lease",p.getString("lease")).put("s",p.getInt("s")).put("result","ok"));Thread.sleep(400);return p
            }
            var count=h.packets.count{it.optString("t")=="input"};sendTouch(points.first);val tap=packetAfter(count,"tap")
            val location=IntArray(2);stage.getLocationOnScreen(location);val norm=geometry.normalized(points.first.x-location[0],points.first.y-location[1],stage.width,stage.height)!!
            check(kotlin.math.abs(tap.getJSONObject("input").getDouble("x")-norm.x)<.01&&kotlin.math.abs(tap.getJSONObject("input").getDouble("y")-norm.y)<.01);checks++
            count=h.packets.count{it.optString("t")=="input"};sendTouch(points.first,hold=650);check(packetAfter(count,"long").getJSONObject("input").getInt("ms")>=500);checks++
            count=h.packets.count{it.optString("t")=="input"};sendTouch(points.first,points.second);packetAfter(count,"swipe");checks++
            capture("controller-input-mode")
            h.pauseVideo();Thread.sleep(2400);count=h.packets.count{it.optString("t")=="input"};sendTouch(points.first);Thread.sleep(200);check(h.packets.count{it.optString("t")=="input"}==count);checks++
            h.resumeVideo();await("fresh screen resumes"){h.engine.assist.canTouchPeer()}
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);await("PiP exits remote control"){!h.engine.assist.controlling&&a.isInPictureInPictureMode};check(h.engine.mediaConnected);checks++
            h.send("grant",first);Thread.sleep(150);check(!h.engine.assist.controlling);checks++
            i.targetContext.startActivity(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_USER_ACTION));await("controller expanded"){!a.isInPictureInPictureMode&&field(a,"resumed")==true}
            grant(true);ui{find("call.preview")!!.performClick()};await("preview swap exits control"){!h.engine.assist.controlling};check((field(a,"callVideoStage") as CallVideoStage).localIsMain);checks++
            grant(true);check(!(field(a,"callVideoStage") as CallVideoStage).localIsMain);h.videoMode("off",2);await("peer screen off exits control"){!h.engine.assist.controlling&&h.engine.remoteVideoMode=="off"};checks++
            return checks
        }finally{invoke("releaseRenderers");h.close();ui{Hub.call=null;Hub.phase=""}}
    }
}
