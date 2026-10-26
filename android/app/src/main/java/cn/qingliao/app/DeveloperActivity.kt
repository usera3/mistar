package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import org.json.*

/** Human-session management. Bot tokens never enter saved state, logs or example snippets. */
class DeveloperActivity:Activity() {
    private val session=Hub.token
    private val palette by lazy{ThemeStore.current(this)}
    private val u by lazy{FormUi(this,palette)}
    private lateinit var host:FrameLayout
    private lateinit var body:LinearLayout
    private lateinit var title:TextView
    private lateinit var feedback:TextView
    private lateinit var refresh:View
    private var bots=JSONArray()
    private var selected=""
    private var route="list"
    private var previous="list"
    private var busy=false
    private var loaded=false
    private var notice=""
    private var secret=""
    private var secretKind=""
    private val dialogs=mutableSetOf<AlertDialog>()
    private fun valid()=!isFinishing&&!isDestroyed&&session.isNotEmpty()&&Hub.token==session
    private fun bot():JSONObject?=(0 until bots.length()).map{bots.getJSONObject(it)}.find{it.optString("id")==selected}
    private fun toast(s:String){if(valid())Toast.makeText(this,s,Toast.LENGTH_SHORT).show()}
    override fun finish(){secret="";super.finish();Motion.activityClose(this)}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);if(!valid()){finish();return}
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor=palette.canvas;window.navigationBarColor=palette.canvas
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=u.col().apply{setBackgroundColor(palette.canvas)}
        root.setOnApplyWindowInsetsListener{v,i->
            if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom)}else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom)
            i
        }
        val top=u.row().apply{setPadding(u.dp(4),u.dp(4),u.dp(8),u.dp(4))}
        top.addView(u.icon("back","返回"){back()}.apply{tag="developer.back"},u.lp(48,48))
        title=u.text("开发者中心",18f,bold=true);top.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        refresh=u.icon("refresh","刷新机器人状态"){load()}.apply{tag="developer.refresh"};top.addView(refresh,u.lp(48,48));root.addView(top,u.lp())
        host=FrameLayout(this);root.addView(host,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);root.requestApplyInsets();render();load()
    }
    override fun onResume(){super.onResume();if(Hub.token!=session)finish()}
    override fun onConfigurationChanged(config:android.content.res.Configuration){super.onConfigurationChanged(config);if(::body.isInitialized)body.layoutParams=LinearLayout.LayoutParams(if(resources.configuration.screenWidthDp>600)u.dp(560)else -1,-2)}
    override fun onDestroy(){secret="";dialogs.toList().forEach{it.dismiss()};dialogs.clear();super.onDestroy()}
    @Deprecated("Android back callback") override fun onBackPressed(){back()}
    private fun back(){
        if(busy){toast("正在处理，请稍候");return}
        when{route=="list"->finish();route=="credential"->credentialDone();route=="detail"->{selected="";navigate("list")};route=="guide"->navigate(previous);route.startsWith("doc:")->navigate("guide");else->navigate(if(selected.isEmpty())"list"else"detail")}
    }
    private fun navigate(next:String){if(!valid()||busy)return;route=next;notice="";render()}
    private fun render(){
        val scroll=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false;isSaveEnabled=false}
        val outer=u.col().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(u.dp(if(u.compact)12 else 20),u.dp(10),u.dp(if(u.compact)12 else 20),u.dp(28))}
        body=u.col();outer.addView(body,LinearLayout.LayoutParams(if(resources.configuration.screenWidthDp>600)u.dp(560)else -1,-2));scroll.addView(outer)
        host.removeAllViews();host.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        title.text=when(route){"detail"->"机器人详情";"edit"->if(selected.isEmpty())"创建机器人"else"编辑资料";"webhook"->"配置回调";"credential"->"保存密钥";"guide"->"接入指南";"doc:BOT_API.md"->"消息 API 文档";"doc:BOT_CALLS.md"->"通话 API 文档";else->"开发者中心"}
        refresh.visibility=if(route=="list"||route=="detail")View.VISIBLE else View.GONE
        feedback=u.text(notice,13f,palette.accent).apply{tag="developer.feedback";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;visibility=if(notice.isEmpty())View.GONE else View.VISIBLE}
        body.addView(feedback,u.lp());if(notice.isNotEmpty())u.gap(body)
        when(route){"list"->list();"detail"->detail();"edit"->edit();"webhook"->webhook();"credential"->credential();"guide"->guide()}
        if(route.startsWith("doc:"))renderDocument(route.removePrefix("doc:"))
        Motion.reveal(body,y=5f,duration=180)
    }
    private fun message(value:String){notice=value;feedback.text=value;feedback.visibility=View.VISIBLE;feedback.post{feedback.requestRectangleOnScreen(android.graphics.Rect(0,0,feedback.width,feedback.height),false)}}
    private fun setEnabled(v:View,value:Boolean){if(v.isClickable||v is EditText||v is Switch){v.isEnabled=value;v.alpha=if(value)1f else .55f};if(v is ViewGroup)for(n in 0 until v.childCount)setEnabled(v.getChildAt(n),value)}
    private fun task(progress:String,work:()->JSONObject,done:(JSONObject)->Unit){
        if(!valid()||busy)return;busy=true;message(progress);setEnabled(body,false);refresh.isEnabled=false
        Hub.job({check(Hub.token==session);work()}, {
            if(valid()){busy=false;setEnabled(body,true);refresh.isEnabled=true;notice="";feedback.visibility=View.GONE;done(it)}
        },{if(valid()){busy=false;setEnabled(body,true);refresh.isEnabled=true;message(it);if(!loaded&&route=="list")render()}})
    }
    private fun putBot(b:JSONObject){
        val list=JSONArray();var found=false
        for(n in 0 until bots.length()){val old=bots.getJSONObject(n);if(old.getString("id")==b.getString("id")){list.put(b);found=true}else list.put(old)}
        if(!found)list.put(b);bots=list
    }
    private fun load(){task("正在读取状态…",{Hub.rawApi("GET","/api/developer/bots",session=session)}){bots=it.getJSONArray("bots");loaded=true;if(route=="detail"&&bot()==null){selected="";route="list"};render()}}
    private fun primary(text:String,tag:String,action:()->Unit){body.addView(u.button(text,tag,click=action),u.lp());u.gap(body,10)}
    private fun row(parent:LinearLayout,kind:String,name:String,description:String,tag:String,action:()->Unit){
        val row=u.row().apply{setPadding(u.dp(4),u.dp(10),u.dp(4),u.dp(10));minimumHeight=u.dp(64);this.tag=tag;isFocusable=true;setOnClickListener{if(!busy&&valid())action()};Motion.press(this)}
        row.addView(FrameLayout(this).apply{background=BrandUi.rounded(this@DeveloperActivity,palette.soft,14);addView(Glyph(this@DeveloperActivity,kind,palette.accent),FrameLayout.LayoutParams(u.dp(20),u.dp(20),Gravity.CENTER))},u.lp(40,40))
        val text=u.col().apply{setPadding(u.dp(12),0,u.dp(8),0)};text.addView(u.text(name,14f,bold=true),u.lp())
        if(description.isNotEmpty()){u.gap(text,4);text.addView(u.text(description,12f,palette.muted),u.lp())}
        row.addView(text,LinearLayout.LayoutParams(0,-2,1f));row.addView(u.text("›",22f,palette.muted));parent.addView(row,u.lp())
    }
    private fun badge(text:String)=u.text(text,11f,palette.accent,true).apply{layoutParams=u.lp(-2,-2);setPadding(u.dp(9),u.dp(5),u.dp(9),u.dp(5));background=BrandUi.rounded(this@DeveloperActivity,palette.soft,20)}
    private fun status(b:JSONObject)=when{!b.optBoolean("bot_enabled")->"已停用";b.optString("webhook").isEmpty()->"待配置";!b.optBoolean("webhook_verified")->"待验证";b.optInt("failed")>0->"投递异常";else->"已接入"}
    private fun list(){
        val hero=u.card().apply{background=StarlightDrawable(palette,u.dp(22).toFloat())}
        hero.addView(u.text(if(u.tiny)"让服务加入聊天"else"让想法，成为聊天伙伴",if(u.compact)21f else 25f,bold=true),u.lp());u.gap(hero,8)
        if(!u.tiny){hero.addView(u.text("用 API 发送消息，用 Webhook 接收事件。",13f,palette.muted),u.lp());u.gap(hero,16)}
        hero.addView(badge(if(loaded)"${bots.length()} / 3 个机器人"else"发送 API + 主动推送"));body.addView(hero,u.lp());u.gap(body,16)
        primary(if(bots.length()>=3)"已达到 3 个机器人上限"else"＋  创建机器人","developer.create"){if(bots.length()<3){selected="";navigate("edit")}}
        body.findViewWithTag<View>("developer.create")?.apply{isEnabled=loaded&&bots.length()<3;alpha=if(isEnabled)1f else .55f}
        val help=u.card();row(help,"file","快速接入指南","从创建到收到第一条消息","developer.guide"){previous="list";navigate("guide")};body.addView(help,u.lp())
        u.section(body,"我的机器人",if(loaded)"点开机器人，继续配置或查看投递状态。"else"")
        if(!loaded){
            body.addView(u.text(if(notice.isEmpty())"正在加载机器人…"else"未能加载机器人",14f,palette.muted),u.lp())
            if(notice.isNotEmpty()){u.gap(body);primary("重新加载","developer.retryLoad"){load()}}
        }else if(bots.length()==0){
            val empty=u.card();empty.addView(Glyph(this,"chat",palette.accent),u.lp(32,32));u.gap(empty,12)
            empty.addView(u.text("你的第一个机器人，在这里启程",16f,bold=true),u.lp());u.gap(empty,7)
            empty.addView(u.text("支持文字、图片、语音、文件和 HTML 卡片，也可以接入语音与视频通话。",13f,palette.muted),u.lp());body.addView(empty,u.lp())
        }else for(n in 0 until bots.length()){
            val b=bots.getJSONObject(n);val card=u.card().apply{tag="developer.bot.${b.getString("id")}";isFocusable=true;setOnClickListener{if(!busy){selected=b.getString("id");navigate("detail")}};Motion.press(this)}
            val head=u.row();head.addView(u.text(b.optString("nickname"),17f,bold=true),LinearLayout.LayoutParams(0,-2,1f));head.addView(u.text("›",22f,palette.muted));card.addView(head,u.lp());u.gap(card,9)
            card.addView(badge(status(b)));u.gap(card,9)
            card.addView(u.text("弥星号  ${b.getString("id")}",12f,palette.muted),u.lp())
            if(b.optString("bot_description").isNotEmpty()){u.gap(card,7);card.addView(u.text(b.optString("bot_description"),13f,palette.muted),u.lp())}
            u.gap(card,12);card.addView(u.text("${b.optInt("pending")} 条待推送  ·  ${b.optInt("failed")} 条失败",12f,if(b.optInt("failed")>0)palette.accent else palette.muted),u.lp())
            body.addView(card,u.lp());u.gap(body,12)
        }
    }
    private fun detail(){
        val b=bot()?:return
        val identity=u.card();identity.addView(u.text(b.optString("nickname"),24f,bold=true),u.lp());u.gap(identity,8);identity.addView(badge(status(b)));u.gap(identity,8)
        if(!u.tiny)identity.addView(u.text(b.optString("bot_description").ifEmpty{"还没有介绍，写几句话让大家认识它。"},13f,palette.muted),u.lp())
        identity.addView(u.link("弥星号 ${b.getString("id")}  ·  复制","developer.copyId"){copy("机器人弥星号",b.getString("id"))},u.lp());body.addView(identity,u.lp())
        val configured=b.optString("webhook").isNotEmpty();val verified=b.optBoolean("webhook_verified")
        if(!verified){
        u.section(body,"接入进度")
        val steps=u.card()
        listOf(Triple("创建机器人","API 凭证在创建或重置时显示。",true),Triple("配置回调地址","将签名密钥写入接收程序后再验证。",configured),Triple("验证回调","验证成功后，用户即可搜索并添加。",verified)).forEachIndexed{n,item->
            val r=u.row();r.addView(badge(if(item.third)"✓"else"${n+1}"));val t=u.col().apply{setPadding(u.dp(12),u.dp(8),0,u.dp(8))};t.addView(u.text(item.first,14f,bold=true),u.lp());u.gap(t,4);t.addView(u.text(item.second,12f,palette.muted),u.lp());r.addView(t,LinearLayout.LayoutParams(0,-2,1f));steps.addView(r,u.lp())
        };body.addView(steps,u.lp())
        }
        u.gap(body,12)
        when{!b.optBoolean("bot_enabled")->primary("启用机器人","developer.enable"){toggle(b,false)};!configured->primary("下一步 · 配置回调","developer.configure"){navigate("webhook")};!verified->primary("验证回调","developer.verify"){mutate("verify")};else->primary("查看消息与通话示例","developer.examples"){previous="detail";navigate("guide")}}
        u.section(body,"投递状态","点击右上角，刷新最新状态。")
        val delivery=u.card();val metrics=u.row()
        listOf("待推送" to b.optInt("pending").toString(),"失败" to b.optInt("failed").toString()).forEach{(label,value)->val c=u.col();c.addView(u.text(value,24f,palette.accent,true));u.gap(c,4);c.addView(u.text(label,12f,palette.muted));metrics.addView(c,LinearLayout.LayoutParams(0,-2,1f))};delivery.addView(metrics,u.lp());u.gap(delivery,12)
        delivery.addView(u.text(if(b.optInt("last_status")==0)"尚无回调响应记录"else"最近回调响应  HTTP ${b.optInt("last_status")}",12f,palette.muted),u.lp())
        if(configured&&verified&&b.optBoolean("bot_enabled")){u.gap(delivery,8);delivery.addView(u.button("检查回调连接","developer.verify",false){mutate("verify")},u.lp())}
        if(b.optInt("failed")>0){u.gap(delivery,10);delivery.addView(u.button("重试失败事件","developer.retry",false){mutate("retry")},u.lp());u.gap(delivery,6);delivery.addView(u.text("接收端请按事件 ID 去重，避免重试产生重复回复。",12f,palette.muted),u.lp())};body.addView(delivery,u.lp())
        u.section(body,"配置")
        val config=u.card();row(config,"me","机器人资料","名称与介绍","developer.edit"){navigate("edit")}
        row(config,"chat","Webhook 回调",b.optString("webhook").ifEmpty{"尚未配置公网 HTTPS 地址"},"developer.webhook"){navigate("webhook")}
        val calls=u.row();val text=u.col();text.addView(u.text("语音与视频通话",14f,bold=true),u.lp());u.gap(text,5);text.addView(u.text("需要接入 WebRTC 媒体程序。",12f,palette.muted),u.lp());calls.addView(text,LinearLayout.LayoutParams(0,-2,1f))
        calls.addView(Switch(this).apply{tag="developer.calls";contentDescription="语音与视频通话";isChecked=b.optBoolean("bot_calls_enabled");setOnClickListener{isChecked=b.optBoolean("bot_calls_enabled");toggle(b,true)}},u.lp(-2,48));config.addView(calls,u.lp());body.addView(config,u.lp())
        u.section(body,"安全与管理")
        val security=u.card()
        if(configured)row(security,"file","重置回调签名密钥","需要更新接收程序并重新验证。","developer.rotateWebhook"){
            confirm("重置回调签名密钥？","旧签名密钥立即失效，验证状态会清除，当前通话将结束。请更新接收程序后重新验证。","重置"){
                task("正在生成签名密钥…",{Hub.rawApi("PUT","/api/developer/bots/$selected/webhook",JSONObject().put("url",b.getString("webhook")),session)}){r->putBot(r.getJSONObject("bot"));secret=r.getString("webhook_secret");secretKind="webhook";route="credential";render()}
            }
        }
        row(security,"file","重置 API 凭证","旧凭证立即失效，需要更新机器人程序。","developer.rotate"){
            confirm("重置 API 凭证？","旧凭证将立即失效，当前机器人通话也会结束。聊天身份与历史记录保留。","重置"){
                task("正在重置…",{Hub.rawApi("POST","/api/developer/bots/$selected/rotate",JSONObject(),session)}){r->putBot(r.getJSONObject("bot"));secret=r.getString("token");secretKind="token";route="credential";render()}
            }
        }
        row(security,"minus",if(b.optBoolean("bot_enabled"))"停用机器人"else"启用机器人",if(b.optBoolean("bot_enabled"))"暂停 API 与回调，并结束当前通话。"else"恢复当前凭证与回调。","developer.toggle"){toggle(b,false)};body.addView(security,u.lp())
        u.gap(body,16);body.addView(u.text("机器人收到的消息与通话内容会交给开发者处理。请向用户说明数据的使用与保留方式。",12f,palette.muted),u.lp())
    }
    private fun edit(){
        val b=bot();body.addView(u.text(if(b==null)"给新伙伴一个名字"else"让大家更了解它",23f,bold=true),u.lp());u.gap(body,8)
        body.addView(u.text(if(b==null)"创建后，按接入进度完成配置即可。每个账号最多 3 个机器人。"else"修改资料不会改变机器人弥星号。",13f,palette.muted),u.lp());u.gap(body,24)
        val name=u.Field("机器人名称","1–20 个字","developer.name",maximum=20);name.value(b?.optString("nickname").orEmpty())
        val desc=u.Field("介绍（可选）","它擅长做什么？","developer.description",maximum=160);desc.value(b?.optString("bot_description").orEmpty())
        body.addView(name.view,u.lp());body.addView(desc.view,u.lp())
        primary(if(b==null)"创建并获取凭证"else"保存资料","developer.save"){
            name.clearError();if(name.value().isBlank())name.fail("请输入机器人名称")else{
                val payload=JSONObject().put("nickname",name.value().trim()).put("description",desc.value().trim())
                task("正在保存…",{Hub.rawApi(if(b==null)"POST"else"PATCH","/api/developer/bots"+if(b==null)""else"/$selected",payload,session)}){r->
                    putBot(r.getJSONObject("bot"));selected=r.getJSONObject("bot").getString("id")
                    if(r.has("token")){secret=r.getString("token");secretKind="token";route="credential"}else route="detail";render()
                }
            }
        }
    }
    private fun webhook(){
        val b=bot()?:return
        body.addView(u.text("连接你的接收服务",23f,bold=true),u.lp());u.gap(body,8)
        body.addView(u.text("接收消息和通话事件，由弥星主动推送。",13f,palette.muted),u.lp());u.gap(body,22)
        val url=u.Field("Webhook 地址","https://example.com/webhook","developer.url",maximum=1000,type=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI);url.value(b.optString("webhook"));body.addView(url.view,u.lp())
        val info=u.card();info.addView(u.text("地址要求",14f,bold=true),u.lp());u.gap(info,6);info.addView(u.text("使用公网 HTTPS 443 地址和有效证书；不含账号、查询参数或 # 片段。服务器会检查地址安全性。",13f,palette.muted),u.lp());body.addView(info,u.lp());u.gap(body,16)
        body.addView(u.text(if(b.optString("webhook").isEmpty())"保存后会生成签名密钥。先配置接收程序，再验证回调。"else"更改地址会生成新签名密钥、取消当前验证并结束通话。",13f,palette.muted),u.lp());u.gap(body,16)
        primary("保存并获取签名密钥","developer.saveWebhook"){
            url.clearError();val value=url.value().trim();val parsed=runCatching{java.net.URI(value)}.getOrNull()
            if(parsed==null||parsed.scheme!="https"||parsed.host.isNullOrEmpty()||parsed.rawUserInfo!=null||parsed.rawQuery!=null||parsed.rawFragment!=null||parsed.port !in listOf(-1,443)||value.any{it.code<33||it.code>126})url.fail("请填写公网 HTTPS 443 地址，不含账号、查询参数或片段")
            else if(value==b.optString("webhook")){navigate("detail");message("地址没有变化，无需重新配置；可在详情验证回调。")}
            else{
                val save={task("正在保存回调…",{Hub.rawApi("PUT","/api/developer/bots/$selected/webhook",JSONObject().put("url",value),session)}){r->putBot(r.getJSONObject("bot"));secret=r.getString("webhook_secret");secretKind="webhook";route="credential";render()}}
                if(b.optString("webhook").isEmpty())save()else confirm("更改回调地址？","现有签名密钥会失效，请同步更新接收程序并重新验证。当前通话将结束。","更改",save)
            }
        }
    }
    private fun credential(){
        val webhook=secretKind=="webhook";val label=if(webhook)"Webhook 签名密钥"else"API 凭证"
        body.addView(u.text(if(webhook)"回调已保存"else"凭证已准备好",23f,bold=true),u.lp());u.gap(body,10)
        body.addView(u.text("密钥只在本次显示，请保存到开发环境。离开此页后无法再次查看。",13f,palette.muted),u.lp());u.gap(body,24)
        val card=u.card();card.addView(u.text(label,13f,palette.muted,true),u.lp());u.gap(card,14)
        val value=u.text("•••• •••• •••• ••••",15f).apply{tag="developer.secret";typeface=Typeface.MONOSPACE;isSaveEnabled=false};card.addView(value,u.lp());u.gap(card,10)
        card.addView(u.link("显示密钥","developer.reveal"){
            val visible=value.text.toString()==secret
            value.text=if(visible)"•••• •••• •••• ••••"else secret
            value.setTextIsSelectable(!visible)
            card.findViewWithTag<TextView>("developer.reveal").text=if(visible)"显示密钥"else"隐藏密钥"
        },u.lp());body.addView(card,u.lp());u.gap(body,16)
        primary("复制${label}","developer.copySecret"){copy(label,secret,true)}
        body.addView(u.button("复制环境变量配置","developer.copyEnv",false){copy("机器人环境变量",(if(webhook)"QINGLIAO_WEBHOOK_SECRET="else"QINGLIAO_BOT_TOKEN=")+secret,true)},u.lp());u.gap(body,20)
        body.addView(u.text(if(webhook)"将 QINGLIAO_WEBHOOK_SECRET 配置到服务并启动后，回到详情验证回调。"else"API 凭证用于发送请求。Webhook 签名密钥会在下一步单独生成。",13f,palette.muted),u.lp());u.gap(body,16)
        primary(if(webhook)"已保存 · 返回验证回调"else if(bot()?.optString("webhook").isNullOrEmpty())"已保存 · 配置回调"else"已保存 · 返回详情","developer.secretDone"){credentialDone()}
    }
    private fun credentialDone(){secret="";route=if(secretKind=="token"&&bot()?.optString("webhook").isNullOrEmpty())"webhook"else"detail";secretKind="";render()}
    private fun mutate(action:String){task(if(action=="verify")"正在验证回调…"else"正在重试…",{Hub.rawApi("POST","/api/developer/bots/$selected/$action",JSONObject(),session)}){
        val msg=if(action=="verify")"回调验证成功"else"已提交重试，请稍后刷新状态"
        task("正在读取状态…",{Hub.rawApi("GET","/api/developer/bots",session=session)}){bots=it.getJSONArray("bots");notice=msg;render()}
    }}
    private fun toggle(b:JSONObject,calls:Boolean){
        val enabled=!b.optBoolean(if(calls)"bot_calls_enabled"else"bot_enabled")
        val label=if(calls)"机器人通话"else"机器人"
        confirm((if(enabled)"启用"else"关闭")+label+"？",if(calls&&enabled)"开发者需要运行 WebRTC 媒体程序。已添加用户默认允许机器人来电，可自行关闭；真人仍需手动接听。"else if(!enabled)"当前通话将结束。"+(if(calls)"消息收发继续可用。"else"API 与回调会暂停，已交给开发者的内容无法收回。")else"恢复当前 API 凭证与已配置回调。",if(enabled)"启用"else"关闭"){
            task("正在更新…",{Hub.rawApi("PATCH","/api/developer/bots/$selected",JSONObject().put(if(calls)"calls_enabled"else"enabled",enabled),session)}){putBot(it.getJSONObject("bot"));render()}
        }
    }
    private fun confirm(title:String,message:String,positive:String,yes:()->Unit){
        val d=AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton(positive){_,_->if(valid()&&!busy)yes()}.create()
        dialogs.add(d);d.setOnDismissListener{dialogs.remove(d)};d.show();d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun copy(label:String,value:String,sensitive:Boolean=false){
        if(!valid()||value.isEmpty())return
        val manager=getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip=ClipData.newPlainText(label,value)
        if(sensitive)clip.description.extras=PersistableBundle().apply{putBoolean("android.content.extra.IS_SENSITIVE",true)}
        manager.setPrimaryClip(clip);toast(if(sensitive)"已复制，60 秒后清除"else"已复制")
        if(sensitive)Hub.main.postDelayed({if(manager.primaryClip?.getItemAt(0)?.text?.toString()==value){if(Build.VERSION.SDK_INT>=28)manager.clearPrimaryClip()else manager.setPrimaryClip(ClipData.newPlainText("",""))}},60000)
    }
    private fun code(title:String,content:String,key:String){
        val c=u.card();c.addView(u.text(title,14f,bold=true),u.lp());u.gap(c,12)
        val h=HorizontalScrollView(this);h.addView(u.text(content,12f).apply{typeface=Typeface.MONOSPACE;setTextIsSelectable(true);tag=key});c.addView(h,u.lp())
        c.addView(u.link("复制示例",key+".copy"){copy(title,content)},u.lp());body.addView(c,u.lp());u.gap(body,12)
    }
    private fun guide(){
        body.addView(u.text("从第一条消息开始",24f,bold=true),u.lp());u.gap(body,8)
        body.addView(u.text("直接发送 API + Webhook 推送，无需轮询。",13f,palette.muted),u.lp())
        u.section(body,"1  创建并连接")
        body.addView(u.text("创建机器人 → 保存 API 凭证 → 填写 HTTPS 回调 → 保存签名密钥并部署程序 → 验证回调。验证通过后，让用户搜索弥星号并主动添加。",14f),u.lp())
        u.section(body,"2  运行接收程序")
        code("Python 标准库示例","# 下载仓库 bot-examples 目录后\nexport QINGLIAO_BOT_TOKEN='<API 凭证>'\nexport QINGLIAO_WEBHOOK_SECRET='<签名密钥>'\npython webhook_bot.py","developer.example.start")
        body.addView(u.text("示例默认监听 127.0.0.1:9080/qingliao/webhook，请在自己的 HTTPS 反向代理后运行。使用随示例提供的公开 CA，保留 TLS 校验。",12f,palette.muted),u.lp())
        u.section(body,"3  发送消息")
        code("文字消息 · JSON","POST /api/bot/messages\nAuthorization: Bearer <API 凭证>\nContent-Type: application/json\n\n{\n  \"recipient\": \"<用户弥星号>\",\n  \"client_id\": \"reply-<原消息ID>\",\n  \"type\": \"text\",\n  \"text\": \"你好，来自弥星\"\n}","developer.example.text")
        code("HTML 卡片 · JSON","{\n  \"recipient\": \"<用户弥星号>\",\n  \"client_id\": \"card-<唯一业务ID>\",\n  \"type\": \"html\",\n  \"text\": \"<div style='padding:16px;background:#f1edfc;border-radius:16px'>来自弥星的问候 ✦</div>\"\n}","developer.example.html")
        code("图片与语音 · Python","from bot_api import BotAPI\n\nbot = BotAPI()\nbot.send_file(\n    '<用户弥星号>', 'photo.png',\n    'image-<唯一业务ID>', kind='image'\n)\nbot.send_file(\n    '<用户弥星号>', 'voice.wav',\n    'voice-<唯一业务ID>', kind='voice'\n)","developer.example.media")
        body.addView(u.text("图片、语音和文件：先将原始字节上传到 /api/bot/uploads，再向 /api/bot/messages 发送 file_id。单文件上限 25 MiB。重试沿用原 client_id。",13f,palette.muted),u.lp())
        u.section(body,"4  接收消息与通话")
        body.addView(u.text("使用原始请求体做 HMAC-SHA256 验签，核对时间与事件 ID；可靠入队后快速返回 2xx，按 event.id 去重。验证事件需返回收到的 challenge。\n\n语音和视频通话还需运行 WebRTC 媒体程序并启用通话能力。示例 call_bot.py 提供媒体链路，模型与语音服务由开发者接入。",14f),u.lp())
        u.section(body,"完整资料")
        val docs=u.card();row(docs,"file","消息 API 与 Webhook","接口、附件、验签与错误码","developer.docs.api"){document("BOT_API.md")};row(docs,"video","语音与视频通话","信令、媒体、忙线与生命周期","developer.docs.calls"){document("BOT_CALLS.md")};row(docs,"file","示例代码与公开 CA","GitHub · usera3/mistar","developer.docs.examples"){document("bot-examples")};body.addView(docs,u.lp())
        u.gap(body,16);body.addView(u.text("机器人消息由服务器中转给开发者；普通用户之间的聊天继续端到端加密。API 凭证与签名密钥分开保存，不要放入公开代码。",12f,palette.muted),u.lp())
    }
    private fun document(path:String){
        if(path in setOf("BOT_API.md","BOT_CALLS.md")){navigate("doc:$path");return}
        val url="https://github.com/usera3/mistar/"+(if(path=="bot-examples")"tree"else"blob")+"/main/"+path
        runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure{toast("没有可打开链接的浏览器")}
    }
    private fun renderDocument(file:String){
        if(file !in setOf("BOT_API.md","BOT_CALLS.md"))return
        val lines=assets.open(file).bufferedReader().use{it.readLines()}
        val paragraph=StringBuilder();val snippet=StringBuilder();var inCode=false;var number=0
        fun flush(){
            if(paragraph.isEmpty())return
            val value=paragraph.toString().trim().replace(Regex("\\[([^]]+)\\]\\(([^)]+)\\)"),"$1（$2）").replace("**","").replace("`","")
            body.addView(u.text(value,14f).apply{setTextIsSelectable(true)},u.lp());u.gap(body,12);paragraph.clear()
        }
        for(line in lines){
            when{
                line.startsWith("```")->{flush();if(inCode){code("示例",snippet.toString().trimEnd(),"developer.doc.code.${number++}");snippet.clear()};inCode=!inCode}
                inCode->snippet.append(line).append('\n')
                line.startsWith("#")-> {flush();u.section(body,line.trimStart('#',' '))}
                line.isBlank()->flush()
                else->{if(paragraph.isNotEmpty())paragraph.append('\n');paragraph.append(line)}
            }
        };flush()
        if(snippet.isNotEmpty())code("示例",snippet.toString(),"developer.doc.code.$number")
    }
}
