package cn.qingliao.app

import java.io.File
import java.io.FileInputStream

/** Accept local audio containers, never playlists or URLs. Decoders receive a bounded file descriptor. */
internal object LocalAudio {
    fun requireContainer(file:File){
        require(file.isFile&&file.length() in 1..PayloadRules.MAX_FILE){"语音文件不可用"}
        val header=FileInputStream(file).use{input->val b=ByteArray(16);val count=input.read(b);if(count>0)b.copyOf(count)else byteArrayOf()}
        fun has(value:String,at:Int=0):Boolean{val b=value.toByteArray(Charsets.US_ASCII);return at+b.size<=header.size&&b.indices.all{header[at+it]==b[it]}}
        val sync=header.size>=2&&(header[0].toInt() and 255)==255&&(header[1].toInt() and 224)==224
        val webm=header.size>=4&&header.take(4).map{it.toInt() and 255}==listOf(0x1a,0x45,0xdf,0xa3)
        require(has("RIFF")&&has("WAVE",8)||has("ftyp",4)||has("OggS")||has("fLaC")||has("ID3")||has("#!AMR")||sync||webm){"这条语音的格式暂不支持"}
    }
}
