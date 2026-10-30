package cn.qingliao.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.MediaMetadataRetriever
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.webkit.WebView
import androidx.core.content.FileProvider
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    fun signedOut(){if(!isDestroyed&&!isFinishing)showLogin()}
    private val hubListener:(JSONObject)->Unit={event->onEvent(event)}
    private val dialogs=java.util.Collections.newSetFromMap(java.util.WeakHashMap<android.app.Dialog,Boolean>())
    private fun trackDialog(dialog:android.app.Dialog){dialogs.add(dialog)}
    private fun dismissDialogs(){dialogs.toList().forEach{runCatching{it.dismiss()}};dialogs.clear()}
    private var palette=ThemePalette(ThemeStore.DEFAULT)
    private val accent get()=palette.accent
    private val ink get()=palette.ink
    private val muted get()=palette.muted
    private val canvas get()=palette.canvas
    private val line get()=palette.line
    private val soft get()=palette.soft
    private lateinit var root: LinearLayout
    private lateinit var screenHost:FrameLayout
    private var motionDirection=0
    private var homeSignature=""
    private var content: LinearLayout? = null
    private var connectionLabel: TextView? = null
    private var peerTitle: TextView? = null
    private var unreadBadge: TextView? = null
    private var actionSheet: Dialog? = null
    private var messageMenu:MessageActionMenu?=null
    private val messageBubbles=mutableMapOf<String,View>()
    private val messageTextViews=mutableMapOf<String,MessageTextView>()
    private var textActionMode:ActionMode?=null
    private var textSelectionMessage=""
    private var selectionMenuTask:Runnable?=null
    private val selectedMessages=linkedSetOf<String>()
    private var selecting=false
    private val selectionChecks=mutableMapOf<String,CheckBox>()
    private var selectionHeader:LinearLayout?=null
    private var selectionFooter:LinearLayout?=null
    private var selectionCount:TextView?=null
    private var selectionForward:View?=null
    private var selectionDelete:View?=null
    private var composerPanel:View?=null
    private var inputPanel:ChatInputPanel?=null
    private var inputRowView:View?=null
    private var recorderButton:View?=null
    private var voicePlayback:VoicePlayback?=null
    private val voiceViews=mutableMapOf<String,VoiceMessageView>()
    private var forwardJob:MessageForwarder?=null
    private var forwardDialog:Dialog?=null
    private var batchDeleteCancel:(()->Unit)?=null
    private var authScreen:AuthScreen?=null
    private var tab = 0
    private var feed: MomentsView? = null
    private var peer: JSONObject? = null
    private var contacts = JSONArray()
    private var conversations = JSONArray()
    private var homeRevision=0L
    private var replyTarget:JSONObject?=null
    private var replyBar:LinearLayout?=null
    private var replyLabel:TextView?=null
    private var searchWindow=false
    private var searchSession=""
    private var searchOwner=""
    private var pickerSession=""
    private var avatarPickerSession=""
    private var saveSession=""
    private var voicePaused=false
    private var micControl:LinearLayout?=null
    private var speakerControl:LinearLayout?=null
    private var shareControl:TextView?=null
    private var cameraControl:LinearLayout?=null
    private var screenRequest:ScreenSharing.Request?=null
    private var screenApproval:Triple<ScreenSharing.Request,Int,Intent?>?=null
    private var resumed=false
    private val messages = mutableListOf<JSONObject>()
    private var hasMore = false
    private var messageList: LinearLayout? = null
    private var messageScroll: ScrollView? = null
    private var latestMessage:TextView?=null
    private val receiptLabels=mutableMapOf<String,TextView>()
    private var chatGeneration=0
    private var composer: EditText? = null
    private var composerOwner=""
    private var composerSession=""
    private var composerPeer=""
    private var restoringDraft=false
    private val savingText=mutableSetOf<String>()
    private var conversationMenu:PopupMenu?=null
    private val draftWarnings=mutableSetOf<String>()
    private var sendButton: View? = null
    private var typingPeer = ""
    private var lastTypingSent = 0L
    private val stopTypingTask = Runnable { stopTyping() }
    private val contactStatusLabels = mutableListOf<Pair<JSONObject, TextView>>()
    private var recording: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStart = 0L
    private var recordOwner=""
    private var recordSession=""
    private var recordPeer=""
    private var recordQuote:JSONObject?=null
    private var recordQuoteRef:JSONObject?=null
    private data class ReadyVoice(val id:String,val file:File,val owner:String,val session:String,val peer:String,val duration:Int,val quote:JSONObject?,val quoteRef:JSONObject?)
    private var readyVoice:ReadyVoice?=null
    private var voiceDraftRow:LinearLayout?=null
    private var voiceDraftCaption:TextView?=null
    private var voiceDraftPlay:FrameLayout?=null
    private var voiceDraftIcon=""
    private var voiceDraftError=""
    private var recordLabel: TextView? = null
    private var recordBar: LinearLayout? = null
    private var player: MediaPlayer? = null
    private var playingId = ""
    private var permissionAction: (() -> Unit)? = null
    private var permissionSession=""
    private data class GrantedAction(val session:String,val expires:Long,val action:()->Unit)
    private var grantedAction:GrantedAction?=null
    private var pickerPeer = ""
    private var pickerKind = "file"
    private var pickerQuote:JSONObject?=null
    private var pickerQuoteRef:JSONObject?=null
    private var saveFile: File? = null
    private var savingMoment=false
    private var returningFromPhotoSave=false
    private var returningFromPost=false
    private val compact get()=CompactUi.enabled(this)
    private val tiny get()=CompactUi.tiny(this)
    private var dictation:DictationDialog?=null
    private var voiceTextJob:VoiceTranscription?=null
    private var voiceTextResults=JSONObject()
    private val hiddenVoiceTexts=mutableSetOf<String>()
    private data class VoiceTextState(val running:Boolean,val text:String)
    private data class VoiceTextPanel(val view:LinearLayout,val message:JSONObject,val width:Int)
    private val voiceTextStates=mutableMapOf<String,VoiceTextState>()
    private val voiceTextPanels=mutableMapOf<String,VoiceTextPanel>()
    private var voiceTextRevision=0
    private var chatHeader:View?=null
    private var callScreen = false
    private var callClock: TextView? = null
    private var callVideoStage:CallVideoStage?=null
    private var callVideoState=CallVideoState()
    private var callPreferenceKey=""
    private var callViewKey=""
    private var callViewEngine:RtcEngine?=null
    private var callViewport:FrameLayout?=null
    private var callTopOverlay:View?=null
    private var callBottomOverlay:View?=null
    private var callScaleControl:TextView?=null
    private var callCaption:TextView?=null
    private var videoEdge=false
    private var callSafeInsets=Rect()
    private var classicTop:View?=null
    private var classicControls:View?=null
    private var classicIdentity:View?=null
    private var pipCard:View?=null
    private var pipClock:TextView?=null
    private var pipTransition=false
    private var assistControl:TextView?=null
    private var assistBar:LinearLayout?=null
    private var assistSettingsOpen=false
    private var assistFeedbackRevision=0
    private data class HtmlPreview(val frame: FrameLayout, val placeholder: TextView, val source: String, var web: WebView? = null)
    private val htmlPreviews = mutableListOf<HtmlPreview>()
    private class ChatImage(val view:ImageView,val label:TextView,val file:JSONObject){@Volatile var active=false;var loading=false;var failed=false;var request=0;var bitmap:android.graphics.Bitmap?=null}
    private val chatImages=mutableListOf<ChatImage>()
    private fun cancelImage(tag:Any){(Hub.http.dispatcher.runningCalls()+Hub.http.dispatcher.queuedCalls()).filter{it.request().tag()===tag}.forEach{it.cancel()}}
    private fun pauseChatImages(){chatImages.forEach{it.active=false;it.request++;it.loading=false;cancelImage(it);Motion.rest(it.view);it.view.setImageDrawable(null);it.bitmap=null;it.label.visibility=View.VISIBLE}}
    private fun releaseChatImages(){pauseChatImages();chatImages.clear()}
    private fun syncChatImages(){
        if(callScreen)return
        chatImages.forEach{item->
            val visible=Hub.foreground&&item.view.isAttachedToWindow&&item.view.getGlobalVisibleRect(Rect())
            if(!visible&&item.active){item.active=false;item.request++;item.loading=false;cancelImage(item);item.view.setImageDrawable(null);item.bitmap=null;item.label.visibility=View.VISIBLE}
            else if(visible&&!item.active){item.active=true;if(!item.failed)loadChatImage(item)}
        }
    }
    private fun decodeChatImage(file:File):android.graphics.Bitmap {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.absolutePath,bounds)
        require(bounds.outWidth>0&&bounds.outHeight>0){"图片格式无法读取"}
        var sample=1;while(bounds.outWidth/sample>1200||bounds.outHeight/sample>1600)sample*=2
        return BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply{inSampleSize=sample})?:throw Exception("图片格式无法读取")
    }
    private fun loadChatImage(item:ChatImage){
        if(!item.active||item.loading||item.bitmap!=null)return
        val request=++item.request;val session=Hub.token;item.loading=true;item.failed=false;item.label.text="正在加载图片…"
        Hub.io.execute{try{
            val file=Hub.cachedFile(item.file,item){!item.active||Hub.token!=session};val bitmap=try{decodeChatImage(file)}catch(e:Exception){file.delete();throw e}
            Hub.main.post{
                if(request!=item.request||!item.active||Hub.token!=session||isDestroyed){bitmap.recycle();return@post}
                item.loading=false;item.bitmap=bitmap;item.view.setImageBitmap(bitmap);item.label.visibility=View.GONE;item.view.contentDescription="查看图片";Motion.image(item.view)
            }
        }catch(_:Exception){Hub.main.post{if(request==item.request&&item.active&&Hub.token==session){item.loading=false;item.failed=true;item.label.text="加载失败，点按重试";item.label.visibility=View.VISIBLE;item.view.contentDescription="图片加载失败，点按重试"}}}}
    }
    private val tick = object : Runnable {
        override fun run() {
            updateStatusLabels()
            if (Hub.call != null) callClock?.let { label ->
                val value=callClockText()
                if(label.text.toString()!=value)label.text=value
            }
            pipClock?.let{if(it.text.toString()!=callClockText())it.text=callClockText()}
            if (recording != null) {
                val elapsed = (SystemClock.elapsedRealtime() - recordStart) / 1000
                recordLabel?.let { label -> val value="正在录音  ${duration(elapsed)}  /  01:00"; if(label.text.toString()!=value)label.text=value }
                if (elapsed >= 60) stopRecording(false,true)
            }
            Hub.main.postDelayed(this, 500)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        palette=ThemeStore.current(this)
        screenHost=FrameLayout(this).apply { setBackgroundColor(canvas) };setContentView(screenHost)
        window.statusBarColor = canvas; window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        Hub.listener = hubListener
        if (Hub.token.isEmpty()) showLogin() else { showHome(); Hub.startService(); refresh() }
        Hub.main.post(tick)
        handleIntent(intent)
    }
    override fun onResume() {
        super.onResume();resumed=true;assistSettingsOpen=false;if(!isInPictureInPictureMode)pipTransition=false; val next=ThemeStore.current(this); val changed=next.primary!=palette.primary; palette=next
        Hub.foreground=true; Hub.viewingPeer=peer?.optString("id") ?: ""
        grantedAction?.let{pending->grantedAction=null;if(Hub.token==pending.session&&SystemClock.elapsedRealtime()<=pending.expires)pending.action()else if(Hub.token==pending.session)toast("权限已开启，请重新点击对应功能")}
        AppUpdates.check();AppUpdates.notifyAvailable()
        finishScreenApproval()
        if(Hub.call!=null) { showCall() }
        else if(changed) {
            if(Hub.token.isEmpty())showLogin()
            else if(peer==null)showHome()
            else showChat()
        }
        if(Hub.token.isNotEmpty()){refresh();if(peer!=null&&!callScreen)loadVoiceTexts();if(!returningFromPhotoSave&&!returningFromPost)feed?.refresh()};returningFromPhotoSave=false;returningFromPost=false;root.post{if(!isDestroyed){if(voicePaused&&peer!=null&&!callScreen){voicePaused=false;renderMessages(false)};syncHtmlPreviews()}}
    }
    override fun onPause() { if(Hub.rtc?.assist?.let{it.controlling||it.pending}==true)Hub.rtc?.assist?.stopControlling("已离开控制画面");inputPanel?.hide();stashDraft(true);conversationMenu?.dismiss(); resumed=false;finishTextSelection();messageMenu?.dismiss();forwardJob?.cancel();forwardDialog?.dismiss();batchDeleteCancel?.invoke();cancelVoiceText();dictation?.cancel();dictation=null;if(playingId.isNotEmpty()){voicePaused=true;stopPlayer()};pauseChatImages();stopTyping();super.onPause();Hub.foreground=false;Hub.viewingPeer="";if(recording!=null)stopRecording(false,true) }
    override fun onDestroy() { grantedAction=null;if(recording!=null)stopRecording(false);clearReadyVoice();inputPanel?.hide();voicePlayback?.dispose();voicePlayback=null;stashDraft(true);conversationMenu?.dismiss(); authScreen?.dispose();authScreen=null; screenRequest?.let{ScreenSharing.cancelRequest(it)};screenRequest=null;screenApproval?.let{ScreenSharing.cancelRequest(it.first)};screenApproval=null;cancelVoiceText();releaseChatImages();activeUploads.toList().forEach{it.cancel("页面已关闭，已取消发送")};activeUploads.clear();dismissDialogs();feed?.dispose();stopTyping(); Hub.main.removeCallbacks(tick); if(Hub.listener===hubListener)Hub.listener=null;stopPlayer();releaseRenderers();releaseHtmlPreviews();actionSheet?.dismiss();super.onDestroy() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(isInPictureInPictureMode&&Hub.call==null){finish();return}
        dictation?.cancel();dictation=null
        if(callScreen && Hub.call!=null){if(!isInPictureInPictureMode&&callVideoStage==null)showCall()else updateCallChrome()} else if(peer!=null)showChat() else if(Hub.token.isNotEmpty())showHome() else authScreen?.reflow()?:showLogin()
    }
    override fun onUserLeaveHint(){super.onUserLeaveHint();if(pipEligible()){if(Build.VERSION.SDK_INT<31)minimizeCall(false)else{pipTransition=true;updateCallChrome()}}}
    override fun onPictureInPictureModeChanged(inPip:Boolean,newConfig:android.content.res.Configuration){super.onPictureInPictureModeChanged(inPip,newConfig);pipTransition=false;if(Hub.call==null){if(inPip)finish();return};updateCallChrome();if(!inPip)updatePipParams()}
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent?) {
        if (Hub.token.isEmpty()) return
        if(intent?.hasExtra("owner")==true&&intent.getStringExtra("owner")!=Hub.me.optString("id"))return
        if(intent?.action?.startsWith("cn.qingliao.app.CALL_")==true) {
            if(!CallActions.valid(intent))return
            showCall()
            if(intent.action=="cn.qingliao.app.CALL_ACCEPT")Hub.main.post {
                if(!isFinishing&&Hub.foreground&&CallActions.valid(intent))acceptCall()
            }
            return
        }
        if (Hub.call != null) { showCall(); return }
        val id = intent?.getStringExtra("peer") ?: return
        val u = (0 until contacts.length()).map { contacts.getJSONObject(it) }.find { it.optString("id") == id }
        if (u != null) openChat(u)
        else Hub.job({ Hub.api("GET", "/api/users?q=$id") }, { it.optJSONObject("user")?.let { u -> openChat(u) } }, { toast(it) })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, radius: Int = 16, border: Int? = null): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat(); if(border!=null) setStroke(dp(1),border) }
    private fun text(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text=value; textSize=size; setTextColor(color); if(bold) setTypeface(typeface,Typeface.BOLD); gravity=Gravity.CENTER_VERTICAL; includeFontPadding=false; layoutParams=LinearLayout.LayoutParams(-2,-2)
    }
    private fun col() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun pad(view: View, x: Int, y: Int = x) { view.setPadding(dp(x),dp(y),dp(x),dp(y)) }
    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(if(w<0)w else dp(w),if(h<0)h else dp(h))
    private fun space(parent: LinearLayout, h: Int) { parent.addView(View(this),lp(0,h)) }
    private fun touch(view: View, color: Int = Color.TRANSPARENT, radius: Int = 12) {
        view.background=RippleDrawable(ColorStateList.valueOf(palette.ripple),bg(color,radius),bg(Color.WHITE,radius))
        view.isClickable=true; view.isFocusable=true
        Motion.press(view)
    }
    private fun divider(parent: LinearLayout, inset: Int = 0) {
        parent.addView(View(this).apply { setBackgroundColor(line) },lp(-1,1).apply { marginStart=dp(inset) })
    }
    private fun section(parent: LinearLayout, label: String) {
        parent.addView(text(label,12f,muted,true).apply { setPadding(dp(4),dp(18),0,dp(10)) },lp())
    }
    private fun button(label: String, primary: Boolean = true, action: () -> Unit): TextView = text(label,15f,if(primary)palette.onPrimary else accent,true).apply {
        gravity=Gravity.CENTER; touch(this,if(primary)palette.primary else soft,18)
        if(primary)background=RippleDrawable(ColorStateList.valueOf(palette.ripple),BrandUi.primary(this@MainActivity,palette),bg(Color.WHITE,18))
        setPadding(dp(18),dp(12),dp(18),dp(12));minimumHeight=dp(48);setOnClickListener { action() }
    }
    private fun icon(kind: String, label: String, color: Int = ink, action: () -> Unit): FrameLayout = FrameLayout(this).apply {
        contentDescription=label; touch(this)
        addView(Glyph(this@MainActivity,kind,color), FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))
        layoutParams=lp(if(compact)40 else 48,if(compact)40 else 48); setOnClickListener { action() }
    }
    private fun avatar(u: JSONObject, size: Int = 52): FrameLayout {
        val colors = intArrayOf(soft,ThemePalette.blend(palette.primary,Color.rgb(250,235,222),.88f),ThemePalette.blend(palette.primary,Color.rgb(229,235,252),.88f),ThemePalette.blend(palette.primary,Color.rgb(249,233,243),.88f))
        return FrameLayout(this).apply {
            background=bg(colors[(u.optString("id").hashCode() and Int.MAX_VALUE)%colors.size],size/2); layoutParams=lp(size,size); clipToOutline=true
            addView(text(u.optString("nickname", "星").take(1),size*.36f,accent,true).apply { gravity=Gravity.CENTER },FrameLayout.LayoutParams(-1,-1))
            val image=ImageView(this@MainActivity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; contentDescription="头像照片"; visibility=View.GONE }
            addView(image,FrameLayout.LayoutParams(-1,-1)); AvatarImages.load(u.optString("avatar_id"),image)
            if(MemberUi.active(u))addView(MemberFrame(this@MainActivity),FrameLayout.LayoutParams(-1,-1))
        }
    }
    private fun reset(color: Int = canvas,fullVideo:Boolean=false) {
        videoEdge=fullVideo;WindowCompat.setDecorFitsSystemWindows(window,!fullVideo)
        stashDraft();composerPeer="";inputPanel?.hide();inputPanel=null;inputRowView=null;recorderButton=null;voiceDraftRow=null;voiceDraftPlay=null;voiceDraftCaption=null;voiceDraftIcon="";voiceViews.clear();conversationMenu?.dismiss();conversationMenu=null
        authScreen?.dispose();authScreen=null
        finishTextSelection();messageTextViews.clear()
        messageMenu?.dismiss();messageMenu=null;messageBubbles.clear();selectionChecks.clear();selectedMessages.clear();selecting=false;selectionHeader=null;selectionFooter=null;composerPanel=null
        forwardJob?.cancel();forwardJob=null;forwardDialog?.dismiss();forwardDialog=null;batchDeleteCancel?.invoke();batchDeleteCancel=null
        dictation?.cancel();dictation=null
        dismissDialogs()
        chatHeader=null
        if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(callScreen);setTurnScreenOn(callScreen)}
        else {val flags=WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;if(callScreen)window.addFlags(flags)else window.clearFlags(flags)}
        homeSignature="";receiptLabels.clear();latestMessage=null
        feed?.dispose(callScreen);feed=null
        stopTyping();if(callScreen)actionSheet?.window?.setWindowAnimations(0);actionSheet?.dismiss(); actionSheet=null; unreadBadge=null; contactStatusLabels.clear()
        releaseChatImages();releaseHtmlPreviews()
        releaseRenderers()
        connectionLabel=null;peerTitle=null;messageList=null;messageScroll=null;composer=null;sendButton=null;callClock=null;micControl=null;speakerControl=null;cameraControl=null;shareControl=null
        if(!callScreen){callPreferenceKey="";callVideoState=CallVideoState();pipTransition=false;updatePipParams()}
        root=col(); root.setBackgroundColor(color)
        root.setOnApplyWindowInsetsListener { v,insets ->
            if(videoEdge){
                val raw=window.decorView.rootWindowInsets?:insets
                callSafeInsets=if(Build.VERSION.SDK_INT>=30){val b=raw.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout());Rect(b.left,b.top,b.right,b.bottom)}else Rect(raw.stableInsetLeft,raw.stableInsetTop,raw.stableInsetRight,raw.stableInsetBottom)
                v.setPadding(0,0,0,0);applyCallInsets();return@setOnApplyWindowInsetsListener insets
            }
            val bottom=if(Build.VERSION.SDK_INT>=30) insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.systemBars()).bottom else insets.systemWindowInsetBottom
            if(Build.VERSION.SDK_INT>=30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(bars.left,bars.top,bars.right,bottom)
            } else v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,bottom)
            updateCompactChat();insets
        }
        root.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->updateCompactChat()}
        screenHost.setBackgroundColor(color);Motion.swap(screenHost,root,motionDirection);motionDirection=0;systemIcons(callScreen);root.requestApplyInsets()
    }
    private fun systemIcons(lightIcons: Boolean) {
        val layout=if(videoEdge)View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION else 0
        window.decorView.systemUiVisibility=layout or if(lightIcons)0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if(Build.VERSION.SDK_INT>=29)window.isNavigationBarContrastEnforced=false
        if(Build.VERSION.SDK_INT>=30) {
            val mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if(lightIcons)0 else mask,mask)
        }
    }
    private fun field(hint: String, secret: Boolean = false): EditText = EditText(this).apply {
        this.hint=hint; textSize=16f; setTextColor(ink); setHintTextColor(muted); background=BrandUi.field(this@MainActivity,palette); setPadding(dp(16),dp(16),dp(16),dp(16)); isSingleLine=true
        inputType=if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        layoutParams=lp(-1,if(compact)44 else 56).apply { bottomMargin=dp(if(compact)8 else 12) };if(compact){textSize=14f;setPadding(dp(12),dp(8),dp(12),dp(8))}; tintInput(this)
    }
    private fun tintInput(input: EditText) {
        input.highlightColor=soft
        if(Build.VERSION.SDK_INT>=29) {
            input.textCursorDrawable?.setTint(accent)
            input.textSelectHandleLeft?.setTint(accent);input.textSelectHandleRight?.setTint(accent);input.textSelectHandle?.setTint(accent)
        }
    }
    private fun AlertDialog.showThemed() {
        trackDialog(this)
        show();listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL).forEach { getButton(it)?.setTextColor(accent) }
    }
    private fun AlertDialog.Builder.showThemed()=create().apply { showThemed() }
    private fun toast(message: String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken,0); root.clearFocus() }
    private fun duration(seconds: Long) = "%02d:%02d".format(seconds/60,seconds%60)
    private fun time(ms: Long) = SimpleDateFormat(if(System.currentTimeMillis()-ms<86400000)"HH:mm" else "MM-dd",Locale.CHINA).format(Date(ms))
    private fun size(n: Long) = if(n>=1048576) "%.1f MB".format(n/1048576.0) else if(n>=1024) "%.1f KB".format(n/1024.0) else "$n B"
    private fun canContact():Boolean = peer?.let { !it.optBoolean("blocked") && !it.optBoolean("blocked_by_me") && it.optBoolean("friend",it.optString("state","accepted")=="accepted") } ?: false
    private fun contactGuard():Boolean { if(canContact())return true;toast("请先在聊天设置中添加好友");return false }
    private fun people() { peer?.let { startActivityForResult(Intent(this,PeopleActivity::class.java).putExtra("peer",it.optString("id")),77);Motion.activityOpen(this) } }
    private fun refreshRelationship() {
        if(!Hub.connected)return
        val id=peer?.optString("id") ?: return;val session=Hub.token;val generation=chatGeneration
        Hub.job({Hub.rawApi("GET","/api/relationships/$id",session=session)}, {
            if(Hub.token!=session||generation!=chatGeneration||peer?.optString("id")!=id)return@job
            val callButtonsChanged=peer?.optBoolean("bot_calls_enabled")!=it.optBoolean("bot_calls_enabled")||peer?.optBoolean("bot_ready")!=it.optBoolean("bot_ready")
            val draft=composer?.text?.toString()?:""
            peer=it;if(callButtonsChanged&&!callScreen){showChat();composer?.setText(draft);composer?.setSelection(draft.length)} else updateStatusLabels()
            if(!canContact()){stopTyping();if(recording!=null)stopRecording(false)}
        }, { /* Offline history remains readable; the server still enforces relationship writes. */ })
    }
    private fun displayName(u:JSONObject)=u.optString("nickname")+if(u.optBoolean("bot"))" · 机器人" else ""
    private fun status(u: JSONObject): String = when {
        u.optBoolean("blocked_by_me") -> "已加入黑名单"
        u.optBoolean("blocked") || !u.optBoolean("friend",true) -> "未添加好友 · 点右上角设置"
        u.optBoolean("bot") -> if(u.optBoolean("bot_ready"))"机器人 · API 服务" else "机器人暂不可用"
        !Hub.connected -> "正在连接…"
        u.optBoolean("online") -> "在线"
        else -> PresenceText.offline(u.optLong("last_seen"))
    }
    private fun updateStatusLabels() {
        if (!Hub.foreground) return
        if (!callScreen) peer?.let { u ->
            val name=if(compact)u.optString("nickname") else displayName(u)
            peerTitle?.let { title -> if(title.text.toString()!=name)title.text=name }
            val value = if (Hub.connected && Hub.peerIsTyping(u.optString("id"))) "对方正在输入…" else status(u)
            connectionLabel?.let { label ->
                if (label.text.toString() != value) label.text = value
                label.setTextColor(if (Hub.connected && (u.optBoolean("online") || Hub.peerIsTyping(u.optString("id")))) accent else muted)
            }
        }
        contactStatusLabels.forEach { (u, label) ->
            val value = status(u)
            if (label.text.toString() != value) label.text = value
            label.setTextColor(if (Hub.connected && u.optBoolean("online")) accent else muted)
        }
    }
    private fun inputChanged(value: CharSequence?) {
        val id = peer?.optString("id") ?: ""
        if (!Hub.foreground || !Hub.connected || !canContact() || callScreen || id.isEmpty() || value.isNullOrBlank()) { stopTyping(); return }
        val now = SystemClock.elapsedRealtime()
        if (typingPeer != id || now - lastTypingSent >= 1500) {
            if (typingPeer.isNotEmpty() && typingPeer != id) stopTyping()
            Hub.sendTyping(id, true); typingPeer = id; lastTypingSent = now
        }
        Hub.main.removeCallbacks(stopTypingTask); Hub.main.postDelayed(stopTypingTask, 2500)
    }
    private fun stopTyping() {
        Hub.main.removeCallbacks(stopTypingTask)
        if (typingPeer.isNotEmpty()) Hub.sendTyping(typingPeer, false)
        typingPeer = ""; lastTypingSent = 0L
    }

    private fun showLogin(register: Boolean = false) {
        if(isInPictureInPictureMode){finish();return}
        grantedAction=null
        clearReadyVoice();voicePlayback?.dispose();voicePlayback=null;player=null;playingId=""
        stashDraft(true);composerPeer="";savingText.clear()
        cancelVoiceText();voiceTextResults=JSONObject();hiddenVoiceTexts.clear();voiceTextStates.clear();voiceTextPanels.clear()
        if(recording!=null)stopRecording(false);stopPlayer();replyTarget=null;searchWindow=false;saveFile=null;pickerSession="";avatarPickerSession="";saveSession="";permissionAction=null;permissionSession=""
        activeUploads.toList().forEach{it.cancel("账号已退出，已取消发送")}
        chatGeneration++;contacts=JSONArray();conversations=JSONArray();messages.clear();hasMore=false
        callScreen=false; peer=null; Hub.viewingPeer=""; reset()
        root.background=StarlightDrawable(palette)
        authScreen=AuthScreen(this,palette) { result ->
            Hub.saveSession(result);if(Hub.me.optBoolean("administrator"))tab=3;showHome();refresh()
            if(Hub.me.optBoolean("admin_password_change_required"))startActivity(Intent(this,AdminActivity::class.java))
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),50)
        }.also{root.addView(it.view,lp(-1,-1))}
    }

    private fun homeConnection() = if(Hub.me.optBoolean("admin_password_change_required"))"请先修改管理员初始密码" else if(Hub.connected) "●  已连接" else "○  连接中…"
    private fun showHome(tabChange:Boolean=false) {
        clearReadyVoice()
        stashDraft(true);composerPeer=""
        cancelVoiceText();voiceTextResults=JSONObject();hiddenVoiceTexts.clear();voiceTextStates.clear();voiceTextPanels.clear()
        peer=null; Hub.viewingPeer=""; callScreen=false; reset(canvas)
        window.statusBarColor=canvas;window.navigationBarColor=canvas
        val top=row().apply { setPadding(dp(if(compact)10 else 22),dp(if(tiny)2 else if(compact)8 else 16),dp(if(compact)10 else 20),dp(if(tiny)2 else if(compact)8 else 18)) }
        val heading=col()
        val brand=row();brand.addView(Glyph(this,"star",accent),lp(10,10));brand.addView(text("弥星",11f,accent,true).apply{setPadding(dp(5),0,0,0)})
        if(!compact){heading.addView(brand);space(heading,8)}
        val headingText=text(arrayOf("消息","联系人","动态","我的")[tab],if(tiny)18f else if(compact)20f else 27f,ink,true)
        connectionLabel=text(homeConnection(),if(tiny)10f else 11f,muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        if(tiny){
            val condensed=row();condensed.addView(headingText);condensed.addView(connectionLabel,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(10)});top.addView(condensed,LinearLayout.LayoutParams(0,-2,1f))
        }else{
            heading.addView(headingText);space(heading,if(compact)3 else 6);heading.addView(connectionLabel);top.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        }
        if(tab<2) top.addView(icon("add","添加好友",accent) { searchFriend() }.apply { touch(this,soft,14) })
        if(tab==2)top.addView(icon("camera","发布动态",accent) { composePost() }.apply { touch(this,soft,14) })
        root.addView(top,lp())
        val scroll=ScrollView(this).apply { isFillViewport=true; clipToPadding=false; isVerticalScrollBarEnabled=false }
        content=col().apply { setPadding(dp(if(compact)8 else 20),0,dp(if(compact)8 else 20),dp(if(compact)10 else 24)) }; scroll.addView(content)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val nav=row().apply { setPadding(dp(if(compact)2 else 8),dp(if(compact)2 else 7),dp(if(compact)2 else 8),dp(if(compact)2 else 6));background=BrandUi.card(this@MainActivity,palette,26) }
        listOf("chat" to "消息","contacts" to "联系人","moments" to "动态","me" to "我的").forEachIndexed { i,(kind,label) ->
            val selected=tab==i
            val item=col().apply { gravity=Gravity.CENTER; contentDescription=label; touch(this,Color.TRANSPARENT,16) }
            val mark=FrameLayout(this).apply {
                if(selected)background=BrandUi.primary(this@MainActivity,palette,16)
                addView(Glyph(this@MainActivity,kind,if(selected)palette.onPrimary else muted),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))
            }
            if(i==0) {
                unreadBadge=text("",9f,Color.WHITE,true).apply { gravity=Gravity.CENTER; background=bg(Color.rgb(209,91,82),10); minWidth=dp(17); setPadding(dp(4),dp(1),dp(4),dp(1)); visibility=View.GONE }
                mark.addView(unreadBadge,FrameLayout.LayoutParams(-2,dp(17),Gravity.TOP or Gravity.END))
            }
            item.addView(mark,lp(if(compact)36 else 54,if(compact)24 else 30)); space(item,3)
            item.addView(text(label,11f,if(selected)accent else muted,selected))
            nav.addView(item,LinearLayout.LayoutParams(0,dp(if(compact)48 else 60),1f)); item.setOnClickListener { if(tab!=i){tab=i;showHome(true);refresh()} }
            if(selected&&tabChange)Motion.reveal(mark,y=0f,duration=180,from=.5f)
        }
        root.addView(nav,lp().apply{marginStart=dp(if(compact)4 else 14);marginEnd=dp(if(compact)4 else 14);bottomMargin=dp(if(compact)2 else 8);topMargin=dp(4)})
        renderHome(); updateUnreadBadge();if(tabChange)Motion.reveal(scroll,y=5f,duration=190,from=.6f)
    }
    private fun updateUnreadBadge() {
        val count=(0 until conversations.length()).sumOf { val c=conversations.getJSONObject(it);if(ConversationState.muted(Hub.me.optString("id"),c.getJSONObject("user").getString("id")))0L else c.optInt("unread").coerceAtLeast(0).toLong() }
        unreadBadge?.apply { text=if(count>99)"99+" else count.toString(); visibility=if(count>0)View.VISIBLE else View.GONE; contentDescription="$count 条未读消息" }
    }
    private fun searchBar(parent: LinearLayout) {
        val search=row().apply { touch(this,palette.surface,18);background=RippleDrawable(ColorStateList.valueOf(palette.ripple),BrandUi.card(this@MainActivity,palette,18),bg(Color.WHITE,18)); setPadding(dp(16),0,dp(16),0); contentDescription="搜索聊天与联系人"; setOnClickListener { localSearch() } }
        search.addView(Glyph(this,"search",muted),lp(18,18))
        search.addView(text(if(tiny)"搜索消息与联系人"else"搜索聊天与联系人",if(tiny)12f else 14f,muted).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(dp(10),0,0,0)})
        parent.addView(search,lp(-1,if(tiny)40 else 48).apply {topMargin=dp(2);bottomMargin=dp(if(tiny)10 else 6)})
    }
    private fun localSearch(){launchSearch()}
    private fun launchSearch(user:JSONObject?=null){
        stashDraft(true);searchSession=Hub.token;searchOwner=Hub.me.optString("id")
        startActivityForResult(Intent(this,SearchActivity::class.java).apply{user?.let{putExtra("peer",it.getString("id"));putExtra("nickname",it.optString("nickname"))}},82);Motion.activityOpen(this)
    }
    private fun cachedHome() {
        if(Hub.token.isEmpty())return
        val owner=Hub.me.optString("id");val revision=homeRevision;val stamp=LocalData.epoch(owner)
        Hub.job({LocalHistory.home(owner)},{result ->
            if(homeRevision!=revision||stamp!=LocalData.epoch(owner)||peer!=null||callScreen)return@job
            contacts=result.optJSONArray("contacts")?:JSONArray();conversations=result.optJSONArray("conversations")?:JSONArray();homeSignature="";renderHome();updateUnreadBadge()
        },{})
    }
    private fun refresh() {
        if(Hub.token.isEmpty()) return
        if(!Hub.connected){if(peer==null&&!callScreen)cachedHome();return}
        val session=Hub.token
        Hub.job({ ConversationState.load(Hub.workOwner(),session);LocalHistory.withPending(Hub.workOwner(),JSONObject().put("contacts",Hub.api("GET","/api/contacts").getJSONArray("contacts")).put("conversations",Hub.api("GET","/api/conversations").getJSONArray("conversations"))) }, {
            if(Hub.token!=session)return@job
            homeRevision++;contacts=it.getJSONArray("contacts"); conversations=it.getJSONArray("conversations"); updateUnreadBadge()
            val oldAvatar=peer?.optString("avatar_id")
            peer?.optString("id")?.let { id -> (0 until contacts.length()).map { contacts.getJSONObject(it) }.find { it.optString("id") == id }?.let { peer=it } }
            if(peer!=null && !callScreen && peer?.optString("avatar_id")!=oldAvatar)renderMessages(false)
            if(peer==null && !callScreen) renderHome()
            if(peer==null)connectionLabel?.text=homeConnection()
            updateStatusLabels()
        }, { if(Hub.token==session&&!Hub.connected)connectionLabel?.text="○  网络未连接，正在重试" })
    }
    private fun empty(parent: LinearLayout, title: String, detail: String, action: String? = null, click: (() -> Unit)? = null) {
        val box=col().apply { gravity=Gravity.CENTER; setPadding(dp(20),dp(70),dp(20),dp(40)) }
        val mark=FrameLayout(this).apply { background=StarlightDrawable(palette,dp(30).toFloat());addView(Glyph(this@MainActivity,"island",accent),FrameLayout.LayoutParams(dp(50),dp(50),Gravity.CENTER)) }
        box.addView(mark,lp(96,96)); space(box,26); box.addView(text(title,21f,ink,true)); space(box,12)
        box.addView(text(detail,14f,muted).apply { gravity=Gravity.CENTER; setLineSpacing(dp(6).toFloat(),1f) }); space(box,26)
        if(action!=null) box.addView(button(action) { click?.invoke() })
        parent.addView(box,lp())
    }
    private fun preview(m: JSONObject): String = when(m.optString("type")) { "html"->"[HTML 消息]"; "image"->"[图片]"; "voice"->"[语音消息]"; "file"->"[文件] " + (m.optJSONObject("file")?.optString("name") ?: ""); else->m.optString("text") }
    private fun conversationActions(anchor:View,user:JSONObject){
        val session=Hub.token;val owner=Hub.me.optString("id");val id=user.getString("id")
        val state=ConversationState.state(owner,id);conversationMenu?.dismiss()
        conversationMenu=PopupMenu(this,anchor).apply{
            menu.add(0,1,0,if(state.optLong("pinned_at")>0)"取消置顶"else"置顶聊天")
            menu.add(0,2,1,if(state.optBoolean("muted"))"关闭消息免打扰"else"开启消息免打扰")
            menu.add(0,3,2,"聊天设置")
            setOnMenuItemClickListener{item->
                if(Hub.token==session&&!isFinishing&&!isDestroyed){
                    if(item.itemId==3)startActivityForResult(Intent(this@MainActivity,PeopleActivity::class.java).putExtra("peer",id),77)
                    else{
                        val key=if(item.itemId==1)"pinned"else"muted";val value=if(item.itemId==1)state.optLong("pinned_at")==0L else !state.optBoolean("muted")
                        Hub.job({ConversationState.flag(owner,session,id,key,value);JSONObject()},{
                            if(key=="muted"&&value)getSystemService(NotificationManager::class.java).cancel(id.hashCode())
                            homeSignature="";renderHome();updateUnreadBadge()
                        },{toast(it)})
                    }
                };true
            };show()
        }
    }
    private fun stashDraft(flush:Boolean=false){
        if(restoringDraft||composerPeer.isEmpty()||composerSession!=Hub.token||composerOwner!=Hub.me.optString("id")||peer?.optString("id")!=composerPeer)return
        val box=composer?:return
        runCatching{
            ConversationState.draft(composerOwner,composerSession,composerPeer,box.text.toString(),replyTarget,box.selectionStart,box.selectionEnd)
            if(flush)ConversationState.flush(composerOwner,composerSession)
        }.onFailure{if(draftWarnings.add(composerPeer))toast(it.message?:"草稿暂未保存")}
    }
    private fun restoreDraft(){
        val box=composer?:return
        runCatching{
            val saved=ConversationState.state(composerOwner,composerPeer)
            restoringDraft=true
            replyTarget=saved.optJSONObject("quote")?.let{JSONObject(it.toString())}
            replyLabel?.text=replyTarget?.let{"引用："+it.optString("excerpt")}.orEmpty();replyBar?.visibility=if(replyTarget==null)View.GONE else View.VISIBLE
            box.setText(saved.optString("draft"));val start=saved.optInt("start",box.length()).coerceIn(0,box.length());val end=saved.optInt("end",start).coerceIn(0,box.length());box.setSelection(start,end)
        }.onFailure{if(draftWarnings.add(composerPeer))toast("本机草稿无法读取")}
        restoringDraft=false;sendButton?.isEnabled=!box.text.isNullOrBlank()&&composerPeer !in savingText;sendButton?.alpha=if(sendButton?.isEnabled==true)1f else .4f
    }
    private fun renderHome() {
        val parent=content ?: return
        if(tab==2) {
            if(feed==null){parent.removeAllViews();feed=MomentsView(this,palette,{saveMomentPhoto(it)}) { composePost() };parent.addView(feed,lp())}
            return
        }
        val signature="$tab|${palette.primary}|${Hub.me}|$contacts|$conversations|${ConversationState.revision(Hub.me.optString("id"))}|${AppUpdates.available?.code}"
        if(signature==homeSignature)return
        homeSignature=signature
        contactStatusLabels.clear(); parent.removeAllViews()
        if(tab==0)AppUpdates.available?.let{release->
            parent.addView(text("弥星 ${release.version} 已发布  ›",13f,accent).apply{
                setPadding(dp(14),dp(12),dp(14),dp(12));background=bg(soft,14);minimumHeight=dp(44)
                contentDescription="查看版本更新";isFocusable=true;setOnClickListener{startActivity(Intent(this@MainActivity,UpdateActivity::class.java))}
            },lp().apply{topMargin=dp(6);bottomMargin=dp(8)})
        }
        if(tab<2)searchBar(parent)
        if(tab==0) {
            val rows=ConversationState.conversations(Hub.me.optString("id"),contacts,conversations)
            if(rows.isEmpty()){empty(parent,"把第一句你好，送出去","添加朋友，开启一段新的对话","添加朋友"){searchFriend()};return}
            if(!tiny)section(parent,"最近聊天 · ${rows.size}")
            val list=col().apply{background=BrandUi.card(this@MainActivity,palette,20);clipToOutline=true}
            rows.forEachIndexed{index,c->
                val u=c.getJSONObject("user");val last=c.getJSONObject("last")
                list.addView(ConversationRow(this,palette,c,avatar(u,if(compact)40 else 50),preview(last).ifEmpty{"还没有消息"},{openChat(u)},{anchor->conversationActions(anchor,u)}),lp())
                if(index<rows.lastIndex)divider(list,if(compact)59 else 77)
            };parent.addView(list,lp())
        } else if(tab==1) renderContacts(parent) else renderProfile(parent)
    }
    private fun renderContacts(parent: LinearLayout) {
        val pending=(0 until contacts.length()).map { contacts.getJSONObject(it) }.filter { it.optString("state")=="incoming" }
        if(pending.isNotEmpty()) {
            section(parent,"新的朋友 · ${pending.size}")
            pending.forEach { u ->
                val item=col().apply { background=bg(soft,16); pad(this,16) }
                val top=row(); top.addView(avatar(u,44)); val info=col().apply { setPadding(dp(12),0,0,0) }; info.addView(text(displayName(u),16f,ink,true)); space(info,6); info.addView(text("请求添加你为好友",12f,muted)); top.addView(info,LinearLayout.LayoutParams(0,-2,1f)); item.addView(top); space(item,14)
                val actions=row(); actions.addView(button("忽略",false) { respond(u,false) },LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(10) }); actions.addView(button("通过申请") { respond(u,true) },LinearLayout.LayoutParams(0,-2,1f)); item.addView(actions)
                parent.addView(item,lp().apply { bottomMargin=dp(10) })
            }
        }
        val friends=(0 until contacts.length()).map { contacts.getJSONObject(it) }.filter { it.optString("state")=="accepted" }.sortedBy { it.optString("nickname") }
        section(parent,"我的朋友 · ${friends.size}")
        if(friends.isEmpty()) { empty(parent,"朋友，让生活热闹一点","用账号或弥星号，找到彼此","添加朋友") { searchFriend() }; return }
        friends.forEachIndexed { i,u ->
            val item=row().apply { touch(this,palette.surface,22); setPadding(dp(14),dp(15),dp(14),dp(15)); setOnClickListener { openChat(u) } }; item.addView(avatar(u,48))
            val info=col().apply { setPadding(dp(14),0,dp(8),0) }; info.addView(text(displayName(u),16f,ink,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }); space(info,6)
            val statusLabel=text(status(u),12f,if(Hub.connected && u.optBoolean("online"))accent else muted)
            info.addView(statusLabel); contactStatusLabels.add(u to statusLabel)
            item.addView(info,LinearLayout.LayoutParams(0,-2,1f)); item.addView(Glyph(this,"arrow",muted),lp(16,16)); parent.addView(item,lp().apply{bottomMargin=dp(9)})
        }
    }
    private fun respond(u: JSONObject, accept: Boolean) {
        val session=Hub.token
        Hub.job({ Hub.rawApi("POST","/api/friends/respond",JSONObject().put("peer",u.getString("id")).put("accept",accept),session).also {
            if(accept)runCatching { ChatCrypto.adoptPeerAfterFriendAction(u.getString("id"),session) }
        } }, { refresh(); if(accept)toast("已添加好友，现在可以聊天了") }, { toast(it) })
    }
    private fun renderProfile(parent: LinearLayout) {
        val card=col().apply { background=StarlightDrawable(palette,dp(24).toFloat());pad(this,20) }
        val identity=row()
        val portrait=FrameLayout(this).apply { contentDescription="更换头像"; setOnClickListener { avatarMenu() } }
        portrait.addView(avatar(Hub.me,68),FrameLayout.LayoutParams(dp(68),dp(68)))
        val camera=FrameLayout(this).apply { background=bg(palette.primary,12,Color.WHITE); addView(Glyph(this@MainActivity,"camera",palette.onPrimary),FrameLayout.LayoutParams(dp(13),dp(13),Gravity.CENTER)) }
        portrait.addView(camera,FrameLayout.LayoutParams(dp(24),dp(24),Gravity.BOTTOM or Gravity.END)); identity.addView(portrait,lp(72,72))
        val info=col().apply { setPadding(dp(16),0,0,0) }
        info.addView(text(Hub.me.optString("nickname"),22f,ink,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }); space(info,8)
        info.addView(text(if(MemberUi.active(Hub.me))"✦ 弥星会员 · 点按更换头像"else"点按更换头像",12f,accent).apply { setOnClickListener { avatarMenu() } })
        identity.addView(info,LinearLayout.LayoutParams(0,-2,1f)); card.addView(identity,lp()); space(card,22)
        val id=row().apply { contentDescription="复制弥星号"; touch(this,Color.TRANSPARENT,8); setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("弥星号",Hub.me.optString("id"))); toast("弥星号已复制") } }
        id.addView(text("弥星号  "+Hub.me.optString("id"),13f,accent),LinearLayout.LayoutParams(0,-2,1f)); id.addView(Glyph(this,"copy",accent),lp(16,16)); card.addView(id,lp(-1,30))
        card.addView(text("@"+Hub.me.optString("username"),12f,muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }); parent.addView(card,lp())
        fun setting(group: LinearLayout, kind: String, label: String, value: String, last: Boolean=false, action: (() -> Unit)?=null) {
            val r=row().apply { setPadding(dp(16),0,dp(14),0); minimumHeight=dp(58); if(action!=null)touch(this,Color.WHITE,16) }
            val mark=FrameLayout(this).apply { background=bg(canvas,10); addView(Glyph(this@MainActivity,kind,accent),FrameLayout.LayoutParams(dp(18),dp(18),Gravity.CENTER)) }
            r.addView(mark,lp(32,32)); r.addView(text(label,14f,ink).apply { setPadding(dp(12),0,dp(8),0) },LinearLayout.LayoutParams(0,-2,1f))
            r.addView(text(value,12f,muted).apply { maxWidth=dp(if(resources.configuration.screenWidthDp<360)84 else 112); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
            if(action!=null) { r.addView(Glyph(this,"arrow",Color.rgb(178,185,196)),lp(16,16)); r.setOnClickListener { action() } }
            group.addView(r,lp()); if(!last)divider(group,60)
        }
        section(parent,"弥星会员")
        val membership=col().apply{background=StarlightDrawable(palette,dp(18).toFloat());clipToOutline=true}
        setting(membership,"star","会员与订单",if(MemberUi.active(Hub.me))"已开通 · 查看权益"else"500MB · 专属标识",true){startActivity(Intent(this,MembershipActivity::class.java));Motion.activityOpen(this)}
        parent.addView(membership,lp())
        if(Hub.me.optBoolean("administrator")){
            section(parent,"平台管理")
            val management=col().apply{background=bg(Color.WHITE,18);clipToOutline=true}
            setting(management,"contacts","管理中心","动态 · 账号 · 记录",true){startActivity(Intent(this,AdminActivity::class.java));Motion.activityOpen(this)}
            parent.addView(management,lp())
        }
        section(parent,"个人资料")
        val profile=col().apply { background=bg(Color.WHITE,18); clipToOutline=true }
        setting(profile,"image","修改头像","相册选图与裁剪") { avatarMenu() }
        setting(profile,"edit","修改昵称",Hub.me.optString("nickname"),true) {
            val input=field("新昵称").apply { setText(Hub.me.optString("nickname")) }; val wrap=col().apply { pad(this,22); addView(input) }
            AlertDialog.Builder(this).setTitle("修改昵称").setView(wrap).setNegativeButton("取消",null).setPositiveButton("保存") { _,_ -> Hub.job({ Hub.api("PATCH","/api/me",JSONObject().put("nickname",input.text.toString())) }, { Hub.saveMe(it); renderHome() }, { toast(it) }) }.showThemed()
        }; parent.addView(profile,lp())
        section(parent,"账号安全")
        val security=col().apply { background=bg(Color.WHITE,18);clipToOutline=true }
        setting(security,"me","账号与安全","邮箱 · 密码 · 迁移") { startActivity(Intent(this,AccountActivity::class.java)) }
        setting(security,"me","黑名单","屏蔽与解除",true) { startActivityForResult(Intent(this,PeopleActivity::class.java),77);Motion.activityOpen(this) }
        parent.addView(security,lp())
        section(parent,"通用")
        val general=col().apply { background=bg(Color.WHITE,18); clipToOutline=true }
        setting(general,"palette","主题颜色",ThemeStore.name(palette.primary)) { startActivity(Intent(this,ThemeActivity::class.java));Motion.activityOpen(this) }
        setting(general,"mic","语音转文字","微信听写 · 预览后发送") { dictationPrivacy() }
        setting(general,"file","存储空间","云端 · 本机") { startActivity(Intent(this,StorageActivity::class.java));Motion.activityOpen(this) }
        setting(general,"bell","后台消息与来电","通知 · 后台运行") { startActivity(Intent(this,BackgroundActivity::class.java));Motion.activityOpen(this) }
        setting(general,"bell","检查更新",AppUpdates.available?.let{"新版 "+it.version}?:BuildConfig.VERSION_NAME,true) { startActivity(Intent(this,UpdateActivity::class.java));Motion.activityOpen(this) }
        parent.addView(general,lp()); space(parent,20)
        val developer=col().apply { background=bg(Color.WHITE,18);clipToOutline=true }
        setting(developer,"contacts","开发者机器人","API · Webhook",true) { startActivity(Intent(this,DeveloperActivity::class.java));Motion.activityOpen(this) }
        parent.addView(developer,lp());space(parent,20)
        parent.addView(button("退出登录",false) { AlertDialog.Builder(this).setMessage("退出后将停止接收消息和来电提醒。确定退出？").setNegativeButton("取消",null).setPositiveButton("退出") { _,_ -> Hub.logout(); showLogin() }.showThemed() }.apply { touch(this,Color.WHITE,14); setTextColor(muted); setTypeface(typeface,Typeface.NORMAL); textSize=14f },lp(-1,48)); space(parent,24)
        parent.addView(text("弥星 Mistar  ·  "+BuildConfig.VERSION_NAME+"\n和在意的人，自在相伴。",11f,muted).apply { gravity=Gravity.CENTER; setLineSpacing(dp(6).toFloat(),1f) },lp())
    }
    private fun searchFriend() {
        val input=field("输入账号或 8 位弥星号"); val wrap=col().apply { pad(this,22); addView(input) }
        val dialog=AlertDialog.Builder(this).setTitle("找到你的朋友").setView(wrap).setNegativeButton("取消",null).setPositiveButton("查找",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val q=input.text.toString().trim(); if(q.isEmpty())return@setOnClickListener
            Hub.job({ Hub.api("GET","/api/users?q="+Uri.encode(q)) }, { result ->
                val u=result.optJSONObject("user")
                if(u==null) { input.error="没有找到这个账号"; return@job }
                dialog.dismiss()
                val accepted=(0 until contacts.length()).map { contacts.getJSONObject(it) }.any { it.optString("id")==u.optString("id") && it.optString("state")=="accepted" }
                val bot=u.optBoolean("bot")
                val description="弥星号  ${u.optString("id")}\n账号  @${u.optString("username")}"+if(bot)"\n\n${u.optString("bot_description")}\n\n这是第三方机器人。发给它的内容由弥星服务器处理并推送给开发者，包括文字、语音、图片和文件。机器人聊天不属于用户间端到端加密。默认允许机器人来电，可在聊天设置关闭；来电仍需手动接听。" else ""
                AlertDialog.Builder(this).setTitle(displayName(u)).setMessage(description).setNegativeButton("取消",null).setPositiveButton(if(accepted)"发消息" else if(bot)"同意并添加" else "添加好友") { _,_ ->
                    if(accepted)openChat(u) else Hub.job({ Hub.api("POST","/api/friends/request",JSONObject().put("peer",u.getString("id")).put("bot_consent",bot)).also { runCatching { ChatCrypto.adoptPeerAfterFriendAction(u.getString("id"),Hub.token) } } }, { toast(if(bot)"机器人已添加" else "申请已发送，等待对方通过"); refresh() }, { toast(it) })
                }.showThemed()
            }, { toast(it) })
        } }; dialog.showThemed()
    }
    private fun showActions(title: String, subtitle: String="", preview: String="", actions: List<ActionSheets.Action>) {
        hideKeyboard(); stopTyping(); actionSheet?.dismiss()
        val dialog=ActionSheets.show(this,title,subtitle,preview,actions); actionSheet=dialog
        dialog.setOnDismissListener { if(actionSheet===dialog)actionSheet=null }
    }
    private fun avatarMenu() {
        showActions("修改头像","让朋友一眼认出你",actions=listOf(
            ActionSheets.Action("image","从相册选择","选图后可拖动、缩放和旋转") { avatarPickerSession=Hub.token;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),63) },
            ActionSheets.Action("me","恢复默认头像","使用昵称首字作为头像") { Hub.job({ Hub.api("DELETE","/api/me/avatar") }, { Hub.saveMe(it); renderHome(); toast("已恢复默认头像") }, { toast(it) }) }
        ))
    }
    private fun copyMessage(value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("弥星消息",value)); if(Build.VERSION.SDK_INT<33)toast("已复制")
    }
    private fun messageActions(m: JSONObject) {
        if(selecting){toggleMessageSelection(m);return}
        if(textSelectionMessage.isNotEmpty()&&textSelectionMessage!=m.optString("id"))finishTextSelection()
        val selectable=messageTextViews[m.optString("id")]
        if(selectable!=null&&textSelectionMessage!=m.optString("id")){
            selectable.requestFocus()
            val local=Rect();if(!selectable.getLocalVisibleRect(local))return
            val time=SystemClock.uptimeMillis();val x=(local.left+dp(6)).coerceAtMost(local.right-1).toFloat();val y=(local.top+dp(6)).coerceAtMost(local.bottom-1).toFloat()
            val down=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,x,y,0);selectable.onTouchEvent(down);down.recycle();selectable.performLongClick()
            val up=android.view.MotionEvent.obtain(time,time+1,android.view.MotionEvent.ACTION_UP,x,y,0);selectable.onTouchEvent(up);up.recycle();return
        }
        val anchor=messageBubbles[m.optString("id")]?:return
        if(m.optBoolean("pending")) {
            showMessageMenu(anchor,m,listOf(
                ActionSheets.Action("send","重试发送") { Hub.job({Outbox.retry(m.getString("client_id"));JSONObject()},{loadPending()},{toast(it)}) },
                ActionSheets.Action("close","取消发送","若消息已经送达，取消不会撤回该消息") { Hub.job({Outbox.cancel(m.getString("client_id"));JSONObject()},{messages.removeAll{it.optString("id")==m.optString("id")};renderMessages(false)},{toast(it)}) }
            ));return
        }
        val actions=mutableListOf<ActionSheets.Action>(); val f=m.optJSONObject("file")
        when(m.optString("type")) {
            "text" -> {actions.add(ActionSheets.Action("copy","复制文字") { val value=selectedMessageText(m);finishTextSelection();copyMessage(value) });if(selectable!=null)actions.add(ActionSheets.Action("partial","部分选中"){messageMenu?.dismiss();selectable.requestFocus()})}
            "html" -> { actions.add(ActionSheets.Action("code","展开 HTML 消息") { showHtml(m.optString("text")) }); actions.add(ActionSheets.Action("copy","复制 HTML 源码") { copyMessage(m.optString("text")) }) }
            "image" -> if(f!=null) { actions.add(ActionSheets.Action("image","查看图片") { viewImage(f) }); actions.add(ActionSheets.Action("download","保存图片") { saveAttachment(f) }) }
            "file" -> if(f!=null) { actions.add(ActionSheets.Action("file","用其他应用打开") { openWith(f) }); actions.add(ActionSheets.Action("download","保存到手机") { saveAttachment(f) }) }
            "voice" -> {
                actions.add(ActionSheets.Action(if(playingId==m.optString("id"))"pause" else "play",if(playingId==m.optString("id"))"停止播放" else "播放语音") { playVoice(m) })
                if(voiceCanTranscribe(m)){
                    val value=voiceTextFor(m);val id=m.optString("id")
                    if(voiceTextStates[id]?.running==true)actions.add(ActionSheets.Action("close","取消转文字"){cancelVoiceText(true)})
                    else actions.add(ActionSheets.Action("mic",if(value.isEmpty())"转文字" else if(id in hiddenVoiceTexts)"显示文字" else "收起文字",if(value.isEmpty())"微信听写识别 · 仅自己可见" else ""){if(value.isEmpty())transcribeVoice(m)else{if(!hiddenVoiceTexts.add(id))hiddenVoiceTexts.remove(id);refreshVoiceText(id)}})
                    if(value.isNotEmpty())actions.add(ActionSheets.Action("copy","复制识别文字"){copyMessage(value)})
                }
            }
        }
        if(!m.optBoolean("withdrawn")&&!m.optBoolean("decrypt_failed")&&m.optString("type")!="call")actions.add(ActionSheets.Action("reply","引用回复"){finishTextSelection();selectReply(m)})
        if(MessageForwarder.eligible(m))actions.add(ActionSheets.Action("forward","转发"){val item=JSONObject(m.toString());if(item.optString("type")=="text")item.put("text",selectedMessageText(m));finishTextSelection();chooseForward(listOf(item))})
        actions.add(ActionSheets.Action("select","多选"){beginMessageSelection(m)})
        actions.add(ActionSheets.Action("close","删除这条记录","仅删除自己账号中的这条消息") { messageMutation(m,false) })
        if(m.optString("sender")==Hub.me.optString("id")&&!m.optBoolean("withdrawn")&&m.optString("type")!="call"&&System.currentTimeMillis()-m.optLong("created")<=120000)
            actions.add(ActionSheets.Action("back","撤回消息","发送后 2 分钟内可撤回") { messageMutation(m,true) })
        showMessageMenu(anchor,m,actions)
    }
    private fun showMessageMenu(anchor:View,m:JSONObject,actions:List<ActionSheets.Action>){
        messageMenu?.dismiss();val session=Hub.token;val target=peer?.optString("id");val generation=chatGeneration
        val selectedText=textSelectionMessage==m.optString("id")&&textActionMode!=null
        val order=mapOf("复制文字" to 0,"播放语音" to 0,"停止播放" to 0,"查看图片" to 0,"展开 HTML 消息" to 0,"用其他应用打开" to 0,"转发" to 1,"转文字" to 1,"部分选中" to 2,"删除这条记录" to 3,"多选" to 4,"引用回复" to 5,"撤回消息" to 6)
        messageMenu=MessageActionMenu.show(this,anchor,actions.sortedBy{order[it.title]?:2},selectedText,{resumed&&Hub.token==session&&peer?.optString("id")==target&&chatGeneration==generation&&Hub.call==null&&messageBubbles[m.optString("id")]===anchor&&(!selectedText||textActionMode!=null&&textSelectionMessage==m.optString("id")&&selectedMessageText(m).isNotEmpty())}){if(messageMenu===it)messageMenu=null}
    }
    private fun selectedMessageText(m:JSONObject):String {
        val view=messageTextViews[m.optString("id")]
        if(textSelectionMessage!=m.optString("id")||view==null)return m.optString("text")
        val start=minOf(view.selectionStart,view.selectionEnd);val end=maxOf(view.selectionStart,view.selectionEnd)
        return if(start>=0&&end>start&&end<=view.text.length)view.text.subSequence(start,end).toString()else""
    }
    private fun finishTextSelection(){selectionMenuTask?.let{Hub.main.removeCallbacks(it)};selectionMenuTask=null;val mode=textActionMode;textActionMode=null;textSelectionMessage="";mode?.finish();messageMenu?.dismiss()}
    private fun selectableMessageText(m:JSONObject,size:Float,color:Int,maxWidth:Int):MessageTextView {
        val id=m.getString("id")
        return MessageTextView(this).apply{
            text=m.optString("text");textSize=size;setTextColor(color);this.maxWidth=maxWidth;includeFontPadding=false;setLineSpacing(dp(3).toFloat(),1f)
            setTextIsSelectable(true);highlightColor=if(m.optString("sender")==Hub.me.optString("id")){if(palette.onPrimary==Color.WHITE)Color.argb(70,0,0,0)else Color.argb(100,255,255,255)}else Color.argb(100,Color.red(palette.primary),Color.green(palette.primary),Color.blue(palette.primary))
            setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
            setOnLongClickListener{if(textActionMode!=null&&textSelectionMessage==id){messageActions(m);true}else false}
            if(Build.VERSION.SDK_INT>=29){textSelectHandleLeft?.setTint(palette.primary);textSelectHandleRight?.setTint(palette.primary)}
            customSelectionActionModeCallback=object:ActionMode.Callback2(){
                override fun onCreateActionMode(mode:ActionMode,menu:Menu):Boolean{
                    if(selecting||!resumed||messageTextViews[id]!==this@apply)return false
                    finishTextSelection();textActionMode=mode;textSelectionMessage=id;menu.clear()
                    post{if(textActionMode===mode&&isAttachedToWindow){android.text.Selection.setSelection(text as android.text.Spannable,0,text.length);selectionMenuTask?.let{Hub.main.removeCallbacks(it)};selectionMenuTask=Runnable{if(textActionMode===mode&&isAttachedToWindow)messageActions(m)}.also{Hub.main.postDelayed(it,140)}}}
                    return true
                }
                override fun onPrepareActionMode(mode:ActionMode,menu:Menu):Boolean{menu.clear();return false}
                override fun onActionItemClicked(mode:ActionMode,item:MenuItem)=false
                override fun onDestroyActionMode(mode:ActionMode){if(textActionMode===mode){textActionMode=null;textSelectionMessage="";selectionMenuTask?.let{Hub.main.removeCallbacks(it)};selectionMenuTask=null;messageMenu?.dismiss()}}
            }
            rangeChanged={if(textActionMode!=null&&textSelectionMessage==id){messageMenu?.dismiss();selectionMenuTask?.let{Hub.main.removeCallbacks(it)};selectionMenuTask=null;if(selectionStart>=0&&selectionEnd>=0&&selectionEnd!=selectionStart)selectionMenuTask=Runnable{if(textSelectionMessage==id&&resumed&&isAttachedToWindow)messageActions(m)}.also{Hub.main.postDelayed(it,180)}}}
            messageTextViews[id]=this
        }
    }
    private fun addSelectableMessage(parent:LinearLayout,holder:View,m:JSONObject):View{
        if(!selecting||m.optBoolean("pending")){
            val generation=chatGeneration;val session=Hub.token;val remote=peer?.optString("id");val id=m.optString("id")
            val eligible=m.optString("type") in setOf("text","html","image","voice","file")&&!m.optBoolean("pending")&&!m.optBoolean("withdrawn")&&!m.optBoolean("decrypt_failed")
            if(!eligible){parent.addView(holder,lp());return holder}
            val frame=ReplySwipeFrame(this,holder,{
                Hub.token==session&&peer?.optString("id")==remote&&generation==chatGeneration&&!isDestroyed&&!isFinishing&&!selecting&&textActionMode==null&&recording==null&&readyVoice==null&&!callScreen&&canContact()&&messages.any{it.optString("id")==id&&!it.optBoolean("withdrawn")&&!it.optBoolean("decrypt_failed")}
            }){selectReply(m);inputPanel?.hide();composer?.post{if(Hub.token==session&&peer?.optString("id")==remote&&!isDestroyed)(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(composer,InputMethodManager.SHOW_IMPLICIT)}}.apply{tag=id}
            parent.addView(frame,lp());return frame
        }
        val frame=object:FrameLayout(this){override fun onInterceptTouchEvent(event:android.view.MotionEvent)=true}.apply{tag=m.optString("id");isClickable=true;setOnClickListener{toggleMessageSelection(m)}};holder.tag=null;holder.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        frame.addView(holder,FrameLayout.LayoutParams(-1,-2).apply{marginStart=dp(if(compact)26 else 36)})
        val check=CheckBox(this).apply{
            buttonDrawable=MessageCheckDrawable(dp(24),palette);isChecked=m.optString("id") in selectedMessages;contentDescription="选择消息："+preview(m).take(100)
            setOnClickListener{toggleMessageSelection(m)}
        };selectionChecks[m.optString("id")]=check
        frame.addView(check,FrameLayout.LayoutParams(dp(if(compact)28 else 36),dp(48),Gravity.START or Gravity.CENTER_VERTICAL));parent.addView(frame,lp());return frame
    }
    private fun selectedSnapshots()=messages.filter{it.optString("id") in selectedMessages}.map{JSONObject(it.toString())}
    private fun beginMessageSelection(m:JSONObject){
        if(m.optBoolean("pending"))return
        inputPanel?.hide();finishTextSelection()
        messageMenu?.dismiss();hideKeyboard();stopTyping();stopPlayer();selecting=true;selectedMessages.clear();selectedMessages.add(m.optString("id"))
        chatHeader?.visibility=View.GONE;composerPanel?.visibility=View.GONE
        selectionHeader=row().apply{setPadding(dp(4),dp(4),dp(8),dp(4));setBackgroundColor(palette.surface)
            addView(icon("back","退出多选"){endMessageSelection()})
            selectionCount=text("",if(compact)14f else 17f,ink,true);addView(selectionCount,LinearLayout.LayoutParams(0,-2,1f))
            addView(text("完成",14f,accent).apply{minimumHeight=dp(44);gravity=Gravity.CENTER;setPadding(dp(10),0,dp(10),0);isFocusable=true;setOnClickListener{endMessageSelection()}})
        };root.addView(selectionHeader,0,lp())
        selectionFooter=row().apply{setPadding(dp(8),dp(8),dp(8),dp(8));setBackgroundColor(palette.surface)}
        fun action(iconName:String,title:String,click:()->Unit)=col().apply{
            gravity=Gravity.CENTER;minimumHeight=dp(56);contentDescription=title;touch(this)
            addView(Glyph(this@MainActivity,iconName,ink),lp(22,22));addView(text(title,11f,ink).apply{setPadding(0,dp(4),0,0)});setOnClickListener{click()}
        }
        selectionForward=action("forward","转发"){chooseForward(selectedSnapshots())}
        selectionDelete=action("trash","删除"){deleteSelectedMessages()}
        selectionFooter!!.addView(selectionForward,LinearLayout.LayoutParams(0,-2,1f));selectionFooter!!.addView(selectionDelete,LinearLayout.LayoutParams(0,-2,1f));root.addView(selectionFooter,lp())
        renderMessages(false);updateMessageSelection()
    }
    private fun toggleMessageSelection(m:JSONObject){
        if(!selecting||m.optBoolean("pending"))return
        val id=m.optString("id");if(id !in selectedMessages&&selectedMessages.size>=MessageForwarder.MAX_MESSAGES){toast("一次最多选择20条消息");selectionChecks[id]?.isChecked=false;return}
        if(!selectedMessages.add(id))selectedMessages.remove(id);updateMessageSelection()
    }
    private fun updateMessageSelection(){
        if(!selecting)return
        val allowed=messages.filter{!it.optBoolean("pending")}.map{it.optString("id")}.toSet();selectedMessages.retainAll(allowed)
        selectionCount?.text="已选 ${selectedMessages.size}/20"
        selectionChecks.forEach{(id,v)->v.isChecked=id in selectedMessages}
        val selected=selectedSnapshots();selectionDelete?.apply{isEnabled=selected.isNotEmpty();alpha=if(isEnabled)1f else .35f}
        selectionForward?.apply{isEnabled=selected.isNotEmpty()&&selected.all{MessageForwarder.eligible(it)};alpha=if(isEnabled)1f else .35f}
    }
    private fun endMessageSelection(){
        if(!selecting)return
        selecting=false;selectedMessages.clear();selectionChecks.clear();selectionHeader?.let{root.removeView(it)};selectionFooter?.let{root.removeView(it)};selectionHeader=null;selectionFooter=null
        chatHeader?.visibility=View.VISIBLE;composerPanel?.visibility=View.VISIBLE;renderMessages(false)
    }
    private fun deleteSelectedMessages(){
        val items=selectedSnapshots();if(items.isEmpty())return
        val session=Hub.token;val target=peer?.optString("id");val generation=chatGeneration
        AlertDialog.Builder(this).setTitle("删除${items.size}条记录？").setMessage("只删除自己账号各设备中的这些记录，对方的记录不受影响，删除后无法恢复。")
            .setNegativeButton("取消",null).setPositiveButton("删除"){_,_->
                if(Hub.token!=session||chatGeneration!=generation||peer?.optString("id")!=target)return@setPositiveButton
                val cancelled=java.util.concurrent.atomic.AtomicBoolean(false);val tag=Any()
                val dialog=ProgressDialog(this).apply{setTitle("删除记录");setMessage("0 / ${items.size}");setCancelable(true);setCanceledOnTouchOutside(false)};trackDialog(dialog)
                val cancel={cancelled.set(true);(Hub.http.dispatcher.runningCalls()+Hub.http.dispatcher.queuedCalls()).filter{it.request().tag()===tag}.forEach{it.cancel()};dialog.dismiss();Unit}
                batchDeleteCancel=cancel;dialog.setOnCancelListener{cancel()};dialog.setButton(DialogInterface.BUTTON_NEGATIVE,"停止"){_,_->cancel()};dialog.show()
                Hub.io.execute{
                    val removed=mutableSetOf<String>();var error=""
                    for(item in items){if(cancelled.get()||Hub.token!=session)break
                        try{Hub.rawApi("DELETE","/api/messages/"+Uri.encode(item.getString("id")),session=session,requestTag=tag);removed.add(item.getString("id"))}
                        catch(e:Exception){if(e is ApiFailure&&e.status==404)removed.add(item.getString("id"))else{error=e.message?:"删除失败";break}}
                        val count=removed.size;Hub.main.post{if(dialog.isShowing)dialog.setMessage("$count / ${items.size}")}
                    }
                    Hub.main.post{
                        dialog.dismiss();if(batchDeleteCancel===cancel)batchDeleteCancel=null
                        if(Hub.token==session&&chatGeneration==generation&&peer?.optString("id")==target&&!isDestroyed){
                            messages.removeAll{it.optString("id") in removed};removed.forEach{voiceTextResults.remove(it)}
                            if(voiceTextJob?.messageId in removed)cancelVoiceText();if(playingId in removed)stopPlayer()
                            selectedMessages.removeAll(removed);if(selectedMessages.isEmpty())endMessageSelection()else{renderMessages(false);updateMessageSelection()};refresh()
                            toast("已删除${removed.size}条"+if(error.isNotEmpty())"，其余未删除：$error"else if(cancelled.get())"，已停止"else"")
                        }
                    }
                }
            }.showThemed()
    }
    private fun chooseForward(items:List<JSONObject>){
        if(!Hub.connected){toast("连接恢复后可以转发");return}
        try{MessageForwarder.validate(items)}catch(e:Exception){toast(e.message?:"消息无法转发");return}
        val session=Hub.token;val target=peer?.optString("id");val generation=chatGeneration
        fun valid()=resumed&&Hub.token==session&&chatGeneration==generation&&peer?.optString("id")==target&&Hub.call==null&&!isDestroyed
        val body=col().apply{setPadding(dp(12),0,dp(12),dp(12))};val query=field("搜索好友");body.addView(query);val results=col();body.addView(results)
        body.addView(text("选择收件人后确认发送 · ${items.size}条逐条转发",11f,muted).apply{setPadding(0,dp(8),0,0)})
        val dialog=ActionSheets.content(this,"转发给", "",body);forwardDialog=dialog;trackDialog(dialog)
        var friends=emptyList<JSONObject>()
        fun filter(){results.removeAllViews();val term=query.text.toString().trim();val found=friends.filter{term.isEmpty()||it.optString("nickname").contains(term,true)||it.optString("username").contains(term,true)||it.optString("id").contains(term)}
            if(found.isEmpty())results.addView(text(if(friends.isEmpty())"暂无可转发的好友"else"没有匹配的好友",13f,muted).apply{setPadding(dp(8),dp(16),dp(8),dp(16))})
            found.take(80).forEach{user->val row=row().apply{setPadding(dp(6),dp(9),dp(6),dp(9));minimumHeight=dp(54);touch(this)}
                row.addView(avatar(user,36));row.addView(text(displayName(user),14f,ink,true).apply{setPadding(dp(10),0,dp(6),0);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f));row.contentDescription="转发给"+displayName(user)
                row.setOnClickListener{if(valid()){
                    dialog.dismiss();val warning=if(user.optBoolean("bot"))"\n\n这是第三方机器人，消息内容会交给机器人开发者处理。"else""
                    AlertDialog.Builder(this).setTitle("发送给${displayName(user)}？").setMessage("${items.size}条消息将按原顺序逐条转发。"+warning)
                        .setNegativeButton("取消",null).setPositiveButton("发送"){_,_->if(valid())startForward(items,user.getString("id"),::valid)}.showThemed()
                }};results.addView(row,lp())
            }
        }
        query.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun afterTextChanged(s:Editable?){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){filter()}})
        results.addView(text("正在加载好友…",13f,muted))
        Hub.job({Hub.rawApi("GET","/api/contacts",session=session)},{p->if(valid()&&dialog.isShowing){val array=p.optJSONArray("contacts")?:JSONArray();friends=(0 until array.length()).map{array.getJSONObject(it)}.filter{it.optString("state")=="accepted"&&!it.optBoolean("blocked")&&!it.optBoolean("blocked_by_me")};filter()}},{if(dialog.isShowing){results.removeAllViews();results.addView(text(it,13f,muted))}})
    }
    private fun startForward(items:List<JSONObject>,recipient:String,valid:()->Boolean){
        if(!valid())return
        forwardJob?.cancel()
        val dialog=ProgressDialog(this).apply{setTitle("逐条转发");setMessage("正在准备…");setCancelable(true);setCanceledOnTouchOutside(false)};forwardDialog=dialog;trackDialog(dialog)
        val session=Hub.token
        val job=MessageForwarder(this,items,recipient,valid,{count,total,stage->if(dialog.isShowing)dialog.setMessage("$stage\n$count / $total\n停止后，已加入待发的消息仍会发送。")},{count,error->
            dialog.dismiss();forwardJob=null;if(Hub.token==session&&!isDestroyed){if(selecting&&count==items.size)endMessageSelection();toast(if(error==null)"${count}条已加入待发消息"else"已加入待发${count}条：$error");refresh()}
        });forwardJob=job;dialog.setOnCancelListener{job.cancel()};dialog.setButton(DialogInterface.BUTTON_NEGATIVE,"停止"){_,_->job.cancel()};dialog.show();job.start()
    }
    private fun messageMutation(m:JSONObject,withdraw:Boolean) {
        finishTextSelection()
        val id=m.optString("id");val session=Hub.token;val target=peer?.optString("id");val generation=chatGeneration
        AlertDialog.Builder(this).setTitle(if(withdraw)"撤回消息？" else "删除这条记录？")
            .setMessage(if(withdraw)"对方可能已经看过或保存这条消息。" else "只删除自己账号各设备上的这条记录，无法恢复。")
            .setNegativeButton("取消",null).setPositiveButton("确定") { _,_ ->
                Hub.job({check(Hub.token==session);Hub.rawApi(if(withdraw)"POST" else "DELETE","/api/messages/$id"+if(withdraw)"/withdraw" else "",session=session)}, { result ->
                    if(Hub.token!=session||chatGeneration!=generation||peer?.optString("id")!=target)return@job
                    if(withdraw){val index=messages.indexOfFirst{it.optString("id")==id};if(index>=0)messages[index]=result}
                    else messages.removeAll{it.optString("id")==id}
                    if(voiceTextJob?.messageId==id)cancelVoiceText();voiceTextResults.remove(id);if(playingId==id)stopPlayer();renderMessages(false);refresh()
                },{if(Hub.token==session)toast(it)})
            }.showThemed()
    }
    private fun bindMessageActions(view: View,m: JSONObject,root: Boolean=true) {
        if(view is SeekBar)return
        if(root || view.isClickable)view.setOnLongClickListener { messageActions(m); true }
        if(view is ViewGroup)for(i in 0 until view.childCount) { val child=view.getChildAt(i); if(child !is WebView&&child !is MessageTextView)bindMessageActions(child,m,false) }
    }
    private fun cancelVoiceText(refresh:Boolean=false){val id=voiceTextJob?.messageId;voiceTextJob?.cancel();voiceTextJob=null;if(id!=null){voiceTextStates.remove(id);if(refresh)refreshVoiceText(id)}}
    private fun setVoiceTextState(id:String,running:Boolean,text:String){val next=VoiceTextState(running,text);if(voiceTextStates[id]==next)return;voiceTextStates[id]=next;refreshVoiceText(id)}
    private fun completeVoiceText(id:String,fid:String,value:String){
        voiceTextRevision++;voiceTextJob=null;voiceTextStates.remove(id);voiceTextResults.put(id,JSONObject().put("file_id",fid).put("text",value));hiddenVoiceTexts.remove(id);refreshVoiceText(id)
    }
    private fun refreshVoiceText(id:String){voiceTextPanels[id]?.let{renderVoiceTextPanel(it)}}
    private fun renderVoiceTextPanel(panel:VoiceTextPanel){
        val id=panel.message.optString("id");val state=voiceTextStates[id];val value=voiceTextFor(panel.message)
        val box=panel.view;box.removeAllViews()
        if(!voiceCanTranscribe(panel.message)||state==null&&(value.isEmpty()||id in hiddenVoiceTexts)){box.visibility=View.GONE;return}
        box.visibility=View.VISIBLE
        if(state!=null){
            val header=row()
            if(state.running)header.addView(ProgressBar(this).apply{indeterminateTintList=ColorStateList.valueOf(accent);contentDescription="正在转文字"},lp(14,14).apply{marginEnd=dp(6)})
            header.addView(text(if(state.running)"正在转文字…" else "转文字未完成",11f,muted).apply{maxLines=2},LinearLayout.LayoutParams(0,-2,1f))
            header.addView(text(if(state.running)"取消" else "重试",12f,accent).apply{gravity=Gravity.CENTER;minimumWidth=dp(40);minimumHeight=dp(36);setOnClickListener{if(state.running)cancelVoiceText(true)else transcribeVoice(panel.message)}})
            box.addView(header,LinearLayout.LayoutParams(-1,-2))
            if(state.text.isNotBlank())box.addView(text(state.text,if(compact)13f else 14f,if(state.running)ink else muted).apply{this.maxWidth=panel.width-dp(20);setLineSpacing(dp(3).toFloat(),1f)})
        }else{
            box.addView(text("语音转文字 · 仅自己可见",10f,muted).apply{this.maxWidth=panel.width-dp(20)})
            box.addView(text(value,if(compact)13f else 14f,ink).apply{this.maxWidth=panel.width-dp(20);setLineSpacing(dp(3).toFloat(),1f);setTextIsSelectable(true);setPadding(0,dp(6),0,0)})
        }
    }
    private fun voiceCanTranscribe(m:JSONObject)=m.optString("type")=="voice"&&!m.optBoolean("pending")&&!m.optBoolean("withdrawn")&&!m.optBoolean("decrypt_failed")&&m.optBoolean("attachment_available",true)&&m.optJSONObject("file")?.optBoolean("available",true)==true
    private fun voiceTextFor(m:JSONObject):String {
        if(!voiceCanTranscribe(m))return ""
        val value=voiceTextResults.optJSONObject(m.optString("id"))?:return ""
        return if(value.optString("file_id")==m.optJSONObject("file")?.optString("id"))value.optString("text")else ""
    }
    private fun loadVoiceTexts(){
        val target=peer?.optString("id")?:return;val owner=Hub.me.optString("id");val session=Hub.token;val generation=chatGeneration;val stamp=LocalData.epoch(owner);val revision=voiceTextRevision
        LocalData.worker.execute{try{val data=LocalData.voiceTexts(owner,target);Hub.main.post{if(Hub.token==session&&peer?.optString("id")==target&&generation==chatGeneration&&stamp==LocalData.epoch(owner)&&revision==voiceTextRevision){voiceTextResults=data;renderMessages(false)}}}catch(_:Exception){}}
    }
    private fun transcribeVoice(m:JSONObject){
        if(!voiceCanTranscribe(m)||Hub.token.isEmpty())return
        if(Hub.call!=null){toast("通话中暂不能转文字");return}
        if(recording!=null||readyVoice!=null){toast("请先发送或丢弃本次录音");return}
        val f=m.optJSONObject("file")?:return
        if(f.optLong("size") !in 1..VoiceTextAudio.MAX_BYTES){setVoiceTextState(m.optString("id"),false,"转文字支持5MB以内、最长60秒的语音");return}
        val owner=Hub.me.optString("id");val target=peer?.optString("id")?:return;val session=Hub.token;val generation=chatGeneration;val id=m.getString("id");val stamp=LocalData.epoch(owner)
        val valid={Hub.token==session&&Hub.me.optString("id")==owner&&peer?.optString("id")==target&&chatGeneration==generation&&LocalData.epoch(owner)==stamp&&Hub.foreground&&!callScreen&&Hub.call==null&&!isDestroyed&&!isFinishing}
        if(!valid()||messages.none{it.optString("id")==id&&voiceCanTranscribe(it)})return
        if(voiceTextJob?.messageId==id&&voiceTextJob?.isRunning==true)return
        cancelVoiceText(true);dictation?.cancel();dictation=null;stopPlayer();setVoiceTextState(id,true,"")
        voiceTextJob=VoiceTranscription(id,f.getString("id"),JSONObject(f.toString()),valid,{value->check(valid());LocalData.putVoiceText(owner,target,m,value,stamp)},
            {value->completeVoiceText(id,f.getString("id"),value)},
            {partial->setVoiceTextState(id,true,partial)},
            {reason->voiceTextJob=null;setVoiceTextState(id,false,reason)}).also{it.start()}
    }
    private fun uploadAvatar(uri: Uri) {
        val progress=ProgressDialog.show(this,"正在更新头像","上传完成后会自动同步给好友",true,false).also{trackDialog(it)}
        var ownPicture: android.graphics.Bitmap?=null
        Hub.job({
            val data=contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("无法读取裁剪图片")
            if(data.size>2*1024*1024)throw Exception("头像文件不能超过 2 MB")
            val body=object : RequestBody() {
                override fun contentType()="image/jpeg".toMediaType()
                override fun contentLength()=data.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(data) }
            }
            val result=Hub.http.newCall(Request.Builder().url(Hub.SERVER+"/api/me/avatar").header("Authorization","Bearer "+Hub.token).put(body).build()).execute().use { response ->
                val user=JSONObject(response.body?.string()?:"{}")
                if(!response.isSuccessful)throw Exception(user.optString("error","头像上传失败"))
                user
            }
            ownPicture=BitmapFactory.decodeByteArray(data,0,data.size)
            result
        }, {
            progress.dismiss(); ownPicture?.let { bitmap -> AvatarImages.seed(it.optString("avatar_id"),bitmap) }; Hub.saveMe(it)
            if(uri.authority=="cn.qingliao.app.files")contentResolver.delete(uri,null,null)
            tab=3; showHome(); refresh(); toast("头像已更新")
        }, {
            progress.dismiss(); if(uri.authority=="cn.qingliao.app.files")contentResolver.delete(uri,null,null); toast(it)
        })
    }

    private fun openChat(u: JSONObject) {
        if(readyVoice?.peer!=u.optString("id"))clearReadyVoice()
        stashDraft(true);composerPeer=""
        chatGeneration++
        if(recording!=null)stopRecording(false)
        stopPlayer();replyTarget=null;searchWindow=false;peer=u; Hub.viewingPeer=u.getString("id"); messages.clear(); hasMore=false
        motionDirection=1;showChat();cachedMessages(false);loadPending();if(Hub.connected)loadMessages(false);refreshRelationship()
    }
    private fun showChat() {
        cancelVoiceText();voiceTextResults=JSONObject();hiddenVoiceTexts.clear();voiceTextStates.clear();voiceTextPanels.clear()
        callScreen=false; reset(canvas); window.statusBarColor=palette.surface;window.navigationBarColor=palette.surface
        val u=peer ?: run { showHome(); return }
        val top=row().apply { setBackgroundColor(palette.surface); setPadding(dp(if(compact)2 else 6),dp(if(compact)0 else 8),dp(if(compact)2 else 8),dp(if(compact)0 else 10)) }
        top.addView(icon("back","返回消息") { leaveChat() })
        val info=col().apply { setPadding(dp(6),0,dp(8),0);minimumHeight=dp(if(compact)40 else 48);gravity=Gravity.CENTER_VERTICAL;contentDescription="聊天设置";setOnClickListener { people() } }
        peerTitle=text(if(compact)u.optString("nickname") else displayName(u),17f,ink,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }; info.addView(peerTitle,lp()); space(info,4)
        connectionLabel=text(status(u),11f,if(Hub.connected && u.optBoolean("online"))accent else muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}; info.addView(connectionLabel)
        top.addView(info,LinearLayout.LayoutParams(0,-2,1f))
        if(!u.optBoolean("bot") || u.optBoolean("bot_calls_enabled")&&u.optBoolean("bot_ready")) {
            top.addView(icon("phone","语音通话",accent) { beginCall(false) }.apply { touch(this,canvas,22) },lp(if(compact)40 else 48,if(compact)40 else 48).apply { marginEnd=dp(if(compact)0 else 6) })
            if(!compact)top.addView(icon("video","视频通话",accent) { beginCall(true) }.apply { touch(this,canvas,22) },lp(48,48))
        }
        top.addView(icon("more",if(compact)"聊天操作" else "聊天设置",muted) { if(compact)chatMenu() else people() },lp(if(compact)40 else 48,if(compact)40 else 48)); chatHeader=top;root.addView(top)
        divider(root)
        if(u.optBoolean("bot")&&!compact)root.addView(text(if(compact)"机器人 · 开发者处理消息" else "第三方机器人 · 内容由服务器中转并交给开发者处理",if(compact)10f else 11f,muted).apply{setPadding(dp(if(compact)8 else 18),dp(if(compact)4 else 9),dp(8),dp(if(compact)4 else 9));setBackgroundColor(soft)},lp())
        messageScroll=ScrollView(this).apply { isFillViewport=true; clipToPadding=false; setBackgroundColor(canvas); isVerticalScrollBarEnabled=false; setPadding(dp(if(compact)6 else 14),0,dp(if(compact)6 else 14),dp(if(compact)6 else 16)); setOnScrollChangeListener { _,_,_,_,_ -> syncHtmlPreviews();if(nearLatest())latestMessage?.visibility=View.GONE } }
        messageScroll!!.setOnTouchListener{_,event->
            if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN&&textActionMode!=null){
                val selected=messageTextViews[textSelectionMessage];val bounds=Rect();val point=IntArray(2)
                if(selected!=null&&selected.getLocalVisibleRect(bounds)){selected.getLocationOnScreen(point);bounds.offset(point[0],point[1]);bounds.inset(-dp(24),-dp(24))}
                if(selected==null||!bounds.contains(event.rawX.toInt(),event.rawY.toInt()))finishTextSelection()
            };false
        }
        messageList=col(); messageScroll!!.addView(messageList)
        val chatFrame=FrameLayout(this);chatFrame.addView(messageScroll,FrameLayout.LayoutParams(-1,-1));root.addView(chatFrame,LinearLayout.LayoutParams(-1,0,1f))
        latestMessage=button("回到最新消息") { latestMessage?.visibility=View.GONE;if(searchWindow){searchWindow=false;messages.clear();cachedMessages(false);loadMessages(false);loadPending()}else scrollLatest(true) }.apply { visibility=View.GONE;contentDescription="回到最新消息";elevation=dp(3).toFloat() }
        chatFrame.addView(latestMessage,FrameLayout.LayoutParams(-2,dp(42),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=dp(12) })
        divider(root)
        val compose=col().apply { background=BrandUi.card(this@MainActivity,palette,20);setPadding(dp(if(compact)4 else 8),dp(if(compact)3 else 6),dp(if(compact)4 else 8),dp(3)) }
        recordBar=row().apply{background=bg(soft,14);setPadding(dp(8),dp(2),dp(2),dp(2));visibility=View.GONE;tag="composer.recording"}
        recordLabel=text("正在录音  00:00",if(compact)11f else 13f,accent);recordBar!!.addView(recordLabel,LinearLayout.LayoutParams(0,-2,1f))
        recordBar!!.addView(icon("close","取消录音"){stopRecording(false)},lp(36,44))
        recordBar!!.addView(icon("stop","结束录音并预览",accent){stopRecording(false,true)}.apply{tag="composer.record.preview"},lp(36,44))
        recordBar!!.addView(icon("send","发送语音",accent){stopRecording(true)}.apply{tag="composer.record.send"},lp(40,44));compose.addView(recordBar,lp())
        voiceDraftRow=row().apply{background=bg(soft,14);setPadding(dp(4),dp(2),dp(2),dp(2));visibility=View.GONE;tag="composer.voiceDraft"}
        voiceDraftPlay=icon("play","试听录音",accent){previewReadyVoice()}.apply{tag="composer.voiceDraft.play"};voiceDraftRow!!.addView(voiceDraftPlay,lp(40,44))
        voiceDraftCaption=text("录音待发送",if(compact)11f else 13f,ink).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
        voiceDraftRow!!.addView(voiceDraftCaption,LinearLayout.LayoutParams(0,-2,1f))
        voiceDraftRow!!.addView(icon("trash","丢弃录音"){clearReadyVoice()}.apply{tag="composer.voiceDraft.discard"},lp(36,44))
        voiceDraftRow!!.addView(icon("send","发送这段录音",accent){sendReadyVoice()}.apply{tag="composer.voiceDraft.send"},lp(40,44));compose.addView(voiceDraftRow,lp())
        replyBar=row().apply{background=bg(canvas,12);setPadding(dp(10),dp(3),0,dp(3));visibility=if(replyTarget==null)View.GONE else View.VISIBLE}
        replyLabel=text(replyTarget?.optString("excerpt")?:"",12f,muted).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
        replyBar!!.addView(replyLabel,LinearLayout.LayoutParams(0,-2,1f));replyBar!!.addView(icon("close","取消引用"){replyTarget=null;replyBar?.visibility=View.GONE;stashDraft()});compose.addView(replyBar)
        val inputRow=row();inputRow.gravity=Gravity.BOTTOM;inputRowView=inputRow
        if(compact)inputRow.addView(icon("add","更多发送方式",accent){sendMenu()}.apply{tag="composer.more"},lp(36,44))
        else{recorderButton=icon("mic","录制语音消息",accent){startRecordingScoped()}.apply{tag="composer.record"};inputRow.addView(recorderButton,lp(40,48))}
        composer=EditText(this).apply {
            hint=if(compact)"消息" else "发消息…"; textSize=if(compact)14f else 16f; setTextColor(ink); setHintTextColor(muted); background=bg(canvas,15); setPadding(dp(if(compact)8 else 14),dp(if(compact)8 else 12),dp(if(compact)8 else 14),dp(if(compact)8 else 12)); minHeight=dp(if(compact)40 else 48);minimumWidth=0;minWidth=0
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines=if(compact)2 else 4
            setOnTouchListener{_,e->if(e.actionMasked==android.view.MotionEvent.ACTION_DOWN)inputPanel?.hide();false}
            filters=arrayOf(android.text.InputFilter.LengthFilter(5000));isSaveEnabled=false;tag="chat.composer"
            tintInput(this)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if(!restoringDraft)inputChanged(s); sendButton?.isEnabled=!s.isNullOrBlank()&&composerPeer !in savingText; sendButton?.alpha=if(sendButton?.isEnabled==true)1f else .4f;stashDraft() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        inputRow.addView(composer,LinearLayout.LayoutParams(0,-2,1f))
        sendButton=icon("send","发送消息",palette.onPrimary) { sendText() }.apply { touch(this,palette.primary,15); isEnabled=false; alpha=.4f }
        if(compact)inputRow.addView(icon("mic","语音转文字",accent){startDictation()}.apply{tag="composer.dictate"},lp(36,44))
        else{
            inputRow.addView(icon("smile","表情面板",accent){emojis()}.apply{tag="composer.emoji"},lp(36,48))
            inputRow.addView(icon("add","更多发送方式",accent){sendMenu()}.apply{tag="composer.more"},lp(36,48))
        }
        inputRow.addView(sendButton,lp(if(compact)40 else 48,if(compact)40 else 48).apply { marginStart=dp(if(compact)2 else 8) }); compose.addView(inputRow)
        val editor=composer!!;val session=Hub.token;val owner=Hub.me.optString("id");val target=u.getString("id")
        inputPanel=ChatInputPanel(this,palette,editor,{
            Hub.token==session&&Hub.me.optString("id")==owner&&peer?.optString("id")==target&&composer===editor&&!callScreen&&!isDestroyed&&!isFinishing
        },listOf(
            ChatInputPanel.Action("image","图片"){pickFile("image")},
            ChatInputPanel.Action("file","文件"){pickFile("file")},
            ChatInputPanel.Action("mic","录制语音",{Hub.call==null&&recording==null}){startRecordingScoped()},
            ChatInputPanel.Action("dictate","语音输入",{Hub.call==null&&recording==null}){startDictation()},
            ChatInputPanel.Action("code","HTML 卡片"){htmlEditor()},
            ChatInputPanel.Action("video","音视频通话",{recording==null}){chatMenu()}
        )){root.post{if(!isDestroyed)updateCompactChat()}}
        compose.addView(inputPanel,lp());root.addView(compose);composerPanel=compose;composerOwner=owner;composerSession=session;composerPeer=target;restoreDraft();renderMessages();loadVoiceTexts()
        renderRecordingUi()
    }

    private fun updateCompactChat(){
        val header=chatHeader?:return
        val available=root.height-root.paddingTop-root.paddingBottom
        val visibility=if(selecting||compact&&available in 1 until dp(170))View.GONE else View.VISIBLE
        if(header.visibility!=visibility)header.visibility=visibility
    }
    private fun chatMenu(){
        showActions("聊天操作",actions=listOf(
            ActionSheets.Action("phone","语音通话"){beginCall(false)},
            ActionSheets.Action("video","视频通话"){beginCall(true)},
            ActionSheets.Action("more","聊天设置"){people()}
        ))
    }
    private fun toggleInputPanel(mode:String){
        if(!contactGuard())return
        if(recording!=null){toast("请先发送或取消本次录音");return}
        hideKeyboard();stopTyping();actionSheet?.dismiss();inputPanel?.toggle(mode)
    }
    private fun sendMenu(){toggleInputPanel("more")}
    private fun startRecordingScoped(){
        if(!contactGuard()||Hub.call!=null){if(Hub.call!=null)toast("通话中暂不能录制语音");return}
        val session=Hub.token;val owner=Hub.me.optString("id");val target=peer?.optString("id");val editor=composer
        inputPanel?.hide()
        permission(arrayOf(Manifest.permission.RECORD_AUDIO)){
            if(Hub.token==session&&Hub.me.optString("id")==owner&&peer?.optString("id")==target&&composer===editor&&!callScreen&&Hub.call==null&&resumed&&Hub.foreground&&!isDestroyed)startRecording()
        }
    }
    private fun dictationPrivacy(){
        val prefs=getSharedPreferences("dictation",MODE_PRIVATE)
        AlertDialog.Builder(this).setTitle("语音转文字").setMessage("识别由微信听写在线服务提供。长按语音消息选择“转文字”后直接开始，仅上传所选语音；本机解密后发送，识别结果仅自己可见并加密缓存在本机。进度、取消和重试都在原语音下方，不弹出确认或处理窗口。\n\n输入框听写使用麦克风，首次仍需确认录音说明。两种方式都不发送聊天历史、草稿或弥星凭据，不会自动发出识别文字。声音上传后无法撤回。")
            .setNegativeButton("关闭",null).setPositiveButton("撤销麦克风听写授权"){_,_->prefs.edit().putBoolean("consent",false).apply();toast("已撤销麦克风听写授权")}.showThemed()
    }
    private fun startDictation(){
        if(!contactGuard()||Hub.call!=null){if(Hub.call!=null)toast("通话中暂不能语音转文字");return}
        if(recording!=null){toast("请先结束语音消息录音");return}
        val input=composer?:return;val remote=peer?.optString("id")?:return;val session=Hub.token
        fun launch(){
            permission(arrayOf(Manifest.permission.RECORD_AUDIO)){
                if(Hub.token!=session||peer?.optString("id")!=remote||composer!==input||Hub.call!=null)return@permission
                val valid={Hub.token==session&&session.isNotEmpty()&&peer?.optString("id")==remote&&composer===input&&!callScreen&&Hub.call==null&&!isDestroyed}
                val draft=DictationDraft(input,valid)
                hideKeyboard();stopTyping();stopPlayer();dictation?.cancel()
                dictation=DictationDialog(this,valid){value->toast(draft.insert(value)?:"已填入，请检查后发送")}.also{it.show()}
            }
        }
        val prefs=getSharedPreferences("dictation",MODE_PRIVATE)
        if(prefs.getBoolean("consent",false))launch() else AlertDialog.Builder(this).setTitle("使用语音转文字").setMessage("仅你主动录制的本次声音会直接发送至微信听写服务用于识别和文字整理，不发送聊天历史、草稿或弥星凭据。声音上传后无法撤回。最长60秒；结果填入输入框，由你检查发送。可随时取消。")
            .setNegativeButton("取消",null).setPositiveButton("同意并开始"){_,_->prefs.edit().putBoolean("consent",true).apply();launch()}.showThemed()
    }
    private fun bubbleBackground(mine: Boolean): GradientDrawable = (if(mine)BrandUi.primary(this,palette,20) else BrandUi.card(this,palette,20)).apply {
        val wide=dp(20).toFloat(); val small=dp(7).toFloat()
        cornerRadii=if(mine)floatArrayOf(wide,wide,small,small,wide,wide,wide,wide) else floatArrayOf(small,small,wide,wide,wide,wide,wide,wide)
    }
    private fun messageTime(ms: Long): String {
        val today=Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }.timeInMillis
        val label=if(ms>=today)"今天" else if(ms>=today-86400000)"昨天" else SimpleDateFormat("MM月dd日",Locale.CHINA).format(Date(ms))
        return label+"  "+SimpleDateFormat("HH:mm",Locale.CHINA).format(Date(ms))
    }
    private fun leaveChat() { chatGeneration++;if(recording!=null)stopRecording(false); hideKeyboard(); stopPlayer();motionDirection=-1;showHome(); refresh() }
    private fun loadPending() {
        val id=peer?.optString("id")?:return;val owner=Hub.me.optString("id");val generation=chatGeneration
        Hub.job({JSONObject().put("pending",Outbox.list(owner,id))},{result->
            if(peer?.optString("id")!=id||generation!=chatGeneration)return@job
            val list=result.getJSONArray("pending")
            if(JSONArray(messages.filter{it.optBoolean("pending")}).toString()==list.toString())return@job
            messages.removeAll{it.optBoolean("pending")}
            for(i in 0 until list.length()){val m=list.getJSONObject(i);if(messages.none{it.optString("client_id")==m.optString("client_id")&&it.optString("sender")==owner})messages.add(m)}
            messages.sortBy{it.optLong("created")};renderMessages(false)
        },{})
    }
    private fun cachedMessages(older:Boolean) {
        val id=peer?.optString("id")?:return;val generation=chatGeneration;val owner=Hub.me.optString("id");val stamp=LocalData.epoch(owner)
        val before=if(older)messages.firstOrNull()?.optLong("created")?:Long.MAX_VALUE else Long.MAX_VALUE
        Hub.job({JSONObject().put("messages",LocalData.messages(owner,id,51,before))},{result->
            if(peer?.optString("id")!=id||generation!=chatGeneration||stamp!=LocalData.epoch(owner)||(!older&&messages.any{!it.optBoolean("pending")}))return@job
            val list=result.getJSONArray("messages");hasMore=list.length()>50
            val from=if(hasMore)1 else 0
            for(i in from until list.length()){val m=list.getJSONObject(i).put("local_cache",true);if(messages.none{it.optString("id")==m.optString("id")})messages.add(m)}
            messages.sortBy{it.optLong("created")};renderMessages(!older,older)
        },{})
    }
    private fun loadMessages(older: Boolean) {
        if(!Hub.connected){cachedMessages(older);return}
        val stamp=LocalData.epoch(Hub.me.optString("id"))
        val id=peer?.getString("id") ?: return
        val generation=chatGeneration;val session=Hub.token
        val before=if(older && messages.isNotEmpty())"?before="+messages.first().optLong("created") else ""
        Hub.job({ Hub.api("GET","/api/messages/$id$before") }, { result ->
            if(peer?.optString("id")!=id||generation!=chatGeneration||session!=Hub.token)return@job
            if(stamp!=LocalData.epoch(Hub.me.optString("id"))){loadMessages(older);return@job}
            val list=result.getJSONArray("messages")
            val batch=(0 until list.length()).map { list.getJSONObject(it) }
            if(older)messages.addAll(0,batch.filter { m -> messages.none { it.optString("id")==m.optString("id") } }) else {
                val live=messages.filter { !it.optBoolean("local_cache") && (it.optBoolean("pending") || it.optLong("created")>=(batch.lastOrNull()?.optLong("created")?:0L)&&batch.none { m -> m.optString("id")==it.optString("id") }) }
                messages.clear();messages.addAll(batch);messages.addAll(live);messages.sortBy { it.optLong("created") }
            }
            hasMore=result.optBoolean("has_more"); renderMessages(!older,older)
            Hub.io.execute { try { Hub.rawApi("POST","/api/read/$id",session=session) } catch(_:Exception){} }
        }, { if(generation==chatGeneration&&session==Hub.token)toast(it) })
    }
    private fun renderMessages(scroll: Boolean = true,older: Boolean=false) {
        finishTextSelection();messageTextViews.clear()
        messageMenu?.dismiss();messageBubbles.clear();selectionChecks.clear()
        val parent=messageList ?: return
        val viewport=messageScroll ?: return
        val oldHeight=parent.height;val oldY=viewport.scrollY
        releaseChatImages();releaseHtmlPreviews();receiptLabels.clear();voiceTextPanels.clear();voiceViews.clear();parent.removeAllViews()
        if(hasMore)parent.addView(text("查看更早的消息",12f,accent).apply { gravity=Gravity.CENTER;setPadding(0,dp(18),0,dp(18));setOnClickListener { loadMessages(true) } },lp())
        if(messages.isEmpty()){parent.addView(text("从一句问候开始吧",13f,muted).apply { gravity=Gravity.CENTER;setPadding(0,dp(40),0,dp(40)) },lp());return}
        messages.forEachIndexed { index,m -> addMessageRow(parent,m,if(index==0)0L else messages[index-1].optLong("created")) }
        updateMessageSelection()
        parent.doOnPreDraw {
            if(parent===messageList){
                if(scroll)viewport.scrollTo(0,(parent.height+viewport.paddingBottom-viewport.height).coerceAtLeast(0))
                else viewport.scrollTo(0,if(older)oldY+(parent.height-oldHeight).coerceAtLeast(0)else oldY)
                syncHtmlPreviews()
            }
        }
    }
    private fun addMessageRow(parent:LinearLayout,m:JSONObject,previousTime:Long):View {
        val created=m.optLong("created")
        if(created-previousTime>300000)parent.addView(text(messageTime(created),11f,muted).apply { gravity=Gravity.CENTER;setPadding(0,dp(if(compact)6 else 22),0,dp(if(compact)6 else 20)) },lp())
        if(m.optString("type")=="call"||m.optBoolean("withdrawn")){
            val entry=text(m.optString("text"),12f,muted).apply { tag=m.optString("id");gravity=Gravity.CENTER;setPadding(dp(10),dp(12),dp(10),dp(12)) };messageBubbles[m.optString("id")]=entry;entry.setOnLongClickListener { messageActions(m);true };return addSelectableMessage(parent,entry,m)
        }
        val mine=m.optString("sender")==Hub.me.optString("id")
        val holder=row().apply { tag=m.optString("id");gravity=Gravity.TOP or if(mine)Gravity.END else Gravity.START; setPadding(0,dp(4),0,dp(if(compact)6 else 12)) }
        val body=col().apply { gravity=if(mine)Gravity.END else Gravity.START }
        val person=avatar(if(mine)Hub.me else peer ?: JSONObject(),if(compact)24 else 36).apply {
            (background as GradientDrawable).cornerRadius=dp(18).toFloat()
            contentDescription=if(mine)"我的头像" else "对方头像"
        }
        val bodyParams=lp(-2,-2).apply { if(mine)marginEnd=dp(if(compact)6 else 10) else marginStart=dp(if(compact)6 else 10) }
        if(!mine)holder.addView(person)
        val bubble=col().apply { background=bubbleBackground(mine); setPadding(dp(if(compact)10 else 14),dp(if(compact)8 else 11),dp(if(compact)10 else 14),dp(if(compact)8 else 11)) }
        val bubbleInk=if(mine)palette.onPrimary else ink
        val bubbleMuted=if(mine)palette.onPrimary else muted
        val maxBubbleWidth=minOf((resources.displayMetrics.widthPixels*.72).toInt(),resources.displayMetrics.widthPixels-dp(if(compact)12+24+6 else 28+36+10))
        val maxWidth=(maxBubbleWidth-dp(28)).coerceAtLeast(dp(80))
        m.optJSONObject("quote")?.let { q ->
            val original=messages.find{it.optString("id")==q.optString("id")}
            val unavailable=original?.optBoolean("withdrawn")==true||original?.optBoolean("decrypt_failed")==true
            val mismatch=original!=null&&(original.optString("sender")!=q.optString("sender")||quoteExcerpt(original)!=q.optString("excerpt"))
            val quoteView=col().apply{background=bg(if(mine)palette.primaryInset else canvas,10);setPadding(dp(10),dp(8),dp(10),dp(8));contentDescription="定位引用的原消息";setOnClickListener{jumpMessage(q.optString("id"))}}
            quoteView.addView(text(if(unavailable)"原消息已不可用" else if(mismatch)"引用内容无法验证" else "引用消息",10f,bubbleMuted))
            if(!unavailable&&!mismatch)quoteView.addView(text(q.optString("excerpt"),12f,bubbleInk).apply{this.maxWidth=(maxWidth-dp(20)).coerceAtLeast(dp(60));maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(5),0,0)})
            bubble.addView(quoteView,lp(-2,-2));space(bubble,8)
        }
        if(!m.optBoolean("attachment_available",true)&&m.optString("type") in listOf("image","file","voice")) {
            bubble.addView(text("云端附件已被删除",14f,bubbleMuted).apply{this.maxWidth=maxWidth})
        } else when(m.optString("type")) {
            "text" -> bubble.addView(if(m.optBoolean("decrypt_failed")||m.optBoolean("pending"))text(m.optString("text"),if(compact)14f else 16f,bubbleInk).apply{this.maxWidth=maxWidth}else selectableMessageText(m,if(compact)14f else 16f,bubbleInk,maxWidth))
            "html" -> {
                val source=m.optString("text")
                val widthDp=((maxBubbleWidth-dp(8))/resources.displayMetrics.density).toInt()
                val frame=FrameLayout(this).apply { background=bg(Color.WHITE,12); clipToOutline=true }
                val placeholder=text("HTML 消息",14f,muted).apply { gravity=Gravity.CENTER }
                frame.addView(placeholder,FrameLayout.LayoutParams(-1,-1))
                val overlay=View(this).apply { contentDescription="查看 HTML 消息"; isClickable=true; isFocusable=true; setOnClickListener { showHtml(source) } }
                frame.addView(overlay,FrameLayout.LayoutParams(-1,-1))
                htmlPreviews.add(HtmlPreview(frame,placeholder,source))
                bubble.setPadding(dp(4),dp(4),dp(4),dp(4)); bubble.addView(frame,lp(widthDp,188))
                bubble.addView(text("HTML · 点按展开",11f,if(mine)palette.onPrimary else accent).apply { setPadding(dp(10),dp(10),dp(10),dp(8)); setOnClickListener { showHtml(source) } })
            }
            "image" -> {
                val f=m.optJSONObject("file")
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; contentDescription="查看图片"; background=bg(line,12); clipToOutline=true; setOnClickListener { f?.let { viewImage(it) } } }
                val imageWidth=minOf(214,((maxBubbleWidth-dp(8))/resources.displayMetrics.density).toInt())
                val frame=FrameLayout(this);val label=text("图片",12f,muted).apply{gravity=Gravity.CENTER}
                frame.addView(image,FrameLayout.LayoutParams(-1,-1));frame.addView(label,FrameLayout.LayoutParams(-1,-1))
                bubble.setPadding(dp(4),dp(4),dp(4),dp(4));bubble.addView(frame,lp(imageWidth,imageWidth*158/214))
                if(f!=null){val item=ChatImage(image,label,f);chatImages.add(item);image.setOnClickListener{if(item.failed){item.failed=false;loadChatImage(item)}else viewImage(f)};label.setOnClickListener{image.performClick()}}
            }
            "file" -> {
                val f=m.optJSONObject("file"); val r=row()
                val mark=FrameLayout(this).apply { background=bg(if(mine)palette.primaryInset else Color.rgb(239,235,246),11); addView(Glyph(this@MainActivity,"file",if(mine)palette.onPrimary else Color.rgb(132,96,158)),FrameLayout.LayoutParams(dp(23),dp(26),Gravity.CENTER)) }
                r.addView(mark,lp(40,44))
                val detail=col().apply { setPadding(dp(12),0,0,0) }
                detail.addView(text(f?.optString("name")?:"文件",14f,bubbleInk,true).apply { maxLines=2; this.maxWidth=(maxWidth-dp(52)).coerceAtLeast(dp(60)); ellipsize=android.text.TextUtils.TruncateAt.END }); space(detail,7)
                val ext=f?.optString("name")?.substringAfterLast('.',"")?.take(8)?.uppercase(Locale.ROOT) ?: ""
                detail.addView(text((if(ext.isEmpty())"文件" else ext)+" · "+size(f?.optLong("size")?:0),11f,bubbleMuted)); r.addView(detail); bubble.addView(r); bubble.contentDescription="打开文件"; bubble.setOnClickListener { f?.let { openAttachment(it) } }
            }
            "voice" -> {
                val f=m.optJSONObject("file");val key=voiceKey(m)
                val widget=VoiceMessageView(this,palette,key,m.optString("text","语音"),bubbleInk,bubbleMuted,maxWidth,{playVoice(m)},{value->if(voicePlayback?.activeKey==key)voicePlayback?.seek(value)},{if(voicePlayback?.activeKey==key)voicePlayback?.cycleSpeed()})
                voiceViews[key]=widget;widget.render(voicePlayback?.state(key)?:VoicePlayback.State(key));bubble.addView(widget)
                bubble.contentDescription="播放语音";bubble.setOnClickListener{if(f!=null)playVoice(m)}
            }
        }
        messageBubbles[m.optString("id")]=bubble;bindMessageActions(bubble,m)
        body.addView(bubble,lp(-2,-2))
        if(voiceCanTranscribe(m)){
            val transcript=col().apply{setPadding(dp(10),dp(6),dp(10),dp(8));background=bg(palette.surface,12,line)}
            val panel=VoiceTextPanel(transcript,m,maxBubbleWidth);voiceTextPanels[m.optString("id")]=panel;renderVoiceTextPanel(panel)
            body.addView(transcript,LinearLayout.LayoutParams(maxBubbleWidth,-2).apply{topMargin=dp(5)})
        }
        if(mine) {
            val receipt=row().apply { gravity=Gravity.END; setPadding(0,dp(if(compact)3 else 6),dp(2),0) }
            val clock=text(SimpleDateFormat("HH:mm",Locale.CHINA).format(Date(created)),10f,muted);receipt.addView(clock)
            val readLabel=text(" · "+if(m.optBoolean("pending"))when(m.optString("send_state")){"blocked"->"发送失败 · 点按重试";"failed"->"等待重试";"sending"->"发送中";else->"待发送"} else if(m.optBoolean("read"))"已读" else "已发送",10f,if(m.optBoolean("read"))accent else muted).apply{this.maxWidth=(maxBubbleWidth-clock.paint.measureText(clock.text.toString()).toInt()-dp(2)).coerceAtLeast(dp(50));maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
            receipt.addView(readLabel);receiptLabels[m.optString("id")]=readLabel;if(m.optBoolean("pending")){readLabel.setOnClickListener{messageActions(m)};readLabel.contentDescription="待发消息操作"};body.addView(receipt)
        }
        holder.addView(body,bodyParams)
        if(mine)holder.addView(person)
        return addSelectableMessage(parent,holder,m)
    }
    private fun releaseHtmlPreviews() {
        htmlPreviews.forEach { item -> item.web?.let { HtmlCards.dispose(it) }; item.web=null }
        htmlPreviews.clear()
    }
    private fun syncHtmlPreviews() {
        syncChatImages()
        if(callScreen)return
        var visible=0
        htmlPreviews.forEach { item ->
            val rect=Rect()
            val show=item.frame.isAttachedToWindow && item.frame.getGlobalVisibleRect(rect) && rect.height()>0 && visible<4
            if(show) {
                visible++
                if(item.web==null) {
                    try {
                        val web=HtmlCards.create(this,item.source,ready={if(item.web!=null)item.placeholder.visibility=View.GONE}) { item.web=null; item.placeholder.text="点按查看源码"; item.placeholder.visibility=View.VISIBLE }
                        item.web=web; item.placeholder.text="正在加载 HTML 卡片…";item.placeholder.visibility=View.VISIBLE
                        item.frame.addView(web,0,FrameLayout.LayoutParams(-1,-1))
                    } catch(_:Exception) { item.placeholder.text="点按查看源码" }
                }
            } else item.web?.let { HtmlCards.dispose(it); item.web=null; item.placeholder.visibility=View.VISIBLE }
        }
    }
    private fun htmlEditor() {
        if(!contactGuard())return
        val recipient=peer?.optString("id") ?: return
        val session=Hub.token;val owner=Hub.me.optString("id");val reference=replyTarget;val quote=replyTarget?.let{JSONObject(it.toString())}
        fun valid()=Hub.token==session&&Hub.me.optString("id")==owner&&peer?.optString("id")==recipient&&!isDestroyed&&!isFinishing
        var pendingSource="";var client=""
        stopTyping(); hideKeyboard()
        val input=EditText(this).apply {
            hint="<div style=\"padding:16px;color:${ThemeStore.hex(accent)}\">你好，HTML！</div>"
            textSize=13f; typeface=Typeface.MONOSPACE; gravity=Gravity.TOP
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background=bg(canvas,12); pad(this,12); filters=arrayOf(android.text.InputFilter.LengthFilter(HtmlCards.MAX_LENGTH))
        }
        val form=col().apply { pad(this,20,12); addView(text("支持 HTML/CSS 排版和内嵌图片，脚本与外部资源不加载。",12f,muted)); space(this,12); addView(input,lp(-1,260)) }
        val dialog=AlertDialog.Builder(this).setTitle("发送 HTML 消息").setView(form).setNegativeButton("取消",null).setNeutralButton("预览",null).setPositiveButton("发送",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if(input.text.isBlank())input.error="请粘贴 HTML 代码" else showHtml(input.text.toString())
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if(!valid())return@setOnClickListener
                val source=input.text.toString().trim()
                if(source.isEmpty()) { input.error="请粘贴 HTML 代码"; return@setOnClickListener }
                if(pendingSource!=source){pendingSource=source;client=UUID.randomUUID().toString()}
                val send=dialog.getButton(AlertDialog.BUTTON_POSITIVE); send.isEnabled=false
                Hub.job({ check(valid());Hub.api("POST","/api/messages",JSONObject().put("recipient",recipient).put("type","html").put("text",source).put("client_id",client).apply{quote?.let{put("quote",it)}}) }, {
                    if(!valid())return@job
                    consumeReply(reference,recipient,session);dialog.dismiss(); hideKeyboard(); if(peer?.optString("id")==recipient)appendMessage(it) else toast("HTML 消息已发送")
                }, { send.isEnabled=true; toast("发送失败：$it") })
            }
        }
        dialog.showThemed()
    }
    private fun showHtml(source: String) {
        hideKeyboard()
        var web: WebView?=null
        try {
            web=HtmlCards.create(this,source)
            val box=FrameLayout(this).apply { addView(web,FrameLayout.LayoutParams(-1,dp(420))) }
            val dialog=AlertDialog.Builder(this).setTitle("HTML 消息").setView(box).setNegativeButton("关闭",null).setPositiveButton("查看源码") { _,_ -> showHtmlSource(source) }.create()
            dialog.setOnDismissListener { web?.let { HtmlCards.dispose(it) } }
            dialog.showThemed()
        } catch(_:Exception) { web?.let { HtmlCards.dispose(it) }; showHtmlSource(source) }
    }
    private fun showHtmlSource(source: String) {
        val scroll=ScrollView(this).apply { addView(text(source,12f).apply { typeface=Typeface.MONOSPACE; setTextIsSelectable(true); pad(this,18) }) }
        AlertDialog.Builder(this).setTitle("HTML 源码").setView(scroll).setNegativeButton("关闭",null).setPositiveButton("复制源码") { _,_ ->
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("HTML 源码",source)); toast("HTML 源码已复制")
        }.showThemed()
    }
    private fun sendText() {
        if(!contactGuard())return
        val input=composer?:return;val value=input.text.toString().trim();val id=peer?.optString("id")?:return
        if(value.isEmpty()||id in savingText)return
        if(value.length>5000){toast("消息不能超过 5000 个字");return}
        stashDraft();stopTyping();savingText.add(id);sendButton?.isEnabled=false;sendButton?.alpha=.4f
        val quote=replyTarget?.let{JSONObject(it.toString())}
        val request=JSONObject().put("recipient",id).put("type","text").put("text",value).put("client_id",UUID.randomUUID().toString()).apply{if(quote!=null)put("quote",quote)}
        Outbox.queueAsync(request,{
            savingText.remove(id)
            if(peer?.optString("id")==id){
                if(composer?.text?.toString()?.trim()==value&&replyTarget?.toString()==quote?.toString()){
                    restoringDraft=true;composer?.setText("");replyTarget=null;replyBar?.visibility=View.GONE;restoringDraft=false
                }
                sendButton?.isEnabled=!composer?.text.isNullOrBlank();sendButton?.alpha=if(sendButton?.isEnabled==true)1f else .4f;appendMessage(it)
            }else if(peer==null&&!callScreen)cachedHome()
        },{reason->
            savingText.remove(id)
            if(peer?.optString("id")==id){sendButton?.isEnabled=!composer?.text.isNullOrBlank();sendButton?.alpha=if(sendButton?.isEnabled==true)1f else .4f}
            toast("待发消息未保存，已保留草稿：$reason")
        },clearDraft=true)
    }
    private fun consumeReply(reference:JSONObject?,target:String,session:String){
        if(reference!=null&&replyTarget===reference&&peer?.optString("id")==target&&Hub.token==session){replyTarget=null;replyBar?.visibility=View.GONE;stashDraft()}
    }
    private fun quoteExcerpt(m:JSONObject):String {
        val value=when(m.optString("type")){"image"->"[图片]";"voice"->"[语音]";"file"->"[文件]";"html"->"[HTML 消息]";else->m.optString("text")}
        val cut=value.take(160);return if(cut.lastOrNull()?.isHighSurrogate()==true)cut.dropLast(1)else cut
    }
    private fun selectReply(m:JSONObject) {
        if(recording!=null||readyVoice!=null){toast("请先发送或丢弃本次录音");return}
        replyTarget=JSONObject().put("id",m.getString("id")).put("sender",m.getString("sender")).put("type",m.getString("type")).put("excerpt",quoteExcerpt(m))
        replyLabel?.text="引用："+quoteExcerpt(m);replyBar?.visibility=View.VISIBLE;composer?.requestFocus();stashDraft()
    }
    private fun jumpMessage(id:String) {
        val target=messageList?.findViewWithTag<View>(id)
        if(target==null){toast("原消息不在当前已加载的记录中，可使用聊天搜索查找");return}
        messageScroll?.post{messageScroll?.scrollTo(0,(target.top-dp(56)).coerceAtLeast(0));if(Motion.enabled())Motion.reveal(target,y=0f,duration=240)}
        target.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null)
    }
    private fun chatSearch(){peer?.let{launchSearch(it)}}
    private fun openSearchMessage(user:JSONObject,mid:String){
        val id=user.getString("id");if(peer?.optString("id")!=id)openChat(user)
        val owner=Hub.me.optString("id");val session=Hub.token;val generation=++chatGeneration;val stamp=LocalData.epoch(owner)
        val visible=messages.filter{!it.optBoolean("pending")}.map{JSONObject(it.toString())};searchWindow=true
        Hub.job({JSONObject().put("messages",LocalData.messages(owner,id,1000))},{result->
            if(generation!=chatGeneration||peer?.optString("id")!=id||Hub.token!=session||stamp!=LocalData.epoch(owner))return@job
            val cached=result.getJSONArray("messages");val all=(0 until cached.length()).map{cached.getJSONObject(it)}.associateBy{it.optString("id")}.toMutableMap()
            visible.forEach{all[it.getString("id")]=it};val ordered=all.values.sortedBy{it.optLong("created")};val at=ordered.indexOfFirst{it.optString("id")==mid}
            if(at<0){searchWindow=false;toast("这条记录已删除或不在本机缓存中");loadMessages(false);return@job}
            val from=(at-20).coerceAtLeast(0);messages.clear();messages.addAll(ordered.subList(from,(at+30).coerceAtMost(ordered.size)));hasMore=from>0;renderMessages(false);latestMessage?.visibility=View.VISIBLE
            messageList?.post{if(generation==chatGeneration)jumpMessage(mid)}
        },{if(generation==chatGeneration){searchWindow=false;toast(it)}})
    }
    private fun nearLatest():Boolean { val scroll=messageScroll?:return true;return (messageList?.height?:0)+scroll.paddingBottom-scroll.height-scroll.scrollY<dp(80) }
    private fun scrollLatest(smooth:Boolean) { val scroll=messageScroll?:return;val list=messageList?:return;list.doOnPreDraw { if(messageList===list){val y=(list.height+scroll.paddingBottom-scroll.height).coerceAtLeast(0);if(smooth&&Motion.enabled())scroll.smoothScrollTo(0,y)else scroll.scrollTo(0,y);syncHtmlPreviews()} } }
    private fun appendMessage(m: JSONObject) {
        if(m.optBoolean("removed")){messages.removeAll{it.optBoolean("pending")&&it.optString("client_id")==m.optString("client_id")};renderMessages(false);return}
        val own=m.optString("sender")==Hub.me.optString("id")
        if(m.optBoolean("pending")&&own&&messages.any{!it.optBoolean("pending")&&it.optString("sender")==Hub.me.optString("id")&&it.optString("client_id")==m.optString("client_id")})return
        val pendingIndex=messages.indexOfFirst{it.optBoolean("pending")&&own&&it.optString("client_id")==m.optString("client_id")}
        if(pendingIndex>=0){messages[pendingIndex]=m;messages.sortBy{it.optLong("created")};renderMessages(false);if(own)scrollLatest(true);return}
        if(messages.any { it.optString("id")==m.optString("id") })return
        if(searchWindow){
            if(own){searchWindow=false;messages.clear();loadMessages(false)}
            else {latestMessage?.visibility=View.VISIBLE;return}
        }
        val previous=messages.lastOrNull();val follow=!selecting&&textActionMode==null&&(nearLatest()||m.optString("sender")==Hub.me.optString("id"))
        val incremental=!callScreen&&messageList!=null&&previous!=null&&m.optLong("created")>=previous.optLong("created")
        messages.add(m);messages.sortBy { it.optLong("created") }
        if(callScreen)return
        if(incremental){val view=addMessageRow(messageList!!,m,previous!!.optLong("created"));if(follow)Motion.reveal(view,y=8f,duration=190)}else renderMessages(false)
        if(follow){latestMessage?.visibility=View.GONE;scrollLatest(true)}else latestMessage?.let { if(it.visibility!=View.VISIBLE){it.visibility=View.VISIBLE;Motion.reveal(it,y=5f,duration=180)} }
    }
    private fun emojis(){toggleInputPanel("emoji")}
    private fun permission(permissions: Array<String>, action: () -> Unit) {
        val missing=permissions.filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isEmpty())action() else { grantedAction=null;permissionAction=action;permissionSession=Hub.token;if(callScreen)updatePipParams();requestPermissions(missing.toTypedArray(),51) }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code,permissions,grants)
        if(code==51) {val action=permissionAction;permissionAction=null;val valid=permissionSession.isNotEmpty()&&permissionSession==Hub.token;permissionSession="";if(!valid)return;if(grants.isNotEmpty()&&grants.all{it==PackageManager.PERMISSION_GRANTED}){if(resumed&&Hub.foreground)action?.invoke()else if(action!=null)grantedAction=GrantedAction(Hub.token,SystemClock.elapsedRealtime()+1000,action)}else toast("需要相应权限才能使用录音或通话功能，可在系统设置中开启")}
    }
    private fun pickFile(kind: String) {
        if(!contactGuard())return
        pickerPeer=peer?.optString("id") ?: return;pickerKind=kind;pickerSession=Hub.token;pickerQuoteRef=replyTarget;pickerQuote=replyTarget?.let{JSONObject(it.toString())}
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType(if(kind=="image")"image/*" else "*/*").addCategory(Intent.CATEGORY_OPENABLE),61)
    }
    @Deprecated("Android result API")
    override fun onActivityResult(code: Int,result: Int,data: Intent?) {
        super.onActivityResult(code,result,data)
        if(code==82){
            if(result==RESULT_OK&&Hub.token==searchSession&&Hub.me.optString("id")==searchOwner&&data!=null){
                val id=data.getStringExtra("peer").orEmpty()
                if(id.matches(Regex("[0-9]{8}"))){
                    val user=(0 until contacts.length()).map{contacts.getJSONObject(it)}.find{it.optString("id")==id}?:runCatching{JSONObject(data.getStringExtra("user").orEmpty()).takeIf{it.optString("id")==id}}.getOrNull()
                    if(user!=null){val mid=data.getStringExtra("message").orEmpty();if(mid.isEmpty())openChat(user)else openSearchMessage(user,mid)}
                }
            };searchSession="";searchOwner="";return
        }
        if(code==89){val ticket=screenRequest;screenRequest=null;if(ticket!=null){screenApproval=Triple(ticket,result,data);finishScreenApproval()};return}
        if(code==61&&(pickerSession.isEmpty()||pickerSession!=Hub.token))return
        if(code in listOf(63,64)&&(avatarPickerSession.isEmpty()||avatarPickerSession!=Hub.token))return
        if(code==62&&(saveSession.isEmpty()||saveSession!=Hub.token)){saveFile=null;return}
        if(code==77&&data?.getStringExtra("action")=="search"){chatSearch();return}
        if(code==77){refresh();refreshRelationship();if(result==RESULT_OK&&peer!=null){chatGeneration++;messages.clear();renderMessages();loadMessages(false)};return}
        if(code==76){if(result==RESULT_OK){returningFromPost=true;tab=2;if(feed==null&&!callScreen)showHome();feed?.refreshFromTop()};return}
        val uri=data?.data ?: return
        if(result!=RESULT_OK)return
        if(code==63) {
            startActivityForResult(Intent(this,AvatarCropActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),64)
            Motion.activityOpen(this)
        } else if(code==64) {
            uploadAvatar(uri)
        } else if(code==61) {
            val quote=pickerQuote;val reference=pickerQuoteRef;val session=pickerSession;val target=pickerPeer;pickerQuote=null;pickerQuoteRef=null
            var name="文件"; var bytes=-1L
            contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c -> if(c.moveToFirst()) { name=c.getString(0)?:name; if(!c.isNull(1))bytes=c.getLong(1) } }
            if(bytes>25*1024*1024) { toast("单个文件不能超过 25 MB"); return }
            val mime=contentResolver.getType(uri)?:"application/octet-stream"
            uploadFile(uri,name,mime,pickerKind,target,"",quote=quote,onResult={message,_->if(message!=null)consumeReply(reference,target,session)})
        } else if(code==62) {
            val file=saveFile ?: return; saveFile=null
            Hub.io.execute { try { contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: throw Exception("无法写入文件"); Hub.main.post { toast("文件已保存") } } catch(e:Exception) { Hub.main.post { toast(e.message?:"保存失败") } } }
        }
    }
    private val activeUploads=mutableSetOf<AttachmentUpload>()
    private fun uploadFile(uri: Uri,name: String,mime: String,kind: String,recipient: String,caption: String,quote:JSONObject?=null,onDone: (() -> Unit)?=null,onResult:((JSONObject?,String?)->Unit)?=null) {
        val session=Hub.token
        lateinit var task:AttachmentUpload
        task=AttachmentUpload(this,uri,name,mime,kind,recipient,caption,messageQuote=quote?.let{JSONObject(it.toString())}){message,error ->
            activeUploads.remove(task);onDone?.invoke();onResult?.invoke(message,error)
            if(Hub.token==session&&!isDestroyed&&!isFinishing) {
                if(message!=null){if(peer?.optString("id")==recipient)appendMessage(message)else toast("附件已加入待发消息")}
                else if(error!=null)toast(error)
            }
        }
        activeUploads.add(task);trackDialog(task.dialog);task.start()
    }
    private fun startRecording() {
        if(!contactGuard()||Hub.call!=null||recording!=null||!resumed||!Hub.foreground||isDestroyed||isFinishing)return
        clearReadyVoice();hideKeyboard();stopPlayer();inputPanel?.hide()
        val staged=cacheDir.listFiles()?.filter{it.isFile&&it.name.matches(Regex("voice-[0-9a-f-]{36}\\.m4a"))}?.sumOf{it.length()}?:0L
        if(staged>=8L*1024*1024){toast("录音暂存较多，请等待发送完成");return}
        try{
            val id=UUID.randomUUID().toString();val file=File(cacheDir,"voice-$id.m4a")
            recordOwner=Hub.me.optString("id");recordSession=Hub.token;recordPeer=peer?.optString("id").orEmpty();recordFile=file;recordQuoteRef=replyTarget;recordQuote=replyTarget?.let{JSONObject(it.toString())}
            val recorder=if(Build.VERSION.SDK_INT>=31)MediaRecorder(this)else MediaRecorder();recording=recorder
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(64000);recorder.setAudioSamplingRate(44100);recorder.setMaxDuration(60000);recorder.setMaxFileSize(2L*1024*1024);recorder.setOutputFile(file.absolutePath)
            recorder.setOnInfoListener{source,what,_->if(recording===source&&what in listOf(MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED,MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED))stopRecording(false,true)}
            recorder.setOnErrorListener{source,_,_->if(recording===source){stopRecording(false);toast("录音中断，请重试")}}
            recorder.prepare();recorder.start();recordStart=SystemClock.elapsedRealtime();renderRecordingUi()
        }catch(_:Exception){runCatching{recording?.release()};recording=null;recordFile?.delete();recordFile=null;renderRecordingUi();toast("无法开始录音，请检查麦克风权限")}
    }
    private fun stopRecording(send:Boolean,preview:Boolean=false){
        val recorder=recording?:return;val file=recordFile;recording=null;recordFile=null
        val stopped=runCatching{recorder.stop()}.isSuccess;runCatching{recorder.release()}
        val owner=recordOwner;val session=recordSession;val target=recordPeer;val quote=recordQuote;val reference=recordQuoteRef
        recordOwner="";recordSession="";recordPeer="";recordQuote=null;recordQuoteRef=null
        if(file!=null&&(send||preview)&&Hub.token==session&&Hub.me.optString("id")==owner&&peer?.optString("id")==target&&!isDestroyed&&!callScreen){
            val measured=runCatching{val metadata=MediaMetadataRetriever();try{metadata.setDataSource(file.absolutePath);metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()}finally{metadata.release()}}.getOrNull()
            val duration=measured?:((SystemClock.elapsedRealtime()-recordStart).coerceIn(0,65000)).toInt()
            if((stopped||measured!=null)&&duration in 1000..65000&&file.length() in 1..2L*1024*1024){readyVoice=ReadyVoice(UUID.randomUUID().toString(),file,owner,session,target,duration,quote,reference);voiceDraftError="";if(send)sendReadyVoice()}
            else{file.delete();toast("录音未完成，请至少录制一秒")}
        }else file?.delete()
        renderRecordingUi()
    }
    private fun readyMatches(clip:ReadyVoice)=Hub.token==clip.session&&Hub.me.optString("id")==clip.owner&&peer?.optString("id")==clip.peer&&!isDestroyed&&!isFinishing
    private fun clearReadyVoice(){
        val old=readyVoice;readyVoice=null;voiceDraftError=""
        if(old!=null){voicePlayback?.forget("draft|"+old.id);old.file.delete()};renderRecordingUi()
    }
    private fun renderRecordingUi(){
        val clip=readyVoice?.takeIf{readyMatches(it)}
        if(recording!=null||clip!=null)inputPanel?.hide()
        inputRowView?.visibility=if(recording!=null||clip!=null)View.GONE else View.VISIBLE
        recorderButton?.isEnabled=recording==null&&clip==null
        recordBar?.visibility=if(recording!=null)View.VISIBLE else View.GONE
        voiceDraftRow?.visibility=if(clip!=null)View.VISIBLE else View.GONE
        if(clip!=null){
            val state=voicePlayback?.state("draft|"+clip.id);val playing=state?.phase=="playing";val glyph=if(playing)"pause"else"play"
            voiceDraftPlay?.let{v->if(voiceDraftIcon!=glyph||v.childCount==0){voiceDraftIcon=glyph;v.removeAllViews();v.addView(Glyph(this,glyph,accent),FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER))};v.contentDescription=if(playing)"暂停试听"else"试听录音"}
            val value=if(voiceDraftError.isNotEmpty())voiceDraftError else if(playing)"试听 "+VoiceMessageView.clock(state?.position?:0)+" / "+VoiceMessageView.clock(clip.duration)else VoiceMessageView.clock(clip.duration)+" · 待发送"
            if(voiceDraftCaption?.text?.toString()!=value)voiceDraftCaption?.text=value
        }
    }
    private fun previewReadyVoice(){
        val clip=readyVoice?:return;if(!readyMatches(clip)||Hub.call!=null||recording!=null)return
        voiceController().toggle("draft|"+clip.id,{readyVoice===clip&&readyMatches(clip)&&resumed&&Hub.foreground&&!callScreen&&Hub.call==null}){_,cancelled->check(!cancelled()&&clip.file.isFile);clip.file}
    }
    private fun sendReadyVoice(){
        val clip=readyVoice?:return;if(!readyMatches(clip)||Hub.call!=null)return
        val generation=chatGeneration;val editor=composer;val originalText=editor?.text?.toString()
        voicePlayback?.forget("draft|"+clip.id);readyVoice=null;voiceDraftError="";renderRecordingUi()
        uploadFile(Uri.fromFile(clip.file),"语音消息.m4a","audio/mp4","voice",clip.peer,"${(clip.duration+999)/1000}″",quote=clip.quote,onResult={message,error->
            if(message!=null){clip.file.delete();consumeReply(clip.quoteRef,clip.peer,clip.session)}
            else if(readyMatches(clip)&&generation==chatGeneration&&composer===editor&&composer?.text?.toString()==originalText&&readyVoice==null&&recording==null&&resumed){readyVoice=clip;voiceDraftError="发送失败，录音已保留";renderRecordingUi()}
            else clip.file.delete()
        })
    }
    private fun voiceKey(m:JSONObject)=m.optString("id")+"|"+(m.optJSONObject("file")?.optString("id").orEmpty())
    private fun voiceController():VoicePlayback{
        voicePlayback?.let{return it}
        return VoicePlayback(this){key->
            val controller=voicePlayback
            player=controller?.media;playingId=controller?.activeKey?.substringBefore('|').orEmpty()
            voiceViews[key]?.render(controller?.state(key)?:VoicePlayback.State(key));if(key.startsWith("draft|"))renderRecordingUi()
        }.also{voicePlayback=it}
    }
    private fun stopPlayer(){voicePlayback?.stop();player=null;playingId=""}
    private fun playVoice(m:JSONObject){
        if(Hub.call!=null||recording!=null){toast("请先结束通话或录音");return}
        val file=m.optJSONObject("file")?:return;val key=voiceKey(m);val id=m.optString("id");val fid=file.optString("id")
        val session=Hub.token;val owner=Hub.me.optString("id");val target=peer?.optString("id");val generation=chatGeneration
        finishTextSelection();messageMenu?.dismiss()
        val valid={Hub.token==session&&Hub.me.optString("id")==owner&&peer?.optString("id")==target&&generation==chatGeneration&&resumed&&Hub.foreground&&!isDestroyed&&!callScreen&&Hub.call==null&&recording==null&&messages.any{it.optString("id")==id&&!it.optBoolean("withdrawn")&&!it.optBoolean("decrypt_failed")&&it.optBoolean("attachment_available",true)&&it.optJSONObject("file")?.optString("id")==fid}}
        voiceController().toggle(key,valid){tag,cancelled->Hub.cachedFile(file,tag,cancelled)}
    }
    private fun loadImage(f: JSONObject,view: ImageView) {
        val session=Hub.token;val active=java.util.concurrent.atomic.AtomicBoolean(true)
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){}
            override fun onViewDetachedFromWindow(v:View){active.set(false);cancelImage(view)}
        })
        Hub.io.execute{try{
            val file=Hub.cachedFile(f,view){!active.get()||Hub.token!=session};val bitmap=try{decodeChatImage(file)}catch(e:Exception){file.delete();throw e}
            Hub.main.post{if(active.get()&&Hub.token==session&&!isDestroyed&&view.isAttachedToWindow){view.setImageBitmap(bitmap);view.contentDescription="图片预览"}else bitmap.recycle()}
        }catch(_:Exception){Hub.main.post{if(Hub.token==session&&active.get()){view.contentDescription="图片加载失败，点按重试";view.setOnClickListener{loadImage(f,view)}}}}}
    }
    private fun viewImage(f: JSONObject) {
        val image=ImageView(this).apply { adjustViewBounds=true; scaleType=ImageView.ScaleType.FIT_CENTER; minimumHeight=dp(220) }; loadImage(f,image)
        AlertDialog.Builder(this).setView(image).setPositiveButton("保存图片") { _,_ -> saveAttachment(f) }.setNegativeButton("关闭",null).showThemed()
    }
    private fun openAttachment(f: JSONObject) {
        showActions(f.optString("name"),size(f.optLong("size")),actions=listOf(
            ActionSheets.Action("file","用其他应用打开") { openWith(f) },
            ActionSheets.Action("download","保存到手机") { saveAttachment(f) }
        ))
    }
    private fun openWith(f: JSONObject) {
        fetchAttachment(f) { file ->
            try { val uri=FileProvider.getUriForFile(this,"cn.qingliao.app.files",file); startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri,f.optString("mime","application/octet-stream")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"打开文件")) }
            catch(_:ActivityNotFoundException) { toast("没有可以打开此文件的应用，请选择保存到手机") }
        }
    }
    private fun fetchAttachment(f: JSONObject,done: (File)->Unit) {
        val dialog=ProgressDialog.show(this,"正在下载",f.optString("name"),true,false).also{trackDialog(it)}
        val session=Hub.token
        Hub.io.execute{try{val file=Hub.cachedFile(f);Hub.main.post{dialog.dismiss();if(Hub.token==session&&!isDestroyed){if(Hub.foreground&&!callScreen)done(file)else toast("附件已下载，可回到聊天后再次打开")}}}
        catch(e:Exception){Hub.main.post{dialog.dismiss();if(Hub.token==session&&!isDestroyed)toast(e.message?:"下载失败")}}}
    }
    private fun saveAttachment(f: JSONObject) { fetchAttachment(f) { file -> saveFile=file;saveSession=Hub.token;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(f.optString("mime","application/octet-stream")).putExtra(Intent.EXTRA_TITLE,f.optString("name","文件")),62) } }

    private fun beginCall(video: Boolean) {
        if(!contactGuard())return
        if(peer?.optBoolean("bot")==true && peer?.optBoolean("bot_calls_enabled")!=true){toast("开发者暂未启用机器人通话");return}
        if(!Hub.connected) { toast("正在连接服务器，请稍后再试"); return }
        if(Hub.call!=null) { toast("你正在通话中");showCall(); return }
        if(recording!=null)stopRecording(false,true)
        val u=peer ?: return
        permission(if(video)arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)) {
            hideKeyboard(); stopPlayer(); Hub.service?.update(true,video)
            Hub.signal("call.invite",JSONObject().put("peer",u.getString("id")).put("video",video))
        }
    }
    private fun acceptCall() {
        val c=Hub.call ?: return
        val callId=c.optString("id");val session=Hub.token
        permission(if(c.optBoolean("video"))arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)) {
            if(Hub.token==session&&Hub.call?.optString("id")==callId&&Hub.phase=="incoming"&&Hub.ringUntil>SystemClock.elapsedRealtime()) {
                Hub.service?.stopRing();Hub.service?.update(true,c.optBoolean("video"));Hub.signal("call.accept")
            }
        }
    }
    private fun busyFeedback(message:String,remote:String,video:Boolean,selfBusy:Boolean=false) {
        if(isFinishing||isDestroyed)return
        val session=Hub.token
        val dialog=AlertDialog.Builder(this).setTitle(if(selfBusy)"正在通话" else "对方忙线").setMessage(message).setNegativeButton("知道了",null)
        if(!selfBusy&&Hub.call==null&&remote.isNotEmpty()&&peer?.optString("id")==remote)dialog.setPositiveButton("重新拨打") { _,_ ->
            if(Hub.token==session&&Hub.call==null&&peer?.optString("id")==remote)beginCall(video)
        }
        dialog.showThemed()
    }
    private fun callClockText():String = if(Hub.rtc?.audioPaused==true)"通话音频已暂停" else if(Hub.phase=="connected")duration((SystemClock.elapsedRealtime()-Hub.connectedAt)/1000) else phaseText()
    private fun phaseText() = when(Hub.phase) { "incoming" -> if(Hub.call?.optBoolean("video")==true)"邀请你视频通话" else "邀请你语音通话"; "outgoing" -> (if(compact)"等待接听 · " else "等待对方接听 · ")+"${((Hub.ringUntil-SystemClock.elapsedRealtime()).coerceAtLeast(0)+999)/1000} 秒"; "connecting" -> "正在建立连接…"; "reconnecting" -> "正在恢复通话 · ${((Hub.reconnectUntil-SystemClock.elapsedRealtime()).coerceAtLeast(0)+999)/1000} 秒"; else -> "" }
    private fun showCall() {
        val c=Hub.call?:return
        if(c.optBoolean("video")&&Hub.rtc!=null&&Hub.phase!="incoming"){showVideoCall();return}
        showCallClassic();updateCallChrome();updatePipParams()
    }
    private fun showCallClassic() {
        cancelVoiceText()
        val c=Hub.call ?: return
        if(recording!=null)stopRecording(false,true)
        hideKeyboard(); stopPlayer(); callScreen=true; reset(palette.callBottom)
        root.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(palette.callTop,palette.callBottom))
        window.decorView.systemUiVisibility=0; window.statusBarColor=palette.callBottom; window.navigationBarColor=palette.callBottom
        val u=Hub.callUser; val compact=this.compact||resources.configuration.screenHeightDp<520;val minimalIdentity=compact&&resources.configuration.screenHeightDp<430
        val top=row().apply { setPadding(dp(if(tiny)8 else 14),dp(if(tiny)4 else 14),dp(if(tiny)8 else 20),dp(if(tiny)4 else 8)) }; top.addView(text((if(compact)""else "弥星  /  ")+if(c.optBoolean("video"))"视频通话" else "语音通话",13f,palette.callMuted),LinearLayout.LayoutParams(0,-2,1f))
        shareControl=null;cameraControl=null
        if(c.optBoolean("video")&&Hub.phase!="incoming"){
            shareControl=text(if(Hub.rtc?.sharingScreen==true)"停止共享"else "共享屏幕",12f,Color.WHITE,true).apply{gravity=Gravity.CENTER;minimumHeight=dp(44);setPadding(dp(10),0,dp(10),0);background=bg(palette.callSurface,14);contentDescription=text;setOnClickListener{toggleScreenSharing()}}
            top.addView(shareControl,lp(-2,44))
        }
        root.addView(top)
        classicTop=top
        val stage=FrameLayout(this); root.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        callViewport=stage
        val identity=col().apply { gravity=Gravity.CENTER; pad(this,if(tiny)2 else if(compact)8 else 20) }
        val portraitSize=if(tiny)32 else if(compact)48 else 98;val haloSize=if(tiny)44 else if(compact)64 else 130
        val halo=FrameLayout(this).apply {
            background=bg(palette.callSurface,haloSize/2,Color.argb(65,Color.red(palette.primary),Color.green(palette.primary),Color.blue(palette.primary)))
            addView(avatar(u,portraitSize),FrameLayout.LayoutParams(dp(portraitSize),dp(portraitSize),Gravity.CENTER))
            addView(Glyph(this@MainActivity,"star",Color.rgb(255,225,182)),FrameLayout.LayoutParams(dp(12),dp(12),Gravity.TOP or Gravity.END).apply{topMargin=dp(10);marginEnd=dp(8)})
        }
        if(!minimalIdentity){identity.addView(halo,lp(haloSize,haloSize));space(identity,if(compact)8 else 24)}; identity.addView(text(displayName(u),if(tiny)17f else if(compact)20f else 27f,Color.WHITE,true).apply {contentDescription="通话对象";maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }); space(identity,if(minimalIdentity)2 else if(compact)8 else 16)
        callClock=text(callClockText(),if(tiny)12f else 14f,palette.callMuted).apply{contentDescription="通话状态";maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}; identity.addView(callClock)
        if(u.optBoolean("bot")&&!minimalIdentity)identity.addView(text("第三方机器人 · 开发者可接收处理通话内容",12f,palette.callMuted).apply{gravity=Gravity.CENTER;setPadding(0,dp(if(tiny)3 else 12),0,0);if(tiny){textSize=10f;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}})
        stage.addView(identity,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        classicIdentity=identity
        pipCard=col().apply{gravity=Gravity.CENTER;setPadding(dp(8),0,dp(8),0);visibility=View.GONE;tag="call.pip.audio";addView(text(displayName(u),16f,Color.WHITE,true).apply{gravity=Gravity.CENTER;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END});pipClock=text(callClockText(),13f,palette.callMuted);addView(pipClock)}
        stage.addView(pipCard,FrameLayout.LayoutParams(-1,-1))
        val controls=col().apply { setPadding(dp(if(compact)6 else 18),dp(if(tiny)2 else if(compact)8 else 18),dp(if(compact)6 else 18),dp(if(tiny)2 else if(compact)8 else 28)); gravity=Gravity.CENTER }
        if(Hub.phase=="incoming") {
            if(!tiny){controls.addView(text((if(compact)"接听使用" else "接听后将使用")+if(c.optBoolean("video"))"摄像头和麦克风" else "麦克风",12f,palette.callMuted)); space(controls,if(compact)12 else 25)}
            val actions=row().apply { gravity=Gravity.CENTER }
            actions.addView(callControl("hangup","拒绝",Color.rgb(207,86,76)) { Hub.rejectIncoming() },LinearLayout.LayoutParams(0,-2,1f))
            actions.addView(callControl(if(c.optBoolean("video"))"video" else "phone","接听",palette.primary) { acceptCall() },LinearLayout.LayoutParams(0,-2,1f)); controls.addView(actions)
        } else {
            val actions=row().apply { gravity=Gravity.CENTER }
            micControl=callControl(if(Hub.rtc?.muted==true)"micOff" else "mic",if(Hub.rtc?.muted==true)"已静音" else "麦克风",if(Hub.rtc?.muted==true)palette.primary else palette.callSurface){Hub.rtc?.toggleMute();updateCallControls()};actions.addView(micControl,LinearLayout.LayoutParams(0,-2,1f))
            speakerControl=callControl("speaker",if(Hub.rtc?.speaker==true){if(compact)"外放开" else "扬声器开"} else {if(compact)"外放关" else "扬声器关"},if(Hub.rtc?.speaker==true)palette.primary else palette.callSurface){Hub.rtc?.toggleSpeaker();updateCallControls()};actions.addView(speakerControl,LinearLayout.LayoutParams(0,-2,1f))
            if(c.optBoolean("video")){
                cameraControl=callControl("video",if(Hub.rtc?.sharingScreen==true)"停止共享"else if(Hub.rtc?.cameraEnabled==true)"镜头设置"else "开启镜头"){
                    val engine=Hub.rtc?:return@callControl
                    if(engine.sharingScreen)ScreenSharing.stop(engine)
                    else if(!engine.cameraEnabled)permission(arrayOf(Manifest.permission.CAMERA)){if(Hub.rtc===engine)engine.toggleCamera()}
                    else showActions("镜头设置",actions=listOf(ActionSheets.Action("flip","翻转镜头"){if(Hub.rtc===engine)engine.flip()},ActionSheets.Action("video","关闭摄像头"){if(Hub.rtc===engine)engine.toggleCamera()}))
                };actions.addView(cameraControl,LinearLayout.LayoutParams(0,-2,1f))
            }
            if(compact)actions.addView(callControl("hangup",if(Hub.phase=="outgoing")"取消" else "挂断",Color.rgb(207,86,76)){Hub.hangup()},LinearLayout.LayoutParams(0,-2,1f))
            controls.addView(actions);if(!compact){space(controls,26);controls.addView(callControl("hangup",if(Hub.phase=="outgoing")"取消呼叫" else "挂断",Color.rgb(207,86,76)){Hub.hangup()})}
        }
        root.addView(controls)
        classicControls=controls
        updateCallControls()
    }
    private fun showVideoCall(){
        val c=Hub.call?:return;val engine=Hub.rtc?:return
        hideKeyboard()
        val session=Hub.token;val owner=Hub.me.optString("id");val id=c.optString("id");val key="$session|$owner|$id"
        if(callPreferenceKey!=key){callPreferenceKey=key;callVideoState=CallVideoState();assistFeedbackRevision=0}
        if(callScreen&&callViewKey==key&&callViewEngine===engine&&callVideoStage!=null){callVideoStage?.refresh();updateCallControls();updateCallChrome();updatePipParams();return}
        cancelVoiceText();hideKeyboard();stopPlayer();callScreen=true;reset(palette.callBottom,true)
        val valid={Hub.token==session&&Hub.me.optString("id")==owner&&Hub.call?.optString("id")==id&&Hub.rtc===engine&&!isDestroyed&&!isFinishing}
        callViewKey=key;callViewEngine=engine
        val viewport=FrameLayout(this).apply{tag="call.viewport"};callViewport=viewport;root.addView(viewport,LinearLayout.LayoutParams(-1,0,1f))
        val stage=CallVideoStage(this,engine,callVideoState,valid,{if(Hub.phase=="connected"){callVideoState.controlsVisible=!callVideoState.controlsVisible;updateCallChrome()}},{updateCallChrome();updatePipParams()})
        callVideoStage=stage;viewport.addView(stage,FrameLayout.LayoutParams(-1,-1))
        val small=compact||resources.configuration.screenHeightDp<500
        val top=row().apply{tag="call.top";background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xB0101020.toInt(),Color.TRANSPARENT))};callTopOverlay=top
        top.addView(text(displayName(Hub.callUser),if(small)12f else 14f,Color.WHITE,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f))
        callScaleControl=text("完整",11f,Color.WHITE).apply{tag="call.scale";gravity=Gravity.CENTER;minimumWidth=dp(40);minimumHeight=dp(40);setOnClickListener{if(valid())stage.toggleScale()}};top.addView(callScaleControl,lp(40,40))
        shareControl=text("共享屏幕",if(small)11f else 12f,Color.WHITE,true).apply{tag="call.share";gravity=Gravity.CENTER;setPadding(dp(8),0,dp(8),0);minimumHeight=dp(40);background=bg(palette.callSurface,12);setOnClickListener{if(valid())toggleScreenSharing()}};top.addView(shareControl,lp(-2,40))
        top.addView(icon("minimize","悬浮通话",Color.WHITE){if(valid())minimizeCall(true)}.apply{tag="call.minimize"},lp(40,40))
        viewport.addView(top,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        assistBar=row().apply{tag="call.assist.bar";background=bg(0xCC252238.toInt(),12);visibility=View.GONE}
        assistControl=text("远程协助",if(small)11f else 12f,Color.WHITE,true).apply{tag="call.assist";gravity=Gravity.CENTER;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;minimumHeight=dp(40);setPadding(dp(8),0,dp(8),0);setOnClickListener{if(valid()){val a=engine.assist;if(a.hosting)a.stopHosting()else if(a.controlling||a.pending)a.stopControlling()else assistanceMenu()}}};assistBar!!.addView(assistControl,LinearLayout.LayoutParams(0,-2,1f))
        assistBar!!.addView(icon("back","对方返回",Color.WHITE){if(valid())engine.assist.sendInput(AssistWire.Input("back"))}.apply{tag="call.assist.back"},lp(36,40))
        assistBar!!.addView(icon("home","对方桌面",Color.WHITE){if(valid())engine.assist.sendInput(AssistWire.Input("home"))}.apply{tag="call.assist.home"},lp(36,40))
        viewport.addView(assistBar,FrameLayout.LayoutParams(dp(if(small)164 else 230),-2,Gravity.TOP or Gravity.START))
        val bottom=col().apply{tag="call.controls";gravity=Gravity.CENTER;setOnClickListener{if(valid()&&Hub.phase=="connected"){callVideoState.controlsVisible=!callVideoState.controlsVisible;updateCallChrome()}};background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0xD0101020.toInt()))};callBottomOverlay=bottom
        callCaption=text("",if(small)11f else 13f,Color.WHITE).apply{gravity=Gravity.CENTER;maxLines=2};bottom.addView(callCaption)
        callClock=text(callClockText(),if(small)11f else 13f,palette.callMuted).apply{gravity=Gravity.CENTER;contentDescription="通话状态"};bottom.addView(callClock);space(bottom,if(small)6 else 14)
        val actions=row().apply{gravity=Gravity.CENTER}
        micControl=callControl("mic","麦克风"){if(valid()){engine.toggleMute();updateCallControls();updatePipParams()}};actions.addView(micControl,LinearLayout.LayoutParams(0,-2,1f))
        speakerControl=callControl("speaker","扬声器"){if(valid()){engine.toggleSpeaker();updateCallControls()}};actions.addView(speakerControl,LinearLayout.LayoutParams(0,-2,1f))
        cameraControl=callControl("video","镜头设置"){
            if(valid()){
                if(engine.sharingScreen)ScreenSharing.stop(engine)
                else if(!engine.cameraEnabled)permission(arrayOf(Manifest.permission.CAMERA)){if(valid())engine.toggleCamera()}
                else showActions("镜头设置",actions=listOf(ActionSheets.Action("flip","翻转镜头"){if(valid())engine.flip()},ActionSheets.Action("video","关闭摄像头"){if(valid())engine.toggleCamera()}))
            }
        };actions.addView(cameraControl,LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(callControl("hangup","挂断",Color.rgb(207,86,76)){if(valid())Hub.hangup()}.apply{tag="call.hangup"},LinearLayout.LayoutParams(0,-2,1f));bottom.addView(actions)
        viewport.addView(bottom,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        pipClock=text(callClockText(),12f,Color.WHITE).apply{tag="call.pip.status";gravity=Gravity.CENTER;maxLines=1;visibility=View.GONE;setBackgroundColor(0x88000000.toInt());setPadding(0,dp(4),0,dp(4))};viewport.addView(pipClock,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        applyCallInsets();updateCallControls();updateCallChrome();updatePipParams();root.requestApplyInsets()
    }
    private fun applyCallInsets(){
        val b=callSafeInsets
        callTopOverlay?.setPadding(b.left+dp(12),b.top+dp(6),b.right+dp(8),dp(12))
        callBottomOverlay?.setPadding(b.left+dp(8),dp(24),b.right+dp(8),b.bottom+dp(if(compact)8 else 18))
        callVideoStage?.safeInsets(b.left,b.top,b.right,b.bottom)
        (assistBar?.layoutParams as? FrameLayout.LayoutParams)?.let{p->p.marginStart=b.left+dp(8);p.topMargin=b.top+dp(if(compact)52 else 68);assistBar?.layoutParams=p}
    }
    private fun updateCallChrome(){
        val pip=isInPictureInPictureMode||pipTransition
        val assist=Hub.rtc?.assist
        val show=!pip&&(Hub.phase!="connected"||callVideoState.controlsVisible||assist?.controlling==true)
        callTopOverlay?.visibility=if(show)View.VISIBLE else View.GONE;callBottomOverlay?.visibility=if(show)View.VISIBLE else View.GONE
        callVideoStage?.let{stage->
            stage.presentation(pip);stage.refresh()
            callScaleControl?.apply{text=if(assist?.controlling==true||stage.filled)"完整"else"铺满";contentDescription=if(assist?.controlling==true)"控制使用完整画面"else if(stage.filled)"完整显示画面"else"铺满屏幕";isEnabled=assist?.controlling!=true&&(if(stage.localIsMain)Hub.rtc?.cameraEnabled==true else Hub.rtc?.remoteVideoMode!="off");alpha=if(isEnabled)1f else .45f}
            callCaption?.text=(if(stage.localIsMain)"自己"else if(Hub.rtc?.remoteVideoMode=="screen")"对方正在共享屏幕"else displayName(Hub.callUser))+(if(Hub.callUser.optBoolean("bot"))" · 第三方机器人"else"")
        }
        classicTop?.visibility=if(pip)View.GONE else View.VISIBLE;classicControls?.visibility=if(pip)View.GONE else View.VISIBLE;classicIdentity?.visibility=if(pip)View.GONE else View.VISIBLE;pipCard?.visibility=if(pip)View.VISIBLE else View.GONE
        pipClock?.visibility=if(pip)View.VISIBLE else View.GONE
        assistBar?.visibility=if(!pip&&!Hub.callUser.optBoolean("bot")&&(Hub.rtc?.sharingScreen==true||Hub.rtc?.remoteVideoMode=="screen"||assist?.hosting==true||assist?.controlling==true))View.VISIBLE else View.GONE
        assistControl?.apply{
            val value=when{assist?.hosting==true->"停止协助";assist?.controlling==true->"结束控制";assist?.incomingRequest?.isNotEmpty()==true->"对方申请控制";assist?.pending==true->"等待·取消";else->"远程协助"}
            if(text.toString()!=value)text=value;contentDescription=if(assist?.controlling==true)"正在控制对方，点击结束控制"else if(assist?.pending==true)"等待对方允许，点击取消申请"else value
        }
        assistBar?.let{bar->val selected=assist?.controlling==true||assist?.hosting==true;bar.isSelected=selected;bar.background=bg(if(selected)palette.primary else 0xCC252238.toInt(),12);val foreground=if(selected)palette.onPrimary else Color.WHITE;assistControl?.setTextColor(foreground);for(n in 1 until bar.childCount){val button=bar.getChildAt(n);button.visibility=if(assist?.controlling==true)View.VISIBLE else View.GONE;((button as? FrameLayout)?.getChildAt(0) as? Glyph)?.let{it.color=foreground;it.invalidate()}}}
        if(videoEdge){
            window.statusBarColor=Color.TRANSPARENT;window.navigationBarColor=Color.TRANSPARENT
            if(!pip){val bars=WindowCompat.getInsetsController(window,window.decorView);bars.isAppearanceLightStatusBars=false;bars.isAppearanceLightNavigationBars=false;bars.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;if(show)bars.show(WindowInsetsCompat.Type.systemBars())else bars.hide(WindowInsetsCompat.Type.systemBars())}
        }
    }
    private fun pipEligible()=callScreen&&Hub.token.isNotEmpty()&&Hub.call!=null&&Hub.phase in listOf("outgoing","connecting","connected","reconnecting")&&screenRequest==null&&screenApproval==null&&permissionAction==null&&grantedAction==null&&!assistSettingsOpen&&!isFinishing&&!isDestroyed
    private fun pipParams():PictureInPictureParams {
        val params=PictureInPictureParams.Builder()
        val video=Hub.call?.optBoolean("video")==true
        params.setAspectRatio(if(!video)android.util.Rational(1,1)else if(resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE)android.util.Rational(4,3)else android.util.Rational(3,4))
        val rect=Rect();if(callViewport?.getGlobalVisibleRect(rect)==true&&!rect.isEmpty)params.setSourceRectHint(rect)
        if(Hub.call!=null&&Hub.token.isNotEmpty()){
            val muted=Hub.rtc?.muted==true
            params.setActions(listOf(RemoteAction(android.graphics.drawable.Icon.createWithResource(this,R.drawable.ic_call_mic),if(muted)"取消静音"else"麦克风静音",if(muted)"取消静音"else"麦克风静音",CallActions.pending("mute")),RemoteAction(android.graphics.drawable.Icon.createWithResource(this,R.drawable.ic_call_end),"挂断","挂断通话",CallActions.pending("hangup"))))
        }else params.setActions(emptyList())
        if(Build.VERSION.SDK_INT>=31){params.setAutoEnterEnabled(pipEligible());params.setSeamlessResizeEnabled(video)}
        return params.build()
    }
    private fun updatePipParams(){if(packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))runCatching{setPictureInPictureParams(pipParams())}}
    private fun minimizeCall(explicit:Boolean){
        if(!pipEligible()||isInPictureInPictureMode)return
        val supported=packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        if(supported){pipTransition=true;updateCallChrome()}
        val entered=supported&&runCatching{enterPictureInPictureMode(pipParams())}.getOrDefault(false)
        if(!entered){pipTransition=false;updateCallChrome()}
        if(!entered&&explicit){toast("系统未允许通话小窗，可从通知栏返回通话");Hub.service?.update();moveTaskToBack(true)}
    }
    private fun toggleScreenSharing(){
        startScreenSharing(false)
    }
    private fun startScreenSharing(wholeDisplay:Boolean){
        if(!resumed||isDestroyed||isFinishing)return
        val engine=Hub.rtc?:return
        if(engine.sharingScreen){ScreenSharing.stop(engine);return}
        if(screenRequest!=null)return
        val ticket=ScreenSharing.request(wholeDisplay)?:run{toast("请在视频通话接通后共享屏幕");return}
        screenRequest=ticket
        updatePipParams()
        try{val manager=getSystemService(MediaProjectionManager::class.java);val capture=if(wholeDisplay&&Build.VERSION.SDK_INT>=34)manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())else manager.createScreenCaptureIntent();startActivityForResult(capture,89)}
        catch(_:Exception){screenRequest=null;ScreenSharing.cancelRequest(ticket);updatePipParams();toast("这台设备暂不支持屏幕共享")}
    }
    private fun assistanceMenu(){
        val engine=Hub.rtc?:return;val a=engine.assist
        if(Hub.callUser.optBoolean("bot")){toast("机器人通话不支持远程控制");return}
        if(Hub.phase!="connected"){toast("请在通话接通后使用协助");return}
        if(!a.peerSupported){toast("对方版本或当前连接不支持远程协助");return}
        val token=Hub.token;val owner=Hub.me.optString("id");val call=Hub.call?.optString("id")
        val valid={Hub.token==token&&Hub.me.optString("id")==owner&&Hub.call?.optString("id")==call&&Hub.rtc===engine&&!isDestroyed&&!isFinishing}
        if(!engine.sharingScreen){
            if(!a.peerSupported){toast("对方版本或当前连接不支持远程协助");return}
            if(a.pending){toast("已申请，等待对方允许");return}
            if(!a.requestControl())toast("请让对方先共享整屏，并保持通话连接")
            return
        }
        val ready=AssistAccessibilityService.available();val full=ScreenSharing.controlShareId(engine)!=null
        val request=a.incomingRequest
        val safeName=displayName(Hub.callUser).filter{!it.isISOControl()&&it !in '\u202a'..'\u202e'&&it !in '\u2066'..'\u2069'}.take(32)
        val description="$safeName（${Hub.callUser.optString("id")}）可点按、长按、滑动，以及返回或桌面。权限最长10分钟，仅用于本次共享；你仍能触摸并随时停止。允许后请由你切换到需要协助的应用。弥星自身、系统授权等界面不接受远程操作。"+(if(!ready)"\n\n首次需要在系统无障碍中开启“弥星远程协助”，返回后再次确认本次允许。开启服务本身不会允许任何人控制。"else"")
        val dialog=AlertDialog.Builder(this).setTitle(if(request.isNotEmpty())"允许对方协助？"else"远程协助")
            .setMessage((if(request.isEmpty())"请让对方先点击“远程协助”申请控制。\n\n"else"")+description)
            .setNegativeButton("关闭",null)
        if(request.isNotEmpty())dialog.setNeutralButton("拒绝"){_,_->if(valid())a.deny()}
        when {
            !ready->dialog.setPositiveButton("开启无障碍"){_,_->if(valid()){
                assistSettingsOpen=true;updatePipParams()
                val settings=Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                try{startActivity(settings)}
                catch(_:Exception){assistSettingsOpen=false;updatePipParams();toast("请在系统无障碍设置中开启弥星远程协助")}
            }}
            !full->dialog.setPositiveButton("重新共享整屏"){_,_->if(valid()){ScreenSharing.stop(engine);startScreenSharing(true)}}
            request.isNotEmpty()->dialog.setPositiveButton("允许本次"){_,_->if(valid()){if(!a.allow(request))toast("申请或共享状态已改变，请重新申请")}}
        }
        dialog.showThemed()
    }
    private fun finishScreenApproval(){
        if(!resumed||!Hub.foreground)return
        val pending=screenApproval?:return;screenApproval=null
        if(!isDestroyed&&!isFinishing)ScreenSharing.approve(pending.first,pending.second,pending.third)else ScreenSharing.cancelRequest(pending.first)
    }
    private fun updateCallControls() {
        fun update(view:LinearLayout?,kind:String,label:String,selected:Boolean) {
            if(view==null)return
            val color=if(selected)palette.primary else palette.callSurface;val circle=view.getChildAt(0) as FrameLayout
            circle.background=bg(color,32);circle.removeAllViews();circle.addView(Glyph(this,kind,if(selected)palette.onPrimary else Color.WHITE),FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER))
            (view.getChildAt(view.childCount-1) as? TextView)?.text=label;view.contentDescription=label
        }
        update(micControl,if(Hub.rtc?.muted==true)"micOff" else "mic",if(Hub.rtc?.muted==true)"已静音" else "麦克风",Hub.rtc?.muted==true)
        update(speakerControl,"speaker",if(Hub.rtc?.speaker==true){if(compact)"外放开" else "扬声器开"} else {if(compact)"外放关" else "扬声器关"},Hub.rtc?.speaker==true)
        update(cameraControl,"video",if(Hub.rtc?.sharingScreen==true)"停止共享"else if(Hub.rtc?.cameraEnabled==true)"镜头设置"else "开启镜头",false)
        shareControl?.apply{text=if(Hub.rtc?.sharingScreen==true)"停止共享"else"共享屏幕";contentDescription=text}
        shareControl?.apply{val enabled=Hub.rtc?.sharingScreen==true||Hub.phase=="connected"&&Hub.rtc?.canShareScreen==true;isEnabled=enabled;alpha=if(enabled)1f else .5f}
    }
    private fun callControl(kind: String,label: String,color: Int=palette.callSurface,action: ()->Unit): LinearLayout {
        val box=col().apply { gravity=Gravity.CENTER; contentDescription=label; isClickable=true; isFocusable=true; setOnClickListener { action() } }
        val circle=FrameLayout(this).apply { background=bg(color,32); addView(Glyph(this@MainActivity,kind,if(color==palette.primary)palette.onPrimary else Color.WHITE),FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER)) }
        val side=if(tiny)40 else if(compact||resources.configuration.screenHeightDp<520)48 else 64
        box.addView(circle,lp(side,side)); space(box,if(tiny)4 else if(side==48)6 else 10); box.addView(text(label,if(tiny)10f else 12f,palette.callMuted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}); return box
    }
    private fun releaseRenderers() {
        assistControl=null;assistBar=null
        callVideoStage?.release();callVideoStage=null;callViewKey="";callViewEngine=null;callViewport=null;callTopOverlay=null;callBottomOverlay=null;callScaleControl=null;callCaption=null;classicTop=null;classicControls=null;classicIdentity=null;pipCard=null;pipClock=null
    }
    private fun composePost(){startActivityForResult(Intent(this,PostActivity::class.java),76);Motion.activityOpen(this)}
    private fun saveMomentPhoto(id:String) {
        if(savingMoment)return
        savingMoment=true;val token=Hub.token
        Hub.job({
            val file=File(cacheDir,"moment-$id.jpg")
            Hub.http.newCall(Request.Builder().url(Hub.SERVER+"/api/post-photos/$id").header("Authorization","Bearer $token").build()).execute().use { response ->
                if(!response.isSuccessful)throw Exception("图片已删除或暂时无法下载")
                val body=response.body?:throw Exception("图片下载失败");file.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
            };JSONObject().put("path",file.absolutePath)
        }, { result ->
            savingMoment=false;if(Hub.token!=token)return@job
            saveFile=File(result.getString("path"));returningFromPhotoSave=true
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/jpeg").putExtra(Intent.EXTRA_TITLE,"弥星动态-"+SimpleDateFormat("yyyyMMdd-HHmmss",Locale.CHINA).format(Date())+".jpg"),62)
        }, { savingMoment=false;toast(it) })
    }
    private fun onEvent(event: JSONObject) {
        if(event.optString("event") in setOf("message.deleted","message.updated","file.removed","conversation.cleared","relationship.changed","expired","call.ringing","call.accepted")){
            finishTextSelection()
            messageMenu?.dismiss();forwardJob?.cancel();forwardDialog?.dismiss()
            if(event.optString("event")=="message.deleted")selectedMessages.remove(event.optString("id"))
            if(event.optString("event")=="conversation.cleared")endMessageSelection()
            updateMessageSelection()
        }
        if(event.optString("event")=="update.state"){if(resumed&&Hub.token.isNotEmpty()&&peer==null&&!callScreen&&tab!=2)renderHome();return}
        if(voiceTextJob?.isRunning==false)cancelVoiceText(true)
        when(event.optString("event")){
            "message.deleted"->{val id=event.optString("id");voiceTextResults.remove(id);if(voiceTextJob?.messageId==id)cancelVoiceText()}
            "message.updated"->{val m=event.optJSONObject("message");val id=m?.optString("id");if(m?.optBoolean("withdrawn")==true||m?.optBoolean("decrypt_failed")==true)voiceTextResults.remove(id);if(voiceTextJob?.messageId==id)cancelVoiceText()}
            "file.removed"->{val fid=event.optString("id");voiceTextResults.keys().asSequence().toList().filter{voiceTextResults.optJSONObject(it)?.optString("file_id")==fid}.forEach{voiceTextResults.remove(it)};if(voiceTextJob?.fileId==fid)cancelVoiceText()}
            "conversation.cleared"->if(event.optString("peer")==peer?.optString("id")){cancelVoiceText();voiceTextResults=JSONObject()}
            "history.ready"->if(event.optBoolean("invalidated")){cancelVoiceText();voiceTextResults=JSONObject();loadVoiceTexts()}
        }
        when(event.optString("event")) {
            "me.updated", "profile.updated" -> {
                val user=event.getJSONObject("user")
                val buttonsChanged=user.optString("id")==peer?.optString("id")&&(peer?.optBoolean("bot_calls_enabled")!=user.optBoolean("bot_calls_enabled")||peer?.optBoolean("bot_ready")!=user.optBoolean("bot_ready"))
                val draft=composer?.text?.toString()?:""
                if(user.optString("id")==peer?.optString("id"))peer=user
                if(user.optString("id")==Hub.callUser.optString("id"))Hub.callUser=user
                if(callScreen)showCall() else if(peer!=null) { if(buttonsChanged){showChat();composer?.setText(draft);composer?.setSelection(draft.length)}else{renderMessages(false);updateStatusLabels()} } else renderHome()
                refresh()
            }
            "ready", "contacts.changed", "presence" -> {
                if(event.optString("event")!="presence")refreshRelationship()
                if(event.optString("event")=="presence" && peer!=null && event.optJSONObject("user")?.optString("id")==peer?.optString("id")) {
                    peer=event.getJSONObject("user"); updateStatusLabels()
                }
                refresh()
            }
            "history.ready" -> {
                val affected=event.optJSONArray("peers")?:JSONArray();val id=peer?.optString("id")
                if(event.optBoolean("invalidated")&&(event.optBoolean("reset")||(0 until affected.length()).any{affected.optString(it)==id})) {
                    chatGeneration++;messages.removeAll{!it.optBoolean("pending")};if(peer!=null&&!callScreen)renderMessages(false)
                }
                if(peer!=null&&!callScreen)loadMessages(false)
            }
            "outbox.changed" -> {
                event.optJSONObject("message")?.let{m->val id=peer?.optString("id");if(id!=null&&id in listOf(m.optString("sender"),m.optString("recipient")))appendMessage(m)}
                if(peer!=null)loadPending()else if(!callScreen)refresh()
            }
            "relationship.changed" -> { refresh();refreshRelationship();feed?.refreshFromTop() }
            "conversation.cleared" -> {
                if(event.optString("peer")==peer?.optString("id")){chatGeneration++;messages.removeAll { it.optLong("created")<=event.optLong("through") };hasMore=false;stopPlayer();renderMessages(false)}
                refresh()
            }
            "message.deleted" -> {
                if(event.optString("peer")==peer?.optString("id")){messages.removeAll{it.optString("id")==event.optString("id")};if(playingId==event.optString("id"))stopPlayer();renderMessages(false)}
                refresh()
            }
            "file.removed" -> {
                messages.filter{it.optString("file_id")==event.optString("id")}.forEach{it.put("attachment_available",false);it.optJSONObject("file")?.put("available",false)}
                renderMessages(false);refresh()
            }
            "message.updated" -> {
                val m=event.getJSONObject("message");val index=messages.indexOfFirst{it.optString("id")==m.optString("id")}
                if(index>=0){messages[index]=m;if(playingId==m.optString("id"))stopPlayer();renderMessages(false)};refresh()
            }
            "post.changed" -> feed?.onEvent(event)
            "typing" -> if(event.optString("peer")==peer?.optString("id")) updateStatusLabels()
            "message" -> {
                val m=event.getJSONObject("message"); val id=peer?.optString("id")
                if(id!=null && id in listOf(m.optString("sender"),m.optString("recipient"))) {
                    appendMessage(m)
                    if(m.optString("sender")==id && Hub.foreground){val session=Hub.token;Hub.io.execute { try { Hub.rawApi("POST","/api/read/$id",session=session) } catch(_:Exception){} }}
                }
                refresh()
            }
            "messages.read" -> if(event.optString("peer")==peer?.optString("id")) { val at=event.optLong("at");messages.filter { !it.optBoolean("pending")&&it.optString("sender")==Hub.me.optString("id")&&it.optLong("created")<=at }.forEach { m -> m.put("read",true);receiptLabels[m.optString("id")]?.apply { text=" · 已读";setTextColor(accent) } } }
            "connection" -> { stopTyping(); updateStatusLabels(); if(peer==null)connectionLabel?.text="○  正在重新连接…" }
            "call.waiting", "call.ringing", "call.accepted", "call.resynced" -> showCall()
            "call.reconnecting", "call.audio" -> { callClock?.text=callClockText();updateCallControls();updateCallChrome();updatePipParams() }
            "call.other_device" -> {if(callScreen){if(peer!=null)showChat()else showHome()};toast("通话已在其他设备处理")}
            "rtc.connected" -> { callClock?.text=callClockText();updateCallControls();updateCallChrome();updatePipParams() }
            "call.video" -> if(Hub.call!=null&&callScreen&&(Hub.foreground||isInPictureInPictureMode))showCall()
            "call.assist" -> if(Hub.call!=null&&callScreen&&(Hub.foreground||isInPictureInPictureMode)){Hub.rtc?.assist?.let{a->if(a.feedbackRevision!=assistFeedbackRevision){assistFeedbackRevision=a.feedbackRevision;if(resumed&&!isInPictureInPictureMode&&a.feedback.isNotEmpty())toast(a.feedback)}};updateCallChrome()}
            "call.ended", "rtc.ended" -> {
                if(event.optString("event")=="call.ended"&&Hub.call!=null&&event.optString("call_id")!=Hub.call?.optString("id"))return
                val remote=Hub.callUser.optString("id")
                if(isInPictureInPictureMode){releaseRenderers();finish();return}
                updatePipParams()
                if(callScreen) {
                    callScreen=false; window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; window.statusBarColor=canvas; window.navigationBarColor=Color.WHITE
                    if(peer!=null) { showChat(); loadMessages(false) } else { showHome(); refresh() }
                    val reason=event.optString("reason")
                    if(reason=="timeout")toast("对方暂未接听") else if(reason=="rejected")toast("对方已拒绝通话") else if(reason=="offline")toast("对方已断开连接") else if(reason=="failed")toast("网络连接未成功，请稍后重拨")
                }
                if(event.optString("reason")=="busy")busyFeedback("对方正在通话或暂时无法接听，请稍后重试。",remote,event.optBoolean("video"))
            }
            "error" -> {
                if(event.optString("code") in listOf("call_busy","self_busy"))busyFeedback(event.optString("error"),event.optString("peer"),event.optBoolean("video"),event.optString("code")=="self_busy")
                else toast(event.optString("error"))
                if(Hub.call==null)Hub.service?.update()
            }
            "expired" -> { toast("登录已过期，请重新登录"); showLogin() }
        }
    }
    @Deprecated("Android back callback")
    override fun onBackPressed() {
        if(authScreen?.back()==true)return
        if(messageMenu?.isShowing==true){finishTextSelection();messageMenu?.dismiss();return}
        if(selecting){endMessageSelection();return}
        if(inputPanel?.opened==true){inputPanel?.hide();return}
        if(callScreen && Hub.call!=null) {if(pipEligible())minimizeCall(true)else toast("请先接听或拒绝来电");return}
        if(peer!=null)leaveChat() else super.onBackPressed()
    }
}
