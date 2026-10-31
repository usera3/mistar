package cn.qingliao.app

import android.app.Instrumentation
import android.os.SystemClock
import org.json.*
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Actual local DTLS/SCTP peer. Outgoing video, when enabled, contains only synthetic blue pixels. */
internal class AssistPeerFixture(private val i:Instrumentation,val callId:String,val shareFromPeer:Boolean):AutoCloseable {
    lateinit var engine:RtcEngine;private set
    private var factory:PeerConnectionFactory?=null
    private var pc:PeerConnection?=null
    private var video:VideoTrack?=null
    private var source:VideoSource?=null
    private var modes:DataChannel?=null
    var control:DataChannel?=null;private set
    val packets=CopyOnWriteArrayList<JSONObject>()
    val ticket=AtomicReference("")
    val tickets=ConcurrentHashMap<String,String>()
    @Volatile var autoAcceptGrant=true
    val frames=AtomicInteger()
    private val candidates=mutableListOf<IceCandidate>()
    private var remoteSet=false
    private val timer=Executors.newSingleThreadScheduledExecutor()
    private val active=AtomicBoolean(true)
    private fun ui(f:()->Unit){if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())f()else i.runOnMainSync(f)}
    fun start(){ui{
        engine=RtcEngine(i.targetContext){p->
            when(p.optString("type")){
                "candidate"->{val c=IceCandidate(p.optString("sdpMid"),p.getInt("sdpMLineIndex"),p.getString("candidate"));if(remoteSet)pc?.addIceCandidate(c)else candidates.add(c)}
                "offer"->{pc!!.setRemoteDescription(object:SdpCallback(){override fun onSetSuccess(){Hub.main.post{remoteSet=true;candidates.forEach{pc!!.addIceCandidate(it)};candidates.clear();pc!!.createAnswer(object:SdpCallback(){override fun onCreateSuccess(s:SessionDescription){pc!!.setLocalDescription(object:SdpCallback(){override fun onSetSuccess(){Hub.main.post{engine.receive(JSONObject().put("type","answer").put("sdp",s.description))}}},s)}},MediaConstraints())}}},SessionDescription(SessionDescription.Type.OFFER,p.getString("sdp")))}
            }
        }
        Hub.rtc=engine
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(i.targetContext).createInitializationOptions())
        factory=PeerConnectionFactory.builder().setVideoEncoderFactory(DefaultVideoEncoderFactory(engine.egl.eglBaseContext,true,true)).setVideoDecoderFactory(DefaultVideoDecoderFactory(engine.egl.eglBaseContext)).createPeerConnectionFactory()
        pc=factory!!.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply{sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN},object:PeerConnection.Observer{
            override fun onSignalingChange(s:PeerConnection.SignalingState){}
            override fun onIceConnectionChange(s:PeerConnection.IceConnectionState){}
            override fun onIceConnectionReceivingChange(b:Boolean){}
            override fun onIceGatheringChange(s:PeerConnection.IceGatheringState){}
            override fun onIceCandidate(c:IceCandidate){Hub.main.post{engine.receive(JSONObject().put("type","candidate").put("sdpMid",c.sdpMid).put("sdpMLineIndex",c.sdpMLineIndex).put("candidate",c.sdp))}}
            override fun onIceCandidatesRemoved(c:Array<out IceCandidate>){}
            override fun onAddStream(s:MediaStream){}
            override fun onRemoveStream(s:MediaStream){}
            override fun onRenegotiationNeeded(){}
            override fun onAddTrack(r:RtpReceiver,s:Array<out MediaStream>){(r.track() as? VideoTrack)?.addSink{frames.incrementAndGet()}}
            override fun onDataChannel(c:DataChannel){Hub.main.post{install(c)}}
        })!!
        if(shareFromPeer){source=factory!!.createVideoSource(true);video=factory!!.createVideoTrack("synthetic-assist-screen",source);pc!!.addTrack(video,listOf("synthetic"));source!!.capturerObserver.onCapturerStarted(true)}
        engine.start(true,true,JSONObject().put("iceServers",JSONArray()))
    }
        if(shareFromPeer)timer.scheduleAtFixedRate({if(active.get()){
            val b=JavaI420Buffer.allocate(320,640);for((buffer,value)in listOf(b.dataY to 41,b.dataU to 240,b.dataV to 110))for(n in 0 until buffer.capacity())buffer.put(n,value.toByte())
            val frame=VideoFrame(b,0,System.nanoTime());try{source?.capturerObserver?.onFrameCaptured(frame)}finally{frame.release()}
        }},0,66,TimeUnit.MILLISECONDS)
    }
    private fun install(c:DataChannel){
        if(c.label()=="xingyu.video-mode.v1"){
            modes=c;c.registerObserver(object:DataChannel.Observer{override fun onBufferedAmountChange(a:Long){};override fun onMessage(b:DataChannel.Buffer){};override fun onStateChange(){Hub.main.post{announceMode()}}});announceMode()
        }else if(c.label()==AssistWire.CHANNEL){
            control=c;c.registerObserver(object:DataChannel.Observer{
                override fun onBufferedAmountChange(a:Long){}
                override fun onStateChange(){Hub.main.post{hello()}}
                override fun onMessage(b:DataChannel.Buffer){val bytes=ByteArray(b.data.remaining());b.data.get(bytes);val p=JSONObject(String(bytes));packets.add(p);if(p.optString("t") in listOf("ticket","grant")){ticket.set(p.getString("ticket"));tickets[p.getString("lease")]=p.getString("ticket")};if(p.optString("t")=="grant"&&autoAcceptGrant)send("accepted",JSONObject().put("lease",p.getString("lease")))}
            });hello()
        }else{c.close();c.dispose()}
    }
    private fun announceMode(){if(shareFromPeer&&modes?.state()==DataChannel.State.OPEN)modes?.send(DataChannel.Buffer(ByteBuffer.wrap(JSONObject().put("mode","screen").put("seq",1).toString().toByteArray()),false))}
    private fun hello(){if(control?.state()==DataChannel.State.OPEN){send("hello");if(shareFromPeer)send("offer",JSONObject().put("ready",true))}}
    fun pauseVideo(){active.set(false)}
    fun resumeVideo(){active.set(true)}
    fun videoMode(mode:String,sequence:Int){modes?.send(DataChannel.Buffer(ByteBuffer.wrap(JSONObject().put("mode",mode).put("seq",sequence).toString().toByteArray()),false))}
    fun send(type:String,body:JSONObject=JSONObject(),version:Int=AssistWire.VERSION):Boolean {
        body.put("v",version).put("t",type).put("call",callId)
        return control?.send(DataChannel.Buffer(ByteBuffer.wrap(body.toString().toByteArray()),false))==true
    }
    override fun close(){
        active.set(false);timer.shutdownNow();timer.awaitTermination(5,TimeUnit.SECONDS)
        ui{control?.unregisterObserver();modes?.unregisterObserver();control?.close();modes?.close();control?.dispose();modes?.dispose();pc?.close();pc?.dispose();video?.dispose();source?.dispose();factory?.dispose();engine.release();if(Hub.rtc===engine)Hub.rtc=null}
    }
}
