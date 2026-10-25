package cn.qingliao.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.webrtc.*

open class SdpCallback : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) { Log.e("QingRTC", error) }
    override fun onSetFailure(error: String) { Log.e("QingRTC", error) }
}

class RtcEngine(private val context: Context,private val sendSignal:(JSONObject)->Unit={Hub.signal("call.signal",JSONObject().put("signal",it))}) {
    val egl = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var capturer: VideoCapturer? = null
    private var videoSender:RtpSender?=null
    private var modes:DataChannel?=null
    private var modeSeq=0
    private var receivedModeSeq=0
    var remoteVideoMode="camera";private set
    var sharingScreen=false;private set
    private var sharedWidth=0
    private var sharedHeight=0
    private var frontCamera=true
    val canShareScreen get()=!released&&videoSender!=null&&mediaConnected
    private var texture: SurfaceTextureHelper? = null
    private var localView: SurfaceViewRenderer? = null
    private var remoteView: SurfaceViewRenderer? = null
    private val signals = mutableListOf<JSONObject>()
    private val candidates = mutableListOf<IceCandidate>()
    private var remoteSet = false
    @Volatile private var released = false
    private val pendingMode=AtomicReference<ByteArray?>()
    private val modeQueued=AtomicBoolean(false)
    private val modeTask=object:Runnable{override fun run(){
        val bytes=pendingMode.getAndSet(null)
        if(!released&&bytes!=null)runCatching{
            val value=JSONObject(String(bytes,Charsets.UTF_8));val seq=value.getInt("seq");val mode=value.getString("mode")
            if(seq>receivedModeSeq&&seq in 1..1000000&&mode in listOf("screen","camera","off")){receivedModeSeq=seq;remoteVideoMode=mode;changedVideo()}
        }
        modeQueued.set(false)
        if(!released&&pendingMode.get()!=null)scheduleMode()
    }}
    private fun scheduleMode(){if(modeQueued.compareAndSet(false,true))Hub.main.postDelayed(modeTask,50)}
    var mediaConnected=false
        private set
    private var isCaller=false
    private var pendingRestart=false
    private var restartTask:Runnable?=null
    var muted = false
    val speaker get()=audioSession.speaker
    val audioPaused get()=!audioSession.ready
    private var remoteAudio:AudioTrack?=null
    var cameraEnabled = false
        private set
    private val audioSession=CallAudio(context){if(!released){applyAudio();Hub.emit(JSONObject().put("event","call.audio"))}}
    private fun applyAudio(){audioTrack?.setEnabled(!muted&&audioSession.ready);remoteAudio?.setEnabled(audioSession.ready)}
    private var statsTask: Runnable? = null

    fun start(video: Boolean, caller: Boolean, config: JSONObject) {
        if (released) return
        isCaller=caller
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
            val servers = mutableListOf<PeerConnection.IceServer>()
            val list = config.getJSONArray("iceServers")
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val urls = item.getJSONArray("urls")
                val builder = PeerConnection.IceServer.builder((0 until urls.length()).map { urls.getString(it) })
                if (item.has("username")) builder.setUsername(item.getString("username")).setPassword(item.getString("credential"))
                servers.add(builder.createIceServer())
            }
            val rtcConfig = PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                if (BuildConfig.FORCE_RELAY || Hub.callUser.optBoolean("bot")) iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
            pc = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { Log.d("QingRTC","ICE $state");Hub.main.post {
                    if(!released&&(state==PeerConnection.IceConnectionState.CONNECTED||state==PeerConnection.IceConnectionState.COMPLETED)){mediaConnected=true;Hub.mediaRestored()}
                } }
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    Hub.main.post {
                        if (released) return@post
                        Log.d("QingRTC", "Connection $state")
                        if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                            mediaConnected=true;Hub.mediaRestored();if(statsTask==null)logStats()
                        } else if(state==PeerConnection.PeerConnectionState.FAILED||state==PeerConnection.PeerConnectionState.DISCONNECTED) {
                            mediaConnected=false;requestRestart()
                        }
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidate(candidate: IceCandidate) { Hub.main.post {
                    if (!released) sendSignal(JSONObject().put("type", "candidate").put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex).put("candidate", candidate.sdp))
                } }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: DataChannel) { Hub.main.post{installModeChannel(channel)} }
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    val track = receiver.track()
                    if (track is VideoTrack) Hub.main.post { if (!released) { remoteVideo = track; remoteView?.let { track.addSink(it) } } }
                    if(track is AudioTrack)Hub.main.post{if(!released){remoteAudio=track;applyAudio()}}
                }
            }) ?: throw Exception("无法创建通话连接")
            audioSession.start(video)
            audioSource = factory!!.createAudioSource(MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            })
            audioTrack = factory!!.createAudioTrack("audio", audioSource)
            pc!!.addTrack(audioTrack, listOf("qingliao"));applyAudio()
            if(video){startCamera();changedVideo()}
            if(caller)installModeChannel(pc!!.createDataChannel("xingyu.video-mode.v1",DataChannel.Init()))
            val buffered = signals.toList(); signals.clear(); buffered.forEach { receive(it) }
            if (caller) pc!!.createOffer(localDescription(), MediaConstraints())
        } catch (e: Exception) {
            Hub.emit(JSONObject().put("event", "error").put("error", e.message ?: "无法启动通话"))
            Hub.signal("call.end"); Hub.endLocalCall(); Hub.emit(JSONObject().put("event", "rtc.ended"))
        }
    }

    private fun localDescription() = object : SdpCallback() {
        override fun onCreateSuccess(sdp: SessionDescription) {
            Hub.main.post {
                if (released) return@post
                pc?.setLocalDescription(object : SdpCallback() {
                    override fun onSetSuccess() { Hub.main.post { if (!released) sendSignal(JSONObject().put("type", sdp.type.canonicalForm()).put("sdp", sdp.description)) } }
                    override fun onSetFailure(error: String) { fail(error) }
                }, sdp)
            }
        }
        override fun onCreateFailure(error: String) { fail(error) }
    }
    private fun fail(error: String) { Hub.main.post { if (!released) { Log.e("QingRTC", error); Hub.emit(JSONObject().put("event", "error").put("error", "无法协商通话连接")); Hub.signal("call.end") } } }
    fun requestRestart() {
        if(released||pc==null)return
        pendingRestart=true;Hub.markCallReconnecting()
        if(!Hub.connected)return
        if(!isCaller){sendSignal(JSONObject().put("type","restart"));return}
        if(restartTask!=null)return
        restartTask=Runnable {
            restartTask=null
            val peer=pc?:return@Runnable
            if(released||!Hub.connected)return@Runnable
            if(peer.signalingState()==PeerConnection.SignalingState.STABLE) {
                pendingRestart=false
                peer.createOffer(localDescription(),MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("IceRestart","true")) })
            } else peer.localDescription?.let { description ->
                sendSignal(JSONObject().put("type",description.type.canonicalForm()).put("sdp",description.description))
            }
        }.also{Hub.main.postDelayed(it,650)}
    }
    fun receive(p:JSONObject) {
        try{receiveChecked(p)}catch(_:Exception){fail("Invalid bounded call signal")}
    }
    private fun receiveChecked(p: JSONObject) {
        if (released) return
        val type=p.optString("type")
        require(type in listOf("offer","answer","candidate","restart"))
        if(type in listOf("offer","answer"))require(p.getString("sdp").length in 1..65536)
        if(type=="candidate")require(p.getString("candidate").length<=4096&&p.optString("sdpMid").length<=128&&p.getInt("sdpMLineIndex") in 0..32)
        val peer = pc ?: run { require(signals.size<128);signals.add(p); return }
        when (p.optString("type")) {
            "restart" -> if(isCaller)requestRestart()
            "offer", "answer" -> {
                val offer = p.getString("type") == "offer"
                peer.setRemoteDescription(object : SdpCallback() {
                    override fun onSetSuccess() { Hub.main.post {
                        if (released) return@post
                        remoteSet = true; candidates.forEach { peer.addIceCandidate(it) }; candidates.clear()
                        if (offer) peer.createAnswer(localDescription(), MediaConstraints())
                        else if(pendingRestart)requestRestart()
                    } }
                    override fun onSetFailure(error: String) { fail(error) }
                }, SessionDescription(if (offer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER, p.getString("sdp")))
            }
            "candidate" -> {
                val c = IceCandidate(p.optString("sdpMid"), p.getInt("sdpMLineIndex"), p.getString("candidate"))
                if (remoteSet) peer.addIceCandidate(c) else { require(candidates.size<128);candidates.add(c) }
            }
        }
    }
    fun attach(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localView?.let { localVideo?.removeSink(it) }; remoteView?.let { remoteVideo?.removeSink(it) }
        localView = local; remoteView = remote
        if(!sharingScreen)local?.let { it.setMirror(frontCamera);localVideo?.addSink(it) }; remote?.let { remoteVideo?.addSink(it) }
    }
    fun detach(local:SurfaceViewRenderer?,remote:SurfaceViewRenderer?) {
        if(localView===local){local?.let{localVideo?.removeSink(it)};localView=null}
        if(remoteView===remote){remote?.let{remoteVideo?.removeSink(it)};remoteView=null}
    }
    fun toggleMute() { muted = !muted;applyAudio() }
    fun toggleSpeaker() { audioSession.toggleSpeaker() }
    fun flip() { val camera=capturer as? CameraVideoCapturer?:return;if(!sharingScreen)camera.switchCamera(object:CameraVideoCapturer.CameraSwitchHandler{
        override fun onCameraSwitchDone(front:Boolean){Hub.main.post{if(!released&&!sharingScreen&&capturer===camera){frontCamera=front;localView?.setMirror(front)}}}
        override fun onCameraSwitchError(error:String){}
    }) }
    fun toggleCamera() {
        if(released||videoSender==null||sharingScreen)return
        try{if(cameraEnabled){videoSender?.setTrack(null,false);disposeInput();cameraEnabled=false;announceMode()}else if(Hub.foreground)startCamera()}
        catch(_:Exception){Hub.emit(JSONObject().put("event","error").put("error","摄像头暂不可用"))}
        changedVideo()
    }
    private fun startCamera(){
        val enumerator=Camera2Enumerator(context)
        val name=enumerator.deviceNames.firstOrNull{enumerator.isFrontFacing(it)}?:enumerator.deviceNames.firstOrNull()?:throw IllegalStateException("未找到可用摄像头")
        val camera=enumerator.createCapturer(name,null)?:throw IllegalStateException("无法打开摄像头")
        frontCamera=enumerator.isFrontFacing(name)
        replaceInput(camera,false,640,480,24);cameraEnabled=true;sharingScreen=false;announceMode()
    }
    private fun replaceInput(next:VideoCapturer,screen:Boolean,width:Int,height:Int,fps:Int){
        val source=factory!!.createVideoSource(screen);val helper=SurfaceTextureHelper.create(if(screen)"XingyuScreen"else "QingCamera",egl.eglBaseContext)
        val track=factory!!.createVideoTrack("video",source)
        try{
            next.initialize(helper,context,source.capturerObserver);source.adaptOutputFormat(width,height,fps);next.startCapture(width,height,fps)
            if(videoSender==null)videoSender=pc!!.addTrack(track,listOf("qingliao"))else check(videoSender!!.setTrack(track,false)){"视频源切换失败"}
        }catch(e:Exception){runCatching{next.stopCapture()};runCatching{next.dispose()};track.dispose();source.dispose();helper.dispose();throw e}
        localView?.let{localVideo?.removeSink(it)};disposeInput()
        capturer=next;videoSource=source;texture=helper;localVideo=track
        if(!screen)localView?.let{it.setMirror(frontCamera);track.addSink(it)}
        videoSender?.parameters?.let{p->p.degradationPreference=if(screen)RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION else RtpParameters.DegradationPreference.BALANCED;p.encodings.forEach{it.maxBitrateBps=if(screen)2500000 else 1200000;it.maxFramerate=fps};videoSender?.setParameters(p)}
    }
    internal fun startScreen(permission:Intent,width:Int,height:Int){
        check(canShareScreen&&!sharingScreen)
        lateinit var screen:ScreenCapturerAndroid
        screen=ScreenCapturerAndroid(permission,object:MediaProjection.Callback(){
            override fun onStop(){Hub.main.post{if(!released&&capturer===screen)ScreenSharing.stop(this@RtcEngine)}}
            override fun onCapturedContentResize(width:Int,height:Int){Hub.main.post{if(!released&&capturer===screen)resizeScreen(width,height)}}
        })
        replaceInput(screen,true,width,height,15);sharedWidth=width;sharedHeight=height;sharingScreen=true;cameraEnabled=false;announceMode();changedVideo()
    }
    internal fun resizeScreen(width:Int,height:Int){
        if(!sharingScreen||released)return
        val size=ScreenSharing.dimensions(width,height)
        if(size.first==sharedWidth&&size.second==sharedHeight)return
        try{videoSource?.adaptOutputFormat(size.first,size.second,15);capturer?.changeCaptureFormat(size.first,size.second,15);sharedWidth=size.first;sharedHeight=size.second}catch(_:Exception){ScreenSharing.stop(this)}
    }
    internal fun stopScreen(){
        if(!sharingScreen||released)return
        sharingScreen=false;cameraEnabled=false;videoSender?.setTrack(null,false);disposeInput();announceMode();changedVideo()
    }
    private fun disposeInput(){
        localView?.let{localVideo?.removeSink(it)}
        val old=capturer;capturer=null
        runCatching{old?.stopCapture()};runCatching{old?.dispose()};localVideo?.dispose();videoSource?.dispose();texture?.dispose()
        localVideo=null;videoSource=null;texture=null
    }
    private fun changedVideo(){if(Hub.rtc===this)Hub.emit(JSONObject().put("event","call.video"))}
    private fun announceMode(){
        val channel=modes?:return
        if(released||channel.state()!=DataChannel.State.OPEN||channel.bufferedAmount()>4096)return
        val body=JSONObject().put("mode",if(sharingScreen)"screen"else if(cameraEnabled)"camera"else "off").put("seq",++modeSeq).toString().toByteArray()
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(body),false))
    }
    private fun installModeChannel(channel:DataChannel){
        if(released||channel.label()!="xingyu.video-mode.v1"||modes!=null){channel.close();channel.dispose();return}
        modes=channel
        channel.registerObserver(object:DataChannel.Observer{
            override fun onBufferedAmountChange(previousAmount:Long){}
            override fun onStateChange(){Hub.main.post{if(!released&&modes===channel&&channel.state()==DataChannel.State.OPEN)announceMode()}}
            override fun onMessage(buffer:DataChannel.Buffer){
                if(released||buffer.binary||buffer.data.remaining() !in 1..256)return
                val bytes=ByteArray(buffer.data.remaining());buffer.data.get(bytes)
                pendingMode.set(bytes);scheduleMode()
            }
        })
        if(channel.state()==DataChannel.State.OPEN)announceMode()
    }
    private fun logStats() {
        statsTask = object : Runnable {
            override fun run() {
                if (released) return
                pc?.getStats { report ->
                    report.statsMap.values.filter { it.type == "inbound-rtp" || it.type == "outbound-rtp" || it.type == "local-candidate" || it.type == "remote-candidate" || it.type == "candidate-pair" && it.members["state"] == "succeeded" }.forEach {
                        Log.d("QingRTC", "stats ${it.type} ${it.members.filterKeys { k -> k in listOf("kind", "bytesReceived", "bytesSent", "framesDecoded", "framesEncoded", "state", "candidateType", "protocol", "localCandidateId", "remoteCandidateId") }}")
                    }
                }
                Hub.main.postDelayed(this, 5000)
            }
        }.also { Hub.main.postDelayed(it, 5000) }
    }
    fun release() {
        if (released) return
        released = true;mediaConnected=false;statsTask?.let { Hub.main.removeCallbacks(it) };restartTask?.let{Hub.main.removeCallbacks(it)};restartTask=null;signals.clear();candidates.clear()
        Hub.main.removeCallbacks(modeTask);pendingMode.set(null);modeQueued.set(false)
        ScreenSharing.engineReleased(this);attach(null,null);sharingScreen=false;cameraEnabled=false
        modes?.unregisterObserver();modes?.close();modes?.dispose();modes=null
        disposeInput();pc?.close();pc?.dispose();pc=null;videoSender=null
        audioTrack?.dispose();audioSource?.dispose();factory?.dispose();egl.release()
        audioSession.release()
    }
}
