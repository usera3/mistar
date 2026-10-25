"""Direct bot API + signed, durable Webhook delivery. No developer polling API."""
import asyncio
import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import socket
import time
import uuid
from urllib.parse import urlsplit

from aiohttp import web, ClientSession, ClientTimeout, TCPConnector, DummyCookieJar
from aiohttp.resolver import DefaultResolver
from botcrypto import BotCrypto, compact


def public_ip(value, own=''):
    try:
        ip = ipaddress.ip_address(value)
        return ip.is_global and not (ip.is_multicast or ip.is_reserved or ip.is_loopback or ip.is_link_local or
            (isinstance(ip, ipaddress.IPv6Address) and (ip.ipv4_mapped or ip.sixtofour or ip.teredo))) and value != own
    except ValueError: return False


class PublicResolver(DefaultResolver):
    def __init__(self, own): super().__init__(); self.own = own
    async def resolve(self, host, port=0, family=socket.AF_INET):
        values = await asyncio.wait_for(super().resolve(host, port, family), 2)
        if not values or any(not public_ip(p['host'], self.own) for p in values): raise OSError('Callback address rejected')
        return values


class Bots:
    def __init__(self, c):
        self.c = c; self.crypto = BotCrypto(c); self.wake = asyncio.Event(); self.http = None
        self.verifying = set(); self.media = asyncio.Semaphore(1)
        c['db'].executescript('''
        CREATE TABLE IF NOT EXISTS bots(user_id TEXT PRIMARY KEY REFERENCES users(id),owner TEXT NOT NULL REFERENCES users(id),
          description TEXT NOT NULL,token_hash TEXT NOT NULL UNIQUE,identity TEXT NOT NULL,enabled INTEGER NOT NULL,
          webhook TEXT NOT NULL DEFAULT '',secret TEXT NOT NULL DEFAULT '',verified INTEGER NOT NULL DEFAULT 0,
          revision INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL,last_status INTEGER NOT NULL DEFAULT 0);
        CREATE INDEX IF NOT EXISTS bots_owner ON bots(owner);
        CREATE TABLE IF NOT EXISTS bot_deliveries(id TEXT PRIMARY KEY,bot TEXT NOT NULL REFERENCES bots(user_id),
          message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,attempts INTEGER NOT NULL DEFAULT 0,
          due INTEGER NOT NULL,created INTEGER NOT NULL,failed INTEGER NOT NULL DEFAULT 0,UNIQUE(bot,message_id));
        CREATE INDEX IF NOT EXISTS bot_deliveries_due ON bot_deliveries(failed,due);
        CREATE TABLE IF NOT EXISTS bot_uploads(id TEXT PRIMARY KEY,bot TEXT NOT NULL REFERENCES bots(user_id),metadata TEXT NOT NULL);
        ''')

    def get(self, uid): return self.c['row']('SELECT * FROM bots WHERE user_id=?', (uid,))

    def public(self, uid):
        b = self.get(uid)
        return {'bot': True, 'bot_description': b['description'], 'bot_enabled': bool(b['enabled']),
                'bot_ready': bool(b['enabled'] and b['verified']), 'bot_calls_enabled':bool(b['calls_enabled']), 'bot_processing': 'server'} if b else {}

    def auth(self, token):
        if not isinstance(token, str) or not re.fullmatch(r'qlb_[A-Za-z0-9_-]{43}', token): self.c['reject']('机器人凭证无效', 401)
        b = self.c['row']('SELECT user_id FROM bots WHERE token_hash=? AND enabled=1', (hashlib.sha256(token.encode()).hexdigest(),))
        if not b: self.c['reject']('机器人凭证已撤销或机器人已停用', 401)
        self.c['moderation'].ensure_account(b['user_id'])
        return b['user_id']

    def authorize(self, request, token):
        uid = self.auth(token)
        allowed = (request.method=='POST' and (request.path in ('/api/bot/messages','/api/bot/uploads','/api/bot/calls') or bool(re.fullmatch(r'/api/bot/calls/[a-f0-9-]{36}/(?:accept|reject|busy|end|signal|connected|heartbeat)',request.path)))) or \
            (request.method=='GET' and (request.path in ('/api/bot/me','/api/bot/contacts','/api/bot/call','/api/bot/ice') or bool(re.fullmatch(r'/api/bot/files/[a-f0-9]{32}', request.path))))
        if not allowed: self.c['reject']('机器人凭证没有此权限', 403)
        request['bot_token'] = token
        return uid

    def current(self, request):
        if request.get('bot_token'): self.auth(request['bot_token'])

    def ready(self, uid):
        self.c['moderation'].ensure_account(uid)
        b = self.get(uid)
        if b and not (b['enabled'] and b['verified']): self.c['reject']('机器人未验证回调或已停用', 409)

    def owner(self, request, uid=None):
        owner = request['uid']
        if self.get(owner): self.c['reject']('请使用个人账号管理机器人', 403)
        if uid:
            b = self.get(uid)
            if not b or b['owner']!=owner: self.c['reject']('机器人不存在', 404)
            return b
        return owner

    def view(self, b):
        return dict(self.c['user_public'](b['user_id'], b['owner']), webhook=b['webhook'], webhook_verified=bool(b['verified']),
            created=b['created'], last_status=b['last_status'],
            pending=self.c['row']('SELECT count(*) AS n FROM bot_deliveries WHERE bot=? AND failed=0',(b['user_id'],))['n'],
            failed=self.c['row']('SELECT count(*) AS n FROM bot_deliveries WHERE bot=? AND failed=1',(b['user_id'],))['n'])

    def profile(self, p):
        name, desc = p.get('nickname',''), p.get('description','')
        if not isinstance(name,str) or not 1<=len(name.strip())<=20 or any(ord(ch)<32 for ch in name): self.c['reject']('名称需为 1–20 个字')
        if not isinstance(desc,str) or len(desc)>160 or any(ord(ch)<32 for ch in desc): self.c['reject']('介绍最多 160 个字，不含控制字符')
        return name.strip(), desc.strip()

    @staticmethod
    def credential():
        token = 'qlb_' + secrets.token_urlsafe(32)
        return token, hashlib.sha256(token.encode()).hexdigest()

    def url(self, value):
        if not isinstance(value,str) or not 1<=len(value)<=1000 or any(ord(c)<33 or ord(c)>126 for c in value): self.c['reject']('请输入公网 HTTPS 回调地址')
        p = urlsplit(value)
        if p.scheme!='https' or not p.hostname or p.username or p.password or p.fragment or p.query or p.port not in (None,443): self.c['reject']('回调必须使用 HTTPS 443，不含账号、查询参数或片段')
        host = p.hostname.lower()
        try: ipaddress.ip_address(host)
        except ValueError:
            if len(host)>253 or not re.fullmatch(r'(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?',host): self.c['reject']('回调域名格式不正确')
        else:
            if not public_ip(host,self.c['PUBLIC_IP']): self.c['reject']('回调不能访问内网或本服务地址')
        return value

    async def manage(self, request):
        owner = self.owner(request)
        if request.method=='GET': return web.json_response({'bots':[self.view(b) for b in self.c['rows']('SELECT * FROM bots WHERE owner=? ORDER BY created',(owner,))]})
        if not self.c['row']('SELECT email FROM users WHERE id=?',(owner,))['email']: self.c['reject']('请先验证账号邮箱',403)
        if self.c['row']('SELECT count(*) AS n FROM bots WHERE owner=?',(owner,))['n']>=3: self.c['reject']('每个账号最多创建 3 个机器人',429)
        if self.c['row']('SELECT count(*) AS n FROM bots')['n']>=50: self.c['reject']('当前机器人名额已满',429)
        name, desc = self.profile(await request.json())
        self.c['limited']('bot-create:'+owner,3,86400)
        private, public = self.crypto.create()
        uid = str(secrets.randbelow(90000000)+10000000)
        while self.c['row']('SELECT 1 FROM users WHERE id=?',(uid,)): uid=str(secrets.randbelow(90000000)+10000000)
        token, hashed = self.credential(); created = self.c['now']()
        wrapped = self.crypto.wrap(uid+':identity',private)
        with self.c['db']:
            self.c['db'].execute('INSERT INTO users(id,username,nickname,salt,password,created,password_kdf) VALUES(?,?,?,?,?,?,2)',(uid,'bot_'+secrets.token_hex(8),name,secrets.token_hex(16),secrets.token_hex(64),created))
            self.c['db'].execute('INSERT INTO bots(user_id,owner,description,token_hash,identity,enabled,created) VALUES(?,?,?,?,?,1,?)',(uid,owner,desc,hashed,wrapped,created))
            self.c['db'].execute('INSERT INTO crypto_identities VALUES(?,?,?,?,?)',(uid,public['key_id'],public['enc'],public['sig'],created))
        return web.json_response({'bot':self.view(self.get(uid)),'token':token},status=201)

    async def update(self, request):
        uid=request.match_info['uid']; b=self.owner(request,uid); p=await request.json()
        self.c['limited']('bot-manage:'+b['owner'],30,3600)
        name, desc=self.profile({'nickname':p.get('nickname',self.c['user_public'](uid)['nickname']),'description':p.get('description',b['description'])})
        enabled=p.get('enabled',bool(b['enabled']))
        if not isinstance(enabled,bool): self.c['reject']('启用状态格式不正确')
        calls_enabled=p.get('calls_enabled',bool(b['calls_enabled']))
        if not isinstance(calls_enabled,bool):self.c['reject']('通话能力格式不正确')
        with self.c['db']:
            self.c['db'].execute('UPDATE users SET nickname=? WHERE id=?',(name,uid))
            self.c['db'].execute('UPDATE bots SET description=?,enabled=?,calls_enabled=?,revision=revision+1 WHERE user_id=?',(desc,int(enabled),int(calls_enabled),uid))
        if not enabled or not calls_enabled:await self.c['botcalls'].stop(uid)
        self.wake.set(); await self.c['profile_updated'](uid)
        return web.json_response({'bot':self.view(self.get(uid))})

    async def rotate(self, request):
        uid=request.match_info['uid']; b=self.owner(request,uid)
        self.c['limited']('bot-rotate:'+b['owner'],10,3600)
        token, hashed=self.credential()
        self.c['write']('UPDATE bots SET token_hash=? WHERE user_id=?',(hashed,uid))
        await self.c['botcalls'].stop(uid)
        return web.json_response({'bot':self.view(self.get(uid)),'token':token})

    async def configure(self, request):
        uid=request.match_info['uid']; b=self.owner(request,uid)
        self.c['limited']('bot-webhook:'+b['owner'],10,3600)
        url=self.url((await request.json()).get('url')); secret=secrets.token_urlsafe(32)
        self.c['write']('UPDATE bots SET webhook=?,secret=?,verified=0,revision=revision+1,last_status=0 WHERE user_id=?',(url,self.crypto.wrap(uid+':webhook',{'secret':secret}),uid))
        await self.c['botcalls'].stop(uid)
        self.wake.set(); await self.c['profile_updated'](uid)
        return web.json_response({'bot':self.view(self.get(uid)),'webhook_secret':secret})

    async def post(self, b, payload):
        self.url(b['webhook'])  # Literal IPs bypass aiohttp's resolver, so validate them here too.
        raw=compact(payload).encode(); timestamp=str(int(time.time())); event_id=payload['id']
        secret=self.crypto.unwrap(b['user_id']+':webhook',b['secret'])['secret']
        signature=hmac.new(secret.encode(),timestamp.encode()+b'.'+event_id.encode()+b'.'+raw,hashlib.sha256).hexdigest()
        async with self.http.post(b['webhook'],data=raw,headers={'Content-Type':'application/json; charset=utf-8',
            'X-Qingliao-Timestamp':timestamp,'X-Qingliao-Event-Id':event_id,'X-Qingliao-Signature':'sha256='+signature},allow_redirects=False) as response:
            data=await response.content.read(4097)
            if len(data)>4096: return 413, b''
            return response.status, data

    async def verify(self, request):
        uid=request.match_info['uid']; b=self.owner(request,uid)
        self.c['limited']('bot-verify:'+b['owner'],10,3600)
        if not b['enabled'] or not b['webhook']: self.c['reject']('请先启用机器人并配置回调',409)
        if uid in self.verifying or len(self.verifying)>=2: self.c['reject']('验证繁忙，请稍后重试',429)
        self.verifying.add(uid); challenge=secrets.token_urlsafe(24)
        try:
            try:
                status, data=await self.post(b,{'id':str(uuid.uuid4()),'event':'webhook.verify','bot_id':uid,'challenge':challenge})
                accepted=200<=status<300 and json.loads(data).get('challenge')==challenge
            except (Exception,): status,accepted=0,False
            current=self.get(uid)
            if not current or current['revision']!=b['revision']: self.c['reject']('配置已变化，请重新验证',409)
            self.c['write']('UPDATE bots SET verified=?,last_status=? WHERE user_id=?',(int(accepted),status,uid))
            if not accepted: self.c['reject']('回调验证失败，请检查 HTTPS、签名及 challenge 响应',400)
            self.wake.set(); await self.c['profile_updated'](uid)
            return web.json_response({'bot':self.view(self.get(uid))})
        finally: self.verifying.discard(uid)

    def reserve(self, peer):
        if not self.get(peer): return
        self.ready(peer)
        if self.c['row']('SELECT count(*) AS n FROM bot_deliveries WHERE bot=?',(peer,))['n']>=100 or self.c['row']('SELECT count(*) AS n FROM bot_deliveries')['n']>=500:
            self.c['reject']('机器人消息队列繁忙，请稍后再发',429)

    def enqueue(self, uid, peer, mid):
        if self.get(peer):
            self.c['db'].execute('INSERT INTO bot_deliveries(id,bot,message_id,due,created) VALUES(?,?,?,?,?)',(str(uuid.uuid4()),peer,mid,self.c['now'](),self.c['now']()))
            self.wake.set()

    def eligible(self, item):
        m=self.c['row']('SELECT * FROM messages WHERE id=?',(item['message_id'],))
        if not m or m['recipient']!=item['bot'] or m['type']!='encrypted': return None
        if not self.c['social'].accepted(item['bot'],m['sender']): return None
        if m['created']<=self.c['social'].through(item['bot'],m['sender']): return None
        if self.c['row']('SELECT 1 FROM message_hides WHERE owner=? AND message_id=?',(item['bot'],m['id'])): return None
        return m

    def exposed(self, message):
        f=message.get('file')
        if f: message=dict(message,file={k:f[k] for k in ('id','name','mime','size')},file_path='/api/bot/files/'+f['id'])
        return message

    async def deliver(self, item):
        b=self.get(item['bot']); m=self.eligible(item)
        if not m:
            self.c['write']('DELETE FROM bot_deliveries WHERE id=?',(item['id'],)); return
        if not b or not b['enabled'] or not b['verified']: return
        try:
            payload={'id':item['id'],'event':'message.created','bot_id':b['user_id'],'message':self.exposed(self.crypto.open(b['user_id'],m))}
            status,_=await self.post(b,payload)
        except Exception: status=0
        latest=self.get(b['user_id'])
        if not latest or latest['revision']!=b['revision']: return
        self.c['write']('UPDATE bots SET last_status=? WHERE user_id=?',(status,b['user_id']))
        if 200<=status<300: self.c['write']('DELETE FROM bot_deliveries WHERE id=?',(item['id'],))
        else:
            attempts=item['attempts']+1
            delay=(5,15,60,300,900,3600,21600,21600)[min(attempts-1,7)]
            self.c['write']('UPDATE bot_deliveries SET attempts=?,due=?,failed=? WHERE id=?',(attempts,self.c['now']()+delay*1000,int(attempts>=8),item['id']))

    async def retry(self, request):
        uid=request.match_info['uid']; b=self.owner(request,uid)
        self.c['limited']('bot-retry:'+b['owner'],10,3600)
        self.c['write']('UPDATE bot_deliveries SET attempts=0,failed=0,due=? WHERE bot=?',(self.c['now'](),uid)); self.wake.set()
        return web.json_response({'bot':self.view(self.get(uid))})

    async def worker(self):
        while True:
            try:
                self.wake.clear()
                self.c['write']('DELETE FROM bot_deliveries WHERE created<?',(self.c['now']()-86400000,))
                self.c['write']('DELETE FROM bot_uploads WHERE id NOT IN (SELECT id FROM uploads)')
                item=self.c['row']('SELECT d.* FROM bot_deliveries d JOIN bots b ON b.user_id=d.bot WHERE d.failed=0 AND b.enabled=1 AND b.verified=1 ORDER BY d.due LIMIT 1')
                if item and item['due']<=self.c['now']():
                    await self.deliver(item)
                    continue
                timeout=min(60,max(.05,(item['due']-self.c['now']())/1000)) if item else 60
                try: await asyncio.wait_for(self.wake.wait(),timeout)
                except asyncio.TimeoutError: pass
            except Exception:
                # Temporary disk/DB failure must not permanently kill delivery or amplify logs.
                self.c['db'].rollback()
                await asyncio.sleep(5)

    async def lifecycle(self, app):
        connector=TCPConnector(resolver=PublicResolver(self.c['PUBLIC_IP']),use_dns_cache=False,force_close=True,limit=2)
        self.http=ClientSession(connector=connector,timeout=ClientTimeout(total=6,connect=3,sock_read=3),trust_env=False,auto_decompress=False,cookie_jar=DummyCookieJar())
        task=asyncio.create_task(self.worker())
        yield
        task.cancel()
        try: await task
        except asyncio.CancelledError: pass
        await self.http.close()

    async def bot_me(self, request): return web.json_response(self.c['user_public'](request['uid'],request['uid']))
    async def bot_contacts(self, request): return await self.c['contacts'](request)

    async def send(self, request):
        self.current(request); uid=request['uid']; p=await request.json(); peer=str(p.get('recipient',''))
        self.c['friend'](uid,peer); self.ready(uid)
        if self.get(peer): self.c['reject']('暂不支持机器人之间互发消息',403)
        cid=p.get('client_id')
        if not isinstance(cid,str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,80}',cid): self.c['reject']('client_id 需为 1–80 位字母、数字或 ._:-')
        receipt,old=self.c['syncstate'].lookup(uid,cid)
        if receipt:
            if receipt['peer']!=peer:self.c['reject']('发送标识已用于其他聊天',409)
            return web.json_response({'id':receipt['message_id'],'client_id':cid,'created':receipt['created'],'removed':old is None})
        kind=p.get('type','text'); inner={'type':kind,'text':p.get('text','语音' if kind=='voice' else '')}; fid=''
        if kind in ('image','voice','file'):
            fid=p.get('file_id',''); uploaded=self.c['row']('SELECT b.metadata FROM bot_uploads b JOIN uploads u ON u.id=b.id WHERE b.id=? AND b.bot=? AND u.recipient=?',(fid,uid,peer))
            if not uploaded: self.c['reject']('请先上传此机器人发给该联系人的附件',403)
            inner['file']=self.crypto.unwrap(uid+':upload:'+fid,uploaded['metadata'])
            if kind=='image' and not inner['file']['mime'].startswith('image/'): self.c['reject']('请选择图片')
            if kind=='voice' and not inner['file']['mime'].startswith('audio/'): self.c['reject']('请选择音频')
        envelope=self.crypto.seal(uid,peer,cid,inner,fid)
        body={'recipient':peer,'client_id':cid,'type':'encrypted','envelope':envelope}
        if fid: body['file_id']=fid
        request._read_bytes=compact(body).encode()
        response=await self.c['send_message'](request); result=json.loads(response.body)
        return web.json_response({k:result[k] for k in ('id','recipient','client_id','created')})

    async def upload(self, request):
        self.current(request); uid=request['uid']; peer=request.query.get('recipient','')
        self.ready(uid); self.c['friend'](uid,peer)
        if self.get(peer): self.c['reject']('暂不支持机器人互发',403)
        self.c['limited']('upload:'+uid,60,3600)
        fid=secrets.token_hex(16); context='qingliao:attachment:1:'+str(uuid.uuid4()); key=self.crypto.stream_key()
        metadata={'id':fid,'name':request.query.get('name','file.bin'),'mime':request.headers.get('Content-Type','application/octet-stream').split(';')[0], 'size':1,'crypto':key,'context':context}
        self.crypto.validate({'type':'file','file':metadata},fid)
        path=self.c['DATA']/'uploads'/fid; tmp=self.c['DATA']/'uploads'/('.part-'+fid); total=0
        try:
            async with self.media:
                with tmp.open('wb') as destination:
                    with self.crypto.encrypting(destination,key,context) as output:
                        async for chunk in self.c['guard'].chunks(request,request['body_limit']):
                            total+=len(chunk)
                            if total>self.c['MAX_FILE']: self.c['reject']('单个文件不能超过 25 MB',413)
                            output.write(chunk)
                if not total: self.c['reject']('不能发送空文件')
                self.current(request); self.c['friend'](uid,peer); self.ready(uid)
                size=tmp.stat().st_size; self.c['guard'].storage_check(request,size)
                metadata['size']=total; wrapped=self.crypto.wrap(uid+':upload:'+fid,metadata)
                tmp.replace(path)
                with self.c['db']:
                    self.c['db'].execute('INSERT INTO uploads VALUES(?,?,?,?,?,?,?)',(fid,uid,peer,'encrypted.bin','application/x-qingliao-encrypted',size,self.c['now']()))
                    self.c['db'].execute('INSERT INTO bot_uploads VALUES(?,?,?)',(fid,uid,wrapped))
            return web.json_response({'id':fid,'name':metadata['name'],'mime':metadata['mime'],'size':total})
        except BaseException: path.unlink(missing_ok=True); raise
        finally: tmp.unlink(missing_ok=True)

    async def download(self, request):
        self.current(request); uid=request['uid']; fid=request.match_info['fid']
        m=self.c['row']('SELECT * FROM messages WHERE file_id=? AND (recipient=? OR sender=?) ORDER BY created DESC LIMIT 1',(fid,uid,uid))
        if not m: self.c['reject']('附件不存在或无权访问',404)
        peer=m['sender'] if m['recipient']==uid else m['recipient']; self.c['friend'](uid,peer)
        if m['created']<=self.c['social'].through(uid,peer) or self.c['row']('SELECT 1 FROM message_hides WHERE owner=? AND message_id=?',(uid,m['id'])): self.c['reject']('附件不存在',404)
        f=self.crypto.open(uid,m).get('file'); path=self.c['DATA']/'uploads'/fid
        if not f or not path.is_file(): self.c['reject']('附件不存在',404)
        self.c['guard'].limited('downloads:'+uid,512*1024*1024,86400,f['size'])
        async with asyncio.timeout(60), self.media:
            self.current(request)
            response=web.StreamResponse(headers={'Content-Type':'application/octet-stream','Content-Length':str(f['size']),'X-Content-Type-Options':'nosniff','Cache-Control':'no-store','Content-Disposition':'attachment'})
            await response.prepare(request); total=0
            with path.open('rb') as source, self.crypto.decrypting(source,f['crypto'],f['context']) as stream:
                while chunk:=stream.read(65536):
                    total+=len(chunk)
                    if total>f['size']:raise ValueError('Invalid attachment size')
                    await asyncio.wait_for(response.write(chunk),5)
            if total!=f['size']:raise ValueError('Invalid attachment size')
            await response.write_eof(); return response

    def routes(self, app):
        app.cleanup_ctx.append(self.lifecycle)
        app.add_routes([web.get('/api/developer/bots',self.manage),web.post('/api/developer/bots',self.manage),
            web.patch('/api/developer/bots/{uid}',self.update),web.post('/api/developer/bots/{uid}/rotate',self.rotate),
            web.put('/api/developer/bots/{uid}/webhook',self.configure),web.post('/api/developer/bots/{uid}/verify',self.verify),
            web.post('/api/developer/bots/{uid}/retry',self.retry),web.get('/api/bot/me',self.bot_me),web.get('/api/bot/contacts',self.bot_contacts),
            web.post('/api/bot/messages',self.send),web.post('/api/bot/uploads',self.upload),web.get('/api/bot/files/{fid}',self.download)])
