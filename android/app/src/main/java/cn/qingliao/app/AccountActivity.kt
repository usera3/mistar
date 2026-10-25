package cn.qingliao.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject

class AccountActivity:Activity() {
    private lateinit var form:LinearLayout
    private lateinit var status:TextView
    private val palette by lazy { ThemeStore.current(this) }
    private var verification=""
    private var verificationEmail=""
    private var exportData=""
    private var importPassword=""
    private var busy=false
    private val openingSession=Hub.token
    private var revision=0
    private val timers=mutableListOf<Runnable>()
    private fun valid()=!isFinishing&&!isDestroyed&&Hub.token==openingSession
    private fun api(method:String,path:String,data:JSONObject?=null)=Hub.rawApi(method,path,data,openingSession)
    private fun request(path:String,body:()->JSONObject,done:(JSONObject)->Unit={status.text="已完成"}) {
        val captured=try{body()}catch(e:Exception){status.text=e.message;return}
        work({api("POST",path,captured)},done)
    }
    override fun onDestroy(){revision++;timers.forEach{Hub.main.removeCallbacks(it)};timers.clear();exportData="";importPassword="";super.onDestroy()}
    override fun onResume(){super.onResume();if(Hub.token!=openingSession)finish()}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(color:Int)=GradientDrawable().apply { setColor(color);cornerRadius=dp(14).toFloat() }
    private fun label(value:String,size:Float=14f):TextView=TextView(this).apply { text=value;textSize=size;setTextColor(palette.ink);setPadding(0,dp(10),0,dp(10)) }
    private fun gap(){form.addView(View(this),LinearLayout.LayoutParams(1,dp(14)))}
    private fun field(hint:String,secret:Boolean=false):EditText=EditText(this).apply {
        this.hint=hint;textSize=15f;setTextColor(palette.ink);setHintTextColor(palette.muted);isSingleLine=true
        inputType=if(secret)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        background=BrandUi.field(this@AccountActivity,palette);setPadding(dp(16),0,dp(16),0)
        form.addView(this,LinearLayout.LayoutParams(-1,dp(56)).apply { bottomMargin=dp(10) })
    }
    private fun action(title:String,primary:Boolean=true,callback:()->Unit):TextView=label(title,15f).apply {
        gravity=Gravity.CENTER;background=if(primary)BrandUi.primary(this@AccountActivity,palette) else shape(palette.soft);setTextColor(if(primary)palette.onPrimary else palette.accent)
        setPadding(dp(14),dp(14),dp(14),dp(14));setOnClickListener { if(!busy)callback() };Motion.press(this)
        form.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
    }
    private fun work(task:()->JSONObject,done:(JSONObject)->Unit={status.text="已完成"}) {
        if(busy||!valid())return
        val expected=revision;busy=true;status.text="正在处理…"
        Hub.job({check(Hub.token==openingSession);task()}, {
            busy=false;if(valid()&&revision==expected){status.text="";done(it)}else if(!isDestroyed)finish()
        },{busy=false;if(valid()&&revision==expected)status.text=it else if(!isDestroyed)finish()})
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor=palette.canvas;window.navigationBarColor=palette.canvas
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(palette.canvas) }
        root.setOnApplyWindowInsetsListener { v,i ->
            val bottom=if(android.os.Build.VERSION.SDK_INT>=30)i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom else i.systemWindowInsetBottom
            v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,bottom);i
        }
        val top=label("‹  返回",17f).apply { setPadding(dp(22),dp(16),dp(22),dp(16));setTextColor(palette.accent);setOnClickListener { finish() } };root.addView(top)
        val scroll=ScrollView(this);form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),0,dp(24),dp(32)) };scroll.addView(form);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        val reset=intent.getStringExtra("mode")=="reset"
        form.addView(label(if(reset)"找回密码" else "账号与安全",27f).apply { setTypeface(typeface,Typeface.BOLD) })
        status=label("").apply { setTextColor(Color.rgb(174,66,55));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        if(reset)resetForm() else accountForm()
        form.addView(status)
    }
    private fun codeButton(purpose:String,email:EditText,username:EditText?=null,password:EditText?=null) {
        lateinit var button:TextView
        button=action("获取邮箱验证码",false) {
            val address=email.text.toString().trim()
            val body=JSONObject().put("purpose",purpose).put("email",address)
            username?.let { body.put("username",it.text.toString()) };password?.let { body.put("password",it.text.toString()) }
            work({api("POST","/api/auth/code",body)},{
                verification=it.getString("verification_id");verificationEmail=address
                button.isEnabled=false;button.text="60 秒后可重发";status.text="信息匹配时会发送验证码，请检查收件箱及垃圾邮件"
                val timer=Runnable { if(valid()){button.isEnabled=true;button.text="获取邮箱验证码"} };timers.add(timer);Hub.main.postDelayed(timer,60000)
            })
        }
    }
    private fun checkedCode(email:EditText,code:EditText):JSONObject {
        require(verification.isNotEmpty() && verificationEmail==email.text.toString().trim() && code.text.length==6) { "请获取并填写此邮箱的验证码" }
        return JSONObject().put("email",verificationEmail).put("verification_id",verification).put("code",code.text.toString())
    }
    private fun passwords(a:EditText,b:EditText):String {
        require(a.text.length in 8..128) { "密码需为 8–128 位" };require(a.text.toString()==b.text.toString()) { "两次输入的密码不一致" };return a.text.toString()
    }
    private fun resetForm() {
        form.addView(label("使用注册或绑定时验证的邮箱找回账号。重置后，所有设备需要重新登录。"))
        val username=field("账号或弥星号")
        val email=field("已验证邮箱").apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        codeButton("reset",email,username)
        val code=field("6 位邮箱验证码").apply { inputType=InputType.TYPE_CLASS_NUMBER }
        val pass=field("新密码 · 至少 8 位",true);val confirm=field("再次输入新密码",true)
        action("重置密码") { request("/api/password/reset",{
            checkedCode(email,code).put("username",username.text.toString()).put("new_password",passwords(pass,confirm))
        },{ pass.text.clear();confirm.text.clear();status.text="密码已重置，请返回登录" }) }
    }
    private fun accountForm() {
        if(Hub.token.isEmpty()){finish();return}
        val address=label("正在读取邮箱状态…");form.addView(address)
        val binding=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };form.addView(binding)
        // Build binding controls in the same form, then hide them for verified accounts.
        val start=form.childCount
        val email=field("绑定验证邮箱").apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val current=field("当前登录密码",true)
        codeButton("bind",email,password=current)
        val code=field("6 位邮箱验证码").apply { inputType=InputType.TYPE_CLASS_NUMBER }
        action("验证并绑定邮箱") { request("/api/email/bind",{checkedCode(email,code).put("password",current.text.toString())},{
            address.text="已绑定邮箱："+verificationEmail;current.text.clear();status.text="邮箱验证完成，可用于找回密码"
            for(i in start until start+5)form.getChildAt(i)?.visibility=View.GONE
        }) }
        val bindingViews=(start until form.childCount).map { form.getChildAt(it) }
        work({api("GET","/api/account")},{p ->
            val value=p.optString("email");address.text=if(value.isEmpty())"尚未绑定邮箱，绑定后才能通过邮件找回密码。" else "已绑定邮箱：$value"
            if(value.isNotEmpty())bindingViews.forEach { it.visibility=View.GONE }
        })
        gap();form.addView(label("修改密码",19f))
        val old=field("当前密码",true);val new=field("新密码 · 至少 8 位",true);val again=field("再次输入新密码",true)
        action("修改密码并重新登录") { request("/api/password/change",{JSONObject().put("old_password",old.text.toString()).put("new_password",passwords(new,again))},{
            Hub.logout();startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));finish()
        }) }
        gap();form.addView(label("迁移加密聊天身份",19f))
        form.addView(label("日常聊天自动加密。换手机前，可导出带口令保护的身份备份，在新手机登录后导入。此文件和迁移口令请分开保管，口令应与登录密码不同。邮箱重置密码无法恢复丢失的聊天身份。"))
        val phrase=field("迁移口令 · 至少 12 位",true)
        action("导出加密身份备份",false) { val password=phrase.text.toString();work({
            JSONObject().put("backup",ChatCrypto.exportIdentity(password))
        },{exportData=it.getString("backup");phrase.text.clear();startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"弥星-聊天身份.qingkey"),71)}) }
        action("导入原设备身份备份",false) {
            if(phrase.text.length<12){status.text="请输入导出时设置的迁移口令";return@action}
            importPassword=phrase.text.toString();phrase.text.clear()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),72)
        }
        gap();action("旧设备和身份备份都丢失",false) { startActivity(Intent(this,RecoveryActivity::class.java));Motion.activityOpen(this) }
        form.addView(label("此版本保护新发送的一对一消息与附件。旧消息、头像和动态仍按原有方式保存；加密不隐藏通信时间、双方账号及附件大小。"))
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null){exportData="";importPassword="";return}
        if(requestCode==71){val text=exportData;exportData="";work({contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw Exception("无法保存备份");JSONObject()},{status.text="已保存带口令保护的身份备份"})}
        if(requestCode==72){val password=importPassword;importPassword="";work({
            val text=contentResolver.openInputStream(uri)?.use { input ->
                val out=java.io.ByteArrayOutputStream();val bytes=ByteArray(4096)
                while(true){val n=input.read(bytes);if(n<0)break;require(out.size()+n<=512*1024) { "备份文件过大" };out.write(bytes,0,n)};out.toString("UTF-8")
            } ?: throw Exception("无法读取备份")
            try{ChatCrypto.importIdentity(text,password)}catch(_:Exception){throw Exception("无法导入，请检查账号、备份文件和迁移口令")};JSONObject()
        },{status.text="聊天身份已导入，返回聊天即可使用"})}
    }
}
