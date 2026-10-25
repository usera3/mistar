package cn.qingliao.app

import android.app.Activity
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*

object MemberUi {
    const val GATEWAY="https://pay.claudcode.xyz"
    fun active(user:JSONObject):Boolean {
        val own=user.optJSONObject("membership")
        return if(own!=null)own.optBoolean("active")&&own.optLong("expires_at")>System.currentTimeMillis() else user.optBoolean("member")
    }
    fun checkout(url:String):Boolean=runCatching {
        val u=URI(url);val expected=URI(GATEWAY)
        url.length<=2048&&u.scheme=="https"&&u.host==expected.host&&u.rawUserInfo==null&&u.port in listOf(-1,443)&&u.rawQuery==null&&u.rawFragment==null&&u.rawPath.matches(Regex("/pay/[A-Za-z0-9._~-]{16,1024}"))
    }.getOrDefault(false)
    fun price(cents:Int)="¥"+(cents/100)+"."+(cents%100).toString().padStart(2,'0')
}

/** Own-account events on Hub's main thread; never takes over the chat/call listener. */
object MembershipEvents {
    val listeners=linkedSetOf<(JSONObject)->Unit>()
    fun publish(event:JSONObject){listeners.toList().forEach{runCatching{it(event)}}}
}

/** Pure decoration on the existing avatar bounds; does not intercept avatar/message gestures. */
class MemberFrame(context:Context):View(context) {
    init { isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas:Canvas) {
        val d=resources.displayMetrics.density;val pad=2*d
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=2*d;shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat(),intArrayOf(0xffffdc8b.toInt(),0xffba8c38.toInt(),0xfff5d99a.toInt()),null,Shader.TileMode.CLAMP)}
        canvas.drawCircle(width/2f,height/2f,minOf(width,height)/2f-pad,p)
        p.shader=null;p.style=Paint.Style.FILL;p.color=0xffd9ac54.toInt()
        val x=width*.77f;val y=height*.2f;val r=width*.075f
        val star=Path().apply{moveTo(x,y-r);quadTo(x+r*.2f,y-r*.2f,x+r,y);quadTo(x+r*.2f,y+r*.2f,x,y+r);quadTo(x-r*.2f,y+r*.2f,x-r,y);quadTo(x-r*.2f,y-r*.2f,x,y-r)}
        canvas.drawPath(star,p)
    }
}

class MembershipActivity:Activity() {
    private val palette by lazy{ThemeStore.current(this)}
    private val session=Hub.token
    private val owner=Hub.me.optString("id")
    private lateinit var form:LinearLayout
    private lateinit var scroll:ScrollView
    private var model=JSONObject()
    private var orders=JSONArray()
    private var selected="month"
    private var focused=""
    private var clientId=""
    private var clientPlan=""
    private var busy=false
    private var revision=0
    private var error=""
    private var checkOnResume=false
    private var lastCheck=0L
    private var qr:Bitmap?=null
    private var qrUrl=""
    private var revealOrder=false
    private val events:(JSONObject)->Unit={event->
        if(valid()){
            val current=event.optJSONObject("membership")?:event.optJSONObject("user")?.optJSONObject("membership")
            if(current!=null){
                adopt(current)
                if(!busy){
                    if(focused.isNotEmpty())work({Hub.rawApi("GET","/api/payments/orders/$focused",session=session)},{update(it)})else render()
                }
            }
        }
    }
    private fun valid()=session.isNotEmpty()&&owner.isNotEmpty()&&Hub.token==session&&Hub.me.optString("id")==owner&&!isFinishing&&!isDestroyed
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun date(n:Long)=SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(Date(n))
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun label(text:String,size:Float=14f,color:Int=palette.ink,bold:Boolean=false)=TextView(this).apply{this.text=text;textSize=size;setTextColor(color);if(bold)setTypeface(typeface,Typeface.BOLD);setLineSpacing(dp(3).toFloat(),1f)}
    private fun gap(parent:LinearLayout,n:Int=12){parent.addView(View(this),LinearLayout.LayoutParams(1,dp(n)))}
    private fun shape(color:Int,stroke:Int?=null)=GradientDrawable().apply{setColor(color);cornerRadius=dp(16).toFloat();stroke?.let{setStroke(dp(1),it)}}
    private fun card()=column().apply{setPadding(dp(16),dp(16),dp(16),dp(16));background=shape(palette.surface);form.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})}
    private fun action(parent:LinearLayout,title:String,primary:Boolean=true,enabled:Boolean=true,click:()->Unit):TextView {
        val v=label(title,14f,if(primary)palette.onPrimary else palette.accent,true).apply{
            gravity=Gravity.CENTER;minimumHeight=dp(48);setPadding(dp(10),dp(12),dp(10),dp(12));background=if(primary)BrandUi.primary(this@MembershipActivity,palette) else shape(palette.soft)
            isEnabled=enabled&&!busy;alpha=if(isEnabled)1f else .5f;setOnClickListener{if(valid()&&!busy)click()};Motion.press(this)
        }
        parent.addView(v,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)});return v
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(!valid()){finish();return}
        window.statusBarColor=palette.canvas;window.navigationBarColor=palette.canvas
        val root=column().apply{setBackgroundColor(palette.canvas)}
        root.setOnApplyWindowInsetsListener{v,i->v.setPadding(0,i.systemWindowInsetTop,0,i.systemWindowInsetBottom);i}
        root.addView(label("‹  弥星会员",19f,palette.ink,true).apply{setPadding(dp(16),dp(12),dp(16),dp(12));minimumHeight=dp(48);contentDescription="返回";setOnClickListener{finish()}})
        scroll=ScrollView(this).apply{isFillViewport=true};form=column().apply{setPadding(dp(16),dp(8),dp(16),dp(24))};scroll.addView(form)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);root.requestApplyInsets();render();load()
    }
    override fun onResume(){super.onResume();if(!valid()){finish();return};if(checkOnResume&&focused.isNotEmpty()&&SystemClock.elapsedRealtime()-lastCheck>10000){checkOnResume=false;refresh(focused)} }
    override fun onStart(){super.onStart();MembershipEvents.listeners.add(events)}
    override fun onStop(){MembershipEvents.listeners.remove(events);super.onStop()}
    override fun onDestroy(){MembershipEvents.listeners.remove(events);revision++;qr=null;super.onDestroy()}
    private fun work(task:()->JSONObject,done:(JSONObject)->Unit) {
        if(!valid()||busy)return
        val generation=revision;busy=true;error="";render()
        Hub.job({check(Hub.token==session&&Hub.me.optString("id")==owner);task()},{value->
            if(!valid()||revision!=generation)return@job
            busy=false;done(value);render()
        },{message->if(valid()&&revision==generation){busy=false;error=message;render()}})
    }
    private fun load(){work({
        val result=Hub.rawApi("GET","/api/membership",session=session)
        result.put("history",Hub.rawApi("GET","/api/payments/orders",session=session).optJSONArray("orders")?:JSONArray());result
    },{val previous=model.optJSONObject("membership");model=it;orders=it.optJSONArray("history")?:JSONArray();adopt(previous);adopt(it.optJSONObject("membership"))})}
    private fun adopt(value:JSONObject?) {
        if(value==null||!valid())return
        val previous=model.optJSONObject("membership")
        if(previous!=null&&(value.optLong("server_time")<previous.optLong("server_time")||value.optLong("server_time")==previous.optLong("server_time")&&value.optLong("expires_at")<previous.optLong("expires_at")))return
        model.put("membership",value);Hub.saveMe(JSONObject(Hub.me.toString()).put("membership",value).put("member",value.optBoolean("active")))
    }
    private fun update(result:JSONObject) {
        adopt(result.optJSONObject("membership"))
        result.optJSONObject("order")?.let{o->focused=o.getString("id");revealOrder=true;if(o.optString("state") in listOf("paid","closed")){clientId="";clientPlan=""};val kept=JSONArray().put(o);for(n in 0 until orders.length()){val old=orders.getJSONObject(n);if(old.optString("id")!=focused)kept.put(old)};orders=kept}
    }
    private fun buy() {
        if(Hub.call!=null){error="请先结束通话，再进行付款";render();return}
        if(clientId.isEmpty()||clientPlan!=selected){clientId=UUID.randomUUID().toString();clientPlan=selected}
        val body=JSONObject().put("plan_id",selected).put("client_id",clientId)
        work({Hub.rawApi("POST","/api/payments/orders",body,session)},{update(it)})
    }
    private fun refresh(id:String) {
        lastCheck=SystemClock.elapsedRealtime()
        work({Hub.rawApi("POST","/api/payments/orders/$id/refresh",JSONObject(),session)},{update(it)})
    }
    private fun retry(id:String){work({Hub.rawApi("POST","/api/payments/orders/$id/retry",JSONObject(),session)},{update(it)})}
    private fun open(order:JSONObject) {
        if(!valid()||Hub.call!=null){error="请先结束通话，再打开付款页";render();return}
        val url=order.optString("pay_url")
        if(!MemberUi.checkout(url)){error="付款地址无法验证，请刷新订单";render();return}
        try{focused=order.getString("id");checkOnResume=true;startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))}
        catch(_:ActivityNotFoundException){checkOnResume=false;error="此设备没有可用浏览器，请用手机扫描订单二维码打开付款页";render()}
    }
    private fun copy(value:String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("弥星会员订单",value))
        if(android.os.Build.VERSION.SDK_INT<33)Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show()
    }
    private fun render() {
        if(!::form.isInitialized)return
        val y=scroll.scrollY;form.removeAllViews()
        val compact=resources.configuration.screenWidthDp<340
        val member=model.optJSONObject("membership")?:JSONObject()
        val hero=card().apply{background=StarlightDrawable(palette,dp(16).toFloat())}
        hero.addView(label(if(member.optBoolean("active"))"✦  你的星光正在闪耀" else "✦  让相伴，多一点星光",if(compact)19f else 25f,palette.ink,true));gap(hero,8)
        hero.addView(label(if(member.optBoolean("active"))"会员有效至 "+date(member.optLong("expires_at")) else "为喜欢的日常，添一份专属感。",13f,palette.muted))
        if(error.isNotEmpty()){card().addView(label(error,13f,palette.accent));action(form,"重新加载",false){load()}}
        if(busy)form.addView(label("正在处理，请稍候…",13f,palette.muted).apply{setPadding(0,0,0,dp(12))})
        val plans=model.optJSONArray("plans")?:JSONArray()
        if(plans.length()>0){
            form.addView(label("选择会员方案",15f,palette.ink,true));gap(form)
            val group=LinearLayout(this).apply{orientation=if(compact)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL};form.addView(group)
            for(n in 0 until plans.length()){
                val plan=plans.getJSONObject(n);val id=plan.getString("id");val on=selected==id
                val cell=column().apply{setPadding(dp(12),dp(14),dp(12),dp(14));background=shape(if(on)palette.soft else palette.surface,if(on)palette.accent else palette.line);contentDescription="选择"+plan.getString("name");isSelected=on;setOnClickListener{if(!busy){selected=id;render()}}}
                cell.addView(label((if(on)"✓  "else"")+plan.getString("name"),14f,palette.ink,true));gap(cell,6)
                cell.addView(label(MemberUi.price(plan.getInt("cents")),if(compact)23f else 21f,palette.accent,true));gap(cell,3)
                cell.addView(label(plan.getInt("days").toString()+" 天",12f,palette.muted))
                group.addView(cell,if(compact)LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)}else LinearLayout.LayoutParams(0,-2,1f).apply{if(n<plans.length()-1)marginEnd=dp(8)})
            }
            var chosen=plans.getJSONObject(0);for(n in 0 until plans.length())if(plans.getJSONObject(n).optString("id")==selected)chosen=plans.getJSONObject(n)
            action(form,if(model.optBoolean("enabled"))MemberUi.price(chosen.getInt("cents"))+(if(member.optBoolean("active"))"  续费"else"  开通")+chosen.getString("name")else"支付维护中",enabled=model.optBoolean("enabled")){buy()}
            gap(form,6);form.addView(label("非自动续费 · 续费从现有到期时间顺延",12f,palette.muted));gap(form,16)
        }else if(!busy){action(form,"加载会员方案",false){load()}}
        val rights=card();rights.addView(label("会员权益",16f,palette.ink,true));gap(rights,10)
        rights.addView(label("500MB 云存储\n每日 256MB 上传额度\n专属会员标识与星光头像框",14f));gap(rights,8)
        rights.addView(label("到期后保留已有文件；超出普通额度时，清理空间或续费后可继续上传。单个文件仍为 25MB。",12f,palette.muted))
        if(focused.isNotEmpty())for(n in 0 until orders.length())if(orders.getJSONObject(n).optString("id")==focused){orderCard(orders.getJSONObject(n));break}
        if(orders.length()>0){form.addView(label("订单记录",16f,palette.ink,true));gap(form)
            for(n in 0 until orders.length()){
                val o=orders.getJSONObject(n);if(o.optString("id")==focused)continue
                val item=card();item.addView(label(o.optString("plan_name")+"  "+MemberUi.price(o.optInt("cents")),14f,palette.ink,true));gap(item,5)
                item.addView(label(state(o.optString("state"))+" · "+date(o.optLong("created")),12f,palette.muted));item.setOnClickListener{focused=o.getString("id");revealOrder=true;render()}
            }
        }
        gap(form);form.addView(label("付款结果以服务端到账核验为准，返回页面不会直接开通。支付平台按账单确认，可能稍有延迟；请核对付款页面的金额与收款人。",12f,palette.muted))
        scroll.post{if(valid()){
            val target=if(revealOrder)form.findViewWithTag<View>("current-order")?.top else null
            revealOrder=false
            if(target!=null&&android.animation.ValueAnimator.areAnimatorsEnabled())scroll.smoothScrollTo(0,target)else scroll.scrollTo(0,target?:y)
        }}
    }
    private fun state(value:String)=when(value){"paid"->"已到账并开通";"closed"->"订单已关闭";"failed"->"下单未完成，可重试";"creating"->"正在确认订单";else->"等待付款 / 到账确认"}
    private fun orderCard(o:JSONObject) {
        val view=card().apply{tag="current-order"};view.addView(label("当前订单 · "+o.optString("plan_name"),16f,palette.ink,true));gap(view,7)
        view.addView(label(MemberUi.price(o.optInt("cents"))+" · "+state(o.optString("state")),14f,palette.accent,true));gap(view,7)
        view.addView(label("开通账号："+Hub.me.optString("nickname")+"（"+owner+"）",12f,palette.muted))
        view.addView(label(o.getString("id"),11f,palette.muted).apply{setTextIsSelectable(true)})
        if(o.optString("state")=="paid"){
            view.addView(label("本单续期至 "+date(o.optLong("granted_until")),13f));return
        }
        if(o.optString("state")=="closed")return
        val url=o.optString("pay_url")
        if(MemberUi.checkout(url)){
            action(view,"打开支付宝付款页"){open(o)}
            if(qr==null||qrUrl!=url){
                val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,512,512,mapOf(EncodeHintType.MARGIN to 2))
                val pixels=IntArray(512*512){index->if(bits[index%512,index/512])Color.BLACK else Color.WHITE}
                qr=Bitmap.createBitmap(pixels,512,512,Bitmap.Config.ARGB_8888);qrUrl=url
            }
            val side=minOf(dp(240),resources.displayMetrics.widthPixels-dp(80)).coerceAtLeast(dp(96))
            view.addView(ImageView(this).apply{setImageBitmap(qr);contentDescription="用手机扫码打开本订单付款页"},LinearLayout.LayoutParams(side,side).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(14)})
            view.addView(label("手表可用手机扫码打开付款页",12f,palette.muted).apply{gravity=Gravity.CENTER})
        }else{action(view,"重试原订单",false){retry(o.getString("id"))}}
        action(view,"查询付款结果",false){refresh(o.getString("id"))}
        action(view,"复制订单号",false){copy(o.getString("id"))}
    }
}
