package cn.qingliao.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class StorageActivity : Activity() {
    private val session=Hub.token
    private val palette by lazy { ThemeStore.current(this) }
    private lateinit var body:LinearLayout
    private var busy=false
    private var next=""
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun valid()=!isFinishing&&!isDestroyed&&session.isNotEmpty()&&Hub.token==session
    private fun col()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    private fun label(value:String,size:Float=14f,color:Int=palette.ink,bold:Boolean=false)=TextView(this).apply {
        text=value;textSize=size;setTextColor(color);includeFontPadding=false;setPadding(0,dp(10),0,dp(10));if(bold)setTypeface(typeface,Typeface.BOLD)
    }
    private fun size(n:Long)=if(n>=1048576)"%.1f MB".format(n/1048576.0) else if(n>=1024)"%.1f KB".format(n/1024.0) else "$n B"
    override fun finish(){super.finish();Motion.activityClose(this)}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);if(!valid()){finish();return}
        window.statusBarColor=palette.canvas;window.navigationBarColor=palette.canvas
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=col().apply{setBackgroundColor(palette.canvas)}
        root.setOnApplyWindowInsetsListener { v,i ->
            if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom)}
            else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i
        }
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(palette.canvas);setPadding(dp(6),dp(6),dp(12),dp(6))}
        header.addView(FrameLayout(this).apply{contentDescription="返回";isFocusable=true;setOnClickListener{finish()};addView(Glyph(this@StorageActivity,"back"),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))},LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(label("存储空间",19f,bold=true));root.addView(header)
        body=col().apply{setPadding(dp(20),dp(14),dp(20),dp(28))};root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);root.requestApplyInsets();load()
    }
    override fun onResume(){super.onResume();if(Hub.token!=session)finish()}
    private fun task(work:()->JSONObject,done:(JSONObject)->Unit) {
        if(!valid()||busy)return;busy=true
        Hub.job({check(Hub.token==session);work()}, {busy=false;if(valid())done(it)}, {busy=false;if(valid()){Toast.makeText(this,it,Toast.LENGTH_LONG).show();action("重试"){load()}}})
    }
    private fun action(title:String,click:()->Unit) {
        body.addView(label(title,14f,palette.accent,true).apply{gravity=Gravity.CENTER;minimumHeight=dp(50);isFocusable=true
            background=BrandUi.card(this@StorageActivity,palette,18);setOnClickListener{if(!busy)click()}
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
    }
    private fun load() {
        task({Hub.rawApi("GET","/api/storage",session=session)}) { s ->
            body.removeAllViews();next=""
            body.addView(label("云端存储空间",15f,palette.muted))
            body.addView(label(size(s.getLong("used"))+" / "+size(s.getLong("quota")),25f,bold=true))
            val bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{progressTintList=android.content.res.ColorStateList.valueOf(palette.accent);progressBackgroundTintList=android.content.res.ColorStateList.valueOf(palette.soft);max=1000;progress=(s.getLong("used")*1000/s.getLong("quota").coerceAtLeast(1)).toInt()};body.addView(bar,LinearLayout.LayoutParams(-1,dp(8)))
            for((key,title) in listOf("sent" to "已发送附件","pending" to "未发送附件","avatar" to "头像","moments" to "动态附件"))body.addView(label("$title    ${size(s.getLong(key))}"))
            body.addView(label("今日已上传 ${size(s.getLong("upload_today"))} / ${size(s.getLong("upload_daily_limit"))}，北京时间每天 08:00 重置。清理空间不会重置上传额度。",12f,palette.muted))
            action("清理未发送的附件（${s.getInt("pending_files")}）") {
                confirm("清理未发送附件？","正在准备发送的附件也可能被清理。已发送消息不受影响。") {
                    task({Hub.rawApi("POST","/api/storage/cleanup",session=session)}){load()}
                }
            }
            action("清理本机缓存（${size(Hub.localCacheBytes())}）") {
                confirm("清理本机缓存？","清理已下载的临时文件和图片缓存。聊天身份、登录信息和云端消息会保留。") {
                    Hub.clearLocalCache();Toast.makeText(this,"本机缓存已清理",Toast.LENGTH_SHORT).show();load()
                }
            }
            body.addView(label("自己的云端附件",18f,bold=true))
            body.addView(label("聊天附件经过加密，服务器只显示大小和上传时间。删除会让双方无法再次下载，已保存的副本仍由持有人保留。",12f,palette.muted))
            loadFiles("")
        }
    }
    private fun loadFiles(before:String) {
        task({Hub.rawApi("GET","/api/storage/files"+if(before.isEmpty())"" else "?before=$before",session=session)}) { result ->
            val items=result.getJSONArray("files");next=result.optString("next")
            if(items.length()==0&&before.isEmpty())body.addView(label("暂无附件",14f,palette.muted))
            for(i in 0 until items.length()) {
                val f=items.getJSONObject(i);val date=SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(f.getLong("created")))
                action("${if(f.optBoolean("sent")||f.optInt("sent")==1)"已发送" else "未发送"} · $date · ${size(f.getLong("size"))} · 删除") {
                    confirm("删除这个云端附件？","双方将无法再次下载这个附件，消息记录保留。此操作无法恢复。") {
                        task({Hub.rawApi("DELETE","/api/storage/files/"+f.getString("id"),session=session)}) { load() }
                    }
                }
            }
            if(next.isNotEmpty())action("加载更多附件") { val cursor=next;next="";loadFiles(cursor) }
        }
    }
    private fun confirm(title:String,message:String,yes:()->Unit) {AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确定"){_,_->if(valid())yes()}.show()}
}
