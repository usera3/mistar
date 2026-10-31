package cn.qingliao.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Native preview/viewer. Reading another person's position never asks for the viewer's GPS. */
class LocationActivity:Activity(){
    private val owner=Hub.me.optString("id");private val session=Hub.token
    private val p by lazy{ThemeStore.current(this)};private val u by lazy{FormUi(this,p)}
    private val peer by lazy{intent.getStringExtra("peer").orEmpty()}
    private val live by lazy{intent.getStringExtra("live").orEmpty()}
    private val expectedSender by lazy{intent.getStringExtra("sender")?:owner}
    private val viewing by lazy{intent.hasExtra("location")||live.isNotEmpty()}
    private var source:LocationSource?=null;private var point:JSONObject?=null;private var fix:Location?=null
    private var busy=false;@Volatile private var resumed=false;@Volatile private var revision=0;private var duration=3600;private var permissionAsked=false;private var liveChecked=false;private var liveUnavailable=false
    private lateinit var content:LinearLayout;private lateinit var state:TextView;private lateinit var title:TextView;private lateinit var coordinates:TextView;private lateinit var precision:TextView;private lateinit var time:TextView
    private var send:TextView?=null;private var shareButton:TextView?=null;private var stop:TextView?=null;private var label:EditText?=null
    private var map:LocationMapView?=null
    private val changed:()->Unit={if(valid())renderLive()}
    private val tick=object:Runnable{override fun run(){if(resumed&&valid()){if(viewing)renderLive();else renderPoint();Hub.main.postDelayed(this,1000)}}}
    private fun valid()=session.isNotEmpty()&&Hub.token==session&&Hub.me.optString("id")==owner&&!isFinishing&&!isDestroyed
    override fun finish(){super.finish();Motion.activityClose(this)}
    override fun onCreate(saved:Bundle?){
        super.onCreate(saved);if(!valid()||!ChatAddress.valid(peer)){finish();return}
        point=intent.getStringExtra("location")?.let{runCatching{JSONObject(it).also{value->LocationPayload.validate(value)}}.getOrNull()}
        window.statusBarColor=p.canvas;window.navigationBarColor=p.canvas;window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=u.col().apply{setBackgroundColor(p.canvas)}
        root.setOnApplyWindowInsetsListener{v,i->if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom)}else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i}
        val header=u.row();header.addView(u.icon("back","返回"){finish()},u.lp(48,48));header.addView(u.text(if(viewing)if(live.isEmpty())"位置详情"else"实时位置"else"分享位置",18f,bold=true),LinearLayout.LayoutParams(0,-2,1f));root.addView(header,u.lp())
        content=u.col().apply{val pad=u.dp(if(u.tiny)10 else 20);setPadding(pad,u.dp(8),pad,u.dp(16))};root.addView(ScrollView(this).apply{addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        val card=u.card();map=LocationMapView(this,p);card.addView(map,u.lp(-1,if(u.tiny)150 else 260));u.gap(card,14)
        title=u.text("正在定位…",if(u.tiny)17f else 21f,bold=true).apply{tag="location.title"};coordinates=u.text("",14f,p.ink).apply{tag="location.coordinates"};precision=u.text("",12f,p.muted).apply{tag="location.precision"};time=u.text("",11f,p.muted).apply{tag="location.time"}
        for(view in listOf(title,coordinates,precision,time)){card.addView(view,u.lp());u.gap(card,8)};content.addView(card,u.lp())
        state=u.text("",12f,p.accent).apply{tag="location.state";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};u.gap(content,12);content.addView(state,u.lp())
        if(!viewing){
            label=EditText(this).apply{hint="位置名称（可选）";setTextColor(p.ink);setHintTextColor(p.muted);textSize=14f;isSingleLine=true;filters=arrayOf(android.text.InputFilter.LengthFilter(60));isSaveEnabled=false;tag="location.label"};content.addView(label,u.lp())
            content.addView(u.link("重新定位","location.refresh"){locate(true)},u.lp());content.addView(u.link("系统定位设置","location.settings"){startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))},u.lp())
            content.addView(u.link("共享通知设置","location.notifications"){startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))},u.lp())
            val spinner=Spinner(this).apply{tag="location.duration";adapter=ArrayAdapter(this@LocationActivity,android.R.layout.simple_spinner_dropdown_item,listOf("共享15分钟","共享1小时","共享8小时"));setSelection(1);onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(parent:AdapterView<*>?){};override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){duration=listOf(900,3600,28800)[position]}}};content.addView(spinner,u.lp(-1,48))
            content.addView(u.text("发送前可预览；实时共享可随时停止。底图由OpenStreetMap提供，会请求当前查看的地图区域。",11f,p.muted),u.lp())
            val footer=u.row().apply{setPadding(u.dp(8),u.dp(4),u.dp(8),u.dp(6))};send=u.button("发送位置","location.send"){send(false)};shareButton=u.button("实时共享","location.start",false){send(true)}
            footer.addView(send,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=u.dp(4)});footer.addView(shareButton,LinearLayout.LayoutParams(0,-2,1f));root.addView(footer,u.lp())
        }else{
            content.addView(u.button("使用其他地图","location.map",false){point?.let{LocationMaps.open(this,it)}},u.lp());u.gap(content,8)
            content.addView(u.button("网页地图","location.web",false){point?.let{LocationMaps.browser(this,it)}},u.lp());u.gap(content,8)
            if(live.isNotEmpty()){stop=u.button("停止我的共享","location.stop",false){stopShare()}.apply{visibility=View.GONE};content.addView(stop,u.lp());content.addView(u.link("重新连接","location.reconnect"){refreshLive()},u.lp());content.addView(u.text("分享自己的位置需从聊天中主动开启。点开地图会将此坐标交给所选地图服务。",11f,p.muted),u.lp())}
            else content.addView(u.text("这是发送时的位置快照，之后移动不会自动更新。点开地图会将此坐标交给所选地图服务。",11f,p.muted),u.lp())
        }
        setContentView(root);root.requestApplyInsets();renderPoint()
    }
    override fun onResume(){super.onResume();resumed=true;if(!valid()){finish();return};map?.resume();LocationSessions.listeners.add(changed);Hub.main.post(tick);if(viewing){if(live.isNotEmpty())refreshLive()}else locate()}
    override fun onPause(){resumed=false;revision++;map?.pause();source?.stop();source=null;fix=null;Hub.main.removeCallbacks(tick);LocationSessions.listeners.remove(changed);super.onPause()}
    override fun onDestroy(){map?.dispose();source?.stop();Hub.main.removeCallbacks(tick);LocationSessions.listeners.remove(changed);super.onDestroy()}
    private fun locate(askAgain:Boolean=false){
        if(!valid()||!resumed||viewing||busy)return
        source?.stop();source=null;fix=null;point=null;renderPoint()
        val permitted=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!permitted){if(!permissionAsked||askAgain){permissionAsked=true;requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION),219)}else state.text="未获得定位权限，点重新定位可再次申请";return}
        state.text="正在取得当前位置…"
        source=LocationSource(this,{location->if(valid()&&resumed&&!busy){fix=location;point=LocationPayload.from(location,source!!.approximate);state.text="定位完成，请检查后发送";renderPoint()}},{message->if(valid()&&resumed){state.text=message;renderPoint()}});source!!.start()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,grants:IntArray){
        super.onRequestPermissionsResult(code,permissions,grants)
        if(code==219&&valid()){
            val allowed=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            if(allowed){if(resumed)locate()}else{state.text="未获得定位权限，可重试或在系统设置中开启";renderPoint()}
        }
    }
    private fun renderPoint(){
        val value=point;title.text=value?.optString("label")?:if(viewing)"暂未取得共享位置"else"等待定位"
        coordinates.text=value?.let{LocationPayload.coordinates(it)}.orEmpty();precision.text=value?.let{LocationPayload.accuracy(it)}.orEmpty()
        time.text=value?.let{"采集于 "+SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(Date(it.getLong("at")))}.orEmpty()
        value?.let{map?.setPoint(it)};map?.syncVisible()
        val available=!busy&&fix?.let{source?.fresh(it)==true&&source?.permitted()==true}==true
        send?.apply{isEnabled=available;alpha=if(available)1f else .45f};shareButton?.apply{isEnabled=available;alpha=if(available)1f else .45f}
    }
    private fun task(work:()->JSONObject,done:(JSONObject)->Unit,error:(String)->Unit={state.text=it}){
        if(busy||!valid())return;busy=true;renderPoint();val expected=revision
        Hub.job(work,{result->
            if(valid()){busy=false;if(expected==revision||viewing)done(result)else{result.optJSONObject("share")?.let{share->Hub.io.execute{runCatching{Hub.rawApi("DELETE","/api/locations/"+share.getString("id"),session=session)}}};state.text="页面已离开，请重新开始共享"};renderPoint()}
        },{if(valid()){busy=false;error(it);renderPoint()}})
    }
    private fun send(realtime:Boolean){
        if(!valid()||!resumed||busy)return
        if(realtime&&!getSystemService(NotificationManager::class.java).areNotificationsEnabled()){
            state.text="实时共享需要显示停止通知，请开启通知后重试"
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),220)
            return
        }
        val latest=fix?:return;val locSource=source?:return
        if(!locSource.fresh(latest)||!locSource.permitted()){state.text="当前位置已过期，请重新定位";return}
        if(realtime&&LocationShareService.instance!=null){val service=LocationShareService.instance!!;startActivity(Intent(this,LocationActivity::class.java).putExtra("peer",service.peer).putExtra("live",service.shareId));return}
        val value=runCatching{LocationPayload.from(latest,locSource.approximate,label?.text?.toString().orEmpty().ifBlank{"我的位置"})}.getOrElse{label?.error="位置名称含有不支持的字符";state.text="请修改位置名称后发送";return};val opening=revision
        task({
            check(valid()&&resumed&&revision==opening)
            var share:JSONObject?=null
            try{
                if(realtime){
                    val epoch=if(ChatAddress.group(peer))Hub.api("GET","/api/groups/$peer").getJSONObject("group").getInt("epoch")else 0
                    share=Hub.api("POST","/api/locations",JSONObject().put("id",UUID.randomUUID().toString()).put("peer",peer).put("duration",duration).put("epoch",epoch)).getJSONObject("share")
                    value.put("live_id",share.getString("id")).put("until",share.getLong("until"))
                }
                check(valid()&&resumed&&revision==opening)
                val packet=LocationPayload.packet(value,peer);val result=Outbox.send(packet,session,owner)
                if(realtime&&result.optBoolean("pending")){Outbox.cancel(packet.getString("client_id"));throw Exception("位置卡片暂未送达，请重试")}
                JSONObject().put("message",result).apply{share?.let{put("share",it)}}
            }catch(e:Exception){share?.let{runCatching{Hub.rawApi("DELETE","/api/locations/"+it.getString("id"),session=session)}};throw e}
        },{result->
            if(realtime){
                val share=result.getJSONObject("share")
                if(!resumed||!valid()||revision!=opening){Hub.io.execute{runCatching{Hub.rawApi("DELETE","/api/locations/"+share.getString("id"),session=session)}};return@task}
                try{startForegroundService(Intent(this,LocationShareService::class.java).setAction("start").putExtra("opening",session).putExtra("owner",owner).putExtra("share",share.toString()))}
                catch(_:Exception){Hub.io.execute{runCatching{Hub.rawApi("DELETE","/api/locations/"+share.getString("id"),session=session)}};state.text="未能开始持续共享，请重试";return@task}
            }
            setResult(RESULT_OK,Intent().putExtra("message",result.getJSONObject("message").toString()));finish()
        })
    }
    private fun refreshLive(){
        if(!valid()||!resumed||busy)return
        task({
            val result=try{Hub.api("GET","/api/locations/$live")}catch(e:ApiFailure){if(e.status in listOf(401,403,404))return@task JSONObject().put("closed",true)else throw e};val s=result.getJSONObject("share")
            val conversation=if(ChatAddress.group(s.getString("peer")))s.getString("peer")else if(s.getString("owner")==owner)s.getString("peer")else s.getString("owner")
            require(s.getString("id")==live&&conversation==peer&&s.getString("owner")==expectedSender)
            point?.takeIf{it.optString("live_id")==live}?.let{require(it.getLong("until")==s.getLong("until"))}
            result.optJSONObject("update")?.let{ChatCrypto.openLocation(it)}?:JSONObject().put("share",s)
        },{liveChecked=true;liveUnavailable=false;if(it.optBoolean("closed"))LocationSessions.close(live)else LocationSessions.remember(it);renderLive()},{liveChecked=true;liveUnavailable=true;renderLive()})
    }
    private fun renderLive(){
        if(live.isEmpty()){renderPoint();return}
        val latest=LocationSessions.get(live);val share=latest?.optJSONObject("share")
        if(share!=null&&(share.optString("owner")!=expectedSender||point?.takeIf{it.optString("live_id")==live}?.let{it.optLong("until")!=share.optLong("until")}==true)){state.text="共享身份不匹配，无法显示更新";stop?.visibility=View.GONE;return}
        latest?.optJSONObject("location")?.let{point=it};renderPoint()
        val active=share!=null&&!LocationSessions.isEnded(live)&&share.getLong("until")>System.currentTimeMillis()
        val age=point?.let{System.currentTimeMillis()-it.optLong("at")}?:Long.MAX_VALUE
        state.text=if(latest==null&&!LocationSessions.isEnded(live)&&!liveChecked)"正在连接实时共享…"else if(liveUnavailable)"暂时无法连接 · 可点重新连接"else if(!active)"实时共享已结束 · 显示最后收到的位置快照"else if(latest!!.optBoolean("disconnected")||age>45000)"暂未更新，请留意采集时间"else"实时共享中 · 剩余${((share!!.getLong("until")-System.currentTimeMillis())/60000+1).coerceAtLeast(1)}分钟"
        stop?.visibility=if(active&&share!!.getString("owner")==owner)View.VISIBLE else View.GONE
    }
    private fun stopShare(){
        if(LocationShareService.instance?.shareId==live){LocationShareService.instance!!.end();renderLive();return}
        task({Hub.api("DELETE","/api/locations/$live")},{LocationSessions.close(live);renderLive()})
    }
}
