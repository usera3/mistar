package cn.qingliao.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import android.media.MediaPlayer
import android.graphics.BitmapFactory
import okhttp3.Request
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Server authorizes every request. A local role flag only controls the navigation entry. */
class AdminActivity:Activity() {
    private val session=Hub.token
    private val palette by lazy{ThemeStore.current(this)}
    private val compact by lazy{CompactUi.enabled(this)}
    private lateinit var root:LinearLayout
    private lateinit var list:LinearLayout
    private lateinit var status:TextView
    @Volatile private var grant=""
    @Volatile private var grantUntil=0L
    private var revision=0
    @Volatile private var foreground=true
    private var busy=false
    private var loaded=false
    private var requiresPasswordChange=false
    private var tab="posts"
    private var state="all"
    private var query=""
    private var owner=""
    private var postId=""
    private var next=""
    private var rows=JSONArray()
    private val active=ConcurrentHashMap.newKeySet<Call>()
    private var mediaDialog:Dialog?=null
    private var actionDialog:Dialog?=null
    private var player:MediaPlayer?=null
    private var mediaFile:File?=null
    private val stagedFiles=ConcurrentHashMap.newKeySet<File>()
    private val expire=Runnable{lock()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun valid()=foreground&&!isFinishing&&!isDestroyed&&Hub.token==session&&session.isNotEmpty()
    private fun unlocked()=grant.isNotEmpty()&&SystemClock.elapsedRealtime()<grantUntil
    private fun col()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun text(value:String,size:Float=14f,color:Int=palette.ink,bold:Boolean=false)=TextView(this).apply{
        this.text=value;textSize=if(compact)minOf(size,16f).let{if(it in 13f..15f)it-1f else it}else size;setTextColor(color);setPadding(0,dp(if(compact)4 else 7),0,dp(if(compact)4 else 7));if(bold)setTypeface(typeface,Typeface.BOLD)
    }
    private fun button(parent:LinearLayout,value:String,primary:Boolean=false,run:()->Unit):TextView=text(value,14f,if(primary)palette.onPrimary else palette.accent,true).apply{
        gravity=Gravity.CENTER;background=if(primary)BrandUi.primary(this@AdminActivity,palette) else BrandUi.field(this@AdminActivity,palette)
        setPadding(dp(10),dp(12),dp(10),dp(12));minimumHeight=dp(44);setOnClickListener{if(!busy&&valid())run()};Motion.press(this)
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
    }
    private fun field(parent:LinearLayout,hint:String,secret:Boolean=false):EditText=EditText(this).apply{
        this.hint=hint;textSize=if(compact)12f else 14f;setTextColor(palette.ink);setHintTextColor(palette.muted);isSingleLine=true
        inputType=InputType.TYPE_CLASS_TEXT or if(secret)InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL
        isSaveEnabled=false;filters=arrayOf(InputFilter.LengthFilter(if(secret)128 else 100));background=BrandUi.field(this@AdminActivity,palette)
        setPadding(dp(12),dp(10),dp(12),dp(10));parent.addView(this,LinearLayout.LayoutParams(-1,dp(50)).apply{bottomMargin=dp(8)})
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor=palette.canvas;window.navigationBarColor=palette.canvas
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root=col().apply{setBackgroundColor(palette.canvas)}
        root.setOnApplyWindowInsetsListener{v,i->
            if(android.os.Build.VERSION.SDK_INT>=30){val x=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(x.left,x.top,x.right,x.bottom)}
            else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i
        }
        val heading=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(5),dp(12),0)}
        heading.addView(text("‹ 返回",if(compact)13f else 16f,palette.accent).apply{setPadding(dp(4),dp(8),dp(if(compact)8 else 16),dp(8));minimumHeight=dp(40);setOnClickListener{finish()}})
        heading.addView(text("管理中心",21f,palette.ink,true));root.addView(heading)
        status=text("正在验证管理员权限…",if(compact)10f else 12f,palette.muted).apply{setPadding(dp(16),0,dp(16),dp(if(compact)4 else 8));if(compact){isSingleLine=true;ellipsize=TextUtils.TruncateAt.END};accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};root.addView(status)
        val scroll=ScrollView(this);list=col().apply{isFocusableInTouchMode=true;setPadding(dp(14),0,dp(14),dp(24))};scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        if(session.isEmpty()){finish();return};loadStatus()
    }
    override fun onResume(){super.onResume();foreground=true;if(Hub.token!=session){finish();return};if(!loaded&&!busy)loadStatus()else if(loaded&&!busy&&!unlocked()){if(requiresPasswordChange)changePassword()else lock()}}
    override fun onStop(){
        foreground=false;revision++;busy=false;grant="";grantUntil=0;Hub.main.removeCallbacks(expire)
        active.toList().forEach{it.cancel()};actionDialog?.dismiss();actionDialog=null;closeMedia();super.onStop()
    }
    override fun onDestroy(){active.toList().forEach{it.cancel()};Hub.main.removeCallbacks(expire);closeMedia();grant="";super.onDestroy()}
    private fun closeMedia(){mediaDialog?.dismiss();mediaDialog=null;try{player?.release()}catch(_:Exception){};player=null;mediaFile?.delete();mediaFile=null;stagedFiles.toList().forEach{it.delete()};stagedFiles.clear()}
    private fun raw(method:String,path:String,body:JSONObject?=null,privileged:Boolean=true):JSONObject{
        check(Hub.token==session&&foreground){"登录状态已改变"}
        val ticket=grant
        if(privileged)check(ticket.isNotEmpty()&&SystemClock.elapsedRealtime()<grantUntil){"管理验证已过期"}
        val request=Request.Builder().url(Hub.SERVER+path).header("Authorization","Bearer $session")
        if(privileged)request.header("X-Admin-Grant",ticket)
        request.method(method,if(method=="POST") (body?:JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType()) else null)
        val call=Hub.http.newCall(request.build());active.add(call)
        try{call.execute().use{response->
            val output=ByteArrayOutputStream()
            response.body?.byteStream()?.use{input->val bytes=ByteArray(16384);while(true){val n=input.read(bytes);if(n<0)break;require(output.size()+n<=3*1024*1024){"管理列表过大"};output.write(bytes,0,n)}}
            val result=JSONObject(output.toString("UTF-8").ifEmpty{"{}"})
            if(!response.isSuccessful)throw AdminFailure(response.code,result.optString("error","管理请求失败"))
            return result
        }}finally{active.remove(call)}
    }
    private class AdminFailure(val code:Int,message:String):Exception(message)
    private fun work(task:()->JSONObject,done:(JSONObject)->Unit){
        if(busy||!valid())return
        val stamp=revision;busy=true;status.text="正在处理…"
        Hub.job({task()},{p->if(valid()&&stamp==revision){busy=false;status.text="";done(p)}},{message->if(valid()&&stamp==revision){busy=false;status.text=message}})
    }
    private fun loadStatus(){work({raw("GET","/api/admin/status",privileged=false)},{p->
        loaded=true;requiresPasswordChange=p.optBoolean("must_change_password")
        if(requiresPasswordChange)changePassword() else lock()
    })}
    private fun changePassword(){
        list.removeAllViews();list.addView(text("设置管理员密码",20f,bold=true))
        list.addView(text("首次登录需要更换初始密码。修改后重新登录管理账号。",13f,palette.muted))
        val old=field(list,"初始密码",true);val new=field(list,"新密码 · 至少 12 位",true);val again=field(list,"再次输入新密码",true)
        button(list,"修改密码并重新登录",true){
            if(new.text.length !in 12..128||new.text.toString()!=again.text.toString()){status.text="新密码至少 12 位，两次输入需一致";return@button}
            val body=JSONObject().put("old_password",old.text.toString()).put("new_password",new.text.toString());old.text.clear();new.text.clear();again.text.clear()
            work({raw("POST","/api/password/change",body,false)},{Hub.logout();Toast.makeText(this,"管理员密码已更新，请重新登录",Toast.LENGTH_LONG).show();finish()})
        }
    }
    private fun lock(){
        if(!valid())return
        revision++;busy=false;grant="";grantUntil=0;Hub.main.removeCallbacks(expire);actionDialog?.dismiss();actionDialog=null;closeMedia()
        list.removeAllViews();list.addView(text("验证管理身份",20f,bold=true));list.addView(text("验证密码后，可管理全站动态、评论和账号。离开管理中心后将重新验证。",13f,palette.muted))
        val password=field(list,"管理员密码",true)
        button(list,"进入管理中心",true){
            val value=password.text.toString();password.text.clear()
            work({raw("POST","/api/admin/unlock",JSONObject().put("password",value),false)},{p->
                grant=p.getString("grant");grantUntil=SystemClock.elapsedRealtime()+600000;Hub.main.postDelayed(expire,600000);fetch()
            })
        }
        status.text="广场已下线 · 好友动态仍可管理"
    }
    private fun path(cursor:String=""):String{
        val page=if(cursor.isEmpty())"" else "&before="+android.net.Uri.encode(cursor)
        return when(tab){
            "users"->"/api/admin/users?q="+android.net.Uri.encode(query)+page
            "audit"->"/api/admin/audit"+(if(cursor.isEmpty())"" else "?before="+cursor)
            "comments"->"/api/admin/posts/$postId/comments?x=1"+page
            else->"/api/admin/posts?state=$state&q="+android.net.Uri.encode(query)+(if(owner.isEmpty())"" else "&owner=$owner")+page
        }
    }
    private fun fetch(older:Boolean=false){
        if(!unlocked()){lock();return}
        val page=if(older)next else "";if(older&&page.isEmpty())return
        val mode=tab;val route=path(page)
        work({raw("GET",route)},{p->
            if(tab!=mode)return@work
            val found=p.optJSONArray(when(tab){"users"->"users";"audit"->"logs";"comments"->"comments";else->"posts"})?:JSONArray()
            if(!older)rows=JSONArray()
            for(i in 0 until found.length())rows.put(found.getJSONObject(i));next=p.optString("next");render()
        })
    }
    private fun render(){
        list.removeAllViews()
        val nav=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        listOf("posts" to "动态","users" to "账号","audit" to "记录").forEach{(key,name)->
            nav.addView(text(if(tab==key)"● $name" else name,14f,palette.accent,true).apply{gravity=Gravity.CENTER;setPadding(dp(10),dp(12),dp(10),dp(12));setOnClickListener{if(!busy){tab=key;next="";owner="";query="";fetch()}}},LinearLayout.LayoutParams(0,-2,1f))
        };list.addView(nav)
        if(tab=="posts"||tab=="users"){
            if(compact){
                val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
                val search=field(row,"内容 / 弥星号").apply{setText(query);layoutParams=LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(6);bottomMargin=dp(8)}}
                button(row,"搜索"){query=search.text.toString().trim();fetch()}.apply{layoutParams=LinearLayout.LayoutParams(-2,dp(44)).apply{bottomMargin=dp(8)}}
                list.addView(row)
            }else{
                val search=field(list,"搜索内容、账号或弥星号").apply{setText(query)}
                button(list,"搜索 / 刷新"){query=search.text.toString().trim();fetch()}
            }
        }else button(list,if(tab=="comments")"刷新评论" else "刷新操作记录"){fetch()}
        if(tab=="posts"){
            if(owner.isNotEmpty()){list.addView(text("当前发布者：$owner",12f,palette.muted));button(list,"查看所有发布者"){owner="";fetch()}}
            button(list,"筛选："+mapOf("all" to "全部","visible" to "未下架","hidden" to "已下架")[state]){
                AlertDialog.Builder(this).setTitle("动态状态").setItems(arrayOf("全部","未下架","已下架")){_,which->state=arrayOf("all","visible","hidden")[which];fetch()}.show()
            }
        }
        if(rows.length()==0)list.addView(text("暂无符合条件的内容",14f,palette.muted))
        for(i in 0 until rows.length()){
            val p=rows.getJSONObject(i)
            val card=col().apply{background=BrandUi.card(this@AdminActivity,palette,16);setPadding(dp(14),dp(10),dp(14),dp(10))}
            when(tab){"users"->userCard(card,p);"audit"->logCard(card,p);"comments"->commentCard(card,p);else->postCard(card,p)}
            list.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
        }
        if(next.isNotEmpty())button(list,"加载更多"){fetch(true)}
        button(list,"锁定管理中心"){lock()};status.text="当前显示 ${rows.length()} 条 · 操作会记录处理原因"
    }
    private fun date(value:Long)=SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(value))
    private fun author(card:LinearLayout,user:JSONObject){
        card.addView(text(user.optString("nickname")+" · "+user.optString("id"),15f,bold=true))
        card.addView(text("@"+user.optString("username"),12f,palette.muted))
    }
    private fun postCard(card:LinearLayout,p:JSONObject){
        val user=p.getJSONObject("user");author(card,user)
        val hidden=p.optJSONObject("moderation")?.optBoolean("hidden")==true
        card.addView(text(date(p.optLong("created"))+" · "+if(hidden)"已下架" else if(p.optString("audience")=="public")"原广场动态（已关闭）" else "好友可见",12f,palette.muted))
        card.addView(text(p.optString("text"),14f).apply{setTextIsSelectable(true)})
        val photos=p.optJSONArray("photos")?:JSONArray()
        for(i in 0 until photos.length()){val fid=photos.getJSONObject(i).getString("id");button(card,"查看图片 ${i+1} / ${photos.length()}"){showMedia(fid,"image")}}
        p.optJSONObject("media")?.let{m->
            if(m.optString("kind")=="video"){
                m.optJSONObject("cover")?.let{c->button(card,"查看视频封面"){showMedia(c.getString("id"),"image")}}
                button(card,"播放视频"){showMedia(m.getString("id"),"video")}
            }else button(card,"播放语音"){showMedia(m.getString("id"),"voice")}
        }
        button(card,"管理评论（${p.optInt("comment_count")} 条可见）"){postId=p.getString("id");tab="comments";fetch()}
        button(card,if(hidden)"恢复动态" else "下架动态"){
            reason(if(hidden)"恢复这条动态" else "下架这条动态"){r->mutate("/api/admin/content/post/"+p.getString("id"),JSONObject().put("hidden",!hidden).put("reason",r))}
        }
        button(card,"处理发布者账号"){tab="users";query=user.getString("id");fetch()}
    }
    private fun commentCard(card:LinearLayout,p:JSONObject){
        author(card,p.getJSONObject("user"));card.addView(text(p.optString("text")))
        val hidden=p.optJSONObject("moderation")?.optBoolean("hidden")==true
        card.addView(text(date(p.optLong("created"))+if(hidden)" · 已下架" else " · 可见",12f,palette.muted))
        button(card,if(hidden)"恢复评论" else "下架评论"){reason(if(hidden)"恢复评论" else "下架评论"){r->mutate("/api/admin/content/comment/"+p.getString("id"),JSONObject().put("hidden",!hidden).put("reason",r))}}
    }
    private fun until(value:Long)=when{value==-1L->"永久";value>System.currentTimeMillis()->date(value)+" 到期";else->"未限制"}
    private fun userCard(card:LinearLayout,p:JSONObject){
        author(card,p);card.addView(text("注册：${date(p.optLong("created"))}\n动态：${p.optInt("post_count")} 条\n封禁：${until(p.optLong("banned_until"))}\n限发动态：${until(p.optLong("posting_until"))}",12f,palette.muted))
        if(p.optBoolean("administrator")){card.addView(text("管理员账号 · 受保护",13f,palette.accent));return}
        val uid=p.getString("id")
        button(card,"查看该账号的全部动态"){tab="posts";owner=uid;query="";state="all";fetch()}
        button(card,"限制发布动态 / 评论"){duration("限发动态",uid,"restrict_posts")}
        button(card,"解除限发"){userAction(uid,"restore_posts","解除该账号的动态限制")}
        button(card,"封禁账号"){duration("封禁账号",uid,"ban")}
        button(card,"解除封禁"){userAction(uid,"unban","解除该账号封禁")}
        button(card,"强制所有设备下线"){userAction(uid,"logout","强制该账号的所有登录设备下线")}
        button(card,"下架该账号的全部动态"){userAction(uid,"hide_posts","下架该账号的全部动态（可逐条恢复）")}
    }
    private fun userAction(uid:String,action:String,title:String){reason(title){r->mutate("/api/admin/users/$uid/action",JSONObject().put("action",action).put("reason",r))}}
    private fun duration(title:String,uid:String,action:String){
        val days=arrayOf(1,3,7,30,90,365,0);var selected=0
        val dialog=AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(arrayOf("1 天","3 天","7 天","30 天","90 天","365 天","永久"),0){_,i->selected=i}
            .setNegativeButton("取消",null).setPositiveButton("下一步"){_,_->reason("$title · "+if(days[selected]==0)"永久" else "${days[selected]} 天"){r->mutate("/api/admin/users/$uid/action",JSONObject().put("action",action).put("days",days[selected]).put("reason",r))}}.create()
        trackAction(dialog)
    }
    private fun reason(title:String,done:(String)->Unit){
        if(!unlocked()){lock();return}
        val form=col().apply{setPadding(dp(18),dp(6),dp(18),0)}
        form.addView(text("处理原因会记录在管理日志中。",13f,palette.muted))
        val input=field(form,"填写处理原因（2–300 字）").apply{isSingleLine=false;maxLines=3;filters=arrayOf(InputFilter.LengthFilter(300))}
        val dialog=AlertDialog.Builder(this).setTitle(title).setView(form).setNegativeButton("取消",null).setPositiveButton("确认处理",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val value=input.text.toString().trim();if(value.length<2){input.error="请填写处理原因";return@setOnClickListener}
            dialog.dismiss();if(valid()&&unlocked())done(value)else lock()
        }};trackAction(dialog)
    }
    private fun trackAction(dialog:Dialog){actionDialog?.dismiss();actionDialog=dialog;dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.setOnDismissListener{if(actionDialog===dialog)actionDialog=null};dialog.show()}
    private fun mutate(path:String,body:JSONObject){work({raw("POST",path,body)},{MomentFeedCache.clear();fetch()})}
    private fun logCard(card:LinearLayout,p:JSONObject){
        val action=mapOf("bootstrap" to "创建管理员","hide" to "下架","restore" to "恢复","ban" to "封禁","unban" to "解封","restrict_posts" to "限发动态","restore_posts" to "解除限发","logout" to "强制下线","hide_posts" to "批量下架")[p.optString("action")]?:p.optString("action")
        card.addView(text(action+" · "+p.optString("target"),15f,bold=true));card.addView(text(p.optString("reason"),14f));card.addView(text(date(p.optLong("created"))+" · 管理员 "+p.optString("actor")+" · 变更 ${p.optInt("changed")} 项",11f,palette.muted))
    }
    private fun showMedia(fid:String,kind:String){
        if(!fid.matches(Regex("[a-f0-9]{32}"))||!unlocked()){lock();return}
        closeMedia();val ticket=grant
        work({
            val maximum=if(kind=="video")25*1024*1024 else if(kind=="voice")5*1024*1024 else 2*1024*1024
            val call=Hub.http.newCall(Request.Builder().url(Hub.SERVER+"/api/admin/media/$fid").header("Authorization","Bearer $session").header("X-Admin-Grant",ticket).build());active.add(call)
            val file=File.createTempFile("admin-media-",if(kind=="video")".mp4" else if(kind=="voice")".audio" else ".jpg",cacheDir)
            stagedFiles.add(file)
            try{
                check(valid()&&ticket==grant&&unlocked()){ "管理验证已结束" }
                call.execute().use{r->
                    if(!r.isSuccessful)throw Exception("媒体不可用或管理验证已过期")
                    require((r.body?.contentLength()?:0)<=maximum){"媒体文件过大"}
                    r.body!!.byteStream().use{input->file.outputStream().use{out->val b=ByteArray(32768);var total=0;while(true){val n=input.read(b);if(n<0)break;check(valid()&&ticket==grant&&unlocked()){ "管理验证已结束" };total+=n;require(total<=maximum);out.write(b,0,n)}}}
                }
                if(!valid()||ticket!=grant||!unlocked())throw Exception("管理验证已结束")
                JSONObject().put("path",file.absolutePath)
            }catch(e:Exception){file.delete();throw e}finally{active.remove(call)}
        },{p->
            val file=File(p.getString("path"));mediaFile=file
            val body=col().apply{setPadding(dp(14),dp(8),dp(14),dp(8))}
            if(kind=="image"){
                val dimensions=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,dimensions)
                var sample=1;while(dimensions.outWidth/sample>1600||dimensions.outHeight/sample>1600)sample*=2
                val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})
                if(bitmap==null){file.delete();status.text="图片不可用";return@work}
                body.addView(ImageView(this).apply{adjustViewBounds=true;setImageBitmap(bitmap)},LinearLayout.LayoutParams(-1,-2))
            }else if(kind=="video"){
                val video=VideoView(this);val controls=MediaController(this);controls.setAnchorView(video);video.setMediaController(controls);video.setVideoPath(file.path)
                video.setOnPreparedListener{video.start()};body.addView(video,LinearLayout.LayoutParams(-1,dp(if(CompactUi.enabled(this))160 else 320)))
                val dialog=AlertDialog.Builder(this).setTitle("管理预览 · 视频").setView(body).setPositiveButton("关闭",null).create()
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.setOnDismissListener{video.stopPlayback();file.delete();mediaFile=null};mediaDialog=dialog;dialog.show();return@work
            }else{
                body.addView(text("语音动态",17f,bold=true));val audio=MediaPlayer().apply{setDataSource(file.path);prepare()};player=audio
                button(body,"播放 / 暂停"){if(audio.isPlaying)audio.pause()else audio.start()}
            }
            val dialog=AlertDialog.Builder(this).setTitle("管理预览").setView(body).setPositiveButton("关闭",null).create()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.setOnDismissListener{try{player?.release()}catch(_:Exception){};player=null;file.delete();mediaFile=null};mediaDialog=dialog;dialog.show()
        })
    }
}
