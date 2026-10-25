package cn.qingliao.app

import android.app.*
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.text.*
import android.view.*
import android.widget.*
import org.json.*
import java.text.SimpleDateFormat
import java.util.*

/** Friend feed. All access and mutations are checked again by the server. */
class MomentsView(private val activity:Activity,private val palette:ThemePalette,private val savePhoto:(String)->Unit,private val compose:()->Unit):LinearLayout(activity) {
    private val items=mutableListOf<JSONObject>()
    private var next=""
    private var scope="friends"
    private var loading=false
    private var disposed=false
    private var error=""
    private var loaded=false
    private var requestId=0
    private var hasNew=false
    private var commentsDialog:Dialog?=null
    private var commentPost=""
    private var reloadComments:(()->Unit)?=null
    private val pendingLikes=mutableSetOf<String>()
    private val expanded=mutableSetOf<String>()
    private val cardViews=linkedMapOf<String,LinearLayout>()
    private var anchorRevision=0
    private var optionsDialog:Dialog?=null
    private var gallery:Dialog?=null
    private var jumpToStart=false
    private var refreshControl:LinearLayout?=null
    private var olderControl:TextView?=null
    private val viewer=Hub.me.optString("id")
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun bg(color:Int,radius:Int=16)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
    private fun col()=LinearLayout(activity).apply { orientation=VERTICAL }
    private fun row()=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
    private fun lp(w:Int=-1,h:Int=-2)=LayoutParams(if(w<0)w else dp(w),if(h<0)h else dp(h))
    private fun gap(parent:LinearLayout,n:Int)=parent.addView(View(activity),lp(0,n))
    private fun label(value:String,size:Float=14f,color:Int=palette.ink,bold:Boolean=false)=TextView(activity).apply { text=value;textSize=size;setTextColor(color);includeFontPadding=false;if(bold)setTypeface(typeface,Typeface.BOLD) }
    private fun action(value:String,kind:String,color:Int=palette.accent,click:()->Unit)=row().apply {
        contentDescription=value;background=RippleDrawable(ColorStateList.valueOf(palette.ripple),null,bg(Color.WHITE,10));isClickable=true;isFocusable=true;minimumHeight=dp(44);setPadding(dp(10),dp(10),dp(10),dp(10))
        Motion.press(this)
        addView(Glyph(activity,kind,color),lp(18,18));addView(label(value,12f,color).apply { setPadding(dp(6),0,0,0) });setOnClickListener { click() }
    }
    private fun portrait(user:JSONObject,size:Int=40)=FrameLayout(activity).apply {
        background=bg(palette.soft,size/2);clipToOutline=true
        addView(label(user.optString("nickname").take(1),16f,palette.accent,true).apply { gravity=Gravity.CENTER },FrameLayout.LayoutParams(-1,-1))
        val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP;visibility=View.GONE };addView(image,FrameLayout.LayoutParams(-1,-1));AvatarImages.load(user.optString("avatar_id"),image,user.optString("avatar_post"))
    }
    private fun toast(value:String)=Toast.makeText(activity,value,Toast.LENGTH_LONG).show()
    private fun date(ms:Long):String {
        val delta=(System.currentTimeMillis()-ms).coerceAtLeast(0)
        return when { delta<60000->"刚刚";delta<3600000->"${delta/60000} 分钟前";delta<86400000->"${delta/3600000} 小时前";else->SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(ms)) }
    }
    private fun restorePage(){MomentFeedCache.get(viewer,scope)?.let { page -> val found=JSONArray(page.json);items.clear();for(i in 0 until found.length())items.add(found.getJSONObject(i));next=page.next;loaded=true }}
    private fun rememberPage(){if(loaded)MomentFeedCache.put(viewer,scope,items,next)}
    init { orientation=VERTICAL;restorePage();render();refresh() }
    fun dispose(immediate:Boolean=false){rememberPage();disposed=true;requestId++;anchorRevision++;Motion.end(this);if(immediate)listOf(commentsDialog,optionsDialog,gallery).forEach { it?.window?.setWindowAnimations(0) };commentsDialog?.dismiss();optionsDialog?.dismiss();gallery?.dismiss();commentsDialog=null;reloadComments=null}
    fun reflow(){render()}
    fun refreshFromTop(){jumpToStart=true;requestId++;loading=false;refresh()}
    fun refresh(older:Boolean=false) {
        if(disposed||loading)return
        if(older&&next.isEmpty())return
        loading=true;val hadError=error.isNotEmpty();error=""
        if(!loaded||items.isEmpty()||hadError)render()else updateLoading()
        val current=++requestId
        val path="/api/posts?scope=$scope"+(if(older)"&before="+Uri.encode(next) else "")
        Hub.job({ Hub.api("GET",path) }, { result ->
            if(disposed||current!=requestId)return@job
            val before=items.joinToString { it.toString() };val oldNext=next;val hadNew=hasNew
            loading=false;loaded=true;hasNew=false
            if(!older)items.clear()
            val found=result.getJSONArray("posts")
            for(i in 0 until found.length()) { val p=found.getJSONObject(i);if(items.none { it.optString("id")==p.optString("id") })items.add(p) }
            next=result.optString("next")
            rememberPage()
            if(before!=items.joinToString { it.toString() }||oldNext!=next||hadNew||items.isEmpty())render()else updateLoading()
            if(jumpToStart){jumpToStart=false;anchorRevision++;scroller()?.post { if(!disposed)scroller()?.scrollTo(0,0) }}
        }, { value -> if(!disposed&&current==requestId){loading=false;error=value;render()} })
    }
    private fun updateLoading(){(refreshControl?.getChildAt(1) as? TextView)?.text=if(loading)"加载中" else "刷新";olderControl?.apply { text=if(loading)"正在加载…" else "查看更多动态";isEnabled=!loading }}
    fun onEvent(event:JSONObject) {
        if(disposed)return
        if(event.optString("action")=="refresh"){MomentFeedCache.clear();items.clear();loaded=false;refreshFromTop();return}
        val id=event.optString("id")
        if(event.optString("action")=="deleted") { items.removeAll { it.optString("id")==id };render();if(commentPost==id)commentsDialog?.dismiss();return }
        if(event.optString("action")=="published"&&items.none { it.optString("id")==id }) {
            Hub.job({Hub.api("GET","/api/posts/$id")},{p->if(!disposed&&(scope!="mine"||p.getJSONObject("user").optString("id")==Hub.me.optString("id"))&&(scope!="public"||p.optString("audience")=="public")&&(scope!="friends"||p.getJSONObject("user").optBoolean("friend"))){hasNew=true;render()}},{})
            return
        }
        if(items.any { it.optString("id")==id })Hub.job({ Hub.api("GET","/api/posts/$id") }, { if(!disposed)replace(it) }, { })
    }
    private fun replace(post:JSONObject) {
        val id=post.optString("id");val index=items.indexOfFirst { it.optString("id")==id };if(index<0)return
        fun commentIds(p:JSONObject):List<String> { val comments=p.optJSONArray("comments")?:JSONArray();return (0 until comments.length()).map { comments.getJSONObject(it).optString("id") } }
        val previous=items[index];val changed=previous.optInt("comment_count")!=post.optInt("comment_count")||commentIds(previous)!=commentIds(post)
        items[index]=post
        keepReading { cardViews[id]?.let { old -> val parent=old.parent as? LinearLayout;val position=parent?.indexOfChild(old)?:-1;if(position>=0){val params=old.layoutParams;parent?.removeViewAt(position);parent?.addView(card(post),position,params)} } }
        if(changed&&commentPost==id)reloadComments?.invoke()
    }
    private fun scroller():ScrollView? { var p=parent;while(p!=null){if(p is ScrollView)return p;p=p.parent};return null }
    private fun keepReading(change:()->Unit) {
        Motion.end(this)
        val scroll=scroller();val anchor=cardViews.entries.firstOrNull { it.value.getGlobalVisibleRect(android.graphics.Rect()) }
        val position=IntArray(2);anchor?.value?.getLocationOnScreen(position);val oldY=position[1];val oldScroll=scroll?.scrollY?:0;val revision=++anchorRevision
        if(scroll!=null) {
            val observer=scroll.viewTreeObserver
            observer.addOnGlobalLayoutListener(object:ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout(){if(observer.isAlive)observer.removeOnGlobalLayoutListener(this);if(disposed||revision!=anchorRevision)return
                    val view=anchor?.key?.let { cardViews[it] }
                    if(view!=null){view.getLocationOnScreen(position);scroll.scrollBy(0,position[1]-oldY)}else scroll.scrollTo(0,oldScroll)
                }
            })
        };change()
    }
    private fun render()=keepReading { renderContents() }
    private fun renderContents() {
        cardViews.clear()
        removeAllViews()
        val filters=row().apply { setPadding(0,dp(2),0,dp(16)) }
        listOf("friends" to "好友","mine" to "我的").forEach { (key,value) ->
            val selected=scope==key
            filters.addView(label(value,13f,if(selected)palette.accent else palette.muted,selected).apply {
                gravity=Gravity.CENTER;background=bg(if(selected)palette.soft else Color.TRANSPARENT,12);setPadding(dp(8),dp(10),dp(8),dp(10));contentDescription=value+"动态"
                setOnClickListener { if(!loading&&scope!=key){rememberPage();scope=key;items.clear();next="";loaded=false;restorePage();render();refresh()} }
            },LayoutParams(0,dp(40),1f).apply { marginEnd=dp(5) })
        }
        refreshControl=action(if(loading)"加载中" else "刷新","refresh",palette.muted) { refresh() };filters.addView(refreshControl);addView(filters,lp())
        val invitation=row().apply { setPadding(dp(18),dp(20),dp(18),dp(20));background=StarlightDrawable(palette,dp(24).toFloat());contentDescription="写动态";setOnClickListener { compose() } }
        invitation.addView(portrait(Hub.me,40),lp(40,40));val words=col().apply { setPadding(dp(12),0,dp(8),0) };words.addView(label("记录生活的这一刻",15f,bold=true));gap(words,6);words.addView(label("文字、图片、视频或语音",12f,palette.muted));invitation.addView(words,LayoutParams(0,-2,1f));invitation.addView(Glyph(activity,"edit",palette.accent),lp(22,22));addView(invitation,lp());gap(this,16)
        if(hasNew)addView(label("有新动态，点击查看",13f,palette.accent,true).apply { gravity=Gravity.CENTER;background=bg(palette.soft,12);setPadding(0,dp(13),0,dp(13));setOnClickListener { refreshFromTop() } },lp().apply { bottomMargin=dp(12) })
        if(error.isNotEmpty())addView(label(error+" · 点按重试",13f,Color.rgb(176,65,56)).apply { setPadding(dp(8),dp(16),dp(8),dp(16));setOnClickListener { refresh() } },lp())
        if(items.isEmpty()) {
            val empty=col().apply { gravity=Gravity.CENTER;setPadding(dp(18),dp(48),dp(18),dp(48)) }
            empty.addView(FrameLayout(activity).apply { background=bg(palette.soft,28);addView(Glyph(activity,"moments",palette.accent),FrameLayout.LayoutParams(dp(38),dp(38),Gravity.CENTER)) },lp(84,84));gap(empty,22)
            empty.addView(label(if(loading&&!loaded)"正在加载动态…" else if(scope=="mine")"把日常，留在这里" else "和朋友分享一点日常",19f,bold=true));gap(empty,10)
            empty.addView(label("动态仅自己和好友可见",13f,palette.muted));gap(empty,22)
            if(!loading)empty.addView(action("发布第一条动态","edit") { compose() })
            addView(empty,lp())
        } else items.forEach { addView(card(it),lp().apply { bottomMargin=dp(14) }) }
        olderControl=null
        if(next.isNotEmpty()){olderControl=label(if(loading)"正在加载…" else "查看更多动态",13f,palette.accent,true).apply { gravity=Gravity.CENTER;setPadding(0,dp(18),0,dp(18));setOnClickListener { refresh(true) } };addView(olderControl,lp())}
        if(loaded&&next.isEmpty()&&items.isNotEmpty())addView(label("已看完 · 和朋友分享新的日常吧",11f,palette.muted).apply { gravity=Gravity.CENTER;setPadding(0,dp(12),0,dp(10)) },lp())
    }
    private fun card(post:JSONObject):LinearLayout {
        val id=post.getString("id");val user=post.getJSONObject("user");val own=user.optString("id")==Hub.me.optString("id")
        val card=col().apply { background=BrandUi.card(activity,palette,24);setPadding(dp(18),dp(18),dp(18),dp(10)) }
        val top=row();top.addView(portrait(user),lp(40,40));val who=col().apply { setPadding(dp(10),0,0,0) }
        who.addView(label(user.optString("nickname"),15f,bold=true).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END });gap(who,6)
        who.addView(label(date(post.optLong("created"))+if(post.optString("audience")=="public")" · 广场可见" else " · 好友可见",11f,palette.muted));top.addView(who,LayoutParams(0,-2,1f))
        top.addView(FrameLayout(activity).apply {
            contentDescription="更多动态操作";isFocusable=true;background=RippleDrawable(ColorStateList.valueOf(palette.ripple),null,bg(Color.WHITE,10))
            addView(Glyph(activity,"more",palette.muted),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));setOnClickListener { postOptions(post) }
        },lp(44,44));card.addView(top,lp())
        if(post.optString("text").isNotEmpty()) {
            gap(card,14)
            card.addView(ExpandablePostText(activity,palette,post.getString("text"),id in expanded) { open -> if(open)expanded.add(id)else expanded.remove(id) },lp())
        }
        val photos=post.getJSONArray("photos");if(photos.length()>0) { gap(card,13);card.addView(MomentPhotoGrid(activity,palette,photos) { index -> gallery?.dismiss();gallery=MomentGallery.show(activity,photos,index,savePhoto) },lp()) }
        post.optJSONObject("media")?.let { media ->
            val video=media.optString("kind")=="video";val seconds=(media.optLong("duration")/1000).coerceAtLeast(1)
            gap(card,14)
            val open={if(Hub.call!=null)toast("请先结束通话，再播放动态")else{activity.startActivity(Intent(activity,MomentPlayerActivity::class.java).putExtra("kind",media.optString("kind")).putExtra("media_id",media.optString("id")));Motion.activityOpen(activity)}}
            if(video)card.addView(MomentVideoTile(activity,palette,media,open),lp(-2,-2)) else {
            val entry=row().apply{background=BrandUi.card(activity,palette,18);setPadding(dp(16),dp(16),dp(16),dp(16));minimumHeight=dp(84);contentDescription=if(video)"播放视频动态" else "播放语音动态";isFocusable=true
                setOnClickListener{open()}}
            entry.addView(FrameLayout(activity).apply{background=BrandUi.primary(activity,palette,16);addView(Glyph(activity,if(video)"video" else "mic",palette.onPrimary),FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER))},lp(48,48))
            val info=col().apply{setPadding(dp(12),0,0,0)};info.addView(label(if(video)"视频动态" else "语音动态",15f,bold=true));gap(info,7);info.addView(label("%02d:%02d".format(seconds/60,seconds%60)+" · 点按播放",12f,palette.muted));entry.addView(info,LayoutParams(0,-2,1f));entry.addView(Glyph(activity,"play",palette.accent),lp(22,22));card.addView(entry,lp())
            }
        }
        gap(card,8)
        val actions=row();actions.addView(View(activity),LayoutParams(0,1,1f))
        val liked=post.optBoolean("liked")
        lateinit var like:LinearLayout
        val likeLabel=(if(liked)"已赞" else "点赞")+(if(post.optInt("like_count")>0)" ${post.optInt("like_count")}" else "")
        like=action(if(id in pendingLikes)"处理中…" else likeLabel,if(liked)"heartFill" else "heart",if(liked)palette.accent else palette.muted) {
            if(pendingLikes.add(id)) {
                like.isEnabled=false;(like.getChildAt(1) as TextView).text="处理中…"
                Hub.job({ Hub.api("POST","/api/posts/$id/like",JSONObject().put("liked",!liked)) }, { pendingLikes.remove(id);if(!disposed)replace(it) }, { pendingLikes.remove(id);if(!disposed)items.find { p -> p.optString("id")==id }?.let { replace(it) };toast(it) })
            }
        }.apply { isEnabled=id !in pendingLikes }
        actions.addView(like)
        actions.addView(action("评论"+(if(post.optInt("comment_count")>0)" ${post.optInt("comment_count")}" else ""),"comment",palette.muted) { showComments(post) });card.addView(actions,lp())
        val comments=post.getJSONArray("comments")
        if(comments.length()>0) {
            val preview=col().apply { background=bg(palette.canvas,10);setPadding(dp(12),dp(10),dp(12),dp(10));setOnClickListener { showComments(post) } }
            for(i in 0 until comments.length()) { val c=comments.getJSONObject(i);preview.addView(label(c.getJSONObject("user").optString("nickname")+"："+c.optString("text"),13f).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setLineSpacing(dp(3).toFloat(),1f);if(i>0)setPadding(0,dp(7),0,0) },lp()) }
            if(post.optInt("comment_count")>2)preview.addView(label("查看全部 ${post.optInt("comment_count")} 条评论",12f,palette.accent).apply { setPadding(0,dp(10),0,0) })
            card.addView(preview,lp().apply { bottomMargin=dp(8) })
        }
        cardViews[id]=card;return card
    }
    private fun postOptions(post:JSONObject) {
        val actions=mutableListOf<ActionSheets.Action>()
        if(post.optString("text").isNotEmpty())actions.add(ActionSheets.Action("copy","复制动态文字") {
            (activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("弥星动态",post.getString("text")));if(android.os.Build.VERSION.SDK_INT<33)toast("已复制")
        })
        if(post.getJSONObject("user").optString("id")==Hub.me.optString("id"))actions.add(ActionSheets.Action("trash","删除动态","同时删除图片、点赞和评论") {
            confirm("删除这条动态？","图片、点赞和评论也会一并删除。") { Hub.job({Hub.api("DELETE","/api/posts/"+post.getString("id"))},{if(!disposed){items.removeAll { p -> p.optString("id")==post.optString("id") };render();toast("动态已删除")}},{toast(it)}) }
        })
        if(actions.isEmpty())actions.add(ActionSheets.Action("image","查看图片") { gallery=MomentGallery.show(activity,post.getJSONArray("photos"),0,savePhoto) })
        optionsDialog?.dismiss();optionsDialog=ActionSheets.show(activity,post.getJSONObject("user").optString("nickname")+"的动态",SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(Date(post.optLong("created")))+if(post.optString("audience")=="public")" · 广场可见" else " · 好友可见",actions=actions)
    }
    private fun confirm(title:String,message:String,action:()->Unit) {
        val d=AlertDialog.Builder(activity).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("删除") { _,_ -> action() }.create();d.show();d.getButton(-1).setTextColor(Color.rgb(183,68,57));d.getButton(-2).setTextColor(palette.accent)
    }
    private fun showComments(post:JSONObject) {
        commentsDialog?.dismiss();val id=post.getString("id");val ownPost=post.getJSONObject("user").optString("id")==Hub.me.optString("id")
        val dialog=Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);commentsDialog=dialog;commentPost=id
        val root=col().apply { setPadding(dp(20),dp(16),dp(20),dp(14));background=bg(Color.WHITE,24) }
        root.addView(View(activity).apply { background=bg(palette.line,2) },lp(34,4).apply { gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(10) })
        val heading=row();val title=label("评论 · ${post.optInt("comment_count")}",19f,bold=true);heading.addView(title,LayoutParams(0,-2,1f));heading.addView(action("关闭","close",palette.muted) { dialog.dismiss() });root.addView(heading)
        root.addView(label(post.getJSONObject("user").optString("nickname")+" · "+post.optString("text").ifEmpty { "分享了图片" },12f,palette.muted).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(5),0,dp(12)) },lp())
        val newComments=label("有新评论，点按查看",12f,palette.accent,true).apply { gravity=Gravity.CENTER;background=bg(palette.soft,10);setPadding(0,dp(12),0,dp(12));visibility=GONE };root.addView(newComments,lp())
        val entries=col();val scroll=ScrollView(activity).apply { addView(entries);isVerticalScrollBarEnabled=false };root.addView(scroll,LayoutParams(-1,0,1f))
        val errorLabel=label("",12f,Color.rgb(176,65,56)).apply { visibility=GONE };root.addView(errorLabel,lp())
        val composer=row().apply { setPadding(0,dp(12),0,0) }
        val input=EditText(activity).apply { hint="说点什么…";textSize=14f;maxLines=4;setTextColor(palette.ink);setHintTextColor(palette.muted);background=bg(palette.canvas,12);setPadding(dp(12),dp(10),dp(12),dp(10));filters=arrayOf(InputFilter.LengthFilter(500));inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;contentDescription="评论内容";if(android.os.Build.VERSION.SDK_INT>=29)textCursorDrawable?.setTint(palette.accent) }
        composer.addView(input,LayoutParams(0,-2,1f));val send=label("发送",14f,palette.onPrimary,true).apply { gravity=Gravity.CENTER;background=bg(palette.primary,12);setPadding(dp(15),dp(12),dp(15),dp(12));minimumHeight=dp(44);contentDescription="发送评论" };composer.addView(send,lp(-2,-2).apply { marginStart=dp(10) });root.addView(composer,lp())
        val all=mutableListOf<JSONObject>();var before="";var fetching=false;var pendingReload=false;var sending=false;var client=UUID.randomUUID().toString()
        fun updateSend(){send.isEnabled=!sending&&input.text.isNotBlank();send.alpha=if(send.isEnabled)1f else .45f;send.text=if(sending)"发送中" else "发送"}
        lateinit var load:(Boolean)->Unit
        fun draw() {
            entries.removeAllViews()
            if(before.isNotEmpty())entries.addView(label("查看更早评论",12f,palette.accent).apply { gravity=Gravity.CENTER;setPadding(0,dp(16),0,dp(16));setOnClickListener { load(true) } },lp())
            if(all.isEmpty())entries.addView(label(if(fetching)"正在加载评论…" else "还没有评论，聊两句吧",14f,palette.muted).apply { gravity=Gravity.CENTER;setPadding(0,dp(50),0,dp(50)) },lp())
            all.forEach { c ->
                val item=row().apply { gravity=Gravity.TOP;setPadding(0,dp(12),0,dp(12)) };val user=c.getJSONObject("user");item.addView(portrait(user,32),lp(32,32))
                val detail=col().apply { setPadding(dp(10),0,0,0) };detail.addView(label(user.optString("nickname"),13f,palette.accent,true));gap(detail,6);detail.addView(label(c.optString("text"),14f).apply { setLineSpacing(dp(3).toFloat(),1f) });gap(detail,7);detail.addView(label(date(c.optLong("created")),10f,palette.muted));item.addView(detail,LayoutParams(0,-2,1f))
                if(ownPost||user.optString("id")==Hub.me.optString("id"))item.setOnLongClickListener {
                    confirm("删除评论？","删除后将无法恢复。") { Hub.job({ Hub.api("DELETE","/api/posts/$id/comments/"+c.getString("id")) }, { all.removeAll { it.optString("id")==c.optString("id") };draw() }, { toast(it) }) };true
                };entries.addView(item,lp())
            }
        }
        load={ older ->
            if(fetching&&!older)pendingReload=true
            if(!fetching&&dialog.isShowing) {
                val oldHeight=entries.height;val oldScroll=scroll.scrollY
                fetching=true;draw();if(!older)newComments.visibility=GONE
                Hub.job({ Hub.api("GET","/api/posts/$id/comments"+(if(older)"?before="+Uri.encode(before) else "")) }, { result ->
                    fetching=false;if(dialog.isShowing) {
                        errorLabel.visibility=GONE;val found=result.getJSONArray("comments");val incoming=(0 until found.length()).map { found.getJSONObject(it) };if(older)all.addAll(0,incoming.filter { c -> all.none { it.optString("id")==c.optString("id") } }) else { all.clear();all.addAll(incoming) };before=result.optString("next");draw()
                        val observer=entries.viewTreeObserver;observer.addOnGlobalLayoutListener(object:ViewTreeObserver.OnGlobalLayoutListener { override fun onGlobalLayout(){if(observer.isAlive)observer.removeOnGlobalLayoutListener(this);if(dialog.isShowing){if(older)scroll.scrollTo(0,oldScroll+(entries.height-oldHeight).coerceAtLeast(0))else scroll.fullScroll(View.FOCUS_DOWN)}} })
                        if(pendingReload){pendingReload=false;newComments.visibility=VISIBLE}
                    }
                }, { value -> fetching=false;if(dialog.isShowing){errorLabel.text=value+" · 点按重试";errorLabel.visibility=VISIBLE;draw()} })
            }
        }
        errorLabel.setOnClickListener { load(false) };newComments.setOnClickListener { load(false) }
        reloadComments={
            val latest=items.find { it.optString("id")==id };title.text="评论 · ${latest?.optInt("comment_count")?:post.optInt("comment_count")}"
            val atBottom=entries.height-scroll.height-scroll.scrollY<dp(60)
            if(atBottom&&!fetching)load(false)else newComments.visibility=VISIBLE
        }
        input.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){client=UUID.randomUUID().toString();updateSend()}
            override fun afterTextChanged(s:Editable?){}
        })
        send.setOnClickListener {
            val body=input.text.toString().trim();if(body.isEmpty()||sending)return@setOnClickListener
            sending=true;updateSend();input.isEnabled=false;errorLabel.visibility=GONE
            Hub.job({ Hub.api("POST","/api/posts/$id/comments",JSONObject().put("text",body).put("client_id",client)) }, {
                sending=false;if(dialog.isShowing){input.setText("");input.isEnabled=true;updateSend();load(false)}
                Hub.job({ Hub.api("GET","/api/posts/$id") }, { if(!disposed)replace(it) }, {})
            }, { value -> sending=false;if(dialog.isShowing){input.isEnabled=true;updateSend();errorLabel.text=value;errorLabel.visibility=VISIBLE} })
        }
        // A full transparent window lets IME insets size the sheet reliably on Android 15.
        val host=FrameLayout(activity).apply { setOnClickListener { dialog.dismiss() } }
        root.isClickable=true
        host.addView(root,FrameLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.78f).toInt(),Gravity.BOTTOM))
        fun fitSheet(insets:WindowInsets) {
            val safe=if(android.os.Build.VERSION.SDK_INT>=30)insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.systemBars()) else null
            val left=safe?.left?:insets.systemWindowInsetLeft;val right=safe?.right?:insets.systemWindowInsetRight
            val top=safe?.top?:insets.systemWindowInsetTop;val bottom=safe?.bottom?:insets.stableInsetBottom
            host.setPadding(left,top,right,bottom)
            val available=((host.height.takeIf { it>0 }?:resources.displayMetrics.heightPixels)-top-bottom-dp(8)).coerceAtLeast(dp(160))
            val target=minOf((resources.displayMetrics.heightPixels*.78f).toInt(),available)
            if(root.layoutParams.height!=target)root.layoutParams=FrameLayout.LayoutParams(-1,target,Gravity.BOTTOM)
        }
        host.setOnApplyWindowInsetsListener { _,insets ->
            fitSheet(insets);host.post { if(dialog.isShowing)fitSheet(insets) }
            insets
        }
        host.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> host.rootWindowInsets?.let { fitSheet(it) } }
        dialog.setContentView(host);dialog.setOnDismissListener { if(commentsDialog===dialog){commentsDialog=null;commentPost="";reloadComments=null} }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent);setGravity(Gravity.BOTTOM)
            setSoftInputMode(if(android.os.Build.VERSION.SDK_INT>=30)WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);setDimAmount(.28f)
            statusBarColor=Color.TRANSPARENT;navigationBarColor=Color.WHITE
            decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            if(android.os.Build.VERSION.SDK_INT>=30)setDecorFitsSystemWindows(false)
        }
        Motion.press(send);Motion.sheet(dialog.window);updateSend();dialog.show();dialog.window?.setLayout(-1,-1);host.requestApplyInsets();load(false)
    }
}
