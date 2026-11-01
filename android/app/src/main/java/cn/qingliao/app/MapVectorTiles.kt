package cn.qingliao.app

import android.net.Uri
import okhttp3.*
import org.json.*
import java.io.*
import java.util.concurrent.TimeUnit

/** Credential-free, bounded vector basemap requests. No user position/name/identity parameters. */
object MapVectorTiles {
    const val origin="https://api.claudcode.xyz/xingyu/map-vector/"
    const val localOrigin="https://appassets.androidplatform.net/assets/location-map/vector/"
    @Volatile var client:OkHttpClient=OkHttpClient.Builder().cache(Cache(File(Hub.app.cacheDir,"location-map-vector"),24L*1024*1024)).followRedirects(false).followSslRedirects(false).connectTimeout(8,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()
    fun accepts(uri:Uri):Boolean {
        if(uri.scheme!="https"||uri.host!="api.claudcode.xyz"||uri.encodedUserInfo!=null||uri.port !in listOf(-1,443)||uri.query!=null||uri.fragment!=null)return false
        if(uri.encodedPath=="/xingyu/map-vector/planet.json")return true
        val m=Regex("/xingyu/map-vector/planet/([0-9]{8}_[0-9]{6}_pt)/([0-9]{1,2})/([0-9]{1,5})/([0-9]{1,5})\\.pbf").matchEntire(uri.encodedPath.orEmpty())?:return false
        val z=m.groupValues[2].toInt();return z in 0..14&&m.groupValues[3].toInt() in 0 until (1 shl z)&&m.groupValues[4].toInt() in 0 until (1 shl z)
    }
    fun fetch(uri:Uri,tag:Any,allowed:()->Boolean):ByteArray {
        require(accepts(uri));check(allowed());val request=Request.Builder().url(uri.toString()).header("User-Agent","Mistar/${BuildConfig.VERSION_NAME} (+https://github.com/usera3/mistar)").tag(tag).build()
        val data=client.newCall(request).execute().use{response->
            check(allowed()&&response.isSuccessful){"矢量地图暂不可用"};val body=response.body?:throw IOException("地图数据为空");require(body.contentLength()<=2*1024*1024)
            val out=ByteArrayOutputStream();body.byteStream().use{input->val b=ByteArray(8192);while(true){check(allowed());val n=input.read(b);if(n<0)break;require(out.size()+n<=2*1024*1024);out.write(b,0,n)}};out.toByteArray()
        }
        if(uri.lastPathSegment!="planet.json")return data
        // Never pass provider-supplied URLs, HTML or other metadata into the WebView.
        val source=JSONObject(String(data,Charsets.UTF_8));val urls=source.getJSONArray("tiles");require(urls.length()==1)
        val path=Regex("https://tiles\\.openfreemap\\.org/planet/([0-9]{8}_[0-9]{6}_pt)/\\{z\\}/\\{x\\}/\\{y\\}\\.pbf").matchEntire(urls.getString(0))?:throw IOException("地图来源不正确")
        return JSONObject().put("tilejson","3.0.0").put("minzoom",0).put("maxzoom",14).put("tiles",JSONArray().put("mistar-vector://planet/"+path.groupValues[1]+"/{z}/{x}/{y}.pbf")).toString().toByteArray(Charsets.UTF_8)
    }
    fun cancel(tag:Any){(client.dispatcher.runningCalls()+client.dispatcher.queuedCalls()).filter{it.request().tag()===tag}.forEach{it.cancel()}}
}
