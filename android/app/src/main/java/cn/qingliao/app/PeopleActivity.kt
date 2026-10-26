package cn.qingliao.app

import android.app.*
import android.content.Intent
import android.os.*
import android.view.*
import android.widget.*
import org.json.JSONObject

/** Relationship controls plus device-local conversation preferences. */
class PeopleActivity:Activity(){
    private val session=Hub.token
    private val owner=Hub.me.optString("id")
    private val peer by lazy{intent.getStringExtra("peer").orEmpty()}
    private val p by lazy{ThemeStore.current(this)}
    private val u by lazy{FormUi(this,p)}
    private lateinit var body:LinearLayout
    private lateinit var status:TextView
    private var busy=false
    private var profile:JSONObject?=null
    private val dialogs=mutableSetOf<AlertDialog>()
    private fun valid()=!isFinishing&&!isDestroyed&&session.isNotEmpty()&&Hub.token==session&&Hub.me.optString("id")==owner
    override fun finish(){super.finish();Motion.activityClose(this)}
    override fun onResume(){super.onResume();if(!valid())finish()}
    override fun onDestroy(){dialogs.toList().forEach{it.dismiss()};dialogs.clear();super.onDestroy()}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);if(!valid()){finish();return}
        window.statusBarColor=p.canvas;window.navigationBarColor=p.canvas
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=u.col().apply{setBackgroundColor(p.canvas)}
        root.setOnApplyWindowInsetsListener{v,i->if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom)}else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i}
        val top=u.row().apply{setPadding(u.dp(4),u.dp(4),u.dp(12),u.dp(4))};top.addView(u.icon("back","返回"){finish()},u.lp(48,48));top.addView(u.text(if(peer.isEmpty())"黑名单"else"聊天设置",18f,bold=true));root.addView(top,u.lp())
        body=u.col().apply{setPadding(u.dp(if(u.compact)12 else 20),u.dp(12),u.dp(if(u.compact)12 else 20),u.dp(28))}
        root.addView(ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(body)},LinearLayout.LayoutParams(-1,0,1f));setContentView(root);root.requestApplyInsets();load()
    }
    private fun status(){status=u.text("",13f,p.accent).apply{tag="people.status";visibility=View.GONE;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};body.addView(status,u.lp())}
    private fun enable(v:View,value:Boolean){if(v.isClickable||v is Switch){v.isEnabled=value;v.alpha=if(value)1f else .55f};if(v is ViewGroup)for(n in 0 until v.childCount)enable(v.getChildAt(n),value)}
    private fun task(work:()->JSONObject,done:(JSONObject)->Unit){
        if(!valid()||busy)return;busy=true;status.text="正在处理…";status.visibility=View.VISIBLE;enable(body,false)
        Hub.job({check(Hub.token==session);work()},{busy=false;if(valid()){enable(body,true);status.visibility=View.GONE;done(it)}},{busy=false;if(valid()){enable(body,true);status.text=it;status.visibility=View.VISIBLE;status.post{status.requestRectangleOnScreen(android.graphics.Rect(0,0,status.width,status.height),false)}}})
    }
    private fun load(){
        if(!valid()||busy)return;body.removeAllViews();status()
        body.addView(u.button("重新加载","people.reload",false){load()},u.lp())
        task({ConversationState.load(owner,session);Hub.rawApi("GET",if(peer.isEmpty())"/api/blocks"else"/api/relationships/$peer",session=session)}){r->body.removeAllViews();status();if(peer.isEmpty())blocks(r)else{profile=r;relationship(r)}}
    }
    private fun row(parent:LinearLayout,title:String,detail:String,key:String,danger:Boolean=false,action:()->Unit){
        val r=u.row().apply{tag=key;minimumHeight=u.dp(56);setPadding(u.dp(2),u.dp(10),u.dp(2),u.dp(10));isFocusable=true;setOnClickListener{if(!busy&&valid())action()};Motion.press(this)}
        val text=u.col();text.addView(u.text(title,14f,if(danger)0xffb13e50.toInt()else p.ink,true),u.lp());if(detail.isNotEmpty()){u.gap(text,5);text.addView(u.text(detail,12f,p.muted),u.lp())};r.addView(text,LinearLayout.LayoutParams(0,-2,1f));r.addView(u.text("›",22f,p.muted));parent.addView(r,u.lp())
    }
    private fun localToggle(parent:LinearLayout,title:String,detail:String,key:String,enabled:Boolean){
        val r=u.row().apply{minimumHeight=u.dp(64);setPadding(0,u.dp(6),0,u.dp(6))};val text=u.col();text.addView(u.text(title,14f,bold=true),u.lp());u.gap(text,4);text.addView(u.text(detail,12f,p.muted),u.lp());r.addView(text,LinearLayout.LayoutParams(0,-2,1f))
        val toggle=Switch(this).apply{tag="people.$key";contentDescription=title;isChecked=enabled}
        toggle.setOnClickListener{
            toggle.isChecked=enabled
            task({ConversationState.flag(owner,session,peer,key,!enabled);JSONObject()}){
                if(key=="muted"&&!enabled)getSystemService(android.app.NotificationManager::class.java).cancel(peer.hashCode())
                setResult(RESULT_OK);profile?.let{body.removeAllViews();status();relationship(it)}?:load()
            }
        };r.addView(toggle,u.lp(-2,48));parent.addView(r,u.lp())
    }
    private fun relationship(r:JSONObject){
        val identity=u.card();val header=u.row()
        val avatar=FrameLayout(this).apply{background=BrandUi.rounded(this@PeopleActivity,p.soft,18)}
        avatar.addView(u.text(r.optString("nickname").take(1),23f,p.accent,true).apply{gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
        val image=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;visibility=View.GONE};avatar.addView(image,FrameLayout.LayoutParams(-1,-1));AvatarImages.load(r.optString("avatar_id"),image);avatar.clipToOutline=true
        header.addView(avatar,u.lp(50,50));val names=u.col().apply{setPadding(u.dp(12),0,0,0)};names.addView(u.text(r.optString("nickname"),19f,bold=true).apply{maxLines=2},u.lp());u.gap(names,6);names.addView(u.text("弥星号 $peer",12f,p.muted),u.lp());header.addView(names,LinearLayout.LayoutParams(0,-2,1f));identity.addView(header,u.lp())
        val blocked=r.optBoolean("blocked_by_me");val friend=r.optBoolean("friend")
        if(!u.tiny||!friend||blocked){u.gap(identity,12);identity.addView(u.text(when{blocked->"已加入黑名单";friend->"已成为好友";else->"尚未成为好友"},12f,p.muted),u.lp())};body.addView(identity,u.lp())
        if(r.optBoolean("bot")){u.gap(body,12);body.addView(u.text("第三方机器人 · ${r.optString("bot_description")}\n发给它的消息与通话内容会交给开发者处理，机器人聊天不属于用户间端到端加密。",12f,p.muted),u.lp())}
        if(u.tiny)u.gap(body,12)else u.section(body,"会话偏好","置顶与消息免打扰在此设备生效。")
        val local=ConversationState.state(owner,peer);val preferences=u.card();localToggle(preferences,"置顶聊天",if(u.tiny)"列表优先显示"else"常用对话排在消息列表前面。","pinned",local.optLong("pinned_at")>0)
        localToggle(preferences,"消息免打扰",if(u.tiny)"来电仍会提醒"else"保留未读标记，不发消息通知；来电不受影响。","muted",local.optBoolean("muted"));body.addView(preferences,u.lp())
        if(u.tiny){u.gap(body,8);body.addView(u.text("偏好仅在此设备生效",11f,p.muted),u.lp())}
        u.section(body,"聊天内容")
        val content=u.card();row(content,"搜索聊天记录","查找本机保存的文字与文件名。","people.search"){setResult(RESULT_OK,Intent().putExtra("action","search"));finish()}
        row(content,"清空聊天记录","同步清空此账号各设备的这段记录。","people.clear",true){confirm("清空聊天记录？","此账号各设备的这段聊天记录将被清空，无法恢复。对方的记录不受影响。"){mutate("/api/conversations/$peer/clear",JSONObject())}}
        body.addView(content,u.lp())
        if(friend&&r.optBoolean("bot")){
            u.section(body,"机器人来电")
            val calls=u.card();val allowed=r.optBoolean("bot_calls_allowed",true)
            row(calls,if(allowed)"关闭机器人来电"else"允许机器人来电",if(r.optBoolean("bot_calls_enabled"))"来电仍需你手动接听。"else"开发者尚未启用通话能力。","people.botCalls"){
                confirm(if(allowed)"关闭机器人来电？"else"允许机器人来电？",if(allowed)"机器人将无法主动拨打你，当前由它发起的通话也会结束。你仍可主动拨打。"else"该机器人可以邀请你语音或视频通话；仍需手动接听，内容由开发者接收处理。"){
                    mutate("/api/relationships/$peer/bot-calls",JSONObject().put("allowed",!allowed))
                }
            };body.addView(calls,u.lp())
        }
        u.section(body,"关系管理")
        val relationship=u.card()
        if(!friend&&!blocked)row(relationship,"添加好友","","people.add"){
            val send={mutate("/api/friends/request",JSONObject().put("peer",peer).put("bot_consent",r.optBoolean("bot")),if(r.optBoolean("bot"))"机器人已添加"else"申请已发送")}
            if(r.optBoolean("bot"))confirm("添加机器人？","文字、语音、图片和文件会由服务器处理并交给第三方开发者。默认允许机器人来电，可自行关闭；来电仍需手动接听。",send)else send()
        }
        if(friend)row(relationship,"删除好友","聊天记录保留，重新联系需要好友验证。","people.remove",true){confirm("删除好友？","双方将解除好友关系，聊天记录保留。重新联系需要好友验证。") {mutate("/api/friends/remove",JSONObject().put("peer",peer))}}
        row(relationship,if(blocked)"解除拉黑"else"加入黑名单",if(blocked)"解除后需要重新添加好友。"else"停止互发消息、通话及查看动态。","people.block",!blocked){confirm(if(blocked)"解除拉黑？"else"加入黑名单？",if(blocked)"解除后双方需要重新添加好友。"else"双方将解除好友关系，无法互发消息或通话，聊天记录保留。") {mutate("/api/blocks",JSONObject().put("peer",peer).put("blocked",!blocked))}}
        body.addView(relationship,u.lp())
    }
    private fun blocks(r:JSONObject){
        body.addView(u.text("拉黑后无法互发消息、通话或查看动态。解除后需要重新添加好友。",13f,p.muted),u.lp());val blocks=r.getJSONArray("blocks")
        if(blocks.length()==0){u.gap(body,24);body.addView(u.text("黑名单为空",20f,bold=true),u.lp())}
        for(n in 0 until blocks.length()){val user=blocks.getJSONObject(n);u.gap(body,12);val c=u.card();row(c,user.optString("nickname"),"弥星号 ${user.getString("id")} · 点击解除拉黑","people.unblock.${user.getString("id")}"){confirm("解除拉黑？","解除后双方需要重新添加好友。") {mutate("/api/blocks",JSONObject().put("peer",user.getString("id")).put("blocked",false))}};body.addView(c,u.lp())}
    }
    private fun confirm(title:String,message:String,yes:()->Unit){
        val d=AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确定"){_,_->if(valid()&&!busy)yes()}.create()
        dialogs.add(d);d.setOnDismissListener{dialogs.remove(d)};d.show()
    }
    private fun mutate(path:String,data:JSONObject,note:String="已完成"){
        task({Hub.rawApi("POST",path,data,session).also{if(path=="/api/friends/request")runCatching{ChatCrypto.adoptPeerAfterFriendAction(data.getString("peer"),session)}}}){setResult(RESULT_OK);Toast.makeText(this,note,Toast.LENGTH_SHORT).show();load()}
    }
}
