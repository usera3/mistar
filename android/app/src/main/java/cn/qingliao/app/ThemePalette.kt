package cn.qingliao.app

import android.content.Context
import android.graphics.Color
import java.util.Locale
import kotlin.math.pow

/** Colours are local appearance preferences, independent from account/session data. */
object ThemeStore {
    const val DEFAULT = 0xFF7660D5.toInt()
    val presets = listOf("弥星紫" to DEFAULT,"雾蓝紫" to 0xFF6669DE.toInt(),"晴空蓝" to 0xFF2563EB.toInt(),"鸢尾紫" to 0xFF7C3AED.toInt(),"玫瑰粉" to 0xFFDB2777.toInt(),"日落橙" to 0xFFEA580C.toInt(),"青绿色" to 0xFF0F7663.toInt(),"湖水青" to 0xFF0891B2.toInt(),"石墨灰" to 0xFF334155.toInt(),"暖杏黄" to 0xFFFBBF24.toInt())
    private var cached=ThemePalette(DEFAULT)
    @Synchronized fun current(context: Context): ThemePalette {
        val color=context.applicationContext.getSharedPreferences("appearance",Context.MODE_PRIVATE).getInt("primary",DEFAULT) or Color.BLACK
        if(cached.primary!=color)cached=ThemePalette(color)
        return cached
    }
    fun save(context: Context,color: Int)=context.applicationContext.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putInt("primary",color or Color.BLACK).commit()
    fun name(color: Int)=presets.find { it.second==color }?.first ?: "自定义颜色"
    fun hex(color: Int)=String.format(Locale.ROOT,"#%06X",color and 0xFFFFFF)
    fun parse(value: String): Int? {
        val raw=value.trim().removePrefix("#")
        return if(raw.matches(Regex("[0-9a-fA-F]{6}")))Color.parseColor("#$raw") else null
    }
}

class ThemePalette(color: Int) {
    val primary=color or Color.BLACK
    val ink=blend(primary,Color.rgb(30,32,43),.96f)
    val muted=blend(primary,Color.rgb(101,107,123),.96f)
    val surface=blend(primary,Color.WHITE,.995f)
    val canvas=blend(primary,Color.WHITE,.965f)
    val soft=blend(primary,Color.WHITE,.91f)
    val line=blend(primary,Color.WHITE,.85f)
    val heroStart=blend(primary,Color.WHITE,.90f)
    val heroEnd=blend(primary,Color.WHITE,.985f)
    val accent=readable(primary,listOf(surface,canvas,soft,heroStart))
    val onPrimary=if(contrast(primary,Color.WHITE)>=contrast(primary,Color.BLACK))Color.WHITE else Color.BLACK
    val primaryEnd=blend(primary,if(onPrimary==Color.WHITE)Color.BLACK else Color.WHITE,.06f)
    val primaryInset=blend(primary,onPrimary,.09f)
    val profileEnd=heroEnd
    val callTop=blend(primary,Color.rgb(20,24,32),.78f)
    val callBottom=blend(primary,Color.rgb(16,20,27),.94f)
    val callSurface=blend(primary,Color.rgb(36,43,53),.76f)
    val callMuted=Color.rgb(199,208,221)
    val ripple=Color.argb(25,Color.red(accent),Color.green(accent),Color.blue(accent))
    companion object {
        fun blend(from: Int,to: Int,amount: Float): Int {
            val t=amount.coerceIn(0f,1f)
            fun channel(a: Int,b: Int)=(a+(b-a)*t+.5f).toInt().coerceIn(0,255)
            return Color.rgb(channel(Color.red(from),Color.red(to)),channel(Color.green(from),Color.green(to)),channel(Color.blue(from),Color.blue(to)))
        }
        private fun luminance(color: Int): Double {
            fun channel(c: Int): Double { val n=c/255.0;return if(n<=.04045)n/12.92 else ((n+.055)/1.055).pow(2.4) }
            return .2126*channel(Color.red(color))+.7152*channel(Color.green(color))+.0722*channel(Color.blue(color))
        }
        fun contrast(a: Int,b: Int): Double { val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05) }
        private fun readable(color: Int,backgrounds: List<Int>): Int {
            fun works(candidate: Int)=backgrounds.all { contrast(candidate,it)>=4.5 }
            if(works(color))return color
            var lo=0f;var hi=1f
            repeat(24) { val mid=(lo+hi)/2; if(works(blend(color,Color.BLACK,mid)))hi=mid else lo=mid }
            return blend(color,Color.BLACK,hi)
        }
    }
}
