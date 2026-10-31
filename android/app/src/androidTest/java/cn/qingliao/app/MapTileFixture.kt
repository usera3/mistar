package cn.qingliao.app

import android.graphics.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Explicit synthetic raster for deterministic native tests; never shipped in the main APK. */
object MapTileFixture {
    val requests=CopyOnWriteArrayList<String>()
    fun client():OkHttpClient {
        val image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);val c=Canvas(image);c.drawColor(Color.rgb(232,237,226));val pen=Paint(Paint.ANTI_ALIAS_FLAG)
        pen.color=Color.rgb(191,218,237);c.drawRect(150f,0f,220f,256f,pen);pen.color=Color.WHITE;pen.strokeWidth=15f
        listOf(32f,112f,236f).forEach{c.drawLine(0f,it,256f,it,pen);c.drawLine(it,0f,it,256f,pen)}
        pen.color=Color.rgb(108,114,119);pen.textSize=11f;c.drawText("SYNTHETIC TEST TILE",15f,22f,pen)
        val bytes=ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();image.recycle()
        return OkHttpClient.Builder().addInterceptor{chain->val request=chain.request();check(MapTiles.parse(android.net.Uri.parse(request.url.toString()))!=null);check(request.header("Authorization")==null&&request.header("Cookie")==null&&request.header("User-Agent")!!.startsWith("Mistar/"));requests.add(request.url.toString());Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic map fixture").header("Content-Type","image/png").header("Cache-Control","public, max-age=86400").body(bytes.toResponseBody("image/png".toMediaType())).build()}.build()
    }
}
