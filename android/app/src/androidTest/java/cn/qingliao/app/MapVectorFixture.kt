package cn.qingliao.app

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream

/** Synthetic OpenMapTiles-schema geometry for real native WebGL rendering, test APK only. */
object MapVectorFixture {
    val requests=java.util.concurrent.atomic.AtomicInteger()
    val paths=java.util.concurrent.CopyOnWriteArrayList<String>()
    val failures=java.util.concurrent.atomic.AtomicInteger();val injected=java.util.concurrent.atomic.AtomicInteger()
    private fun varint(n:Long):ByteArray{var x=n;val b=ByteArrayOutputStream();do{val v=(x and 127).toInt();x=x ushr 7;b.write(v or if(x!=0L)128 else 0)}while(x!=0L);return b.toByteArray()}
    private fun scalar(k:Int,n:Long)=varint((k shl 3).toLong())+varint(n)
    private fun bytes(k:Int,b:ByteArray)=varint(((k shl 3) or 2).toLong())+varint(b.size.toLong())+b
    private fun text(k:Int,s:String)=bytes(k,s.toByteArray())
    private fun geometry(type:Int,points:List<Pair<Int,Int>>):ByteArray {
        val out=ByteArrayOutputStream();var x=0;var y=0
        fun write(n:Int){out.write(varint(n.toLong()))};fun delta(v:Int)=((v shl 1) xor (v shr 31))
        write(9);write(delta(points[0].first));write(delta(points[0].second));x=points[0].first;y=points[0].second
        if(points.size>1){write(((points.size-1) shl 3) or 2);for(p in points.drop(1)){write(delta(p.first-x));write(delta(p.second-y));x=p.first;y=p.second}}
        if(type==3)write(15);return out.toByteArray()
    }
    private fun layer(name:String,type:Int,points:List<Pair<Int,Int>>,attributes:Map<String,Any>):ByteArray {
        val tags=attributes.entries.flatMapIndexed{index,_->listOf(index,index)}.fold(ByteArray(0)){a,v->a+varint(v.toLong())}
        val feature=scalar(1,1)+bytes(2,tags)+scalar(3,type.toLong())+bytes(4,geometry(type,points))
        var layer=scalar(15,2)+text(1,name)+bytes(2,feature)
        attributes.forEach{(k,v)->layer+=text(3,k)+bytes(4,if(v is Number)scalar(5,v.toLong())else text(1,v.toString()))};return bytes(3,layer+scalar(5,4096))
    }
    val tile:ByteArray by lazy {
        layer("water",3,listOf(3000 to 0,4096 to 0,4096 to 4096,3000 to 4096),mapOf("class" to "river"))+
        layer("building",3,listOf(600 to 600,1800 to 600,1800 to 1800,600 to 1800),mapOf("render_height" to 6,"render_min_height" to 0))+
        layer("transportation",2,listOf(0 to 950,4096 to 950),mapOf("class" to "primary","ramp" to 0,"layer" to 0,"z_order" to 5))+
        layer("transportation_name",2,listOf(0 to 950,4096 to 950),mapOf("class" to "primary","name" to "合成测试街道","name:zh" to "合成测试街道"))+
        layer("poi",1,listOf(1200 to 1200),mapOf("class" to "park","rank" to 1,"name" to "合成公园","name:zh" to "合成公园"))
    }
    fun client()=OkHttpClient.Builder().addInterceptor{chain->requests.incrementAndGet();val request=chain.request();paths.add(request.url.encodedPath);check(MapVectorTiles.accepts(android.net.Uri.parse(request.url.toString())));check(request.header("Authorization")==null&&request.header("Cookie")==null)
        val metadata=request.url.encodedPath.endsWith("planet.json")
        val body=if(metadata)"{\"tiles\":[\"https://tiles.openfreemap.org/planet/20261025_080012_pt/{z}/{x}/{y}.pbf\"]}".toByteArray()else tile
        val failed=!metadata&&failures.getAndUpdate{if(it>0)it-1 else 0}>0;if(failed)injected.incrementAndGet()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(failed)503 else 200).message("Synthetic vector fixture").header("Cache-Control","public, max-age=86400").body(body.toResponseBody((if(metadata)"application/json"else"application/x-protobuf").toMediaType())).build()
    }.build()
}
