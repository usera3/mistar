package cn.qingliao.app

import android.app.Activity
import android.os.SystemClock
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject

/** One native flow, with in-memory drafts and page/session-bound network completions. */
internal class AuthScreen(private val activity:Activity,private val p:ThemePalette,private val signedIn:(JSONObject)->Unit) {
    private val u=FormUi(activity,p)
    private val openingSession=Hub.token
    val view=FrameLayout(activity)
    private var page="login"
    private var revision=0
    private var active=true
    private var busy=false
    private var username="";private var nickname="";private var password=""
    private var email="";private var code="";private var confirm=""
    private var verification="";private var verifiedEmail=""
    private var retryAt=0L
    private var codeButton:TextView?=null
    private lateinit var status:TextView
    private var fields=mutableListOf<FormUi.Field>()
    private var capture:(()->Unit)?=null
    private val timer=object:Runnable{override fun run(){if(valid()){updateCountdown();Hub.main.postDelayed(this,1000)}}}
    init{render();Hub.main.post(timer)}
    private fun valid()=active&&!activity.isFinishing&&!activity.isDestroyed&&Hub.token==openingSession
    fun reflow(){if(valid()&&!busy){capture?.invoke();render()}}
    fun dispose(){active=false;revision++;Hub.main.removeCallbacks(timer);password="";confirm="";code="";verification="";fields.forEach{it.edit.text.clear()};capture=null}
    fun back():Boolean{
        if(page=="login")return false
        if(busy)return true
        capture?.invoke()
        go(when(page){"register.email"->"register";"reset.password"->"reset";else->"login"});return true
    }
    private fun go(next:String){
        if(busy||!valid())return
        val resetFlow=page.startsWith("reset");val nextReset=next.startsWith("reset")
        if(resetFlow!=nextReset||next=="login"||next=="login.code"){
            verification="";verifiedEmail="";code="";confirm=""
            if(resetFlow)password=""
        }
        page=next;render()
    }
    private fun render(){
        revision++;fields.clear();codeButton=null
        val scroll=ScrollView(activity).apply{isFillViewport=true;isVerticalScrollBarEnabled=false;isSaveEnabled=false}
        val outer=u.col().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(u.dp(if(u.compact)14 else 24),u.dp(if(u.compact)6 else 16),u.dp(if(u.compact)14 else 24),u.dp(24))}
        val form=u.col()
        val width=activity.resources.configuration.screenWidthDp
        outer.addView(form,LinearLayout.LayoutParams(if(width>520)u.dp(440)else -1,-2))
        scroll.addView(outer);view.removeAllViews();view.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        val header=u.row()
        if(page!="login"&&page!="login.code")header.addView(u.icon("back","返回上一步"){back()}.apply{tag="auth.back"},u.lp(48,48))
        else header.addView(ImageView(activity).apply{setImageResource(R.drawable.ic_launcher);contentDescription="弥星标志";background=BrandUi.rounded(activity,p.surface,12);clipToOutline=true},u.lp(if(u.tiny)28 else if(u.compact)38 else 46,if(u.tiny)28 else if(u.compact)38 else 46))
        header.addView(u.text("MISTAR",11f,p.accent,true).apply{letterSpacing=.16f;setPadding(u.dp(10),0,0,0)},LinearLayout.LayoutParams(0,-2,1f))
        if(page=="login")header.addView(u.link("验证码登录","auth.loginByCode"){capture?.invoke();if(emailValid(username.trim()))email=username.trim().lowercase(java.util.Locale.ROOT);password="";go("login.code")}.apply{setPadding(u.dp(6),u.dp(6),u.dp(6),u.dp(6))},u.lp(-2,if(u.compact)40 else 48))
        if(page=="login.code")header.addView(u.link("密码登录","auth.loginPassword"){capture?.invoke();go("login")}.apply{setPadding(u.dp(6),u.dp(6),u.dp(6),u.dp(6))},u.lp(-2,if(u.compact)40 else 48))
        form.addView(header,u.lp());u.gap(form,if(u.tiny)6 else if(u.compact)12 else 32)
        val title=when(page){"login"->if(u.tiny)"欢迎回到弥星"else"相伴，从这里开始";"login.code"->"邮箱验证码登录";"register"->if(u.tiny)"创建弥星账号"else"认识一下，新朋友";"register.email"->"验证你的邮箱";"reset"->"找回密码";else->"设置新密码"}
        val subtitle=when(page){"login"->"登录弥星，继续和在意的人聊天。";"login.code"->"使用已绑定的邮箱，无需输入密码。";"register"->"先设置你的昵称、账号和密码。";"register.email"->"最后一步，用邮箱保护你的账号。";"reset"->"填写绑定邮箱即可找回，无需记住账号。";else->"重置后，所有设备都需要重新登录。"}
        if(!u.tiny||page!="login"){form.addView(u.text(title,if(u.tiny)18f else if(u.compact)23f else 28f,bold=true),u.lp());u.gap(form,8)}
        if(!u.tiny){form.addView(u.text(subtitle,13f,p.muted),u.lp());u.gap(form,if(u.compact)16 else 26)}
        if(page.startsWith("register")||page.startsWith("reset")){
            val second=page.contains('.')
            val steps=u.row()
            listOf(if(u.tiny)"1  账号"else if(page.startsWith("reset"))"1  验证身份"else"1  账号信息",if(u.tiny){if(page.startsWith("reset"))"2  密码"else"2  验证"}else if(page.startsWith("reset"))"2  新密码"else"2  邮箱验证").forEachIndexed{index,s->
                steps.addView(u.text(s,12f,if((index==1)==second)p.accent else p.muted,true).apply{setPadding(u.dp(10),u.dp(9),u.dp(6),u.dp(9));background=BrandUi.rounded(activity,if((index==1)==second)p.soft else p.surface,12)},LinearLayout.LayoutParams(0,-2,1f).apply{if(index==0)marginEnd=u.dp(8)})
            };form.addView(steps,u.lp());u.gap(form,if(u.tiny)12 else 18)
        }
        fun field(title:String,hint:String,key:String,value:String,secret:Boolean=false,max:Int=128,type:Int=InputType.TYPE_CLASS_TEXT):FormUi.Field{
            val f=u.Field(title,hint,key,secret,max,type);f.value(value);fields.add(f);form.addView(f.view,u.lp());return f
        }
        status=u.text("",13f,p.accent).apply{tag="auth.status";visibility=View.GONE;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        when(page){
            "login"->{
                val name=field("账号",if(u.tiny)"邮箱、账号或弥星号"else"账号、邮箱或 8 位弥星号","auth.username",username,max=254,type=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                val pass=field("密码","输入登录密码","auth.password",password,true)
                name.edit.setAutofillHints(View.AUTOFILL_HINT_USERNAME);pass.edit.setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
                capture={username=name.value();password=pass.value()}
                val forgot=u.link("忘记密码？","auth.forgot"){capture?.invoke();password="";go("reset")}.apply{gravity=Gravity.END}
                if(!u.tiny)form.addView(forgot,u.lp())
                form.addView(status,u.lp());u.gap(form,if(u.tiny)2 else 10)
                val submit={capture?.invoke();clearErrors();when{username.isBlank()->name.fail("请输入账号或弥星号");password.isBlank()->pass.fail("请输入密码");else->request("/api/login",JSONObject().put("username",username.trim()).put("password",password),"正在登录…"){signedIn(it)}};Unit}
                form.addView(u.button("登录弥星","auth.submit",click=submit),u.lp());pass.done(submit)
                if(u.tiny)form.addView(forgot,u.lp())
                u.gap(form,12);form.addView(u.link("还没有账号？  创建一个","auth.register"){capture?.invoke();password="";go("register")},u.lp())
            }
            "login.code"->{
                val mail=field("邮箱","注册或绑定时验证的邮箱","auth.email",email,max=254,type=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                mail.edit.setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS)
                val otp=field("验证码","6 位邮箱验证码","auth.code",code,max=6,type=InputType.TYPE_CLASS_NUMBER)
                otp.edit.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
                capture={email=mail.value().trim().lowercase(java.util.Locale.ROOT);code=otp.value()}
                codeButton=u.button("发送验证码","auth.sendCode",false){
                    capture?.invoke();clearErrors()
                    when{!emailValid(email)->mail.fail("请输入正确的邮箱地址");SystemClock.elapsedRealtime()<retryAt->Unit;else->{
                        val address=email
                        request("/api/auth/code",JSONObject().put("purpose","login").put("email",address),"正在发送验证码…"){
                            verification=it.getString("verification_id");verifiedEmail=address;code="";otp.edit.text.clear()
                            retryAt=SystemClock.elapsedRealtime()+it.optLong("retry_after",60).coerceIn(1,3600)*1000
                            message(if(u.tiny)"邮箱匹配时会发送验证码。"else"邮箱匹配时会发送验证码，请检查邮箱及垃圾邮件。");updateCountdown()
                        }
                    }}
                };form.addView(codeButton,u.lp());u.gap(form,if(u.tiny)4 else 12)
                form.addView(status,u.lp());u.gap(form,if(u.tiny)4 else 10)
                val submit={capture?.invoke();clearErrors();when{
                    !emailValid(email)->mail.fail("请输入正确的邮箱地址")
                    verification.isEmpty()||verifiedEmail!=email->mail.fail("请先为当前邮箱发送验证码")
                    !code.matches(Regex("[0-9]{6}"))->otp.fail("请输入 6 位验证码")
                    else->request("/api/login/code",JSONObject().put("email",email).put("verification_id",verification).put("code",code),"正在登录…"){signedIn(it)}
                };Unit}
                form.addView(u.button("验证并登录","auth.submit",click=submit),u.lp());otp.done(submit)
            }
            "register"->{
                val nick=field("昵称","你希望大家怎么称呼你","auth.nickname",nickname,max=20)
                val name=field("账号","4–24 位字母、数字或下划线","auth.username",username,max=24,type=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                val pass=field("密码","8–128 位，建议混合字母与数字","auth.password",password,true)
                name.edit.setAutofillHints("newUsername");pass.edit.setAutofillHints("newPassword")
                capture={nickname=nick.value();username=name.value();password=pass.value()}
                form.addView(status,u.lp());u.gap(form,4)
                val next={capture?.invoke();clearErrors();when{nickname.isBlank()->nick.fail("请输入昵称");!username.matches(Regex("[A-Za-z0-9_]{4,24}"))->name.fail("账号需为 4–24 位字母、数字或下划线");password.length !in 8..128->pass.fail("密码需为 8–128 位");else->go("register.email")};Unit}
                form.addView(u.button("下一步 · 验证邮箱","auth.next",click=next),u.lp());pass.done(next)
                u.gap(form,8);form.addView(u.link("已有账号？  返回登录","auth.login"){capture?.invoke();password="";go("login")},u.lp())
            }
            "register.email","reset"->{
                val reset=page=="reset"
                val mail=field("邮箱",if(reset)"注册或绑定时验证的邮箱"else"输入可接收验证码的邮箱","auth.email",email,max=254,type=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                mail.edit.setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS)
                val otp=field("验证码","6 位邮箱验证码","auth.code",code,max=6,type=InputType.TYPE_CLASS_NUMBER)
                otp.edit.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
                capture={email=mail.value().trim().lowercase(java.util.Locale.ROOT);code=otp.value()}
                codeButton=u.button("发送验证码","auth.sendCode",false){
                    capture?.invoke();clearErrors()
                    when{!emailValid(email)->mail.fail("请输入正确的邮箱地址");SystemClock.elapsedRealtime()<retryAt->Unit;else->{
                        val address=email
                        request("/api/auth/code",JSONObject().put("purpose",if(reset)"reset"else"register").put("email",address),"正在发送验证码…"){
                            verification=it.getString("verification_id");verifiedEmail=address
                            retryAt=SystemClock.elapsedRealtime()+it.optLong("retry_after",60).coerceIn(1,3600)*1000
                            otp.edit.text.clear();code="";message(if(reset)"信息匹配时会发送验证码，请检查邮箱及垃圾邮件。"else"验证码已发送，请检查邮箱及垃圾邮件。")
                            updateCountdown()
                        }
                    }}
                };form.addView(codeButton,u.lp());u.gap(form,12)
                form.addView(status,u.lp());u.gap(form,10)
                val next={capture?.invoke();clearErrors();when{
                    !emailValid(email)->mail.fail("请输入正确的邮箱地址")
                    verification.isEmpty()||verifiedEmail!=email->mail.fail("请先为当前邮箱发送验证码")
                    !code.matches(Regex("[0-9]{6}"))->otp.fail("请输入 6 位验证码")
                    reset->go("reset.password")
                    else->request("/api/register",JSONObject().put("username",username.trim()).put("nickname",nickname.trim()).put("password",password).put("email",email).put("verification_id",verification).put("code",code),"正在创建账号…"){signedIn(it)}
                };Unit}
                form.addView(u.button(if(reset)"下一步 · 设置密码"else"创建账号","auth.submit",click=next),u.lp());otp.done(next)
                if(!reset){u.gap(form,12);form.addView(u.text("邮箱用于验证和找回账号，请使用自己的邮箱。",12f,p.muted),u.lp())}
            }
            "reset.password"->{
                val pass=field("新密码","8–128 位新密码","auth.password",password,true)
                val again=field("确认密码","再次输入新密码","auth.confirm",confirm,true)
                pass.edit.setAutofillHints("newPassword");again.edit.setAutofillHints("newPassword")
                capture={password=pass.value();confirm=again.value()}
                form.addView(status,u.lp());u.gap(form,8)
                val submit={capture?.invoke();clearErrors();when{password.length !in 8..128->pass.fail("密码需为 8–128 位");password!=confirm->again.fail("两次输入的密码不一致");else->request("/api/password/reset",JSONObject().put("email",email).put("verification_id",verification).put("code",code).put("new_password",password),"正在重置密码…"){
                    username=email;password="";confirm="";go("login");message("密码已重置，请使用新密码登录。")
                }};Unit}
                form.addView(u.button("重置密码","auth.submit",click=submit),u.lp());again.done(submit)
            }
        }
        u.gap(form,if(u.compact)18 else 32)
        form.addView(u.text("弥星  ·  和在意的人，自在相伴",11f,p.muted).apply{gravity=Gravity.CENTER},u.lp())
        fields.zipWithNext().forEach{(current,next)->current.edit.nextFocusForwardId=next.edit.id}
        updateCountdown();Motion.reveal(form,y=5f,duration=180)
    }
    private fun emailValid(value:String)=value.length<=254&&android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()
    private fun clearErrors(){fields.forEach{it.clearError()};status.visibility=View.GONE}
    private fun message(value:String){status.text=value;status.visibility=View.VISIBLE}
    private fun enable(v:View,value:Boolean){if(v.isClickable||v is EditText){v.isEnabled=value;v.alpha=if(value)1f else .55f};if(v is ViewGroup)for(n in 0 until v.childCount)enable(v.getChildAt(n),value)}
    private fun updateCountdown(){
        val seconds=((retryAt-SystemClock.elapsedRealtime()+999)/1000).coerceAtLeast(0)
        codeButton?.apply{text=if(seconds>0)"${seconds}s 后重新发送"else if(retryAt>0)"重新发送验证码"else"发送验证码";isEnabled=!busy&&seconds==0L;alpha=if(isEnabled)1f else .55f}
    }
    private fun request(path:String,data:JSONObject,progress:String,done:(JSONObject)->Unit){
        if(busy||!valid())return
        busy=true;val expected=revision;message(progress);enable(view,false)
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken,0)
        Hub.job({check(valid());Hub.rawApi("POST",path,data,openingSession)}, {
            if(valid()&&revision==expected){busy=false;enable(view,true);updateCountdown();status.visibility=View.GONE;done(it)}
        },{if(valid()&&revision==expected){busy=false;enable(view,true);updateCountdown();message(it)}})
    }
}
