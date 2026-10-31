"""Bounded resource use. All admission and quota checks are enforced on the server."""
import asyncio
import hashlib
import ipaddress
import json
import math
import os
import re
import shutil
import time
from contextlib import asynccontextmanager
from aiohttp import web

MIB = 1024 * 1024


class WorkGate:
    def __init__(self, reject, active=1, queued=3):
        self.reject, self.maximum = reject, active + queued
        self.sem, self.pending = asyncio.Semaphore(active), 0

    async def __aenter__(self):
        if self.pending >= self.maximum: self.reject('服务器繁忙，请稍后重试', 429)
        self.pending += 1
        try: await asyncio.wait_for(self.sem.acquire(), 3)
        except asyncio.TimeoutError:
            self.pending -= 1
            self.reject('服务器繁忙，请稍后重试', 429)
        except BaseException:
            self.pending -= 1
            raise
        return self

    async def __aexit__(self, *args):
        self.sem.release()
        self.pending -= 1


class Defense:
    def __init__(self, core):
        self.c, self.db = core, core['db']
        self.http_active, self.ws_active = 0, 0
        self.http_ips, self.ws_ips = {}, {}
        self.reserved = {}
        self.IDLE_TIMEOUT = 15
        self.JSON_TIMEOUT = 12
        self.UPLOAD_TIMEOUT = 180
        self.MAX_HTTP = 16
        self.MAX_WS = 32
        self.MAX_WS_USER = 2
        self.MAX_UPLOADS = 3
        self.MAX_MEDIA_FILES = 20000
        self.MAX_USER_MEDIA = int(os.environ.get('USER_STORAGE_MB', '200')) * MIB
        self.MIN_FREE = int(os.environ.get('MIN_FREE_MB', '64')) * MIB
        self.MAX_USER_MESSAGES = 64 * MIB
        self.MAX_ALL_MESSAGES = 128 * MIB
        self.MAX_SIGNAL_BYTES_HOUR=8*MIB
        self.MAX_GLOBAL_SIGNAL_BYTES_HOUR=128*MIB
        self.trust_proxy = os.environ.get('QINGLIAO_TRUST_PROXY', '0') == '1'
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS abuse_buckets(id TEXT PRIMARY KEY,tokens REAL NOT NULL,updated REAL NOT NULL,expires REAL NOT NULL);
        CREATE INDEX IF NOT EXISTS abuse_expiry ON abuse_buckets(expires);
        CREATE INDEX IF NOT EXISTS uploads_owner ON uploads(owner);
        CREATE INDEX IF NOT EXISTS photos_owner ON post_photos(owner);
        CREATE TABLE IF NOT EXISTS message_usage(owner TEXT PRIMARY KEY,bytes INTEGER NOT NULL,n INTEGER NOT NULL);
        INSERT OR IGNORE INTO message_usage SELECT sender,sum(length(CAST(text AS BLOB))),count(*) FROM messages GROUP BY sender;
        CREATE TRIGGER IF NOT EXISTS message_usage_add AFTER INSERT ON messages BEGIN
          INSERT INTO message_usage VALUES(NEW.sender,length(CAST(NEW.text AS BLOB)),1)
          ON CONFLICT(owner) DO UPDATE SET bytes=bytes+length(CAST(NEW.text AS BLOB)),n=n+1;
        END;
        CREATE TRIGGER IF NOT EXISTS message_usage_remove AFTER DELETE ON messages BEGIN
          UPDATE message_usage SET bytes=max(0,bytes-length(CAST(OLD.text AS BLOB))),n=max(0,n-1) WHERE owner=OLD.sender;
        END;
        CREATE TABLE IF NOT EXISTS upload_usage(owner TEXT NOT NULL,day INTEGER NOT NULL,bytes INTEGER NOT NULL,PRIMARY KEY(owner,day));
        CREATE TABLE IF NOT EXISTS content_usage(owner TEXT NOT NULL,day INTEGER NOT NULL,bytes INTEGER NOT NULL,PRIMARY KEY(owner,day));
        ''')
        cols={r['name'] for r in self.db.execute('PRAGMA table_info(users)')}
        if 'created_ip' not in cols: self.db.execute("ALTER TABLE users ADD COLUMN created_ip TEXT NOT NULL DEFAULT ''")
        if 'email_key' not in cols: self.db.execute("ALTER TABLE users ADD COLUMN email_key TEXT NOT NULL DEFAULT ''")
        self.db.execute('CREATE INDEX IF NOT EXISTS users_creation_ip ON users(created_ip,created)')
        self.db.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_email_key ON users(email_key) WHERE email_key<>''")
        self.db.commit()
        current=self.db.execute('PRAGMA page_count').fetchone()[0]
        page=self.db.execute('PRAGMA page_size').fetchone()[0]
        self.db.execute('PRAGMA max_page_count='+str(max(current,256*MIB//page)))

    def reject(self, text, status=400): self.c['reject'](text, status)

    def clear(self):
        # Used only by isolated fixtures; there is no administrative reset API.
        self.c['write']('DELETE FROM abuse_buckets')

    def limited(self, key, count, seconds, amount=1):
        ident=hashlib.sha256(key.encode()).hexdigest()
        t=time.time()
        item=self.c['row']('SELECT * FROM abuse_buckets WHERE id=?', (ident,))
        if item:
            tokens=min(count, item['tokens']+max(0,t-item['updated'])*count/seconds)
        else:
            if self.c['row']('SELECT count(*) AS n FROM abuse_buckets')['n'] >= 20000:
                self.c['write']('DELETE FROM abuse_buckets WHERE expires<?', (t,))
                if self.c['row']('SELECT count(*) AS n FROM abuse_buckets')['n'] >= 20000:
                    self.reject('服务器繁忙，请稍后重试', 429)
            tokens=count
        if tokens < amount:
            e=self.c['ApiError']('操作太频繁，请稍后再试', 429)
            e.retry_after=min(86400,max(1,math.ceil((amount-tokens)*seconds/count)))
            raise e
        self.c['write']('INSERT OR REPLACE INTO abuse_buckets VALUES(?,?,?,?)', (ident,tokens-amount,max(t,item['updated']) if item else t,t+max(seconds*2,120)))

    def client_ip(self, request):
        peer=request.remote or 'unknown'
        if self.trust_proxy and peer in ('127.0.0.1','::1'):
            candidate=request.headers.get('X-Real-IP','')
            try: peer=str(ipaddress.ip_address(candidate))
            except ValueError: pass
        try:
            addr=ipaddress.ip_address(peer)
            if addr.version==6: return str(ipaddress.ip_network(str(addr)+'/64',strict=False))
        except ValueError: pass
        return peer

    @staticmethod
    def email_key(email):
        name,domain=email.lower().rsplit('@',1)
        if domain in ('gmail.com','googlemail.com'):
            name=name.split('+',1)[0].replace('.','');domain='gmail.com'
        return name+'@'+domain

    def email_taken(self, email):
        return self.c['row']('SELECT id FROM users WHERE email=? OR email_key=?', (email,self.email_key(email)))

    def registration(self, request):
        if os.environ.get('QINGLIAO_REGISTRATION_OPEN','1')!='1': self.reject('注册暂未开放',403)
        t=self.c['now']()
        if self.c['row']('SELECT count(*) AS n FROM users')['n'] >= 500: self.reject('注册名额暂满',429)
        if self.c['row']('SELECT count(*) AS n FROM users WHERE created>?',(t-86400000,))['n'] >= 30: self.reject('今日注册名额暂满，请明天再试',429)
        ip=request['client_ip']
        if self.c['row']('SELECT count(*) AS n FROM users WHERE created_ip=? AND created>?',(ip,t-3600000))['n'] >= 3: self.reject('当前网络注册过于频繁，请稍后再试',429)
        if self.c['row']('SELECT count(*) AS n FROM users WHERE created_ip=? AND created>?',(ip,t-86400000))['n'] >= 10: self.reject('当前网络今日注册次数已达上限',429)

    def migrate_emails(self):
        # Preserve all legacy accounts even if an old installation allowed aliases.
        for user in self.c['rows']("SELECT id,email FROM users WHERE email<>'' AND email_key=''"):
            key=self.email_key(user['email'])
            if not self.c['row']('SELECT id FROM users WHERE email_key=?',(key,)):
                self.db.execute('UPDATE users SET email_key=? WHERE id=?',(key,user['id']))
        self.db.commit()

    @web.middleware
    async def admission(self, request, handler):
        ip=self.client_ip(request);request['client_ip']=ip
        if request.headers.get('Content-Encoding','identity').lower() not in ('','identity'):self.reject('不支持压缩请求体',415)
        self.limited('http-ip:'+ip,600,60)
        self.limited('http-global',2400,60)
        is_ws=request.path=='/ws'
        active=self.ws_active if is_ws else self.http_active
        maximum=self.MAX_WS if is_ws else self.MAX_HTTP
        ips=self.ws_ips if is_ws else self.http_ips
        if active>=maximum or ips.get(ip,0)>=(8 if is_ws else 8): self.reject('连接过多，请稍后重试',429)
        limit=32*1024
        if request.path in ('/api/messages','/api/bot/messages'):limit=900000
        elif re.fullmatch(r'/api/groups/g_[a-f0-9]{32}/messages',request.path):limit=6*1024*1024
        elif re.fullmatch(r'/api/bot/calls/[a-f0-9-]{36}/signal',request.path):limit=300000
        elif request.path=='/api/uploads':limit=self.c['MAX_FILE']+65536
        elif request.path=='/api/bot/uploads':limit=self.c['MAX_FILE']
        elif request.path=='/api/post-media':limit=(5 if request.query.get('kind')=='voice' else 25)*MIB
        elif re.fullmatch(r'/api/post-media/[a-f0-9]{32}/cover',request.path):limit=self.c['MAX_AVATAR']
        elif request.path in ('/api/me/avatar','/api/post-photos'):limit=self.c['MAX_AVATAR']
        elif request.path=='/api/payments/notify':limit=8192
        if request.content_length is not None and request.content_length>limit: self.reject('请求内容过大',413)
        request['body_limit']=limit
        if is_ws:
            self.limited('ws-connect:'+ip,15,60);self.ws_active+=1
        else:self.http_active+=1
        ips[ip]=ips.get(ip,0)+1
        try: return await handler(request)
        finally:
            ips[ip]-=1
            if not ips[ip]:ips.pop(ip,None)
            if is_ws:self.ws_active-=1
            else:self.http_active-=1

    async def chunks(self, request, limit):
        start=time.monotonic();size=0
        try:
            while True:
                remaining=self.UPLOAD_TIMEOUT-(time.monotonic()-start)
                if remaining<=0:self.reject('上传超时，请重试',408)
                chunk=await asyncio.wait_for(request.content.read(65536),min(self.IDLE_TIMEOUT,remaining))
                if self.c.get('moderation'):self.c['moderation'].check_request(request)
                if not chunk:break
                size+=len(chunk);request['body_bytes']=size
                if size>limit:self.reject('上传文件过大',413)
                yield chunk
        except asyncio.TimeoutError:self.reject('上传超时，请重试',408)

    def user_media(self, uid):
        return self.c['row']('SELECT (SELECT coalesce(sum(size),0) FROM uploads WHERE owner=?)+(SELECT coalesce(sum(size),0) FROM avatars WHERE owner=?)+(SELECT coalesce(sum(size),0) FROM post_photos WHERE owner=?) AS bytes',(uid,uid,uid))['bytes']

    def media_limit(self,uid):
        return self.c['membership'].media_limit(uid) if self.c.get('membership') else self.MAX_USER_MEDIA

    def daily_limit(self,uid,fresh):
        return self.c['membership'].daily_limit(uid,fresh) if self.c.get('membership') else (32 if fresh else 128)*MIB

    def storage_check(self, request, amount, credit=0):
        if self.c.get('moderation'):self.c['moderation'].check_request(request)
        uid=request['uid'];own=id(request)
        pending=sum(v[1] for k,v in self.reserved.items() if k!=own)
        user_pending=sum(v[1] for k,v in self.reserved.items() if k!=own and v[0]==uid)
        if self.c['storage_used']()+pending+amount-credit>self.c['MAX_STORAGE']: self.reject('服务器附件空间已满',507)
        if self.user_media(uid)+user_pending+amount-credit>self.media_limit(uid):self.reject('账号附件空间已满，请清理附件或检查会员额度',507)
        if shutil.disk_usage(self.c['DATA']).free<self.MIN_FREE+amount:self.reject('服务器剩余空间不足',507)

    @asynccontextmanager
    async def reserve_upload(self, request):
        uid=request['uid'];limit=request['body_limit'];amount=request.content_length if request.content_length is not None else limit
        if request.path not in ('/api/uploads','/api/bot/uploads','/api/post-media'):amount=limit
        if request.path=='/api/bot/uploads':amount+=65536
        if len(self.reserved)>=self.MAX_UPLOADS or sum(v[0]==uid for v in self.reserved.values())>=2:self.reject('同时上传的文件过多，请稍后重试',429)
        count=self.c['row']('SELECT (SELECT count(*) FROM uploads)+(SELECT count(*) FROM avatars)+(SELECT count(*) FROM post_photos) AS n')['n']
        if count+len(self.reserved)>=self.MAX_MEDIA_FILES:self.reject('服务器附件数量已达上限',507)
        self.storage_check(request,amount)
        if self.c['row']('SELECT (SELECT count(*) FROM uploads WHERE owner=?)+(SELECT count(*) FROM post_photos WHERE owner=?) AS n',(uid,uid))['n']>=5000:self.reject('账号附件数量已达上限',507)
        day=int(time.time()//86400)
        user=self.c['row']('SELECT created FROM users WHERE id=?',(uid,))
        fresh=self.c['now']()-user['created']<86400000
        member=bool(self.c.get('membership') and self.c['membership'].active(uid))
        maximum=self.daily_limit(uid,fresh)
        new_budget=fresh and not member
        used=self.c['row']('SELECT bytes FROM upload_usage WHERE owner=? AND day=?',(uid,day))
        if (used['bytes'] if used else 0)+amount>maximum:self.reject('今日上传额度已用完，请明天再试',429)
        if new_budget:
            shared=self.c['row']("SELECT bytes FROM upload_usage WHERE owner='new-accounts' AND day=?",(day,))
            if (shared['bytes'] if shared else 0)+amount>128*MIB:self.reject('新账号今日上传额度已用完，请明天再试',429)
        self.reserved[id(request)]=(uid,amount)
        for key in (uid,'new-accounts') if new_budget else (uid,):
            self.db.execute('INSERT INTO upload_usage VALUES(?,?,?) ON CONFLICT(owner,day) DO UPDATE SET bytes=bytes+excluded.bytes',(key,day,amount))
        self.db.commit()
        try:
            yield
            refund=max(0,amount-request.get('body_bytes',amount))
            if refund:
                for key in (uid,'new-accounts') if new_budget else (uid,):
                    self.db.execute('UPDATE upload_usage SET bytes=max(0,bytes-?) WHERE owner=? AND day=?',(refund,key,day))
                self.db.commit()
        finally:self.reserved.pop(id(request),None)

    @web.middleware
    async def body(self, request, handler):
        if request.get('uid'):self.limited('http-user:'+request['uid'],300,60)
        cleanup=request.path in ('/api/logout','/api/storage/cleanup','/api/blocks','/api/friends/remove') or bool(re.fullmatch(r'/api/(?:conversations/[0-9]{8}/clear|messages/[a-f0-9-]{36}/withdraw)',request.path))
        cleanup=cleanup or bool(re.fullmatch(r'/api/developer/bots/[0-9]{8}(?:/rotate)?',request.path))
        cleanup=cleanup or bool(re.fullmatch(r'/api/bot/calls/[a-f0-9-]{36}/(?:end|reject|heartbeat)',request.path)) or bool(re.fullmatch(r'/api/relationships/[0-9]{8}/bot-calls',request.path))
        if request.method in ('POST','PUT','PATCH') and not cleanup:
            if shutil.disk_usage(self.c['DATA']).free<self.MIN_FREE+MIB:self.reject('服务器剩余空间不足',507)
        binary=(request.path in ('/api/uploads','/api/bot/uploads','/api/post-photos','/api/post-media','/api/me/avatar') or re.fullmatch(r'/api/post-media/[a-f0-9]{32}/cover',request.path)) and request.method in ('POST','PUT')
        if binary:
            async with self.reserve_upload(request):return await handler(request)
        if request.method in ('POST','PUT','PATCH'):
            async def read():
                data=bytearray()
                async for part in self.chunks(request,request['body_limit']):data.extend(part)
                request._read_bytes=bytes(data)
                if data and request.path!='/api/payments/notify':
                    parsed=json.loads(data)
                    if not isinstance(parsed,dict):self.reject('请求格式不正确')
            try:await asyncio.wait_for(read(),self.JSON_TIMEOUT)
            except asyncio.TimeoutError:self.reject('请求超时，请重试',408)
        return await handler(request)

    def message_space(self, uid, size, fail=True):
        user=self.c['row']('SELECT bytes,n FROM message_usage WHERE owner=?',(uid,)) or {'bytes':0,'n':0}
        total=self.c['row']('SELECT coalesce(sum(bytes),0) AS n FROM message_usage')['n']
        ok=user['bytes']+size<=self.MAX_USER_MESSAGES and user['n']<100000 and total+size<=self.MAX_ALL_MESSAGES
        if not ok and fail:self.reject('聊天记录空间已满，请联系管理员处理',507)
        return ok

    def content_charge(self, uid, size):
        size=max(256,size);day=int(time.time()//86400)
        fresh=self.c['now']()-self.c['row']('SELECT created FROM users WHERE id=?',(uid,))['created']<86400000
        keys=[(uid,(2 if fresh else 8)*MIB)]
        if fresh:keys.append(('new-accounts',32*MIB))
        for key,maximum in keys:
            used=self.c['row']('SELECT bytes FROM content_usage WHERE owner=? AND day=?',(key,day))
            if (used['bytes'] if used else 0)+size>maximum:self.reject('今日内容额度已用完，请明天再试',429)
        for key,_ in keys:
            self.db.execute('INSERT INTO content_usage VALUES(?,?,?) ON CONFLICT(owner,day) DO UPDATE SET bytes=bytes+excluded.bytes',(key,day,size))
        self.db.commit()

    def reap(self):
        self.c['write']('DELETE FROM abuse_buckets WHERE expires<?',(time.time(),))
        self.c['write']('DELETE FROM upload_usage WHERE day<?',(int(time.time()//86400)-7,))
        self.c['write']('DELETE FROM content_usage WHERE day<?',(int(time.time()//86400)-7,))
        self.c['write']('DELETE FROM verifications WHERE created<?',(self.c['now']()-7*86400000,))
        self.c['write']('DELETE FROM message_usage WHERE n=0 AND owner NOT IN (SELECT id FROM users)')
        for folder,table in [('uploads','uploads'),('avatars','avatars'),('moments','post_photos')]:
            known={r['id'] for r in self.c['rows']('SELECT id FROM '+table)}
            for path in (self.c['DATA']/folder).iterdir():
                if path.is_symlink() or not path.is_file() or path.name in known:continue
                if re.fullmatch(r'(?:\.part-)?[a-f0-9]{32}',path.name) and path.stat().st_mtime<time.time()-300:path.unlink(missing_ok=True)
