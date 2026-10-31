package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import java.io.File
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic native navigation, theme contrast and actual compact layout checks. No real accounts. */
internal object VisualTests {
    fun run(i:Instrumentation,layout:String):String {
        val folder=File(i.targetContext.getExternalFilesDir(null),"visual-test").apply{mkdirs()}
        val http=Hub.http;val updates=AppUpdates.transport;val original=ThemeStore.current(i.targetContext).primary
        val network=AtomicInteger();var checks=0;var a:MainActivity?=null
        val owner="87000001";val session="synthetic-visual-session";val now=System.currentTimeMillis()
        fun user(id:String,name:String)=JSONObject().put("id",id).put("nickname",name).put("username","preview_$id").put("state","accepted").put("friend",true).put("online",true)
        val friends=JSONArray();val chats=JSONArray()
        val names=listOf("小桃","山海","晚风","林间来信","旅行搭子","周末的计划")
        val previews=listOf("周末去看星星，记得带相机。","照片已经收到了，很喜欢。","晚点把地址发给你。","今天也要好好吃饭。","下次一起去海边吧。","好呀，那就这样说定了。")
        for(n in names.indices){val friend=user("8700000${n+2}",names[n]);friends.put(friend);chats.put(JSONObject().put("user",friend).put("unread",if(n==0)5 else 0).put("last",JSONObject().put("type","text").put("text",previews[n]).put("created",now-n*3600000)))}
        fun ui(f:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())f()else i.runOnMainSync(f)}
        fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        fun set(o:Any,n:String,v:Any?){o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
        fun invoke(o:Any,n:String,vararg args:Any?){ui{o.javaClass.declaredMethods.first{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(o,*args)}}
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun find(tag:String)=a?.let{walk(it.window.decorView).firstOrNull{v->v.tag==tag}}
        fun await(label:String,p:()->Boolean){val end=SystemClock.elapsedRealtime()+15000;while(true){var ready=false;ui{ready=p()};if(ready){i.waitForIdleSync();return};check(SystemClock.elapsedRealtime()<end){"Visual timeout: $label"};Thread.sleep(40)}}
        fun screen(name:String){i.waitForIdleSync();Thread.sleep(350);val b=i.uiAutomation.takeScreenshot();File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        try {
            for(color in (ThemeStore.presets.map{it.second}+listOf(Color.WHITE,Color.BLACK))){
                val p=ThemePalette(color)
                check(listOf(p.surface,p.canvas,p.soft,p.heroStart).all{ThemePalette.contrast(p.accent,it)>=4.49})
                check(ThemePalette.contrast(p.ink,p.canvas)>=7&&ThemePalette.contrast(p.muted,p.canvas)>=4.5){"Text contrast for ${ThemeStore.hex(color)}: ${ThemePalette.contrast(p.muted,p.canvas)}"}
                check(ThemePalette.contrast(p.primary,p.onPrimary)>=4.5&&ThemePalette.contrast(p.primaryEnd,p.onPrimary)>=4.5);checks++
            }
            ui {
                ThemeStore.save(i.targetContext,ThemeStore.DEFAULT);Hub.token=session;Hub.me=user(owner,"星光旅人").put("admin_password_change_required",true);Hub.connected=false;ConversationState.clearMemory()
                Hub.http=OkHttpClient.Builder().addInterceptor{c->
                    check(c.request().url.host in setOf("47.245.2.254","api.claudcode.xyz"))
                    val path=c.request().url.encodedPath
                    val body=when{path=="/api/contacts"->JSONObject().put("contacts",friends);path=="/api/conversations"->JSONObject().put("conversations",chats);path.startsWith("/api/messages/")->JSONObject().put("messages",JSONArray()).put("has_more",false);else->JSONObject().put("ok",true)}
                    Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic visual fixture").body(body.toString().toResponseBody()).build()
                }.addNetworkInterceptor{c->network.incrementAndGet();error("Real network forbidden: ${c.request().url.host}")}.build();AppUpdates.transport=Hub.http
            }
            LocalData.homePart(owner,"contacts",friends);LocalData.homePart(owner,"conversations",chats)
            ConversationState.flag(owner,session,"87000003","pinned",true);ConversationState.draft(owner,session,"87000003","海边的计划，写到一半也不会丢。",null,4,4)
            a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity=a!!
            ui{Hub.me.remove("admin_password_change_required");Hub.connected=true;set(activity,"contacts",friends);set(activity,"conversations",chats);invoke(activity,"showHome",false)}
            await("home navigation"){find("home.navigation")!=null&&find("conversation:87000002")!=null}
            val cfg=activity.resources.configuration
            check(if(layout=="phone")!CompactUi.enabled(activity)&&cfg.screenWidthDp>=360&&cfg.screenHeightDp>=600 else CompactUi.enabled(activity));checks++
            val nav=find("home.navigation")!!
            ui{val b=Rect();check(nav.getGlobalVisibleRect(b)&&b.width()==nav.width&&b.height()==nav.height);check(walk(nav).filter{it.tag?.toString()?.startsWith("home.nav.")==true}.count{it.isSelected}==1)};checks++
            ui{val badge=(nav as HomeNavigation).badge;val b=Rect();check(badge.getGlobalVisibleRect(b)&&b.width()==badge.width&&b.height()==badge.height){"Unread badge clipped"}};checks++
            screen("home")
            for(tab in listOf(1,3,0)){
                ui{find("home.nav.$tab")!!.performClick()};await("selected tab $tab"){find("home.nav.$tab")?.isSelected==true}
                check(find("home.navigation")===nav&&walk(activity.window.decorView).count{it.tag=="home.navigation"}==1);checks++
                if(tab==1)screen("contacts");if(tab==3)screen("profile")
            }
            ui{repeat(4){for(tab in listOf(1,3,0))find("home.nav.$tab")!!.performClick()}}
            await("rapid tabs settle"){find("home.nav.0")?.isSelected==true};check(find("home.navigation")===nav);checks++
            for(color in listOf(0xFFFBBF24.toInt(),0xFF2563EB.toInt())){
                ui{ThemeStore.save(i.targetContext,color);set(activity,"palette",ThemePalette(color));invoke(activity,"showHome",false)}
                await("custom theme navigation"){(find("home.navigation") as? HomeNavigation)?.themeColor==color}
                check(find("home.navigation")!==nav);screen(if(color==0xFFFBBF24.toInt())"yellow-theme"else"blue-theme");checks++
            }
            val incoming=JSONObject().put("id","visual-incoming").put("sender","87000002").put("recipient",owner).put("type","text").put("text","周末一起看星星吧，记得带相机。✨").put("created",now-120000)
            val outgoing=JSONObject().put("id","visual-outgoing").put("sender",owner).put("recipient","87000002").put("type","text").put("text","好呀，想把这一刻留在照片里。").put("created",now-60000).put("read",true)
            ui{ThemeStore.save(i.targetContext,ThemeStore.DEFAULT);set(activity,"palette",ThemePalette(ThemeStore.DEFAULT));set(activity,"peer",friends.getJSONObject(0));@Suppress("UNCHECKED_CAST") val messages=field(activity,"messages") as MutableList<JSONObject>;messages.clear();messages.addAll(listOf(incoming,outgoing));invoke(activity,"showChat")}
            await("native chat"){find("chat.composer")!=null};i.waitForIdleSync()
            ui {
                val portraits=walk(activity.window.decorView).filter{it.contentDescription in listOf("我的头像","对方头像")}
                check(portraits.size==2);@Suppress("UNCHECKED_CAST") val bubbles=field(activity,"messageBubbles") as Map<String,View>
                for((id,mine)in listOf("visual-incoming" to false,"visual-outgoing" to true)){
                    val bubble=bubbles[id]!!;val photo=portraits.first{it.contentDescription==if(mine)"我的头像"else"对方头像"};val b=IntArray(2);val p=IntArray(2);bubble.getLocationOnScreen(b);photo.getLocationOnScreen(p)
                    check(if(mine)p[0]>b[0]+bubble.width else p[0]+photo.width<b[0]);check(b[0]>=0&&b[0]+bubble.width<=activity.window.decorView.width)
                }
            };checks++
            screen("chat")
            val editor=find("chat.composer")!!
            val hiddenNavigation=field(activity,"homeNavigation") as View
            ui{walk(hiddenNavigation).first{it.tag=="home.nav.3"}.performClick()};check(find("chat.composer")===editor&&field(activity,"peer")!=null);checks++
            invoke(activity,"sendMenu");await("input drawer"){find("composer.action.image")!=null};check(find("chat.composer")===editor&&(field(activity,"actionSheet") as? Dialog)?.isShowing!=true);checks++;screen("input-tools")
            ui{Hub.token="";Hub.me=JSONObject();activity.signedOut();walk(hiddenNavigation).first{it.tag=="home.nav.3"}.performClick()};check(find("auth.submit")!=null&&find("home.navigation")==null);checks++;screen("login")
            check(network.get()==0);checks++
            val sha=MessageDigest.getInstance("SHA-256").digest(File(i.targetContext.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
            File(folder,"result.json").writeText(JSONObject().put("passed",true).put("checks",checks).put("api",Build.VERSION.SDK_INT).put("apkSha256",sha).put("phoneLayout",layout=="phone").put("widthDp",cfg.screenWidthDp).put("heightDp",cfg.screenHeightDp).put("themesChecked",12).put("persistentNavigation",true).put("rapidTabSwitches",12).put("sideAvatarsRetained",true).put("realNetworkRequests",network.get()).put("physicalDevices",false).toString(2))
            return "PASS native visual layout, custom-theme contrast and stable navigation: $checks checks"
        }catch(e:Throwable){runCatching{screen("failure")};File(folder,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally{ui{a?.finish();Hub.service?.stopSelf();ConversationState.clearMemory();Hub.token="";Hub.me=JSONObject();Hub.connected=false;Hub.http=http;AppUpdates.transport=updates;ThemeStore.save(i.targetContext,original)}}
    }
}
