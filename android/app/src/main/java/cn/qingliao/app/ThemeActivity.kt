package cn.qingliao.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.*
import android.view.*
import android.widget.*

class ThemeActivity : Activity() {
    private val basePalette by lazy { ThemeStore.current(this) }
    override fun finish(){super.finish();Motion.activityClose(this)}
    private var draft=ThemeStore.DEFAULT
    private var syncing=false
    private lateinit var preview: LinearLayout
    private lateinit var save: TextView
    private lateinit var code: EditText
    private lateinit var codeCaption: TextView
    private lateinit var hue: SeekBar
    private lateinit var saturation: SeekBar
    private lateinit var brightness: SeekBar
    private val swatches=mutableListOf<Pair<Int,FrameLayout>>()
    private val sliderValues=mutableListOf<TextView>()
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun bg(color: Int,radius: Int=14,border: Int?=null)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat();if(border!=null)setStroke(dp(1),border) }
    private fun label(value: String,size: Float=14f,color: Int=Color.rgb(30,35,43),bold: Boolean=false)=TextView(this).apply { text=value;textSize=size;setTextColor(color);includeFontPadding=false;gravity=Gravity.CENTER_VERTICAL;if(bold)setTypeface(typeface,Typeface.BOLD) }
    private fun col()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    private fun gap(parent: LinearLayout,n: Int)=parent.addView(View(this),LinearLayout.LayoutParams(0,dp(n)))
    override fun onCreate(state: Bundle?) {
        super.onCreate(state);draft=state?.getInt("draft") ?: ThemeStore.current(this).primary
        window.statusBarColor=basePalette.canvas;window.navigationBarColor=basePalette.canvas
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=col().apply { setBackgroundColor(basePalette.canvas) }
        root.setOnApplyWindowInsetsListener { v,insets ->
            if(Build.VERSION.SDK_INT>=30){val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom)}
            else v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            insets
        }
        val top=row().apply { setPadding(dp(8),dp(8),dp(18),dp(10)) }
        top.addView(FrameLayout(this).apply { contentDescription="取消主题设置";setOnClickListener { finish() };addView(Glyph(this@ThemeActivity,"back"),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER)) },LinearLayout.LayoutParams(dp(48),dp(48)))
        top.addView(label("主题颜色",20f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        save=label("保存",14f,bold=true).apply { gravity=Gravity.CENTER;setPadding(dp(17),0,dp(17),0);contentDescription="保存主题颜色";setOnClickListener {
            if(ThemeStore.parse(code.text.toString())==null){code.error="请输入 6 位颜色代码";return@setOnClickListener}
            if(ThemeStore.save(this@ThemeActivity,draft)){setResult(RESULT_OK);finish()}else Toast.makeText(this@ThemeActivity,"保存失败，请重试",Toast.LENGTH_LONG).show()
        } };top.addView(save,LinearLayout.LayoutParams(-2,dp(48)));root.addView(top)
        val form=col().apply { setPadding(dp(20),dp(6),dp(20),dp(28)) }
        form.addView(label("让弥星，换成你喜欢的颜色",13f,Color.rgb(100,112,126)));gap(form,16)
        preview=col();preview.contentDescription="主题实时预览";form.addView(preview,LinearLayout.LayoutParams(-1,-2));gap(form,22)
        form.addView(label("精选配色",14f,bold=true));gap(form,10)
        ThemeStore.presets.chunked(if(CompactUi.enabled(this))3 else 5).forEach { group ->
            val r=row()
            group.forEach { (name,color) ->
                val cell=col().apply { gravity=Gravity.CENTER;contentDescription="选择"+name;setOnClickListener { select(color) };setPadding(0,dp(5),0,dp(9)) }
                val circle=FrameLayout(this);swatches.add(color to circle)
                val fill=View(this).apply { background=bg(color,22,Color.rgb(220,225,232)) };circle.addView(fill,FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER))
                cell.addView(circle,LinearLayout.LayoutParams(dp(48),dp(48)));gap(cell,5);cell.addView(label(name,11f,Color.rgb(100,112,126)).apply { gravity=Gravity.CENTER })
                r.addView(cell,LinearLayout.LayoutParams(0,-2,1f))
            };form.addView(r)
        }
        gap(form,16);form.addView(label("自定义颜色",14f,bold=true));gap(form,10)
        val custom=col().apply { background=BrandUi.card(this@ThemeActivity,basePalette);setPadding(dp(16),dp(16),dp(16),dp(14)) }
        codeCaption=label("",12f,Color.rgb(100,112,126));custom.addView(codeCaption);gap(custom,10)
        code=EditText(this).apply {
            hint="#RRGGBB";textSize=16f;isSingleLine=true;typeface=Typeface.MONOSPACE;setSelectAllOnFocus(true)
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            contentDescription="自定义颜色代码";background=BrandUi.field(this@ThemeActivity,basePalette);setPadding(dp(12),dp(10),dp(12),dp(10));filters=arrayOf(InputFilter.LengthFilter(16))
        };custom.addView(code,LinearLayout.LayoutParams(-1,dp(46)));gap(custom,6)
        code.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {
                if(syncing)return
                val color=ThemeStore.parse(s.toString());save.isEnabled=color!=null;save.alpha=if(color==null).4f else 1f
                if(color!=null){code.error=null;draft=color;render(false)}else if(s.toString().removePrefix("#").length>=6)code.error="请输入 6 位十六进制颜色代码"
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        fun slider(name: String,max: Int): SeekBar {
            val title=row();title.addView(label(name,12f,Color.rgb(100,112,126)),LinearLayout.LayoutParams(0,-2,1f));val value=label("",11f,Color.rgb(100,112,126));sliderValues.add(value);title.addView(value);custom.addView(title)
            val bar=SeekBar(this).apply { this.max=max;contentDescription=name }
            custom.addView(bar,LinearLayout.LayoutParams(-1,dp(48)))
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?,progress: Int,fromUser: Boolean) { if(!syncing&&fromUser)select(Color.HSVToColor(floatArrayOf(hue.progress.toFloat(),saturation.progress/100f,brightness.progress/100f)),false) }
                override fun onStartTrackingTouch(seek: SeekBar?) {}
                override fun onStopTrackingTouch(seek: SeekBar?) {}
            });return bar
        }
        hue=slider("色相",359);saturation=slider("饱和度",100);brightness=slider("明度",100);form.addView(custom)
        gap(form,14);form.addView(label("只改变本机界面的显示，不影响朋友的主题颜色。",12f,Color.rgb(100,112,126)).apply { setLineSpacing(dp(3).toFloat(),1f) });gap(form,14)
        form.addView(label("恢复默认配色",14f,basePalette.accent).apply { gravity=Gravity.CENTER;background=BrandUi.card(this@ThemeActivity,basePalette,18);setOnClickListener { select(ThemeStore.DEFAULT) } },LinearLayout.LayoutParams(-1,dp(48)))
        val scroll=ScrollView(this).apply { isFillViewport=false;isVerticalScrollBarEnabled=false;addView(form) };root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);root.requestApplyInsets();render(true)
    }
    override fun onSaveInstanceState(out: Bundle) { out.putInt("draft",draft);super.onSaveInstanceState(out) }
    private fun select(color: Int,syncSliders: Boolean=true) { draft=color;code.error=null;save.isEnabled=true;save.alpha=1f;render(true,syncSliders) }
    private fun render(updateCode: Boolean,syncSliders: Boolean=true) {
        val p=ThemePalette(draft);syncing=true
        if(updateCode)code.setText(ThemeStore.hex(draft))
        if(syncSliders) { val hsv=FloatArray(3);Color.colorToHSV(draft,hsv);hue.progress=hsv[0].toInt();saturation.progress=(hsv[1]*100+.5f).toInt();brightness.progress=(hsv[2]*100+.5f).toInt() }
        sliderValues[0].text="${hue.progress}°";sliderValues[1].text="${saturation.progress}%";sliderValues[2].text="${brightness.progress}%"
        listOf(hue,saturation,brightness).forEach { it.progressTintList=ColorStateList.valueOf(p.accent);it.thumbTintList=ColorStateList.valueOf(p.accent) }
        save.background=BrandUi.primary(this,p,16);save.setTextColor(p.onPrimary);codeCaption.text=ThemeStore.name(draft)+"  ·  "+ThemeStore.hex(draft);code.background=BrandUi.field(this,p)
        code.highlightColor=p.soft
        if(Build.VERSION.SDK_INT>=29)code.textCursorDrawable?.setTint(p.accent)
        swatches.forEach { (color,view) -> view.background=if(color==draft)GradientDrawable().apply { setColor(Color.TRANSPARENT);cornerRadius=dp(24).toFloat();setStroke(dp(2),p.accent) } else null;view.contentDescription=if(color==draft)"当前已选择" else null }
        preview.removeAllViews();preview.background=BrandUi.card(this,p,24);preview.setPadding(dp(18),dp(18),dp(18),dp(16))
        val title=row();title.addView(label("聊天预览",13f,p.ink,true),LinearLayout.LayoutParams(0,-2,1f));title.addView(label("实时预览",10f,p.accent));preview.addView(title);gap(preview,16)
        fun bubble(value: String,mine: Boolean) {
            val r=row().apply { gravity=if(mine)Gravity.END else Gravity.START }
            val t=label(value,14f,if(mine)p.onPrimary else p.ink).apply { background=if(mine)BrandUi.primary(this@ThemeActivity,p,18) else bg(p.soft,18);setPadding(dp(14),dp(12),dp(14),dp(12));maxWidth=resources.displayMetrics.widthPixels-dp(112) }
            r.addView(t);preview.addView(r,LinearLayout.LayoutParams(-1,-2));gap(preview,9)
        }
        bubble("这个颜色很适合你 😊",false);bubble("就用这个主题吧。",true)
        val footer=row();val mark=FrameLayout(this).apply { background=bg(p.soft,12);addView(Glyph(this@ThemeActivity,"chat",p.accent),FrameLayout.LayoutParams(dp(18),dp(18),Gravity.CENTER)) };footer.addView(mark,LinearLayout.LayoutParams(dp(38),dp(28)))
        footer.addView(label("  消息",11f,p.accent),LinearLayout.LayoutParams(0,-2,1f));footer.addView(FrameLayout(this).apply { background=bg(p.primary,10,p.line);addView(Glyph(this@ThemeActivity,"send",p.onPrimary),FrameLayout.LayoutParams(dp(17),dp(17),Gravity.CENTER)) },LinearLayout.LayoutParams(dp(32),dp(30)));preview.addView(footer)
        syncing=false
    }
}
