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
    private var pageFailed=false
    private var pageGeneration=0;private var selection:JSONObject?=null
    private var mode="view";private var padding=0;private var actors="[]";private var meeting="null"
    private var cover=0
    private var paintedActors="";private var paintedMeeting=""
    private val page="https://appassets.androidplatform.net/assets/location-map/index.html"
    @Volatile var tileFailure="";private set
    val web=WebView(activity)
    private val error=FormUi(activity,p).text("地图暂时无法显示，点按重试",13f,p.ink).apply{tag="location.map.error";gravity=Gravity.CENTER;visibility=View.GONE;setPadding(20,20,20,20);setOnClickListener{if(disposed)activity.recreate()else load()}}
    init{
        tag="location.mapview";setBackgroundColor(p.soft)
        web.settings.apply{javaScriptEnabled=true;domStorageEnabled=false;allowFileAccess=false;allowContentAccess=false;setGeolocationEnabled(false);javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false);mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;cacheMode=WebSettings.LOAD_NO_CACHE;builtInZoomControls=false;displayZoomControls=false}
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.isLongClickable=false;web.setOnLongClickListener{true};web.setDownloadListener{_,_,_,_,_->}
        web.setOnTouchListener{v,event->v.parent?.requestDisallowInterceptTouchEvent(event.actionMasked !in listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL));false}
        web.webViewClient=object:WebViewClient(){
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                val uri=request.url
                if(request.method!="GET")return blocked()
                if(uri.scheme=="https"&&uri.host=="appassets.androidplatform.net"&&uri.port in listOf(-1,443)&&uri.query==null){
                    val asset=mapOf("/assets/location-map/index.html" to "text/html","/assets/location-map/leaflet.js" to "application/javascript","/assets/location-map/leaflet.css" to "text/css")[uri.encodedPath]?:return blocked()
                    return WebResourceResponse(asset,"UTF-8",activity.assets.open("location-map/"+uri.lastPathSegment))
                }
                val tile=MapTiles.parse(uri)?:return blocked()
                if(!allowed())return blocked()
                return try{val bytes=MapTiles.fetch(tile.first,tile.second,tile.third,this@LocationMapView){allowed()};WebResourceResponse("image/png",null,ByteArrayInputStream(bytes))}catch(e:Exception){tileFailure=e.javaClass.simpleName;blocked()}
            }
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                if(request.url.toString()==page&&request.method=="GET")return false
                if(request.hasGesture()&&request.url.toString() in setOf("https://www.openstreetmap.org/copyright","https://leafletjs.com/")&&allowed())runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW,request.url))}
                return true
            }
            override fun onPageFinished(view:WebView,url:String){if(!disposed&&!pageFailed&&url==page){ready=true;rendered="";web.visibility=View.VISIBLE;error.visibility=View.GONE;configure();syncVisible();paint();if(allowed()){web.evaluateJavascript("window.resumeMap();window.setActors($actors);window.setMeeting($meeting)",null);selection?.let{web.evaluateJavascript("window.selectPoint($it)",null)}}}}
            override fun onReceivedError(view:WebView,request:WebResourceRequest,failure:WebResourceError){if(request.isForMainFrame)pageError()}
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){if(request.isForMainFrame)pageError()}
            override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean{if(!disposed){dispose();error.visibility=View.VISIBLE};return true}
        }
        addView(web,LayoutParams(-1,-1));addView(error,LayoutParams(-1,-1))
        load()
    }
    private fun load(){if(disposed)return;pageGeneration++;pageFailed=false;ready=false;rendered="";paintedActors="";paintedMeeting="";error.visibility=View.GONE;web.visibility=View.VISIBLE;web.loadUrl(page)}
    private fun pageError(){if(disposed)return;pageFailed=true;ready=false;rendered="";web.stopLoading();web.visibility=View.INVISIBLE;error.visibility=View.VISIBLE}
    private fun blocked()=WebResourceResponse("text/plain","UTF-8",403,"Blocked",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream(ByteArray(0)))
    private fun allowed()=active&&!disposed&&Hub.token==session&&Hub.me.optString("id")==owner
    private fun configure(){if(ready&&!disposed)web.evaluateJavascript("window.configureMap(${JSONObject.quote(mode)},$padding,${JSONObject.quote(ThemeStore.hex(p.accent))},$cover)",null)}
    fun mode(value:String){require(value in listOf("view","pick","live"));mode=value;configure()}
    fun safeBottom(value:Int){padding=value.coerceIn(0,120);configure()}
    fun cameraTop(value:Int){val next=value.coerceIn(0,300);if(next!=cover){cover=next;configure()}}
    fun rememberPick(value:JSONObject){LocationPayload.validate(value);selection=JSONObject().put("lat",value.getDouble("lat")).put("lon",value.getDouble("lon"))}
    fun pick(value:JSONObject){rememberPick(value);if(point==null)setPoint(value);if(ready&&allowed())web.evaluateJavascript("window.selectPoint($selection)",null)}
    fun center(){selection=null;if(ready&&allowed())web.evaluateJavascript("window.centerPosition()",null)}
    fun focus(key:String){if(ready&&allowed()&&key.matches(Regex("p[0-9]{1,2}")))web.evaluateJavascript("window.focusActor(${JSONObject.quote(key)})",null)}
    fun actors(value:org.json.JSONArray){actors=value.toString();if(ready&&allowed()&&actors!=paintedActors){paintedActors=actors;web.evaluateJavascript("window.setActors($actors)",null)}}
    fun meeting(value:JSONObject?){meeting=value?.let{JSONObject().put("lat",it.getDouble("lat")).put("lon",it.getDouble("lon")).put("label",it.getString("label")).toString()}?:"null";if(ready&&allowed()&&meeting!=paintedMeeting){paintedMeeting=meeting;web.evaluateJavascript("window.setMeeting($meeting)",null)}}
    fun interaction(done:(JSONObject)->Unit){val expected=pageGeneration;if(ready&&allowed())web.evaluateJavascript("JSON.stringify(window.mapState.interaction||{})"){raw->if(allowed()&&expected==pageGeneration)runCatching{JSONObject(org.json.JSONTokener(raw).nextValue() as String).put("_page",expected)}.getOrNull()?.let{done(it)}}}
    fun setPoint(value:JSONObject){LocationPayload.validate(value);point=JSONObject().put("lat",value.getDouble("lat")).put("lon",value.getDouble("lon")).put("accuracy",value.getDouble("accuracy")).put("approximate",value.getBoolean("approximate")).put("label",value.getString("label")).put("color",ThemeStore.hex(p.accent));syncVisible();paint()}
    private fun paint(){if(!ready||!allowed())return;val value=point?.toString()?:return;if(value==rendered)return;rendered=value;web.evaluateJavascript("window.setPosition($value)",null)}
    fun syncVisible(){if(disposed)return;val next=resumed&&isAttachedToWindow&&getGlobalVisibleRect(android.graphics.Rect())&&Hub.token==session&&Hub.me.optString("id")==owner;if(next!=active){active=next;if(active){web.onResume();rendered="";paint();web.evaluateJavascript("window.resumeMap&&window.resumeMap()",null)}else{MapTiles.cancel(this);web.onPause()}}}
    fun resume(){resumed=true;syncVisible()}
    fun pause(){resumed=false;active=false;MapTiles.cancel(this);web.onPause()}
    fun dispose(){if(disposed)return;disposed=true;active=false;resumed=false;MapTiles.cancel(this);web.stopLoading();removeView(web);web.removeAllViews();web.destroy()}
    override fun onDetachedFromWindow(){pause();super.onDetachedFromWindow()}
}
