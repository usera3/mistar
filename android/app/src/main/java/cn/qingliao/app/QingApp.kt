package cn.qingliao.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.*

class QingApp : Application() {
    override fun onCreate() {
        super.onCreate();Hub.init(this)
        registerActivityLifecycleCallbacks(object:ActivityLifecycleCallbacks{
            override fun onActivityCreated(activity:Activity,state:Bundle?){Hub.activities[activity]=true}
            override fun onActivityDestroyed(activity:Activity){Hub.activities.remove(activity)}
            override fun onActivityStarted(activity:Activity){};override fun onActivityResumed(activity:Activity){}
            override fun onActivityPaused(activity:Activity){};override fun onActivityStopped(activity:Activity){}
            override fun onActivitySaveInstanceState(activity:Activity,state:Bundle){}
        })
    }
}

object Hub {
    const val SERVER = "https://47.245.2.254"
    lateinit var app: Application
    lateinit var http: OkHttpClient
    val io = Executors.newFixedThreadPool(4)
    val main = Handler(Looper.getMainLooper())
    var token = ""
    var me = JSONObject()
    var socket: WebSocket? = null
    var connected = false
    var listener: ((JSONObject) -> Unit)? = null
    var foreground = false
    var viewingPeer = ""
    private val typingUntil = mutableMapOf<String, Long>()
    var call: JSONObject? = null
    var callUser = JSONObject()
    var phase = ""
    var connectedAt = 0L
    var ringUntil=0L
    var rtc: RtcEngine? = null
    var service: ChatService? = null
    private var reconnect = 0
    private var reconnectTask: Runnable? = null
    private var connectionEpoch=0L
    private var authDeadline:Runnable?=null
    private var callDeadline:Runnable?=null
    var reconnectUntil=0L
    private var pendingHangup=""
    private var callRevision=0L
    private var ownedCallId=""
    private var pendingInvitePeer=""
    private var pendingInviteId=""
    private var pendingInviteUntil=0L
    private var networkWatch:NetworkWatch?=null
    val activities=java.util.WeakHashMap<Activity,Boolean>()
    private val workScope=ThreadLocal<Pair<String,String>>()
    fun workSession()=workScope.get()?.first?:token
    fun workOwner()=workScope.get()?.second?:me.optString("id")
    private val prefs get() = app.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun init(a: Application) {
        app = a
        clearSharedFiles()
        val cert = a.assets.open("server_ca.crt").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("qingliao", cert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        http = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, tm)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build()
        try {
            token = decrypt(prefs.getString("token", "") ?: "")
            me = JSONObject(prefs.getString("me", "{}") ?: "{}")
        } catch (_: Exception) { token = ""; prefs.edit().clear().apply() }
        val manager = a.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("connection", "消息连接", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("messages", "聊天消息", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel("calls", "来电提醒", NotificationManager.IMPORTANCE_HIGH))
        networkWatch=NetworkWatch(a).also{it.start()}
        AppUpdates.init()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("qingliao.session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("qingliao.session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    private fun decrypt(s: String): String {
        if (s.isEmpty()) return ""
        val data = Base64.decode(s, Base64.NO_WRAP)
        return String(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12))) }.doFinal(data.copyOfRange(12, data.size)))
    }
    fun saveSession(p: JSONObject) {
        if(token.isNotEmpty()&&token!=p.getString("token"))logout()
        token = p.getString("token"); me = p.getJSONObject("user")
        prefs.edit().putString("token", encrypt(token)).putString("me", me.toString()).apply()
        startService()
    }
    fun saveMe(p: JSONObject) { me = p; prefs.edit().putString("me", p.toString()).apply() }
    fun startService() {
        if(me.optBoolean("admin_password_change_required"))return
        try{app.startForegroundService(Intent(app,ChatService::class.java));app.getSharedPreferences("background",Context.MODE_PRIVATE).edit().remove("last_service_error").apply()}
        catch(e:RuntimeException){app.getSharedPreferences("background",Context.MODE_PRIVATE).edit().putString("last_service_error",e.javaClass.simpleName).apply()}
    }
    fun serviceStopped(stopped:ChatService) {
        if(service!==stopped)return
        service=null
        Outbox.stop()
        if(call!=null)hangup()
        connectionEpoch++;authDeadline?.let{main.removeCallbacks(it)};authDeadline=null
        reconnectTask?.let{main.removeCallbacks(it)};reconnectTask=null
        socket?.cancel();socket=null;connected=false;typingUntil.clear()
    }
    fun logout() {
        val old = token
        runCatching{ConversationState.flush(me.optString("id"),old)}
        ConversationState.clearMemory()
        LocationShareService.instance?.end();LocationSessions.clear()
        val mapCacheClient=MapTiles.client;mapCacheClient.dispatcher.cancelAll();io.execute{runCatching{mapCacheClient.cache?.evictAll()}}
        GroupDirectory.clear()
        val privateScreens=activities.keys.filter{it !is MainActivity}.toList()
        val mainScreens=activities.keys.filterIsInstance<MainActivity>().toList()
        main.post{privateScreens.forEach{if(!it.isFinishing)it.finish()}}
        Outbox.stop();CallActions.clear();ringUntil=0
        ownedCallId="";pendingInvitePeer="";pendingInviteId="";pendingInviteUntil=0
        connectionEpoch++;authDeadline?.let{main.removeCallbacks(it)};authDeadline=null
        callDeadline?.let{main.removeCallbacks(it)};callDeadline=null;pendingHangup="";reconnectUntil=0
        token = ""
        (http.dispatcher.runningCalls()+http.dispatcher.queuedCalls()).filter{it.request().header("Authorization")=="Bearer $old"}.forEach{it.cancel()}
        ChatCrypto.clear(); clearSharedFiles()
        token = ""; connected = false; typingUntil.clear(); AvatarImages.clear(); MomentsImages.clear();MomentFeedCache.clear();socket?.close(1000, "logout"); socket = null
        reconnectTask?.let { main.removeCallbacks(it) }; reconnectTask = null
        rtc?.release(); rtc = null; call = null; phase = ""
        prefs.edit().clear().apply(); me = JSONObject()
        if(Looper.myLooper()==Looper.getMainLooper())mainScreens.forEach{it.signedOut()}
        else main.post{if(token.isEmpty())mainScreens.forEach{it.signedOut()}}
        app.stopService(Intent(app, ChatService::class.java))
        app.getSystemService(NotificationManager::class.java).cancelAll()
        io.execute {
            try { http.newCall(Request.Builder().url("$SERVER/api/logout").header("Authorization", "Bearer $old").post("{}".toRequestBody("application/json".toMediaType())).build()).execute().close() } catch (_: Exception) {}
        }
    }

    fun api(method: String, path: String, data: JSONObject? = null): JSONObject {
        val session=workSession();val owner=workOwner();require(token==session){"登录状态已改变"};val stamp=LocalData.epoch(owner)
        if(path=="/api/messages" && method=="POST") {
            return Outbox.send(data!!,session,owner)
        }
        if(path=="/api/contacts")try { ChatCrypto.ready() } catch(_:Exception) {}
        val raw=rawApi(method,path,data,session);require(token==session&&me.optString("id")==owner){"登录状态已改变"};GroupDirectory.observe(raw,owner,session)
        return ChatCrypto.transform(raw).also{result->
            if(token==session&&owner.isNotEmpty()&&result.has("messages"))LocalHistory.messages(owner,session,result.getJSONArray("messages"),stamp)
            if(token==session&&owner.isNotEmpty()&&result.has("contacts"))LocalHistory.homePart(owner,session,"contacts",result.getJSONArray("contacts"),stamp)
            if(token==session&&owner.isNotEmpty()&&result.has("conversations"))LocalHistory.homePart(owner,session,"conversations",result.getJSONArray("conversations"),stamp)
        }
    }
    fun rawApi(method: String, path: String, data: JSONObject? = null, session: String = workSession(),requestTag:Any?=null): JSONObject {
        val body = data?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(SERVER + path).header("Authorization", "Bearer $session").header("X-Mistar-Groups","1").tag(requestTag)
            .method(method, if (method in listOf("POST", "PATCH", "PUT")) body ?: "{}".toRequestBody("application/json".toMediaType()) else null).build()
        http.newCall(request).execute().use { response ->
            val result = JSONObject(response.body?.string() ?: "{}")
            if (!response.isSuccessful) throw ApiFailure(response.code,result.optString("error", "请求失败 (${response.code})"))
            return result
        }
    }
    fun job(task: () -> JSONObject, success: (JSONObject) -> Unit, error: (String) -> Unit) {
        val expected=token;val owner=me.optString("id")
        io.execute {
            if(token!=expected)return@execute
            workScope.set(expected to owner)
            try { val p = task(); main.post { if(token==expected)success(p) } }
            catch (e: Exception) { main.post { if(token==expected)error(e.message?.take(180) ?: "网络连接失败，请重试") } }
            finally{workScope.remove()}
        }
    }
    fun emit(p: JSONObject) { listener?.invoke(p) }
    fun peerIsTyping(peer: String): Boolean {
        val expires = typingUntil[peer] ?: return false
        if (expires <= SystemClock.elapsedRealtime()) { typingUntil.remove(peer); return false }
        return true
    }
    fun sendTyping(peer: String, active: Boolean) {
        if (connected && peer.isNotEmpty()) socket?.send(JSONObject().put("event", "typing").put("peer", peer).put("active", active).toString())
    }
    fun signal(event: String, extra: JSONObject = JSONObject()) {
        val expected=token;val active=call?.let { JSONObject(it.toString()) }
        if(event=="call.accept") {
            if(phase!="incoming"||active==null)return
            ownedCallId=active.optString("id")
        }
        if(event=="call.signal" && active!=null) {
            io.execute { try {
                val encrypted=ChatCrypto.sealSignal(extra.getJSONObject("signal"),active)
                main.post { if(token==expected && call?.optString("id")==active.optString("id"))sendSignal(event,JSONObject().put("signal",encrypted).put("call_id",active.getString("id"))) }
            } catch(_:Exception){ main.post { if(token==expected&&call?.optString("id")==active.optString("id")){emit(JSONObject().put("event","error").put("error","无法建立安全通话连接"));sendSignal("call.end",JSONObject().put("call_id",active.getString("id")));endLocalCall()} } } }
            return
        }
        if(event=="call.invite") {
            io.execute { try { ChatCrypto.ready();rawApi("GET","/api/crypto/identity/"+extra.getString("peer"),session=expected).also { require(it.has("key_id")) { "对方需先升级 App 并登录" } };main.post { if(token==expected){pendingInvitePeer=extra.getString("peer");pendingInviteId=java.util.UUID.randomUUID().toString();extra.put("invite_id",pendingInviteId);pendingInviteUntil=SystemClock.elapsedRealtime()+10000;sendSignal(event,extra)} } }
            catch(e:Exception) { main.post { if(token==expected)emit(JSONObject().put("event","error").put("error",e.message?:"无法建立安全连接")) } } }
            return
        }
        sendSignal(event,extra)
    }
    private fun sendSignal(event:String,extra:JSONObject) {
        extra.put("event",event)
        if(!extra.has("call_id"))call?.let { extra.put("call_id",it.getString("id")) }
        if(socket?.send(extra.toString())!=true)emit(JSONObject().put("event","error").put("error","尚未连接服务器，请稍后重试"))
    }
    fun networkChanged(available:Boolean,changed:Boolean) {
        if(token.isEmpty()||service==null)return
        if(!available){socket?.let{it.cancel();lost(it,token,0)};return}
        if(changed)socket?.let{it.cancel();lost(it,token,0)}
        if(!connected)connect()
    }
    fun markCallReconnecting() {
        val active=call?:return
        if(callDeadline==null) {
            val id=active.optString("id");val session=token;reconnectUntil=SystemClock.elapsedRealtime()+20000
            callDeadline=Runnable{
                callDeadline=null
                if(token==session&&call?.optString("id")==id&&(!connected||rtc?.mediaConnected!=true)) {
                    hangup();emit(JSONObject().put("event","error").put("error","通话重连超时，请稍后重拨"))
                }
            }.also{main.postDelayed(it,20000)}
        }
        phase="reconnecting";emit(JSONObject().put("event","call.reconnecting"))
    }
    fun mediaRestored() {
        if(call==null||!connected)return
        callDeadline?.let{main.removeCallbacks(it)};callDeadline=null;reconnectUntil=0
        if(connectedAt==0L){connectedAt=SystemClock.elapsedRealtime();signal("call.connected")}
        phase="connected";emit(JSONObject().put("event","rtc.connected"))
    }
    fun rejectIncoming() {
        if(phase!="incoming")return
        if(!connected){hangup();return}
        signal("call.reject");endLocalCall();emit(JSONObject().put("event","rtc.ended"))
    }
    fun hangup() {
        val active=call?:return;pendingHangup=active.optString("id")
        if(connected)signal("call.end",JSONObject().put("call_id",pendingHangup))
        endLocalCall();emit(JSONObject().put("event","rtc.ended"))
    }
    private fun resyncCall() {
        val session=token;val epoch=connectionEpoch;val revision=callRevision
        job({rawApi("GET","/api/call",session=session)}, {state ->
            if(token!=session||epoch!=connectionEpoch||revision!=callRevision||!connected)return@job
            val active=state.optJSONObject("call");val local=call
            if(pendingHangup.isNotEmpty()) {
                if(active?.optString("id")==pendingHangup)signal("call.end",JSONObject().put("call_id",pendingHangup))
                pendingHangup="";return@job
            }
            if(active==null){if(local!=null){endLocalCall();emit(JSONObject().put("event","rtc.ended"))};return@job}
            if(local==null) {
                if(!active.optBoolean("accepted"))handle(JSONObject().put("event",if(active.optString("caller")==me.optString("id"))"call.waiting" else "call.ringing").put("call",active).put("user",state.getJSONObject("user")))
                return@job
            }
            if(active.optString("id")!=local.optString("id")){endLocalCall();emit(JSONObject().put("event","rtc.ended"));return@job}
            call=active;callUser=state.getJSONObject("user")
            if(active.optBoolean("accepted")) {
                if(rtc!=null)rtc?.requestRestart()
                else if(foreground)handle(JSONObject().put("event","call.accepted").put("call",active))
                else hangup()
            } else {phase=if(active.optString("caller")==me.optString("id"))"outgoing" else "incoming";emit(JSONObject().put("event","call.resynced"))}
        },{if(token==session&&epoch==connectionEpoch&&call!=null)markCallReconnecting()})
    }
    fun connect() {
        if (token.isBlank() || socket != null) return
        reconnectTask?.let { main.removeCallbacks(it) }; reconnectTask = null
        val expectedToken = token;val epoch=++connectionEpoch
        authDeadline?.let{main.removeCallbacks(it)}
        authDeadline=Runnable { if(epoch==connectionEpoch&&!connected)socket?.let{it.cancel();lost(it,expectedToken,0)} }.also{main.postDelayed(it,15000)}
        socket = http.newWebSocket(Request.Builder().url(SERVER.replace("https", "wss") + "/ws").build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { if(token==expectedToken&&epoch==connectionEpoch)ws.send(JSONObject().put("token",expectedToken).toString()) else ws.close(1000,"stale") }
            override fun onMessage(ws: WebSocket, text: String) {
                if(token!=expectedToken||epoch!=connectionEpoch)return
                try { val p = JSONObject(text)
                    if(p.optString("event") in listOf("message","message.updated","group.message"))p.put("message",ChatCrypto.incoming(p.getJSONObject("message")))
                    if(p.optString("event")=="group.message")p.put("event","message")
                    if(p.optString("event")=="location.live"){
                        LocationSessions.accept(p.getJSONObject("update"),expectedToken,me.optString("id"));return
                    }
                    if(p.optString("event")=="call.signal") {
                        val active=call
                        if(active==null || active.optString("id")!=p.optString("call_id"))return
                        p.put("signal",ChatCrypto.openSignal(p.getJSONObject("signal"),active))
                    }
                    main.post { if(token==expectedToken&&epoch==connectionEpoch&&socket===ws)handle(p) }
                } catch (_: Exception) {
                    val failed=runCatching { JSONObject(text) }.getOrNull()
                    if(failed?.optString("event")=="call.signal")main.post {
                        if(token==expectedToken && call?.optString("id")==failed.optString("call_id")) {
                            emit(JSONObject().put("event","error").put("error","无法验证通话连接，请重新拨打"))
                            signal("call.end");endLocalCall()
                        }
                    }
                }
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, reason) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { main.post { lost(ws, expectedToken, code) } }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { main.post { lost(ws, expectedToken, 0) } }
        })
    }
    private fun lost(ws: WebSocket, expected: String, code: Int) {
        if (token != expected || socket !== ws) return
        authDeadline?.let{main.removeCallbacks(it)};authDeadline=null
        socket = null; connected = false; typingUntil.clear();LocationSessions.disconnected()
        if(call!=null)markCallReconnecting()
        emit(JSONObject().put("event", "connection"))
        if (code == 1008) { logout(); emit(JSONObject().put("event", "expired")); return }
        service?.update()
        if (token.isNotEmpty()) {
            val base=minOf(24000L,1500L*(1L shl minOf(reconnect++,4)))
            val delay=(base*(.75+Math.random()*.5)).toLong()
            reconnectTask = Runnable { connect() }.also { main.postDelayed(it, delay) }
        }
    }
    private fun handle(p: JSONObject) {
        when (p.optString("event")) {
            "location.closed"->LocationSessions.close(p.optString("share_id"))
            "group.removed"->{val id=p.optString("group_id");GroupDirectory.removed(id,p.optInt("epoch"));app.getSystemService(NotificationManager::class.java).cancel(id.hashCode())}
            "app.update" -> AppUpdates.check(hint=p.optInt("version_code"))
            "error" -> if(p.optString("code") in listOf("call_busy","self_busy")) {
                if(p.optString("invite_id")==pendingInviteId) {pendingInvitePeer="";pendingInviteId="";pendingInviteUntil=0}
                if(call==null)service?.update()
            }
            "ready" -> { connected=true;reconnect=0;authDeadline?.let{main.removeCallbacks(it)};authDeadline=null;typingUntil.clear();saveMe(p.getJSONObject("user"));service?.update();resyncCall();LocalHistory.synchronize();Outbox.kick() }
            "me.updated" -> saveMe(p.getJSONObject("user"))
            "membership.updated" -> p.optJSONObject("membership")?.let{saveMe(JSONObject(me.toString()).put("membership",it).put("member",it.optBoolean("active")))}
            "typing" -> {
                val peer = p.optString("peer")
                if (p.optBoolean("active")) typingUntil[peer] = SystemClock.elapsedRealtime() + 5000 else typingUntil.remove(peer)
            }
            "presence" -> if (p.optJSONObject("user")?.optBoolean("online") != true) typingUntil.remove(p.optJSONObject("user")?.optString("id"))
            "message" -> {
                val m = p.getJSONObject("message")
                typingUntil.remove(m.optString("sender"))
                val conversation=ChatAddress.peer(m,me.optString("id"))
                if (m.optString("sender") != me.optString("id") && conversation.isNotEmpty() && (!foreground || viewingPeer != conversation)) {
                    val preview = when (m.optString("type")) { "text" -> m.optString("text"); "html" -> "[HTML 消息]"; "image" -> "[图片]"; "voice" -> "[语音]"; "file" -> "[文件]"; else -> m.optString("text") }
                    notify("弥星", "你收到一条新消息", conversation, false)
                }
            }
            "call.waiting", "call.ringing" -> {
                val received=p.getJSONObject("call")
                if(p.optString("event")=="call.waiting") {
                    if(received.optString("invite_id")!=pendingInviteId||pendingInviteId.isEmpty()||received.optString("callee")!=pendingInvitePeer||SystemClock.elapsedRealtime()>pendingInviteUntil)return
                    ownedCallId=received.optString("id");pendingInvitePeer=""
                }
                if(call!=null&&call?.optString("id")!=received.optString("id"))return
                callRevision++
                call = p.getJSONObject("call"); callUser = p.getJSONObject("user")
                phase = if (p.optString("event") == "call.ringing") "incoming" else "outgoing"
                connectedAt = 0
                val active=call!!
                val remaining=active.optLong("expires_in_ms",(45000-(System.currentTimeMillis()-active.optLong("created"))).coerceIn(0,45000))
                ringUntil=SystemClock.elapsedRealtime()+remaining
                if (phase == "incoming") { service?.ring(); if (!foreground) notify((if(callUser.optBoolean("bot"))"机器人 · " else "")+callUser.optString("nickname"), if (call!!.optBoolean("video")) "邀请你视频通话" else "邀请你语音通话", callUser.optString("id"), true) }
            }
            "call.accepted" -> {
                val accepted=p.getJSONObject("call")
                if(call?.optString("id")!=accepted.optString("id"))return
                if(ownedCallId!=accepted.optString("id")){endLocalCall();emit(JSONObject().put("event","call.other_device"));return}
                callRevision++
                service?.stopRing();CallActions.clear();ringUntil=0;app.getSystemService(NotificationManager::class.java).cancel(99);call = p.getJSONObject("call"); phase = "connecting"
                service?.update(true, call!!.optBoolean("video"))
                val engine=RtcEngine(app);rtc=engine;val activeId=call!!.optString("id")
                val caller = call!!.optString("caller") == me.optString("id")
                job({ api("GET", "/api/ice") }, { result ->
                    if(call?.optString("id")==activeId&&rtc===engine&&phase=="connecting")engine.start(call!!.optBoolean("video"),caller,result)
                    else if(rtc!==engine)engine.release()
                }, { error -> if(call?.optString("id")==activeId&&rtc===engine){emit(JSONObject().put("event","error").put("error",error));signal("call.end");endLocalCall()} })
            }
            "call.signal" -> if (p.optString("call_id") == call?.optString("id")) rtc?.receive(p.getJSONObject("signal"))
            "call.peer_reconnecting" -> if(p.optString("call_id")==call?.optString("id"))markCallReconnecting()
            "call.peer_reconnected" -> if(p.optString("call_id")==call?.optString("id"))rtc?.requestRestart()
            "call.ended" -> {if(p.optString("call_id")==pendingHangup)pendingHangup="";if(p.optString("call_id")==call?.optString("id"))endLocalCall()}
        }
        if(p.optString("event") in listOf("ready","me.updated","membership.updated"))MembershipEvents.publish(p)
        LocalHistory.event(p);emit(p)
    }
    fun endLocalCall() {
        ownedCallId="";pendingInvitePeer="";pendingInviteId="";pendingInviteUntil=0
        callRevision++;CallActions.clear();ringUntil=0
        callDeadline?.let{main.removeCallbacks(it)};callDeadline=null;reconnectUntil=0
        service?.stopRing(); rtc?.release(); rtc = null; call = null; phase = ""; connectedAt = 0; service?.update()
        app.getSystemService(NotificationManager::class.java).cancel(99)
    }
    fun notify(title: String, text: String, peer: String, call: Boolean) {
        val owner=me.optString("id");if(token.isEmpty()||owner.isEmpty())return
        if(!call&&ConversationState.muted(owner,peer))return
        val intent = Intent(app, MainActivity::class.java).putExtra("peer", peer).putExtra("owner",owner).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(app, (owner+":"+peer).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val publicVersion=NotificationCompat.Builder(app,if(call)"calls" else "messages").setSmallIcon(R.drawable.ic_notification).setContentTitle("弥星").setContentText(if(call)"有新的来电" else "有新的消息").build()
        val n = NotificationCompat.Builder(app, if (call) "calls" else "messages").setSmallIcon(cn.qingliao.app.R.drawable.ic_notification)
            .setContentTitle(if(call)title else "弥星").setContentText(if(call)text.take(100) else "你收到一条新消息").setContentIntent(pending).setAutoCancel(true)
            .setCategory(if (call) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setPublicVersion(publicVersion).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPriority(NotificationCompat.PRIORITY_HIGH)
        if(call&&phase=="incoming") {
            val open=CallActions.pending("open")
            n.setContentIntent(open).setAutoCancel(false).setOngoing(true).setTimeoutAfter((ringUntil-SystemClock.elapsedRealtime()).coerceIn(1,45000))
                .addAction(R.drawable.ic_notification,"拒绝",CallActions.pending("reject"))
                .addAction(R.drawable.ic_notification,"接听",CallActions.pending("accept"))
            val manager=app.getSystemService(NotificationManager::class.java)
            if(Build.VERSION.SDK_INT<34||manager.canUseFullScreenIntent())n.setFullScreenIntent(open,true)
        }
        try { app.getSystemService(NotificationManager::class.java).notify(if (call) 99 else peer.hashCode(), n.build()) } catch (_: SecurityException) {}
    }
    private var cacheEpoch=0L
    @Synchronized fun localCacheBytes():Long = File(app.cacheDir,"shared").listFiles()?.filter{it.isFile}?.sumOf{it.length()} ?: 0L
    @Synchronized fun clearLocalCache() {
        cacheEpoch++
        File(app.cacheDir,"shared").listFiles()?.filter{it.isFile}?.forEach{it.delete()}
        AvatarImages.clear();MomentsImages.clear();MomentFeedCache.clear()
    }
    @Synchronized fun clearSharedFiles() {
        cacheEpoch++
        app.cacheDir.listFiles()?.filter{it.isFile&&it.name.startsWith("admin-media-")}?.forEach{it.delete()}
        listOf(File(app.cacheDir,"shared"),File(app.cacheDir,"crypto-upload")).forEach { dir -> dir.listFiles()?.filter { it.isFile }?.forEach { it.delete() } }
        app.cacheDir.listFiles()?.filter{it.isFile&&it.name.matches(Regex("(?:voice-[0-9a-f-]{36}\\.m4a|moment-[0-9a-f]{32}\\.jpg)"))}?.forEach{it.delete()}
    }
    fun cachedFile(f: JSONObject,loadTag:Any?=null,cancelled:()->Boolean={false}): File {
        val expected=token;val uid=me.optString("id");val epoch=synchronized(this){cacheEpoch}
        require(f.optBoolean("available",true)) { "云端附件已被删除" }
        require(f.getString("id").matches(Regex("[a-f0-9]{32}"))) { "附件编号不正确" }
        require(expected.isNotEmpty()) { "请先登录" }
        check(!cancelled()){ "下载已取消" }
        val dir=File(app.cacheDir,"shared").apply { mkdirs() }
        val safeName=f.optString("name","file").replace(Regex("[^\\p{L}\\p{N}._ -]"),"_").take(100)
        val path=File(dir,uid+"_"+f.getString("id")+"_"+safeName)
        if(path.exists() && path.length()==f.optLong("size")){path.setLastModified(System.currentTimeMillis());return path}
        val request=Request.Builder().url(SERVER+"/api/files/"+f.getString("id")).header("Authorization","Bearer "+expected).tag(loadTag).build()
        val temp=File.createTempFile("attachment-",".part",dir)
        try {
            http.newCall(request).execute().use { r ->
                if(!r.isSuccessful)throw Exception("附件下载失败，请重试")
                r.body!!.byteStream().use { input -> temp.outputStream().use { output ->
                    var total=0L
                    val out=object:java.io.FilterOutputStream(output){override fun write(bytes:ByteArray,offset:Int,length:Int){check(!cancelled()&&token==expected){"下载已取消"};total+=length;require(total<=PayloadRules.MAX_FILE){"附件内容过大"};this.out.write(bytes,offset,length)}}
                    if(f.has("crypto"))ChatCrypto.decryptFile(input,out,f.getString("crypto"),f.getString("context")) else input.copyTo(out)
                } }
            }
            require(token==expected && me.optString("id")==uid) { "登录状态已改变" }
            require(temp.length()==f.optLong("size")) { "附件完整性校验失败" }
            synchronized(this) {
                require(cacheEpoch==epoch&&token==expected) { "缓存已清理，请重新下载" }
                if(!temp.renameTo(path))throw Exception("无法保存附件")
                val entries=dir.listFiles()?.filter{it.isFile&&!it.name.startsWith("attachment-")&&it!=path}?.sortedBy{it.lastModified()}?:emptyList()
                var used=(dir.listFiles()?.filter{it.isFile&&!it.name.startsWith("attachment-")}?.sumOf{it.length()}?:0L);var count=entries.size+1
                for(old in entries){if(used<=128L*1024*1024&&count<=64)break;val size=old.length();if(old.delete()){used-=size;count--}}
            }
            return path
        } finally { temp.delete() }
    }

}

class ChatService : Service() {
    private var ringtone: android.media.Ringtone? = null
    override fun onCreate() { super.onCreate(); Hub.service = this; update(); Hub.connect() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (Hub.token.isEmpty()) { stopSelf(); return START_NOT_STICKY }; Hub.connect(); return START_STICKY }
    fun update(media: Boolean = false, video: Boolean = false) {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, "connection").setSmallIcon(R.drawable.ic_notification).setContentTitle(if(Hub.call!=null)"弥星通话"else"弥星")
            .setContentText(if (Hub.call != null) "通话进行中 · 点按返回" else if (Hub.connected) "消息服务已连接" else "正在重新连接…")
            .setContentIntent(pending).setOngoing(true)
        if(Hub.call!=null&&Hub.phase in listOf("outgoing","connecting","connected","reconnecting"))builder.addAction(R.drawable.ic_call_end,"挂断",CallActions.pending("hangup"))
        val notification=builder.build()
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            if (media || Hub.rtc != null) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video || Hub.call?.optBoolean("video") == true && Hub.rtc != null) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            try { startForeground(1, notification, type) } catch (_: SecurityException) { startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING) }
        } else startForeground(1, notification)
    }
    fun ring() { try { ringtone = android.media.RingtoneManager.getRingtone(this, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)); ringtone?.play() } catch (_: Exception) {} }
    fun stopRing() { ringtone?.stop(); ringtone = null }
    override fun onDestroy() { stopRing();Hub.serviceStopped(this);super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
