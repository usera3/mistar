package cn.qingliao.app

import android.widget.EditText

/** Capture the insertion range before recording. Revalidation prevents late cross-chat/draft writes. */
internal class DictationDraft(private val input:EditText,private val valid:()->Boolean) {
    private val original=input.text.toString()
    private val start=input.selectionStart.coerceIn(0,original.length)
    private val end=input.selectionEnd.coerceIn(start,original.length)
    private var applied=false
    fun insert(value:String):String? {
        if(applied||!valid()||input.text.toString()!=original)return "草稿已变化，识别结果未覆盖"
        if(value.isBlank())return "没有识别到文字"
        if(original.length-(end-start)+value.length>5000)return "文字超过消息长度限制，原草稿已保留"
        applied=true;input.text.replace(start,end,value);input.setSelection((start+value.length).coerceAtMost(input.length()));return null
    }
}
