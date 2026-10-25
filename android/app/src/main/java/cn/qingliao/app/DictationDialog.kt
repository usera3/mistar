package cn.qingliao.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.*
import android.widget.*
import java.util.concurrent.atomic.AtomicBoolean

/** Recording preview is separate from the draft. Every exit cancels microphone, HTTP and late callbacks. */
internal class DictationDialog(private val activity:Activity,private val valid:()->Boolean,private val client:WetypeClient=WetypeClient(),private val onInsert:(String)->Unit) {
    private val active=AtomicBoolean(true)
    private val p=ThemeStore.current(activity)
    private val compact=CompactUi.enabled(activity)
    private val preferences=activity.getSharedPreferences("dictation",0)
    private val dialog=Dialog(activity)
    @Volatile private var stream:WetypeClient.Stream?=null
    @Volatile private var capture:DictationAudio?=null
    @Volatile private var finishRequested=false
    private var latest=""
    private var failedState=false
    private var started=0L
    private lateinit var status:TextView
    private lateinit var preview:TextView
    private lateinit var end:TextView
    private lateinit var levels:Spinner
    private lateinit var meter:ProgressBar
    private val ticker=object:Runnable {override fun run(){if(!active.get()||!valid())return;if(started>0&&!finishRequested)status.text="正在听 · %02d:%02d".format((SystemClock.elapsedRealtime()-started)/60000,(SystemClock.elapsedRealtime()-started)/1000%60);Hub.main.postDelayed(this,500)}}
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun text(s:String,size:Float=14f)=TextView(activity).apply{text=s;textSize=size;setTextColor(p.ink);includeFontPadding=false}
    private fun ui(block:()->Unit){Hub.main.post{if(active.get()&&valid()&&!activity.isDestroyed)block() else cancel()}}
    fun show(){
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=BrandUi.card(activity,p,22)}
        val top=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        status=text("正在连接语音服务…",13f);top.addView(status,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(text("取消",14f).apply{setTextColor(p.accent);gravity=Gravity.CENTER;contentDescription="取消语音转文字";setOnClickListener{cancel()}},LinearLayout.LayoutParams(dp(48),dp(40)));root.addView(top)
        meter=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progressTintList=android.content.res.ColorStateList.valueOf(p.accent);contentDescription="实际录音音量"};root.addView(meter,LinearLayout.LayoutParams(-1,dp(4)))
        preview=text("连接成功后开始说话",if(compact)15f else 17f).apply{setPadding(0,dp(10),0,dp(10));setTextIsSelectable(true)}
        val scroll=ScrollView(activity).apply{isFillViewport=true;addView(preview)};root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        levels=Spinner(activity).apply{contentDescription="识别文字版本";adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,arrayOf("原始识别","基础整理","优化表达"));setSelection(preferences.getInt("level",1).coerceIn(0,2))}
        levels.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(v:AdapterView<*>?){};override fun onItemSelected(v:AdapterView<*>?,view:View?,position:Int,id:Long){preferences.edit().putInt("level",position).apply();stream?.changeLevel(position)}}
        root.addView(levels,LinearLayout.LayoutParams(-1,dp(40)))
        end=text("准备中…",15f).apply{gravity=Gravity.CENTER;setTextColor(p.onPrimary);background=BrandUi.primary(activity,p,14);isEnabled=false;contentDescription="结束语音转文字";setOnClickListener{finishRecording()}}
        root.addView(end,LinearLayout.LayoutParams(-1,dp(44)))
        root.addView(text(if(compact)"微信听写 · 最长60秒\n仅填入，由你发送" else "仅本次声音发送至微信听写 · 最长60秒\n完成后填入输入框，由你检查并发送",10f).apply{setTextColor(p.muted);setPadding(0,dp(6),0,0)})
        dialog.setContentView(root);dialog.setCanceledOnTouchOutside(false);dialog.setOnCancelListener{cancel()};dialog.setOnDismissListener{cancel()};dialog.show()
        val height=(activity.resources.displayMetrics.heightPixels*.88).toInt()
        dialog.window?.apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));setLayout((activity.resources.displayMetrics.widthPixels-dp(if(compact)12 else 36)).coerceAtMost(dp(480)),height);setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)}
        Hub.main.post(ticker)
        Thread({
            try {
                client.handshake();client.check();if(!active.get())return@Thread
                val engine=client.Stream(preferences.getInt("level",1).coerceIn(0,2)){r->val selected=r.selected(stream?.level?:preferences.getInt("level",1));ui{latest=selected;preview.text=selected.ifBlank{"正在听你说话…"}}};stream=engine
                val microphone=DictationAudio(compact,engine::push,{value->ui{meter.progress=(value*100).toInt()}},{engine.finish();ui{if(!failedState){finishRequested=true;end.isEnabled=false;end.text="正在整理…";levels.isEnabled=false;status.text="录音已结束"}}},{error->ui{failed(error)}});capture=microphone
                ui{started=SystemClock.elapsedRealtime();end.isEnabled=true;end.text="结束并填入";preview.text="正在听你说话…"}
                if(!active.get()){microphone.stop();return@Thread}
                Thread({microphone.run()},"xingyu-dictation-mic").start()
                val result=engine.run();ui{
                    if(result.isBlank()){failed(IllegalStateException("没有识别到文字，请再试一次"));return@ui}
                    if(result.length>5000){failed(IllegalStateException("识别文字超过消息长度限制"));return@ui}
                    onInsert(result);cancel()
                }
            }catch(error:Throwable){ui{failed(error)}}finally{capture?.stop()}
        },"xingyu-dictation-network").start()
    }
    private fun finishRecording(){if(!active.get()||finishRequested)return;finishRequested=true;levels.isEnabled=false;end.isEnabled=false;end.text="正在整理…";status.text="录音已结束";capture?.stop();stream?.finish()}
    private fun failed(error:Throwable){
        failedState=true
        capture?.stop();client.cancel();finishRequested=true;Hub.main.removeCallbacks(ticker);meter.progress=0;levels.isEnabled=false
        status.text=(if(error is WetypeClient.Failure)error.message else "语音识别未完成，请重试")
        if(latest.isBlank()){preview.text="未填入任何文字，原草稿已保留。";end.text="关闭";end.isEnabled=true;end.setOnClickListener{cancel()}}
        else{preview.text=latest;end.text="保留已识别文字";end.isEnabled=true;end.setOnClickListener{if(valid())onInsert(latest);cancel()}}
    }
    fun cancel(){if(!active.compareAndSet(true,false))return;Hub.main.removeCallbacks(ticker);capture?.stop();client.cancel();latest="";dialog.dismiss()}
}
