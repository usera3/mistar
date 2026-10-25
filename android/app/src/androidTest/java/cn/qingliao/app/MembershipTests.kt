package cn.qingliao.app

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import android.view.*
import android.widget.*
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.*
import java.io.File
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Native checkout/QR/state/owner tests, with all HTTP intercepted; no payment page is opened. */
object MembershipTests {
    fun run(i:Instrumentation):String {
        val folder=File(i.targetContext.getExternalFilesDir(null),"membership-test").apply{mkdirs()}
        val session="synthetic-membership-session";val owner="83990001"
        val oldHttp=Hub.http;val oldUpdates=AppUpdates.transport
        val records=linkedMapOf<String,JSONObject>();val creates=mutableListOf<JSONObject>();var paid=false
        val launchIntents=mutableListOf<String>()
        val monitor=object:Instrumentation.ActivityMonitor(){
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                if(intent.action==Intent.ACTION_VIEW){launchIntents.add(intent.dataString?:"");return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)}
                return null
            }
        }
        i.addMonitor(monitor)
        val block=AtomicBoolean(false);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fun membership()=JSONObject().put("active",paid).put("expires_at",if(paid)System.currentTimeMillis()+30*86400000L else 0).put("server_time",System.currentTimeMillis()).put("storage_bytes",(if(paid)500L else 200L)*1024*1024).put("upload_daily_bytes",(if(paid)256L else 32L)*1024*1024)
        val plans=JSONArray().put(JSONObject().put("id","month").put("name","月卡").put("cents",990).put("days",30)).put(JSONObject().put("id","quarter").put("name","季卡").put("cents",2500).put("days",90)).put(JSONObject().put("id","year").put("name","年卡").put("cents",8800).put("days",365))
        fun ui(f:()->Unit)=i.runOnMainSync(f)
        fun field(o:Any,name:String)=o.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(o)
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun await(timeout:Long=20000,p:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(true){var ok=false;ui{ok=p()};if(ok){i.waitForIdleSync();return};check(SystemClock.elapsedRealtime()<end){"Native membership condition timed out"};Thread.sleep(35)}}
        fun invoke(a:Any,name:String){ui{a.javaClass.declaredMethods.first{it.name==name&&it.parameterCount==0}.apply{isAccessible=true}.invoke(a)}}
        fun screen(name:String){i.waitForIdleSync();Thread.sleep(400);val image=i.uiAutomation.takeScreenshot();File(folder,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
        ui{
            Hub.token=session;Hub.me=JSONObject().put("id",owner).put("nickname","星光旅人").put("username","synthetic_member");Hub.connected=false
            Hub.http=OkHttpClient.Builder().addInterceptor{chain->
                val r=chain.request();check(r.url.host in setOf("47.245.2.254","api.claudcode.xyz"))
                if(block.get()&&r.url.encodedPath=="/api/membership"){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
                val path=r.url.encodedPath
                if(path.startsWith("/api/")&&!path.endsWith("app-release"))check(r.header("Authorization")=="Bearer $session")
                val result=when{
                    path=="/api/membership"->JSONObject().put("enabled",true).put("plans",plans).put("membership",membership()).put("auto_renew",false)
                    path=="/api/payments/orders"&&r.method=="POST"->{
                        val buffer=Buffer();r.body!!.writeTo(buffer);val body=JSONObject(buffer.readUtf8());check(body.keys().asSequence().toSet()==setOf("plan_id","client_id"));UUID.fromString(body.getString("client_id"))
                        val id="ms_"+(creates.size+1).toString(16).padStart(32,'a');creates.add(body)
                        val o=JSONObject().put("id",id).put("plan",body.getString("plan_id")).put("plan_name","月卡").put("cents",990).put("days",30).put("state","pending").put("created",System.currentTimeMillis()).put("pay_url",MemberUi.GATEWAY+"/pay/synthetic_"+id).put("paid",0).put("granted_until",0)
                        records[id]=o;JSONObject().put("order",o).put("membership",membership())
                    }
                    path=="/api/payments/orders"->JSONObject().put("orders",JSONArray(records.values.toList())).put("membership",membership())
                    path.startsWith("/api/payments/orders/")->{
                        val id=path.removePrefix("/api/payments/orders/").substringBefore('/');val o=records[id]!!
                        if(paid){o.put("state","paid").put("paid",System.currentTimeMillis()).put("granted_until",System.currentTimeMillis()+30*86400000L)}
                        JSONObject().put("order",o).put("membership",membership())
                    }
                    else->JSONObject().put("ok",true)
                }
                Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("Local synthetic membership API").body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }.build();AppUpdates.transport=Hub.http
        }
        val a=i.startActivitySync(Intent(i.targetContext,MembershipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MembershipActivity
        var checks=0
        fun texts()=walk(a.window.decorView).filterIsInstance<TextView>()
        fun click(text:String){await{texts().any{it.text.toString()==text&&it.isEnabled}};ui{val v=texts().first{it.text.toString()==text&&it.isEnabled};v.requestRectangleOnScreen(android.graphics.Rect(0,0,v.width,v.height),true);v.performClick()};i.waitForIdleSync()}
        try {
            await{texts().any{it.text.toString()=="¥9.90"}&&field(a,"busy")==false};checks++
            check(texts().any{it.text.toString().contains("非自动续费")});checks++
            screen("plans")
            check(!MemberUi.checkout("https://attacker.example/pay/abcdefghijklmnop"));check(!MemberUi.checkout("javascript:alert(1)"));check(!MemberUi.checkout("https://pay.claudcode.xyz@attacker.example/pay/abcdefghijklmnop"));check(!MemberUi.checkout("https://pay.claudcode.xyz/pay/abcdefghijklmnop?token=bad"));checks++
            click("¥9.90  开通月卡")
            await{records.size==1&&field(a,"busy")==false&&field(a,"qr")!=null};checks++
            check(creates.size==1&&!MemberUi.active(Hub.me));checks++
            val bitmap=field(a,"qr") as Bitmap;val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels)))).text
            check(decoded==records.values.first().getString("pay_url")&&!decoded.contains(session));checks++
            ui{val q=walk(a.window.decorView).filterIsInstance<ImageView>().first{it.contentDescription=="用手机扫码打开本订单付款页"};q.requestRectangleOnScreen(android.graphics.Rect(0,0,q.width,q.height),true)}
            screen("checkout-qr")
            click("打开支付宝付款页");check(launchIntents==listOf(decoded));checks++
            click("查询付款结果");await{field(a,"busy")==false};check(!MemberUi.active(Hub.me)&&creates.size==1);checks++
            paid=true
            ui{Hub.javaClass.getDeclaredMethod("handle",JSONObject::class.java).apply{isAccessible=true}.invoke(Hub,JSONObject().put("event","membership.updated").put("membership",membership()).put("order_id",records.keys.first()))}
            await{field(a,"busy")==false&&texts().any{it.text.toString().contains("本单续期至")}};check(MemberUi.active(Hub.me));checks++
            screen("paid")
            val firstClient=creates.first().getString("client_id")
            paid=false;click("¥9.90  续费月卡");await{creates.size==2&&field(a,"busy")==false};check(creates.last().getString("client_id")!=firstClient);checks++
            // Begin a blocked old-owner load, then switch accounts before its response.
            block.set(true);invoke(a,"load");check(entered.await(5,TimeUnit.SECONDS))
            ui{Hub.token="synthetic-new-owner-session";Hub.me=JSONObject().put("id","83990002");a.finish()};release.countDown();Thread.sleep(600)
            check(Hub.me.optString("id")=="83990002"&&!Hub.me.has("membership"));check(MembershipEvents.listeners.isEmpty());checks++
            val digest=MessageDigest.getInstance("SHA-256").digest(File(i.targetContext.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
            File(folder,"result.json").writeText(JSONObject().put("passed",true).put("api",Build.VERSION.SDK_INT).put("checks",checks).put("apkSha256",digest).put("qrRoundtrip",true).put("serverConfirmationRequired",true).put("freshRenewalClientId",true).put("accountSwitchGuard",true).put("noChatListenerReplacement",true).put("externalPaymentPagesOpened",0).put("realPaymentRequests",0).put("physicalDevices",false).toString(2))
            return "PASS native membership purchase, QR, server-paid push, renewal and account isolation API${Build.VERSION.SDK_INT}, $checks checks"
        }catch(e:Throwable){File(folder,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally{i.removeMonitor(monitor);release.countDown();ui{a.finish();Hub.service?.stopSelf();Outbox.stop();Hub.token="";Hub.me=JSONObject();Hub.http=oldHttp;AppUpdates.transport=oldUpdates};MembershipEvents.listeners.clear()}
    }
}
