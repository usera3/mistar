package cn.qingliao.app

import android.app.Activity
import android.content.*
import android.os.*
import android.text.*
import android.text.style.ForegroundColorSpan
import android.view.*
import android.view.inputmethod.*
import android.widget.*
import org.json.*

/** Local-only search. No query, draft or decrypted message leaves the device. */
class SearchActivity:Activity(){
    private val session=Hub.token
    private val owner=Hub.me.optString("id")
    private val peer by lazy{intent.getStringExtra("peer").orEmpty()}
    private val p by lazy{ThemeStore.current(this)}
    private val u by lazy{FormUi(this,p)}
    private var contacts=JSONArray()
    private var filter="all"
    private var generation=0
    private var loaded=false
    private var searching=false
    private var scheduled:Runnable?=null
    private lateinit var query:EditText
    private lateinit var results:LinearLayout
    private lateinit var status:TextView
    private val chips=mutableMapOf<String,TextView>()
    private fun valid()=!isFinishing&&!isDestroyed&&Hub.token==session&&Hub.me.optString("id")==owner
    override fun finish(){super.finish();Motion.activityClose(this)}
    override fun onResume(){super.onResume();if(!valid())finish()}
    override fun onDestroy(){generation++;scheduled?.let{Hub.main.removeCallbacks(it)};super.onDestroy()}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);if(!valid()){finish();return}
        window.statusBarColor=p.canvas;window.navigationBarColor=p.canvas
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=u.col().apply{setBackgroundColor(p.canvas)}
        root.setOnApplyWindowInsetsListener{v,i->if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom)}else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i}
        val top=u.row().apply{setPadding(u.dp(4),u.dp(4),u.dp(4),u.dp(4))}
        top.addView(u.icon("back","返回"){finish()},u.lp(44,48))
        val field=u.row().apply{background=BrandUi.field(this@SearchActivity,p)}
        query=EditText(this).apply{
            tag="search.query";hint=if(peer.isEmpty())"联系人、文字或文件名"else"文字或文件名";textSize=if(u.compact)13f else 15f;setTextColor(p.ink);setHintTextColor(p.muted)
            isSingleLine=true;inputType=android.text.InputType.TYPE_CLASS_TEXT;imeOptions=EditorInfo.IME_ACTION_SEARCH;isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            filters=arrayOf(InputFilter.LengthFilter(80));background=null;setPadding(u.dp(12),u.dp(12),0,u.dp(12));minimumHeight=u.dp(48)
        };field.addView(query,LinearLayout.LayoutParams(0,-2,1f))
        val clear=u.icon("close","清空搜索"){query.text.clear()}.apply{tag="search.clear";visibility=View.GONE};field.addView(clear,u.lp(44,48));top.addView(field,LinearLayout.LayoutParams(0,-2,1f));root.addView(top,u.lp())
        val tabs=u.row().apply{setPadding(u.dp(12),u.dp(6),u.dp(12),u.dp(6))}
        if(peer.isEmpty())listOf("all" to "全部","contacts" to "联系人","messages" to "消息").forEach{(key,title)->
            val c=u.link(title,"search.filter.$key"){filter=key;paintChips();search()};chips[key]=c;tabs.addView(c,LinearLayout.LayoutParams(0,-2,1f))
        };root.addView(tabs,u.lp())
        status=u.text(if(peer.isEmpty())"搜索本机联系人、文字和文件名"else"搜索本机保存的文字和文件名",12f,p.muted).apply{tag="search.status";maxLines=1;ellipsize=TextUtils.TruncateAt.END;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;setPadding(u.dp(16),u.dp(8),u.dp(16),u.dp(8))};root.addView(status,u.lp())
        results=u.col().apply{setPadding(u.dp(12),0,u.dp(12),u.dp(24))};root.addView(ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(results)},LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);root.requestApplyInsets();paintChips()
        query.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun afterTextChanged(s:Editable?){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){clear.visibility=if(s.isNullOrEmpty())View.GONE else View.VISIBLE;search()}})
        query.setOnEditorActionListener{_,action,_->if(action==EditorInfo.IME_ACTION_SEARCH){(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken,0);query.clearFocus();search();true}else false}
        Hub.job({LocalData.home(owner)}, {if(valid()){contacts=it.optJSONArray("contacts")?:JSONArray();loaded=true;search()}},{if(valid()){loaded=true;status.text=it;search()}})
    }
    private fun paintChips(){chips.forEach{(key,v)->v.background=BrandUi.rounded(this,if(filter==key)p.soft else p.canvas,14);v.setTextColor(if(filter==key)p.accent else p.muted)}}
    private fun highlight(text:String,needle:String)=SpannableString(text).apply{
        if(needle.isNotEmpty()){var at=text.indexOf(needle,ignoreCase=true);var count=0;while(at>=0&&count++<12){setSpan(ForegroundColorSpan(p.accent),at,at+needle.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);at=text.indexOf(needle,at+needle.length,ignoreCase=true)}}
    }
    private fun search(){
        scheduled?.let{Hub.main.removeCallbacks(it)};val revision=++generation;val needle=query.text.toString().trim();results.removeAllViews()
        if(!loaded){status.text="正在读取本机记录…";return}
        if(needle.isEmpty()){status.text="输入关键词，查找联系人、文字或文件名";return}
        status.text="正在搜索…"
        scheduled=Runnable{if(valid()&&generation==revision){
            if(searching)return@Runnable
            searching=true
            val current=filter
            Hub.job({JSONObject().put("messages",if(current!="contacts")LocalData.searchMessages(owner,needle,peer)else JSONArray())},{data->
                searching=false;if(valid()){if(generation==revision)render(needle,current,data.getJSONArray("messages"))else search()}
            },{searching=false;if(valid()){if(generation==revision)status.text=it else search()}})
        }}.also{Hub.main.postDelayed(it,180)}
    }
    private fun render(needle:String,current:String,messages:JSONArray){
        results.removeAllViews()
        val users=(0 until contacts.length()).map{contacts.getJSONObject(it)}
        val found=if(peer.isEmpty()&&current!="messages")users.filter{it.optString("state")=="accepted"&&listOf(it.optString("nickname"),it.optString("username"),it.optString("id")).any{s->s.contains(needle,true)}}.take(30)else emptyList()
        if(found.isNotEmpty()){u.section(results,"联系人 · ${found.size}");found.forEach{user->item(user.optString("nickname"),"弥星号 ${user.optString("id")}",needle,"search.contact.${user.getString("id")}"){select(user,"")}}}
        if(messages.length()>0){u.section(results,"本机消息 · ${messages.length()}");for(n in 0 until messages.length()){
            val m=messages.getJSONObject(n);val id=m.getString("peer");val user=users.find{it.optString("id")==id}?:JSONObject().put("id",id).put("nickname",if(id==peer)intent.getStringExtra("nickname")?:"聊天"else"聊天 $id").put("friend",false)
            item(user.optString("nickname")+"  ·  "+ConversationRow.labelTime(m.optLong("created")),m.optString("snippet"),needle,"search.message.${m.getString("id")}"){select(user,m.getString("id"))}
        }}
        status.text=if(found.isEmpty()&&messages.length()==0)"本机没有找到匹配内容"else if(u.tiny)"找到 ${found.size+messages.length()} 项 · 本机记录"else"${found.size+messages.length()} 个结果 · 仅搜索本机保存的记录"
    }
    private fun item(title:String,detail:String,needle:String,key:String,click:()->Unit){
        val c=u.card().apply{tag=key;isFocusable=true;setOnClickListener{if(valid())click()};Motion.press(this)}
        c.addView(u.text(title,14f,bold=true).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;text=highlight(title,needle)},u.lp());u.gap(c,8)
        c.addView(u.text(detail,13f,p.muted).apply{maxLines=3;ellipsize=TextUtils.TruncateAt.END;text=highlight(detail,needle)},u.lp());results.addView(c,u.lp());u.gap(results,8)
    }
    private fun select(user:JSONObject,message:String){
        if(!valid())return
        val safe=JSONObject().put("id",user.getString("id")).put("nickname",user.optString("nickname")).put("friend",user.optBoolean("friend",user.optString("state")=="accepted")).put("state",user.optString("state")).put("bot",user.optBoolean("bot")).put("bot_calls_enabled",user.optBoolean("bot_calls_enabled")).put("bot_ready",user.optBoolean("bot_ready"))
        setResult(RESULT_OK,Intent().putExtra("peer",user.getString("id")).putExtra("message",message).putExtra("user",safe.toString()));finish()
    }
}
