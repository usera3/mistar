package cn.qingliao.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputFilter
import android.view.*
import android.widget.*
import org.json.JSONObject

/** Human account management only. Credentials are shown once and never persisted by the app. */
class DeveloperActivity : Activity() {
    private val session=Hub.token
    private val palette by lazy { ThemeStore.current(this) }
    private lateinit var body:LinearLayout
    private var busy=false
    private val dialogs=mutableSetOf<AlertDialog>()
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun col()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun valid()=!isFinishing&&!isDestroyed&&session.isNotEmpty()&&Hub.token==session
    private fun label(s:String,size:Float=14f,color:Int=palette.ink,bold:Boolean=false)=TextView(this).apply{
        text=s;textSize=size;setTextColor(color);setPadding(0,dp(8),0,dp(8));if(bold)setTypeface(typeface,Typeface.BOLD)
    }
    private fun note(s:String){if(valid())Toast.makeText(this,s,Toast.LENGTH_LONG).show()}
    override fun finish(){super.finish();Motion.activityClose(this)}
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        if(!valid()){finish();return}
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor=palette.canvas;window.navigationBarColor=palette.canvas
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=col().apply{setBackgroundColor(palette.canvas)}
        root.setOnApplyWindowInsetsListener{v,i->
            if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom)}
            else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom)
            i
        }
        val top=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(palette.canvas);setPadding(dp(6),dp(6),dp(16),dp(6))}
        top.addView(FrameLayout(this).apply{contentDescription="返回";isFocusable=true;setOnClickListener{finish()};addView(Glyph(this@DeveloperActivity,"back"),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))},LinearLayout.LayoutParams(dp(48),dp(48)))
        top.addView(label("开发者机器人",19f,bold=true));root.addView(top)
        body=col().apply{setPadding(dp(20),dp(12),dp(20),dp(28))}
        root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);root.requestApplyInsets();load()
    }
    override fun onResume(){super.onResume();if(Hub.token!=session)finish()}
    override fun onDestroy(){dialogs.toList().forEach{it.dismiss()};dialogs.clear();super.onDestroy()}
    private fun show(builder:AlertDialog.Builder):AlertDialog{
        val d=builder.create();dialogs.add(d);d.setOnDismissListener{dialogs.remove(d)};d.show()
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);return d
    }
    private fun task(work:()->JSONObject,done:(JSONObject)->Unit){
        if(!valid()||busy)return;busy=true
        Hub.job({check(Hub.token==session){"登录状态已改变"};work()}, {busy=false;if(valid())done(it)}, {busy=false;if(valid()){note(it);if(body.childCount==0){body.addView(label("加载失败，请检查网络后重试。",14f,palette.muted));action(body,"重新加载"){load()}}}})
    }
    private fun action(parent:LinearLayout,title:String,click:()->Unit){
        parent.addView(label(title,14f,palette.accent,true).apply{
            gravity=Gravity.CENTER;minimumHeight=dp(48);isFocusable=true;contentDescription=title
            background=BrandUi.card(this@DeveloperActivity,palette,18)
            setOnClickListener{if(!busy&&valid())click()}
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
    }
    private fun load(){
        if(!valid()||busy)return
        task({Hub.rawApi("GET","/api/developer/bots",session=session)}){result->
            body.removeAllViews()
            body.addView(label("让你的服务加入聊天",22f,bold=true))
            body.addView(label("发送 API + Webhook 主动推送\n支持文字、图片、语音、文件和 HTML。每个账号最多 3 个机器人，需验证邮箱。",13f,palette.muted))
            action(body,"接入说明"){guide()};action(body,"创建机器人"){edit(null)}
            action(body,"通话接入说明"){guide("BOT_CALLS.md")}
            action(body,"刷新机器人状态"){load()}
            val bots=result.getJSONArray("bots")
            if(bots.length()==0)body.addView(label("还没有机器人，先创建一个吧。",14f,palette.muted))
            for(i in 0 until bots.length()){
                val b=bots.getJSONObject(i);val card=col().apply{setPadding(dp(16),dp(12),dp(16),dp(16));background=GradientDrawable().apply{setColor(palette.soft);cornerRadius=dp(18).toFloat()}}
                card.addView(label(b.optString("nickname")+"  ·  机器人",18f,bold=true))
                card.addView(label("弥星号  "+b.getString("id"),13f,palette.muted).apply{setTextIsSelectable(true)})
                card.addView(label(b.optString("bot_description"),13f,palette.muted))
                card.addView(label(when{!b.optBoolean("bot_enabled")->"已停用";!b.optBoolean("webhook_verified")->"待验证回调";else->"已接入 · 回调已验证"},13f,palette.accent,true))
                val url=b.optString("webhook")
                card.addView(label(if(url.isEmpty())"尚未配置回调地址" else url,12f,palette.muted))
                card.addView(label("待推送 ${b.optInt("pending")}  ·  失败 ${b.optInt("failed")}  ·  最近 HTTP ${b.optInt("last_status")}",12f,palette.muted))
                action(card,"编辑机器人"){edit(b)}
                action(card,"配置 Webhook"){webhook(b)}
                action(card,"验证回调"){mutate(b,"verify")}
                card.addView(label(if(b.optBoolean("bot_calls_enabled"))"语音 / 视频通话已启用" else "语音 / 视频通话未启用",12f,palette.muted))
                action(card,if(b.optBoolean("bot_calls_enabled"))"关闭机器人通话" else "启用机器人通话") {
                    val enable=!b.optBoolean("bot_calls_enabled")
                    confirm(if(enable)"启用机器人通话？" else "关闭机器人通话？",if(enable)"需要开发者运行 WebRTC 媒体程序，并通过 Webhook 处理来电和连接事件。已添加用户默认允许机器人来电，可自行关闭；来电仍需手动接听。" else "将结束当前通话，并关闭新通话。文字与附件功能继续可用。") {
                        task({Hub.rawApi("PATCH","/api/developer/bots/"+b.getString("id"),JSONObject().put("calls_enabled",enable),session)}){load()}
                    }
                }
                if(b.optInt("failed")>0)action(card,"重试失败消息"){mutate(b,"retry")}
                action(card,if(b.optBoolean("bot_enabled"))"停用机器人" else "启用机器人"){
                    val enable=!b.optBoolean("bot_enabled")
                    confirm(if(enable)"启用机器人？" else "停用机器人？",if(enable)"已配置的凭证恢复可用，待发送回调会继续。" else "将立即拒绝新的 API 请求并暂停回调；已经发出的回调无法收回。"){
                        task({Hub.rawApi("PATCH","/api/developer/bots/"+b.getString("id"),JSONObject().put("enabled",enable),session)}){load()}
                    }
                }
                action(card,"重置 API 凭证"){
                    confirm("重置 API 凭证？","旧凭证立即失效。机器人程序需要换用新凭证，聊天身份和历史记录保留。"){
                        task({Hub.rawApi("POST","/api/developer/bots/"+b.getString("id")+"/rotate",JSONObject(),session)}){r->load();credential("API 凭证",r.getString("token"))}
                    }
                }
                body.addView(card,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(20)})
            }
        }
    }
    private fun input(hint:String,value:String,maximum:Int)=EditText(this).apply{
        this.hint=hint;setText(value);textSize=15f;setTextColor(palette.ink);filters=arrayOf(InputFilter.LengthFilter(maximum));setPadding(dp(8),dp(10),dp(8),dp(10))
    }
    private fun edit(bot:JSONObject?){
        val form=col().apply{setPadding(dp(20),dp(8),dp(20),dp(8))}
        val name=input("机器人名称",bot?.optString("nickname")?:"",20)
        val desc=input("介绍（可选）",bot?.optString("bot_description")?:"",160)
        form.addView(name);form.addView(desc)
        val d=show(AlertDialog.Builder(this).setTitle(if(bot==null)"创建机器人" else "编辑机器人").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null))
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            if(name.text.isBlank()){name.error="请输入名称";return@setOnClickListener}
            val p=JSONObject().put("nickname",name.text.toString().trim()).put("description",desc.text.toString().trim())
            task({Hub.rawApi(if(bot==null)"POST" else "PATCH","/api/developer/bots"+(bot?.let{"/"+it.getString("id")}?:""),p,session)}){r->
                d.dismiss();load();if(r.has("token"))credential("API 凭证",r.getString("token"))
            }
        }
    }
    private fun webhook(bot:JSONObject){
        val form=col().apply{setPadding(dp(20),dp(8),dp(20),dp(8))}
        form.addView(label("填写你控制的公网 HTTPS 443 地址。保存后会产生新的签名密钥，请先更新接收程序，再点“验证回调”。",13f,palette.muted))
        val url=input("https://example.com/qingliao/webhook",bot.optString("webhook"),1000).apply{inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI}
        form.addView(url)
        val d=show(AlertDialog.Builder(this).setTitle("配置 Webhook").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null))
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            task({Hub.rawApi("PUT","/api/developer/bots/"+bot.getString("id")+"/webhook",JSONObject().put("url",url.text.toString().trim()),session)}){r->d.dismiss();load();credential("Webhook 签名密钥",r.getString("webhook_secret"))}
        }
    }
    private fun mutate(bot:JSONObject,action:String){task({Hub.rawApi("POST","/api/developer/bots/"+bot.getString("id")+"/"+action,JSONObject(),session)}){note("已完成");load()}}
    private fun confirm(title:String,message:String,yes:()->Unit){show(AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确定"){_,_->if(valid()&&!busy)yes()})}
    private fun credential(title:String,value:String){
        val form=col().apply{setPadding(dp(22),dp(8),dp(22),dp(8))}
        form.addView(label("仅显示这一次，请保存到开发环境的密钥配置中。不要发到聊天或公开代码里。",13f,palette.muted))
        form.addView(label(value,14f).apply{setTextIsSelectable(true);typeface=Typeface.MONOSPACE})
        show(AlertDialog.Builder(this).setTitle(title).setView(form).setNegativeButton("已保存",null).setPositiveButton("复制"){_,_->
            if(valid()){
                val manager=getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip=ClipData.newPlainText(title,value)
                if(Build.VERSION.SDK_INT>=33)clip.description.extras=PersistableBundle().apply{putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true)}
                manager.setPrimaryClip(clip);note("已复制，60 秒后清除")
                Hub.main.postDelayed({if(manager.primaryClip?.getItemAt(0)?.text?.toString()==value){if(Build.VERSION.SDK_INT>=28)manager.clearPrimaryClip()else manager.setPrimaryClip(ClipData.newPlainText("",""))}},60000)
            }
        })
    }
    private fun guide(file:String="BOT_API.md"){
        val content=assets.open(file).bufferedReader().use{it.readText()}
        val view=ScrollView(this).apply{addView(label(content,13f).apply{setPadding(dp(22),dp(8),dp(22),dp(12));setTextIsSelectable(true)})}
        show(AlertDialog.Builder(this).setTitle("机器人接入说明").setView(view).setPositiveButton("知道了",null))
    }
}
