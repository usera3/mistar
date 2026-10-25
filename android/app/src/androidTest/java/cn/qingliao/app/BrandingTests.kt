package cn.qingliao.app

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.File

/** Exact signed old->new APK upgrade, synthetic account and data; no remote requests. */
object BrandingTests {
    fun run(i:Instrumentation,seed:Boolean):String {
        val context=i.targetContext
        val folder=File(context.getExternalFilesDir(null),"mistar-branding").apply{mkdirs()}
        val state=File(folder,"seed.json")
        val owner="83990001";val peer="83990002";val session="synthetic-branding-upgrade-session"
        val pub=if(seed)JSONObject()else JSONObject(state.readText()).getJSONObject("identity")
        val client=OkHttpClient.Builder().addInterceptor{chain->
            val request=chain.request();val path=request.url.encodedPath
            val body=when {
                path.startsWith("/api/crypto/identity/")->pub
                path=="/api/crypto/identity"->{val buffer=Buffer();request.body!!.writeTo(buffer);val p=JSONObject(buffer.readUtf8());for(k in p.keys())pub.put(k,p.get(k));JSONObject().put("ok",true)}
                path.endsWith("/latest.json")->JSONObject().put("release",JSONObject.NULL)
                else->JSONObject().put("ok",true)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic local branding upgrade").body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        i.runOnMainSync{Hub.http=client;AppUpdates.transport=client}
        try {
            if(seed){
                check(context.packageManager.getPackageInfo(context.packageName,0).versionName=="2.9.0")
                // This flag makes startService return before creating a socket; not a real administrator.
                val user=JSONObject().put("id",owner).put("nickname","合成升级测试").put("username","synthetic_upgrade").put("admin_password_change_required",true)
                i.runOnMainSync{Hub.saveSession(JSONObject().put("token",session).put("user",user))}
                val identity=ChatCrypto.ready()
                LocalData.put(owner,JSONObject().put("id","branding-message").put("sender",peer).put("recipient",owner).put("type","text").put("text","Synthetic encrypted chat survives rename").put("created",System.currentTimeMillis()-1000))
                check(ThemeStore.save(context,0xffdb2777.toInt()))
                check(context.getSharedPreferences("session",Context.MODE_PRIVATE).edit().putBoolean("branding_synthetic",true).commit())
                File(context.filesDir,"branding-owned-marker").writeText("Synthetic file survives rename")
                state.writeText(JSONObject().put("uid",android.os.Process.myUid()).put("identity",identity).toString())
                check(Hub.service==null)
                return "PASS old signed code28 seeded real encrypted session, Tink identity, encrypted cache and custom theme; no real account/network"
            }
            val before=JSONObject(state.readText())
            val info=context.packageManager.getPackageInfo(context.packageName,0)
            check(info.versionName=="2.9.1")
            @Suppress("DEPRECATION") val code=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
            check(code==29L&&android.os.Process.myUid()==before.getInt("uid"))
            check(Hub.token==session&&Hub.me.getString("id")==owner&&Hub.service==null)
            check(ChatCrypto.ready().getString("key_id")==before.getJSONObject("identity").getString("key_id"))
            val messages=LocalData.messages(owner,peer)
            check(messages.length()==1&&messages.getJSONObject(0).getString("text")=="Synthetic encrypted chat survives rename")
            check(ThemeStore.current(context).primary==0xffdb2777.toInt())
            check(File(context.filesDir,"branding-owned-marker").readText()=="Synthetic file survives rename")
            check(context.applicationInfo.loadLabel(context.packageManager).toString()=="弥星")
            // Only the synthetic session is removed for a clean native login screenshot.
            check(context.getSharedPreferences("session",Context.MODE_PRIVATE).edit().clear().commit())
            check(ThemeStore.save(context,ThemeStore.DEFAULT))
            i.runOnMainSync{Hub.token="";Hub.me=JSONObject();ChatCrypto.clear()}
            val activity=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            i.waitForIdleSync();Thread.sleep(500)
            fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
            i.runOnMainSync{
                val texts=views(activity.window.decorView).filterIsInstance<TextView>().map{it.text.toString()}
                check(texts.any{it=="弥星"}&&texts.any{it=="登录弥星"}&&texts.none{it.contains("星屿")})
            }
            val bitmap=i.uiAutomation.takeScreenshot()
            File(folder,"login.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val result=JSONObject().put("passed",true).put("name","弥星").put("latinName","Mistar").put("api",Build.VERSION.SDK_INT).put("versionBefore",28).put("versionAfter",29).put("sameUid",true).put("encryptedSessionRetained",true).put("tinkIdentityRetained",true).put("encryptedChatCacheRetained",true).put("customThemeRetained",true).put("appFileRetained",true).put("launcherAndLoginRenamed",true).put("realUserData",false).put("physicalDevice",false)
            File(folder,"result.json").writeText(result.toString(2))
            i.runOnMainSync{activity.finish()}
            return "PASS signed 28->29 upgrade, UID/session/Tink identity/encrypted chat/theme/files retained; native 弥星 launcher/login"
        }catch(e:Throwable){File(folder,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally{client.dispatcher.cancelAll();client.connectionPool.evictAll()}
    }
}
