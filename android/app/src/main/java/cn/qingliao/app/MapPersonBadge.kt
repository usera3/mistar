package cn.qingliao.app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Private avatar stays inside native/local map memory; no avatar URL or credentials in the page. */
class MapPersonBadge(context:Context,private val p:ThemePalette,val user:JSONObject,changed:()->Unit):FrameLayout(context){
    private var encoded="";private var initialized=false
    private val fallback=FormUi(context,p).text(user.optString("nickname","友").take(1),17f,p.accent,true).apply{gravity=Gravity.CENTER}
    private val photo=object:ImageView(context){override fun setImageBitmap(b:Bitmap?){super.setImageBitmap(b);encoded="";if(initialized)Hub.main.post{changed()}}}.apply{scaleType=ImageView.ScaleType.CENTER_CROP;visibility=GONE}
    init{background=BrandUi.rounded(context,p.soft,24);clipToOutline=true;addView(fallback,LayoutParams(-1,-1));addView(photo,LayoutParams(-1,-1));initialized=true;AvatarImages.load(user.optString("avatar_id"),photo);contentDescription=user.optString("nickname","共享成员")}
    fun data():String {
        if(encoded.isNotEmpty())return encoded
        val image=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);val c=Canvas(image);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color=p.accent;c.drawCircle(32f,32f,32f,paint)
        val original=(photo.drawable as? BitmapDrawable)?.bitmap
        if(original!=null){val side=minOf(original.width,original.height);val left=(original.width-side)/2;val top=(original.height-side)/2;val path=Path().apply{addCircle(32f,32f,32f,Path.Direction.CW)};c.save();c.clipPath(path);c.drawBitmap(original,Rect(left,top,left+side,top+side),Rect(0,0,64,64),paint);c.restore()}
        else{paint.color=p.onPrimary;paint.textAlign=Paint.Align.CENTER;paint.textSize=30f;c.drawText(user.optString("nickname","友").take(1),32f,42f,paint)}
        val bytes=ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();image.recycle();encoded="data:image/png;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP);return encoded
    }
}
