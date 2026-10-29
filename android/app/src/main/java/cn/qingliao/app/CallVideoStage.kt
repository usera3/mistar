package cn.qingliao.app

import android.content.Context
import android.graphics.Color
import android.view.*
import android.widget.*
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Per-call display preferences; swapping views never changes camera, capture or RTP senders. */
internal class CallVideoState {
    var localMain=false
    var controlsVisible=true
    var cameraFill=true
    var screenFill=false
}

/** Fixed Surface layers, interchangeable sinks. UI controls are siblings over the full video stage. */
internal class CallVideoStage(context:Context,private val engine:RtcEngine,private val state:CallVideoState,
    private val valid:()->Boolean,private val toggleControls:()->Unit,private val displayChanged:()->Unit):FrameLayout(context){
    private val u=FormUi(context,ThemeStore.current(context))
    private val full=SurfaceViewRenderer(context)
    private val preview=SurfaceViewRenderer(context)
    private val mainBlank=u.text("",17f,Color.WHITE).apply{gravity=Gravity.CENTER;setPadding(u.dp(24),0,u.dp(24),0);tag="call.main.placeholder"}
    private val mini=FrameLayout(context).apply{tag="call.preview";background=BrandUi.rounded(context,u.p.callSurface,14);clipToOutline=true;contentDescription="切换自己与对方画面";isFocusable=true}
    private val miniBlank=u.text("",11f,Color.WHITE).apply{gravity=Gravity.CENTER;setPadding(u.dp(4),0,u.dp(4),0)}
    private val badge=u.text("",10f,Color.WHITE).apply{gravity=Gravity.CENTER;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setBackgroundColor(0x66000000);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    private var localSink:SurfaceViewRenderer?=null
    private var remoteSink:SurfaceViewRenderer?=null
    private var disposed=false
    private var signature=""
    private var pip=false
    private var safeLeft=0;private var safeTop=0;private var safeRight=0;private var safeBottom=0
    val localIsMain get()=state.localMain
    val filled get()=if(!state.localMain&&engine.remoteVideoMode=="screen")state.screenFill else state.cameraFill
    init {
        tag="call.video.stage";setBackgroundColor(Color.BLACK)
        // Surface Z order is fixed. Reparenting/swapping SurfaceViews can leave black or stale surfaces.
        full.init(engine.egl.eglBaseContext,null);preview.init(engine.egl.eglBaseContext,null)
        full.setEnableHardwareScaler(false);preview.setEnableHardwareScaler(false);preview.setZOrderMediaOverlay(true)
        addView(full,LayoutParams(-1,-1));addView(mainBlank,LayoutParams(-1,-1))
        mini.addView(preview,LayoutParams(-1,-1));mini.addView(miniBlank,LayoutParams(-1,-1));mini.addView(badge,LayoutParams(-1,u.dp(22),Gravity.BOTTOM));addView(mini)
        setOnClickListener{if(valid()&&!pip)toggleControls()}
        mini.setOnClickListener{if(valid()&&!pip){state.localMain=!state.localMain;refresh(true);displayChanged()}}
        refresh(true)
    }
    fun safeInsets(left:Int,top:Int,right:Int,bottom:Int){safeLeft=left;safeTop=top;safeRight=right;safeBottom=bottom;layoutPreview()}
    fun presentation(pictureInPicture:Boolean){pip=pictureInPicture;mini.visibility=if(pip)View.GONE else View.VISIBLE;layoutPreview()}
    fun toggleScale(){if(!valid())return;if(!state.localMain&&engine.remoteVideoMode=="screen")state.screenFill=!state.screenFill else state.cameraFill=!state.cameraFill;refresh();displayChanged()}
    fun refresh(force:Boolean=false){
        if(disposed||!valid())return
        val next="${state.localMain}|${engine.cameraEnabled}|${engine.sharingScreen}|${engine.remoteVideoMode}|$pip"
        if(force||next!=signature){
            engine.detach(localSink,remoteSink);full.clearImage();preview.clearImage();full.setMirror(false);preview.setMirror(false)
            localSink=if(state.localMain)full else if(pip)null else preview;remoteSink=if(state.localMain){if(pip)null else preview}else full
            engine.attach(localSink,remoteSink);signature=next
        }
        val localUnavailable=!engine.cameraEnabled||engine.sharingScreen
        val localLabel=if(engine.sharingScreen)"正在共享你的屏幕\n切换到需要分享的应用"else"你的摄像头已关闭"
        val remoteUnavailable=engine.remoteVideoMode=="off"
        val remoteLabel="对方摄像头已关闭"
        val mainUnavailable=if(state.localMain)localUnavailable else remoteUnavailable
        val miniUnavailable=if(state.localMain)remoteUnavailable else localUnavailable
        full.visibility=if(mainUnavailable)View.INVISIBLE else View.VISIBLE;mainBlank.visibility=if(mainUnavailable)View.VISIBLE else View.GONE
        mainBlank.text=if(state.localMain)localLabel else remoteLabel
        preview.visibility=if(pip||miniUnavailable)View.INVISIBLE else View.VISIBLE;miniBlank.visibility=if(miniUnavailable)View.VISIBLE else View.GONE
        miniBlank.text=if(state.localMain)remoteLabel else if(engine.sharingScreen)"正在共享\n屏幕"else"镜头已关闭"
        badge.text=(if(state.localMain)"对方"else"自己")+(if(u.compact)""else" · 点按切换")
        full.setScalingType(if(filled)RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        preview.setScalingType(if(state.localMain&&engine.remoteVideoMode=="screen")RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        layoutPreview()
    }
    private fun layoutPreview(){
        if(width<=0||height<=0)return
        val small=width<u.dp(340)||height<u.dp(400)
        // Keep the tap target fixed while controls toggle, avoiding taps landing on the top buttons.
        val top=safeTop+u.dp(if(small)48 else 66)
        val available=(height-top-safeBottom-u.dp(if(small)80 else 160)).coerceAtLeast(u.dp(48))
        val h=minOf(u.dp(if(small)76 else 148),available)
        val w=minOf(u.dp(if(small)56 else 106),(h*.74f).toInt()).coerceAtLeast(u.dp(32))
        val maxTop=(height-h-safeBottom).coerceAtLeast(safeTop)
        mini.layoutParams=LayoutParams(w,h,Gravity.TOP or Gravity.END).apply{topMargin=top.coerceAtMost(maxTop);marginEnd=safeRight+u.dp(if(small)8 else 16)}
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);layoutPreview()}
    fun release(){if(disposed)return;disposed=true;engine.detach(localSink,remoteSink);localSink=null;remoteSink=null;full.release();preview.release()}
}
