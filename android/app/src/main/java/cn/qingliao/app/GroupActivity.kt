package cn.qingliao.app

import android.app.*
import android.content.*
import android.content.res.ColorStateList
import android.os.*
import android.text.*
import android.view.*
import android.widget.*
import org.json.*
import java.util.UUID

/** Private friend picker and member management; every completion belongs to the opening account. */
class GroupActivity:Activity(){
    private val session=Hub.token;private val owner=Hub.me.optString("id")
    private val p by lazy{ThemeStore.current(this)};private val u by lazy{FormUi(this,p)}
    private val gid by lazy{intent.getStringExtra("peer").orEmpty()}
    private var creation=UUID.randomUUID().toString();private var busy=false
    private lateinit var content:LinearLayout;private lateinit var status:TextView
    private var people=emptyList<JSONObject>();private val selected=linkedSetOf<String>()
    private var group:JSONObject?=null;private var name:FormUi.Field?=null;private var search:FormUi.Field?=null
    private var list:LinearLayout?=null;private var create:TextView?=null
    private val dialogs=mutableSetOf<AlertDialog>()
    private fun valid()=session.isNotEmpty()&&Hub.token==session&&Hub.me.optString("id")==owner&&!isFinishing&&!isDestroyed
    override fun finish(){super.finish();Motion.activityClose(this)}
    override fun onResume(){super.onResume();if(!valid())finish()}
    override fun onDestroy(){dialogs.toList().forEach{it.dismiss()};dialogs.clear();super.onDestroy()}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);if(!valid()||gid.isNotEmpty()&&!ChatAddress.group(gid)){finish();return}
        if(state?.getString("owner")==owner){creation=state.getString("creation")?:creation;selected.addAll(state.getStringArrayList("selected")?:emptyList())}
        window.statusBarColor=p.canvas;window.navigationBarColor=p.canvas;window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=u.col().apply{setBackgroundColor(p.canvas)}
        root.setOnApplyWindowInsetsListener{v,i->if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,b.bottom)}else v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i}
        val header=u.row().apply{setPadding(u.dp(4),0,u.dp(12),0)};header.addView(u.icon("back","返回"){finish()},u.lp(48,48));header.addView(u.text(if(gid.isEmpty())"创建群聊"else"群资料",18f,bold=true),LinearLayout.LayoutParams(0,-2,1f));root.addView(header,u.lp())
        content=u.col().apply{setPadding(u.dp(if(u.compact)10 else 20),u.dp(8),u.dp(if(u.compact)10 else 20),u.dp(24))}
        root.addView(ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        status=u.text("",12f,p.accent).apply{tag="group.status";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;visibility=View.GONE};content.addView(status,u.lp())
        if(gid.isEmpty()){
            name=u.Field("群名称","给群聊起个名字","group.name",maximum=30).also{it.value(if(state?.getString("owner")==owner)state.getString("name")?:"新群聊"else"新群聊");content.addView(it.view,u.lp())}
            search=u.Field("选择好友","搜索昵称或弥星号","group.search",maximum=80).also{content.addView(it.view,u.lp())}
            content.addView(u.text("普通好友群聊 · 最多20人（含你）",11f,p.muted),u.lp());u.gap(content,8)
            list=u.col().also{content.addView(it,u.lp())}
            create=u.button("选择好友后创建","group.create"){createGroup()}.apply{isEnabled=false;alpha=.5f};root.addView(create,u.lp().apply{setMargins(u.dp(12),u.dp(6),u.dp(12),u.dp(8))})
            search!!.edit.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){renderPicker()};override fun afterTextChanged(s:Editable?){}})
            loadPeople{renderPicker()}
        }else reload()
        setContentView(root);root.requestApplyInsets()
    }
    override fun onSaveInstanceState(out:Bundle){out.putString("owner",owner);out.putString("creation",creation);out.putStringArrayList("selected",ArrayList(selected));out.putString("name",name?.value());super.onSaveInstanceState(out)}
    private fun task(work:()->JSONObject,done:(JSONObject)->Unit){
        if(busy||!valid())return;busy=true;status.text="正在处理…";status.visibility=View.VISIBLE;create?.isEnabled=false
        Hub.job({check(valid());work()},{if(valid()){busy=false;status.visibility=View.GONE;done(it);updateCreate()}},{if(valid()){busy=false;status.text=it;status.visibility=View.VISIBLE;updateCreate()}})
    }
    private fun loadPeople(done:()->Unit){task({Hub.api("GET","/api/contacts")}){result->
        val a=result.getJSONArray("contacts");people=(0 until a.length()).map{a.getJSONObject(it)}.filter{it.optString("state")=="accepted"&&!it.optBoolean("bot")&&!it.optBoolean("group")&&!it.optBoolean("blocked")}.sortedBy{it.optString("nickname")}
        selected.retainAll(people.map{it.getString("id")}.toSet());done()
    }}
    private fun portrait(user:JSONObject):View=FrameLayout(this).apply{
        background=BrandUi.rounded(this@GroupActivity,p.soft,22);clipToOutline=true
        addView(u.text(user.optString("nickname").take(1),17f,p.accent,true).apply{gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
        val image=ImageView(this@GroupActivity).apply{scaleType=ImageView.ScaleType.CENTER_CROP;visibility=View.GONE};addView(image,FrameLayout.LayoutParams(-1,-1));AvatarImages.load(user.optString("avatar_id"),image)
    }
    private fun updateCreate(){create?.apply{isEnabled=!busy&&selected.isNotEmpty();alpha=if(isEnabled)1f else .5f;text=if(selected.isEmpty())"选择好友后创建"else"创建群聊（${selected.size+1}人）"}}
    private fun renderPicker(){
        val parent=list?:return;parent.removeAllViews();val q=search?.value()?.trim().orEmpty()
        val shown=people.filter{q.isEmpty()||it.optString("nickname").contains(q,true)||it.optString("id").contains(q)}
        if(shown.isEmpty())parent.addView(u.text(if(people.isEmpty())"还没有可邀请的普通好友，请先添加并通过好友验证。"else"没有匹配的好友",13f,p.muted),u.lp())
        for(person in shown){val id=person.getString("id");val row=u.row().apply{tag="group.member.$id";minimumHeight=u.dp(60);setPadding(u.dp(4),u.dp(8),u.dp(4),u.dp(8));isFocusable=true}
            row.addView(portrait(person),u.lp(38,38));row.addView(u.text(person.optString("nickname"),14f).apply{setPadding(u.dp(10),0,u.dp(6),0);maxLines=1;ellipsize=TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f))
            val check=CheckBox(this).apply{isChecked=id in selected;contentDescription="选择 ${person.optString("nickname")}";buttonTintList=ColorStateList.valueOf(p.accent)}
            val toggle={if(!busy){if(id in selected)selected.remove(id)else if(selected.size<19)selected.add(id)else Toast.makeText(this,"最多选择19位好友",Toast.LENGTH_SHORT).show();check.isChecked=id in selected;updateCreate()};Unit}
            row.setOnClickListener{toggle()};check.setOnClickListener{toggle()};row.addView(check,u.lp(44,44));parent.addView(row,u.lp())
        };updateCreate()
    }
    private fun createGroup(){
        val value=name?.value()?.trim().orEmpty();if(value.isEmpty()){name?.fail("请输入群名称");return};if(selected.isEmpty())return
        val payload=JSONObject().put("name",value).put("members",JSONArray(selected.toList())).put("client_id",creation)
        task({Hub.api("POST","/api/groups",payload)}){result->setResult(RESULT_OK,Intent().putExtra("group",result.getJSONObject("group").toString()));finish()}
    }
    private fun reload(){task({Hub.api("GET","/api/groups/$gid")}){group=it.getJSONObject("group");renderInfo()}}
    private fun addAction(parent:LinearLayout,title:String,key:String,action:()->Unit){parent.addView(u.button(title,key,false){if(!busy&&valid())action()},u.lp().apply{topMargin=u.dp(8)})}
    private fun confirm(title:String,message:String,action:()->Unit){val d=AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确定"){_,_->if(valid()&&!busy)action()}.create();dialogs.add(d);d.setOnDismissListener{dialogs.remove(d)};d.show()}
    private fun mutate(action:String,extra:JSONObject=JSONObject(),exit:Boolean=false){
        val g=group?:return;val body=JSONObject(extra.toString()).put("action",action).put("epoch",g.getInt("epoch"))
        task({Hub.api("POST","/api/groups/$gid/members",body)}){if(exit){GroupDirectory.removed(gid,g.getInt("epoch")+1);setResult(RESULT_OK,Intent().putExtra("left",gid));finish()}else{setResult(RESULT_OK);reload()}}
    }
    private fun renderInfo(){
        val g=group?:return;content.removeAllViews();content.addView(status,u.lp());val title=u.card()
        title.addView(u.text(g.getString("nickname"),22f,bold=true),u.lp());u.gap(title,8);title.addView(u.text("${g.optInt("member_count")}人 · 加密群聊",12f,p.muted),u.lp());content.addView(title,u.lp())
        u.section(content,"群成员")
        val members=g.getJSONArray("members")
        for(n in 0 until members.length()){val person=members.getJSONObject(n);val id=person.getString("id");val row=u.row().apply{minimumHeight=u.dp(56);tag="group.info.member.$id";setPadding(u.dp(4),u.dp(6),u.dp(4),u.dp(6))};row.addView(portrait(person),u.lp(36,36));row.addView(u.text(person.optString("nickname")+if(id==g.getString("owner"))" · 群主"else"",14f).apply{setPadding(u.dp(10),0,0,0);maxLines=1;ellipsize=TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f));row.setOnClickListener{if(!busy&&valid()&&id!=owner){if(g.optBoolean("is_owner"))PopupMenu(this,row).apply{menu.add("查看资料");menu.add("移出群聊");menu.add("转让群主");setOnMenuItemClickListener{item->when(item.title.toString()){"移出群聊"->confirm("移出群聊？",person.optString("nickname")){mutate("remove",JSONObject().put("member",id))};"转让群主"->confirm("转让群主？","将群管理权限转交给${person.optString("nickname")}"){mutate("transfer",JSONObject().put("member",id))};else->startActivity(Intent(this@GroupActivity,PeopleActivity::class.java).putExtra("peer",id))};true};show()}else startActivity(Intent(this,PeopleActivity::class.java).putExtra("peer",id))}};content.addView(row,u.lp())}
        if(g.optBoolean("is_owner")){
            addAction(content,"邀请好友","group.invite"){loadPeople{inviteDialog()}}
            addAction(content,"修改群名称","group.rename"){
                val edit=EditText(this).apply{setText(g.getString("nickname"));filters=arrayOf(InputFilter.LengthFilter(30));isSingleLine=true}
                val d=AlertDialog.Builder(this).setTitle("群名称").setView(edit).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->val text=edit.text.toString().trim();if(text.isNotEmpty())task({Hub.api("PATCH","/api/groups/$gid",JSONObject().put("name",text))}){group=it.getJSONObject("group");setResult(RESULT_OK);renderInfo()}}.create();dialogs.add(d);d.setOnDismissListener{dialogs.remove(d)};d.show()
            }
            addAction(content,"解散群聊","group.dissolve"){confirm("解散群聊？","所有成员将无法继续在此群聊发送消息。"){mutate("dissolve",exit=true)}}
        }else addAction(content,"退出群聊","group.leave"){confirm("退出群聊？","退出后将不再接收此群的新消息。"){mutate("leave",exit=true)}}
        u.gap(content,16);content.addView(u.text("普通好友群聊，最多20人。群内暂不支持群通话；可从成员资料发起私聊。",11f,p.muted),u.lp())
    }
    private fun inviteDialog(){
        val g=group?:return;val members=g.getJSONArray("members");val existing=(0 until members.length()).map{members.getJSONObject(it).getString("id")}.toSet();val options=people.filter{it.getString("id") !in existing}
        if(options.isEmpty()){status.text="没有可邀请的新好友";status.visibility=View.VISIBLE;return}
        val checked=linkedSetOf<String>();val d=AlertDialog.Builder(this).setTitle("邀请好友").setMultiChoiceItems(options.map{it.optString("nickname")}.toTypedArray(),BooleanArray(options.size)){dialog,index,on->val id=options[index].getString("id");if(on){if(checked.size+existing.size<20)checked.add(id)else{(dialog as AlertDialog).listView.setItemChecked(index,false);Toast.makeText(this,"群聊最多20人",Toast.LENGTH_SHORT).show()}}else checked.remove(id)}.setNegativeButton("取消",null).setPositiveButton("邀请"){_,_->if(checked.isNotEmpty())mutate("invite",JSONObject().put("members",JSONArray(checked.toList())))}.create();dialogs.add(d);d.setOnDismissListener{dialogs.remove(d)};d.show()
    }
}
