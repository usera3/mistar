package cn.qingliao.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.TextView

/** Gesture-only accessibility bridge. Never requests or traverses window content or event text. */
class AssistAccessibilityService:AccessibilityService(){
    companion object {
        private var instance:AssistAccessibilityService?=null
        internal fun available()=instance!=null
        internal fun unlocked()=Hub.app.getSystemService(PowerManager::class.java).isInteractive&&!Hub.app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        @Suppress("DEPRECATION") internal fun geometry():AssistWire.Geometry {
            val display=Hub.app.getSystemService(WindowManager::class.java).defaultDisplay;val p=Point();display.getRealSize(p)
            return AssistWire.Geometry(p.x,p.y,display.rotation)
        }
        internal fun begin(host:RemoteAssist,lease:String)=instance?.beginHost(host,lease)==true
        internal fun end(host:RemoteAssist){instance?.endHost(host)}
        internal fun execute(host:RemoteAssist,lease:String,input:AssistWire.Input,geometry:AssistWire.Geometry,deadline:Long,done:(String)->Unit){instance?.inject(host,lease,input,geometry,deadline,done)?:done("blocked")}
    }
    private var host:RemoteAssist?=null
    private var lease=""
    private var chip:TextView?=null
    private var overlayRect=RectF()
    private var foreground=""
    private var busy=false
    private var generation=0
    private var pending:Runnable?=null
    private val wm by lazy{getSystemService(WindowManager::class.java)}
    private val imePackages by lazy{getSystemService(InputMethodManager::class.java).enabledInputMethodList.map{it.packageName}.toSet()}
    private val protectedPackages by lazy {
        setOf(packageName,"android","com.android.systemui","com.android.intentresolver","com.android.settings","com.android.permissioncontroller","com.google.android.permissioncontroller","com.android.packageinstaller","com.google.android.packageinstaller","com.android.phone","com.android.server.telecom","com.google.android.dialer","com.android.dialer","com.eg.android.AlipayGphone","com.lbe.security.miui","com.miui.securitycenter","com.coloros.safecenter","com.oplus.safecenter","com.iqoo.secure","com.huawei.systemmanager","com.samsung.android.permissioncontroller")
    }
    override fun onServiceConnected(){instance=this;Hub.rtc?.assist?.sharingChanged()}
    override fun onAccessibilityEvent(event:AccessibilityEvent?){
        val e=event?:return;val pkg=e.packageName?.toString().orEmpty();if(pkg.isEmpty())return
        if(pkg==packageName&&e.className?.toString()==StopChip::class.java.name)return
        if(host==null){foreground=if(pkg==packageName)packageName else "";return}
        if(e.eventType==AccessibilityEvent.TYPE_VIEW_FOCUSED&&pkg !in imePackages&&e.isPassword){host?.stopHosting("检测到密码输入，已停止协助");return}
        if(e.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&pkg !in imePackages){
            foreground=pkg
            chip?.let{it.visibility=if(pkg==packageName)View.GONE else View.VISIBLE;it.post{updateOverlayRect()}}
            if(pkg in protectedPackages&&pkg!=packageName&&pkg!="com.android.systemui")host?.stopHosting("进入系统或受保护界面，已停止协助")
        }
    }
    override fun onInterrupt(){host?.stopHosting("系统中断了协助服务")}
    override fun onUnbind(intent:Intent?):Boolean{host?.stopHosting("协助服务已关闭");if(instance===this)instance=null;return super.onUnbind(intent)}
    override fun onDestroy(){host?.stopHosting("协助服务已关闭");if(instance===this)instance=null;super.onDestroy()}
    private fun dp(n:Int)=(resources.displayMetrics.density*n+.5f).toInt()
    private class StopChip(c:Context):TextView(c){override fun getAccessibilityClassName():CharSequence=StopChip::class.java.name}
    private fun beginHost(value:RemoteAssist,id:String):Boolean {
        if(host!=null||!unlocked())return false
        val view=StopChip(this).apply{
            text="对方协助中 · 停止";textSize=12f;setTextColor(ThemeStore.current(this@AssistAccessibilityService).onPrimary);setPadding(dp(12),dp(8),dp(12),dp(8));maxWidth=geometry().width-dp(16)
            background=BrandUi.rounded(this@AssistAccessibilityService,ThemeStore.current(this@AssistAccessibilityService).primary,16);contentDescription="停止远程协助";isFocusable=true;visibility=if(this@AssistAccessibilityService.foreground==packageName)View.GONE else View.VISIBLE;setOnClickListener{host?.stopHosting()}
        }
        val params=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=dp(8);y=systemInsets().top+dp(8)}
        var downX=0f;var downY=0f;var initialX=0;var initialY=0;var moved=false
        view.setOnTouchListener{_,e->when(e.actionMasked){
            android.view.MotionEvent.ACTION_DOWN->{if(e.x>=view.width-dp(44)){host?.stopHosting();true}else{downX=e.rawX;downY=e.rawY;initialX=params.x;initialY=params.y;moved=false;false}}
            android.view.MotionEvent.ACTION_MOVE->{val dx=e.rawX-downX;val dy=e.rawY-downY;if(kotlin.math.abs(dx)+kotlin.math.abs(dy)>ViewConfiguration.get(this).scaledTouchSlop)moved=true;if(moved){val g=geometry();params.x=(initialX+dx.toInt()).coerceIn(0,(g.width-view.width).coerceAtLeast(0));params.y=(initialY+dy.toInt()).coerceIn(0,(g.height-view.height).coerceAtLeast(0));view.isPressed=false;runCatching{wm.updateViewLayout(view,params)};updateOverlayRect()};moved}
            android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL->moved
            else->false
        }}
        return try {
            wm.addView(view,params);host=value;lease=id;chip=view;generation++
            view.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->updateOverlayRect()};view.post{updateOverlayRect()}
            true
        }catch(_:Exception){runCatching{wm.removeViewImmediate(view)};false}
    }
    private fun updateOverlayRect(){chip?.let{val p=IntArray(2);it.getLocationOnScreen(p);overlayRect=RectF(p[0].toFloat(),p[1].toFloat(),p[0]+it.width.toFloat(),p[1]+it.height.toFloat())}}
    private fun endHost(value:RemoteAssist){
        if(host!==value)return
        host=null;lease="";foreground="";generation++;pending?.let{Hub.main.removeCallbacks(it)};pending=null;busy=false
        val view=chip;chip=null;overlayRect=RectF();if(view!=null)runCatching{wm.removeViewImmediate(view)}
    }
    private fun systemInsets():Rect {
        val raw=chip?.rootWindowInsets
        if(raw!=null){if(Build.VERSION.SDK_INT>=30){val b=raw.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout());return Rect(b.left,b.top,b.right,b.bottom)};return Rect(raw.stableInsetLeft,raw.stableInsetTop,raw.stableInsetRight,raw.stableInsetBottom)}
        fun dimension(name:String):Int{val id=resources.getIdentifier(name,"dimen","android");return if(id==0)0 else resources.getDimensionPixelSize(id)}
        return Rect(0,dimension("status_bar_height"),0,dimension("navigation_bar_height"))
    }
    private fun permitted(value:RemoteAssist,id:String,g:AssistWire.Geometry)=host===value&&lease==id&&value.authorizes(id,g)&&unlocked()&&geometry()==g&&foreground.isNotEmpty()&&foreground !in protectedPackages&&chip?.isAttachedToWindow==true&&chip?.visibility==View.VISIBLE
    private fun inject(value:RemoteAssist,id:String,input:AssistWire.Input,g:AssistWire.Geometry,deadline:Long,done:(String)->Unit){
        if(!permitted(value,id,g)){done("blocked");return};if(busy){done("busy");return}
        val version=generation;busy=true
        pending=Runnable{
            pending=null
            if(generation!=version||SystemClock.elapsedRealtime()>deadline||!permitted(value,id,g)){if(generation==version)busy=false;done("blocked");return@Runnable}
            if(input.kind=="back"||input.kind=="home"){
                val ok=performGlobalAction(if(input.kind=="back")GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME);busy=false;done(if(ok)"ok"else"blocked");return@Runnable
            }
            val insets=systemInsets();val start=PointF(input.x*(g.width-1),input.y*(g.height-1));val end=PointF(input.endX*(g.width-1),input.endY*(g.height-1))
            val allowed=RectF(insets.left.toFloat()+1,insets.top.toFloat()+1,(g.width-insets.right-1).toFloat(),(g.height-insets.bottom-1).toFloat())
            val bounds=RectF(minOf(start.x,end.x)-1,minOf(start.y,end.y)-1,maxOf(start.x,end.x)+1,maxOf(start.y,end.y)+1)
            if(!allowed.contains(start.x,start.y)||!allowed.contains(end.x,end.y)||RectF.intersects(bounds,overlayRect)){busy=false;done("blocked");return@Runnable}
            val path=Path().apply{moveTo(start.x,start.y);if(input.kind=="swipe")lineTo(end.x,end.y)}
            val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,input.duration.toLong())).build()
            val callback=object:GestureResultCallback(){
                private fun finish(result:String){if(generation==version){busy=false;if(host===value&&lease==id)done(result)}}
                override fun onCompleted(gestureDescription:GestureDescription){finish("ok")}
                override fun onCancelled(gestureDescription:GestureDescription){finish("cancelled")}
            }
            if(!dispatchGesture(gesture,callback,Hub.main)){busy=false;done("blocked")}
        }.also{Hub.main.postDelayed(it,100)}
    }
}
