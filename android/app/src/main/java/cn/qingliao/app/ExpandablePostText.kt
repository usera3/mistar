package cn.qingliao.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.StaticLayout
import android.text.TextUtils
import android.view.Gravity
import android.widget.*

/** Decide whether the fold control exists before measuring its parent. */
class ExpandablePostText(context:Context,palette:ThemePalette,source:String,initiallyOpen:Boolean,changed:(Boolean)->Unit):LinearLayout(context) {
    private var open=initiallyOpen
    private var lastTextWidth=0
    private var overflows=false
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private val body=TextView(context).apply { text=source;textSize=16f;setTextColor(palette.ink);includeFontPadding=false;setLineSpacing(dp(4).toFloat(),1f);maxLines=if(open)Int.MAX_VALUE else 6;ellipsize=TextUtils.TruncateAt.END }
    private val more=TextView(context).apply { text=if(open)"收起全文" else "展开全文";textSize=13f;setTextColor(palette.accent);setTypeface(typeface,Typeface.BOLD);gravity=Gravity.CENTER_VERTICAL;includeFontPadding=false;setPadding(0,0,dp(18),0);isFocusable=true;background=RippleDrawable(ColorStateList.valueOf(palette.ripple),null,null) }
    init {
        orientation=VERTICAL
        addView(body,LayoutParams(-1,-2));addView(more,LayoutParams(-2,dp(44)))
        Motion.press(more)
        more.setOnClickListener { ((parent as? android.view.ViewGroup)?.parent as? android.view.ViewGroup)?.let { Motion.bounds(it) };open=!open;body.maxLines=if(open)Int.MAX_VALUE else 6;more.text=if(open)"收起全文" else "展开全文";changed(open);requestLayout() }
    }
    override fun onMeasure(widthSpec:Int,heightSpec:Int) {
        val width=(MeasureSpec.getSize(widthSpec)-paddingLeft-paddingRight).coerceAtLeast(1)
        if(lastTextWidth!=width) {
            lastTextWidth=width
            overflows=StaticLayout.Builder.obtain(body.text,0,body.text.length,body.paint,width).setIncludePad(false).setLineSpacing(body.lineSpacingExtra,body.lineSpacingMultiplier).setBreakStrategy(body.breakStrategy).build().lineCount>6
        }
        more.visibility=if(overflows||open)VISIBLE else GONE
        super.onMeasure(widthSpec,heightSpec)
    }
}
