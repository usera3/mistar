package cn.qingliao.app

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

/** Shared native action panels. User content is always shown as plain text. */
object ActionSheets {
    data class Action(val icon: String, val title: String, val detail: String = "", val run: () -> Unit)
    fun show(activity: Activity, title: String, subtitle: String = "", preview: String = "", actions: List<Action>): Dialog {
        val theme=ThemeStore.current(activity);val ink=theme.ink;val muted=theme.muted
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        val dialog=create(activity,title,subtitle,body)
        fun dp(v: Int)=(v*activity.resources.displayMetrics.density).toInt()
        if(preview.isNotBlank())body.addView(TextView(activity).apply {
            text=preview; textSize=14f; setTextColor(muted); maxLines=3; ellipsize=TextUtils.TruncateAt.END
            setPadding(dp(14),dp(12),dp(14),dp(12)); background=rounded(theme.canvas,dp(14).toFloat())
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        actions.forEach { action ->
            val r=LinearLayout(activity).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                minimumHeight=dp(64); setPadding(dp(10),dp(10),dp(10),dp(10))
                background=RippleDrawable(ColorStateList.valueOf(theme.ripple),null,rounded(Color.WHITE,dp(14).toFloat()))
                contentDescription=action.title; isFocusable=true; setOnClickListener { dialog.dismiss(); action.run() }
            }
            Motion.press(r)
            val mark=FrameLayout(activity).apply {
                background=rounded(theme.soft,dp(12).toFloat())
                addView(Glyph(activity,action.icon,theme.accent),FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER))
            }
            r.addView(mark,LinearLayout.LayoutParams(dp(40),dp(40)))
            val info=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),0,0,0) }
            info.addView(TextView(activity).apply { text=action.title; textSize=15f; setTextColor(ink); includeFontPadding=false })
            if(action.detail.isNotEmpty())info.addView(TextView(activity).apply { text=action.detail; textSize=12f; setTextColor(muted); includeFontPadding=false; setPadding(0,dp(5),0,0) })
            r.addView(info,LinearLayout.LayoutParams(0,-2,1f)); body.addView(r,LinearLayout.LayoutParams(-1,-2))
        }
        open(dialog); return dialog
    }
    fun content(activity: Activity, title: String, subtitle: String, body: View): Dialog = create(activity,title,subtitle,body).also { open(it) }
    private fun rounded(color: Int, radius: Float)=GradientDrawable().apply { setColor(color); cornerRadius=radius }
    private fun create(activity: Activity, title: String, subtitle: String, body: View): Dialog {
        val theme=ThemeStore.current(activity);val ink=theme.ink;val muted=theme.muted
        fun dp(v: Int)=(v*activity.resources.displayMetrics.density).toInt()
        val dialog=Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val hostInsets=activity.window.decorView.rootWindowInsets
        val hostBars=if(android.os.Build.VERSION.SDK_INT>=30)hostInsets?.getInsets(android.view.WindowInsets.Type.systemBars()) else null
        val safeBottom=hostBars?.bottom ?: hostInsets?.stableInsetBottom ?: 0
        val safeLeft=hostBars?.left ?: 0; val safeRight=hostBars?.right ?: 0
        val root=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(20)+safeLeft,dp(10),dp(20)+safeRight,dp(22)+safeBottom)
            background=rounded(theme.surface,0f).apply { val r=dp(30).toFloat(); cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f) }
            setOnApplyWindowInsetsListener { v,insets ->
                if(android.os.Build.VERSION.SDK_INT>=30) {
                    val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    v.setPadding(dp(20)+maxOf(bars.left,safeLeft),dp(10),dp(20)+maxOf(bars.right,safeRight),dp(22)+maxOf(bars.bottom,safeBottom))
                } else v.setPadding(dp(20),dp(10),dp(20),dp(22)+maxOf(insets.stableInsetBottom,safeBottom))
                insets
            }
        }
        root.addView(View(activity).apply{background=rounded(theme.line,dp(2).toFloat())},LinearLayout.LayoutParams(dp(36),dp(4)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(10)})
        val top=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL; orientation=LinearLayout.HORIZONTAL }
        top.addView(TextView(activity).apply { text=title; textSize=19f; setTextColor(ink); setTypeface(typeface,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END; includeFontPadding=false },LinearLayout.LayoutParams(0,-2,1f))
        top.addView(FrameLayout(activity).apply {
            contentDescription="关闭面板"; isFocusable=true; setOnClickListener { dialog.dismiss() }
            addView(Glyph(activity,"close",muted),FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER))
        },LinearLayout.LayoutParams(dp(44),dp(48)))
        root.addView(top)
        if(subtitle.isNotEmpty())root.addView(TextView(activity).apply { text=subtitle; textSize=12f; setTextColor(muted); setPadding(0,0,0,dp(16)); includeFontPadding=false })
        root.addView(body,LinearLayout.LayoutParams(-1,-2))
        val scroll=object : ScrollView(activity) {
            override fun onMeasure(w: Int,h: Int) {
                val limit=(resources.displayMetrics.heightPixels*.78).toInt()
                super.onMeasure(w,MeasureSpec.makeMeasureSpec(minOf(limit,MeasureSpec.getSize(h).takeIf { it>0 } ?: limit),MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled=false; addView(root) }
        dialog.setContentView(scroll); dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS); setDimAmount(.28f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            navigationBarColor=Color.WHITE
            if(android.os.Build.VERSION.SDK_INT>=29)isNavigationBarContrastEnforced=false
            if(android.os.Build.VERSION.SDK_INT>=30)setDecorFitsSystemWindows(false)
            decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        return dialog
    }
    private fun open(dialog: Dialog) {
        Motion.sheet(dialog.window)
        dialog.show(); dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
        if(android.os.Build.VERSION.SDK_INT>=30) {
            val mask=android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            dialog.window?.insetsController?.setSystemBarsAppearance(mask,mask)
        }
    }
}
