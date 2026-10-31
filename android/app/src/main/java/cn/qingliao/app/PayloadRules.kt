package cn.qingliao.app

import org.json.JSONObject

/** A valid signature authenticates a sender, not the sender's supplied file names or sizes. */
object PayloadRules {
    const val MAX_FILE=25L*1024*1024
    fun validate(inner:JSONObject,fid:String,participants:Set<String>?=null) {
        val kind=inner.get("type");val text=inner.opt("text") ?: ""
        require(kind is String && kind in listOf("text","html","image","voice","file")) { "消息类型不正确" }
        require(text is String && text.length<=if(kind=="html")50000 else 5000) { "消息内容过长或格式不正确" }
        inner.optJSONObject("quote")?.let{quote->
            require(quote.length()==4&&quote.get("id") is String&&quote.get("sender") is String&&quote.get("type") is String)
            val id=quote.getString("id");require(java.util.UUID.fromString(id).toString()==id)
            require(quote.getString("sender").matches(Regex("[0-9]{8}"))&&(participants==null||quote.getString("sender") in participants))
            require(quote.get("excerpt") is String&&quote.getString("excerpt").length<=160)
            require(quote.getString("type") in listOf("text","html","image","voice","file"))
        }
        require(!inner.has("quote")||inner.optJSONObject("quote")!=null){"引用格式不正确"}
        if(inner.has("location")){require(kind=="text"&&inner.optJSONObject("location")!=null);LocationPayload.validate(inner.getJSONObject("location"));require(text==LocationPayload.text(inner.getJSONObject("location"))){"位置描述不一致"}}
        if(kind in listOf("text","html")) {
            require(fid.isEmpty()&&!inner.has("file")) { "文字消息不应包含附件" };return
        }
        require(fid.matches(Regex("[a-f0-9]{32}"))) { "附件编号不正确" }
        val file=inner.getJSONObject("file")
        require(file.getString("id")==fid)
        val name=file.get("name");val mime=file.get("mime");val bytes=file.get("size")
        require(name is String&&name.length in 1..180&&name.none { it.isISOControl()||it=='/'||it=='\\'||it in '\u202a'..'\u202e'||it in '\u2066'..'\u2069' }) { "附件名称不正确" }
        require(mime is String&&mime.length<=100&&mime.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))) { "附件类型不正确" }
        require(bytes is Number&&bytes.toDouble()==bytes.toLong().toDouble()&&bytes.toLong() in 1..MAX_FILE) { "附件大小不正确" }
        require(file.getString("crypto").length in 30..8192) { "附件密钥格式不正确" }
        require(file.getString("context").matches(Regex("qingliao:attachment:1:[a-fA-F0-9-]{36}"))) { "附件上下文不正确" }
    }
}
