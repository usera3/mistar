package cn.qingliao.app

import android.app.*
import android.content.Intent
import android.os.Build
import android.view.*
import android.widget.TextView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.source
import okio.buffer
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic HTTPS intercepted in-process; fixture APK stays outside the shipped APK. */
object UpdateTests {
    fun run(i:Instrumentation,waitForInstall:Boolean=false):String {
        val folder=File(i.targetContext.getExternalFilesDir(null),"updates-test").apply{mkdirs()}
        val fixture=File(folder,"future.apk");check(fixture.isFile)
        val hash=MessageDigest.getInstance("SHA-256");fixture.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;hash.update(b,0,n)}}
        val good=AppRelease(28,"2.8.1",fixture.length(),AppUpdates.hex(hash.digest()),AppUpdates.RELEASES+"xingyu-2.8.1-28.apk",listOf("本地合成版本 · 更新提醒与下载安装","此测试不连接真实用户"),System.currentTimeMillis())
        var mode="good";val hits=AtomicInteger();val apkHits=AtomicInteger();var checks=0
        val oldTransport=AppUpdates.transport
        val prefs=i.targetContext.getSharedPreferences("app_updates",0);prefs.edit().clear().commit()
        fun main(block:()->Unit)=i.runOnMainSync(block)
        fun await(timeout:Long=30000,condition:()->Boolean){val until=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<until){var done=false;main{done=condition()};if(done)return;Thread.sleep(60)};error("Update test timeout $mode")}
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
        fun click(a:Activity,text:String){main{val v=views(a.window.decorView).filterIsInstance<TextView>().first{it.text.toString()==text};v.performClick()}}
        fun screen(name:String){i.waitForIdleSync();Thread.sleep(350);i.uiAutomation.takeScreenshot().also{b->File(folder,"$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        main{
            AppUpdates.init()
            AppUpdates.transport=OkHttpClient.Builder().addInterceptor{chain->
                val request=chain.request();check(request.header("Authorization")==null&&request.header("Cookie")==null)
                check(request.url.scheme=="https"&&request.url.host=="api.claudcode.xyz"&&request.url.encodedPath.startsWith("/xingyu/releases/"))
                val metadata=request.url.encodedPath.endsWith("latest.json")
                val body:ResponseBody=if(metadata){hits.incrementAndGet();val p=good.json();if(mode=="badurl")p.put("url","https://evil.invalid/private");if(mode=="badhash")p.put("sha256","0".repeat(64));p.toString().toResponseBody("application/json".toMediaType())}
                else {apkHits.incrementAndGet();object:ResponseBody(){override fun contentType()="application/vnd.android.package-archive".toMediaType();override fun contentLength()=if(mode=="oversize")good.size+1 else good.size
                    override fun source()=fixture.inputStream().source().buffer()}}
                if(mode=="slow"&&!metadata)Thread.sleep(1000)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(mode=="error")503 else 200).message("Synthetic updater fixture").body(body).build()
            }.build()
        }
        lateinit var a:Activity
        try{
            for((key,value) in listOf("version_code" to true,"version_code" to 26.5,"version_code" to 0,"size" to 134217729,"min_sdk" to 25,"schema" to true,"package" to "other.app","url" to "https://evil.invalid/a.apk","sha256" to "bad","certificate_sha256" to "0".repeat(64),"version" to "../bad","notes" to org.json.JSONArray().put("x\nprivate"))){
                val p=good.json().put(key,value);check(runCatching{AppRelease.parse(p)}.isFailure);checks++
            }
            check(AppRelease.parse(good.json())==good);checks++
            @Suppress("DEPRECATION")
            File(folder,"signature-diagnostic.json").writeText(org.json.JSONArray(listOf(64,134217728,134217792).map{flag->
                val info=i.targetContext.packageManager.getPackageArchiveInfo(fixture.absolutePath,flag)
                JSONObject().put("flag",flag).put("legacy",info?.signatures?.map{AppUpdates.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))})
                    .put("current",if(Build.VERSION.SDK_INT>=28)info?.signingInfo?.apkContentsSigners?.map{AppUpdates.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))}else null)
            }).toString())
            AppUpdates.verifyApk(fixture,good);checks++
            check(runCatching{AppUpdates.verifyApk(fixture,good.copy(code=29))}.isFailure);checks++
            val malformed=File(folder,"invalid.apk").apply{writeBytes(ByteArray(256))};check(runCatching{AppUpdates.verifyApk(malformed,good)}.isFailure);malformed.delete();checks++
            main{AppUpdates.check(manual=true)};await{!AppUpdates.checking};check(AppUpdates.available==good);checks++
            val before=hits.get();main{repeat(10){AppUpdates.check()}};Thread.sleep(200);check(hits.get()==before);checks++
            main{AppUpdates.setNotifications(true);AppUpdates.notifyAvailable()}
            val manager=i.targetContext.getSystemService(NotificationManager::class.java)
            check(manager.activeNotifications.any{it.id==109});main{AppUpdates.notifyAvailable()};checks++
            val open=Intent(i.targetContext,UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            a=i.startActivitySync(open);await{views(a.window.decorView).filterIsInstance<TextView>().any{it.text.toString()=="新版本 2.8.1"}}
            screen("available")
            click(a,"复制下载地址");checks++
            click(a,"下载更新");await(60000){!AppUpdates.downloading};check(AppUpdates.downloaded(good)?.isFile==true);checks++
            screen("downloaded")
            mode="badhash";main{AppUpdates.check(manual=true)};await{!AppUpdates.checking};val bad=AppUpdates.available!!
            main{AppUpdates.download(bad)};await(60000){!AppUpdates.downloading};check(AppUpdates.error.contains("完整性")&&AppUpdates.downloaded(bad)==null);checks++
            mode="oversize";main{AppUpdates.download(good)};await{!AppUpdates.downloading};check(AppUpdates.error.contains("大小不符"));checks++
            mode="slow";main{AppUpdates.download(good)};await{apkHits.get()>=4};main{AppUpdates.cancelDownload()};Thread.sleep(1500);check(!AppUpdates.downloading&&AppUpdates.downloaded(good)==null);checks++
            mode="badurl";main{AppUpdates.check(manual=true)};await{!AppUpdates.checking};check(AppUpdates.error.contains("校验失败"));checks++
            mode="error";main{AppUpdates.check(manual=true)};await{!AppUpdates.checking};check(AppUpdates.error.isNotBlank());checks++
            mode="good";main{AppUpdates.check(manual=true)};await{!AppUpdates.checking}
            main{AppUpdates.download(good)};await(60000){!AppUpdates.downloading};check(AppUpdates.downloaded(good)!=null)
            screen("ready-install")
            // Actually open the OS installer with a content URI; installation is completed by the owned-emulator driver.
            click(a,"安装更新");Thread.sleep(1000);screen("system-installer")
            val result=JSONObject().put("passed",true).put("api",Build.VERSION.SDK_INT).put("checks",checks).put("signedApkVerified",true).put("isolatedHttps",true).put("dedupedNotifications",true).put("realUserMessages",0).put("physicalDevices",false)
            File(folder,"result.json").writeText(result.toString(2))
            if(waitForInstall){
                fun nodes(n:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).flatMap{n.getChild(it)?.let{c->nodes(c)}?:emptyList()}
                val button=nodes(i.uiAutomation.rootInActiveWindow).first{it.isClickable&&it.text?.toString()?.uppercase() in listOf("INSTALL","UPDATE","安装","更新")}
                val rect=android.graphics.Rect();button.getBoundsInScreen(rect)
                File(folder,"installer-ready.json").writeText(JSONObject().put("x",rect.centerX()).put("y",rect.centerY()).put("observedLabel",button.text.toString()).toString())
                // The driver confirms the observed OS button; successful upgrade ends this target process.
                Thread.sleep(45000)
            }
            return "PASS updater native API${Build.VERSION.SDK_INT}, $checks checks, signed package download/identity/hash/bounds/cancel/notification/UI/installer"
        }catch(e:Throwable){File(folder,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally{main{AppUpdates.cancelDownload();AppUpdates.transport=oldTransport};/* Keep installer Activity/fixture until the driver completes its explicit install. */}
    }
}
