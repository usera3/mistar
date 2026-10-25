package cn.qingliao.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Only the separate test APK contains this synthetic, fully intercepted UI scenario. */
object AdminTests {
    fun run(i:Instrumentation,variant:String):String {
        val first=AtomicBoolean(true);val hidden=AtomicBoolean(false);val commentHidden=AtomicBoolean(false)
        val requests=CopyOnWriteArrayList<Pair<String,JSONObject>>();val screens=mutableListOf<Activity>()
        val folder=File(i.targetContext.getExternalFilesDir(null),"admin-$variant").apply{mkdirs()}
        val pid="a".repeat(32);val cid="b".repeat(32);val fid="c".repeat(32);val adminId="99009911";val userId="99009922"
        fun user(admin:Boolean=false)=JSONObject().put("id",if(admin)adminId else userId).put("username",if(admin)"synthetic_admin" else "synthetic_author").put("nickname",if(admin)"本地模拟管理员" else "本地模拟发布者").put("administrator",admin).put("created",System.currentTimeMillis()).put("post_count",1).put("banned_until",0).put("posting_until",0)
        fun post()=JSONObject().put("id",pid).put("user",user()).put("text","这是一条仅用于界面验收的本地模拟动态。").put("created",System.currentTimeMillis()).put("audience","friends").put("photos",JSONArray().put(JSONObject().put("id",fid))).put("comment_count",1).put("moderation",JSONObject().put("hidden",hidden.get()))
        fun await(predicate:()->Boolean){val end=SystemClock.elapsedRealtime()+12000;while(!predicate()){check(SystemClock.elapsedRealtime()<end){"Admin native UI timed out"};Thread.sleep(30)};i.waitForIdleSync()}
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
        fun all(a:Activity):List<View>{if(Looper.myLooper()==Looper.getMainLooper())return views(a.window.decorView);var result=listOf<View>();i.runOnMainSync{result=views(a.window.decorView)};return result}
        fun invoke(o:Any,name:String,vararg args:Any?){val m=o.javaClass.declaredMethods.first{it.name==name&&it.parameterCount==args.size}.apply{isAccessible=true};i.runOnMainSync{m.invoke(o,*args)}}
        fun field(o:Any,name:String):Any?=o.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(o)
        fun set(o:Any,name:String,value:Any?){o.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(o,value)}
        fun start(cls:Class<out Activity>)=i.startActivitySync(Intent(i.targetContext,cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also{screens.add(it)}
        fun capture(name:String,a:Activity){i.runOnMainSync{a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)};i.waitForIdleSync();Thread.sleep(200);val b=i.uiAutomation.takeScreenshot();File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        fun click(a:Activity,label:String){
            await{all(a).any{it is TextView&&it.text.toString()==label}}
            val v=all(a).first{it is TextView&&it.text.toString()==label}
            i.runOnMainSync{v.requestRectangleOnScreen(Rect(0,0,v.width,v.height),true)};i.waitForIdleSync()
            i.runOnMainSync{v.performClick()}
        }
        fun confirm(a:Activity){
            await{field(a,"actionDialog") is AlertDialog}
            val d=field(a,"actionDialog") as AlertDialog
            i.runOnMainSync{views(d.window!!.decorView).filterIsInstance<EditText>().first().setText("本地合成验收原因");d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()}
        }
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff7660d5.toInt())}
        val image=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        i.runOnMainSync{
            Hub.token="local-admin-fixture-1";Hub.me=user(true).put("admin_password_change_required",true);Hub.connected=false
            Hub.http=OkHttpClient.Builder().addInterceptor{chain->
                val r=chain.request();val path=r.url.encodedPath
                val buffer=okio.Buffer();r.body?.writeTo(buffer);val body=runCatching{JSONObject(buffer.readUtf8())}.getOrDefault(JSONObject())
                if(r.method=="POST")requests.add(path to body)
                var bytes:ByteArray?=null
                val data=when {
                    path=="/api/admin/status"->JSONObject().put("administrator",true).put("must_change_password",first.get())
                    path=="/api/password/change"->{first.set(false);JSONObject().put("ok",true)}
                    path=="/api/admin/unlock"->JSONObject().put("grant","local-fixture-grant").put("expires",System.currentTimeMillis()+600000)
                    path=="/api/admin/posts"->JSONObject().put("posts",JSONArray().put(post())).put("next","")
                    path.endsWith("/comments")&&path.startsWith("/api/admin/")->JSONObject().put("comments",JSONArray().put(JSONObject().put("id",cid).put("user",user()).put("text","本地模拟评论").put("created",System.currentTimeMillis()).put("moderation",JSONObject().put("hidden",commentHidden.get())))).put("next","")
                    path.startsWith("/api/admin/content/post/")->{check(r.header("X-Admin-Grant")=="local-fixture-grant");hidden.set(body.getBoolean("hidden"));JSONObject().put("ok",true)}
                    path.startsWith("/api/admin/content/comment/")->{commentHidden.set(body.getBoolean("hidden"));JSONObject().put("ok",true)}
                    path=="/api/admin/users"->JSONObject().put("users",JSONArray().put(user()).put(user(true))).put("next","")
                    path.startsWith("/api/admin/users/")->JSONObject().put("ok",true)
                    path=="/api/admin/audit"->JSONObject().put("logs",JSONArray().put(JSONObject().put("action","hide").put("target",pid).put("reason","本地合成验收原因").put("actor",adminId).put("created",System.currentTimeMillis()).put("changed",1))).put("next","")
                    path.startsWith("/api/admin/media/")->{check(r.header("X-Admin-Grant")=="local-fixture-grant");bytes=image;JSONObject()}
                    path=="/api/posts"&&r.method=="POST"->JSONObject().put("id",pid).put("audience",body.optString("audience"))
                    path=="/api/posts"->JSONObject().put("posts",JSONArray()).put("next","")
                    path=="/api/contacts"->JSONObject().put("contacts",JSONArray())
                    path=="/api/conversations"->JSONObject().put("conversations",JSONArray())
                    else->JSONObject()
                }
                Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("Only local synthetic admin test").body((bytes?:data.toString().toByteArray()).toResponseBody((if(bytes!=null)"image/png" else "application/json").toMediaType())).build()
            }.build()
        }
        try{
            val setup=start(AdminActivity::class.java)
            await{all(setup).filterIsInstance<EditText>().size==3}
            i.runOnMainSync{val fields=all(setup).filterIsInstance<EditText>();fields[0].setText("synthetic-old-password");fields[1].setText("synthetic-new-admin-password");fields[2].setText("synthetic-new-admin-password")}
            click(setup,"修改密码并重新登录");await{Hub.token.isEmpty()}
            check(requests.any{it.first=="/api/password/change"&&it.second.getString("new_password")=="synthetic-new-admin-password"})
            i.runOnMainSync{Hub.token="local-admin-fixture-2";Hub.me=user(true).put("admin_password_change_required",false);Hub.connected=true}
            val a=start(AdminActivity::class.java)
            await{all(a).filterIsInstance<EditText>().size==1}
            i.runOnMainSync{all(a).filterIsInstance<EditText>().first().setText("synthetic-new-admin-password")}
            click(a,"进入管理中心");await{all(a).filterIsInstance<TextView>().any{it.text.toString()=="下架动态"}}
            Thread.sleep(4000);capture("posts",a)
            click(a,"查看图片 1 / 1");await{(field(a,"mediaDialog") as? android.app.Dialog)?.isShowing==true}
            check(i.targetContext.cacheDir.listFiles()?.any{it.name.startsWith("admin-media-")}==true)
            i.runOnMainSync{(field(a,"mediaDialog") as android.app.Dialog).dismiss()}
            await{i.targetContext.cacheDir.listFiles()?.none{it.name.startsWith("admin-media-")}!=false}
            click(a,"下架动态");confirm(a);await{hidden.get()&&all(a).filterIsInstance<TextView>().any{it.text.toString()=="恢复动态"}}
            click(a,"恢复动态");confirm(a);await{!hidden.get()}
            click(a,"管理评论（1 条可见）");await{all(a).filterIsInstance<TextView>().any{it.text.toString()=="下架评论"}}
            click(a,"下架评论");confirm(a);await{commentHidden.get()}
            click(a,"账号");await{all(a).filterIsInstance<TextView>().any{it.text.toString()=="封禁账号"}}
            capture("users",a)
            click(a,"封禁账号");await{field(a,"actionDialog") is AlertDialog}
            i.runOnMainSync{(field(a,"actionDialog") as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()}
            await{(field(a,"actionDialog") as? AlertDialog)?.window?.decorView?.let{views(it).any{v->v is EditText}}==true}
            confirm(a);await{requests.any{it.first.contains("/admin/users/")&&it.second.optString("action")=="ban"}}
            check(requests.first{it.first.contains("/admin/users/")&&it.second.optString("action")=="ban"}.second.getInt("days")==1)
            click(a,"解除封禁");confirm(a);await{requests.any{it.second.optString("action")=="unban"}}
            click(a,"记录");await{all(a).filterIsInstance<TextView>().any{it.text.toString()=="本地合成验收原因"}};capture("audit",a)
            val theme=start(ThemeActivity::class.java);await{(field(a,"grant") as String).isEmpty()};i.runOnMainSync{theme.finish()}
            await{all(a).filterIsInstance<TextView>().any{it.text.toString()=="进入管理中心"}}
            check(i.targetContext.cacheDir.listFiles()?.none{it.name.startsWith("admin-media-")}!=false)
            i.runOnMainSync{a.finish();Hub.me=user(false).put("id","99009933");Hub.token="local-admin-fixture-3";Hub.connected=true}
            val main=start(MainActivity::class.java) as MainActivity
            i.runOnMainSync{set(main,"tab",3)};invoke(main,"showHome",false);invoke(main,"renderHome")
            check(all(main).filterIsInstance<TextView>().none{it.text.toString()=="管理中心"})
            i.runOnMainSync{set(main,"tab",2)};invoke(main,"showHome",false);invoke(main,"renderHome");i.waitForIdleSync()
            check(all(main).filterIsInstance<TextView>().none{it.text.toString()=="广场"})
            i.runOnMainSync{i.targetContext.getSharedPreferences("moments-draft",0).edit().putString("99009933",JSONObject().put("text","本地旧广场草稿").put("audience","public").toString()).commit()}
            val composer=start(PostActivity::class.java);await{all(composer).filterIsInstance<TextView>().any{it.text.toString() in listOf("仅好友圈 · 自己和好友可见","仅好友可见")}}
            check(field(composer,"audience")=="friends");capture("friends-only",composer)
            val result=JSONObject().put("passed",true).put("allHttpLocallyIntercepted",true).put("physicalDeviceTests",false)
                .put("api",android.os.Build.VERSION.SDK_INT).put("densityDpi",i.targetContext.resources.displayMetrics.densityDpi).put("fontScale",i.targetContext.resources.configuration.fontScale)
                .put("initialPasswordChange",true).put("hideRestoreAndComments",true).put("banReasonAndDuration",true).put("ordinaryAccountNoAdminEntry",true)
                .put("publicPlazaEntryRemoved",true).put("oldPublicDraftNowFriends",true).put("grantClearedOnBackground",true).put("privatePreviewCacheCleared",true)
            File(folder,"result.json").writeText(result.toString(2))
            return "PASS native admin and closed plaza UI: "+result.toString()
        }finally{i.runOnMainSync{Hub.logout();screens.forEach{if(!it.isFinishing)it.finish()}}}
    }
}
