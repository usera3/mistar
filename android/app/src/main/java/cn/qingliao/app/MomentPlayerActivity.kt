package cn.qingliao.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.*
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** Tap-to-play authenticated moment media. No public URL, background autoplay or permanent cache. */
class MomentPlayerActivity:Activity() {
    private val session=Hub.token
    private val owner=Hub.me.optString("id")
    private val palette by lazy{ThemeStore.current(this)}
    private var file:File?=null
    @Volatile private var transfer:okhttp3.Call?=null
    @Volatile private var inflightFile:File?=null
    private var player:MediaPlayer?=null
    private var video:VideoView?=null
    private var foreground=false
    private var ready=false
    @Volatile private var closed=false
    private var total=0
    private lateinit var status:TextView
    private lateinit var toggle:TextView
    private lateinit var seek:SeekBar
    private lateinit var stage:FrameLayout
    private val handler=Handler(Looper.getMainLooper())
    private val audio by lazy{getSystemService(Context.AUDIO_SERVICE) as AudioManager}
    private val focus by lazy{AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()).setOnAudioFocusChangeListener{change->if(change<0)pause()}.build()}
    private fun valid()=!closed&&session.isNotEmpty()&&Hub.token==session&&Hub.me.optString("id")==owner
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun time(ms:Int)="%02d:%02d".format(ms/60000,ms/1000%60)
    private fun text(value:String,size:Float=15f)=TextView(this).apply{text=value;textSize=size;setTextColor(Color.WHITE);gravity=Gravity.CENTER;includeFontPadding=false}
    private val tick=object:Runnable{override fun run(){if(!valid()){finish();return};if(ready){try{if(Hub.call!=null)pause();val position=if(video!=null)video!!.currentPosition else player?.currentPosition?:0;seek.progress=position.coerceAtLeast(0);status.text=time(position)+" / "+time(total);toggle.text=if(video?.isPlaying==true||player?.isPlaying==true)"暂停" else "播放"}catch(_:Exception){failed("媒体播放已中止")}};handler.postDelayed(this,400)}}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);if(!valid()||Hub.call!=null){finish();return}
        val kind=intent.getStringExtra("kind")?:""
        if(kind !in listOf("video","voice")){finish();return}
        window.statusBarColor=palette.callBottom;window.navigationBarColor=palette.callBottom
        window.decorView.systemUiVisibility=0
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(palette.callBottom);setPadding(dp(16),0,dp(16),0)}
        root.setOnApplyWindowInsetsListener{v,i->if(Build.VERSION.SDK_INT>=30){val bars=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(16)+bars.left,bars.top,dp(16)+bars.right,bars.bottom)}else v.setPadding(dp(16),i.systemWindowInsetTop,dp(16),i.systemWindowInsetBottom);i}
        root.addView(text("‹  "+if(kind=="video")"视频动态" else "语音动态",18f).apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(56);contentDescription="关闭媒体播放";setOnClickListener{finish()}})
        stage=FrameLayout(this);root.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        stage.addView(Glyph(this,if(kind=="video")"video" else "mic",palette.callMuted),FrameLayout.LayoutParams(dp(80),dp(80),Gravity.CENTER))
        status=text("正在加载…",14f);root.addView(status,LinearLayout.LayoutParams(-1,dp(36)))
        seek=SeekBar(this).apply{isEnabled=false;contentDescription="播放进度";progressTintList=android.content.res.ColorStateList.valueOf(palette.primary);thumbTintList=android.content.res.ColorStateList.valueOf(palette.primary)}
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){if(ready){video?.seekTo(seek.progress);player?.seekTo(seek.progress)}};override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){}})
        root.addView(seek,LinearLayout.LayoutParams(-1,dp(48)))
        toggle=text("加载中",16f).apply{isEnabled=false;contentDescription="播放或暂停";background=BrandUi.primary(this@MomentPlayerActivity,palette);setTextColor(palette.onPrimary);setOnClickListener{if(video?.isPlaying==true||player?.isPlaying==true)pause() else play()}}
        root.addView(toggle,LinearLayout.LayoutParams(-1,dp(52)).apply{bottomMargin=dp(24)})
        setContentView(root);root.requestApplyInsets()
        Hub.io.execute{var downloaded:File?=null
            try {
                val local=intent.getStringExtra("local_uri")
                downloaded=if(local!=null)MomentMediaFiles.prepare(this,Uri.parse(local),kind,::valid).file else {
                    val id=intent.getStringExtra("media_id")?:"";require(id.matches(Regex("[a-f0-9]{32}"))){"媒体标识不正确"}
                    val temp=File.createTempFile("moment-play-",".media",cacheDir);downloaded=temp;inflightFile=temp
                    Hub.http.newCall(Request.Builder().url(Hub.SERVER+"/api/post-media/$id").header("Authorization","Bearer $session").build()).also{transfer=it;if(!valid())it.cancel()}.execute().use{response->
                        check(response.isSuccessful){"媒体已删除或无权查看"};val body=response.body?:error("媒体无法读取");require(body.contentLength()<=MomentMediaFiles.maximum(kind)){"媒体文件过大"}
                        body.byteStream().use{input->temp.outputStream().use{output->val buffer=ByteArray(65536);var size=0L;while(true){check(valid()){"播放已关闭"};val count=input.read(buffer);if(count<0)break;size+=count;require(size<=MomentMediaFiles.maximum(kind)){"媒体文件过大"};output.write(buffer,0,count)}}}
                    };temp
                }
                transfer=null;val current=downloaded;handler.post{if(valid()){file=current;inflightFile=null;try{prepare(kind)}catch(_:Exception){failed("媒体无法播放")}}else current?.delete()}
            }catch(e:Exception){downloaded?.delete();handler.post{if(valid()){status.text=e.message?:"媒体无法播放";toggle.text="关闭";toggle.isEnabled=true;toggle.setOnClickListener{finish()}}}}
        }
    }
    private fun prepare(kind:String){
        if(kind=="video"){
            val view=VideoView(this);video=view;stage.removeAllViews();stage.addView(view,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));view.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
            view.setOnPreparedListener{media->total=media.duration;ready=total in 1..180000;if(ready){seek.max=total;seek.isEnabled=true;toggle.isEnabled=true;if(foreground)play()}else failed("视频时长不支持")}
            view.setOnErrorListener{_,_,_->failed("视频无法解码，请选择兼容的MP4");true};view.setVideoURI(Uri.fromFile(file))
        }else{
            val audioPlayer=MediaPlayer();player=audioPlayer
            audioPlayer.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());audioPlayer.setDataSource(file!!.absolutePath)
            audioPlayer.setOnPreparedListener{total=it.duration;ready=total in 1..60000;if(ready){seek.max=total;seek.isEnabled=true;toggle.isEnabled=true;if(foreground)play()}else failed("语音时长不支持")}
            audioPlayer.setOnErrorListener{_,_,_->failed("语音无法解码");true};audioPlayer.prepareAsync()
        }
    }
    private fun play(){if(!valid()||!foreground||!ready||Hub.call!=null)return;if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return;video?.start();player?.start();toggle.text="暂停"}
    private fun failed(message:String){ready=false;pause();status.text=message;seek.isEnabled=false;toggle.text="关闭";toggle.isEnabled=true;toggle.setOnClickListener{finish()}}
    private fun pause(){runCatching{video?.pause();if(player?.isPlaying==true)player?.pause()};audio.abandonAudioFocusRequest(focus);if(::toggle.isInitialized)toggle.text="播放"}
    override fun onResume(){super.onResume();foreground=true;if(!valid())finish()else{handler.removeCallbacks(tick);handler.post(tick)}}
    override fun onPause(){foreground=false;handler.removeCallbacks(tick);pause();super.onPause()}
    override fun onDestroy(){closed=true;transfer?.cancel();transfer=null;inflightFile?.delete();inflightFile=null;handler.removeCallbacksAndMessages(null);pause();video?.stopPlayback();player?.release();player=null;file?.delete();file=null;super.onDestroy()}
    override fun finish(){super.finish();Motion.activityClose(this)}
}
