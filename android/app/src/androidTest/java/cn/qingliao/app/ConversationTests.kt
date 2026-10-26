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
import org.json.*
import java.io.File
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic native conversation, encrypted draft, transaction, notification and local-search tests. */
object ConversationTests {
    fun run(i:Instrumentation,expectedLayout:String=""):String {
        val root=File(i.targetContext.getExternalFilesDir(null),"conversation-test").apply{mkdirs()}
        val owner="83000001";val one="83000002";val two="83000003";val unit="83000004";val session="synthetic-conversation-session"
        val oldHttp=Hub.http;val oldUpdates=AppUpdates.transport;val paths=CopyOnWriteArrayList<String>();var checks=0
        fun user(id:String,name:String)=JSONObject().put("id",id).put("nickname",name).put("username","qa_$id").put("friend",true).put("state","accepted").put("online",true)
        val users=JSONArray().put(user(one,"小桃")).put(user(two,"山海"))
        val now=System.currentTimeMillis()
        fun message(peer:String,value:String,time:Long)=JSONObject().put("id",UUID.randomUUID().toString()).put("sender",peer).put("recipient",owner).put("type","text").put("text",value).put("created",time)
        val first=message(one,"周末去海边，想带相机记录星空。",now-180000)
        val second=message(two,"晚点把旅行计划发给你。",now-90000)
        val file=message(two,"",now-120000).put("type","file").put("file_id","a".repeat(32)).put("file",JSONObject().put("id","a".repeat(32)).put("name","航班安排.pdf").put("mime","application/pdf").put("size",100))
        val conversations=JSONArray().put(JSONObject().put("user",users.getJSONObject(1)).put("unread",5).put("last",second)).put(JSONObject().put("user",users.getJSONObject(0)).put("unread",3).put("last",first))
        fun ui(f:()->Unit)=i.runOnMainSync(f)
        fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        fun invoke(o:Any,n:String,vararg args:Any?){ui{o.javaClass.declaredMethods.first{it.name==n&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(o,*args)}}
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList()
        fun await(timeout:Long=15000,p:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(true){var ok=false;ui{ok=p()};if(ok){i.waitForIdleSync();return};check(SystemClock.elapsedRealtime()<end){"Native conversation condition timed out"};Thread.sleep(35)}}
        fun screen(name:String){i.waitForIdleSync();Thread.sleep(450);val b=i.uiAutomation.takeScreenshot();File(root,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        ui{
            Hub.token=session;Hub.me=user(owner,"星光旅人").put("admin_password_change_required",true);Hub.connected=false;ConversationState.clearMemory()
            Hub.http=OkHttpClient.Builder().addInterceptor{chain->
                val request=chain.request();check(request.url.host in setOf("47.245.2.254","api.claudcode.xyz"));val path=request.url.encodedPath;paths.add(path)
                val body=when{
                    path=="/api/contacts"->JSONObject().put("contacts",users)
                    path=="/api/conversations"->JSONObject().put("conversations",conversations)
                    path.startsWith("/api/relationships/")->user(path.substringAfterLast('/'),if(path.endsWith(one))"小桃"else"山海")
                    path.startsWith("/api/messages/")->JSONObject().put("messages",JSONArray()).put("has_more",false)
                    path.startsWith("/api/crypto/")->JSONObject().put("error","Synthetic delivery disabled")
                    else->JSONObject().put("ok",true)
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(path.startsWith("/api/crypto/"))409 else 200).message("Synthetic conversation fixture").body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }.build();AppUpdates.transport=Hub.http
        }
        val db=i.targetContext.openOrCreateDatabase("chat-local.sqlite",Context.MODE_PRIVATE,null)
        var a:MainActivity?=null;var extra:Activity?=null
        val notifications=i.targetContext.getSystemService(NotificationManager::class.java)
        try {
            LocalData.homePart(owner,"contacts",users);LocalData.homePart(owner,"conversations",conversations)
            LocalData.put(owner,first);LocalData.put(owner,second);LocalData.put(owner,file)
            val marker="DRAFT_NATIVE_PRIVATE_"+UUID.randomUUID();val quote=JSONObject().put("id",first.getString("id")).put("sender",one).put("type","text").put("excerpt",first.getString("text"))
            ConversationState.draft(owner,session,one,marker,quote,6,9);ConversationState.flush(owner);ConversationState.clearMemory()
            val saved=ConversationState.state(owner,one);check(saved.getString("draft")==marker&&saved.getInt("start")==6&&saved.getJSONObject("quote").getString("id")==first.getString("id"));checks++
            for(suffix in listOf("","-wal")){val f=File(i.targetContext.getDatabasePath("chat-local.sqlite").path+suffix);if(f.exists())check(!String(f.readBytes(),Charsets.ISO_8859_1).contains(marker))};checks++
            var rejected=false;try{LocalData.chatState("83000999",one)}catch(_:Exception){rejected=true};check(rejected);checks++
            ConversationState.draft(owner,session,unit,"transaction draft",null,17,17);ConversationState.flag(owner,session,unit,"muted",true);ConversationState.flush(owner)
            val payload=JSONObject().put("recipient",unit).put("type","text").put("text","transaction draft").put("client_id",UUID.randomUUID().toString())
            db.execSQL("CREATE TRIGGER conversation_qa_fail BEFORE INSERT ON chat_state WHEN NEW.peer='83000004' BEGIN SELECT RAISE(ABORT,'synthetic state failure'); END")
            rejected=false;try{ConversationState.queueText(owner,session,payload)}catch(_:Exception){rejected=true};check(rejected&&LocalData.outgoing(owner,payload.getString("client_id"))==null&&ConversationState.state(owner,unit).getString("draft")=="transaction draft");checks++
            db.execSQL("DROP TRIGGER conversation_qa_fail")
            ConversationState.queueText(owner,session,payload);check(LocalData.outgoing(owner,payload.getString("client_id"))!=null&&!ConversationState.hasDraft(ConversationState.state(owner,unit)));LocalData.remove(owner,payload.getString("client_id"));checks++
            ConversationState.draft(owner,session,unit,"newer draft",null,11,11);ConversationState.queueText(owner,session,JSONObject(payload.toString()).put("client_id",UUID.randomUUID().toString()));check(ConversationState.state(owner,unit).getString("draft")=="newer draft");checks++
            val pending=LocalData.pending(owner,unit);for(n in 0 until pending.length())LocalData.remove(owner,pending.getJSONObject(n).getString("client_id"))
            ConversationState.draft(owner,session,one,"",null,0,0);ConversationState.flush(owner)
            val activity=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity;a=activity
            ui{Hub.me.remove("admin_password_change_required")};invoke(activity,"showHome",false);await{walk(activity.window.decorView).any{it.tag=="conversation:$one"}}
            if(expectedLayout=="phone")check(!CompactUi.enabled(activity)&&activity.resources.configuration.screenWidthDp>=360&&activity.resources.configuration.screenHeightDp>=600){"Phone test is running a compact/watch layout"}
            if(expectedLayout=="watch")check(CompactUi.enabled(activity)){"Watch test is not using a compact layout"}
            ui{val row=walk(activity.window.decorView).filterIsInstance<ConversationRow>().first();val visible=android.graphics.Rect();check(row.getGlobalVisibleRect(visible)&&visible.height()>=row.height){"Initial conversation row clipped"}};checks++
            fun find(tag:String)=walk(activity.window.decorView).firstOrNull{it.tag==tag}
            fun menu(peer:String,item:Int){
                ui{val v=find("conversation:$peer")!!;v.requestRectangleOnScreen(android.graphics.Rect(0,0,v.width,v.height),true);v.performLongClick()}
                await{field(activity,"conversationMenu")!=null}
                ui{(field(activity,"conversationMenu") as PopupMenu).menu.performIdentifierAction(item,0)}
            }
            menu(one,1);await{ConversationState.state(owner,one).optLong("pinned_at")>0};await{walk(activity.window.decorView).filterIsInstance<ConversationRow>().firstOrNull()?.tag=="conversation:$one"};checks++
            menu(one,2);await{ConversationState.state(owner,one).optBoolean("muted")};await{(field(activity,"unreadBadge") as? TextView)?.text=="5"};checks++
            ui{notifications.cancelAll()};await{notifications.activeNotifications.isEmpty()};ui{Hub.notify("private","private",one,false)};check(notifications.activeNotifications.isEmpty());checks++
            ui{Hub.notify("private","private",two,false)};await{notifications.activeNotifications.any{it.id==two.hashCode()&&it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()=="你收到一条新消息"}};checks++
            ui{Hub.notify("合成来电","Synthetic call notice",one,true)};await{notifications.activeNotifications.any{it.id==99}};ui{notifications.cancelAll()};checks++
            screen("conversations")
            invoke(activity,"openChat",users.getJSONObject(0));await{find("chat.composer")!=null}
            ui{(find("chat.composer") as EditText).setText("海边的计划，写到一半也不会丢。")};invoke(activity,"selectReply",first)
            ui{activity.onBackPressed()};await{(find("conversation.preview:$one") as? TextView)?.text?.startsWith("[草稿]")==true};checks++
            check(ConversationState.state(owner,one).has("quote"));screen("draft-list");checks++
            invoke(activity,"openChat",users.getJSONObject(1));await{find("chat.composer")!=null};check((find("chat.composer") as EditText).text.isEmpty());checks++
            ui{(find("chat.composer") as EditText).setText("另一段聊天的草稿");activity.onBackPressed()};invoke(activity,"openChat",users.getJSONObject(0));await{(find("chat.composer") as? EditText)?.text.toString()=="海边的计划，写到一半也不会丢。"};check(field(activity,"replyTarget")!=null);checks++
            ui{val box=find("chat.composer") as EditText;box.setSelection(2,5);activity.onConfigurationChanged(activity.resources.configuration)};check((find("chat.composer") as EditText).selectionStart==2);checks++
            ui{walk(activity.window.decorView).first{it.contentDescription=="取消引用"}.performClick()};check(!ConversationState.state(owner,one).has("quote"));checks++
            ui{activity.onBackPressed()}
            val people=i.startActivitySync(Intent(i.targetContext,PeopleActivity::class.java).putExtra("peer",one).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PeopleActivity;extra=people
            fun pf(tag:String)=walk(people.window.decorView).firstOrNull{it.tag==tag}
            await{pf("people.muted")!=null};check((pf("people.pinned") as Switch).isChecked&&(pf("people.muted") as Switch).isChecked);checks++
            screen("chat-settings")
            ui{pf("people.muted")!!.performClick()};await{pf("people.muted") is Switch&&!(pf("people.muted") as Switch).isChecked};check(!ConversationState.muted(owner,one));checks++
            ui{pf("people.clear")!!.performClick();val dialogs=field(people,"dialogs") as Set<*>;(dialogs.first() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()};check(paths.none{it.endsWith("/clear")});checks++
            ui{people.finish()};extra=null;await{!activity.isFinishing};i.waitForIdleSync()
            val monitor=i.addMonitor(SearchActivity::class.java.name,null,false);invoke(activity,"localSearch");val search=monitor.waitForActivityWithTimeout(5000) as SearchActivity;i.removeMonitor(monitor);extra=search
            fun sf(tag:String)=walk(search.window.decorView).firstOrNull{it.tag==tag}
            fun query(value:String){ui{(sf("search.query") as EditText).setText(value)}}
            val networkBefore=paths.size;query("山海");await{sf("search.contact.$two")!=null};checks++
            query("航班");await{sf("search.message.${file.getString("id")}")!=null};check(paths.size==networkBefore);checks++
            ui{sf("search.clear")!!.performClick()};await{(sf("search.query") as EditText).text.isEmpty()};check(sf("search.message.${file.getString("id")}")==null);checks++
            query("周末");await{sf("search.message.${first.getString("id")}")!=null};screen("search")
            ui{sf("search.filter.contacts")!!.performClick()};await{sf("search.message.${first.getString("id")}")==null};ui{sf("search.filter.all")!!.performClick()};await{sf("search.message.${first.getString("id")}")!=null};checks++
            ui{sf("search.message.${first.getString("id")}")!!.performClick()};extra=null;await{field(activity,"searchWindow")==true&&field(activity,"peer")?.let{(it as JSONObject).optString("id")==one}==true};checks++
            check((field(activity,"composer") as EditText).text.toString()=="海边的计划，写到一半也不会丢。");checks++
            db.execSQL("CREATE TRIGGER conversation_qa_send_fail BEFORE INSERT ON outbox WHEN NEW.peer='83000002' BEGIN SELECT RAISE(ABORT,'synthetic outbox failure'); END")
            invoke(activity,"sendText");await{(field(activity,"savingText") as Set<*>).isEmpty()};check((field(activity,"composer") as EditText).text.toString()=="海边的计划，写到一半也不会丢。"&&LocalData.pending(owner,one).length()==0);checks++
            db.execSQL("DROP TRIGGER conversation_qa_send_fail")
            invoke(activity,"sendText");await{(field(activity,"composer") as EditText).text.isEmpty()};check(!ConversationState.hasDraft(ConversationState.state(owner,one))&&LocalData.pending(owner,one).length()==1);checks++
            await{!(field(Outbox,"running") as java.util.concurrent.atomic.AtomicBoolean).get()}
            val midnight=Calendar.getInstance().apply{set(2026,Calendar.OCTOBER,26,12,0,0);set(Calendar.MILLISECOND,0)}.timeInMillis
            check(ConversationRow.labelTime(midnight-86400000,midnight)=="昨天"&&ConversationRow.labelTime(midnight,midnight).contains(':'));checks++
            ConversationState.flush(owner,session);ui{Hub.token="different-account-session";Hub.me=user("83000999","另一个合成账号")}
            check(!ConversationState.hasDraft(ConversationState.state("83000999",two))&&ConversationState.state("83000999",one).optLong("pinned_at")==0L);rejected=false;try{ConversationState.draft(owner,session,one,"stale write",null,0,0)}catch(_:Exception){rejected=true};check(rejected);checks++
            val digest=MessageDigest.getInstance("SHA-256").digest(File(i.targetContext.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}
            File(root,"result.json").writeText(JSONObject().put("passed",true).put("api",Build.VERSION.SDK_INT).put("checks",checks).put("widthDp",activity.resources.configuration.screenWidthDp).put("heightDp",activity.resources.configuration.screenHeightDp).put("phoneLayout",!CompactUi.enabled(activity)).put("rootWidthPx",activity.window.decorView.width).put("rootHeightPx",activity.window.decorView.height).put("apkSha256",digest).put("draftEncrypted",true).put("quoteAndCursorRestored",true).put("outboxDraftAtomic",true).put("newerDraftSurvivesSend",true).put("accountIsolation",true).put("mutedMessagesSuppressNotifications",true).put("callsPreserved",true).put("searchRequests",0).put("physicalDevices",false).toString(2))
            return "PASS native conversation, encrypted draft, pin/mute and local search API${Build.VERSION.SDK_INT}: $checks checks"
        }catch(e:Throwable){File(root,"failure.txt").writeText(e.stackTraceToString());throw e}
        finally{
            runCatching{db.execSQL("DROP TRIGGER IF EXISTS conversation_qa_fail");db.execSQL("DROP TRIGGER IF EXISTS conversation_qa_send_fail")};db.close()
            ui{extra?.finish();a?.finish();notifications.cancelAll();Hub.service?.stopSelf();Outbox.stop();ConversationState.clearMemory();Hub.token="";Hub.me=JSONObject();Hub.http=oldHttp;AppUpdates.transport=oldUpdates}
        }
    }
}
