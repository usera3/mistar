package cn.qingliao.app

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject

object LocationMaps {
    fun web(p:JSONObject):Uri {LocationPayload.validate(p);return Uri.parse("https://api.map.baidu.com/marker").buildUpon().appendQueryParameter("location",LocationPayload.coordinates(p).replace(" ","")).appendQueryParameter("coord_type","wgs84").appendQueryParameter("title",p.getString("label")).appendQueryParameter("content","弥星分享的位置").appendQueryParameter("output","html").appendQueryParameter("src","webapp.mistar.app").build()}
    fun open(activity:Activity,p:JSONObject){
        LocationPayload.validate(p)
        val session=Hub.token;val owner=Hub.me.optString("id");val value=JSONObject(p.toString())
        AlertDialog.Builder(activity).setTitle("选择地图").setItems(arrayOf("高德地图","百度地图","其他地图应用","网页地图")){_,which->
            if(activity.isFinishing||activity.isDestroyed||Hub.token!=session||Hub.me.optString("id")!=owner)return@setItems
            if(which==3){browser(activity,value);return@setItems}
            val intent=when(which){
                0->Intent(Intent.ACTION_VIEW,amap(value)).setPackage("com.autonavi.minimap")
                1->Intent(Intent.ACTION_VIEW,baidu(value)).setPackage("com.baidu.BaiduMap")
                else->Intent(Intent.ACTION_VIEW,Uri.parse("geo:${value.getDouble("lat")},${value.getDouble("lon")}").buildUpon().appendQueryParameter("q","${value.getDouble("lat")},${value.getDouble("lon")} (${value.getString("label")})").build())
            }
            try{activity.startActivity(intent)}catch(_:ActivityNotFoundException){Toast.makeText(activity,"未安装相应地图，可选择网页地图",Toast.LENGTH_SHORT).show()}
        }.setNegativeButton("取消",null).show()
    }
    fun amap(p:JSONObject):Uri {LocationPayload.validate(p);return Uri.parse("androidamap://viewMap").buildUpon().appendQueryParameter("sourceApplication","弥星").appendQueryParameter("poiname",p.getString("label")).appendQueryParameter("lat",p.getDouble("lat").toString()).appendQueryParameter("lon",p.getDouble("lon").toString()).appendQueryParameter("dev","1").build()}
    fun baidu(p:JSONObject):Uri {LocationPayload.validate(p);return Uri.parse("baidumap://map/marker").buildUpon().appendQueryParameter("location",LocationPayload.coordinates(p).replace(" ","")).appendQueryParameter("coord_type","wgs84").appendQueryParameter("title",p.getString("label")).appendQueryParameter("src","andr.cn.qingliao.app").build()}
    fun browser(activity:Activity,p:JSONObject){try{activity.startActivity(Intent(Intent.ACTION_VIEW,web(p)))}catch(_:Exception){Toast.makeText(activity,"没有可打开地图的浏览器",Toast.LENGTH_SHORT).show()}}
}
