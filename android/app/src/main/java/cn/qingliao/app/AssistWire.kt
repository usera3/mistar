package cn.qingliao.app

import android.graphics.PointF
import android.graphics.RectF
import org.json.JSONObject

/** Small, input-only protocol. No scripts, text/clipboard, file operations or accessibility trees. */
internal object AssistWire {
    const val CHANNEL="mistar.remote-assistance.v2"
    const val VERSION=2
    const val MAX_BYTES=1536
    data class Geometry(val width:Int,val height:Int,val rotation:Int){
        init{require(width in 64..16384&&height in 64..16384&&rotation in 0..3)}
        fun json()=JSONObject().put("w",width).put("h",height).put("r",rotation)
        fun videoRect(width:Int,height:Int):RectF {
            val scale=minOf(width.toFloat()/this.width,height.toFloat()/this.height)
            val w=this.width*scale;val h=this.height*scale
            return RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
        }
        fun normalized(x:Float,y:Float,width:Int,height:Int):PointF?{
            if(width<=0||height<=0||!x.isFinite()||!y.isFinite())return null
            val rect=videoRect(width,height);if(!rect.contains(x,y))return null
            return PointF(((x-rect.left)/rect.width()).coerceIn(0f,1f),((y-rect.top)/rect.height()).coerceIn(0f,1f))
        }
    }
    data class Input(val kind:String,val x:Float=0f,val y:Float=0f,val endX:Float=x,val endY:Float=y,val duration:Int=60){
        init {
            require(kind in listOf("tap","long","swipe","back","home"))
            require(listOf(x,y,endX,endY).all{it.isFinite()&&it in 0f..1f})
            require(duration in 40..1200)
            if(kind=="long")require(duration in 500..1200)
        }
        fun json()=JSONObject().put("op",kind).put("x",x.toDouble()).put("y",y.toDouble()).put("ex",endX.toDouble()).put("ey",endY.toDouble()).put("ms",duration)
    }
    fun id(value:String)=value.matches(Regex("[a-f0-9]{32}"))
    fun parse(bytes:ByteArray):JSONObject {
        require(bytes.size in 1..MAX_BYTES);val raw=String(bytes,Charsets.UTF_8);var depth=0;var quoted=false;var escape=false
        raw.forEach{c->if(quoted){if(escape)escape=false else if(c=='\\')escape=true else if(c=='"')quoted=false}else when(c){'"'->quoted=true;'{','['->{depth++;require(depth<=4)};'}',']'->{depth--;require(depth>=0)}}}
        require(!quoted&&depth==0);return JSONObject(raw)
    }
    fun integer(p:JSONObject,key:String,min:Int,max:Int):Int {val v=p.get(key);require(v is Int&&v in min..max);return v}
    fun geometry(p:JSONObject)=Geometry(integer(p,"w",64,16384),integer(p,"h",64,16384),integer(p,"r",0,3)).also{keys(p,setOf("w","h","r"))}
    fun input(p:JSONObject):Input {
        keys(p,setOf("op","x","y","ex","ey","ms"))
        fun coord(k:String):Float{val v=p.get(k);require(v is Number);return v.toFloat().also{require(it.isFinite()&&it in 0f..1f)}}
        return Input(p.getString("op"),coord("x"),coord("y"),coord("ex"),coord("ey"),integer(p,"ms",40,1200))
    }
    fun keys(p:JSONObject,allowed:Set<String>){require(p.keys().asSequence().toSet()==allowed)}
}
