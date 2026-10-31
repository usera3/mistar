package cn.qingliao.app

import android.app.*
import android.content.Intent
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Real native form with a captured sign-in callback; every request is intercepted. */
internal object CodeLoginTests {
    fun run(i:Instrumentation,layout:String):String {
        val folder=File(i.targetContext.getExternalFilesDir(null),"code-login-test").apply{mkdirs()}
        val oldHttp=Hub.http;val oldUpdates=AppUpdates.transport
        val requests=CopyOnWriteArrayList<Pair<String,JSONObject>>();val result=AtomicReference<JSONObject>()
        val blocked=AtomicBoolean();val fail=AtomicBoolean(true);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var a:MainActivity?=null;var screen:AuthScreen?=null;var checks=0
        fun ui(f:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())f()else{var failure:Throwable?=null;i.runOnMainSync{try{f()}catch(e:Throwable){failure=e}};failure?.let{throw it}}}
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        fun find(tag:String)=a?.let{walk(it.window.decorView).firstOrNull{v->v.tag==tag}}
        fun await(label:String,p:()->Boolean){val end=SystemClock.elapsedRealtime()+15000;while(true){var ok=false;ui{ok=p()};if(ok){i.waitForIdleSync();return};check(SystemClock.elapsedRealtime()<end){"Code login timeout: $label"};Thread.sleep(40)}}
        fun click(tag:String){await(tag){find(tag)?.isEnabled==true};ui{val v=find(tag)!!;v.requestRectangleOnScreen(Rect(0,0,v.width,v.height),true);v.performClick()};i.waitForIdleSync()}
        fun fill(tag:String,value:String){ui{(find(tag) as EditText).setText(value)}}
        fun count(path:String)=requests.count{it.first==path}
        fun text(tag:String)=(find(tag) as TextView).text.toString()
        fun snapshot(name:String){i.waitForIdleSync();Thread.sleep(300);val b=i.uiAutomation.takeScreenshot();File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        fun fresh(){ui{
            screen?.dispose();(field(a!!,"authScreen") as? AuthScreen)?.dispose()
            val next=AuthScreen(a!!,ThemeStore.current(a!!)){result.set(it)};screen=next
            a!!.javaClass.getDeclaredField("authScreen").apply{isAccessible=true}.set(a,next)
            (field(a!!,"root") as LinearLayout).apply{removeAllViews();addView(next.view,LinearLayout.LayoutParams(-1,-1))}
        };i.waitForIdleSync()}
        try {
            ui{Hub.token="";Hub.me=JSONObject();Hub.connected=false
                Hub.http=OkHttpClient.Builder().addInterceptor{c->
                    check(c.request().url.host in setOf("47.245.2.254","api.claudcode.xyz"))
                    val path=c.request().url.encodedPath;val buffer=Buffer();c.request().body?.writeTo(buffer);val raw=buffer.readUtf8();val payload=if(raw.isEmpty())JSONObject()else JSONObject(raw);requests.add(path to payload)
                    var status=200
                    val body=when(path){
                        "/api/auth/code"->JSONObject().put("verification_id","native-login-${requests.size}").put("retry_after",1)
                        "/api/login/code"->{
                            if(blocked.get()){entered.countDown();check(release.await(8,TimeUnit.SECONDS))}
                            if(fail.getAndSet(false)){status=400;JSONObject().put("error","验证码无效或已过期，请重新获取")}
                            else JSONObject().put("token","synthetic-code-login-token").put("user",JSONObject().put("id","88000001").put("nickname","合成登录用户"))
                        }
                        else->JSONObject().put("ok",true)
                    }
                    Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic code login").body(body.toString().toResponseBody()).build()
                }.build();AppUpdates.transport=Hub.http}
            a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity;fresh()
            val cfg=a!!.resources.configuration;check(if(layout=="phone")!CompactUi.enabled(a!!)&&cfg.screenWidthDp>=360&&cfg.screenHeightDp>=600 else CompactUi.enabled(a!!));checks++
            await("password mode laid out"){val v=find("auth.loginByCode");val b=Rect();v!=null&&v.width>0&&v.getGlobalVisibleRect(b)&&b.height()==v.height};checks++
            fill("auth.username","NATIVE@example.com");fill("auth.password","temporary-password");click("auth.loginByCode")
            check(find("auth.username")==null&&find("auth.password")==null&&text("auth.email")=="native@example.com");checks++
            click("auth.loginPassword");check(text("auth.password").isEmpty());click("auth.loginByCode");checks++
            fill("auth.email","not-email");click("auth.sendCode");check(count("/api/auth/code")==0);checks++
            fill("auth.email","NATIVE@example.com");click("auth.sendCode");await("code sent"){find("auth.sendCode")?.isEnabled==false&&text("auth.status").contains("邮箱匹配")}
            val sent=requests.last{it.first=="/api/auth/code"}.second;check(sent.getString("purpose")=="login"&&sent.getString("email")=="native@example.com"&&!sent.has("password")&&!sent.has("username"));checks++
            ui{find("auth.sendCode")!!.performClick()};check(count("/api/auth/code")==1);checks++
            fill("auth.code","123456");fill("auth.email","other@example.com");click("auth.submit");check(count("/api/login/code")==0);checks++
            fill("auth.email","native@example.com");fill("auth.code","12");click("auth.submit");check(count("/api/login/code")==0);checks++
            fill("auth.code","123456");ui{screen!!.reflow()};check(text("auth.code")=="123456"&&text("auth.email")=="native@example.com");checks++
            click("auth.submit");await("inline code error"){text("auth.status").contains("验证码无效")};check(result.get()==null);checks++
            click("auth.sendCode");await("replacement code sent"){count("/api/auth/code")==2&&text("auth.code").isEmpty()&&text("auth.status").contains("邮箱匹配")};checks++
            fill("auth.code","123456");snapshot("code-login");click("auth.submit");await("successful sign-in callback"){result.get()!=null}
            check(result.get().getJSONObject("user").getString("id")=="88000001");val login=requests.last{it.first=="/api/login/code"}.second;check(!login.has("password")&&!login.has("username")&&login.getString("email")=="native@example.com");checks++
            result.set(null);fresh();click("auth.loginByCode");fill("auth.email","native@example.com");click("auth.sendCode");await("new proof"){text("auth.status").contains("邮箱匹配")};fill("auth.code","123456")
            blocked.set(true);click("auth.submit");check(entered.await(4,TimeUnit.SECONDS));fresh();release.countDown();Thread.sleep(250);check(result.get()==null&&Hub.token.isEmpty()&&find("auth.password")!=null);checks++
            click("auth.loginByCode");ui{check(screen!!.back())};check(find("auth.password")!=null);checks++
            val preferences=i.targetContext.getSharedPreferences("session",android.content.Context.MODE_PRIVATE).all.toString();check(!preferences.contains("123456")&&!preferences.contains("synthetic-code-login-token"));checks++
            val sha=MessageDigest.getInstance("SHA-256").digest(File(i.targetContext.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
            File(folder,"result.json").writeText(JSONObject().put("passed",true).put("checks",checks).put("apkSha256",sha).put("api",Build.VERSION.SDK_INT).put("realRequests",0).put("staleAuthIgnored",true).put("purposeBound",true).put("noPasswordRequired",true).put("physicalDevices",false).toString(2))
            return "PASS native email code login, switching and stale responses: $checks checks"
        }catch(e:Throwable){runCatching{snapshot("failure")};File(folder,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally{release.countDown();ui{screen?.dispose();a?.finish();Hub.token="";Hub.me=JSONObject();Hub.http=oldHttp;AppUpdates.transport=oldUpdates}}
    }
}
