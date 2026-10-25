package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*

class BackgroundActivity:Activity() {
    private val session=Hub.token
    private val palette by lazy{ThemeStore.current(this)}
    private lateinit var form:LinearLayout
    private lateinit var connection:TextView
    private val prefs by lazy{getSharedPreferences("background",MODE_PRIVATE)}
    private val refresh=object:Runnable{override fun run(){if(!isFinishing&&!isDestroyed){updateConnection();Hub.main.postDelayed(this,1500)}}}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Float=14f)=TextView(this).apply{text=value;textSize=size;setTextColor(palette.ink);setPadding(0,dp(10),0,dp(10))}
    private fun action(title:String,click:()->Unit) {
        form.addView(label(title,14f).apply{gravity=Gravity.CENTER;minimumHeight=dp(50);setTextColor(palette.accent);isFocusable=true
            background=BrandUi.card(this@BackgroundActivity,palette,18);setOnClickListener{runCatching{click()}.onFailure{Toast.makeText(this@BackgroundActivity,"此系统未提供该设置入口",Toast.LENGTH_SHORT).show()}}
        },LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(session.isEmpty()){finish();return}
        window.statusBarColor=palette.canvas;window.navigationBarColor=palette.canvas;window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(palette.canvas)}
        root.setOnApplyWindowInsetsListener{v,i->if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom)}else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i}
        root.addView(label("‹  后台消息与来电",19f).apply{setPadding(dp(22),dp(16),dp(22),dp(16));setOnClickListener{finish()}})
        form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(8),dp(22),dp(28))};root.addView(ScrollView(this).apply{addView(form)},LinearLayout.LayoutParams(-1,0,1f));setContentView(root);root.requestApplyInsets()
    }
    override fun onResume(){super.onResume();if(Hub.token!=session){finish();return};render();Hub.main.removeCallbacks(refresh);Hub.main.post(refresh)}
    override fun onPause(){Hub.main.removeCallbacks(refresh);super.onPause()}
    private fun render() {
        form.removeAllViews();connection=label("",17f).apply{setTypeface(typeface,Typeface.BOLD)};form.addView(connection);updateConnection()
        val manager=getSystemService(NotificationManager::class.java)
        form.addView(label("消息通知："+if(manager.areNotificationsEnabled())"已允许" else "未允许"))
        action("消息通知设置"){startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))}
        val channel=manager.getNotificationChannel("calls")
        form.addView(label("来电通知："+if(manager.areNotificationsEnabled()&&channel?.importance!=NotificationManager.IMPORTANCE_NONE)"已开启" else "未开启"))
        action("来电声音与弹出设置"){startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName).putExtra(Settings.EXTRA_CHANNEL_ID,"calls"))}
        form.addView(label("锁屏全屏来电："+if(Build.VERSION.SDK_INT<34||manager.canUseFullScreenIntent())"已允许" else "需在系统中允许"))
        if(Build.VERSION.SDK_INT>=34)action("锁屏全屏来电设置"){startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:$packageName")))}
        val power=getSystemService(PowerManager::class.java)
        form.addView(label("电池优化："+if(power.isIgnoringBatteryOptimizations(packageName))"已允许更稳定的后台运行" else "系统可能限制后台运行"))
        action("后台电池设置"){startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}
        form.addView(Switch(this).apply{text="开机首次解锁后恢复消息连接";textSize=14f;setTextColor(palette.ink);minimumHeight=dp(52);isChecked=prefs.getBoolean("restore_on_boot",true);setOnCheckedChangeListener{_,checked->prefs.edit().putBoolean("restore_on_boot",checked).apply()}})
        action("重新连接消息服务"){Hub.startService();Hub.networkChanged(true,true);updateConnection()}
        form.addView(label("请保持“消息连接”常驻通知开启。系统设置中强行停止弥星后，需要手动打开应用才能恢复接收。",12f).apply{setTextColor(palette.muted)})
        val error=prefs.getString("last_service_error","")
        if(!error.isNullOrEmpty())form.addView(label("上次启动受到系统限制，可先打开弥星并检查上面的设置。",12f).apply{setTextColor(palette.muted)})
    }
    private fun updateConnection(){val value=when{Hub.service==null->"消息服务尚未运行";Hub.connected->"消息连接正常";else->"正在重新连接…"};if(connection.text.toString()!=value)connection.text=value}
}
