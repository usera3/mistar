package cn.qingliao.app

import android.app.*
import android.graphics.Color
import android.graphics.drawable.*
import android.view.*
import android.widget.*

/** Short, centered actions matching the location flow; no generic form page as a menu. */
object LocationSheets {
    fun choose(activity:Activity,valid:()->Boolean,send:()->Unit,live:()->Unit)=show(activity,"",listOf("发送位置" to send,"共享实时位置" to live),false,valid)
    fun end(activity:Activity,valid:()->Boolean,action:()->Unit)=show(activity,"是否结束位置共享？",listOf("结束" to action),true,valid)
    private fun show(activity:Activity,title:String,actions:List<Pair<String,()->Unit>>,destructive:Boolean,valid:()->Boolean):Dialog {
        val p=ThemeStore.current(activity);val u=FormUi(activity,p);val d=Dialog(activity);d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root=u.col().apply{background=GradientDrawable().apply{setColor(p.surface);val r=u.dp(16).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)}}
        if(title.isNotEmpty())root.addView(u.text(title,13f,p.muted).apply{gravity=Gravity.CENTER;setPadding(u.dp(12),u.dp(20),u.dp(12),u.dp(20))},u.lp())
        for((label,action) in actions){
            root.addView(View(activity).apply{setBackgroundColor(p.line)},u.lp(-1,1))
            root.addView(u.text(label,17f,if(destructive)Color.rgb(233,81,75)else p.ink).apply{tag="location.sheet.$label";gravity=Gravity.CENTER;minimumHeight=u.dp(if(u.tiny)44 else 58);isFocusable=true;setPadding(u.dp(12),u.dp(12),u.dp(12),u.dp(12));setOnClickListener{d.dismiss();if(valid())action()};Motion.press(this)},u.lp())
        }
        root.addView(View(activity).apply{setBackgroundColor(p.canvas)},u.lp(-1,8))
        root.addView(u.text("取消",17f,p.ink).apply{tag="location.sheet.cancel";gravity=Gravity.CENTER;minimumHeight=u.dp(54);setOnClickListener{d.dismiss()};setPadding(0,u.dp(12),0,u.dp(12))},u.lp())
        root.setOnApplyWindowInsetsListener{v,i->val bottom=if(android.os.Build.VERSION.SDK_INT>=30)i.getInsets(WindowInsets.Type.systemBars()).bottom else i.stableInsetBottom;v.setPadding(0,0,0,bottom);i}
        d.setContentView(root);d.setCanceledOnTouchOutside(true);d.window?.apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));setGravity(Gravity.BOTTOM);addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);setDimAmount(.4f);navigationBarColor=p.surface;if(android.os.Build.VERSION.SDK_INT>=30)setDecorFitsSystemWindows(false)}
        Motion.sheet(d.window);d.show();d.window?.setLayout(-1,-2);return d
    }
}
