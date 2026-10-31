package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.os.*
import android.view.*
import android.widget.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Actual native form/navigation tests; every HTTP request is intercepted, with no real accounts. */
object AuthDeveloperTests {
    fun run(i:Instrumentation):String {
        val folder=File(i.targetContext.getExternalFilesDir(null),"auth-developer-test").apply{mkdirs()}
        val oldHttp=Hub.http;val oldUpdates=AppUpdates.transport
        val requests=CopyOnWriteArrayList<Pair<String,JSONObject>>()
        val initialFailure=AtomicBoolean(true);val block=AtomicBoolean(false)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var bots=JSONArray();var verifyFailure=true;var checks=0
        val ownerSession="synthetic-developer-session"
        val token="qlb_synthetic_native_test_only";val secret="synthetic_webhook_test_only"
        fun ui(f:()->Unit){var failure:Throwable?=null;i.runOnMainSync{try{f()}catch(e:Throwable){failure=e}};failure?.let{throw it}}
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun await(timeout:Long=12000,p:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(true){var ok=false;ui{ok=p()};if(ok){i.waitForIdleSync();return};check(SystemClock.elapsedRealtime()<end){"Native auth/developer condition timed out"};Thread.sleep(35)}}
        fun screen(name:String){i.waitForIdleSync();Thread.sleep(4500);val b=i.uiAutomation.takeScreenshot();File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?=if(intent.action==Intent.ACTION_VIEW)Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)else null}
        i.addMonitor(monitor)
        ui{
            Hub.token="";Hub.me=JSONObject();Hub.connected=false
            Hub.http=OkHttpClient.Builder().addInterceptor{chain->
                val r=chain.request();val path=r.url.encodedPath
                check(r.url.host in setOf("47.245.2.254","api.claudcode.xyz")){"Unexpected network origin"}
                val buf=Buffer();r.body?.writeTo(buf);val raw=buf.readUtf8();val payload=if(raw.isEmpty())JSONObject()else JSONObject(raw)
                requests.add(path to payload)
                if(path.startsWith("/api/developer/"))check(r.header("Authorization")=="Bearer $ownerSession")
                var status=200
                val result=when{
                    path=="/api/auth/code"->JSONObject().put("verification_id","synthetic-${payload.getString("purpose")}").put("retry_after",2)
                    path=="/api/register"->{status=409;JSONObject().put("error","这个账号已被使用")}
                    path=="/api/password/reset"->JSONObject().put("ok",true)
                    path=="/api/login"->{
                        if(block.get()){entered.countDown();check(release.await(10,TimeUnit.SECONDS));JSONObject().put("token","must-never-save").put("user",JSONObject().put("id","83990001"))}
                        else{status=401;JSONObject().put("error","账号或密码不正确")}
                    }
                    path=="/api/developer/bots"&&r.method=="GET"->{
                        if(initialFailure.getAndSet(false)){status=503;JSONObject().put("error","合成网络失败，请重试")}else JSONObject().put("bots",bots)
                    }
                    path=="/api/developer/bots"&&r.method=="POST"->{
                        val b=JSONObject().put("id","83990009").put("nickname",payload.getString("nickname")).put("bot_description",payload.getString("description")).put("bot_enabled",true).put("webhook","").put("webhook_verified",false).put("bot_calls_enabled",false).put("pending",0).put("failed",0).put("last_status",0)
                        bots.put(b);JSONObject().put("bot",b).put("token",token)
                    }
                    path.endsWith("/webhook")->{val b=bots.getJSONObject(0);b.put("webhook",payload.getString("url")).put("webhook_verified",false);JSONObject().put("bot",b).put("webhook_secret",secret)}
                    path.endsWith("/verify")->{if(verifyFailure){verifyFailure=false;status=409;JSONObject().put("error","回调验证失败，请确认接收程序已配置签名密钥")}else{bots.getJSONObject(0).put("webhook_verified",true).put("failed",2).put("last_status",200);JSONObject().put("ok",true)}}
                    path.endsWith("/retry")->{bots.getJSONObject(0).put("failed",0).put("pending",2);JSONObject().put("ok",true)}
                    path.endsWith("/rotate")->JSONObject().put("bot",bots.getJSONObject(0)).put("token",token+"_rotated")
                    path.startsWith("/api/developer/bots/")&&r.method=="PATCH"->{val b=bots.getJSONObject(0);if(payload.has("calls_enabled"))b.put("bot_calls_enabled",payload.getBoolean("calls_enabled"));if(payload.has("enabled"))b.put("bot_enabled",payload.getBoolean("enabled"));JSONObject().put("bot",b)}
                    else->JSONObject().put("ok",true)
                }
                Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic native forms").body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }.build();AppUpdates.transport=Hub.http
        }
        var activity:Activity=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun find(tag:String)=walk(activity.window.decorView).firstOrNull{it.tag==tag}
        fun click(tag:String){await{find(tag)?.isEnabled==true};ui{val v=find(tag)!!;v.requestRectangleOnScreen(android.graphics.Rect(0,0,v.width,v.height),true);v.performClick()};i.waitForIdleSync()}
        fun fill(tag:String,value:String){ui{(find(tag) as EditText).setText(value)}}
        fun text(tag:String)=(find(tag) as TextView).text.toString()
        fun count(path:String)=requests.count{it.first==path}
        fun idle(){await{val status=find("auth.status") as? TextView;status==null||!status.text.toString().startsWith("正在")}}
        fun developerIdle(){await{val f=activity.javaClass.getDeclaredField("busy").apply{isAccessible=true};f.getBoolean(activity).not()}}
        fun confirm(positive:Boolean){ui{val f=activity.javaClass.getDeclaredField("dialogs").apply{isAccessible=true};val d=(f.get(activity) as Set<*>).first() as AlertDialog;d.getButton(if(positive)AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE).performClick()};i.waitForIdleSync()}
        try {
            await{find("auth.submit")!=null}
            ui{for(tag in listOf("auth.username","auth.password","auth.submit")){val v=find(tag)!!;val r=android.graphics.Rect();check(v.getGlobalVisibleRect(r)&&r.height()>=v.height){"Initial login control clipped: $tag"}}};checks++
            screen("login")
            click("auth.submit");check(count("/api/login")==0&&text("auth.username.error").isNotEmpty());checks++
            fill("auth.username","native_test");fill("auth.password","synthetic-pass-123")
            val pass=find("auth.password") as EditText;check(pass.transformationMethod!=null)
            click("auth.password.visibility");check(pass.transformationMethod==null);click("auth.password.visibility");check(pass.transformationMethod!=null);checks++
            click("auth.submit");await{text("auth.status")=="账号或密码不正确"};check(text("auth.username")=="native_test");checks++
            click("auth.register");fill("auth.nickname","星光助手");fill("auth.username","bad!");fill("auth.password","short");click("auth.next");check(find("auth.email")==null);checks++
            fill("auth.username","native_test");fill("auth.password","synthetic-pass-123");check(find("auth.username.error")?.visibility==View.GONE);screen("register-profile");click("auth.next")
            fill("auth.email","not-email");click("auth.sendCode");check(count("/api/auth/code")==0);checks++
            fill("auth.email","native@example.com");click("auth.sendCode");idle();check(count("/api/auth/code")==1&&find("auth.sendCode")?.isEnabled==false);checks++
            fill("auth.code","123456");fill("auth.email","other@example.com");click("auth.submit");check(count("/api/register")==0);checks++
            fill("auth.email","native@example.com");click("auth.back");check(text("auth.nickname")=="星光助手"&&text("auth.password")=="synthetic-pass-123");click("auth.next");check(text("auth.email")=="native@example.com");checks++
            screen("register-email");click("auth.submit");await{text("auth.status")=="这个账号已被使用"};check(count("/api/register")==1);check(requests.last{it.first=="/api/register"}.second.getString("verification_id")=="synthetic-register");checks++
            click("auth.back");click("auth.login");check(text("auth.password").isEmpty());click("auth.forgot");check(find("auth.username")==null);checks++;fill("auth.email","NATIVE@example.com")
            await(5000){find("auth.sendCode")?.isEnabled==true};screen("forgot-email");click("auth.sendCode");idle();check(!requests.last{it.first=="/api/auth/code"}.second.has("username"));checks++
            fill("auth.code","123456");fill("auth.email","other@example.com");click("auth.submit");check(find("auth.confirm")==null);checks++
            fill("auth.email","native@example.com");click("auth.submit");fill("auth.password","new-synthetic-pass");fill("auth.confirm","mismatch");click("auth.submit");check(count("/api/password/reset")==0);checks++
            fill("auth.confirm","new-synthetic-pass");click("auth.submit");await{text("auth.status")=="密码已重置，请使用新密码登录。"};check(text("auth.password").isEmpty()&&text("auth.username")=="native@example.com");check(!requests.last{it.first=="/api/password/reset"}.second.has("username"));checks++
            // A successful old-screen response must not create a session after navigation/disposal.
            fill("auth.username","native_test");fill("auth.password","synthetic-pass-123");block.set(true);click("auth.submit");check(entered.await(4,TimeUnit.SECONDS))
            ui{activity.javaClass.getDeclaredMethod("showLogin",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(activity,false)};release.countDown();Thread.sleep(300);check(Hub.token.isEmpty());checks++
            ui{activity.finish();Hub.token=ownerSession;Hub.me=JSONObject().put("id","83990001").put("nickname","开发者测试")}
            activity=i.startActivitySync(Intent(i.targetContext,DeveloperActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await{find("developer.retryLoad")!=null};click("developer.retryLoad");developerIdle();check(find("developer.create")?.isEnabled==true);checks++
            ui{val v=find("developer.create")!!;val r=android.graphics.Rect();check(v.getGlobalVisibleRect(r)&&r.height()>=v.height){"Initial create control clipped"}};checks++
            // FLAG_SECURE is retained in production; only synthetic, non-secret screenshots clear it.
            check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
            ui{activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)};screen("developer-empty");ui{activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
            click("developer.create");click("developer.save");check(count("/api/developer/bots")==2);fill("developer.name","星光助手");fill("developer.description","消息、图片与语音，都能轻松接入。")
            click("developer.save");developerIdle();check(text("developer.secret")!=token);click("developer.reveal");check(text("developer.secret")==token);click("developer.copySecret")
            check(text("developer.secret")==token&&find("developer.secretDone")!=null);checks++
            click("developer.secretDone");fill("developer.url","http://localhost:9080/test");click("developer.saveWebhook");check(count("/api/developer/bots/83990009/webhook")==0);checks++
            fill("developer.url","https://example.com/webhook");click("developer.saveWebhook");developerIdle();click("developer.copyEnv")
            val manager=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            check(manager.primaryClip?.getItemAt(0)?.text.toString()=="QINGLIAO_WEBHOOK_SECRET=$secret");check(find("developer.secretDone")!=null);click("developer.secretDone");checks++
            click("developer.verify");developerIdle();check(text("developer.feedback").contains("验证失败"));click("developer.verify");developerIdle();await{find("developer.retry")!=null};checks++
            click("developer.retry");developerIdle();check(find("developer.retry")==null);checks++
            ui{activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)};screen("developer-detail");ui{activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
            click("developer.calls");confirm(false);check(!bots.getJSONObject(0).getBoolean("bot_calls_enabled"));click("developer.calls");confirm(true);developerIdle();check(bots.getJSONObject(0).getBoolean("bot_calls_enabled"));checks++
            val configured=count("/api/developer/bots/83990009/webhook");click("developer.webhook");click("developer.saveWebhook");developerIdle();check(count("/api/developer/bots/83990009/webhook")==configured);checks++
            click("developer.examples");check(find("developer.example.text")!=null);click("developer.example.text.copy");check(manager.primaryClip?.getItemAt(0)?.text.toString().contains("<API 凭证>"));check(!manager.primaryClip!!.getItemAt(0).text.toString().contains(token));checks++
            ui{activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)};screen("developer-guide");ui{activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
            val beforeDoc=requests.size;click("developer.docs.api");check(walk(activity.window.decorView).filterIsInstance<TextView>().any{it.text.toString().contains("API 根地址")});check(requests.size==beforeDoc);click("developer.back");checks++
            click("developer.back");click("developer.rotate");confirm(true);developerIdle();check(find("developer.secretDone")!=null);ui{Hub.token="different-owner";activity.finish()};Thread.sleep(100);check(activity.isFinishing);checks++
            val digest=MessageDigest.getInstance("SHA-256").digest(File(i.targetContext.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
            File(folder,"result.json").writeText(JSONObject().put("passed",true).put("api",Build.VERSION.SDK_INT).put("checks",checks).put("requestsIntercepted",requests.size).put("realRequests",0).put("apkSha256",digest).put("staleAuthIgnored",true).put("secretNotPersisted",true).put("physicalDevices",false).toString(2))
            return "PASS native auth, registration, reset and developer setup API${Build.VERSION.SDK_INT}: $checks checks"
        }catch(e:Throwable){File(folder,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally{release.countDown();i.removeMonitor(monitor);ui{activity.finish();Hub.service?.stopSelf();Outbox.stop();Hub.token="";Hub.me=JSONObject();Hub.http=oldHttp;AppUpdates.transport=oldUpdates}}
    }
}
