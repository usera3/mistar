package cn.qingliao.app

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Distribution is independent of the chat client: no bearer, cookies or private CA. */
data class AppRelease(val code:Int,val version:String,val size:Long,val digest:String,val url:String,val notes:List<String>,val published:Long) {
    fun json()=JSONObject().put("schema",1).put("package","cn.qingliao.app").put("version_code",code).put("version",version)
        .put("size",size).put("sha256",digest).put("url",url).put("notes",org.json.JSONArray(notes)).put("min_sdk",26)
        .put("certificate_sha256",AppUpdates.CERTIFICATE).put("published_at",published)
    companion object {
        fun parse(p:JSONObject):AppRelease {
            fun integer(key:String):Long { val n=p.get(key);require(n is Int||n is Long);return (n as Number).toLong() }
            val code=integer("version_code");val version=p.get("version");require(code in 1..1000000&&version is String&&version.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")))
            val size=integer("size");val date=integer("published_at");require(size in 1..128L*1024*1024&&date>0&&integer("min_sdk")==26L)
            require(integer("schema")==1L&&p.get("package")=="cn.qingliao.app"&&p.get("certificate_sha256")==AppUpdates.CERTIFICATE)
            val url=AppUpdates.RELEASES+"xingyu-$version-$code.apk";require(p.get("url")==url)
            val hash=p.get("sha256");require(hash is String&&hash.matches(Regex("[a-f0-9]{64}")))
            val a=p.getJSONArray("notes");require(a.length() in 1..12)
            val notes=(0 until a.length()).map { val s=a.get(it);require(s is String&&s.length in 1..180&&s.none{c->c.code<32});s }
            return AppRelease(code.toInt(),version,size,hash,url,notes,date)
        }
    }
}

object AppUpdates {
    const val PAGE="https://api.claudcode.xyz/xingyu/download/"
    const val RELEASES="https://api.claudcode.xyz/xingyu/releases/"
    const val CERTIFICATE="a38b07b5c3fbf83f3f054240773df907d3ba5d990119ac74d06bd4358ad2d7f1"
    private val worker=Executors.newSingleThreadExecutor()
    internal var transport=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(600,TimeUnit.SECONDS).build()
    private val prefs get()=Hub.app.getSharedPreferences("app_updates",Context.MODE_PRIVATE)
    var latest:AppRelease?=null;private set
    val available get()=latest?.takeIf{it.code>BuildConfig.VERSION_CODE}
    var checking=false;private set
    var downloading=false;private set
    var progress=0;private set
    var error="";private set
    private var lastCheck=0L
    private var lastAttempt=0L
    private var pendingHint=0
    private var hintRetry:Runnable?=null
    @Volatile private var generation=0L
    private var downloadCall:Call?=null
    private var ready:Pair<AppRelease,File>?=null
    private val observers=mutableSetOf<()->Unit>()
    val notificationsEnabled get()=prefs.getBoolean("notify",true)
    private val dir get()=File(Hub.app.cacheDir,"updates").apply{mkdirs()}
    fun init() {
        dir.listFiles()?.forEach{it.delete()}
        latest=runCatching{AppRelease.parse(JSONObject(prefs.getString("manifest","{}")!!))}.getOrNull()
        Hub.app.getSystemService(NotificationManager::class.java).apply{
            createNotificationChannel(NotificationChannel("updates","版本更新",NotificationManager.IMPORTANCE_DEFAULT))
            if(available==null)cancel(109)
        }
    }
    fun observe(listener:()->Unit){observers.add(listener)}
    fun unobserve(listener:()->Unit){observers.remove(listener)}
    private fun changed(){observers.toList().forEach{it()};Hub.emit(JSONObject().put("event","update.state"))}
    fun setNotifications(enabled:Boolean){prefs.edit().putBoolean("notify",enabled).apply();if(!enabled)Hub.app.getSystemService(NotificationManager::class.java).cancel(109)else notifyAvailable();changed()}
    /** Called on open/resume or a WebSocket hint, never from a polling timer. */
    fun check(manual:Boolean=false,hint:Int=0) {
        if(hint>pendingHint)pendingHint=hint
        val now=android.os.SystemClock.elapsedRealtime()
        if(checking)return
        if(!manual&&lastAttempt>0&&now-lastAttempt<10000){
            if(pendingHint>maxOf(BuildConfig.VERSION_CODE,latest?.code?:0)&&hintRetry==null){
                hintRetry=Runnable{hintRetry=null;check(hint=pendingHint)}.also{Hub.main.postDelayed(it,10000-(now-lastAttempt))}
            };return
        }
        if(!manual&&hint<=maxOf(BuildConfig.VERSION_CODE,latest?.code?:0)&&lastCheck>0&&now-lastCheck<15*60*1000)return
        checking=true;error="";lastAttempt=now;changed()
        worker.execute{
            val result=runCatching{
                val request=Request.Builder().url(RELEASES+"latest.json").header("Cache-Control","no-cache").build()
                val call=transport.newCall(request).apply{timeout().timeout(30,TimeUnit.SECONDS)}
                call.execute().use{response->
                    if(!response.isSuccessful)throw IOException("更新服务暂不可用，请稍后重试")
                    val body=response.body?:throw IOException("版本信息为空")
                    if(body.contentLength()>8192)throw IOException("版本信息异常")
                    val data=body.byteStream().use{it.readBytesBounded(8192)}
                    AppRelease.parse(JSONObject(String(data,Charsets.UTF_8)))
                }
            }
            Hub.main.post{
                checking=false
                result.onSuccess{r->
                    lastCheck=android.os.SystemClock.elapsedRealtime()
                    if(latest==null||r.code>=latest!!.code){latest=r;prefs.edit().putString("manifest",r.json().toString()).apply()}
                    notifyAvailable()
                }.onFailure{error=if(it is IOException)it.message.orEmpty().take(100)else "版本信息校验失败，请稍后重试"}
                changed()
                // One bounded retry handles a release published while an earlier check was in flight.
                if(result.isSuccess&&pendingHint>maxOf(BuildConfig.VERSION_CODE,latest?.code?:0)){
                    pendingHint=0
                    hintRetry=Runnable{hintRetry=null;check(manual=true,hint=0)}.also{Hub.main.postDelayed(it,10000)}
                }else pendingHint=0
            }
        }
    }
    private fun java.io.InputStream.readBytesBounded(limit:Int):ByteArray {
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
        while(true){val n=read(buffer);if(n<0)break;if(output.size()+n>limit)throw IOException("版本信息异常");output.write(buffer,0,n)}
        return output.toByteArray()
    }
    internal fun notifyAvailable() {
        val release=available?:return
        val manager=Hub.app.getSystemService(NotificationManager::class.java)
        if(!notificationsEnabled||!manager.areNotificationsEnabled()||manager.getNotificationChannel("updates")?.importance==NotificationManager.IMPORTANCE_NONE||prefs.getInt("notified",0)>=release.code)return
        val open=PendingIntent.getActivity(Hub.app,109,Intent(Hub.app,UpdateActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(Hub.app,"updates").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("弥星 ${release.version} 可以更新了").setContentText("轻点查看更新内容与下载安装")
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION).build()
        try{manager.notify(109,notification);prefs.edit().putInt("notified",release.code).apply()}catch(_:SecurityException){}
    }
    fun downloaded(release:AppRelease)=ready?.takeIf{it.first==release&&it.second.isFile}?.second
    fun download(release:AppRelease) {
        if(downloading||release.code<=BuildConfig.VERSION_CODE)return
        generation++;val own=generation;downloading=true;progress=0;error="";ready=null;changed()
        worker.execute{
            val partial=File(dir,"download.part");val target=File(dir,"update.apk")
            val result=runCatching{
                dir.listFiles()?.forEach{it.delete()}
                val call=transport.newCall(Request.Builder().url(release.url).build())
                synchronized(this){if(own!=generation)throw IOException("已取消下载");downloadCall=call}
                call.execute().use{response->
                    if(!response.isSuccessful)throw IOException("下载失败，请稍后重试")
                    val body=response.body?:throw IOException("安装包为空")
                    if(body.contentLength()!=-1L&&body.contentLength()!=release.size)throw IOException("安装包大小不符")
                    val hash=MessageDigest.getInstance("SHA-256");var size=0L;var lastProgress=0L
                    body.byteStream().use{input->partial.outputStream().use{output->
                        val buffer=ByteArray(65536)
                        while(true){
                            if(own!=generation||call.isCanceled())throw IOException("已取消下载")
                            val n=input.read(buffer);if(n<0)break;size+=n
                            if(size>release.size)throw IOException("安装包超出声明大小")
                            output.write(buffer,0,n);hash.update(buffer,0,n)
                            val now=android.os.SystemClock.elapsedRealtime()
                            if(now-lastProgress>200){lastProgress=now;val percentage=(size*100/release.size).toInt();Hub.main.post{if(own==generation){progress=percentage;changed()}}}
                        }
                    }}
                    if(size!=release.size||hex(hash.digest())!=release.digest)throw IOException("安装包完整性校验失败")
                }
                verifyApk(partial,release)
                synchronized(this){if(own!=generation)throw IOException("已取消下载");if(!partial.renameTo(target))throw IOException("无法保存安装包")}
                target
            }
            partial.delete()
            Hub.main.post{
                if(own==generation){downloading=false;downloadCall=null
                    result.onSuccess{ready=release to it;progress=100}.onFailure{error=if(it is IOException)it.message.orEmpty().take(100)else "安装包身份校验失败";target.delete()}
                    changed()
                }
            }
        }
    }
    fun cancelDownload(){synchronized(this){generation++;downloadCall?.cancel();downloadCall=null};if(downloading){downloading=false;progress=0;error="已取消下载";changed()}}
    internal fun verifyApk(file:File,release:AppRelease) {
        val pm=Hub.app.packageManager
        @Suppress("DEPRECATION")
        // Android 9's archive parser only collects certificates with GET_SIGNATURES also set.
        val info=pm.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNATURES or if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else 0)?:throw IOException("安装包无法识别")
        @Suppress("DEPRECATION") val code=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
        require(info.packageName==Hub.app.packageName&&code==release.code.toLong()&&code>BuildConfig.VERSION_CODE&&info.versionName==release.version)
        @Suppress("DEPRECATION") val signatures=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures
        require(signatures?.size==1&&hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()))==CERTIFICATE)
    }
    internal fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
}
