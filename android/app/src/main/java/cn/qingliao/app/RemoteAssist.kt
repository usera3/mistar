package cn.qingliao.app

import android.os.SystemClock
import org.json.JSONObject
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** One peer, one explicitly approved direction. Leases live only in this accepted call/engine. */
internal class RemoteAssist(private val engine:RtcEngine,private val clock:()->Long=SystemClock::elapsedRealtime){
    private val session=Hub.token
    private val owner=Hub.me.optString("id")
    private val peer=Hub.callUser.optString("id")
    private val call=Hub.call?.optString("id").orEmpty()
    private var channel:DataChannel?=null
    @Volatile private var closed=false
    private val packets=ArrayBlockingQueue<ByteArray>(8)
    private val posted=AtomicBoolean()
    private var invalid=0
    var peerSupported=false;private set
    var peerOffers=false;private set
    var incomingRequest="";private set
    private var incomingUntil=0L
    private var requestId=""
    private var requestUntil=0L
    private var lastRequestAt=-10000L
    private var lastPeerRequestAt=-10000L
    private data class Lease(val id:String,val request:String,val share:String,val geometry:AssistWire.Geometry,val ackUntil:Long=0,var sequence:Int=0,var lastAction:Long=-10000,var ticket:String="",var ticketAt:Long=0,var previousTicket:String="",var previousAt:Long=0,var confirmed:Boolean=false)
    private var local:Lease?=null
    private var remote:Lease?=null
    private var waitingUntil=0L
    private var lastOffer:Boolean?=null
    private var helloCount=0
    private var helloAt=0L
    var note="";private set
    var feedback="";private set
    var feedbackRevision=0;private set
    val hosting get()=local!=null
    val controlling get()=remote!=null
    val pending get()=requestId.isNotEmpty()
    val geometry get()=remote?.geometry
    internal fun authorizes(lease:String,geometry:AssistWire.Geometry):Boolean {
        val l=local?:return false
        return current()&&l.confirmed&&l.id==lease&&l.geometry==geometry&&ScreenSharing.controlShareId(engine)==l.share
    }
    private val tick=object:Runnable{override fun run(){
        if(closed)return
        if(!bound()){stopAll("通话状态已改变");return}
        sharingChanged()
        val now=clock()
        if(local?.let{!it.confirmed&&now>it.ackUntil}==true)stopHosting("对方未确认连接，授权已结束")
        if(!peerSupported&&helloCount<3&&now-helloAt>1000&&send("hello")){helloCount++;helloAt=now}
        local?.let{l->if(now-l.ticketAt>=1000){l.previousTicket=l.ticket;l.previousAt=l.ticketAt;l.ticket=id();l.ticketAt=now;send("ticket",JSONObject().put("lease",l.id).put("ticket",l.ticket))}}
        if(incomingRequest.isNotEmpty()&&now>incomingUntil){incomingRequest="";changed()}
        if(requestId.isNotEmpty()&&now>requestUntil){requestId="";note="申请已过期，可重新申请";changed()}
        if(waitingUntil>0&&now>waitingUntil)stopControlling("操作未确认，已停止控制")
        Hub.main.postDelayed(this,500)
    }}
    private fun bound()=!closed&&session.isNotEmpty()&&peer.isNotEmpty()&&peer!=owner&&Hub.token==session&&Hub.me.optString("id")==owner&&Hub.callUser.optString("id")==peer&&setOf(Hub.call?.optString("caller"),Hub.call?.optString("callee"))==setOf(owner,peer)&&Hub.call?.optString("id")==call&&Hub.rtc===engine&&!Hub.callUser.optBoolean("bot")&&Hub.call?.optBoolean("video")==true&&Hub.call?.optBoolean("accepted")==true
    private fun current()=bound()&&Hub.phase=="connected"&&engine.mediaConnected
    private fun id()=UUID.randomUUID().toString().replace("-","")
    fun install(value:DataChannel){
        if(!bound()||value.label()!=AssistWire.CHANNEL||channel!=null){value.close();value.dispose();return}
        channel=value
        value.registerObserver(object:DataChannel.Observer{
            override fun onBufferedAmountChange(amount:Long){}
            override fun onStateChange(){Hub.main.post{if(!closed&&channel===value){if(value.state()==DataChannel.State.OPEN){send("hello");sharingChanged()}else if(value.state()==DataChannel.State.CLOSED)close()}}}
            override fun onMessage(buffer:DataChannel.Buffer){
                if(closed||buffer.binary||buffer.data.remaining() !in 1..AssistWire.MAX_BYTES)return
                val bytes=ByteArray(buffer.data.remaining());buffer.data.get(bytes)
                if(!packets.offer(bytes))return
                schedule()
            }
        })
        if(value.state()==DataChannel.State.OPEN)send("hello")
        Hub.main.post(tick)
    }
    private fun schedule(){if(posted.compareAndSet(false,true))Hub.main.postDelayed({
        repeat(4){packets.poll()?.let{runCatching{receive(AssistWire.parse(it))}.onFailure{invalid++;if(invalid>=12)close()}}}
        posted.set(false);if(packets.isNotEmpty()&&!closed)schedule()
    },33)}
    private fun send(type:String,body:JSONObject=JSONObject()):Boolean{
        val c=channel?:return false
        if(closed||!bound()||c.state()!=DataChannel.State.OPEN||c.bufferedAmount()>4096)return false
        body.put("v",AssistWire.VERSION).put("t",type).put("call",call)
        val raw=body.toString().toByteArray();if(raw.size>AssistWire.MAX_BYTES)return false
        return runCatching{c.send(DataChannel.Buffer(ByteBuffer.wrap(raw),false))}.getOrDefault(false)
    }
    private fun changed(){if(Hub.rtc===engine){AssistNotices.update(incomingRequest);Hub.emit(JSONObject().put("event","call.assist"))}}
    private fun feedback(value:String){if(feedback!=value){feedback=value;feedbackRevision++};changed()}
    fun sharingChanged(){
        if(closed)return
        val share=if(current())ScreenSharing.controlShareId(engine)else null
        local?.let{lease->
            if(share!=lease.share||!AssistAccessibilityService.available()||!AssistAccessibilityService.unlocked()||AssistAccessibilityService.geometry()!=lease.geometry)stopHosting("协助授权已结束")
        }
        if(remote!=null&&(!current()||engine.remoteVideoMode!="screen"))stopControlling("共享或通话连接已改变")
        val offer=current()&&share!=null&&!hosting&&!controlling
        if(lastOffer!=offer){if(send("offer",JSONObject().put("ready",offer))){lastOffer=offer;changed()}}
    }
    fun requestControl():Boolean {
        val now=clock()
        if(!current()||!peerSupported||!peerOffers||hosting||controlling||pending||now-lastRequestAt<10000)return false
        lastRequestAt=now;val request=id()
        if(!send("request",JSONObject().put("request",request)))return false
        requestId=request;requestUntil=now+120000;note="等待对方允许";changed();return true
    }
    fun allow(request:String):Boolean{
        if(!AssistWire.id(request)||!current()||!peerSupported||incomingRequest!=request||clock()>incomingUntil||hosting||controlling||!Hub.foreground||!AssistAccessibilityService.available()||!AssistAccessibilityService.unlocked())return false
        val share=ScreenSharing.controlShareId(engine)?:return false
        val lease=Lease(id(),request,share,AssistAccessibilityService.geometry(),ackUntil=clock()+5000)
        lease.ticket=id();lease.ticketAt=clock()
        if(!AssistAccessibilityService.begin(this,lease.id))return false
        local=lease
        if(!send("grant",JSONObject().put("request",request).put("lease",lease.id).put("geometry",lease.geometry.json()).put("ttl",0).put("ticket",lease.ticket))){local=null;AssistAccessibilityService.end(this);return false}
        incomingRequest="";note="对方可协助操作，随时可以停止";changed();return true
    }
    fun deny(){val request=incomingRequest;incomingRequest="";if(request.isNotEmpty())send("deny",JSONObject().put("request",request));note="已拒绝本次申请";changed()}
    fun stopHosting(reason:String="已停止对方控制"){
        val old=local;local=null;AssistAccessibilityService.end(this)
        if(old!=null)send("revoke",JSONObject().put("lease",old.id));note=reason;changed()
    }
    fun stopControlling(reason:String="已结束控制"){
        val old=remote;val request=requestId;remote=null;waitingUntil=0;requestId=""
        if(old!=null)send("release",JSONObject().put("lease",old.id))else if(request.isNotEmpty())send("cancel",JSONObject().put("request",request));note=reason;if(old!=null)feedback(reason)else changed()
    }
    private fun stopAll(reason:String){stopHosting(reason);stopControlling(reason);incomingRequest="";peerOffers=false;changed()}
    fun canTouchPeer():Boolean {
        val r=remote?:return false;val size=engine.remoteFrameSize
        return current()&&Hub.foreground&&engine.remoteVideoMode=="screen"&&size.first>0&&size.second>0&&SystemClock.elapsedRealtime()-engine.remoteFrameAt<2000&&abs(size.first.toDouble()/size.second-r.geometry.width.toDouble()/r.geometry.height)<.02
    }
    fun sendInput(input:AssistWire.Input):Boolean{
        val lease=remote?:return false;val now=clock()
        if(!canTouchPeer()||waitingUntil>0||now-lease.lastAction<300){note="画面更新或操作处理中，请稍后再试";feedback(note);return false}
        if(lease.sequence==Int.MAX_VALUE){stopControlling("操作序号已用尽，请重新申请");return false}
        val sequence=lease.sequence+1
        if(!send("input",JSONObject().put("lease",lease.id).put("s",sequence).put("ticket",lease.ticket).put("input",input.json()))){stopControlling("协助连接不可用");return false}
        lease.sequence=sequence;lease.lastAction=now;waitingUntil=now+3000;return true
    }
    private fun receive(p:JSONObject){
        require(bound()&&p.get("v")==AssistWire.VERSION&&p.getString("call")==call)
        val kind=p.getString("t");val base=setOf("v","t","call")
        fun fields(vararg extra:String)=AssistWire.keys(p,base+extra)
        fun nonce(key:String)=p.getString(key).also{require(AssistWire.id(it))}
        when(kind){
            "hello"->{fields();if(!peerSupported){peerSupported=true;send("hello");lastOffer=null;sharingChanged()}}
            "offer"->{fields("ready");require(p.get("ready") is Boolean);val ready=p.getBoolean("ready");if(peerOffers!=ready){peerOffers=ready;changed()}}
            "request"->{fields("request");val request=nonce("request");val now=clock()
                if(current()&&peerSupported&&ScreenSharing.controlShareId(engine)!=null&&!hosting&&!controlling&&now-lastPeerRequestAt>=10000){lastPeerRequestAt=now;incomingRequest=request;incomingUntil=now+120000;note="对方申请协助控制，请由你确认";changed()}
                else send("deny",JSONObject().put("request",request))
            }
            "deny"->{fields("request");if(nonce("request")==requestId){requestId="";note="对方未允许本次控制";feedback(note)}}
            "cancel"->{fields("request");val request=nonce("request");if(incomingRequest==request){incomingRequest="";note="对方已取消申请";changed()};if(local?.request==request)stopHosting("对方已取消申请")}
            "grant"->{fields("request","lease","geometry","ttl","ticket");val request=nonce("request");val lease=nonce("lease");val ticket=nonce("ticket");val g=AssistWire.geometry(p.getJSONObject("geometry"));AssistWire.integer(p,"ttl",0,0)
                if(current()&&!hosting&&Hub.foreground&&request==requestId&&clock()<=requestUntil){remote=Lease(lease,request,"",g,ticket=ticket);requestId="";if(!send("accepted",JSONObject().put("lease",lease))){remote=null;feedback("协助连接不可用");return};note="正在控制对方，点按会操作对方手机";changed()}
                else if(request==requestId&&!Hub.foreground){stopControlling("已离开控制画面")}
            }
            "accepted"->{fields("lease");local?.takeIf{it.id==nonce("lease")}?.let{it.confirmed=true}}
            "ticket"->{fields("lease","ticket");val token=nonce("ticket");remote?.takeIf{it.id==nonce("lease")}?.ticket=token}
            "revoke"->{fields("lease");if(remote?.id==nonce("lease")){remote=null;waitingUntil=0;note="对方已停止授权";feedback(note)}}
            "release"->{fields("lease");if(local?.id==nonce("lease"))stopHosting("对方已结束控制")}
            "input"->{fields("lease","s","input","ticket");val leaseId=nonce("lease");val token=nonce("ticket");val sequence=AssistWire.integer(p,"s",1,Int.MAX_VALUE);val input=AssistWire.input(p.getJSONObject("input"));val lease=local?:return
                if(lease.id!=leaseId)return
                val now=clock()
                if(!current()||ScreenSharing.controlShareId(engine)!=lease.share||AssistAccessibilityService.geometry()!=lease.geometry){stopHosting("共享状态已改变");return}
                if(sequence!=lease.sequence+1||now-lease.lastAction<300){stopHosting("操作顺序异常，已停止协助");return}
                lease.sequence=sequence;lease.lastAction=now
                val born=if(token==lease.ticket)lease.ticketAt else if(token==lease.previousTicket)lease.previousAt else 0L
                val done:(String)->Unit={result->if(local===lease&&current())send("ack",JSONObject().put("lease",lease.id).put("s",sequence).put("result",result))}
                if(born<=0||now-born>2500)done("blocked")else AssistAccessibilityService.execute(this,lease.id,input,lease.geometry,born+2500,done)
            }
            "ack"->{fields("lease","s","result");val sequence=AssistWire.integer(p,"s",1,Int.MAX_VALUE);val result=p.getString("result");require(result in listOf("ok","cancelled","blocked","busy"))
                remote?.takeIf{it.id==nonce("lease")&&it.sequence==sequence}?.let{waitingUntil=0;if(result!="ok"){note=when(result){"blocked"->"此界面不能远程操作，请让对方处理";"busy"->"对方正在操作，请稍后再试";else->"手势被取消，可重新操作"};feedback(note)}else feedback=""}
            }
            else->error("Unknown assistance packet")
        }
    }
    fun close(){if(closed)return;stopAll("协助连接已结束");closed=true;peerSupported=false;Hub.main.removeCallbacks(tick);packets.clear();channel?.unregisterObserver();channel?.close();channel?.dispose();channel=null}
}
