package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.os.*
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import java.io.File
import java.security.MessageDigest

/** Owned emulator + synthetic statuses: semantic color, self/peer UI and network classification. */
object PresenceUiTests {
    fun run(i:Instrumentation,variant:String):String {
        check(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk",true)||Build.HARDWARE in setOf("ranchu","goldfish"))
        val folder=File(i.targetContext.getExternalFilesDir(null),"presence-ui-test").apply{mkdirs()};val oldHttp=Hub.http;val oldUpdates=AppUpdates.transport
        val user=JSONObject().put("id","85000001").put("username","qa_presence").put("nickname","合成星光旅人").put("admin_password_change_required",true)
        fun ui(action:()->Unit){var failure:Throwable?=null;i.runOnMainSync{try{action()}catch(e:Throwable){failure=e}};failure?.let{throw it}}
        fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        fun set(o:Any,n:String,v:Any?){o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
        fun invoke(o:Any,n:String,vararg values:Any?){ui{o.javaClass.declaredMethods.first{it.name==n&&it.parameterCount==values.size}.apply{isAccessible=true}.invoke(o,*values)}}
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun dot(view:TextView):Int {val text=view.text as? Spanned?:error("Presence indicator must have semantic span");return text.getSpans(0,1,ForegroundColorSpan::class.java).first().foregroundColor}
        ui{Hub.token="synthetic-presence-ui-session";Hub.me=JSONObject(user.toString());Hub.connected=false;Hub.http=OkHttpClient.Builder().addInterceptor{chain->
            val r=chain.request();check(r.url.host in setOf("47.245.2.254","api.claudcode.xyz"));val value=when(r.url.encodedPath){"/api/contacts"->JSONObject().put("contacts",JSONArray());"/api/conversations"->JSONObject().put("conversations",JSONArray());else->JSONObject().put("ok",true)}
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic presence UI").body(value.toString().toResponseBody("application/json".toMediaType())).build()
        }.build();AppUpdates.transport=Hub.http}
        val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity;var checks=0
        fun capture(name:String){i.waitForIdleSync();Thread.sleep(180);i.uiAutomation.takeScreenshot().let{b->File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        try{
            ui{Hub.me.remove("admin_password_change_required");NetworkPresence.refresh()};val actual=NetworkPresence.kind;check(actual in setOf("wifi","ethernet","mobile","4g","3g","5g","2g","vpn","other",""));checks++
            for((base,override,expected) in listOf(Triple(13,0,"4g"),Triple(20,0,"5g"),Triple(13,3,"5g"),Triple(13,5,"5g"),Triple(3,0,"3g"),Triple(1,0,"2g"),Triple(0,0,"mobile"))){check(NetworkPresence.cellular(base,override)==expected);checks++}
            val palettes=listOf("#7660D5","#334155","#16A34A","#2563EB","#E11D48","#D97706","#0D9488","#7C3AED","#C026D3","#475569","#000000","#FFFFFF")
            for(color in palettes){ui{set(a,"palette",ThemePalette(android.graphics.Color.parseColor(color)));Hub.connected=true};invoke(a,"showHome",false);i.waitForIdleSync();val label=field(a,"connectionLabel") as TextView;check(label.text.contains("在线")&&dot(label)==PresenceText.ONLINE_COLOR);checks++}
            ui{set(NetworkPresence,"kind","wifi");Hub.connected=true};invoke(a,"updateStatusLabels");check((field(a,"connectionLabel") as TextView).text.contains("Wi-Fi"));capture("self-wifi-green");checks++
            for(kind in listOf("4g","5g","mobile","")){ui{set(NetworkPresence,"kind",kind)};invoke(a,"updateStatusLabels");val label=field(a,"connectionLabel") as TextView;check(dot(label)==PresenceText.ONLINE_COLOR);if(kind.isNotEmpty())check(label.text.contains(NetworkPresence.label(kind)));checks++}
            val friend=JSONObject().put("id","85000002").put("nickname","合成好友").put("friend",true).put("state","accepted").put("online",true).put("network","5g")
            ui{set(a,"peer",friend)};invoke(a,"showChat");i.waitForIdleSync();var label=field(a,"connectionLabel") as TextView;check(label.text.contains("5G")&&dot(label)==PresenceText.ONLINE_COLOR);capture("friend-5g-green");checks++
            friend.put("network","wifi");invoke(a,"updateStatusLabels");label=field(a,"connectionLabel") as TextView;check(label.text.contains("Wi-Fi"));checks++
            friend.put("online",false).put("last_seen",System.currentTimeMillis()-120000);invoke(a,"updateStatusLabels");label=field(a,"connectionLabel") as TextView;check(label.text.contains("分钟前")&&!label.text.contains("Wi-Fi")&&dot(label)!=PresenceText.ONLINE_COLOR);capture("friend-offline-gray");checks++
            ui{Hub.connected=false};invoke(a,"showHome",false);i.waitForIdleSync();label=field(a,"connectionLabel") as TextView;check(!label.text.contains("在线")&&dot(label)!=PresenceText.ONLINE_COLOR);checks++
            val packet=NetworkPresence.packet();check(packet.length()==2&&packet.has("event")&&packet.has("network")&&!packet.toString().contains("SSID"));checks++
            ui{set(NetworkPresence,"kind",actual)}
            val digest=MessageDigest.getInstance("SHA-256").digest(File(i.targetContext.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
            File(folder,"result.json").writeText(JSONObject().put("passed",true).put("checks",checks).put("api",Build.VERSION.SDK_INT).put("apkSha256",digest).put("semanticGreenAcrossThemes",12).put("selfNetwork",true).put("peerNetwork",true).put("offlineGray",true).put("nrNsaMappedTo5g",true).put("unknownNotInvented",true).put("actualTransport",actual).put("privateNetworkFieldsAbsent",true).put("realRequests",0).put("physicalDevices",false).toString(2))
            return "PASS semantic online color and own/friend coarse network: $checks checks"
        }catch(e:Throwable){runCatching{capture("failure")};File(folder,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally{ui{a.finish();Hub.service?.stopSelf();Outbox.stop();Hub.token="";Hub.me=JSONObject();Hub.connected=false;Hub.http=oldHttp;AppUpdates.transport=oldUpdates}}
    }
}
