package cn.qingliao.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.*
import android.widget.*
import org.json.JSONObject

class RecoveryActivity:Activity() {
    private val session=Hub.token
    private val palette by lazy{ThemeStore.current(this)}
    private lateinit var form:LinearLayout
    private lateinit var status:TextView
    private lateinit var password:EditText
    private lateinit var code:EditText
    private lateinit var resend:TextView
    private var email=""
    private var verification=""
    private var busy=false
    private var destroyed=false
    private var cooldown:Runnable?=null
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun valid()=!destroyed&&!isFinishing&&Hub.token==session&&session.isNotEmpty()
    private fun label(value:String,size:Float=14f)=TextView(this).apply{text=value;textSize=size;setTextColor(palette.ink);setPadding(0,dp(10),0,dp(10))}
    private fun field(hint:String,secret:Boolean=false)=EditText(this).apply{
        this.hint=hint;textSize=15f;isSingleLine=true;setTextColor(palette.ink);setPadding(dp(14),dp(14),dp(14),dp(14))
        inputType=if(secret)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_NUMBER
        background=BrandUi.field(this@RecoveryActivity,palette);form.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
    }
    private fun button(title:String,click:()->Unit)=label(title,15f).apply{
        gravity=Gravity.CENTER;minimumHeight=dp(52);isFocusable=true;setTextColor(palette.onPrimary)
        background=BrandUi.primary(this@RecoveryActivity,palette)
        setOnClickListener{if(!busy&&valid())click()};form.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);if(!valid()){finish();return}
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor=palette.canvas;window.navigationBarColor=palette.canvas
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(palette.canvas)}
        root.setOnApplyWindowInsetsListener { v,i ->
            if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom)}
            else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i
        }
        root.addView(label("‹  返回",17f).apply{setPadding(dp(24),dp(16),dp(24),dp(16));setOnClickListener{finish()}})
        form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),0,dp(24),dp(28))}
        root.addView(ScrollView(this).apply{addView(form)},LinearLayout.LayoutParams(-1,0,1f));setContentView(root);root.requestApplyInsets()
        form.addView(label("重新开始加密聊天",24f).apply{setTypeface(typeface,Typeface.BOLD)})
        form.addView(label("仅在旧设备和聊天身份备份都丢失时使用。此操作会建立新的聊天身份，旧的加密记录仍无法恢复。所有设备会退出登录，好友关系需要重新添加并通过验证，黑名单会保留。"))
        val address=label("正在确认已验证邮箱…");form.addView(address)
        password=field("当前登录密码",true);resend=button("获取邮箱验证码"){requestCode()}
        code=field("6 位邮箱验证码").apply{filters=arrayOf(InputFilter.LengthFilter(6))}
        button("验证并重新开始"){
            if(email.isEmpty()||verification.isEmpty()||code.text.length!=6){status.text="请先获取并填写邮箱验证码";return@button}
            val proof=JSONObject().put("password",password.text.toString()).put("verification_id",verification).put("code",code.text.toString())
            AlertDialog.Builder(this).setTitle("重新开始加密聊天？").setMessage("旧的加密记录无法恢复，所有设备将退出登录，并需要重新添加好友。请确认已无法取得原设备或身份备份。")
                .setNegativeButton("取消",null).setPositiveButton("重新开始"){_,_->restart(proof)}.show()
        }
        status=label("").apply{accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};form.addView(status)
        Hub.job({Hub.rawApi("GET","/api/account",session=session)}, {
            if(valid()){email=it.optString("email");address.text=if(email.isEmpty())"请先在账号与安全中绑定并验证邮箱。" else "已验证邮箱：$email";resend.isEnabled=email.isNotEmpty()}
        },{if(valid())status.text=it})
    }
    private fun requestCode() {
        val captured=password.text.toString();if(captured.length !in 8..128){status.text="请输入当前登录密码";return}
        busy=true;status.text="正在发送…"
        Hub.job({check(Hub.token==session);Hub.rawApi("POST","/api/auth/code",JSONObject().put("purpose","identity_restart").put("email",email).put("password",captured),session)}, {
            busy=false;if(valid()){verification=it.getString("verification_id");status.text="请检查已验证邮箱的收件箱及垃圾邮件";resend.isEnabled=false;resend.text="60 秒后可重发"
                cooldown=Runnable{if(valid()){resend.isEnabled=true;resend.text="获取邮箱验证码"}}.also{Hub.main.postDelayed(it,60000)}
            }
        },{busy=false;if(valid())status.text=it})
    }
    private fun restart(proof:JSONObject) {
        if(!valid()||busy)return;busy=true;status.text="正在安全保存新聊天身份…"
        // Session revocation is part of success, so this completion explicitly handles signed-out state.
        Hub.io.execute {
            try{check(Hub.token==session);ChatCrypto.restartIdentity(proof)
                Hub.main.post{
                    if(destroyed)return@post
                    if(Hub.token==session)Hub.logout()
                    if(Hub.token.isEmpty()){startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));Toast.makeText(this,"请重新登录，然后重新添加好友",Toast.LENGTH_LONG).show()};finish()
                }
            }catch(e:Exception){Hub.main.post{busy=false;if(valid())status.text=e.message?:"连接未完成，请重试；新身份已安全保存在本机" else if(!destroyed)finish()}}
        }
    }
    override fun onDestroy(){destroyed=true;cooldown?.let{Hub.main.removeCallbacks(it)};super.onDestroy()}
}
