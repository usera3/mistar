package cn.qingliao.app

import android.app.Activity
import android.content.*
import android.net.Uri
import android.view.*
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Local, pinned Leaflet and a narrowly allowed raster tile origin. No JS bridge or Web geolocation. */
class LocationMapView(private val activity:Activity,private val p:ThemePalette):FrameLayout(activity){
    private val owner=Hub.me.optString("id");private val session=Hub.token
    private var resumed=false;@Volatile private var active=false;private var ready=false;private var disposed=false;private var point:JSONObject?=null;private var rendered=""
    @Volatile var tileFailure="";private set
    val web=WebView(activity)
    private val error=FormUi(activity,p).text("地图渲染中断，请返回后重试",12f,p.muted).apply{gravity=Gravity.CENTER;visibility=View.GONE}
    init{
        tag="location.mapview";background=BrandUi.rounded(activity,p.soft,16);clipToOutline=true
        web.settings.apply{javaScriptEnabled=true;domStorageEnabled=false;allowFileAccess=false;allowContentAccess=false;setGeolocationEnabled(false);javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false);mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;cacheMode=WebSettings.LOAD_NO_CACHE;builtInZoomControls=false;displayZoomControls=false}
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.isLongClickable=false;web.setOnLongClickListener{true};web.setDownloadListener{_,_,_,_,_->}
        web.setOnTouchListener{v,event->v.parent?.requestDisallowInterceptTouchEvent(event.actionMasked !in listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL));false}
        web.webViewClient=object:WebViewClient(){
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                val uri=request.url
                if(request.method!="GET")return blocked()
                if(uri.scheme=="https"&&uri.host=="mistar-map.invalid"&&uri.query==null){
                    val asset=mapOf("/leaflet.js" to "application/javascript","/leaflet.css" to "text/css")[uri.path]?:return blocked()
                    return WebResourceResponse(asset,"UTF-8",activity.assets.open("location-map"+uri.path))
                }
                val tile=MapTiles.parse(uri)?:return blocked()
                if(!allowed())return blocked()
                return try{val bytes=MapTiles.fetch(tile.first,tile.second,tile.third,this@LocationMapView){allowed()};WebResourceResponse("image/png",null,ByteArrayInputStream(bytes))}catch(e:Exception){tileFailure=e.javaClass.simpleName;blocked()}
            }
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                if(request.hasGesture()&&request.url.toString() in setOf("https://www.openstreetmap.org/copyright","https://leafletjs.com/")&&allowed())runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW,request.url))}
                return true
            }
            override fun onPageFinished(view:WebView,url:String){if(!disposed){ready=true;rendered="";syncVisible();paint()}}
            override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean{if(!disposed){dispose();error.visibility=View.VISIBLE};return true}
        }
        addView(web,LayoutParams(-1,-1));addView(error,LayoutParams(-1,-1))
        web.loadDataWithBaseURL("https://mistar-map.invalid/index.html",activity.assets.open("location-map/index.html").bufferedReader().use{it.readText()},"text/html","UTF-8",null)
    }
    private fun blocked()=WebResourceResponse("text/plain","UTF-8",403,"Blocked",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream(ByteArray(0)))
    private fun allowed()=active&&!disposed&&Hub.token==session&&Hub.me.optString("id")==owner
    fun setPoint(value:JSONObject){LocationPayload.validate(value);point=JSONObject().put("lat",value.getDouble("lat")).put("lon",value.getDouble("lon")).put("accuracy",value.getDouble("accuracy")).put("approximate",value.getBoolean("approximate")).put("label",value.getString("label")).put("color",ThemeStore.hex(p.accent));syncVisible();paint()}
    private fun paint(){if(!ready||!allowed())return;val value=point?.toString()?:return;if(value==rendered)return;rendered=value;web.evaluateJavascript("window.setPosition($value)",null)}
    fun syncVisible(){if(disposed)return;val next=resumed&&isAttachedToWindow&&getGlobalVisibleRect(android.graphics.Rect())&&Hub.token==session&&Hub.me.optString("id")==owner;if(next!=active){active=next;if(active){web.onResume();web.evaluateJavascript("window.resumeMap&&window.resumeMap()",null);rendered="";paint()}else{MapTiles.cancel(this);web.onPause()}}}
    fun resume(){resumed=true;syncVisible()}
    fun pause(){resumed=false;active=false;MapTiles.cancel(this);web.onPause()}
    fun dispose(){if(disposed)return;disposed=true;active=false;resumed=false;MapTiles.cancel(this);web.stopLoading();removeView(web);web.removeAllViews();web.destroy()}
    override fun onDetachedFromWindow(){pause();super.onDetachedFromWindow()}
}
