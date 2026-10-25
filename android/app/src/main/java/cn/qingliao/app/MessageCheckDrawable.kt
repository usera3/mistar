package cn.qingliao.app

import android.graphics.*
import android.graphics.drawable.Drawable

/** Circular selection mark, while CheckBox retains Android's checked accessibility state. */
class MessageCheckDrawable(private val pixels:Int,private val palette:ThemePalette):Drawable() {
    private var checked=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun getIntrinsicWidth()=pixels
    override fun getIntrinsicHeight()=pixels
    override fun isStateful()=true
    override fun onStateChange(state:IntArray):Boolean{val value=android.R.attr.state_checked in state;val changed=value!=checked;checked=value;if(changed)invalidateSelf();return changed}
    override fun draw(canvas:Canvas){
        val x=bounds.exactCenterX();val y=bounds.exactCenterY();val radius=minOf(bounds.width(),bounds.height())*.43f
        paint.color=if(checked)palette.primary else palette.muted;paint.style=if(checked)Paint.Style.FILL else Paint.Style.STROKE;paint.strokeWidth=pixels*.06f;canvas.drawCircle(x,y,radius,paint)
        if(checked){paint.color=palette.onPrimary;paint.style=Paint.Style.STROKE;paint.strokeWidth=pixels*.08f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND;val path=Path().apply{moveTo(x-radius*.48f,y);lineTo(x-radius*.09f,y+radius*.34f);lineTo(x+radius*.48f,y-radius*.34f)};canvas.drawPath(path,paint)}
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
