package cn.qingliao.app

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import org.json.*
import java.io.File

/** Can run against the previous signed APK to reproduce the main-resource regression. */
object MapCompatibilityTests {
    fun run(i:Instrumentation,baseline:Boolean):String {
        check(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk",true)||Build.HARDWARE in setOf("ranchu","goldfish"))
        val folder=File(i.targetContext.getExternalFilesDir(null),if(baseline)"map-baseline"else"map-compat").apply{mkdirs()}
        val original=MapTiles.client;var a:Activity?=null
        fun ui(action:()->Unit){var failure:Throwable?=null;i.runOnMainSync{try{action()}catch(e:Throwable){failure=e}};failure?.let{throw it}}
        try{
            ui{Hub.token="synthetic-map-compat";Hub.me=JSONObject().put("id","89200001");MapTiles.client=MapTileFixture.client()}
            val point=JSONObject().put("v",1).put("lat",35.6895).put("lon",139.6917).put("accuracy",12).put("at",System.currentTimeMillis()).put("approximate",false).put("label","合成兼容性位置")
            a=i.startActivitySync(Intent(i.targetContext,LocationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("peer","89200002").putExtra("location",point.toString()))
            val map=a!!.javaClass.getDeclaredField("map").apply{isAccessible=true}.get(a) as LocationMapView
            var value=JSONObject();var url="";val end=SystemClock.elapsedRealtime()+20000
            do{
                val latch=java.util.concurrent.CountDownLatch(1)
                ui{url=map.web.url.orEmpty();map.web.evaluateJavascript("JSON.stringify(window.mapState||{})"){raw->value=runCatching{JSONObject(JSONTokener(raw).nextValue() as String)}.getOrDefault(JSONObject());latch.countDown()}}
                latch.await(2,java.util.concurrent.TimeUnit.SECONDS);if(value.optInt("tilesLoaded")>0&&value.optDouble("lat")==35.6895)break;Thread.sleep(100)
            }while(SystemClock.elapsedRealtime()<end)
            i.uiAutomation.takeScreenshot().let{image->File(folder,"screen.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
            val result=JSONObject().put("baseline",baseline).put("api",Build.VERSION.SDK_INT).put("mainUrl",url).put("state",value).put("rendered",value.optInt("tilesLoaded")>0).put("webview",android.webkit.WebView.getCurrentWebViewPackage()?.versionName)
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(File(i.targetContext.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
            result.put("passed",baseline||result.getBoolean("rendered")&&value.optDouble("lat")==35.6895&&url=="https://appassets.androidplatform.net/assets/location-map/index.html").put("apkSha256",digest)
            File(folder,"result.json").writeText(result.toString(2))
            if(!baseline)check(result.getBoolean("rendered")&&value.optDouble("lat")==35.6895&&url=="https://appassets.androidplatform.net/assets/location-map/index.html"){result.toString()}
            return "PASS map compatibility probe: $result"
        }finally{ui{a?.finish();Hub.token="";Hub.me=JSONObject();MapTiles.client.dispatcher.cancelAll();MapTiles.client=original}}
    }
}
