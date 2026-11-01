package cn.qingliao.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.*
import android.provider.Settings
import android.text.*
import android.view.*
import android.view.inputmethod.*
import android.widget.*
import org.json.*
import java.util.*

/** Map-first picker, static viewer and consent-bound live room. Coordinates remain signed/encrypted. */
class LocationActivity:Activity(){
    private val owner=Hub.me.optString("id");private val session=Hub.token
    private val p by lazy{ThemeStore.current(this)};private val u by lazy{FormUi(this,p)}
    private val peer by lazy{intent.getStringExtra("peer").orEmpty()}
    private val peerInfo by lazy{runCatching{JSONObject(intent.getStringExtra("peer_info").orEmpty())}.getOrDefault(JSONObject().put("id",peer).put("nickname","好友"))}
    private val expectedSender by lazy{intent.getStringExtra("sender")?:owner}
    private var mode="send";private var live="";private var point:JSONObject?=null;private var address="";private var manual=false
    private var devicePoint:JSONObject?=null;private var fix:Location?=null;private var source:LocationSource?=null
    private var busy=false;@Volatile private var resumed=false;@Volatile private var revision=0;private var permissionAsked=false;private var duration=3600
    private var firstLookup=false;private var seenInteraction=0;private var roomBusy=false;private var roomRevision=0;private var sheetExpanded=false;private var safeTop=0;private var safeBottom=0;private var imeBottom=0;private var focused=""
    private var seenMapPage=0
    private var dialog:Dialog?=null;private var geoDelay:Runnable?=null;private var ticks=0
    private lateinit var root:FrameLayout;private lateinit var map:LocationMapView;private lateinit var top:LinearLayout;private lateinit var panel:LinearLayout
    private lateinit var title:TextView;private lateinit var detail:TextView;private lateinit var state:TextView;private lateinit var submit:TextView;private lateinit var start:TextView;private lateinit var power:View;private lateinit var recenter:View;private lateinit var flag:View
    private lateinit var rows:LinearLayout;private lateinit var search:EditText;private lateinit var label:EditText;private lateinit var participants:LinearLayout
    private lateinit var participantHost:HorizontalScrollView
    private lateinit var searchContainer:LinearLayout
    private val badges=linkedMapOf<String,MapPersonBadge>();private val results=mutableListOf<LocationPlaces.Place>()
    private val places by lazy{LocationPlaces(this){valid()&&resumed&&mode in setOf("send","meeting")}}
    private val changed:()->Unit={if(valid()&&resumed&&mode=="live")renderRoom()}
    private val tick=object:Runnable{override fun run(){if(!valid()||!resumed)return;map.syncVisible();if(mode in setOf("send","meeting"))map.interaction{onMapInteraction(it)};if(++ticks%4==0){if(mode=="live")renderRoom()else renderSelection()};Hub.main.postDelayed(this,250)}}
    private fun valid()=session.isNotEmpty()&&Hub.token==session&&Hub.me.optString("id")==owner&&!isFinishing&&!isDestroyed
    override fun finish(){super.finish();Motion.activityClose(this)}
    override fun onCreate(saved:Bundle?){
        super.onCreate(saved);if(!valid()||!ChatAddress.valid(peer)){finish();return}
        live=intent.getStringExtra("live").orEmpty();mode=intent.getStringExtra("mode")?:if(live.isNotEmpty())"live"else if(intent.hasExtra("location"))"view"else"send"
        if(mode !in setOf("send","meeting","view","live","liveStart")){finish();return}
        point=(intent.getStringExtra("location")?:intent.getStringExtra("center"))?.let{runCatching{JSONObject(it).also{v->LocationPayload.validate(v)}}.getOrNull()};address=intent.getStringExtra("address").orEmpty().take(180)
        manual=intent.getBooleanExtra("map_selected",false)
        if(mode=="meeting")point=point?.let{mapPoint(it.getDouble("lat"),it.getDouble("lon"),"集合点")}.also{manual=it!=null}
        window.statusBarColor=Color.TRANSPARENT;window.navigationBarColor=Color.TRANSPARENT
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or if(u.tiny)0 else View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(u.tiny)
        root=FrameLayout(this).apply{setBackgroundColor(p.canvas);tag="location.screen"};map=LocationMapView(this,p);root.addView(map,FrameLayout.LayoutParams(-1,-1))
        label=EditText(this).apply{tag="location.label";isSingleLine=true;isSaveEnabled=false;filters=arrayOf(InputFilter.LengthFilter(60));hint="位置名称";setTextColor(p.ink)}
        top=u.col();root.addView(top,FrameLayout.LayoutParams(-1,-2,Gravity.TOP));buildHeader()
        panel=u.col().apply{tag="location.panel";background=GradientDrawable().apply{setColor(p.surface);val r=u.dp(18).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)}}
        root.addView(panel,FrameLayout.LayoutParams(-1,u.dp(260),Gravity.BOTTOM));buildPanel()
        recenter=floating("target","定位到位置标记"){recenterLocation()}.apply{tag="location.recenter"};root.addView(recenter,FrameLayout.LayoutParams(u.dp(46),u.dp(46),Gravity.BOTTOM or Gravity.START).apply{leftMargin=u.dp(14)})
        flag=floating("flag","设置集合点"){openMeeting()}.apply{tag="location.meeting"};root.addView(flag,FrameLayout.LayoutParams(u.dp(52),u.dp(52),Gravity.BOTTOM or Gravity.END).apply{rightMargin=u.dp(14)})
        root.setOnApplyWindowInsetsListener{_,i->
            if(Build.VERSION.SDK_INT>=30){val bars=i.getInsets(WindowInsets.Type.systemBars());safeTop=bars.top;safeBottom=bars.bottom;imeBottom=i.getInsets(WindowInsets.Type.ime()).bottom}
            else{safeTop=maxOf(i.stableInsetTop,i.systemWindowInsetTop);safeBottom=maxOf(i.stableInsetBottom,i.systemWindowInsetBottom.coerceAtMost(u.dp(80)));imeBottom=0
                if(!u.tiny&&safeBottom==0){val bar=resources.getIdentifier("navigation_bar_height","dimen","android");if(bar!=0)safeBottom=resources.getDimensionPixelSize(bar)}
            }
            if(u.tiny){safeTop=0;safeBottom=0}
            updateLayout();i
        };root.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->updateLayout()}
        setContentView(root);root.requestApplyInsets();applyMode();renderSelection()
        if(mode=="view")point?.let{map.setPoint(it)}
    }
    private fun floating(kind:String,description:String,dark:Boolean=false,action:()->Unit):View=FrameLayout(this).apply{
        contentDescription=description;isFocusable=true;background=BrandUi.rounded(this@LocationActivity,if(dark)0x2effffff else Color.WHITE,30);elevation=u.dp(3).toFloat()
        addView(Glyph(this@LocationActivity,kind,if(dark)Color.WHITE else p.ink),FrameLayout.LayoutParams(u.dp(23),u.dp(23),Gravity.CENTER));setOnClickListener{if(valid())action()};Motion.press(this)
    }
    private fun buildHeader(){
        val row=u.row().apply{setPadding(u.dp(12),u.dp(6),u.dp(12),u.dp(8))}
        power=floating(if(mode in setOf("live","liveStart"))"power"else"back",if(mode in setOf("live","liveStart"))"结束位置共享"else"取消",mode in setOf("live","liveStart")){
            if(mode=="live"&&ownService()!=null)confirmEnd()else finish()
        };row.addView(power,u.lp(44,44));row.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        submit=u.button(if(mode=="meeting")"设为集合点"else"发送","location.send"){send(false)}.apply{minimumWidth=u.dp(72);setPadding(u.dp(14),u.dp(6),u.dp(14),u.dp(6))};row.addView(submit,u.lp(-2,44))
        if(u.tiny)row.addView(floating("target","定位到位置标记",mode in setOf("live","liveStart")){recenterLocation()},u.lp(40,44).apply{leftMargin=u.dp(4)})
        row.addView(floating(if(mode in setOf("live","liveStart"))"minimize"else"more",if(mode in setOf("live","liveStart"))"返回聊天"else"更多位置操作",mode in setOf("live","liveStart")){if(mode in setOf("live","liveStart"))finish()else more()},u.lp(44,44).apply{leftMargin=u.dp(8)})
        top.addView(row,u.lp());participants=u.row().apply{gravity=Gravity.CENTER;tag="location.participants"};participantHost=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;isFillViewport=true;addView(participants)};top.addView(participantHost,u.lp(-1,56))
        state=u.text(if(mode=="view")""else"正在定位…",12f,if(mode in setOf("live","liveStart"))Color.WHITE else p.muted).apply{tag="location.state";gravity=Gravity.CENTER;maxLines=2;ellipsize=TextUtils.TruncateAt.END;setPadding(u.dp(12),u.dp(6),u.dp(12),u.dp(10));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};top.addView(state,u.lp())
        start=u.button("开始共享位置 · 1小时","location.start"){beginLive()}.apply{visibility=View.GONE;setOnLongClickListener{if(mode=="liveStart")chooseDuration();true}};top.addView(start,u.lp().apply{setMargins(u.dp(36),u.dp(2),u.dp(36),u.dp(10))})
    }
    private fun buildPanel(){
        val handle=FrameLayout(this).apply{tag="location.panel.handle";minimumHeight=u.dp(if(u.tiny)12 else 24);addView(View(this@LocationActivity).apply{background=BrandUi.rounded(this@LocationActivity,p.line,4)},FrameLayout.LayoutParams(u.dp(32),u.dp(4),Gravity.CENTER))}
        var y=0f;handle.setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{y=e.rawY;true};MotionEvent.ACTION_UP->{if(kotlin.math.abs(e.rawY-y)>u.dp(18)){sheetExpanded=e.rawY<y;updateLayout()};true};else->true}};handle.setOnClickListener{sheetExpanded=!sheetExpanded;updateLayout()};panel.addView(handle,u.lp(-1,if(u.tiny)12 else 24))
        val searchRow=u.row().apply{background=BrandUi.rounded(this@LocationActivity,p.soft,10);setPadding(u.dp(12),0,u.dp(4),0)};searchRow.addView(Glyph(this,"search",p.muted),u.lp(19,19))
        search=EditText(this).apply{tag="location.search";hint="搜索地点";textSize=15f;setTextColor(p.ink);setHintTextColor(p.muted);isSingleLine=true;background=null;isSaveEnabled=false;filters=arrayOf(InputFilter.LengthFilter(80));imeOptions=EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI;setPadding(u.dp(8),u.dp(10),u.dp(8),u.dp(10));setOnFocusChangeListener{_,focus->if(focus){sheetExpanded=true;updateLayout()}};setOnEditorActionListener{_,action,event->if(action==EditorInfo.IME_ACTION_SEARCH||event?.keyCode==KeyEvent.KEYCODE_ENTER){lookup(text.toString());true}else false}}
        searchRow.addView(search,LinearLayout.LayoutParams(0,-2,1f));searchRow.addView(u.icon("search","搜索地点"){lookup(search.text.toString())},u.lp(40,40));searchContainer=searchRow;panel.addView(searchRow,u.lp().apply{setMargins(u.dp(14),u.dp(2),u.dp(14),u.dp(6))})
        val selection=u.row().apply{setPadding(u.dp(16),u.dp(4),u.dp(10),u.dp(4));setOnClickListener{if(mode=="view")point?.let{LocationMaps.open(this@LocationActivity,it)}else rename()}}
        val texts=u.col();title=u.text("正在取得位置",if(u.tiny)14f else 16f,bold=true).apply{tag="location.title";maxLines=1;ellipsize=TextUtils.TruncateAt.END};detail=u.text("可拖动地图选点",if(u.tiny)10f else 12f,p.muted).apply{tag="location.coordinates";maxLines=if(u.tiny)1 else 2;ellipsize=TextUtils.TruncateAt.END};texts.addView(title,u.lp());u.gap(texts,4);texts.addView(detail,u.lp());selection.addView(texts,LinearLayout.LayoutParams(0,-2,1f));selection.addView(u.text("✓",20f,p.accent),u.lp(32,36));panel.addView(selection,u.lp())
        rows=u.col();panel.addView(ScrollView(this).apply{tag="location.places";isVerticalScrollBarEnabled=false;addView(rows)},LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun applyMode(){
        val isLive=mode in setOf("live","liveStart");panel.visibility=if(isLive)View.GONE else View.VISIBLE;participantHost.visibility=if(isLive)View.VISIBLE else View.GONE
        window.statusBarColor=if(u.tiny){if(isLive)Color.rgb(58,56,71)else p.canvas}else Color.TRANSPARENT;window.navigationBarColor=if(u.tiny)p.surface else Color.TRANSPARENT
        top.background=if(isLive)GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xa8000000.toInt(),0x65000000,Color.TRANSPARENT))else null
        submit.visibility=if(mode in setOf("send","meeting"))View.VISIBLE else View.GONE;start.visibility=if(mode=="liveStart")View.VISIBLE else View.GONE;flag.visibility=if(mode=="live")View.VISIBLE else View.GONE
        recenter.visibility=if(u.tiny)View.GONE else View.VISIBLE
        map.mode(if(mode in setOf("send","meeting"))"pick"else if(isLive)"live"else"view");searchContainer.visibility=if(mode=="view")View.GONE else View.VISIBLE
        if(isLive)window.decorView.systemUiVisibility=window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else window.decorView.systemUiVisibility=window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        updateLayout()
    }
    private fun updateLayout(){
        if(!::root.isInitialized||!::panel.isInitialized||root.height==0)return
        top.setPadding(0,safeTop,0,0)
        val liveMode=mode in setOf("live","liveStart");val available=(root.height-imeBottom).coerceAtLeast(u.dp(100))
        val desired=if(mode=="view")u.dp(if(u.tiny)90 else 160)else if(sheetExpanded)(available*.62f).toInt()else u.dp(if(u.tiny)135 else 250)
        val height=if(liveMode)0 else desired.coerceAtMost((available-u.dp(88)).coerceAtLeast(u.dp(70)))
        val pp=panel.layoutParams as FrameLayout.LayoutParams;if(pp.height!=height||pp.bottomMargin!=imeBottom){pp.height=height;pp.bottomMargin=imeBottom;panel.layoutParams=pp};panel.setPadding(0,0,0,if(imeBottom==0)safeBottom else 0)
        val mp=map.layoutParams as FrameLayout.LayoutParams;val mapHeight=if(liveMode)root.height else (available-height).coerceAtLeast(1);if(mp.height!=mapHeight){mp.height=mapHeight;map.layoutParams=mp}
        map.safeBottom(if(liveMode)(safeBottom/resources.displayMetrics.density).toInt()+8 else 6)
        map.cameraTop((top.height/resources.displayMetrics.density).toInt())
        for(button in listOf(recenter,flag)){val bp=button.layoutParams as FrameLayout.LayoutParams;val bottom=if(liveMode)safeBottom+u.dp(22)else imeBottom+height+u.dp(14);if(bp.bottomMargin!=bottom){bp.bottomMargin=bottom;button.layoutParams=bp}}
    }
    override fun onResume(){super.onResume();resumed=true;if(!valid()){finish();return};map.resume();LocationSessions.listeners.add(changed);Hub.main.post(tick)
        if(mode in setOf("send","meeting","liveStart"))locate()else if(mode=="live"){refreshRoom();restoreMeeting()}else renderSelection()
    }
    override fun onPause(){resumed=false;revision++;roomRevision++;roomBusy=false;map.pause();source?.stop();source=null;fix=null;places.cancel();geoDelay?.let{Hub.main.removeCallbacks(it)};Hub.main.removeCallbacks(tick);LocationSessions.listeners.remove(changed);dialog?.dismiss();super.onPause()}
    override fun onDestroy(){places.close();map.dispose();super.onDestroy()}
    private fun locate(force:Boolean=false){
        if(!valid()||!resumed||busy||mode !in setOf("send","meeting","liveStart"))return
        source?.stop();source=null;fix=null
        val allowed=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!allowed){if(!permissionAsked||force){permissionAsked=true;requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION),219)}else state.text="可在地图上选点，或允许定位权限";return};if(force)firstLookup=false
        state.text="正在取得当前位置…"
        source=LocationSource(this,{value->if(valid()&&resumed&&!busy){fix=value;devicePoint=LocationPayload.from(value,source!!.approximate);map.setPoint(devicePoint!!)
            if(!manual){val previous=point;val retained=address.isNotBlank()&&previous!=null&&FloatArray(1).also{Location.distanceBetween(previous.getDouble("lat"),previous.getDouble("lon"),value.latitude,value.longitude,it)}[0]<50;point=JSONObject(devicePoint.toString());if(retained)point!!.put("label",previous!!.getString("label"))else address="";if(!firstLookup&&mode!="liveStart"){firstLookup=true;lookup("")}}
            state.text=if(mode=="liveStart")"位置已就绪，点开始共享"else"";renderSelection()
        }},{message->if(valid()&&resumed)state.text=message});source!!.start()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,grants:IntArray){super.onRequestPermissionsResult(code,permissions,grants);if(code==219&&valid()){if(resumed)locate()}}
    private fun mapPoint(lat:Double,lon:Double,name:String)=LocationPayload.from(Location("map").apply{latitude=lat;longitude=lon;accuracy=0f;time=System.currentTimeMillis()},false,name)
    private fun onMapInteraction(value:JSONObject){
        if(value.optInt("_page")!=seenMapPage){seenMapPage=value.optInt("_page");seenInteraction=0}
        if(mode !in setOf("send","meeting")||value.optInt("id")<=seenInteraction)return;seenInteraction=value.getInt("id")
        val lat=value.optDouble("lat");val lon=value.optDouble("lon");if(!lat.isFinite()||!lon.isFinite()||lat !in -90.0..90.0||lon !in -180.0..180.0)return
        if(value.optBoolean("device")&&devicePoint!=null){manual=false;point=JSONObject(devicePoint.toString())}else{manual=true;point=mapPoint(lat,lon,"地图选点")}
        if(manual)map.rememberPick(point!!);label.setText("");address="";state.text="";results.clear();renderSelection();renderResults();geoDelay?.let{Hub.main.removeCallbacks(it)};geoDelay=Runnable{if(valid()&&resumed)lookup("")}.also{Hub.main.postDelayed(it,600)}
    }
    private fun lookup(query:String){
        if(!valid()||!resumed||mode !in setOf("send","meeting"))return
        geoDelay?.let{Hub.main.removeCallbacks(it)};geoDelay=null
        val current=point?:devicePoint;if(current==null){state.text="请先定位或在地图上选点";return}
        val lat=current.getDouble("lat");val lon=current.getDouble("lon");state.text=if(query.isBlank())"正在获取附近地址…"else"正在搜索地点…"
        places.find(query,lat,lon){found,error->if(valid()&&resumed){results.clear();results.addAll(found);if(query.isBlank()&&found.isNotEmpty()){address=found[0].address;if(label.text.isBlank())point?.put("label",found[0].name)};state.text=error;renderSelection();renderResults()}}
    }
    private fun renderResults(){
        rows.removeAllViews();val current=devicePoint
        for((index,place) in results.withIndex()){
            val row=u.row().apply{tag="location.place.$index";minimumHeight=u.dp(if(u.tiny)58 else 70);setPadding(u.dp(16),u.dp(10),u.dp(12),u.dp(10));isFocusable=true}
            val texts=u.col();texts.addView(u.text(place.name,15f).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END},u.lp());u.gap(texts,6)
            val distance=current?.let{val m=place.distance(it.getDouble("lat"),it.getDouble("lon"));if(m>=1000)String.format(Locale.CHINA,"%.1f公里",m/1000)else"${m.toInt()}米"}
            texts.addView(u.text(listOfNotNull(distance,place.address.takeIf{it.isNotBlank()}).joinToString(" | "),12f,p.muted).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END},u.lp());row.addView(texts,LinearLayout.LayoutParams(0,-2,1f));row.addView(u.text("›",20f,p.muted),u.lp(20,32))
            row.setOnClickListener{if(valid()&&!busy){manual=true;point=mapPoint(place.lat,place.lon,place.name);address=place.address;label.setText("");map.pick(point!!);devicePoint?.let{map.setPoint(it)};hideKeyboard();sheetExpanded=false;renderSelection();updateLayout()}};rows.addView(row,u.lp())
        }
    }
    private fun renderSelection(){
        val value=point;title.text=label.text.toString().ifBlank{value?.optString("label")?:"拖动地图选择地点"};detail.text=if(mode in setOf("send","meeting")&&state.text.isNotBlank())state.text else address.ifBlank{value?.let{if(manual)"地图选点 · "+LocationPayload.coordinates(it)else LocationPayload.accuracy(it)}?:"也可以搜索地点"}
        submit.isEnabled=value!=null&&!busy;submit.alpha=if(submit.isEnabled)1f else .45f;start.isEnabled=fix?.let{source?.fresh(it)==true}==true&&!busy;start.alpha=if(start.isEnabled)1f else .45f
        if(mode !in setOf("live","liveStart"))state.visibility=View.GONE
        if(mode in setOf("view","live"))value?.let{map.setPoint(it)}
    }
    private fun rename(){if(mode !in setOf("send","meeting")||busy)return;val old=label.parent as? android.view.ViewGroup;old?.removeView(label);label.setText(label.text.toString().ifBlank{point?.optString("label").orEmpty()});dialog=AlertDialog.Builder(this).setTitle("位置名称").setView(label).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->renderSelection()}.create().also{it.show()}}
    private fun hideKeyboard(){currentFocus?.let{getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0)};search.clearFocus();root.requestFocus()}
    private fun recenterLocation(){if(mode in setOf("send","meeting")){if(fix==null)locate(true)else{manual=false;point=devicePoint?.let{JSONObject(it.toString())};label.setText("");map.center();lookup("")}}else map.center()}
    private fun more(){
        val actions=mutableListOf<ActionSheets.Action>()
        if(mode in setOf("send","meeting"))actions.add(ActionSheets.Action("location","重新定位"){locate(true)})
        point?.let{value->
            actions.add(ActionSheets.Action("target","使用其他地图/导航"){LocationMaps.open(this,value)})
            actions.add(ActionSheets.Action("eye","坐标与精度"){AlertDialog.Builder(this).setTitle("位置详情").setMessage(LocationPayload.coordinates(value)+"\n"+(if(manual)"地图选点"else LocationPayload.accuracy(value))).setPositiveButton("关闭",null).show()})
        }
        if(mode in setOf("send","meeting","liveStart"))actions.add(ActionSheets.Action("settings","系统定位设置"){startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))})
        dialog=ActionSheets.show(this,"位置",actions=actions)
    }
    private fun ownService()=LocationShareService.instance?.takeIf{it.peer==peer}
    private fun beginLive(){
        if(mode!="liveStart"||busy||!valid()||!resumed)return
        val existing=LocationShareService.instance
        if(existing!=null){if(existing.peer==peer){mode="live";live=existing.shareId;applyMode();refreshRoom()}else state.text="你正在其他聊天共享位置，请先结束那次共享";return}
        if(!getSystemService(NotificationManager::class.java).areNotificationsEnabled()){state.text="请开启共享通知后重试";if(Build.VERSION.SDK_INT>=33)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),220);return}
        val value=fix?.takeIf{source?.fresh(it)==true}?.let{LocationPayload.from(it,source!!.approximate)}?:run{state.text="等待定位完成后可开始共享";return};send(true,value)
    }
    private fun send(realtime:Boolean,livePoint:JSONObject?=null){
        if(!valid()||!resumed||busy)return
        val chosen=livePoint?:point?:return;val name=label.text.toString().trim().ifBlank{chosen.getString("label")}
        val value=JSONObject(chosen.toString()).put("label",if(mode=="meeting")("集合点 · "+name).take(60)else name)
        if(runCatching{LocationPayload.validate(value)}.isFailure){label.error="位置名称含有不支持的字符";state.text="请修改位置名称后发送";return}
        if(!realtime&&!manual&&fix?.let{source?.fresh(it)==true}!=true){state.text="当前位置已过期，请重新定位";return}
        val meetingMode=mode=="meeting";val mapSelection=manual;val originalAddress=address;val opening=revision;busy=true;renderSelection();state.text=if(realtime)"正在开始共享…"else"正在发送…"
        Hub.job({var metadata:JSONObject?=null
            try{check(valid()&&resumed&&revision==opening)
                if(realtime){val epoch=if(ChatAddress.group(peer))Hub.api("GET","/api/groups/$peer").getJSONObject("group").getInt("epoch")else 0;metadata=Hub.api("POST","/api/locations",JSONObject().put("id",UUID.randomUUID().toString()).put("peer",peer).put("duration",duration).put("epoch",epoch)).getJSONObject("share");value.put("live_id",metadata.getString("id")).put("until",metadata.getLong("until"))}
                check(valid()&&resumed&&revision==opening);val payload=LocationPayload.packet(value,peer);if(!realtime){if(mapSelection)payload.put("location_source","map");if(originalAddress.isNotBlank())payload.put("location_address",originalAddress);if(meetingMode)payload.put("location_role","meeting")}
                val result=Outbox.send(payload,session,owner);if(realtime&&result.optBoolean("pending")){Outbox.cancel(payload.getString("client_id"));throw Exception("位置卡片暂未送达，请重试")};JSONObject().put("message",result).apply{metadata?.let{put("share",it)}}
            }catch(e:Exception){metadata?.let{runCatching{Hub.rawApi("DELETE","/api/locations/"+it.getString("id"),session=session)}};throw e}
        },{result->
            val metadata=result.optJSONObject("share");if(!valid()||!resumed||opening!=revision){metadata?.let{m->Hub.io.execute{runCatching{Hub.rawApi("DELETE","/api/locations/"+m.getString("id"),session=session)}}};return@job}
            busy=false;val message=result.getJSONObject("message");LocationSessions.observeMeeting(message);setResult(RESULT_OK,Intent().putExtra("message",message.toString()))
            if(metadata!=null){try{startForegroundService(Intent(this,LocationShareService::class.java).setAction("start").putExtra("opening",session).putExtra("owner",owner).putExtra("share",metadata.toString()))}catch(_:Exception){state.text="未能开始共享，请重试";Hub.io.execute{runCatching{Hub.rawApi("DELETE","/api/locations/"+metadata.getString("id"),session=session)}};return@job}
                live=metadata.getString("id");intent.putExtra("live",live).putExtra("mode","live");mode="live";source?.stop();source=null;fix=null;point=value;applyMode();LocationSessions.remember(JSONObject().put("share",metadata).put("location",value).put("user",JSONObject(Hub.me.toString())));refreshRoom()
            }else finish()
        },{error->if(valid()){busy=false;state.text=error;renderSelection()}})
    }
    private fun refreshRoom(){
        if(!valid()||!resumed||roomBusy||mode!="live")return;roomBusy=true;val expected=++roomRevision;val at=SystemClock.elapsedRealtime()
        Hub.job({val result=Hub.api("GET","/api/locations?peer="+android.net.Uri.encode(peer));val values=result.getJSONArray("shares");val decoded=JSONArray()
            for(n in 0 until values.length()){val item=values.getJSONObject(n);val s=item.getJSONObject("share");if(s.optString("id")==live&&s.optString("owner")!=expectedSender&&intent.hasExtra("location"))continue
                val value=item.optJSONObject("update")?.let{ChatCrypto.openLocation(it)}?:JSONObject().put("share",s);item.optJSONObject("user")?.let{value.put("user",it)};decoded.put(value)};JSONObject().put("values",decoded)
        },{result->if(valid()&&resumed&&expected==roomRevision){roomBusy=false;val values=result.getJSONArray("values");val ids=mutableSetOf<String>();for(n in 0 until values.length()){val value=values.getJSONObject(n);ids.add(value.getJSONObject("share").getString("id"));LocationSessions.remember(value)};LocationSessions.reconcile(peer,ids,at);renderRoom()}},{error->if(valid()&&expected==roomRevision){roomBusy=false;state.text=error}})
    }
    private fun renderRoom(){
        if(!::participants.isInitialized||!valid()||mode!="live")return
        val entries=LocationSessions.room(peer).sortedBy{if(it.getJSONObject("share").getString("owner")==owner)""else it.getJSONObject("share").getString("owner")};val actorArray=JSONArray();participants.removeAllViews()
        for((index,entry) in entries.withIndex()){
            val s=entry.getJSONObject("share");val uid=s.getString("owner");val user=entry.optJSONObject("user")?:if(uid==owner)Hub.me else if(!ChatAddress.group(peer))peerInfo else JSONObject().put("id",uid).put("nickname","群成员")
            val badge=badges.getOrPut(uid){MapPersonBadge(this,p,JSONObject(user.toString())){if(valid()&&resumed)renderRoom()}};val parent=badge.parent as? android.view.ViewGroup;parent?.removeView(badge);val opaque="p$index";badge.setOnClickListener{focused=uid;map.focus(opaque)};participants.addView(badge,u.lp(42,42).apply{setMargins(u.dp(4),0,u.dp(4),0)})
            entry.optJSONObject("location")?.let{location->actorArray.put(JSONObject().put("key",opaque).put("name",user.optString("nickname","共享成员")).put("avatar",badge.data()).put("lat",location.getDouble("lat")).put("lon",location.getDouble("lon")));if(focused==uid||point==null||uid==owner&&focused.isEmpty()){point=JSONObject(location.toString());map.setPoint(point!!)}}
        }
        map.actors(actorArray);map.meeting(LocationSessions.meeting(peer));val stale=entries.any{it.optBoolean("disconnected")||it.optJSONObject("location")?.let{v->System.currentTimeMillis()-v.optLong("at")>45000}==true};state.text=if(stale)"部分位置暂未更新"else if(ownService()!=null)"你正在共享位置 · ${entries.size}人"else if(entries.isNotEmpty())"${entries.size}人正在共享位置"else"当前没有人在共享位置"
        if(ownService()==null){start.visibility=View.VISIBLE;start.text="共享我的位置";start.isEnabled=true;start.alpha=1f;start.setOnClickListener{mode="liveStart";intent.putExtra("mode",mode);applyMode();locate(true)}}else start.visibility=View.GONE
    }
    private fun chooseDuration(){dialog=AlertDialog.Builder(this).setTitle("共享时长").setSingleChoiceItems(arrayOf("15分钟","1小时","8小时"),listOf(900,3600,28800).indexOf(duration)){d,n->duration=listOf(900,3600,28800)[n];start.text="开始共享位置 · "+listOf("15分钟","1小时","8小时")[n];d.dismiss()}.show()}
    private fun confirmEnd(){dialog=LocationSheets.end(this,{valid()}){val service=ownService();if(service!=null)service.end()else if(live.isNotEmpty()&&LocationSessions.get(live)?.getJSONObject("share")?.optString("owner")==owner)Hub.io.execute{runCatching{Hub.rawApi("DELETE","/api/locations/$live",session=session)}};finish()}}
    private fun openMeeting(){if(!valid())return;startActivityForResult(Intent(this,LocationActivity::class.java).putExtra("peer",peer).putExtra("peer_info",peerInfo.toString()).putExtra("mode","meeting").apply{point?.let{putExtra("center",it.toString())}},221)}
    private fun restoreMeeting(){val token=session;Hub.io.execute{runCatching{LocalData.messages(owner,peer,200)}.getOrNull()?.let{values->Hub.main.post{if(Hub.token==token&&valid()){for(n in 0 until values.length())LocationSessions.observeMeeting(values.getJSONObject(n));renderRoom()}}}}}
    @Deprecated("Android result API") override fun onActivityResult(code:Int,result:Int,data:Intent?){super.onActivityResult(code,result,data);if(code==221&&result==RESULT_OK&&valid())data?.getStringExtra("message")?.let{runCatching{LocationSessions.observeMeeting(JSONObject(it))}}}
}
