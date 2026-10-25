package cn.qingliao.app

import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.*
import org.json.*
import java.security.KeyStore
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

/** Device-private encrypted cache and drafts; owner/kind/id are authenticated as associated data. */
object LocalData {
    val worker=Executors.newSingleThreadExecutor()
    private val epochs=ConcurrentHashMap<String,AtomicLong>()
    fun epoch(owner:String)=epochs.getOrPut(owner){AtomicLong()}.get()
    fun changed(owner:String)=epochs.getOrPut(owner){AtomicLong()}.incrementAndGet()
    private val db:SQLiteDatabase by lazy {
        Hub.app.openOrCreateDatabase("chat-local.sqlite",Context.MODE_PRIVATE,null).apply{
            enableWriteAheadLogging()
            execSQL("CREATE TABLE IF NOT EXISTS cache(owner TEXT,mid TEXT,peer TEXT,created INTEGER,body BLOB,n INTEGER,withdrawn INTEGER,PRIMARY KEY(owner,mid))")
            execSQL("CREATE INDEX IF NOT EXISTS local_pair ON cache(owner,peer,created)")
            execSQL("CREATE TABLE IF NOT EXISTS hidden(owner TEXT,mid TEXT,created INTEGER,PRIMARY KEY(owner,mid))")
            execSQL("CREATE TABLE IF NOT EXISTS clears(owner TEXT,peer TEXT,through INTEGER,PRIMARY KEY(owner,peer))")
            execSQL("CREATE TABLE IF NOT EXISTS versions(owner TEXT PRIMARY KEY,revision INTEGER)")
            execSQL("CREATE TABLE IF NOT EXISTS file_flags(owner TEXT,fid TEXT,created INTEGER,PRIMARY KEY(owner,fid))")
            execSQL("CREATE TABLE IF NOT EXISTS home(owner TEXT PRIMARY KEY,body BLOB,created INTEGER)")
            execSQL("CREATE TABLE IF NOT EXISTS voice_text(owner TEXT,mid TEXT,fid TEXT,peer TEXT,created INTEGER,updated INTEGER,body BLOB,n INTEGER,PRIMARY KEY(owner,mid))")
            execSQL("CREATE TABLE IF NOT EXISTS outbox(owner TEXT,client TEXT,peer TEXT,created INTEGER,body BLOB,n INTEGER,state TEXT,attempts INTEGER,next INTEGER,error TEXT,PRIMARY KEY(owner,client))")
            execSQL("UPDATE outbox SET state='queued' WHERE state='sending'")
        }
    }
    private fun scope(owner:String){require(Hub.token.isNotEmpty()&&owner==Hub.me.optString("id")){"登录状态已改变"}}
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey("qingliao.local.v1",null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder("qingliao.local.v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
    }
    private fun seal(owner:String,kind:String,id:String,value:JSONObject):ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key());updateAAD("$owner|$kind|$id".toByteArray())}
        return cipher.iv+cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
    }
    private fun open(owner:String,kind:String,id:String,bytes:ByteArray):JSONObject {
        require(bytes.size in 29..524288)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)));updateAAD("$owner|$kind|$id".toByteArray())}
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
    }
    private fun number(sql:String,vararg values:String):Long=db.rawQuery(sql,values).use{if(it.moveToFirst())it.getLong(0)else 0L}
    @Synchronized fun put(owner:String,message:JSONObject,stamp:Long=epoch(owner)) {
        scope(owner);if(stamp!=epoch(owner)||message.optBoolean("pending")||message.optBoolean("removed"))return
        val id=message.getString("id");val peer=if(message.optString("sender")==owner)message.getString("recipient")else message.getString("sender")
        if(message.optBoolean("withdrawn"))db.execSQL("DELETE FROM voice_text WHERE owner=? AND mid=?",arrayOf(owner,id))
        if(number("SELECT count(*) FROM hidden WHERE owner=? AND mid=?",owner,id)>0)return
        if(message.optLong("created")<=number("SELECT through FROM clears WHERE owner=? AND peer=?",owner,peer))return
        if(!message.optBoolean("withdrawn")&&number("SELECT withdrawn FROM cache WHERE owner=? AND mid=?",owner,id)>0)return
        val bytes=seal(owner,"cache",id,message);require(bytes.size<=256*1024)
        db.insertWithOnConflict("cache",null,ContentValues().apply{put("owner",owner);put("mid",id);put("peer",peer);put("created",message.optLong("created"));put("body",bytes);put("n",bytes.size);put("withdrawn",if(message.optBoolean("withdrawn"))1 else 0)},SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("DELETE FROM cache WHERE rowid IN (SELECT rowid FROM cache WHERE owner=? ORDER BY created DESC LIMIT -1 OFFSET 1000)",arrayOf(owner))
        while(number("SELECT coalesce(sum(n),0) FROM cache WHERE owner=?",owner)>10*1024*1024||number("SELECT coalesce(sum(n),0) FROM cache")>32*1024*1024)db.execSQL("DELETE FROM cache WHERE rowid IN (SELECT rowid FROM cache ORDER BY created LIMIT 64)")
    }
    @Synchronized fun messages(owner:String,peer:String,limit:Int=50,before:Long=Long.MAX_VALUE):JSONArray {
        scope(owner);val items=mutableListOf<JSONObject>()
        db.rawQuery("SELECT mid,body FROM cache WHERE owner=? AND peer=? AND created<? ORDER BY created DESC LIMIT ?",arrayOf(owner,peer,before.toString(),limit.coerceIn(1,1000).toString())).use{c->while(c.moveToNext())runCatching{open(owner,"cache",c.getString(0),c.getBlob(1))}.getOrNull()?.let{m->
            if(number("SELECT count(*) FROM file_flags WHERE owner=? AND fid=?",owner,m.optString("file_id"))>0){m.put("attachment_available",false);m.optJSONObject("file")?.put("available",false)}
            items.add(m)
        }}
        return JSONArray(items.reversed())
    }
    @Synchronized fun hide(owner:String,id:String) {
        scope(owner);db.execSQL("DELETE FROM cache WHERE owner=? AND mid=?",arrayOf(owner,id))
        db.execSQL("DELETE FROM voice_text WHERE owner=? AND mid=?",arrayOf(owner,id))
        db.execSQL("INSERT OR REPLACE INTO hidden VALUES(?,?,?)",arrayOf(owner,id,System.currentTimeMillis()))
        db.execSQL("DELETE FROM hidden WHERE rowid IN (SELECT rowid FROM hidden WHERE owner=? ORDER BY created DESC LIMIT -1 OFFSET 2000)",arrayOf(owner))
    }
    @Synchronized fun clear(owner:String,peer:String,through:Long) {
        scope(owner);db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO clears VALUES(?,?,?)",arrayOf(owner,peer,through))
            db.execSQL("UPDATE clears SET through=max(through,?) WHERE owner=? AND peer=?",arrayOf(through,owner,peer))
            db.execSQL("DELETE FROM cache WHERE owner=? AND peer=? AND created<=?",arrayOf(owner,peer,through))
            db.execSQL("DELETE FROM voice_text WHERE owner=? AND peer=? AND created<=?",arrayOf(owner,peer,through));db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    @Synchronized fun version(owner:String):Long {scope(owner);return number("SELECT revision FROM versions WHERE owner=?",owner)}
    @Synchronized fun acceptVersion(owner:String,state:JSONObject):Boolean {
        scope(owner);val revision=state.getLong("revision");val previous=version(owner)
        val invalid=previous!=revision
        if(invalid){changed(owner)
            if(state.optBoolean("reset")||revision<previous){db.execSQL("DELETE FROM cache WHERE owner=?",arrayOf(owner));db.execSQL("DELETE FROM voice_text WHERE owner=?",arrayOf(owner))}
            else state.optJSONArray("peers")?.let{peers->for(i in 0 until peers.length()){db.execSQL("DELETE FROM cache WHERE owner=? AND peer=?",arrayOf(owner,peers.getString(i)));db.execSQL("DELETE FROM voice_text WHERE owner=? AND peer=?",arrayOf(owner,peers.getString(i)))}}
            val oldHome=home(owner);val conversations=oldHome.optJSONArray("conversations")?:JSONArray()
            val affected=state.optJSONArray("peers")?:JSONArray();val peers=(0 until affected.length()).map{affected.getString(it)}.toSet()
            homePart(owner,"conversations",JSONArray((0 until conversations.length()).map{conversations.getJSONObject(it)}.filter{!state.optBoolean("reset")&&revision>=previous&&it.getJSONObject("user").optString("id") !in peers}))
        }
        db.execSQL("INSERT OR REPLACE INTO versions VALUES(?,?)",arrayOf(owner,revision));return invalid
    }
    @Synchronized fun clearHistory(owner:String){scope(owner);changed(owner);db.execSQL("DELETE FROM cache WHERE owner=?",arrayOf(owner));db.execSQL("DELETE FROM voice_text WHERE owner=?",arrayOf(owner))}
    @Synchronized fun historyBytes(owner:String):Long {scope(owner);return number("SELECT coalesce(sum(n),0) FROM cache WHERE owner=?",owner)+number("SELECT coalesce(sum(n),0) FROM voice_text WHERE owner=?",owner)}
    @Synchronized fun resetIdentity(owner:String){scope(owner);changed(owner);for(table in listOf("cache","outbox","versions","hidden","clears","file_flags","home","voice_text"))db.execSQL("DELETE FROM $table WHERE owner=?",arrayOf(owner))}
    @Synchronized fun fileGone(owner:String,fid:String){scope(owner);db.execSQL("DELETE FROM voice_text WHERE owner=? AND fid=?",arrayOf(owner,fid));db.execSQL("INSERT OR REPLACE INTO file_flags VALUES(?,?,?)",arrayOf(owner,fid,System.currentTimeMillis()));db.execSQL("DELETE FROM file_flags WHERE rowid IN (SELECT rowid FROM file_flags WHERE owner=? ORDER BY created DESC LIMIT -1 OFFSET 2000)",arrayOf(owner))}
    @Synchronized fun voiceTexts(owner:String,peer:String):JSONObject {
        scope(owner);db.execSQL("DELETE FROM voice_text WHERE updated<?",arrayOf(System.currentTimeMillis()-30L*86400000))
        val result=JSONObject()
        db.rawQuery("SELECT mid,fid,body FROM voice_text WHERE owner=? AND peer=?",arrayOf(owner,peer)).use{c->while(c.moveToNext())runCatching{open(owner,"voice_text",c.getString(0)+":"+c.getString(1),c.getBlob(2))}.getOrNull()?.let{result.put(c.getString(0),it)}}
        return result
    }
    @Synchronized fun putVoiceText(owner:String,peer:String,message:JSONObject,value:String,stamp:Long) {
        scope(owner);check(stamp==epoch(owner)){"聊天记录已改变，请重新转文字"}
        val id=message.getString("id");val fid=message.getJSONObject("file").getString("id")
        require(if(message.optString("sender")==owner)message.optString("recipient")==peer else message.optString("recipient")==owner&&message.optString("sender")==peer)
        require(value.isNotBlank()&&value.length<=5000&&message.optString("type")=="voice"&&!message.optBoolean("pending")&&!message.optBoolean("withdrawn")&&!message.optBoolean("decrypt_failed"))
        require(message.optBoolean("attachment_available",true)&&message.getJSONObject("file").optBoolean("available",true)&&fid.matches(Regex("[a-f0-9]{32}")))
        check(number("SELECT count(*) FROM hidden WHERE owner=? AND mid=?",owner,id)==0L&&number("SELECT count(*) FROM file_flags WHERE owner=? AND fid=?",owner,fid)==0L)
        check(message.optLong("created")>number("SELECT through FROM clears WHERE owner=? AND peer=?",owner,peer))
        val bytes=seal(owner,"voice_text","$id:$fid",JSONObject().put("file_id",fid).put("text",value))
        db.execSQL("INSERT OR REPLACE INTO voice_text VALUES(?,?,?,?,?,?,?,?)",arrayOf(owner,id,fid,peer,message.optLong("created"),System.currentTimeMillis(),bytes,bytes.size))
        db.execSQL("DELETE FROM voice_text WHERE rowid IN (SELECT rowid FROM voice_text WHERE owner=? ORDER BY updated DESC LIMIT -1 OFFSET 500)",arrayOf(owner))
        while(number("SELECT coalesce(sum(n),0) FROM voice_text WHERE owner=?",owner)>2*1024*1024||number("SELECT coalesce(sum(n),0) FROM voice_text")>8*1024*1024)db.execSQL("DELETE FROM voice_text WHERE rowid IN (SELECT rowid FROM voice_text ORDER BY updated LIMIT 32)")
    }
    @Synchronized fun home(owner:String):JSONObject {
        scope(owner);return db.rawQuery("SELECT body FROM home WHERE owner=?",arrayOf(owner)).use{if(it.moveToFirst())runCatching{open(owner,"home",owner,it.getBlob(0))}.getOrDefault(JSONObject())else JSONObject()}
    }
    @Synchronized fun homePart(owner:String,key:String,array:JSONArray) {
        scope(owner);val value=home(owner)
        val items=if(key=="contacts")array else JSONArray((0 until minOf(array.length(),100)).map{i->
            val c=array.getJSONObject(i);val m=c.getJSONObject("last")
            JSONObject().put("user",c.getJSONObject("user")).put("unread",c.optInt("unread")).put("last",JSONObject().put("type",m.optString("type")).put("text",if(m.optString("type")=="html")"" else m.optString("text").take(160)).put("created",m.optLong("created")).apply{m.optJSONObject("file")?.let{put("file",JSONObject().put("name",it.optString("name").take(80)))}})
        })
        value.put(key,items);val bytes=seal(owner,"home",owner,value);if(bytes.size>512*1024)return
        db.execSQL("INSERT OR REPLACE INTO home VALUES(?,?,?)",arrayOf(owner,bytes,System.currentTimeMillis()));db.execSQL("DELETE FROM home WHERE rowid IN (SELECT rowid FROM home ORDER BY created DESC LIMIT -1 OFFSET 10)")
    }
    @Synchronized fun queue(owner:String,payload:JSONObject):JSONObject {
        scope(owner);val client=payload.getString("client_id");require(client.length in 1..80)
        val old=outgoing(owner,client);if(old!=null){require(old.optString("recipient")==payload.optString("recipient")&&old.optString("type")==payload.optString("type")&&old.optString("text")==payload.optString("text")&&old.optString("file_id")==payload.optString("file_id")){"发送标识已被使用"};return old}
        val bytes=seal(owner,"outbox",client,payload);require(bytes.size<=256*1024)
        require(number("SELECT count(*) FROM outbox WHERE owner=?",owner)<100&&number("SELECT coalesce(sum(n),0) FROM outbox WHERE owner=?",owner)+bytes.size<=5*1024*1024&&number("SELECT coalesce(sum(n),0) FROM outbox")+bytes.size<=10*1024*1024){"待发送消息空间已满，请先处理或取消一些消息"}
        val created=System.currentTimeMillis()
        db.execSQL("INSERT INTO outbox VALUES(?,?,?,?,?,?,'queued',0,0,'')",arrayOf(owner,client,payload.getString("recipient"),created,bytes,bytes.size))
        return outgoing(owner,client)!!
    }
    @Synchronized fun outgoing(owner:String,client:String):JSONObject? {
        scope(owner)
        return db.rawQuery("SELECT body,created,state,attempts,error,peer FROM outbox WHERE owner=? AND client=?",arrayOf(owner,client)).use{c->
            if(!c.moveToFirst())null else {
                val decoded=runCatching{open(owner,"outbox",client,c.getBlob(0))}.getOrNull()
                if(decoded==null){state(owner,client,"blocked",c.getInt(3),0,"本机记录无法读取，请删除后重新输入")
                    JSONObject().put("client_id",client).put("recipient",c.getString(5)).put("type","text").put("text","[本机待发内容无法读取]").put("local_unreadable",true).put("local_created",c.getLong(1)).put("local_state","blocked").put("local_error","本机记录无法读取，请删除后重新输入")
                }else decoded.put("local_created",c.getLong(1)).put("local_state",c.getString(2)).put("local_attempts",c.getInt(3)).put("local_error",c.getString(4))
            }
        }
    }
    @Synchronized fun pending(owner:String,peer:String?=null):JSONArray {
        scope(owner);val ids=mutableListOf<String>()
        db.rawQuery("SELECT client FROM outbox WHERE owner=?"+if(peer==null)" ORDER BY created" else " AND peer=? ORDER BY created",if(peer==null)arrayOf(owner)else arrayOf(owner,peer)).use{c->while(c.moveToNext())ids.add(c.getString(0))}
        return JSONArray(ids.mapNotNull{outgoing(owner,it)})
    }
    @Synchronized fun next(owner:String):JSONObject? {
        scope(owner)
        val id=db.rawQuery("SELECT client FROM outbox WHERE owner=? AND state IN ('queued','failed') AND next<=? ORDER BY created LIMIT 1",arrayOf(owner,System.currentTimeMillis().toString())).use{if(it.moveToFirst())it.getString(0)else null}?:return null
        return outgoing(owner,id)
    }
    @Synchronized fun hasRetry(owner:String):Boolean {scope(owner);return number("SELECT count(*) FROM outbox WHERE owner=? AND state IN ('queued','failed')",owner)>0}
    @Synchronized fun state(owner:String,client:String,state:String,attempts:Int,next:Long,error:String="") {
        db.execSQL("UPDATE outbox SET state=?,attempts=?,next=?,error=? WHERE owner=? AND client=?",arrayOf(state,attempts,next,error.take(120),owner,client))
    }
    @Synchronized fun remove(owner:String,client:String){db.execSQL("DELETE FROM outbox WHERE owner=? AND client=?",arrayOf(owner,client))}
    @Synchronized fun retry(owner:String,client:String){scope(owner);db.execSQL("UPDATE outbox SET state='queued',attempts=0,next=0,created=? WHERE owner=? AND client=?",arrayOf(System.currentTimeMillis(),owner,client))}
}
