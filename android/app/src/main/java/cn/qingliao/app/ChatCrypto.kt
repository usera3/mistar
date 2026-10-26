package cn.qingliao.app

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.crypto.tink.*
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.signature.SignatureKeyTemplates
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** HPKE and signatures use Tink. Identity keys are immutable and pinned on first use.
 * This is not a Double Ratchet protocol and does not claim forward secrecy. */
object ChatCrypto {
    private val utf = Charsets.UTF_8
    private val peers = ConcurrentHashMap<String, JSONObject>()
    @Volatile private var owner = ""
    @Volatile private var privateIdentity: JSONObject? = null
    @Volatile private var ownPublic: JSONObject? = null
    private val prefs get() = Hub.app.getSharedPreferences("chat_crypto", Context.MODE_PRIVATE)
    init { TinkConfig.register() }
    fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun un64(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private fun serialize(k: KeysetHandle): String = ByteArrayOutputStream().also { CleartextKeysetHandle.write(k, JsonKeysetWriter.withOutputStream(it)) }.toString("UTF-8")
    private fun read(s: String) = CleartextKeysetHandle.read(JsonKeysetReader.withString(s))
    private fun publicRead(s: String) = KeysetHandle.readNoSecret(JsonKeysetReader.withString(s))
    private fun fingerprint(enc: String, sig: String) = MessageDigest.getInstance("SHA-256").digest((enc+"\n"+sig).toByteArray(utf)).joinToString("") { "%02x".format(it) }
    private fun publicOf(p: JSONObject): JSONObject {
        val enc=serialize(read(p.getString("enc")).publicKeysetHandle)
        val sig=serialize(read(p.getString("sig")).publicKeysetHandle)
        return JSONObject().put("enc",enc).put("sig",sig).put("key_id",fingerprint(enc,sig))
    }
    private fun master(): SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("qingliao.chat.v1",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("qingliao.chat.v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun sealLocal(bytes: ByteArray, uid: String): String {
        val c=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,master());updateAAD(uid.toByteArray(utf)) }
        return b64(c.iv+c.doFinal(bytes))
    }
    private fun openLocal(s: String,uid: String): ByteArray {
        val bytes=un64(s)
        return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE,master(),GCMParameterSpec(128,bytes.copyOfRange(0,12)));updateAAD(uid.toByteArray(utf));doFinal(bytes.copyOfRange(12,bytes.size)) }
    }
    fun clear() { owner="";privateIdentity=null;ownPublic=null;peers.clear() }
    private fun freshIdentity()=JSONObject()
        .put("enc",serialize(KeysetHandle.generateNew(KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"))))
        .put("sig",serialize(KeysetHandle.generateNew(SignatureKeyTemplates.ECDSA_P256)))
    /** Save a pending private identity before the request; a lost HTTP response must be recoverable. */
    @Synchronized fun restartIdentity(proof:JSONObject):JSONObject {
        val uid=Hub.me.getString("id");val session=Hub.token
        val stored=prefs.getString("pending:$uid",null)
        val pending=if(stored!=null)JSONObject(String(openLocal(stored,uid),utf)) else
            JSONObject().put("operation",java.util.UUID.randomUUID().toString()).put("identity",freshIdentity())
        check(prefs.edit().putString("pending:$uid",sealLocal(pending.toString().toByteArray(utf),uid)).commit()) { "无法安全保存新聊天身份" }
        val body=JSONObject(proof.toString());val pub=publicOf(pending.getJSONObject("identity"))
        body.put("operation",pending.getString("operation")).put("enc",pub.getString("enc")).put("sig",pub.getString("sig"))
        require(Hub.token==session&&Hub.me.optString("id")==uid)
        val response=Hub.rawApi("POST","/api/crypto/restart",body,session)
        require(response.getString("key_id")==pub.getString("key_id"))
        clear();return response
    }
    @Synchronized fun ready(): JSONObject {
        val uid=Hub.me.optString("id");require(uid.isNotEmpty()) { "请先登录" }
        val session=Hub.token
        if(owner==uid && ownPublic!=null)return ownPublic!!
        val remote=Hub.rawApi("GET","/api/crypto/identity/$uid",session=session)
        var stored=prefs.getString("identity:$uid",null)
        prefs.getString("pending:$uid",null)?.let { sealed ->
            val pending=JSONObject(String(openLocal(sealed,uid),utf));val candidate=pending.getJSONObject("identity")
            if(remote.optString("key_id")==publicOf(candidate).getString("key_id")) {
                require(Hub.token==session&&Hub.me.optString("id")==uid)
                val saved=sealLocal(candidate.toString().toByteArray(utf),uid)
                check(prefs.edit().putString("identity:$uid",saved).remove("pending:$uid").commit())
                stored=saved
                LocalData.resetIdentity(uid)
                ConversationState.clearMemory()
            }
        }
        val identity=if(stored!=null)JSONObject(String(openLocal(stored!!,uid),utf)) else {
            if(remote.has("key_id"))throw Exception("此设备尚未迁移聊天身份，请在账号与安全中导入原设备备份")
            freshIdentity()
        }
        val pub=publicOf(identity)
        require(Hub.token==session && Hub.me.optString("id")==uid) { "登录状态已改变" }
        if(remote.has("key_id") && pub.getString("key_id")!=remote.getString("key_id"))throw Exception("聊天身份无法验证，请导入此账号的原设备备份")
        // Persist before publishing: a network failure must not lose an already-published private key.
        check(prefs.edit().putString("identity:$uid",sealLocal(identity.toString().toByteArray(utf),uid)).commit()) { "无法保存聊天身份" }
        Hub.rawApi("POST","/api/crypto/identity",pub,session)
        require(Hub.token==session && Hub.me.optString("id")==uid) { "登录状态已改变" }
        owner=uid;privateIdentity=identity;ownPublic=pub
        return pub
    }
    /** Called only after the person presses Add/Accept, never in response to a socket event. */
    @Synchronized fun adoptPeerAfterFriendAction(uid:String,session:String) {
        val me=Hub.me.getString("id");require(Hub.token==session)
        val p=Hub.rawApi("GET","/api/crypto/identity/$uid",session=session)
        if(!p.has("key_id"))return
        require(p.getString("key_id")==fingerprint(p.getString("enc"),p.getString("sig")))
        publicRead(p.getString("enc"));publicRead(p.getString("sig"))
        require(Hub.token==session&&Hub.me.optString("id")==me)
        val name="$me:$uid";val old=prefs.getString("peer:$name",null);val edit=prefs.edit()
        if(old!=null)edit.putBoolean("known:$name:$old",true)
        check(edit.putString("peer:$name",p.getString("key_id")).commit())
        peers.keys.filter { it==name||it.startsWith("$name:") }.forEach{peers.remove(it)}
        peers[name]=p
    }
    private fun peer(uid: String,requested:String=""): JSONObject {
        val me=Hub.me.getString("id");if(uid==me)return ready()
        val session=Hub.token
        val name="$me:$uid"
        val activePin=prefs.getString("peer:$name",null)
        val historical=requested.isNotEmpty()&&activePin!=null&&requested!=activePin
        if(historical)require(prefs.getBoolean("known:$name:$requested",false)) { "无法验证历史聊天身份" }
        val cacheName=if(historical)"$name:$requested" else name
        peers[cacheName]?.let { cached -> if(requested.isEmpty()||cached.optString("key_id")==requested)return cached }
        val p=Hub.rawApi("GET","/api/crypto/identity/$uid"+if(requested.isNotEmpty()&&activePin!=null)"?key_id=$requested" else "",session=session)
        if(!p.has("key_id"))throw Exception("对方需先升级 App 并登录，才能建立加密聊天")
        require(p.getString("key_id")==fingerprint(p.getString("enc"),p.getString("sig"))) { "无法验证聊天身份" }
        publicRead(p.getString("enc")); publicRead(p.getString("sig"))
        synchronized(this) {
            require(Hub.token==session && Hub.me.optString("id")==me) { "登录状态已改变" }
            val pin=prefs.getString("peer:$name",null)
            require(requested.isEmpty()||requested==p.getString("key_id")) { "无法验证历史聊天身份" }
            if(!historical) {
                if(pin!=null && pin!=p.getString("key_id"))throw Exception("无法建立安全连接，请稍后重试")
                check(prefs.edit().putString("peer:$name",p.getString("key_id")).commit())
            }
            peers[cacheName]=p
        }
        return p
    }
    private fun signed(e: JSONObject) = listOf("context","sender_key","recipient_key","sender_cipher","recipient_cipher").joinToString("\n") { e.getString(it) }.toByteArray(utf)
    private fun encrypt(peerId: String, value: JSONObject, context: String): JSONObject {
        val self=ready();val other=peer(peerId);val bytes=value.toString().toByteArray(utf);val aad=context.toByteArray(utf)
        val e=JSONObject().put("v",1).put("context",context).put("sender_key",self.getString("key_id")).put("recipient_key",other.getString("key_id"))
            .put("sender_cipher",b64(publicRead(self.getString("enc")).getPrimitive(HybridEncrypt::class.java).encrypt(bytes,aad)))
            .put("recipient_cipher",b64(publicRead(other.getString("enc")).getPrimitive(HybridEncrypt::class.java).encrypt(bytes,aad)))
        e.put("signature",b64(read(privateIdentity!!.getString("sig")).getPrimitive(PublicKeySign::class.java).sign(signed(e))))
        return e
    }
    private fun decrypt(e: JSONObject, sender: String, recipient: String, context: String): JSONObject {
        val me=Hub.me.getString("id");val mine=me==sender
        require(me==sender || me==recipient)
        val self=ready();val from=peer(sender,e.getString("sender_key"));val to=peer(recipient,e.getString("recipient_key"))
        require(e.getInt("v")==1 && e.getString("context")==context && e.getString("sender_key")==from.getString("key_id") && e.getString("recipient_key")==to.getString("key_id")) { "无法验证加密消息" }
        require(self.getString("key_id")==e.getString(if(mine)"sender_key" else "recipient_key"))
        publicRead(from.getString("sig")).getPrimitive(PublicKeyVerify::class.java).verify(un64(e.getString("signature")),signed(e))
        val bytes=read(privateIdentity!!.getString("enc")).getPrimitive(HybridDecrypt::class.java).decrypt(un64(e.getString(if(mine)"sender_cipher" else "recipient_cipher")),context.toByteArray(utf))
        return JSONObject(String(bytes,utf))
    }
    fun outgoing(p: JSONObject): JSONObject {
        val uid=Hub.me.getString("id");val id=p.getString("client_id");val recipient=p.getString("recipient");val fid=p.optString("file_id")
        val inner=JSONObject().put("type",p.getString("type")).put("text",p.optString("text"))
        val maximum=if(inner.getString("type")=="html")50000 else 5000
        require(inner.getString("text").length<=maximum) { "消息过长" }
        p.optJSONObject("private_file")?.let { inner.put("file",it) }
        p.optJSONObject("quote")?.let{inner.put("quote",it)}
        PayloadRules.validate(inner,fid,setOf(uid,recipient))
        val context="qingliao:message:1:$uid:$recipient:$id:$fid"
        return JSONObject().put("recipient",recipient).put("client_id",id).put("type","encrypted").put("envelope",encrypt(recipient,inner,context)).apply { if(fid.isNotEmpty())put("file_id",fid) }
    }
    fun incoming(m: JSONObject): JSONObject {
        if(m.optString("type")!="encrypted")return m
        val result=JSONObject(m.toString())
        for(name in listOf("type","text","file","quote","e2ee","decrypt_failed","pending","send_state","send_error","local_cache","withdrawn"))result.remove(name)
        try {
            val sender=m.getString("sender");val recipient=m.getString("recipient");val fid=if(m.isNull("file_id"))"" else m.optString("file_id")
            val context="qingliao:message:1:$sender:$recipient:${m.getString("client_id")}:$fid"
            val inner=decrypt(m.getJSONObject("envelope"),sender,recipient,context)
            PayloadRules.validate(inner,fid,setOf(sender,recipient))
            result.put("type",inner.getString("type")).put("text",inner.optString("text")).put("e2ee",true)
            inner.optJSONObject("quote")?.let{result.put("quote",it)}
            if(fid.isNotEmpty()) {
                val f=inner.getJSONObject("file");require(f.getString("id")==fid)
                result.put("file",f.put("available",m.optBoolean("attachment_available",true)))
            }
        } catch(_:Exception) {
            result.put("type","text").put("text","[加密消息暂时无法读取，请检查聊天身份]").put("decrypt_failed",true)
            result.remove("file")
            result.remove("quote")
        }
        result.remove("envelope")
        return result
    }
    fun transform(p: JSONObject): JSONObject {
        if(p.has("conversations"))p.getJSONArray("conversations").let { a -> for(i in 0 until a.length()){val c=a.getJSONObject(i);c.put("last",incoming(c.getJSONObject("last")))} }
        if(p.has("messages"))p.getJSONArray("messages").let { a -> for(i in 0 until a.length())a.put(i,incoming(a.getJSONObject(i))) }
        return if(p.optString("type")=="encrypted")incoming(p) else p
    }
    fun sealSignal(p: JSONObject,call: JSONObject): JSONObject {
        val me=Hub.me.getString("id");val other=if(call.getString("caller")==me)call.getString("callee") else call.getString("caller")
        return JSONObject().put("envelope",encrypt(other,p,"qingliao:call:1:${call.getString("id")}:$me:$other"))
    }
    fun openSignal(p: JSONObject,call: JSONObject): JSONObject {
        val me=Hub.me.getString("id");val other=if(call.getString("caller")==me)call.getString("callee") else call.getString("caller")
        return decrypt(p.getJSONObject("envelope"),other,me,"qingliao:call:1:${call.getString("id")}:$other:$me")
    }
    fun fileKey(): String = serialize(KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_1MB")))
    fun encryptFile(input: InputStream, output: OutputStream,key: String,context: String) {
        read(key).getPrimitive(StreamingAead::class.java).newEncryptingStream(output,context.toByteArray(utf)).use { out ->
            val buffer=ByteArray(65536);var total=0L
            while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=25L*1024*1024) { "单个文件不能超过 25 MB" };out.write(buffer,0,n)}
            require(total>0) { "不能发送空文件" }
        }
    }
    fun decryptFile(input: InputStream,output: OutputStream,key: String,context: String) {
        read(key).getPrimitive(StreamingAead::class.java).newDecryptingStream(input,context.toByteArray(utf)).use { it.copyTo(output) }
    }
    fun exportIdentity(passphrase: String): String {
        ready();require(passphrase.length>=12) { "迁移口令至少 12 位，与登录密码分开设置" }
        val uid=Hub.me.getString("id");val salt=ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key=backupKey(passphrase,salt)
        val c=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key);updateAAD(uid.toByteArray(utf)) }
        val pins=JSONObject()
        prefs.all.filterKeys { it.startsWith("peer:$uid:") }.forEach { (name,value) -> pins.put(name.removePrefix("peer:$uid:"),value) }
        val known=JSONObject()
        prefs.all.filterKeys { it.startsWith("known:$uid:") }.forEach { (name,value) -> if(value==true)known.put(name.removePrefix("known:$uid:"),true) }
        val transfer=JSONObject(privateIdentity?.toString() ?: throw Exception("登录状态已改变")).put("pins",pins).put("known",known)
        return JSONObject().put("v",2).put("uid",uid).put("salt",b64(salt)).put("iv",b64(c.iv)).put("data",b64(c.doFinal(transfer.toString().toByteArray(utf)))).toString()
    }
    private fun backupKey(password: String,salt: ByteArray): SecretKey {
        val spec=PBEKeySpec(password.toCharArray(),salt,600000,256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES") } finally { spec.clearPassword() }
    }
    @Synchronized fun importIdentity(text: String,passphrase: String) {
        require(text.toByteArray(utf).size<=512*1024) { "备份文件过大" }
        val p=JSONObject(text);val uid=Hub.me.getString("id")
        val session=Hub.token
        require(p.getInt("v") in 1..2 && p.getString("uid")==uid) { "请选择此账号的聊天身份备份" }
        val salt=un64(p.getString("salt"));val iv=un64(p.getString("iv"));require(salt.size==16 && iv.size==12)
        val c=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,backupKey(passphrase,salt),GCMParameterSpec(128,iv));updateAAD(uid.toByteArray(utf)) }
        val identity=JSONObject(String(c.doFinal(un64(p.getString("data"))),utf))
        val pub=publicOf(identity);val remote=Hub.rawApi("GET","/api/crypto/identity/$uid",session=session)
        require(remote.getString("key_id")==pub.getString("key_id")) { "备份身份不匹配" }
        require(Hub.token==session && Hub.me.optString("id")==uid) { "登录状态已改变" }
        val pins=identity.optJSONObject("pins")
        val known=identity.optJSONObject("known")
        require((pins?.length()?:0)<=500&&(known?.length()?:0)<=5000) { "备份中的联系人过多" }
        val history=mutableListOf<String>()
        known?.keys()?.forEach { name -> require(name.matches(Regex("[0-9]{8}:[0-9a-f]{64}"))&&known.get(name)==true);history.add(name) }
        val restored=mutableMapOf<String,String>()
        pins?.keys()?.forEach { peer -> val pin=pins.getString(peer);require(peer.matches(Regex("[0-9]{8}")) && pin.matches(Regex("[0-9a-f]{64}")));restored[peer]=pin }
        identity.remove("pins");identity.remove("known")
        val editor=prefs.edit().putString("identity:$uid",sealLocal(identity.toString().toByteArray(utf),uid))
        // Merge known history, but never silently replace an existing active trust pin on import.
        restored.forEach { (peer,pin) ->
            val existing=prefs.getString("peer:$uid:$peer",null)
            if(existing==null||existing==pin)editor.putString("peer:$uid:$peer",pin)
            else editor.putBoolean("known:$uid:$peer:$pin",true)
        }
        history.forEach { editor.putBoolean("known:$uid:$it",true) }
        check(editor.commit())
        clear()
    }
}
