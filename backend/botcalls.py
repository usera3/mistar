"""Realtime bot call control over REST; signed Webhooks carry events, never media.

The developer runs the WebRTC endpoint. Personal key ownership and call acceptance
stay in the native app. Calls are ephemeral, bounded and tied to one bot process.
"""
import asyncio
import json
import re
import secrets
import uuid
from aiohttp import web
from botcrypto import compact, units


class BotCalls:
    MAX_SECONDS=1800
    LEASE_MS=30000
    def __init__(self,c):
        self.c=c;self.wake=asyncio.Event()
        c['db'].executescript('''
        CREATE TABLE IF NOT EXISTS bot_call_permissions(owner TEXT NOT NULL,bot TEXT NOT NULL REFERENCES bots(user_id),allowed INTEGER NOT NULL,PRIMARY KEY(owner,bot));
        CREATE TABLE IF NOT EXISTS bot_call_history(call_id TEXT PRIMARY KEY,bot TEXT NOT NULL REFERENCES bots(user_id),peer TEXT NOT NULL,client_id TEXT,created INTEGER NOT NULL,ended INTEGER NOT NULL DEFAULT 0,UNIQUE(bot,client_id));
        CREATE INDEX IF NOT EXISTS bot_call_history_time ON bot_call_history(created);
        CREATE TABLE IF NOT EXISTS bot_call_events(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,bot TEXT NOT NULL REFERENCES bots(user_id),call_id TEXT NOT NULL,kind TEXT NOT NULL,body TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,due INTEGER NOT NULL,expires INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS bot_call_events_due ON bot_call_events(due,seq);
        ''')
        if 'calls_enabled' not in {r['name'] for r in c['db'].execute('PRAGMA table_info(bots)')}:
            c['db'].execute('ALTER TABLE bots ADD COLUMN calls_enabled INTEGER NOT NULL DEFAULT 0');c['db'].commit()

    def bot_for(self,call):
        return next((uid for uid in (call['caller'],call['callee']) if self.c['bots'].get(uid)),None)

    def enabled(self,uid):
        b=self.c['bots'].get(uid)
        return bool(b and b['enabled'] and b['verified'] and b['calls_enabled'])

    def permission(self,owner,bot):
        if not self.c['social'].accepted(owner,bot):return False
        r=self.c['row']('SELECT allowed FROM bot_call_permissions WHERE owner=? AND bot=?',(owner,bot))
        return bool(r['allowed']) if r else True

    async def permissions(self,request):
        uid=request['uid'];peer=request.match_info['peer']
        self.c['friend'](uid,peer)
        if self.c['bots'].get(uid) or not self.c['bots'].get(peer):self.c['reject']('仅个人账号可设置机器人来电权限',403)
        p=await request.json();allowed=p.get('allowed')
        if not isinstance(allowed,bool):self.c['reject']('来电权限格式不正确')
        self.c['limited']('bot-call-permission:'+uid,30,3600)
        self.c['write']('INSERT INTO bot_call_permissions VALUES(?,?,?) ON CONFLICT(owner,bot) DO UPDATE SET allowed=excluded.allowed',(uid,peer,int(allowed)))
        if not allowed:
            active=self.c['call_for'](uid)
            if active and active['caller']==peer:await self.c['finish_call'](active,'cancelled',uid)
        return web.json_response({'allowed':allowed})

    def check_invite(self,caller,peer,video=False,invite_id=''):
        bot=caller if self.c['bots'].get(caller) else peer if self.c['bots'].get(peer) else None
        if not bot:return
        if self.c['bots'].get(caller) and self.c['bots'].get(peer):self.c['reject']('暂不支持机器人之间通话',403)
        if not self.enabled(bot):self.c['reject']('机器人尚未启用通话或回调不可用',409)
        if caller==bot:
            if not self.permission(peer,bot):self.c['reject']('对方尚未允许机器人来电',403)
            self.c['limited']('bot-dial:'+bot,2,60)
            self.c['limited']('bot-dial-peer:'+bot+':'+peer,3,600)
            self.c['limited']('bot-dial-day:'+bot,50,86400)
        self.c['limited']('bot-call-start:global',20,60)
        if sum(self.bot_for(c) is not None for c in self.c['calls'].values())>=2:self.c['call_busy']('机器人通话线路繁忙，请稍后重试',peer,video,invite_id)
        if self.c['row']('SELECT count(*) AS n FROM bot_call_history WHERE bot=?',(bot,))['n']>=1000 or self.c['row']('SELECT count(*) AS n FROM bot_call_history')['n']>=5000:self.c['reject']('通话记录额度已满，请稍后重试',429)
        self.capacity(bot,1024)

    def record(self,call,p):
        bot=self.bot_for(call)
        if not bot:return
        peer=call['callee'] if call['caller']==bot else call['caller']
        if call['caller']==bot:call['bot_session']=p['bot_session']
        self.c['write']('INSERT INTO bot_call_history(call_id,bot,peer,client_id,created) VALUES(?,?,?,?,?)',
            (call['id'],bot,peer,p.get('client_id') if call['caller']==bot else None,self.c['now']()))

    def capacity(self,bot,size):
        own=self.c['row']('SELECT count(*) AS n,coalesce(sum(length(body)),0) AS bytes FROM bot_call_events WHERE bot=?',(bot,))
        total=self.c['row']('SELECT count(*) AS n,coalesce(sum(length(body)),0) AS bytes FROM bot_call_events')
        if own['n']>=128 or total['n']>=512 or own['bytes']+size>2*1024*1024 or total['bytes']+size>8*1024*1024:
            self.c['reject']('机器人通话事件队列繁忙',429)

    def validate_signal(self,signal):
        if not isinstance(signal,dict):self.c['reject']('通话信令格式不正确')
        kind=signal.get('type')
        if kind not in ('offer','answer','candidate','restart'):self.c['reject']('通话信令类型不正确')
        if kind in ('offer','answer') and (not isinstance(signal.get('sdp'),str) or not 1<=units(signal['sdp'])<=65536):self.c['reject']('SDP 格式或大小不正确')
        if kind=='candidate':
            index=signal.get('sdpMLineIndex')
            if not isinstance(signal.get('candidate'),str) or units(signal['candidate'])>4096 or not isinstance(signal.get('sdpMid'),str) or units(signal['sdpMid'])>128 or not isinstance(index,int) or isinstance(index,bool) or not 0<=index<=32:self.c['reject']('ICE 候选格式不正确')
        if len(compact(signal).encode())>200000:self.c['reject']('通话信令过大',413)

    async def publish(self,uid,event):
        if not self.c['bots'].get(uid):return
        kind=event['event'];call=event.get('call') or self.c['calls'].get(event.get('call_id',''))
        cid=call['id'] if call else event.get('call_id','')
        if not cid:return
        if kind=='call.ended':
            self.c['write']('UPDATE bot_call_history SET ended=? WHERE call_id=? AND bot=?',(self.c['now'](),cid,uid))
            self.c['write']('DELETE FROM bot_call_events WHERE bot=? AND call_id=?',(uid,cid))
        elif not call or uid not in (call['caller'],call['callee']):return
        data=dict(event)
        try:
            if kind=='call.signal':
                data['signal']=self.c['bots'].crypto.open_signal(uid,call,event['signal'])
                self.validate_signal(data['signal'])
            eid=str(uuid.uuid4());payload={'id':eid,'event':kind,'bot_id':uid,**{k:v for k,v in data.items() if k!='event'}}
            wrapped=self.c['bots'].crypto.wrap(uid+':call-event:'+eid,payload)
            self.capacity(uid,len(wrapped))
            expires=self.c['now']()+(60000 if kind=='call.ended' else 30000)
            if call and not call.get('accepted'):expires=min(expires,call['created']+45000)
            self.c['write']('INSERT INTO bot_call_events(id,bot,call_id,kind,body,due,expires) VALUES(?,?,?,?,?,?,?)',(eid,uid,cid,kind,wrapped,self.c['now'](),expires))
            self.wake.set()
        except Exception:
            if kind!='call.ended' and call:await self.c['finish_call'](call,'failed')
            if kind=='call.signal':self.c['reject']('无法验证或转交通话信令',409)

    async def stop(self,uid,reason='ended'):
        call=self.c['call_for'](uid)
        if call:await self.c['finish_call'](call,reason,uid)

    async def tick(self):
        for call in list(self.c['calls'].values()):
            bot=self.bot_for(call)
            if not bot:continue
            if not self.enabled(bot):await self.c['finish_call'](call,'offline');continue
            if call.get('accepted') and (call.get('bot_deadline',call['accepted_at']+self.LEASE_MS)<self.c['now']() or self.c['now']()-call['accepted_at']>self.MAX_SECONDS*1000):await self.c['finish_call'](call,'offline')

    def session(self,p):
        value=p.get('session_id','')
        if not isinstance(value,str) or not re.fullmatch(r'[a-f0-9-]{36}',value) or str(uuid.UUID(value))!=value:self.c['reject']('需要本次媒体进程的 session_id UUID')
        return value

    def access(self,uid,cid):
        h=self.c['row']('SELECT * FROM bot_call_history WHERE call_id=? AND bot=?',(cid,uid))
        if not h:self.c['reject']('通话不存在',404)
        call=self.c['calls'].get(cid)
        return call,h

    async def invite(self,request):
        self.c['bots'].current(request);uid=request['uid'];p=await request.json();peer=str(p.get('recipient',''));cid=p.get('client_id')
        if not isinstance(cid,str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,80}',cid):self.c['reject']('client_id 格式不正确')
        session=self.session(p)
        old=self.c['row']('SELECT * FROM bot_call_history WHERE bot=? AND client_id=?',(uid,cid))
        if old:
            if old['peer']!=peer:self.c['reject']('通话发送标识已用于其他联系人',409)
            call=self.c['calls'].get(old['call_id'])
            if call and call.get('bot_session')!=session:self.c['reject']('通话已由另一个机器人进程处理',409)
            return web.json_response({'call':self.c['call_public'](call) if call else None,'call_id':old['call_id'],'ended':call is None})
        if not isinstance(p.get('video',False),bool):self.c['reject']('通话类型格式不正确')
        call=await self.c['signaling'](uid,{'event':'call.invite','peer':peer,'video':p.get('video',False),'invite_id':str(uuid.uuid4()),'bot_session':session,'client_id':cid})
        return web.json_response({'call':self.c['call_public'](call),'call_id':call['id']})

    async def action(self,request):
        self.c['bots'].current(request);uid=request['uid'];cid=request.match_info['cid'];action=request.match_info['action'];p=await request.json()
        if action not in ('accept','reject','busy','end','signal','connected','heartbeat'):self.c['reject']('通话操作不存在',404)
        call,h=self.access(uid,cid)
        if call is None:
            if action in ('end','reject','busy'):return web.json_response({'ok':True,'ended':True,'call_id':cid})
            self.c['reject']('通话已结束',409)
        self.c['friend'](uid,h['peer']);self.c['bots'].ready(uid)
        if not self.enabled(uid):self.c['reject']('机器人通话未启用',409)
        if action in ('accept','signal','connected','heartbeat'):
            session=self.session(p)
            if action=='accept':
                if uid!=call['callee']:self.c['reject']('只有被叫可以接听',403)
                if call.get('bot_session') and call['bot_session']!=session:self.c['reject']('已由另一个机器人进程接听',409)
                call['bot_session']=session
            elif call.get('bot_session')!=session:self.c['reject']('通话属于另一个媒体进程',409)
        if action=='busy':
            await self.c['finish_call'](call,'busy',uid)
        elif action=='heartbeat':
            self.c['limited']('bot-heartbeat:'+uid,12,60)
            if not call.get('accepted'):self.c['reject']('通话尚未接听',409)
        elif action=='signal':
            if not call.get('accepted'):self.c['reject']('通话尚未接听',409)
            signal=p.get('signal');self.validate_signal(signal)
            await self.c['signaling'](uid,{'event':'call.signal','call_id':cid,'signal':self.c['bots'].crypto.seal_signal(uid,call,signal)})
        elif not (action=='accept' and call['accepted']):
            await self.c['signaling'](uid,{'event':'call.'+action,'call_id':cid})
        if call.get('accepted'):call['bot_deadline']=self.c['now']()+self.LEASE_MS
        return web.json_response({'ok':True,'call':self.c['call_public'](call) if cid in self.c['calls'] else None})

    async def state(self,request):
        self.c['limited']('bot-call-state:'+request['uid'],30,60)
        return await self.c['callstate'].state(request)

    async def ice(self,request):
        if not self.c['call_for'](request['uid']):self.c['reject']('请先发起或接听通话',409)
        return await self.c['ice'](request)

    async def deliver(self,item):
        b=self.c['bots'].get(item['bot']);call=self.c['calls'].get(item['call_id'])
        if not b or not b['verified'] or (item['kind']!='call.ended' and (not call or not self.enabled(item['bot']))):
            self.c['write']('DELETE FROM bot_call_events WHERE id=?',(item['id'],));return
        revision=b['revision']
        try:
            payload=self.c['bots'].crypto.unwrap(item['bot']+':call-event:'+item['id'],item['body'])
            status,_=await self.c['bots'].post(b,payload)
        except Exception:status=0
        current=self.c['bots'].get(item['bot'])
        if not current or current['revision']!=revision:return
        if 200<=status<300:self.c['write']('DELETE FROM bot_call_events WHERE id=?',(item['id'],))
        else:
            attempts=item['attempts']+1
            if attempts>=6:
                self.c['write']('DELETE FROM bot_call_events WHERE id=?',(item['id'],))
                if call:await self.c['finish_call'](call,'offline')
            else:self.c['write']('UPDATE bot_call_events SET attempts=?,due=? WHERE id=?',(attempts,self.c['now']()+min(4000,500*2**attempts),item['id']))

    async def worker(self):
        while True:
            try:
                self.wake.clear()
                self.c['write']('DELETE FROM bot_call_events WHERE expires<?',(self.c['now'](),))
                self.c['write']('DELETE FROM bot_call_history WHERE created<? AND ended>0',(self.c['now']()-86400000,))
                item=self.c['row']('SELECT e.* FROM bot_call_events e WHERE NOT EXISTS(SELECT 1 FROM bot_call_events earlier WHERE earlier.bot=e.bot AND earlier.call_id=e.call_id AND earlier.seq<e.seq) ORDER BY e.due,e.seq LIMIT 1')
                if item and item['due']<=self.c['now']():await self.deliver(item);continue
                timeout=min(10,max(.05,(item['due']-self.c['now']())/1000)) if item else 10
                try:await asyncio.wait_for(self.wake.wait(),timeout)
                except asyncio.TimeoutError:pass
            except Exception:
                self.c['db'].rollback();await asyncio.sleep(2)

    async def lifecycle(self,app):
        # An old SDP must never resurrect media after service/process restart.
        self.c['write']("DELETE FROM bot_call_events WHERE kind!='call.ended'")
        self.c['write']('UPDATE bot_call_history SET ended=? WHERE ended=0',(self.c['now'](),))
        task=asyncio.create_task(self.worker())
        yield
        task.cancel()
        try:await task
        except asyncio.CancelledError:pass

    def routes(self,app):
        app.cleanup_ctx.append(self.lifecycle)
        app.add_routes([web.post('/api/bot/calls',self.invite),web.post('/api/bot/calls/{cid}/{action}',self.action),
            web.get('/api/bot/call',self.state),web.get('/api/bot/ice',self.ice),web.post('/api/relationships/{peer}/bot-calls',self.permissions)])
