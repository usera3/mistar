package cn.qingliao.app

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Wire compatibility with the user-supplied WeType extension. Only for external dictation. */
internal object WetypeWire {
    const val SALT="aI0wC6qT1nB3nN5r"
    const val BOOTSTRAP="G7E1C8B0D7D7E4L3J2U9D6W0B2M8Q6O5"
    val P=BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFF",16)
    val N=BigInteger("FFFFFFFE0000000075A30D1B9038A115",16)
    private val A=P-BigInteger.valueOf(3)
    private val B=BigInteger("E87579C11079F43DD824993C2CEE5ED3",16)
    private val G=BigInteger("161FF7528B899B2D0C28607CA52C5B86",16) to BigInteger("CF5AC8395BAFEB13C02DA292DDED7A83",16)
    private val random=SecureRandom()
    val HEADER="#!OPUS_RAW_V1".toByteArray()+byteArrayOf(2,1,0)
    fun hex(b:ByteArray)=b.joinToString(""){"%02X".format(Locale.ROOT,it.toInt() and 255)}
    fun unhex(s:String):ByteArray {require(s.length%2==0&&s.matches(Regex("[a-fA-F0-9]*")));return ByteArray(s.length/2){s.substring(it*2,it*2+2).toInt(16).toByte()}}
    fun hash(kind:String,b:ByteArray)=hex(MessageDigest.getInstance(kind).digest(b))
    fun letters(n:Int)=ByteArray(n).also{random.nextBytes(it)}.map{('a'.code+(it.toInt() and 255)%26).toChar()}.joinToString("")
    fun device():String {val prefix="AND"+hex(ByteArray(9).also{random.nextBytes(it)}).lowercase(Locale.ROOT).take(17)+letters(12);return prefix+hash("MD5",(prefix+SALT).toByteArray())}
    private fun pointAdd(a:Pair<BigInteger,BigInteger>?,b:Pair<BigInteger,BigInteger>?):Pair<BigInteger,BigInteger>? {
        if(a==null)return b;if(b==null)return a
        val(x,y)=a;val(u,v)=b;if(x==u&&(y+v).mod(P)==BigInteger.ZERO)return null
        val m=(if(x==u)(BigInteger.valueOf(3)*x*x+A)*(BigInteger.valueOf(2)*y).mod(P).modInverse(P) else (v-y)*(u-x).mod(P).modInverse(P)).mod(P)
        val w=(m*m-x-u).mod(P);return w to (m*(x-w)-y).mod(P)
    }
    private fun multiply(s:BigInteger,p:Pair<BigInteger,BigInteger>?):Pair<BigInteger,BigInteger>? {
        require(s.signum()>=0);var k=s;var q=p;var out:Pair<BigInteger,BigInteger>?=null
        while(k.signum()!=0){if(k.testBit(0))out=pointAdd(out,q);q=pointAdd(q,q);k=k.shiftRight(1)};return out
    }
    fun scalar():BigInteger {while(true){val n=BigInteger(128,random);if(n.signum()>0&&n<N)return n}}
    private fun coord(n:BigInteger)=n.toString(16).padStart(32,'0').uppercase(Locale.ROOT)
    fun publicKey(n:BigInteger):String {require(n.signum()>0&&n<N);val(x,y)=multiply(n,G)!!;return "04"+coord(x)+coord(y)}
    fun sharedKey(n:BigInteger,s:String):String {
        require(n.signum()>0&&n<N&&s.matches(Regex("04[0-9a-fA-F]{64}")))
        val x=BigInteger(s.substring(2,34),16);val y=BigInteger(s.substring(34),16)
        require(x<P&&y<P&&(y*y-x*x*x-A*x-B).mod(P)==BigInteger.ZERO&&multiply(N,x to y)==null)
        return coord(multiply(n,x to y)!!.first)
    }
    fun crypt(b:ByteArray,key:String,encrypt:Boolean):ByteArray=Cipher.getInstance("AES/ECB/PKCS5Padding").run{init(if(encrypt)Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,SecretKeySpec(key.toByteArray(),"AES"));doFinal(b)}
    fun varint(value:BigInteger):ByteArray {require(value.signum()>=0&&value.bitLength()<=64);var n=value;val out=ByteArrayOutputStream();while(n>BigInteger.valueOf(127)){out.write(n.toInt() and 127 or 128);n=n.shiftRight(7)};out.write(n.toInt());return out.toByteArray()}
    private fun v(n:Long)=varint(BigInteger.valueOf(n))
    fun integer(tag:Int,n:Long)=v((tag*8).toLong())+v(n)
    fun blob(tag:Int,b:ByteArray)=v((tag*8+2).toLong())+v(b.size.toLong())+b
    fun string(tag:Int,s:String)=blob(tag,s.toByteArray())
    fun dh(pub:String,code:String,name:String="弥星语音输入")=string(1,pub)+string(2,"")+integer(3,1)+string(4,code)+string(6,"")+string(7,"")+string(8,name)+string(9,"")
    fun uin(code:String,pub:String,id:String)=string(1,code)+string(2,pub)+string(4,id)+string(5,"")
    fun voice(id:String,seq:Int,audio:ByteArray,end:Boolean,total:Int,enhanced:Boolean=false):ByteArray {
        require(id.isNotEmpty()&&seq>=0&&total>=0&&(seq!=0||(audio.isEmpty()&&end)))
        return blob(1,string(2,id)+(if(audio.isEmpty())byteArrayOf() else blob(4,audio))+integer(5,5)+(if(end)integer(6,1) else byteArrayOf())+(if(seq>0)integer(7,seq.toLong()) else byteArrayOf())+(if(total>0)integer(11,total.toLong()) else byteArrayOf())+(if(enhanced)integer(14,1) else byteArrayOf()))
    }
    class Fields(private val bytes:ByteArray) {
        private var pos=0
        val all=mutableMapOf<Int,MutableList<Pair<Int,Any>>>()
        private fun read():BigInteger {var n=BigInteger.ZERO;for(i in 0..9){require(pos<bytes.size);val b=bytes[pos++].toInt() and 255;require(i!=9||b<=1);n=n.or(BigInteger.valueOf((b and 127).toLong()).shiftLeft(i*7));if(b<128)return n};error("Invalid varint")}
        init {
            require(bytes.size<=8*1024*1024)
            while(pos<bytes.size){val key=read();require(key.bitLength()<=32);val tag=key.shiftRight(3).toInt();val wire=key.toInt() and 7;require(tag in 1 until (1 shl 29))
                val value:Any=if(wire==0)read() else {val size=when(wire){1->8;5->4;2->{val n=read();require(n.bitLength()<31);n.toInt()};else->error("Invalid wire")};require(size<=bytes.size-pos);bytes.copyOfRange(pos,pos+size).also{pos+=size}}
                all.getOrPut(tag){mutableListOf()}.add(wire to value)
            }
        }
        fun number(tag:Int)=all[tag]?.lastOrNull{it.first==0}?.second as? BigInteger?:BigInteger.ZERO
        fun int(tag:Int):Int {val n=number(tag);require(n.bitLength()<31);return n.toInt()}
        fun long(tag:Int):Long {val n=number(tag);require(n.bitLength()<63);return n.toLong()}
        fun bytes(tag:Int)=all[tag]?.lastOrNull{it.first==2}?.second as? ByteArray?:byteArrayOf()
        fun text(tag:Int)=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes(tag))).toString()
        fun result():Fields {require(all.isNotEmpty()&&number(1).signum()==0);return this}
    }
    fun snappy(b:ByteArray):ByteArray {
        var pos=0;var size=0;var shift=0
        while(true){require(pos<b.size&&shift<=21);val v=b[pos++].toInt() and 255;size=size or ((v and 127) shl shift);if(v<128)break;shift+=7}
        require(size in 0..8*1024*1024);val out=ByteArray(size);var at=0
        fun little(n:Int):Long{require(pos+n<=b.size);var v=0L;repeat(n){v=v or ((b[pos++].toLong() and 255) shl (it*8))};return v}
        while(pos<b.size){val tag=b[pos++].toInt() and 255;val kind=tag and 3
            if(kind==0){var len=(tag shr 2)+1;if(len>60){val n=little(len-60);require(n<size.toLong()+1);len=n.toInt()+1};require(len<=b.size-pos&&len<=size-at);b.copyInto(out,at,pos,pos+len);at+=len;pos+=len}
            else {val offset=if(kind==1)little(1)+((tag and 224) shl 3) else little(if(kind==2)2 else 4);val len=if(kind==1)4+((tag shr 2) and 7) else 1+(tag shr 2);require(offset>0&&offset<=at&&len<=size-at);repeat(len){out[at]=out[at-offset.toInt()];at++}}
        };require(at==size);return out
    }
    fun headers(kind:String,encrypted:ByteArray,task:Int,uin:BigInteger=BigInteger.ZERO,key:String="",token:String="",timestamp:Long=System.currentTimeMillis(),trace:String=letters(16)):Map<String,String> {
        val ts=timestamp.toString();val digest=hash("MD5",encrypted);val t=task.toString();val version="4.0.0.58119"
        val out=linkedMapOf("Kb-OsType" to "1","Kb-Version" to version,"Kb-SystemVersion" to "34","Kb-PackageType" to "2","Kb-TimeStamp" to ts,"Kb-BodyMd5" to digest,"Kb-TraceId" to trace,"Kb-TaskId" to t,"Kb-Scene" to "2","Kb-CompressionType" to "1","Content-Type" to "application/octet-stream","Use_DebugNet" to "0")
        val ordered=mutableListOf("1",version,"2")
        when(kind){
            "dh"->{ordered.addAll(listOf(ts,digest,trace,t));out["Kb-Uin"]=uin.toString();out["Kb-DeviceCodeRestrictionV2"]="1"}
            "uin"->{ordered.addAll(listOf(ts,digest,token,trace,t));out["Kb-GenUinToken"]=token;out["Kb-DeviceCodeRestrictionV2"]="1"}
            "api"->{require(key.isNotEmpty()&&uin.signum()>0);ordered.addAll(listOf("4547","0",ts,digest,uin.toString(),trace,t));out.putAll(mapOf("Kb-Uin" to uin.toString(),"Kb-SharedKeySuffix" to key.takeLast(4),"Kb-CmdId" to "4547","Kb-SubCmdId" to "0"))}
            else->error("Unknown endpoint")
        };out["Kb-Sign"]=hash("SHA-256",(ordered.joinToString("")+SALT).toByteArray());return out
    }
}
