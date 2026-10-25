"""Server-owned membership orders. Legacy Epay MD5 is confined to the gateway boundary."""
import asyncio
import hashlib
import hmac
import json
import os
import re
import secrets
import uuid
from urllib.parse import parse_qsl, urlsplit

from aiohttp import ClientSession, ClientTimeout, DummyCookieJar, TCPConnector, web

MIB = 1024 * 1024
DAY = 86400000
PLANS = {
    'month': {'id':'month', 'name':'月卡', 'cents':990, 'days':30},
    'quarter': {'id':'quarter', 'name':'季卡', 'cents':2500, 'days':90},
    'year': {'id':'year', 'name':'年卡', 'cents':8800, 'days':365},
}
CALLBACK_KEYS = {'pid','trade_no','out_trade_no','type','name','money','trade_status','param','sign_type','sign'}


def money(cents):
    return f'{cents//100}.{cents%100:02d}'


def cents(value):
    if not isinstance(value,str) or not re.fullmatch(r'(?:0|[1-9][0-9]{0,6})(?:\.[0-9]{1,2})?',value):
        raise ValueError('Invalid money')
    whole,_,fraction=value.partition('.')
    return int(whole)*100+int((fraction+'00')[:2])


def sign(fields, key):
    canonical='&'.join(k+'='+fields[k] for k in sorted(fields) if k not in ('sign','sign_type') and fields[k]!='')
    # MD5 is required by the pinned gateway's epay-md5-v1 contract, not used for chat crypto.
    return hashlib.md5((canonical+key).encode('utf-8'),usedforsecurity=False).hexdigest()


class Membership:
    def __init__(self,core):
        self.c=core;self.db=core['db'];self.session=None;self.transport=None;self.creating=set();self.refreshing=set();self.notifying={}
        self.gateway=os.environ.get('MISTAR_PAY_GATEWAY','https://pay.claudcode.xyz').rstrip('/')
        self.pid=os.environ.get('MISTAR_PAY_PID','')
        self.key=os.environ.get('MISTAR_PAY_KEY','')
        self.keys=[s for s in (self.key,os.environ.get('MISTAR_PAY_PREVIOUS_KEY','')) if s]
        self.notify_url=os.environ.get('MISTAR_PAY_NOTIFY_URL','https://api.claudcode.xyz/xingyu/payments/notify')
        self.return_url=os.environ.get('MISTAR_PAY_RETURN_URL','https://api.claudcode.xyz/xingyu/payments/return')
        self.enabled=os.environ.get('MISTAR_PAY_ENABLED','0')=='1'
        for value in (self.gateway,self.notify_url,self.return_url):
            p=urlsplit(value)
            if p.scheme!='https' or not p.hostname or p.username or p.password or p.fragment or p.query or p.port not in (None,443):
                raise ValueError('Payment endpoint must be a fixed HTTPS URL')
        if urlsplit(self.gateway).path:raise ValueError('Gateway must be an HTTPS origin')
        if self.pid and not re.fullmatch(r'[A-Za-z0-9_-]{1,32}',self.pid):raise ValueError('Invalid payment merchant')
        self.gate=asyncio.Semaphore(2)
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS memberships(owner TEXT PRIMARY KEY REFERENCES users(id),expires INTEGER NOT NULL,updated INTEGER NOT NULL,notified INTEGER NOT NULL DEFAULT 0);
        CREATE INDEX IF NOT EXISTS membership_expiry ON memberships(expires,notified);
        CREATE TABLE IF NOT EXISTS membership_orders(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES users(id),client TEXT NOT NULL,
          plan TEXT NOT NULL,plan_name TEXT NOT NULL,days INTEGER NOT NULL,cents INTEGER NOT NULL,pid TEXT NOT NULL,subject TEXT NOT NULL,
          nonce TEXT NOT NULL,notify_url TEXT NOT NULL,return_url TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,
          state TEXT NOT NULL,trade_no TEXT UNIQUE,pay_url TEXT NOT NULL DEFAULT '',checked INTEGER NOT NULL DEFAULT 0,paid INTEGER NOT NULL DEFAULT 0,
          granted_until INTEGER NOT NULL DEFAULT 0,UNIQUE(owner,client));
        CREATE INDEX IF NOT EXISTS membership_order_owner ON membership_orders(owner,created DESC,id DESC);
        ''')
        self.db.commit()

    def reject(self,text,status=400):self.c['reject'](text,status)
    def ready(self):return bool(self.enabled and self.pid and self.key)
    def row(self,order):return self.c['row']('SELECT * FROM membership_orders WHERE id=?',(order,))
    def active(self,uid):
        r=self.c['row']('SELECT expires FROM memberships WHERE owner=?',(uid,))
        return bool(r and r['expires']>self.c['now']())
    def media_limit(self,uid):return max(self.c['guard'].MAX_USER_MEDIA,500*MIB) if self.active(uid) else self.c['guard'].MAX_USER_MEDIA
    def daily_limit(self,uid,fresh):return 256*MIB if self.active(uid) else (32 if fresh else 128)*MIB
    def status(self,uid):
        r=self.c['row']('SELECT expires FROM memberships WHERE owner=?',(uid,))
        active=bool(r and r['expires']>self.c['now']())
        u=self.c['row']('SELECT created FROM users WHERE id=?',(uid,))
        fresh=bool(u and self.c['now']()-u['created']<DAY)
        return {'active':active,'expires_at':r['expires'] if r else 0,'server_time':self.c['now'](),
            'storage_bytes':self.media_limit(uid),'upload_daily_bytes':self.daily_limit(uid,fresh),'badge':active,'avatar_frame':active}

    def public_order(self,r):
        return {k:r[k] for k in ('id','plan','plan_name','days','cents','created','state','pay_url','paid','granted_until')}

    async def catalog(self,request):
        return web.json_response({'enabled':self.ready(),'plans':list(PLANS.values()),'membership':self.status(request['uid']),
            'benefits':{'storage_bytes':500*MIB,'upload_daily_bytes':256*MIB,'badge':True,'avatar_frame':True},'auto_renew':False})

    async def orders(self,request):
        uid=request['uid'];self.c['limited']('membership-list:'+uid,30,60)
        before=request.query.get('before','')
        match=re.fullmatch(r'([0-9]{1,16}):(ms_[a-f0-9]{32})',before) if before else None
        if before and not match:self.reject('分页参数不正确')
        cursor=(int(match[1]),match[2]) if match else (9223372036854775807,'~')
        rows=self.c['rows']('SELECT * FROM membership_orders WHERE owner=? AND (created,id)<(?,?) ORDER BY created DESC,id DESC LIMIT 31',(uid,)+cursor)
        next_cursor=str(rows[29]['created'])+':'+rows[29]['id'] if len(rows)>30 else ''
        return web.json_response({'orders':[self.public_order(r) for r in rows[:30]],'next':next_cursor,'membership':self.status(uid)})

    def owned(self,request):
        r=self.row(request.match_info['oid'])
        if not r or r['owner']!=request['uid']:self.reject('订单不存在',404)
        return r

    async def detail(self,request):
        r=self.owned(request)
        return web.json_response({'order':self.public_order(r),'membership':self.status(r['owner'])})

    def create_fields(self,r):
        return {'pid':r['pid'],'type':'alipay','out_trade_no':r['id'],'notify_url':r['notify_url'],'return_url':r['return_url'],
            'name':r['subject'],'money':money(r['cents']),'param':r['nonce'],'sign_type':'MD5'}

    async def exchange(self,path,fields):
        payload=dict(fields);payload['sign']=sign(payload,self.key)
        async with self.gate:
            if self.transport is not None:return await self.transport(path,payload)
            if self.session is None:raise RuntimeError('Payment client not started')
            async with self.session.post(self.gateway+path,data=payload,allow_redirects=False,headers={'Accept-Encoding':'identity'}) as response:
                if response.status!=200 or response.headers.get('Content-Encoding','identity') not in ('','identity'):
                    raise ValueError('Payment provider unavailable')
                raw=await response.content.read(16385)
                if len(raw)>16384:raise ValueError('Large payment response')
                value=json.loads(raw)
                if not isinstance(value,dict) or type(value.get('code')) is not int or value['code']!=1:raise ValueError('Payment request rejected')
                return value

    def checkout(self,value):
        if not isinstance(value,str) or len(value)>2048:raise ValueError('Invalid checkout')
        p=urlsplit(value)
        if p.scheme!='https' or p.netloc!=urlsplit(self.gateway).netloc or p.query or p.fragment or not re.fullmatch(r'/pay/[A-Za-z0-9._~-]{16,1024}',p.path):
            raise ValueError('Invalid checkout origin')
        return value

    async def create(self,request):
        uid=request['uid']
        if not self.ready():self.reject('支付服务维护中，请稍后再试',503)
        if self.c['bots'].public(uid).get('bot'):self.reject('请使用个人账号购买会员',403)
        p=await request.json()
        if set(p)!={'plan_id','client_id'} or not isinstance(p.get('plan_id'),str) or p['plan_id'] not in PLANS:self.reject('请选择有效的会员方案')
        try:client=str(uuid.UUID(p['client_id']))
        except (ValueError,TypeError,AttributeError):self.reject('下单标识不正确')
        r=self.c['row']('SELECT * FROM membership_orders WHERE owner=? AND client=?',(uid,client))
        if r and r['plan']!=p['plan_id']:self.reject('请重新选择会员方案',409)
        if not r:
            self.c['limited']('membership-create:'+uid,10,86400)
            self.c['limited']('membership-create-global',300,86400)
            pending=self.c['row']("SELECT count(*) AS n FROM membership_orders WHERE owner=? AND state IN ('creating','pending','failed') AND created>?",(uid,self.c['now']()-15*60000))['n']
            if pending>=3:self.reject('还有未处理的订单，请先查看订单记录',429)
            if self.c['row']('SELECT count(*) AS n FROM membership_orders')['n']>=50000:self.reject('订单服务暂不可用，请联系管理员',503)
            plan=PLANS[p['plan_id']];oid='ms_'+uuid.uuid4().hex;t=self.c['now']()
            self.db.execute('INSERT INTO membership_orders(id,owner,client,plan,plan_name,days,cents,pid,subject,nonce,notify_url,return_url,created,updated,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                (oid,uid,client,plan['id'],plan['name'],plan['days'],plan['cents'],self.pid,'弥星会员·'+plan['name'],secrets.token_hex(16),self.notify_url,self.return_url,t,t,'creating'))
            self.db.commit();r=self.row(oid)
        return await self.create_existing(r)

    async def retry(self,request):
        r=self.owned(request)
        if not self.ready():self.reject('支付服务维护中，请稍后再试',503)
        self.c['limited']('membership-retry:'+r['owner'],10,60)
        return await self.create_existing(r)

    async def create_existing(self,r):
        if r['state'] not in ('creating','failed') or r['id'] in self.creating:
            return web.json_response({'order':self.public_order(self.row(r['id'])),'membership':self.status(r['owner'])})
        if r['pid']!=self.pid:self.reject('旧订单不能重试，请联系管理员',409)
        self.creating.add(r['id'])
        try:
            result=await self.exchange('/mapi.php',self.create_fields(r))
            trade=result.get('trade_no','');url=self.checkout(result.get('payurl'))
            if not isinstance(trade,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}',trade):raise ValueError('Invalid trade number')
            old=self.row(r['id'])
            if old['trade_no'] and old['trade_no']!=trade:raise ValueError('Trade number changed')
            self.db.execute("UPDATE membership_orders SET trade_no=?,pay_url=?,state=CASE WHEN state='paid' THEN state ELSE 'pending' END,updated=? WHERE id=?",(trade,url,self.c['now'](),r['id']));self.db.commit()
        except (asyncio.CancelledError,Exception) as error:
            if self.row(r['id'])['state']!='paid':
                self.db.execute("UPDATE membership_orders SET state='failed',updated=? WHERE id=? AND state IN ('creating','failed')",(self.c['now'](),r['id']));self.db.commit()
                if isinstance(error,asyncio.CancelledError):raise
                self.reject('支付平台暂不可用，订单已保留，可稍后重试',502)
        finally:self.creating.discard(r['id'])
        return web.json_response({'order':self.public_order(self.row(r['id'])),'membership':self.status(r['owner'])})

    def validate_payment(self,r,trade,amount,nonce):
        if not isinstance(trade,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}',trade) or r['trade_no'] and r['trade_no']!=trade:
            self.reject('支付订单无法核验',409)
        try:amount=cents(amount)
        except ValueError:self.reject('支付金额无法核验',400)
        if amount!=r['cents'] or not isinstance(nonce,str) or not hmac.compare_digest(nonce,r['nonce']):self.reject('支付内容无法核验',409)
        other=self.c['row']('SELECT id FROM membership_orders WHERE trade_no=? AND id<>?',(trade,r['id']))
        if other:self.reject('支付交易重复',409)

    def settle(self,r,trade):
        # One transaction records payment AND entitlement. No network await is allowed inside it.
        with self.db:
            current=self.row(r['id'])
            if current['state']=='paid':return False
            now=self.c['now']();previous=self.c['row']('SELECT expires FROM memberships WHERE owner=?',(r['owner'],))
            until=max(now,previous['expires'] if previous else 0)+r['days']*DAY
            self.db.execute("UPDATE membership_orders SET state='paid',trade_no=?,paid=?,updated=?,granted_until=? WHERE id=?",(trade,now,now,until,r['id']))
            self.db.execute('INSERT INTO memberships(owner,expires,updated,notified) VALUES(?,?,?,0) ON CONFLICT(owner) DO UPDATE SET expires=excluded.expires,updated=excluded.updated,notified=0',(r['owner'],until,now))
        return True

    async def changed(self,uid,oid=''):
        # Payment ACK and the call-maintenance loop must not wait behind slow sockets.
        # Entitlement is already durable; reconnect or an authenticated read catches up.
        if uid in self.notifying or len(self.notifying)>=16:return
        async def send():
            try:
                try:await asyncio.wait_for(self.c['profile_updated'](uid),5)
                except (asyncio.TimeoutError,ConnectionError,RuntimeError):pass
                try:await asyncio.wait_for(self.c['emit'](uid,{'event':'membership.updated','membership':self.status(uid),'order_id':oid}),3)
                except (asyncio.TimeoutError,ConnectionError,RuntimeError):pass
            finally:self.notifying.pop(uid,None)
        self.notifying[uid]=asyncio.create_task(send())

    async def notify(self,request):
        if not self.keys:self.reject('支付验证暂不可用',503)
        if request.content_type!='application/x-www-form-urlencoded':self.reject('回调格式不正确',415)
        raw=await request.read()
        if len(raw)>8192:self.reject('回调内容过大',413)
        try:
            pairs=parse_qsl(raw.decode('utf-8'),keep_blank_values=True,strict_parsing=True,max_num_fields=16)
            fields=dict(pairs)
        except (ValueError,UnicodeError):self.reject('回调格式不正确')
        if len(pairs)!=len(fields) or set(fields)!=CALLBACK_KEYS:self.reject('回调字段不正确')
        # The legacy canonical format is unescaped. Fixed key set + delimiter-free values prevents ambiguous re-splitting.
        if any(len(v.encode())>512 or any(c in v for c in ('&','=','\x00','\r','\n')) for v in fields.values()):self.reject('回调字段不正确')
        signature=fields['sign']
        if fields['sign_type']!='MD5' or not re.fullmatch('[a-fA-F0-9]{32}',signature):self.reject('回调无法验证',403)
        valid=0
        for key in self.keys:valid|=int(hmac.compare_digest(sign(fields,key),signature.lower()))
        if not valid:self.reject('回调无法验证',403)
        r=self.row(fields['out_trade_no'])
        if not r:self.reject('订单不存在',404)
        if fields['pid']!=r['pid'] or fields['type']!='alipay' or fields['name']!=r['subject'] or fields['trade_status']!='TRADE_SUCCESS':self.reject('支付状态无法核验',409)
        self.validate_payment(r,fields['trade_no'],fields['money'],fields['param'])
        if self.settle(r,fields['trade_no']):await self.changed(r['owner'],r['id'])
        return web.Response(text='success',headers={'Cache-Control':'no-store'})

    async def refresh(self,request):
        r=self.owned(request);uid=r['owner'];self.c['limited']('membership-refresh:'+uid,12,60)
        if r['state']=='paid' or r['id'] in self.refreshing or self.c['now']()-r['checked']<10000:
            return web.json_response({'order':self.public_order(r),'membership':self.status(uid)})
        if not self.key or r['pid']!=self.pid:self.reject('订单查询维护中，请稍后再试',503)
        self.refreshing.add(r['id']);self.db.execute('UPDATE membership_orders SET checked=? WHERE id=?',(self.c['now'](),r['id']));self.db.commit()
        try:
            result=await self.exchange('/api.php',{'act':'order','pid':r['pid'],'out_trade_no':r['id'],'sign_type':'MD5'})
            if result.get('out_trade_no')!=r['id']:raise ValueError('Wrong order response')
            self.validate_payment(r,result.get('trade_no'),result.get('money'),result.get('param'))
            if type(result.get('status')) is not int or result['status'] not in (0,1):raise ValueError('Invalid status')
            if result['status']==1:
                if result.get('trade_status')!='TRADE_SUCCESS':raise ValueError('Wrong payment status')
                if self.settle(r,result['trade_no']):await self.changed(uid,r['id'])
            elif result.get('trade_status')=='TRADE_CLOSED':
                self.db.execute("UPDATE membership_orders SET state='closed',updated=? WHERE id=? AND state<>'paid'",(self.c['now'](),r['id']));self.db.commit()
        except self.c['ApiError']:raise
        except Exception:self.reject('暂时无法确认到账，请稍后再查；不会重复下单',502)
        finally:self.refreshing.discard(r['id'])
        return web.json_response({'order':self.public_order(self.row(r['id'])),'membership':self.status(uid)})

    async def expire(self):
        for r in self.c['rows']('SELECT owner FROM memberships WHERE expires<=? AND notified=0 LIMIT 20',(self.c['now'](),)):
            self.db.execute('UPDATE memberships SET notified=1 WHERE owner=? AND expires<=?',(r['owner'],self.c['now']()));self.db.commit()
            await self.changed(r['owner'])

    async def lifecycle(self,app):
        self.session=ClientSession(timeout=ClientTimeout(total=12,connect=4),connector=TCPConnector(limit=2),cookie_jar=DummyCookieJar(),trust_env=False,auto_decompress=False)
        yield
        tasks=list(self.notifying.values())
        for task in tasks:task.cancel()
        if tasks:await asyncio.gather(*tasks,return_exceptions=True)
        await self.session.close();self.session=None

    def routes(self,app):
        app.add_routes([web.get('/api/membership',self.catalog),web.get('/api/payments/orders',self.orders),web.post('/api/payments/orders',self.create),
            web.get('/api/payments/orders/{oid}',self.detail),web.post('/api/payments/orders/{oid}/retry',self.retry),web.post('/api/payments/orders/{oid}/refresh',self.refresh),
            web.post('/api/payments/notify',self.notify)])
        app.cleanup_ctx.append(self.lifecycle)
