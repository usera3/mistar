package cn.qingliao.app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/** Shared native surfaces. All colours derive from the user's local theme. */
object BrandUi {
    fun rounded(context: Context,color: Int,radius: Int=22,border: Int?=null,stroke: Int=1)=GradientDrawable().apply {
        setColor(color);cornerRadius=radius*context.resources.displayMetrics.density
        if(border!=null)setStroke((stroke*context.resources.displayMetrics.density).toInt().coerceAtLeast(1),border)
    }
    fun card(context: Context,p: ThemePalette,radius: Int=22)=rounded(context,p.surface,radius,p.line)
    fun primary(context: Context,p: ThemePalette,radius: Int=18)=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(p.primary,p.primaryEnd)).apply {
        cornerRadius=radius*context.resources.displayMetrics.density
    }
    fun field(context: Context,p: ThemePalette)=StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused),rounded(context,p.surface,18,p.accent,2))
        addState(intArrayOf(),rounded(context,p.surface,18,p.line))
    }
}

/** Quiet, static star/island details for welcome and cover surfaces, never behind chat text. */
class StarlightDrawable(private val palette: ThemePalette,private val radius: Float=0f): Drawable() {
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow=Paint(Paint.ANTI_ALIAS_FLAG)
    private val detail=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=palette.primary;alpha=36 }
    private val orbit=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=palette.primary;alpha=16;style=Paint.Style.STROKE;strokeWidth=1.2f }
    private val shape=Path()
    private val stars=Path()
    private val area=RectF()
    private var unit=1f
    override fun onBoundsChange(bounds: Rect) {
        if(bounds.isEmpty)return
        area.set(bounds);unit=area.width()/400f
        fill.shader=LinearGradient(area.left,area.top,area.right,area.bottom,intArrayOf(palette.heroStart,palette.heroEnd),null,Shader.TileMode.CLAMP)
        glow.shader=RadialGradient(area.right-area.width()*.12f,area.top+area.height()*.15f,area.width()*.7f,intArrayOf(Color.argb(40,255,255,255),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        shape.reset();shape.addRoundRect(area,radius,radius,Path.Direction.CW)
        stars.reset()
        fun star(x: Float,y: Float,r: Float) {
            stars.moveTo(x,y-r);stars.cubicTo(x+r*.18f,y-r*.18f,x+r*.18f,y-r*.18f,x+r,y)
            stars.cubicTo(x+r*.18f,y+r*.18f,x+r*.18f,y+r*.18f,x,y+r)
            stars.cubicTo(x-r*.18f,y+r*.18f,x-r*.18f,y+r*.18f,x-r,y)
            stars.cubicTo(x-r*.18f,y-r*.18f,x-r*.18f,y-r*.18f,x,y-r);stars.close()
        }
        star(area.right-48*unit,area.top+44*unit,9*unit)
        star(area.right-94*unit,area.top+25*unit,4*unit)
        star(area.right-25*unit,area.top+83*unit,3*unit)
    }
    override fun draw(canvas: Canvas) {
        if(area.isEmpty)return
        canvas.save();canvas.clipPath(shape)
        canvas.drawRect(area,fill);canvas.drawRect(area,glow)
        canvas.drawOval(area.right-156*unit,area.top-66*unit,area.right+112*unit,area.top+132*unit,orbit)
        canvas.drawPath(stars,detail);canvas.restore()
    }
    override fun setAlpha(alpha: Int) { fill.alpha=alpha;invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { fill.colorFilter=filter;invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
