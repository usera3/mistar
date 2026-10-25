package cn.qingliao.app

import android.app.*
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.widget.TextView
import java.io.File
import java.util.UUID

/** Recording starts only after the explicit record action and Android microphone grant. */
class MomentRecorder(private val activity:Activity,private val done:(Uri)->Unit) {
    private var recorder:MediaRecorder?=null
    private var file:File?=null
    private var dialog:AlertDialog?=null
    private var complete=false
    private var started=0L
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var label:TextView
    private val clock=object:Runnable{override fun run(){if(recorder==null)return;if(Hub.call!=null){close();return};val seconds=(SystemClock.elapsedRealtime()-started)/1000;label.text="正在录音  %02d:%02d\n最长60秒，完成后可选择可见范围".format(seconds/60,seconds%60);if(seconds>=59)finish()else handler.postDelayed(this,250)}}
    fun show(){
        if(Hub.call!=null)return
        try {
            val directory=File(activity.filesDir,"moment-recordings").apply{mkdirs()}
            file=File(directory,UUID.randomUUID().toString()+".m4a")
            recorder=(if(Build.VERSION.SDK_INT>=31)MediaRecorder(activity)else @Suppress("DEPRECATION")MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC);setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);setAudioEncoder(MediaRecorder.AudioEncoder.AAC);setAudioEncodingBitRate(64000);setAudioSamplingRate(44100);setMaxDuration(59000);setMaxFileSize(5L*1024*1024);setOutputFile(file!!.absolutePath);setOnInfoListener{_,what,_->if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED||what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)finish()};prepare();start()
            }
            started=SystemClock.elapsedRealtime()
            label=TextView(activity).apply{setPadding(48,32,48,32);textSize=16f}
            dialog=AlertDialog.Builder(activity).setTitle("录制动态语音").setView(label).setNegativeButton("取消"){_,_->close()}.setPositiveButton("完成录音",null).create().apply {
                setOnDismissListener{close()};show();getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{finish()}
                listOf(-1,-2).forEach{getButton(it).setTextColor(ThemeStore.current(activity).accent)}
            }
            handler.post(clock)
        }catch(_:Exception){close();android.widget.Toast.makeText(activity,"录音无法启动，请检查麦克风权限",android.widget.Toast.LENGTH_LONG).show()}
    }
    private fun finish(){
        val current=recorder?:return
        handler.removeCallbacks(clock);recorder=null
        try{current.stop();current.release();require(file!!.length()>32);complete=true;done(Uri.fromFile(file));dialog?.dismiss()}
        catch(_:Exception){runCatching{current.release()};close();android.widget.Toast.makeText(activity,"录音太短，请重新录制",android.widget.Toast.LENGTH_SHORT).show()}
    }
    fun close(){handler.removeCallbacks(clock);recorder?.let{runCatching{it.stop()};it.release()};recorder=null;if(!complete)file?.delete();val old=dialog;dialog=null;old?.setOnDismissListener(null);old?.dismiss()}
    companion object{
        fun discard(context:Context,uri:Uri?){if(uri?.scheme!="file")return;runCatching{val file=File(uri.path!!).canonicalFile;val directory=File(context.filesDir,"moment-recordings").canonicalFile;if(file.parentFile==directory)file.delete()}}
    }
}
