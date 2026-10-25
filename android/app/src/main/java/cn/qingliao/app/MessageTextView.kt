package cn.qingliao.app

import android.content.Context
import android.widget.TextView

/** Android owns selection handles and their drag behavior. */
class MessageTextView(context:Context):TextView(context) {
    var rangeChanged:(()->Unit)?=null
    override fun onSelectionChanged(start:Int,end:Int){super.onSelectionChanged(start,end);rangeChanged?.invoke()}
}
