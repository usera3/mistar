package cn.qingliao.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import org.json.JSONObject

/** Account-scoped relationship controls. Destructive requests always retain the opening session. */
class PeopleActivity : Activity() {
    private val session=Hub.token
    private val peer by lazy { intent.getStringExtra("peer") ?: "" }
    private val palette by lazy { ThemeStore.current(this) }
    private lateinit var body: LinearLayout
    private var busy=false
    private var generation=0
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun col()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun label(value:String,size:Float=14f,color:Int=palette.ink,bold:Boolean=false)=TextView(this).apply {
        text=value;textSize=size;setTextColor(color);includeFontPadding=false
        setPadding(0,dp(9),0,dp(9));if(bold)setTypeface(typeface,Typeface.BOLD)
    }
    private fun valid()=!isFinishing&&!isDestroyed&&session.isNotEmpty()&&Hub.token==session
    override fun finish(){super.finish();Motion.activityClose(this)}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(!valid()){finish();return}
        window.statusBarColor=palette.canvas;window.navigationBarColor=palette.canvas
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=col().apply { setBackgroundColor(palette.canvas) }
        root.setOnApplyWindowInsetsListener { v,i ->
            if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom)}
            else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom)
            i
        }
        val top=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(palette.canvas);setPadding(dp(6),dp(6),dp(16),dp(6)) }
        top.addView(FrameLayout(this).apply { contentDescription="返回";isFocusable=true;setOnClickListener{finish()};addView(Glyph(this@PeopleActivity,"back"),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER)) },LinearLayout.LayoutParams(dp(48),dp(48)))
        top.addView(label(if(peer.isEmpty())"黑名单" else "聊天设置",19f,bold=true));root.addView(top)
        body=col().apply { setPadding(dp(20),dp(16),dp(20),dp(28)) }
        root.addView(ScrollView(this).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);root.requestApplyInsets();load()
    }
    override fun onResume(){super.onResume();if(Hub.token!=session)finish()}
    private fun task(work:()->JSONObject,done:(JSONObject)->Unit) {
        if(!valid()||busy)return
        busy=true
        Hub.job({check(Hub.token==session){"登录状态已改变"};work()}, {
            busy=false;if(valid())done(it)
        }, { busy=false;if(valid()){Toast.makeText(this,it,Toast.LENGTH_LONG).show();body.addView(label("操作失败，可点此重试",14f,palette.accent).apply{setOnClickListener{load()}})} })
    }
    private fun load() {
        if(!valid()||busy)return
        body.removeAllViews();body.addView(label("正在加载…",14f,palette.muted))
        val revision=++generation
        task({Hub.rawApi("GET",if(peer.isEmpty())"/api/blocks" else "/api/relationships/$peer",session=session)}) { result ->
            if(revision!=generation)return@task
            body.removeAllViews()
            if(peer.isEmpty()) {
                body.addView(label("拉黑后无法互发消息、通话或查看动态。解除后需要重新添加好友。",13f,palette.muted))
                val blocks=result.getJSONArray("blocks")
                if(blocks.length()==0)body.addView(label("黑名单为空",17f,bold=true))
                for(i in 0 until blocks.length()) {
                    val u=blocks.getJSONObject(i)
                    body.addView(label(u.optString("nickname"),17f,bold=true))
                    body.addView(label("弥星号  "+u.getString("id"),12f,palette.muted))
                    action("解除拉黑") { confirm("解除拉黑？","解除后双方需要重新添加好友。") { mutate("/api/blocks",JSONObject().put("peer",u.getString("id")).put("blocked",false)) } }
                }
            } else {
                body.addView(label(result.optString("nickname"),22f,bold=true))
                body.addView(label("弥星号  $peer",13f,palette.muted))
                if(result.optBoolean("bot"))body.addView(label("第三方机器人\n"+result.optString("bot_description")+"\n发给机器人的内容由弥星服务器处理并推送给开发者。机器人聊天不属于用户间端到端加密。",13f,palette.muted))
                val blocked=result.optBoolean("blocked_by_me");val friend=result.optBoolean("friend")
                body.addView(label(when{blocked->"已加入黑名单";friend->"你们已经是好友";else->"当前未建立好友关系"},13f,palette.muted))
                if(friend&&result.optBoolean("bot")) {
                    val allowed=result.optBoolean("bot_calls_allowed",true)
                    body.addView(label(if(result.optBoolean("bot_calls_enabled"))"机器人支持语音与视频通话。默认允许来电，你仍需手动接听；可在这里关闭。" else "开发者暂未启用机器人通话。",13f,palette.muted))
                    action(if(allowed)"关闭机器人来电" else "允许机器人来电") {
                        confirm(if(allowed)"关闭机器人来电？" else "允许机器人来电？",if(allowed)"机器人将无法主动拨打你，当前由它发起的通话也会结束。你仍可主动拨打。" else "该机器人可以邀请你语音或视频通话；你仍需手动接听。通话内容由机器人开发者接收处理。") {
                            mutate("/api/relationships/$peer/bot-calls",JSONObject().put("allowed",!allowed))
                        }
                    }
                }
                if(!friend&&!blocked)action("添加好友") {
                    if(result.optBoolean("bot"))confirm("添加机器人？","发给机器人的文字、语音、图片和文件会由服务器处理并交给第三方开发者。默认允许机器人来电，可在聊天设置关闭；来电仍需手动接听。") {mutate("/api/friends/request",JSONObject().put("peer",peer).put("bot_consent",true),"机器人已添加")}
                    else mutate("/api/friends/request",JSONObject().put("peer",peer),"申请已发送，等待对方通过")
                }
                action("搜索聊天记录"){setResult(RESULT_OK,android.content.Intent().putExtra("action","search"));finish()}
                action("清空聊天记录",true) { confirm("清空聊天记录？","会同步清空这个账号各设备上的这段聊天。对方的记录不受影响，清空后无法恢复。") { mutate("/api/conversations/$peer/clear",JSONObject()) } }
                if(friend)action("删除好友",true) { confirm("删除好友？","双方将解除好友关系，聊天记录保留。重新联系需通过好友验证。") { mutate("/api/friends/remove",JSONObject().put("peer",peer)) } }
                action(if(blocked)"解除拉黑" else "加入黑名单",!blocked) { confirm(if(blocked)"解除拉黑？" else "加入黑名单？",if(blocked)"解除后需要重新添加好友。" else "双方将解除好友关系，不能互发消息或通话，聊天记录保留。") { mutate("/api/blocks",JSONObject().put("peer",peer).put("blocked",!blocked)) } }
            }
        }
    }
    private fun action(title:String,danger:Boolean=false,click:()->Unit) {
        body.addView(label(title,15f,if(danger)Color.rgb(192,63,67) else palette.accent,true).apply {
            gravity=Gravity.CENTER;minimumHeight=dp(52);isFocusable=true;contentDescription=title
            background=BrandUi.card(this@PeopleActivity,palette,18)
            setOnClickListener { if(!busy)click() }
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
    }
    private fun confirm(title:String,message:String,yes:()->Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确定"){_,_->if(valid())yes()}.show()
    }
    private fun mutate(path:String,data:JSONObject,note:String="已完成") {
        task({Hub.rawApi("POST",path,data,session).also {
            if(path=="/api/friends/request")runCatching { ChatCrypto.adoptPeerAfterFriendAction(data.getString("peer"),session) }
        }}) {
            setResult(RESULT_OK);Toast.makeText(this,note,Toast.LENGTH_SHORT).show();load()
        }
    }
}
