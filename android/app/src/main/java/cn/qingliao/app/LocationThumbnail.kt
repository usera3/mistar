package cn.qingliao.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.*
import android.widget.*
import org.json.JSONObject

class LocationThumbnail(context:Context,private val p:ThemePalette,private val point:JSONObject,private val open:()->Unit):FrameLayout(context){
    private val session=Hub.token;private val owner=Hub.me.optString("id");private var active=false;private var loading=false;private var failed=false;private var generation=0
    private var bitmap:Bitmap?=null
    private val image=ImageView(context).apply{scaleType=ImageView.ScaleType.CENTER_CROP}
    private val note=FormUi(context,p).text("地图加载中…",11f,p.muted).apply{gravity=Gravity.CENTER}
    init{
        tag="location.thumbnail";background=BrandUi.rounded(context,p.soft,12);clipToOutline=true
        addView(image,LayoutParams(-1,-1));addView(note,LayoutParams(-1,-1))
        addView(FormUi(context,p).text("© OpenStreetMap contributors",9f,android.graphics.Color.DKGRAY).apply{setBackgroundColor(0xe6ffffff.toInt());setPadding(4,2,4,2);contentDescription="OpenStreetMap版权与许可";setOnClickListener{runCatching{context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://www.openstreetmap.org/copyright")))}}},LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END))
        contentDescription="位置地图预览，点按展开";isFocusable=true;setOnClickListener{open()}
    }
    private fun permitted(g:Int)=active&&generation==g&&session==Hub.token&&owner==Hub.me.optString("id")
    fun update(){
        val visible=Hub.foreground&&isAttachedToWindow&&getGlobalVisibleRect(Rect())
        if(!visible){pause();return};active=true
        if(loading||bitmap!=null||failed)return
        val g=++generation;loading=true;note.text="地图加载中…"
        try{MapTiles.executor.execute{
            try{val result=MapTiles.thumbnail(point,this,{permitted(g)},p.accent)
                Hub.main.post{if(permitted(g)){loading=false;bitmap=result;image.setImageBitmap(result);note.visibility=View.GONE;Motion.image(image)}else result.recycle()}
            }catch(_:Exception){Hub.main.post{if(permitted(g)){loading=false;failed=true;note.text="地图暂不可用 · 点按查看"}}}
        }}catch(_:Exception){loading=false;failed=true;note.text="地图暂忙 · 点按查看"}
    }
    fun pause(){if(!active&&bitmap==null&&!loading)return;active=false;generation++;loading=false;MapTiles.cancel(this);image.setImageDrawable(null);bitmap=null;note.visibility=View.VISIBLE}
    override fun onDetachedFromWindow(){pause();super.onDetachedFromWindow()}
}
