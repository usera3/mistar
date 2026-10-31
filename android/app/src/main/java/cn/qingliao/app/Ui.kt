package cn.qingliao.app

import android.content.Context
import android.graphics.*
import android.view.View

class Glyph(context: Context, private var kind: String, var color: Int = Color.rgb(30, 35, 43)) : View(context) {
    fun variant(value:String){if(kind!=value){kind=value;invalidate()}}
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.save(); val scale = minOf(width, height) / 24f; c.translate((width - 24 * scale) / 2, (height - 24 * scale) / 2); c.scale(scale, scale)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@Glyph.color; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun line(vararg xy: Float) { val path = Path(); path.moveTo(xy[0], xy[1]); for (i in 2 until xy.size step 2) path.lineTo(xy[i], xy[i+1]); c.drawPath(path, p) }
        fun round(l: Float,t: Float,r: Float,b: Float,rad:Float=3f) { c.drawRoundRect(l,t,r,b,rad,rad,p) }
        when (kind) {
            "location" -> {val path=Path();path.moveTo(12f,22f);path.cubicTo(8f,17f,4f,12f,4f,8f);path.cubicTo(4f,-1f,20f,-1f,20f,8f);path.cubicTo(20f,12f,16f,17f,12f,22f);c.drawPath(path,p);c.drawCircle(12f,8f,3f,p)}
            "home" -> { line(2f,11f,12f,3f,22f,11f);line(5f,10f,5f,21f,19f,21f,19f,10f);line(10f,21f,10f,14f,14f,14f,14f,21f) }
            "minimize" -> { round(3f,4f,21f,20f,2f);round(12f,12f,19f,18f,1f) }
            "reply" -> { line(9f,5f,3f,11f,9f,17f);val path=Path();path.moveTo(3f,11f);path.lineTo(13f,11f);path.cubicTo(18f,11f,21f,14f,21f,20f);c.drawPath(path,p) }
            "pin" -> { line(8f,3f,16f,3f);line(9f,3f,9f,9f,5f,14f,19f,14f,15f,9f,15f,3f);line(12f,14f,12f,22f) }
            "bellOff" -> { c.drawArc(6f,3f,18f,16f,180f,180f,false,p);line(6f,9f,6f,15f,3f,18f,21f,18f,18f,15f,18f,9f);line(3f,3f,21f,21f) }
            "eye" -> { val path=Path();path.moveTo(2f,12f);path.cubicTo(7f,3f,17f,3f,22f,12f);path.cubicTo(17f,21f,7f,21f,2f,12f);c.drawPath(path,p);c.drawCircle(12f,12f,3f,p) }
            "star" -> { p.style=Paint.Style.FILL;val path=Path();path.moveTo(12f,2f);path.cubicTo(13.4f,8.6f,15.4f,10.6f,22f,12f);path.cubicTo(15.4f,13.4f,13.4f,15.4f,12f,22f);path.cubicTo(10.6f,15.4f,8.6f,13.4f,2f,12f);path.cubicTo(8.6f,10.6f,10.6f,8.6f,12f,2f);c.drawPath(path,p) }
            "island" -> { val path=Path();path.moveTo(3f,15f);path.cubicTo(7f,15f,7f,10f,10f,11f);path.cubicTo(13f,14f,15f,10f,18f,12f);path.lineTo(22f,15f);path.cubicTo(16f,20f,8f,21f,3f,15f);c.drawPath(path,p);c.drawArc(8f,18f,18f,23f,20f,140f,false,p);line(12f,2f,13f,5f,16f,6f,13f,7f,12f,10f,11f,7f,8f,6f,11f,5f,12f,2f) }
            "moments" -> { round(3f,3f,21f,21f,5f);c.drawCircle(15.8f,8f,1.5f,p);line(4f,17f,9f,11f,14f,16f,17f,13f,21f,17f) }
            "refresh" -> { c.drawArc(4f,4f,20f,20f,40f,285f,false,p);line(20f,3f,20f,9f,14f,9f) }
            "comment" -> { round(3f,4f,21f,18f,5f);line(7f,18f,7f,22f,12f,18f) }
            "heart", "heartFill" -> { if(kind=="heartFill")p.style=Paint.Style.FILL;val path=Path();path.moveTo(12f,21f);path.cubicTo(-6f,10f,4f,-2f,12f,7f);path.cubicTo(20f,-2f,30f,10f,12f,21f);c.drawPath(path,p) }
            "more" -> { p.style=Paint.Style.FILL;listOf(5f,12f,19f).forEach { c.drawCircle(it,12f,1.4f,p) } }
            "minus" -> line(5f,12f,19f,12f)
            "trash" -> { line(4f,6f,20f,6f);line(9f,6f,9f,3f,15f,3f,15f,6f);line(6f,6f,7f,21f,17f,21f,18f,6f);line(10f,10f,10f,17f);line(14f,10f,14f,17f) }
            "chat" -> { round(3f,3f,21f,17f,5f); line(6f,17f,6f,21f,11f,17f); c.drawCircle(8f,10f,.7f,p); c.drawCircle(12f,10f,.7f,p); c.drawCircle(16f,10f,.7f,p) }
            "chatFill" -> { p.style=Paint.Style.FILL;round(2f,3f,22f,18f,6f);val tail=Path();tail.moveTo(6f,16f);tail.lineTo(6f,22f);tail.lineTo(13f,17f);tail.close();c.drawPath(tail,p);p.color=Color.WHITE;listOf(7.5f,12f,16.5f).forEach{c.drawCircle(it,10.5f,1f,p)} }
            "contacts" -> { c.drawCircle(9f,8f,3.5f,p); c.drawArc(3f,13f,15f,23f,180f,180f,false,p); c.drawArc(12f,4f,20f,12f,-70f,150f,false,p); c.drawArc(13f,13f,23f,22f,210f,150f,false,p) }
            "me" -> { c.drawCircle(12f,8f,4f,p); c.drawArc(4f,14f,20f,26f,180f,180f,false,p) }
            "search" -> { c.drawCircle(10.5f,10.5f,6.5f,p); line(16f,16f,21f,21f) }
            "add" -> { line(12f,5f,12f,19f); line(5f,12f,19f,12f) }
            "back" -> { line(14f,5f,7f,12f,14f,19f) }
            "reply" -> { line(10f,5f,3f,11f,10f,17f);val path=Path();path.moveTo(3f,11f);path.lineTo(13f,11f);path.quadTo(21f,11f,21f,20f);c.drawPath(path,p) }
            "forward" -> {line(14f,4f,21f,10f,14f,16f);val path=Path();path.moveTo(21f,10f);path.lineTo(11f,10f);path.quadTo(3f,10f,3f,20f);c.drawPath(path,p)}
            "select" -> {c.drawCircle(12f,12f,9f,p);line(7f,12f,11f,16f,17f,8f)}
            "partial" -> {c.drawCircle(5f,4f,2f,p);c.drawCircle(19f,20f,2f,p);line(5f,6f,5f,18f,15f,18f);line(9f,6f,19f,6f,19f,17f);line(9f,12f,11f,12f);line(15f,12f,17f,12f)}
            "quote" -> {round(3f,3f,21f,18f,7f);line(9f,18f,9f,22f,14f,18f);line(9f,8f,7f,10f,9f,10f,9f,12f);line(16f,8f,14f,10f,16f,10f,16f,12f)}
            "arrow" -> { line(9f,6f,15f,12f,9f,18f) }
            "phone" -> { val path=Path(); path.moveTo(5f,3f); path.lineTo(9f,3f); path.lineTo(10f,8f); path.lineTo(7.5f,10f); path.quadTo(10f,15f,15f,17f); path.lineTo(17f,14f); path.lineTo(21f,16f); path.lineTo(21f,20f); path.quadTo(16f,24f,8f,16f); path.quadTo(1f,9f,5f,3f); c.drawPath(path,p) }
            "palette" -> { c.drawCircle(12f,12f,9f,p); c.drawCircle(8f,8f,1f,p); c.drawCircle(14f,6.8f,1f,p); c.drawCircle(18f,11f,1f,p); c.drawCircle(6.7f,13.5f,1f,p); c.drawCircle(12f,17f,2f,p) }
            "camera" -> { round(3f,6f,21f,20f); line(7f,6f,9f,3f,15f,3f,17f,6f); c.drawCircle(12f,13f,3.5f,p) }
            "hangup" -> { val path=Path(); path.moveTo(3f,10f); path.quadTo(12f,3f,21f,10f); path.lineTo(21f,15f); path.lineTo(16f,15f); path.lineTo(16f,11.5f); path.quadTo(12f,10f,8f,11.5f); path.lineTo(8f,15f); path.lineTo(3f,15f); path.close(); c.drawPath(path,p) }
            "video" -> { round(2f,5f,16f,19f); line(16f,9f,22f,6f,22f,18f,16f,15f) }
            "image" -> { round(3f,3f,21f,21f); c.drawCircle(8f,8f,1.5f,p); line(4f,18f,10f,12f,14f,16f,17f,13f,21f,18f) }
            "file" -> { line(5f,3f,14f,3f,20f,9f,20f,21f,5f,21f,5f,3f); line(14f,3f,14f,9f,20f,9f); line(9f,14f,16f,14f); line(9f,17f,14f,17f) }
            "code" -> { line(7f,6f,2f,12f,7f,18f); line(17f,6f,22f,12f,17f,18f); line(14f,3f,10f,21f) }
            "mic", "micOff", "dictate" -> { round(9f,2f,15f,15f); c.drawArc(6f,7f,18f,19f,0f,180f,false,p); line(12f,19f,12f,22f); if(kind=="micOff") line(3f,3f,21f,21f) }
            "stop" -> { p.style=Paint.Style.FILL;c.drawRoundRect(5f,5f,19f,19f,3f,3f,p) }
            "send" -> { line(3f,4f,22f,12f,3f,20f,6f,12f,3f,4f); line(6f,12f,15f,12f) }
            "smile" -> { c.drawCircle(12f,12f,9f,p); c.drawCircle(8.5f,9f,.6f,p); c.drawCircle(15.5f,9f,.6f,p); c.drawArc(7f,10f,17f,17f,15f,150f,false,p) }
            "speaker" -> { line(3f,9f,7f,9f,12f,5f,12f,19f,7f,15f,3f,15f,3f,9f); c.drawArc(10f,7f,20f,17f,-55f,110f,false,p); c.drawArc(8f,3f,26f,21f,-55f,110f,false,p) }
            "flip" -> { round(3f,6f,21f,20f); line(7f,6f,9f,3f,15f,3f,17f,6f); c.drawArc(8f,9f,16f,17f,0f,290f,false,p); line(12f,9f,16f,9f,16f,12f) }
            "close" -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            "check" -> line(5f,12f,10f,17f,20f,6f)
            "download" -> { line(12f,3f,12f,16f); line(7f,11f,12f,16f,17f,11f); line(4f,17f,4f,21f,20f,21f,20f,17f) }
            "play" -> { p.style=Paint.Style.FILL; val path=Path(); path.moveTo(7f,4f); path.lineTo(20f,12f); path.lineTo(7f,20f); path.close(); c.drawPath(path,p) }
            "pause" -> { line(8f,5f,8f,19f); line(16f,5f,16f,19f) }
            "copy" -> { round(8f,8f,21f,21f,2f); line(16f,5f,16f,3f,3f,3f,3f,16f,5f,16f) }
            "edit" -> { line(14f,5f,19f,10f); line(4f,15f,16f,3f,21f,8f,9f,20f,3f,21f,4f,15f) }
            "bell" -> { c.drawArc(6f,3f,18f,16f,180f,180f,false,p); line(6f,9f,6f,15f,3f,18f,21f,18f,18f,15f,18f,9f); c.drawArc(9f,18f,15f,23f,0f,180f,false,p) }
            "logout" -> { line(10f,4f,4f,4f,4f,20f,10f,20f); line(10f,12f,21f,12f); line(16f,7f,21f,12f,16f,17f) }
        }
        c.restore()
    }
}

/** Decorative audio bars; the duration is the actual recording length. */
class VoiceBars(context: Context, private val tint: Int) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=tint; strokeCap=Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val heights=floatArrayOf(.25f,.5f,.72f,.42f,.9f,.6f,.36f,.68f,.95f,.52f,.3f,.6f)
        val step=width.toFloat()/heights.size; paint.strokeWidth=step*.4f
        heights.forEachIndexed { i,h -> val x=step*(i+.5f); val half=height*h*.4f; canvas.drawLine(x,height/2f-half,x,height/2f+half,paint) }
    }
}
