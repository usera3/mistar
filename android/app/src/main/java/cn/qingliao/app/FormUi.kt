package cn.qingliao.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*

/** Native form surfaces shared by sign-in and developer setup; no credentials are saved here. */
internal class FormUi(val context:Context,val p:ThemePalette) {
    val compact get()=CompactUi.enabled(context)
    val tiny get()=CompactUi.tiny(context)
    fun dp(n:Int)=(n*context.resources.displayMetrics.density+.5f).toInt()
    fun lp(w:Int=-1,h:Int=-2)=LinearLayout.LayoutParams(if(w<0)w else dp(w),if(h<0)h else dp(h))
    fun col()=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    fun row()=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
    fun text(value:String,size:Float=14f,color:Int=p.ink,bold:Boolean=false)=TextView(context).apply{
        text=value;textSize=if(tiny)size.coerceAtMost(20f)else size;setTextColor(color);includeFontPadding=false
        if(bold)setTypeface(typeface,Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(),1f)
    }
    fun gap(parent:LinearLayout,n:Int=12){parent.addView(View(context),lp(1,n))}
    fun card()=col().apply{background=BrandUi.card(context,p);val padding=dp(if(tiny)12 else 16);setPadding(padding,padding,padding,padding)}
    fun touch(v:View,primary:Boolean=false){
        v.background=RippleDrawable(ColorStateList.valueOf(p.ripple),if(primary)BrandUi.primary(context,p,16)else BrandUi.rounded(context,p.soft,16),BrandUi.rounded(context,Color.WHITE,16))
        v.isFocusable=true;v.isClickable=true;Motion.press(v)
    }
    fun button(title:String,tag:String,primary:Boolean=true,click:()->Unit)=text(title,14f,if(primary)p.onPrimary else p.accent,true).apply{
        this.tag=tag;gravity=Gravity.CENTER;minimumHeight=dp(48);setPadding(dp(14),dp(12),dp(14),dp(12))
        touch(this,primary);setOnClickListener{if(isEnabled)click()}
    }
    fun link(title:String,tag:String,click:()->Unit)=text(title,13f,p.accent,true).apply{
        this.tag=tag;gravity=Gravity.CENTER;minimumHeight=dp(48);setPadding(dp(6),dp(10),dp(6),dp(10))
        background=RippleDrawable(ColorStateList.valueOf(p.ripple),null,BrandUi.rounded(context,Color.WHITE,12));isFocusable=true;setOnClickListener{if(isEnabled)click()};Motion.press(this)
    }
    fun icon(kind:String,title:String,click:()->Unit)=FrameLayout(context).apply{
        contentDescription=title;minimumWidth=dp(48);minimumHeight=dp(48);isFocusable=true
        background=RippleDrawable(ColorStateList.valueOf(p.ripple),null,BrandUi.rounded(context,Color.WHITE,14))
        addView(Glyph(context,kind,p.accent),FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER))
        setOnClickListener{if(isEnabled)click()}
    }
    fun section(parent:LinearLayout,title:String,subtitle:String=""){
        gap(parent,if(tiny)10 else 20);parent.addView(text(title,if(tiny)13f else 15f,bold=true),lp())
        if(subtitle.isNotEmpty()){gap(parent,5);parent.addView(text(subtitle,12f,p.muted),lp())};gap(parent,if(tiny)6 else 10)
    }
    inner class Field(title:String,hint:String,val key:String,secret:Boolean=false,maximum:Int=128,type:Int=InputType.TYPE_CLASS_TEXT) {
        val view=col()
        private val shell=row().apply{background=BrandUi.field(context,p)}
        val edit=EditText(context).apply{
            this.hint=if(tiny&&!hint.contains(title))"$title · $hint"else hint;tag=key;textSize=if(tiny)14f else 15f;setTextColor(p.ink);setHintTextColor(p.muted)
            isSingleLine=true;inputType=if(secret)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else type
            filters=arrayOf(InputFilter.LengthFilter(maximum));background=null;setPadding(dp(14),dp(12),dp(8),dp(12))
            minimumHeight=dp(if(tiny)48 else 52);imeOptions=EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSaveEnabled=false
        }
        private val error=text("",12f,Color.rgb(173,56,67)).apply{visibility=View.GONE;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;tag="$key.error"}
        init {
            val label=text(title,12f,p.muted,true);view.addView(label,lp());if(tiny)label.visibility=View.GONE else gap(view,6)
            edit.id=View.generateViewId();label.labelFor=edit.id
            shell.addView(edit,LinearLayout.LayoutParams(0,-2,1f))
            edit.setOnFocusChangeListener{_,focus->shell.background=BrandUi.fieldSurface(context,p,focus)}
            if(secret){
                edit.transformationMethod=PasswordTransformationMethod.getInstance()
                val toggle=icon("eye","显示密码"){}.apply{tag="$key.visibility"}
                toggle.setOnClickListener{
                    val start=edit.selectionStart;val end=edit.selectionEnd
                    val visible=edit.transformationMethod!=null
                    edit.transformationMethod=if(visible)null else PasswordTransformationMethod.getInstance()
                    toggle.contentDescription=if(visible)"隐藏密码" else "显示密码"
                    if(start>=0&&end>=0)edit.setSelection(start.coerceAtMost(edit.length()),end.coerceAtMost(edit.length()))
                }
                shell.addView(toggle,lp(48,48))
            }
            view.addView(shell,lp());gap(view,4);view.addView(error,lp());gap(view,if(tiny)6 else 12)
            edit.addTextChangedListener(object:TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){clearError()}
                override fun afterTextChanged(s:Editable?){}
            })
        }
        fun value()=edit.text.toString()
        fun value(value:String){edit.setText(value)}
        fun fail(message:String):Boolean{error.text=message;error.visibility=View.VISIBLE;edit.requestFocus();edit.post{view.requestRectangleOnScreen(android.graphics.Rect(0,0,view.width,view.height),false)};return false}
        fun clearError(){error.text="";error.visibility=View.GONE}
        fun done(action:()->Unit){edit.imeOptions=EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI;edit.setOnEditorActionListener{_,id,event->if(id==EditorInfo.IME_ACTION_DONE||event?.keyCode==KeyEvent.KEYCODE_ENTER&&event.action==KeyEvent.ACTION_UP){action();true}else false}}
    }
}
