package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import java.util.Locale

class UpdateActivity:Activity() {
    private val palette by lazy{ThemeStore.current(this)}
    private lateinit var status:TextView
    private lateinit var notes:TextView
    private lateinit var progress:ProgressBar
    private lateinit var primary:TextView
    private lateinit var cancel:TextView
    private lateinit var check:TextView
    private lateinit var notify:Switch
    private val observer:()->Unit={render()}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Float=14f)=TextView(this).apply{text=value;textSize=size;setTextColor(palette.ink);setPadding(0,dp(8),0,dp(8))}
    private fun button(value:String,action:()->Unit)=label(value).apply{
        gravity=Gravity.CENTER;minimumHeight=dp(48);setTextColor(palette.accent);isFocusable=true
        background=BrandUi.card(this@UpdateActivity,palette,16)
        setOnClickListener{action()};Motion.press(this)
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.statusBarColor=palette.canvas;window.navigationBarColor=palette.canvas
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val compact=resources.configuration.screenWidthDp<=320
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(palette.canvas)}
        root.setOnApplyWindowInsetsListener{v,i->if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom)}else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i}
        root.addView(label(if(compact)"‹  弥星更新"else"‹  版本更新",if(compact)17f else 19f).apply{setPadding(dp(16),dp(12),dp(16),dp(12));minimumHeight=dp(48);contentDescription="返回";setOnClickListener{finish()}})
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(if(compact)12 else 24),0,dp(if(compact)12 else 24),dp(24))}
        root.addView(ScrollView(this).apply{isFillViewport=true;addView(form)},LinearLayout.LayoutParams(-1,0,1f))
        if(!compact)form.addView(label("弥星",26f).apply{setTypeface(typeface,Typeface.BOLD)})
        form.addView(label("当前版本  ${BuildConfig.VERSION_NAME}",12f).apply{setTextColor(palette.muted)})
        status=label("",17f).apply{setTypeface(typeface,Typeface.BOLD);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};form.addView(status)
        notes=label("").apply{setLineSpacing(dp(4).toFloat(),1f)}
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100}
        primary=button(""){act()}
        if(compact){form.addView(primary,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)});form.addView(progress,LinearLayout.LayoutParams(-1,dp(6)));form.addView(notes)}
        else{form.addView(notes);form.addView(progress,LinearLayout.LayoutParams(-1,dp(6)));form.addView(primary,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})}
        cancel=button("取消下载"){AppUpdates.cancelDownload()};form.addView(cancel,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        check=button("检查更新"){AppUpdates.check(manual=true)};form.addView(check,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        form.addView(button("分享下载地址"){
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"弥星 · Android 下载\n"+AppUpdates.PAGE),"分享弥星"))
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        form.addView(button("复制下载地址"){
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("弥星下载",AppUpdates.PAGE))
            Toast.makeText(this,"下载地址已复制",Toast.LENGTH_SHORT).show()
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        notify=Switch(this).apply{text="接收版本更新通知";textSize=14f;setTextColor(palette.ink);minimumHeight=dp(52);isChecked=AppUpdates.notificationsEnabled
            setOnCheckedChangeListener{_,enabled->AppUpdates.setNotifications(enabled)}};form.addView(notify)
        form.addView(label("连接在线时推送新版提醒；离线或被系统强行停止后，下次打开或重连时检查。下载由你发起，安装需系统确认。",12f).apply{setTextColor(palette.muted)})
        form.addView(button("通知设置"){
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName).putExtra(Settings.EXTRA_CHANNEL_ID,"updates"))
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        setContentView(root);root.requestApplyInsets()
    }
    override fun onResume(){super.onResume();AppUpdates.observe(observer);render();AppUpdates.check();AppUpdates.notifyAvailable()}
    override fun onStop(){AppUpdates.unobserve(observer);AppUpdates.cancelDownload();super.onStop()}
    private fun render() {
        if(isFinishing||isDestroyed||!::status.isInitialized)return
        val r=AppUpdates.available
        status.text=when{AppUpdates.downloading->"正在下载 ${AppUpdates.progress}%";AppUpdates.error.isNotEmpty()->AppUpdates.error;AppUpdates.checking->"正在检查更新…";r!=null->"新版本 ${r.version}";AppUpdates.latest!=null->"已是最新版本";else->"检查可用的新版本"}
        val description=r?.let{it.notes.joinToString("\n"){s->"• $s"}+"\n\n"+String.format(Locale.ROOT,"安装包 %.1f MB",it.size/1048576.0)}?:"固定下载地址始终提供最新的正式版本。"
        if(notes.text.toString()!=description)notes.text=description
        progress.visibility=if(AppUpdates.downloading)View.VISIBLE else View.GONE;progress.progress=AppUpdates.progress
        primary.visibility=if(r!=null)View.VISIBLE else View.GONE
        primary.text=if(r!=null&&AppUpdates.downloaded(r)!=null)"安装更新" else "下载更新"
        primary.isEnabled=!AppUpdates.downloading;primary.alpha=if(primary.isEnabled)1f else .45f
        cancel.visibility=if(AppUpdates.downloading)View.VISIBLE else View.GONE
        check.text=if(AppUpdates.checking)"正在检查…" else "检查更新";check.isEnabled=!AppUpdates.checking&&!AppUpdates.downloading
        notify.isChecked=AppUpdates.notificationsEnabled
    }
    private fun act() {
        if(Hub.call!=null){Toast.makeText(this,"请在通话结束后更新",Toast.LENGTH_SHORT).show();return}
        val r=AppUpdates.available?:return
        val apk=AppUpdates.downloaded(r)
        if(apk==null){AppUpdates.download(r);return}
        try{
            if(!packageManager.canRequestPackageInstalls()){
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")))
                Toast.makeText(this,"允许后返回，点“安装更新”继续",Toast.LENGTH_LONG).show();return
            }
            val uri=FileProvider.getUriForFile(this,"$packageName.files",apk)
            @Suppress("DEPRECATION")
            val install=Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE,true)
            startActivity(install)
        }catch(_:Exception){Toast.makeText(this,"无法打开安装器，可从下载页下载安装",Toast.LENGTH_LONG).show()}
    }
}
