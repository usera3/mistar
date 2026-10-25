package cn.qingliao.app

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.text.*
import android.view.*
import android.widget.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.*
import java.util.UUID

class PostActivity:Activity() {
    private lateinit var palette:ThemePalette
    private lateinit var input:EditText
    private lateinit var grid:LinearLayout
    private lateinit var counter:TextView
    private lateinit var publish:TextView
    private lateinit var progress:TextView
    private lateinit var photoCount:TextView
    private lateinit var photoHint:TextView
    private lateinit var draftHint:TextView
    private lateinit var progressBar:ProgressBar
    private var photoDialog:Dialog?=null
    private var photoActions:Dialog?=null
    private var recorder:MomentRecorder?=null
    private var mediaUri:Uri?=null
    private var mediaKind=""
    private var mediaId=""
    private var mediaDuration=0L
    private var mediaCover:ByteArray?=null
    private var mediaRevision=0
    private var checkingMedia=false
    private var audience="friends"
    private val compact by lazy{CompactUi.enabled(this)}
    private lateinit var mediaBox:LinearLayout
    private lateinit var audienceLabel:TextView
    private val openingSession=Hub.token
    private val photos=mutableListOf<Uri>()
    private val uploaded=mutableMapOf<String,String>()
    private var clientId=UUID.randomUUID().toString()
    private var busy=false
    private var done=false
    private var attempted=false
    private var ownerId=""
    private val saveDraft=Runnable { persistDraft();if(!done&&::draftHint.isInitialized)draftHint.text="草稿已保存到本机" }
    private val prefs get()=getSharedPreferences("moments-draft",MODE_PRIVATE)
    private val draftKey get()=ownerId
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun bg(color:Int,radius:Int=14)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
    private fun col()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun label(value:String,size:Float=14f,color:Int=palette.ink,bold:Boolean=false)=TextView(this).apply { text=value;textSize=size;setTextColor(color);includeFontPadding=false;if(bold)setTypeface(typeface,Typeface.BOLD) }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);palette=ThemeStore.current(this)
        if(Hub.token.isEmpty()){finish();return}
        ownerId=Hub.me.optString("id")
        val draft=try { JSONObject(state?.getString("draft")?:prefs.getString(draftKey,"{}")?:"{}") }catch(_:Exception){JSONObject()}
        audience="friends"
        mediaKind=draft.optString("media_kind").takeIf{it in listOf("video","voice")}?:""
        if(mediaKind.isNotEmpty())mediaUri=draft.optString("media_uri").takeIf{it.isNotEmpty()}?.let{Uri.parse(it)}
        mediaDuration=draft.optLong("media_duration")
        mediaId=draft.optString("media_id").takeIf{it.matches(Regex("[a-f0-9]{32}"))}?:""
        clientId=draft.optString("client_id").ifEmpty { UUID.randomUUID().toString() }
        attempted=draft.optBoolean("attempted")
        draft.optJSONArray("photos")?.let { a -> for(i in 0 until minOf(a.length(),9))photos.add(Uri.parse(a.getString(i))) }
        val root=col().apply { setBackgroundColor(palette.canvas) }
        root.setOnApplyWindowInsetsListener { v,insets ->
            if(Build.VERSION.SDK_INT>=30){val b=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom)}
            else v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            insets
        }
        window.statusBarColor=palette.canvas;window.navigationBarColor=palette.canvas;window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val top=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(20),dp(10)) }
        top.addView(FrameLayout(this).apply { contentDescription="返回动态";addView(Glyph(this@PostActivity,"back"),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));setOnClickListener { leave() } },LinearLayout.LayoutParams(dp(44),dp(44)))
        top.addView(label("发布动态",if(compact)16f else 20f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        publish=label("发布",14f,palette.onPrimary,true).apply { gravity=Gravity.CENTER;setPadding(dp(20),0,dp(20),0);background=BrandUi.primary(this@PostActivity,palette,16);contentDescription="发布这条动态";setOnClickListener { submit() } }
        top.addView(publish,LinearLayout.LayoutParams(-2,dp(40)));root.addView(top)
        Motion.press(publish)
        val form=col().apply { setPadding(dp(20),dp(18),dp(20),dp(28));background=BrandUi.card(this@PostActivity,palette,24) }
        input=EditText(this).apply {
            hint="分享此刻的想法…";textSize=18f;setTextColor(palette.ink);setHintTextColor(palette.muted);background=null;gravity=Gravity.TOP;minLines=4;maxLines=12
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters=arrayOf(InputFilter.LengthFilter(5000));setPadding(0,dp(10),0,dp(10));contentDescription="动态正文";setText(draft.optString("text"));highlightColor=palette.soft
            if(Build.VERSION.SDK_INT>=29)textCursorDrawable?.setTint(palette.accent)
        };form.addView(input,LinearLayout.LayoutParams(-1,-2))
        counter=label("",12f,palette.muted).apply { gravity=Gravity.END };form.addView(counter,LinearLayout.LayoutParams(-1,dp(28)))
        photoCount=label("",13f,palette.ink,true).apply { setPadding(0,dp(12),0,dp(12)) };form.addView(photoCount)
        grid=col();form.addView(grid,LinearLayout.LayoutParams(-1,-2))
        mediaBox=col();form.addView(mediaBox,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(14)})
        val mediaTools=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        fun mediaTool(title:String,description:String,action:()->Unit){mediaTools.addView(label(title,13f,palette.accent,true).apply{gravity=Gravity.CENTER;minimumHeight=dp(48);background=bg(palette.soft,14);contentDescription=description;setOnClickListener{if(!busy&&!checkingMedia)action()}},LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(5)})}
        mediaTool("选视频","选择视频"){chooseMedia("video")};mediaTool("选音频","选择音频"){chooseMedia("voice")};mediaTool("录语音","录制语音"){recordVoice()};form.addView(mediaTools,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        form.addView(label("图片、视频或语音三选一\n视频：MP4 · 25MB/3分钟\n语音：M4A、PCM WAV · 5MB/60秒",11f,palette.muted).apply{setPadding(0,dp(10),0,0);setLineSpacing(dp(3).toFloat(),1f)})
        photoHint=label("点按预览 · 长按照片调整顺序",12f,palette.muted).apply { setPadding(0,dp(10),0,0) };form.addView(photoHint)
        val privacy=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(if(compact)8 else 14),dp(if(compact)10 else 16),dp(if(compact)8 else 14),dp(if(compact)10 else 16));background=bg(palette.canvas) }
        privacy.addView(Glyph(this,"contacts",palette.accent),LinearLayout.LayoutParams(dp(20),dp(20)))
        audienceLabel=label("",14f).apply{setPadding(dp(12),0,0,0)};privacy.addView(audienceLabel,LinearLayout.LayoutParams(0,-2,1f));privacy.addView(Glyph(this,"me",palette.accent),LinearLayout.LayoutParams(dp(18),dp(18)))
        privacy.contentDescription="仅好友圈 · 自己和好友可见";privacy.isFocusable=false;privacy.setOnClickListener{Toast.makeText(this,"广场已下线，动态仅自己和好友可见",Toast.LENGTH_SHORT).show()}
        form.addView(privacy,0,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) });renderAudience()
        draftHint=label("草稿自动保存在本机",12f,palette.muted).apply { setPadding(0,dp(14),0,dp(8)) };form.addView(draftHint)
        progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { visibility=View.GONE;progressTintList=android.content.res.ColorStateList.valueOf(palette.accent);progressBackgroundTintList=android.content.res.ColorStateList.valueOf(palette.soft) };form.addView(progressBar,LinearLayout.LayoutParams(-1,dp(5)))
        progress=label("",13f,palette.accent).apply { visibility=View.GONE };form.addView(progress)
        val scroll=ScrollView(this).apply { addView(form);setPadding(dp(16),dp(4),dp(16),dp(16));clipToPadding=false;isFillViewport=true;isVerticalScrollBarEnabled=false };root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        input.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){edited();update();scheduleDraft()}
            override fun afterTextChanged(s:Editable?){}
        });renderPhotos();renderMedia();update()
    }
    private fun update() { counter.text="${input.text.length} / 5000";publish.isEnabled=!busy&&!checkingMedia&&(input.text.isNotBlank()||photos.isNotEmpty()||mediaUri!=null);publish.alpha=if(publish.isEnabled)1f else .45f }
    private fun edited(){if(attempted&&!busy){clientId=UUID.randomUUID().toString();uploaded.clear();mediaId="";attempted=false}}
    private fun draft()=JSONObject().put("text",input.text.toString()).put("photos",JSONArray(photos.map { it.toString() })).put("client_id",clientId).put("attempted",attempted).put("audience",audience).put("media_kind",mediaKind).put("media_uri",mediaUri?.toString()?:"").put("media_duration",mediaDuration).put("media_id",mediaId)
    private fun scheduleDraft(){Hub.main.removeCallbacks(saveDraft);if(!done)Hub.main.postDelayed(saveDraft,700)}
    private fun persistDraft(){if(!done&&::input.isInitialized){if(input.text.isBlank()&&photos.isEmpty()&&mediaUri==null)prefs.edit().remove(draftKey).apply()else prefs.edit().putString(draftKey,draft().toString()).apply()}}
    override fun onSaveInstanceState(out:Bundle){super.onSaveInstanceState(out);out.putString("draft",draft().toString())}
    override fun onResume(){super.onResume();if(openingSession.isEmpty()||Hub.token!=openingSession||Hub.me.optString("id")!=ownerId)finish()}
    override fun onPause(){recorder?.close();recorder=null;super.onPause();Hub.main.removeCallbacks(saveDraft);persistDraft()}
    override fun onDestroy(){mediaRevision++;recorder?.close();Hub.main.removeCallbacks(saveDraft);photoDialog?.dismiss();photoActions?.dismiss();super.onDestroy()}
    override fun finish(){super.finish();Motion.activityClose(this)}
    private fun renderAudience(){audienceLabel.text=if(compact)"仅好友可见" else "仅好友圈 · 自己和好友可见"}
    private fun replaceMedia(action:()->Unit){
        if(photos.isNotEmpty()||mediaUri!=null)AlertDialog.Builder(this).setTitle("更换动态附件？").setMessage("图片、视频和语音每条动态选择一种，选好后会替换当前附件。").setNegativeButton("取消",null).setPositiveButton("继续"){_,_->action()}.show() else action()
    }
    private fun chooseMedia(kind:String)=replaceMedia{
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType(if(kind=="video")"video/mp4" else "audio/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),if(kind=="video")72 else 73)
    }
    private fun recordVoice()=replaceMedia{
        if(Hub.call!=null){Toast.makeText(this,"请先结束通话",Toast.LENGTH_SHORT).show();return@replaceMedia}
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO),74)
        else {recorder?.close();recorder=MomentRecorder(this){uri->selectMedia(uri,"voice")};recorder?.show()}
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,grants:IntArray){
        super.onRequestPermissionsResult(code,permissions,grants)
        if(code==74&&openingSession.isNotEmpty()&&Hub.token==openingSession&&Hub.me.optString("id")==ownerId&&grants.firstOrNull()==android.content.pm.PackageManager.PERMISSION_GRANTED){recorder=MomentRecorder(this){uri->selectMedia(uri,"voice")};recorder?.show()}
    }
    private fun selectMedia(uri:Uri,kind:String){
        if(busy||Hub.token!=openingSession)return
        val revision=++mediaRevision;checkingMedia=true;progress.visibility=View.VISIBLE;progress.text="正在检查${if(kind=="video")"视频" else "语音"}…";update()
        Hub.job({val prepared=MomentMediaFiles.prepare(this,uri,kind){Hub.token==openingSession&&mediaRevision==revision&&!isDestroyed};try{JSONObject().put("duration",prepared.duration).put("cover",prepared.cover)}finally{prepared.file.delete()}},{info->
            if(isDestroyed||revision!=mediaRevision)return@job
            checkingMedia=false;edited();if(mediaUri!=uri)MomentRecorder.discard(this,mediaUri);photos.clear();uploaded.clear();mediaUri=uri;mediaKind=kind;mediaDuration=info.getLong("duration");mediaCover=info.opt("cover") as? ByteArray;mediaId="";progress.visibility=View.GONE;renderPhotos();renderMedia();update();scheduleDraft()
        },{message->if(!isDestroyed&&revision==mediaRevision){checkingMedia=false;progress.text=message;update();if(uri!=mediaUri)MomentRecorder.discard(this,uri)}})
    }
    private fun renderMedia(){
        mediaBox.removeAllViews();val uri=mediaUri?:return
        if(mediaKind=="video"){
            val image=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;background=bg(Color.rgb(30,27,43),16);clipToOutline=true;contentDescription="所选视频的静态封面"}
            mediaBox.addView(image,LinearLayout.LayoutParams(-1,dp(180)).apply{bottomMargin=dp(8)})
            fun show(bytes:ByteArray){image.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size))}
            mediaCover?.let{show(it)}?:run{
                val revision=mediaRevision
                Hub.job({val prepared=MomentMediaFiles.prepare(this,uri,"video"){Hub.token==openingSession&&mediaRevision==revision&&!isDestroyed};try{JSONObject().put("cover",prepared.cover!!)}finally{prepared.file.delete()}},{result->if(!isDestroyed&&mediaUri==uri&&revision==mediaRevision){val bytes=result.get("cover") as ByteArray;mediaCover=bytes;show(bytes)}},{})
            }
        }
        val seconds=(mediaDuration/1000).coerceAtLeast(1)
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;background=bg(palette.soft,18);setPadding(dp(14),dp(14),dp(8),dp(14))}
        val title=label((if(mediaKind=="video")"已选视频" else "已选语音")+" · %02d:%02d".format(seconds/60,seconds%60),14f,palette.accent,true)
        row.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        fun control(kind:String,description:String,action:()->Unit){row.addView(FrameLayout(this).apply{contentDescription=description;isFocusable=true;addView(Glyph(this@PostActivity,kind,palette.accent),FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER));setOnClickListener{if(!busy&&!checkingMedia)action()}},LinearLayout.LayoutParams(dp(44),dp(48)))}
        control("play","预览动态媒体"){startActivity(Intent(this,MomentPlayerActivity::class.java).putExtra("kind",mediaKind).putExtra("local_uri",uri.toString()));Motion.activityOpen(this)}
        control("close","移除动态媒体"){edited();mediaRevision++;MomentRecorder.discard(this,mediaUri);mediaUri=null;mediaKind="";mediaId="";mediaCover=null;mediaDuration=0;renderMedia();renderPhotos();update();scheduleDraft()}
        mediaBox.addView(row)
    }
    private fun renderPhotos() {
        grid.removeAllViews();grid.visibility=if(mediaUri==null)View.VISIBLE else View.GONE;photoCount.visibility=if(mediaUri==null)View.VISIBLE else View.GONE;photoHint.visibility=if(mediaUri==null)View.VISIBLE else View.GONE
        photoCount.text=if(photos.isEmpty())"添加照片 · 最多 9 张" else "照片 · ${photos.size} / 9"
        val cell=((resources.configuration.screenWidthDp-84)/3).coerceIn(72,180)
        val entries=photos.size+if(photos.size<9)1 else 0
        for(start in 0 until entries step 3) {
            val r=LinearLayout(this)
            for(index in start until minOf(start+3,entries)) {
                val box=FrameLayout(this).apply { background=bg(palette.canvas,12);clipToOutline=true }
                if(index<photos.size) {
                    val uri=photos[index];val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP;contentDescription="已选图片 ${index+1}" };box.addView(image,FrameLayout.LayoutParams(-1,-1))
                    box.contentDescription="预览已选图片 ${index+1}";box.setOnClickListener { if(!busy)photoDialog=MomentGallery.showLocal(this,photos.toList(),photos.indexOf(uri)) }
                    box.setOnLongClickListener { if(!busy)photoOptions(uri);true }
                    Hub.io.execute { val bitmap=try { MomentsImages.decode(this,uri,360) }catch(_:Exception){null};Hub.main.post { if(!isDestroyed){if(bitmap!=null)image.setImageBitmap(bitmap) else image.contentDescription="图片无法读取，请移除后重新选择"} } }
                    val number=label(if(index==0)"封面" else "${index+1}",10f,Color.WHITE,true).apply { gravity=Gravity.CENTER;setPadding(dp(7),dp(4),dp(7),dp(4));background=bg(Color.argb(170,20,24,30),8) }
                    box.addView(number,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply { leftMargin=dp(6);bottomMargin=dp(6) })
                    val remove=FrameLayout(this).apply { contentDescription="移除图片 ${index+1}";addView(FrameLayout(this@PostActivity).apply { background=bg(Color.argb(180,20,24,30),10);addView(Glyph(this@PostActivity,"close",Color.WHITE),FrameLayout.LayoutParams(dp(17),dp(17),Gravity.CENTER)) },FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER));setOnClickListener { if(!busy)removePhoto(uri) } }
                    box.addView(remove,FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP or Gravity.END))
                } else {
                    val prompt=col().apply { gravity=Gravity.CENTER;addView(Glyph(this@PostActivity,"add",palette.accent),LinearLayout.LayoutParams(dp(28),dp(28)));addView(label("添加图片",12f,palette.muted).apply { gravity=Gravity.CENTER;setPadding(0,dp(7),0,0) }) }
                    box.addView(prompt,FrameLayout.LayoutParams(-1,-1));box.contentDescription="添加动态图片";box.setOnClickListener { if(!busy)startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),71) }
                }
                r.addView(box,LinearLayout.LayoutParams(dp(cell),dp(cell)).apply { if(index%3<2)marginEnd=dp(6);bottomMargin=dp(6) })
            };grid.addView(r)
        }
    }
    override fun onConfigurationChanged(config:android.content.res.Configuration){super.onConfigurationChanged(config);renderPhotos()}
    override fun onActivityResult(code:Int,result:Int,data:Intent?) {
        super.onActivityResult(code,result,data);if(result!=RESULT_OK||data==null||busy||openingSession.isEmpty()||Hub.token!=openingSession||Hub.me.optString("id")!=ownerId)return
        if(code==72||code==73){data.data?.let{uri->runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};selectMedia(uri,if(code==72)"video" else "voice")};return}
        if(code!=71)return
        if(mediaUri!=null){MomentRecorder.discard(this,mediaUri);mediaUri=null;mediaKind="";mediaId="";renderMedia()}
        val selected=if(data.clipData!=null)(0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri } else listOfNotNull(data.data)
        var overflow=false
        edited();selected.forEach { uri ->
            if(!photos.contains(uri)) {
                if(photos.size>=9)overflow=true
                else { try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }catch(_:Exception){};photos.add(uri) }
            }
        }
        if(overflow)Toast.makeText(this,"最多可选 9 张图片",Toast.LENGTH_LONG).show();renderPhotos();update();scheduleDraft()
    }
    private fun removePhoto(uri:Uri){edited();photos.remove(uri);uploaded.remove(uri.toString());renderPhotos();update();scheduleDraft()}
    private fun photoOptions(uri:Uri) {
        val index=photos.indexOf(uri);if(index<0)return
        val actions=mutableListOf(ActionSheets.Action("image","预览照片") { photoDialog=MomentGallery.showLocal(this,photos.toList(),photos.indexOf(uri)) })
        fun move(target:Int){edited();photos.remove(uri);photos.add(target,uri);renderPhotos();scheduleDraft()}
        if(index>0){actions.add(ActionSheets.Action("image","设为首图","放在动态图片的第一张") { move(0) });actions.add(ActionSheets.Action("back","向前移动") { move(index-1) })}
        if(index<photos.lastIndex)actions.add(ActionSheets.Action("arrow","向后移动") { move(index+1) })
        actions.add(ActionSheets.Action("trash","移除照片") { removePhoto(uri) })
        photoActions?.dismiss();photoActions=ActionSheets.show(this,"照片 ${index+1} / ${photos.size}",actions=actions)
    }
    private fun submit() {
        if(busy)return
        busy=true;attempted=true;update();input.isEnabled=false;progress.visibility=View.VISIBLE;progress.text="正在准备发布…";progressBar.max=photos.size+1;progressBar.progress=0;progressBar.visibility=View.VISIBLE;persistDraft()
        val body=input.text.toString();val selection=photos.toList();val identity=clientId;val owner=Hub.me.optString("id");val token=Hub.token;val selectedMedia=mediaUri;val kind=mediaKind;val visibility=audience;val savedDraft=draft()
        Hub.job({
            val ids=JSONArray()
            selection.forEachIndexed { index,uri ->
                check(Hub.token==token&&Hub.me.optString("id")==owner){"登录状态已改变，请重新登录"}
                Hub.main.post { if(!isDestroyed)progress.text="正在上传图片 ${index+1} / ${selection.size}…" }
                val fid=uploaded[uri.toString()]?:run {
                    val jpeg=MomentsImages.prepare(this,uri)
                    Hub.http.newCall(Request.Builder().url(Hub.SERVER+"/api/post-photos").header("Authorization","Bearer $token").post(jpeg.toRequestBody("image/jpeg".toMediaType())).build()).execute().use { r ->
                        val p=JSONObject(r.body?.string()?:"{}");if(!r.isSuccessful)error(p.optString("error","图片上传失败"));p.getString("id")
                    }.also { uploaded[uri.toString()]=it }
                };ids.put(fid);Hub.main.post { if(!isDestroyed)progressBar.progress=index+1 }
            }
            check(Hub.token==token){"登录状态已改变，请重新登录"}
            val attached=if(selectedMedia==null)"" else if(mediaId.isNotEmpty()){
                if(kind=="video")MomentMediaFiles.ensureCover(this,selectedMedia,mediaId,token,owner)
                mediaId
            }else MomentMediaFiles.upload(this,selectedMedia,kind,token,owner,uploaded={info->mediaId=info.getString("id");if(Hub.token==token&&Hub.me.optString("id")==owner){savedDraft.put("media_id",mediaId);prefs.edit().putString(owner,savedDraft.toString()).commit()}}){percent->Hub.main.post{if(!isDestroyed){progress.text="正在上传${if(kind=="video")"视频" else "语音"} $percent%";progressBar.max=100;progressBar.progress=percent}}}.getString("id")
            Hub.main.post { if(!isDestroyed)progress.text="正在发布动态…" }
            Hub.api("POST","/api/posts",JSONObject().put("text",body).put("photos",ids).put("client_id",identity).put("audience",visibility).put("media",attached))
        }, {
            done=true;MomentRecorder.discard(this,mediaUri);Hub.main.removeCallbacks(saveDraft);prefs.edit().remove(owner).apply();setResult(RESULT_OK);Toast.makeText(this,"动态已发布",Toast.LENGTH_SHORT).show();finish()
        }, { error -> if(!isDestroyed){busy=false;input.isEnabled=true;progressBar.visibility=View.GONE;progress.text="发布未完成：$error\n草稿已保留，可以重试。";persistDraft();update()} })
    }
    private fun leave() {
        if(busy){Toast.makeText(this,"正在发布，请稍候",Toast.LENGTH_SHORT).show();return}
        if(input.text.isBlank()&&photos.isEmpty()&&mediaUri==null){done=true;prefs.edit().remove(draftKey).apply();finish();return}
        val dialog=AlertDialog.Builder(this).setTitle("保留这次编辑？").setMessage("草稿会保存在这台设备上，下次发布时继续编辑。")
            .setNegativeButton("继续编辑",null).setNeutralButton("放弃") { _,_ -> done=true;MomentRecorder.discard(this,mediaUri);prefs.edit().remove(draftKey).apply();finish() }
            .setPositiveButton("保留草稿") { _,_ -> finish() }.create()
        dialog.show();listOf(-1,-2,-3).forEach { dialog.getButton(it).setTextColor(palette.accent) }
    }
    @Deprecated("Android back callback") override fun onBackPressed(){leave()}
}
