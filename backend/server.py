import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import sqlite3
import time
import uuid
from io import BytesIO
from pathlib import Path

from aiohttp import web, WSMsgType

DATA = Path(os.environ.get('QINGLIAO_DATA', './data'))
DATA.mkdir(parents=True, exist_ok=True)
(DATA / 'uploads').mkdir(exist_ok=True)
(DATA / 'avatars').mkdir(exist_ok=True)
(DATA / 'moments').mkdir(exist_ok=True)
db = sqlite3.connect(DATA / 'qingliao.sqlite3')
db.row_factory = sqlite3.Row
db.executescript('''
PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL,nickname TEXT NOT NULL,salt TEXT NOT NULL,password TEXT NOT NULL,created INTEGER NOT NULL,last_seen INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS friends(owner TEXT NOT NULL,peer TEXT NOT NULL,state TEXT NOT NULL,read_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(owner,peer));
CREATE TABLE IF NOT EXISTS uploads(id TEXT PRIMARY KEY,owner TEXT NOT NULL,recipient TEXT NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,size INTEGER NOT NULL,created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY,sender TEXT NOT NULL,recipient TEXT NOT NULL,type TEXT NOT NULL,text TEXT NOT NULL DEFAULT '',file_id TEXT,created INTEGER NOT NULL,client_id TEXT,UNIQUE(sender,client_id));
CREATE INDEX IF NOT EXISTS messages_pair ON messages(sender,recipient,created);
CREATE TABLE IF NOT EXISTS avatars(id TEXT PRIMARY KEY,owner TEXT UNIQUE NOT NULL,size INTEGER NOT NULL,created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS posts(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES users(id),text TEXT NOT NULL,created INTEGER NOT NULL,client_id TEXT NOT NULL,UNIQUE(owner,client_id));
CREATE INDEX IF NOT EXISTS posts_time ON posts(created DESC,id DESC);
CREATE TABLE IF NOT EXISTS post_photos(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES users(id),post_id TEXT REFERENCES posts(id) ON DELETE CASCADE,position INTEGER NOT NULL DEFAULT 0,size INTEGER NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,created INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS post_photos_post ON post_photos(post_id,position);
CREATE TABLE IF NOT EXISTS post_likes(post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,user_id TEXT NOT NULL REFERENCES users(id),PRIMARY KEY(post_id,user_id));
CREATE TABLE IF NOT EXISTS post_comments(id TEXT PRIMARY KEY,post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,user_id TEXT NOT NULL REFERENCES users(id),text TEXT NOT NULL,created INTEGER NOT NULL,client_id TEXT NOT NULL,UNIQUE(user_id,client_id));
CREATE INDEX IF NOT EXISTS post_comments_time ON post_comments(post_id,created DESC,id DESC);
''')
for table,columns in {
    'posts':{'audience':"TEXT NOT NULL DEFAULT 'friends'"},
    'post_photos':{'kind':"TEXT NOT NULL DEFAULT 'image'",'mime':"TEXT NOT NULL DEFAULT 'image/jpeg'",'duration':'INTEGER NOT NULL DEFAULT 0','parent_media':'TEXT REFERENCES post_photos(id) ON DELETE CASCADE'},
}.items():
    present={r['name'] for r in db.execute('PRAGMA table_info('+table+')')}
    for name,definition in columns.items():
        if name not in present:db.execute('ALTER TABLE '+table+' ADD COLUMN '+name+' '+definition)
db.execute('CREATE INDEX IF NOT EXISTS posts_audience_time ON posts(audience,created DESC,id DESC)')
db.execute('CREATE UNIQUE INDEX IF NOT EXISTS post_media_cover ON post_photos(parent_media) WHERE parent_media IS NOT NULL')
db.commit()
if 'last_seen' not in {r['name'] for r in db.execute('PRAGMA table_info(users)')}:
    db.execute('ALTER TABLE users ADD COLUMN last_seen INTEGER NOT NULL DEFAULT 0')
    db.commit()
if 'avatar_id' not in {r['name'] for r in db.execute('PRAGMA table_info(users)')}:
    db.execute("ALTER TABLE users ADD COLUMN avatar_id TEXT NOT NULL DEFAULT ''")
    db.commit()
online = {}
typing = {}
TYPING_TTL = 5
calls = {}
limits = {}
password_slots = asyncio.Semaphore(1)
avatar_slots = asyncio.Semaphore(1)
MAX_AVATAR = 2 * 1024 * 1024
MAX_FILE = 25 * 1024 * 1024
MAX_STORAGE = int(os.environ.get('MAX_STORAGE_MB', '1024')) * 1024 * 1024
TURN_SECRET = os.environ.get('TURN_SECRET', '')
PUBLIC_IP = os.environ.get('PUBLIC_IP', '47.245.2.254')


def now(): return int(time.time() * 1000)


def row(sql, args=()):
    r = db.execute(sql, args).fetchone()
    return dict(r) if r else None


def rows(sql, args=()): return [dict(r) for r in db.execute(sql, args).fetchall()]


def write(sql, args=()):
    db.execute(sql, args)
    db.commit()


class ApiError(Exception):
    def __init__(self, message, status=400): self.message, self.status = message, status


def reject(message, status=400): raise ApiError(message, status)


def call_busy(message,peer='',video=False,invite_id='',code='call_busy'):
    error=ApiError(message,409)
    error.details={'code':code,'peer':peer,'video':bool(video),'invite_id':str(invite_id)[:64]}
    raise error


def limited(key, count, seconds):
    guard.limited(key, count, seconds)


def user_public(uid, viewer=None):
    u = row('SELECT id,username,nickname,last_seen,avatar_id FROM users WHERE id=?', (uid,))
    if u:
        u.update(bots.public(uid))
        if 'membership' in globals():
            u['member']=membership.active(uid)
            if viewer==uid:u['membership']=membership.status(uid)
        u['online'] = bool(online.get(uid))
        if viewer==uid and 'moderation' in globals():
            staff=moderation.staff(uid)
            u['administrator']=bool(staff)
            u['admin_password_change_required']=bool(staff and staff['must_change'])
        if viewer != uid and not row("SELECT 1 FROM friends WHERE owner=? AND peer=? AND state='accepted'", (viewer, uid)):
            u['last_seen'] = 0
            u['online'] = False
        if viewer != uid and not row('SELECT 1 FROM friends WHERE owner=? AND peer=?', (viewer, uid)):
            u['avatar_id'] = ''
    return u


def auth(token):
    if not token: reject('请先登录', 401)
    s = row('SELECT user_id FROM sessions WHERE hash=? AND expires>?', (hashlib.sha256(token.encode()).hexdigest(), now()))
    if not s: reject('登录已过期，请重新登录', 401)
    moderation.ensure_account(s['user_id'])
    return s['user_id']


def friend(a, b):
    moderation.ensure_account(a);moderation.ensure_account(b)
    social.require_friend(a,b)


def conversation_target(a, b):
    if groups.is_id(b):groups.member(b, a)
    else:friend(a, b)


def message_public(m):
    m = dict(m)
    if m.get('type') == 'encrypted':
        m['envelope'] = json.loads(m['text'])
        m['text'] = ''
    if m.get('file_id'):
        m['file'] = row('SELECT id,name,mime,size FROM uploads WHERE id=?', (m['file_id'],))
    m['read'] = social.read_at(m['recipient'],m['sender']) >= m['created']
    return social.decorate(m)


async def emit(uid, data):
    if data.get('event','').startswith('call.'): await botcalls.publish(uid,data)
    for ws in tuple(online.get(uid, set())):
        token=getattr(ws,'_session_token','')
        if not row('SELECT 1 FROM sessions WHERE hash=? AND expires>?',(hashlib.sha256(token.encode()).hexdigest(),now())):
            asyncio.create_task(close_socket(ws,1008))
            continue
        try: await asyncio.wait_for(ws.send_json(data), 3)
        except (ConnectionError, RuntimeError, asyncio.TimeoutError):
            try: await asyncio.wait_for(ws.close(code=1013), .5)
            except (asyncio.TimeoutError, ConnectionError, RuntimeError): pass


async def close_socket(ws,code):
    try:await asyncio.wait_for(ws.close(code=code),2)
    except (ConnectionError,RuntimeError,asyncio.TimeoutError):pass


async def presence(uid):
    for f in rows("SELECT peer FROM friends WHERE owner=? AND state='accepted'", (uid,)):
        await emit(f['peer'], {'event': 'presence', 'user': user_public(uid, f['peer'])})


async def profile_updated(uid):
    await emit(uid, {'event': 'me.updated', 'user': user_public(uid, uid)})
    for f in rows('SELECT peer FROM friends WHERE owner=?', (uid,)):
        await emit(f['peer'], {'event': 'profile.updated', 'user': user_public(uid, f['peer'])})


def storage_used():
    return row('SELECT (SELECT coalesce(sum(size),0) FROM uploads)+(SELECT coalesce(sum(size),0) FROM avatars)+(SELECT coalesce(sum(size),0) FROM post_photos) AS n')['n']


async def set_typing(uid, peer, ws, active):
    key = (uid, peer)
    was_active = bool(typing.get(key))
    if active:
        typing.setdefault(key, {})[ws] = time.monotonic() + TYPING_TTL
    elif key in typing:
        if ws is None: typing.pop(key)
        else:
            typing[key].pop(ws, None)
            if not typing[key]: typing.pop(key)
    is_active = bool(typing.get(key))
    if active or was_active != is_active:
        await emit(peer, {'event': 'typing', 'peer': uid, 'active': is_active, 'expires_in': TYPING_TTL * 1000})


async def typing_event(uid, ws, p):
    limited('typing:' + uid, 120, 60)
    peer = str(p['peer'])
    friend(uid, peer)
    if not isinstance(p.get('active'), bool): reject('输入状态格式不正确')
    await set_typing(uid, peer, ws, p['active'])


@web.middleware
async def errors(request, handler):
    try:
        response = await handler(request)
    except ApiError as e:
        response = web.json_response(dict(error=e.message,**getattr(e,'details',{})), status=e.status)
        if e.status == 429: response.headers['Retry-After'] = str(getattr(e, 'retry_after', 60))
        if e.status in (408, 413, 429): response.force_close()
    except (json.JSONDecodeError, UnicodeDecodeError, ValueError, KeyError, TypeError, RecursionError):
        response = web.json_response({'error': '请求格式不正确'}, status=400)
    except ConnectionResetError:
        response = web.json_response({'error': '连接已中断'}, status=408)
        response.force_close()
    except sqlite3.OperationalError as e:
        db.rollback()
        response = web.json_response({'error': '服务器存储暂不可用'}, status=507 if 'full' in str(e).lower() else 503)
    except web.HTTPException as e:
        response = web.json_response({'error': '请求无法处理' if e.status != 404 else '内容不存在'}, status=e.status)
    except Exception:
        logging.exception('Request failed')
        response = web.json_response({'error': '服务器暂时无法处理请求'}, status=500)
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['Cache-Control'] = 'no-store'
    return response


@web.middleware
async def authentication(request, handler):
    if request.path.startswith('/api/') and request.path not in ('/api/register', '/api/login', '/api/login/code', '/api/health', '/api/app-release', '/api/auth/code', '/api/password/reset', '/api/payments/notify'):
        token=request.headers.get('Authorization', '').removeprefix('Bearer ')
        request['uid'] = bots.authorize(request,token) if token.startswith('qlb_') else auth(token)
        if request.path.startswith('/api/bot/') and not request.get('bot_token'): reject('请使用机器人专用凭证',403)
    return await handler(request)


def public_plaza_open(): return os.environ.get('QINGLIAO_PUBLIC_PLAZA', '0') == '1'


async def health(request): return web.json_response({'ok': True, 'app': 'qingliao', 'version': '1.24.0', 'live_location':'encrypted-v1', 'groups':True, 'group_e2ee':'manifest-v1', 'email_code_login':True, 'password_reset_email_only':True, 'app_updates':True, 'membership':True,'moderation': True, 'require_email': security.require_email, 'require_e2ee': security.require_e2ee, 'bot_api': 'webhook-v1', 'bot_calls':True,'moments_media':True,'moments_public':public_plaza_open(),'moments_video_covers':True,'bot_calls_default_allowed':True,'call_busy_feedback':True})


async def register(request):
    limited('register:' + request['client_ip'], 8, 3600)
    p = await request.json()
    username = p.get('username', '').strip().lower()
    password = p.get('password', '')
    nickname = p.get('nickname', '').strip()
    if not re.fullmatch(r'[a-z0-9_]{4,24}', username): reject('账号需为 4–24 位字母、数字或下划线')
    if not 8 <= len(password) <= 128: reject('密码需为 8–128 位')
    if not 1 <= len(nickname) <= 20: reject('昵称需为 1–20 个字')
    if row('SELECT id FROM users WHERE username=?', (username,)): reject('这个账号已被使用', 409)
    guard.registration(request)
    email, verification = '', None
    if security.require_email:
        email = security.email(p.get('email', ''))
        verification = security.verify(p, 'register', email)
        if guard.email_taken(email): reject('邮箱无法使用，请使用其他邮箱')
    salt = secrets.token_bytes(16)
    async with password_slots:
        hashed = await asyncio.to_thread(hashlib.scrypt, password.encode(), salt=salt, n=16384, r=8, p=5)
    if row('SELECT id FROM users WHERE username=?', (username,)): reject('这个账号已被使用', 409)
    uid = str(secrets.randbelow(90000000) + 10000000)
    while user_public(uid): uid = str(secrets.randbelow(90000000) + 10000000)
    if email and guard.email_taken(email): reject('邮箱无法使用，请使用其他邮箱')
    guard.registration(request)
    security.consume(verification)
    db.execute('INSERT INTO users(id,username,nickname,salt,password,created,email,password_kdf,created_ip,email_key) VALUES(?,?,?,?,?,?,?,2,?,?)', (uid, username, nickname, salt.hex(), hashed.hex(), now(), email, request['client_ip'], guard.email_key(email) if email else ''))
    db.commit()
    return web.json_response(new_session(uid))


def new_session(uid, verification_id=None):
    token = secrets.token_urlsafe(32)
    # No await/partial commit: a login proof and its new session succeed or roll back together.
    try:
        security.consume(verification_id)
        db.execute('DELETE FROM sessions WHERE expires<?', (now(),))
        old_sessions = rows('SELECT hash FROM sessions WHERE user_id=? ORDER BY expires DESC LIMIT -1 OFFSET 4', (uid,))
        for old in old_sessions:
            db.execute('DELETE FROM sessions WHERE hash=?', (old['hash'],))
        db.execute('INSERT INTO sessions VALUES(?,?,?)', (hashlib.sha256(token.encode()).hexdigest(), uid, now() + 30 * 86400000))
        result = {'token': token, 'user': user_public(uid, uid)}
        db.commit()
    except Exception:
        db.rollback()
        raise
    for old in old_sessions:
        for ws in tuple(online.get(uid,set())):
            if hashlib.sha256(getattr(ws,'_session_token','').encode()).hexdigest()==old['hash']:
                asyncio.create_task(close_socket(ws,1008))
    return result


async def login(request):
    limited('login:' + request['client_ip'], 30, 300)
    p = await request.json()
    username = str(p.get('username', '')).strip().lower()
    limited('login-account:' + username[:254], 30, 300)
    password = str(p.get('password', ''))
    if len(password) > 128: reject('账号或密码不正确', 401)
    u = row('SELECT * FROM users WHERE username=? OR id=? OR (email!=? AND email=?)', (username, username, '', username))
    if u: limited('login-user-ip:' + u['id'] + ':' + request['client_ip'], 20, 300)
    salt = bytes.fromhex(u['salt']) if u else b'qingliao-invalid!'
    async with password_slots:
        hashed = await asyncio.to_thread(hashlib.scrypt, password.encode(), salt=salt, n=16384, r=8, p=5 if not u or u.get('password_kdf') == 2 else 1)
    if not u or bots.get(u['id']) or not hmac.compare_digest(hashed.hex(), u['password']): reject('账号或密码不正确', 401)
    if row('SELECT password FROM users WHERE id=?', (u['id'],))['password'] != u['password']: reject('密码已更新，请重新登录', 401)
    moderation.ensure_account(u['id'])
    return web.json_response(new_session(u['id']))


async def logout(request):
    token = request.headers['Authorization'].removeprefix('Bearer ')
    write('DELETE FROM sessions WHERE hash=?', (hashlib.sha256(token.encode()).hexdigest(),))
    for ws in tuple(online.get(request['uid'], set())):
        if getattr(ws, '_session_token', None) == token: await ws.close(code=1000)
    return web.json_response({'ok': True})


async def me(request):
    uid = request['uid']
    if request.method == 'PATCH':
        p = await request.json()
        name = str(p.get('nickname', '')).strip()
        if not 1 <= len(name) <= 20: reject('昵称需为 1–20 个字')
        write('UPDATE users SET nickname=? WHERE id=?', (name, uid))
        await profile_updated(uid)
    return web.json_response(user_public(uid, uid))


def prepare_avatar(data):
    from PIL import Image, ImageOps, UnidentifiedImageError
    try:
        with Image.open(BytesIO(data)) as original:
            if original.format not in ('JPEG', 'PNG', 'WEBP') or getattr(original, 'n_frames', 1) != 1:
                reject('请选择 JPG、PNG 或静态 WebP 图片')
            if max(original.size) > 2048 or original.width * original.height > 4194304:
                reject('图片分辨率过大，请裁剪后上传')
            original.load()
            oriented = ImageOps.exif_transpose(original)
            if oriented.mode in ('RGBA', 'LA') or 'transparency' in oriented.info:
                rgba = oriented.convert('RGBA')
                picture = Image.new('RGB', rgba.size, 'white')
                picture.paste(rgba, mask=rgba.getchannel('A'))
            else:
                picture = oriented.convert('RGB')
            picture = ImageOps.fit(picture, (512, 512), method=Image.Resampling.LANCZOS)
            output = BytesIO()
            picture.save(output, format='JPEG', quality=88, optimize=True, exif=b'')
            return output.getvalue()
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        reject('图片无法读取，请选择有效的图片')


async def change_avatar(request):
    uid = request['uid']
    limited('avatar:' + uid, 20, 3600)
    async with avatar_slots:
        old = row('SELECT * FROM avatars WHERE owner=?', (uid,))
        if request.method == 'DELETE':
            with db:
                db.execute("UPDATE users SET avatar_id='' WHERE id=?", (uid,))
                db.execute('DELETE FROM avatars WHERE owner=?', (uid,))
        else:
            if request.content_length is not None and request.content_length > MAX_AVATAR:
                reject('头像文件不能超过 2 MB', 413)
            data = bytearray()
            async for chunk in guard.chunks(request, request['body_limit']):
                data.extend(chunk)
                if len(data) > MAX_AVATAR: reject('头像文件不能超过 2 MB', 413)
            if not data: reject('请选择图片')
            jpeg = await asyncio.to_thread(prepare_avatar, bytes(data))
            guard.storage_check(request, len(jpeg), old['size'] if old else 0)
            fid = secrets.token_hex(16)
            path = DATA / 'avatars' / fid
            try:
                path.write_bytes(jpeg)
                with db:
                    db.execute('DELETE FROM avatars WHERE owner=?', (uid,))
                    db.execute('INSERT INTO avatars VALUES(?,?,?,?)', (fid, uid, len(jpeg), now()))
                    db.execute('UPDATE users SET avatar_id=? WHERE id=?', (fid, uid))
            except BaseException:
                path.unlink(missing_ok=True)
                raise
        if old: (DATA / 'avatars' / old['id']).unlink(missing_ok=True)
    await profile_updated(uid)
    return web.json_response(user_public(uid, uid))


async def avatar_image(request):
    uid, fid = request['uid'], request.match_info['fid']
    image = row('SELECT * FROM avatars WHERE id=?', (fid,))
    if image and image['owner']!=uid and social.blocked(uid,image['owner']):reject('头像不存在或无权访问',404)
    post_context=False
    pid=request.query.get('post','')
    if image and re.fullmatch('[a-f0-9]{32}',pid):
        p=visible_post(uid,pid);post_context=p['audience']=='public' and p['owner']==image['owner']
    if not image or (image['owner'] != uid and not post_context and not row('SELECT 1 FROM friends WHERE owner=? AND peer=?', (uid, image['owner'])) and not groups.shared(uid,image['owner'])):
        reject('头像不存在或无权访问', 404)
    path = DATA / 'avatars' / fid
    if not path.is_file(): reject('头像不存在', 404)
    return web.FileResponse(path, headers={'Content-Type': 'image/jpeg'})


async def search(request):
    limited('search:' + request['uid'], 30, 60)
    q = request.query.get('q', '').strip().lower()
    u = row('SELECT id FROM users WHERE (username=? OR id=?) AND id NOT IN (SELECT uid FROM administrators)', (q, q))
    return web.json_response({'user': user_public(u['id'], request['uid']) if u and u['id'] != request['uid'] and not social.blocked(request['uid'],u['id']) else None})


async def contacts(request):
    uid = request['uid']
    result = []
    for f in rows('SELECT peer,state,read_at FROM friends WHERE owner=?', (uid,)):
        u = user_public(f['peer'], uid)
        u['state'] = f['state']
        result.append(u)
    if request.headers.get('X-Mistar-Groups') == '1':result.extend(groups.contacts(uid))
    return web.json_response({'contacts': result})


async def request_friend(request):
    uid = request['uid']
    limited('friend:' + uid, 10, 3600)
    if row("SELECT count(*) AS n FROM friends WHERE owner=?", (uid,))['n'] >= 500: reject('联系人数量已达上限')
    payload=await request.json()
    peer = str(payload['peer'])
    if peer == uid or not user_public(peer) or moderation.staff(peer): reject('用户不存在', 404)
    if social.blocked(uid,peer):reject('当前无法发送好友申请',403)
    if bots.get(peer):
        bots.ready(peer)
        if payload.get('bot_consent') is not True: reject('请升级 App 并确认机器人消息由服务端和开发者处理',400)
    old = row('SELECT state FROM friends WHERE owner=? AND peer=?', (uid, peer))
    if old:
        if old['state'] == 'accepted': reject('你们已经是好友了')
        if old['state'] == 'incoming': reject('对方已向你发出申请，请在新朋友中通过')
        reject('已发送好友申请，请等待对方通过')
    if row("SELECT count(*) AS n FROM friends WHERE owner=? AND state='outgoing'",(uid,))['n']>=20:reject('待通过的申请过多，请稍后再试',429)
    if row("SELECT count(*) AS n FROM friends WHERE owner=? AND state='incoming'",(peer,))['n']>=50:reject('对方好友申请较多，请稍后再试',429)
    if bots.get(peer):
        if row('SELECT count(*) AS n FROM friends WHERE owner=?',(peer,))['n']>=500:reject('机器人联系人数量已达上限',429)
        with db:
            db.execute("INSERT INTO friends(owner,peer,state) VALUES(?,?,'accepted')",(uid,peer))
            db.execute("INSERT INTO friends(owner,peer,state) VALUES(?,?,'accepted')",(peer,uid))
        await emit(uid,{'event':'contacts.changed'})
        return web.json_response({'ok':True,'accepted':True})
    db.execute("INSERT INTO friends(owner,peer,state) VALUES(?,?,'outgoing')", (uid, peer))
    db.execute("INSERT INTO friends(owner,peer,state) VALUES(?,?,'incoming')", (peer, uid))
    db.commit()
    await emit(peer, {'event': 'contacts.changed'})
    return web.json_response({'ok': True})


async def respond_friend(request):
    uid = request['uid']
    p = await request.json()
    peer, accept = str(p['peer']), p.get('accept') is True
    if not row("SELECT 1 FROM friends WHERE owner=? AND peer=? AND state='incoming'", (uid, peer)): reject('好友申请不存在', 404)
    if accept:
        write("UPDATE friends SET state='accepted' WHERE (owner=? AND peer=?) OR (owner=? AND peer=?)", (uid, peer, peer, uid))
    else:
        write('DELETE FROM friends WHERE (owner=? AND peer=?) OR (owner=? AND peer=?)', (uid, peer, peer, uid))
    await emit(peer, {'event': 'contacts.changed'})
    await emit(uid, {'event': 'contacts.changed'})
    return web.json_response({'ok': True})


async def conversations(request):
    uid=request['uid'];result=[];budget=0
    peers=rows('SELECT sender AS peer FROM messages WHERE recipient=? UNION SELECT recipient AS peer FROM messages WHERE sender=?',(uid,uid))
    for item in peers:
        peer=item['peer'];latest=social.history_ids(uid,peer,now()+10000,1)
        if not latest:continue
        m=row('SELECT * FROM messages WHERE id=?',(latest[0]['id'],));amount=len(m['text'].encode())
        if m['type']=='encrypted' and budget+amount>2*1024*1024:m=dict(m,type='summary',text='[加密消息，点开查看]',file_id=None)
        else:budget+=amount
        through=max(social.through(uid,peer),social.read_at(uid,peer))
        unread=row('SELECT count(*) AS n FROM messages m WHERE sender=? AND recipient=? AND created>? AND NOT EXISTS(SELECT 1 FROM message_hides h WHERE h.owner=? AND h.message_id=m.id)',(peer,uid,through,uid))['n']
        person=social.person(peer,uid)
        if person:result.append({'user':person,'last':message_public(m),'unread':unread})
    if request.headers.get('X-Mistar-Groups') == '1':
        for item in groups.conversations(uid):
            amount=len(json.dumps(item['last']).encode())
            if budget+amount>2*1024*1024:
                m=item['last'];item['last']={k:m[k] for k in ('id','sender','recipient','group_id','group_epoch','created','client_id')};item['last'].update(type='summary',text='[加密群消息，点开查看]')
            else:budget+=amount
            result.append(item)
    result.sort(key=lambda x:x['last']['created'],reverse=True)
    return web.json_response({'conversations':result})


async def messages(request):
    uid,peer=request['uid'],request.match_info['peer'];limited('history:'+uid,60,60)
    if not social.accepted(uid,peer) and not row('SELECT 1 FROM messages WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?) LIMIT 1',(uid,peer,peer,uid)) and not social.through(uid,peer):reject('聊天记录不存在',403)
    before=int(request.query.get('before',str(now()+10000)));identities=social.history_ids(uid,peer,before)
    result=[];budget=0
    for identity in identities[:50]:
        m=row('SELECT * FROM messages WHERE id=?',(identity['id'],));amount=len(m['text'].encode())
        if result and budget+amount>2*1024*1024:break
        result.append(message_public(m));budget+=amount
    return web.json_response({'messages':list(reversed(result)),'has_more':len(identities)>len(result)})


async def send_message(request):
    uid = request['uid']
    bots.current(request)
    limited('message:' + uid, 90, 60)
    limited('message-burst:' + uid, 20, 10)
    p = await request.json()
    peer, kind = str(p['recipient']), p.get('type', 'text')
    text = str(p.get('text', '')).strip()
    client_id = str(p.get('client_id', ''))[:80] or str(uuid.uuid4())
    receipt,old=syncstate.lookup(uid,client_id)
    if receipt:
        if receipt['peer']!=peer:reject('发送标识已用于其他聊天',409)
        if old:return web.json_response(message_public(old))
        return web.json_response({'id':receipt['message_id'],'sender':uid,'recipient':peer,'client_id':client_id,'created':receipt['created'],'type':'call','text':'已删除的消息','removed':True})
    friend(uid, peer)
    bots.ready(uid); bots.ready(peer)
    file_id = p.get('file_id')
    if kind == 'encrypted':
        context = 'qingliao:message:1:' + uid + ':' + peer + ':' + client_id + ':' + str(file_id or '')
        text = security.check_envelope(uid, peer, p, context)
        if file_id and not row("SELECT 1 FROM uploads WHERE id=? AND owner=? AND recipient=? AND mime='application/x-qingliao-encrypted'", (file_id, uid, peer)):
            reject('加密附件不存在或无权使用', 403)
    elif security.require_e2ee:
        reject('请升级双方 App 后使用加密聊天', 426)
    elif kind in ('text', 'html'):
        maximum = 50000 if kind == 'html' else 5000
        if not 1 <= len(text) <= maximum: reject(f'消息需为 1–{maximum} 个字')
        file_id = None
    elif kind in ('image', 'voice', 'file'):
        f = row('SELECT * FROM uploads WHERE id=? AND owner=? AND recipient=?', (file_id, uid, peer))
        if not f: reject('附件不存在或无权使用', 403)
        if kind == 'image' and not f['mime'].startswith('image/'): reject('请选择图片')
        if kind == 'voice' and not f['mime'].startswith('audio/'): reject('语音格式不正确')
        text = text[:100]
    else: reject('不支持的消息类型')
    mid = str(uuid.uuid4())
    syncstate.reserve(uid)
    guard.message_space(uid, len(text.encode()))
    guard.content_charge(uid,len(text.encode())+256)
    created=message_timestamp()
    bots.reserve(peer)
    with db:
        db.execute('INSERT INTO messages VALUES(?,?,?,?,?,?,?,?)',(mid,uid,peer,kind,text,file_id,created,client_id))
        syncstate.record(uid,client_id,peer,mid,created)
        bots.enqueue(uid,peer,mid)
    await set_typing(uid, peer, None, False)
    m = message_public(row('SELECT * FROM messages WHERE id=?', (mid,)))
    await emit(peer, {'event': 'message', 'message': m})
    await emit(uid, {'event': 'message', 'message': m})
    return web.json_response(m)


async def read_messages(request):
    uid, peer = request['uid'], request.match_info['peer']
    friend(uid, peer)
    latest = row('SELECT max(created) AS t FROM messages WHERE sender=? AND recipient=?', (peer, uid))['t'] or 0
    write('UPDATE friends SET read_at=max(read_at,?) WHERE owner=? AND peer=?', (latest, uid, peer))
    write('INSERT INTO conversation_reads VALUES(?,?,?) ON CONFLICT(owner,peer) DO UPDATE SET at=max(at,excluded.at)',(uid,peer,latest))
    await emit(peer, {'event': 'messages.read', 'peer': uid, 'at': latest})
    return web.json_response({'ok': True})


async def upload(request):
    uid = request['uid']
    limited('upload:' + uid, 60, 3600)
    peer = request.query.get('recipient', '')
    conversation_target(uid, peer)
    if (security.require_e2ee or groups.is_id(peer)) and request.headers.get('Content-Type', '').split(';')[0] != 'application/x-qingliao-encrypted': reject('请升级 App 后发送加密附件', 426)
    if storage_used() >= MAX_STORAGE: reject('服务器存储空间已满', 507)
    name = request.query.get('name', '文件')[:180].replace('/', '_').replace('\\', '_')
    mime = request.headers.get('Content-Type', 'application/octet-stream').split(';')[0]
    if mime == 'application/x-qingliao-encrypted': name = 'encrypted.bin'
    if len(mime) > 100: mime = 'application/octet-stream'
    fid = secrets.token_hex(16)
    path = DATA / 'uploads' / fid
    temporary = DATA / 'uploads' / ('.part-' + fid)
    size = 0
    try:
        with temporary.open('wb') as f:
            async for chunk in guard.chunks(request, request['body_limit']):
                size += len(chunk)
                if size > MAX_FILE + (65536 if mime == 'application/x-qingliao-encrypted' else 0): reject('单个文件不能超过 25 MB', 413)
                f.write(chunk)
        if size == 0: reject('不能发送空文件')
        guard.storage_check(request, size)
        conversation_target(uid, peer)
        if not groups.is_id(peer):bots.ready(peer)
        temporary.replace(path)
        write('INSERT INTO uploads VALUES(?,?,?,?,?,?,?)', (fid, uid, peer, name, mime, size, now()))
    except BaseException:
        temporary.unlink(missing_ok=True)
        path.unlink(missing_ok=True)
        raise
    return web.json_response({'id': fid, 'name': name, 'mime': mime, 'size': size})


async def download(request):
    uid, fid = request['uid'], request.match_info['fid']
    f = row('SELECT * FROM uploads WHERE id=?', (fid,))
    if not f or not (uid in (f['owner'], f['recipient']) or groups.is_id(f['recipient']) and groups.file_allowed(uid, f)): reject('文件不存在或无权访问', 404)
    if uid == f['recipient'] and not row('SELECT 1 FROM messages WHERE file_id=? AND recipient=?', (fid, uid)): reject('文件尚未发送', 404)
    path = DATA / 'uploads' / fid
    if not path.exists(): reject('文件不存在', 404)
    guard.limited('downloads:'+uid,512*1024*1024,86400,f['size'])
    return web.FileResponse(path, headers={'Content-Type': f['mime'], 'Content-Disposition': 'attachment'})


def visible_post(uid, pid):
    post = row('SELECT * FROM posts WHERE id=?', (pid,))
    if post and post['audience']=='public' and not public_plaza_open() and post['owner']!=uid:
        reject('广场暂时关闭，此公开动态暂不可查看', 404)
    if not post or moderation.hidden('post',pid) or social.blocked(uid,post['owner']) or (post['owner'] != uid and post['audience']!='public' and not social.accepted(uid,post['owner'])):
        reject('动态不存在或无权查看', 404)
    return post


def comment_public(c, uid):
    return {'id': c['id'], 'user': user_public(c['user_id'], uid), 'text': c['text'], 'created': c['created']}


def post_public(p, uid):
    pid = p['id']
    author=user_public(p['owner'],uid)
    author['friend']=p['owner']==uid or social.accepted(uid,p['owner'])
    if p['audience']=='public':
        author['avatar_id']=(row('SELECT avatar_id FROM users WHERE id=?',(p['owner'],)) or {}).get('avatar_id','')
        author['avatar_post']=pid
    preview = rows('SELECT c.* FROM post_comments c WHERE post_id=? AND '+moderation.visible_sql('c','comment')+' ORDER BY created DESC,id DESC LIMIT 2', (pid,))
    media=row("SELECT id,kind,mime,duration,size,width,height FROM post_photos WHERE post_id=? AND kind IN ('video','voice') LIMIT 1",(pid,))
    if media and media['kind']=='video':media['cover']=row("SELECT id,width,height FROM post_photos WHERE parent_media=? AND kind='cover'",(media['id'],))
    return {'id': pid, 'user': author, 'text': p['text'], 'created': p['created'],
            'audience':p['audience'],
            'photos': rows("SELECT id,width,height FROM post_photos WHERE post_id=? AND kind='image' ORDER BY position", (pid,)),
            'media':media,
            'like_count': row('SELECT count(*) AS n FROM post_likes WHERE post_id=?', (pid,))['n'],
            'liked': bool(row('SELECT 1 FROM post_likes WHERE post_id=? AND user_id=?', (pid, uid))),
            'comment_count': row('SELECT count(*) AS n FROM post_comments c WHERE post_id=? AND '+moderation.visible_sql('c','comment'), (pid,))['n'],
            'comments': [comment_public(c, uid) for c in reversed(preview)]}


def page_cursor(request):
    value = request.query.get('before', '')
    if not value: return (9223372036854775807, 'z' * 32)
    if not re.fullmatch(r'[0-9]{1,16}:[0-9a-f]{32}', value): reject('分页参数不正确')
    created, identity = value.split(':')
    return int(created), identity


def next_cursor(items, more):
    return str(items[-1]['created']) + ':' + items[-1]['id'] if more else ''


async def posts(request):
    uid = request['uid']
    before = page_cursor(request)
    scope=request.query.get('scope','mine' if request.query.get('mine','')=='1' else 'friends')
    if scope not in ('public','friends','mine'):reject('请选择广场、好友或自己的动态')
    if scope=='public' and not public_plaza_open():reject('广场暂时关闭，请使用好友动态',403)
    audience={'mine':'p.owner=?','public':"p.audience='public' AND ?<>''",'friends':"(p.owner=? OR p.owner IN (SELECT peer FROM friends WHERE owner=? AND state='accepted'))"}[scope]
    args=(uid,uid) if scope=='friends' else (uid,)
    if not public_plaza_open():
        audience += " AND (p.audience<>'public' OR p.owner=?)"
        args += (uid,)
    visible="NOT EXISTS (SELECT 1 FROM blocks b WHERE (b.owner=? AND b.peer=p.owner) OR (b.peer=? AND b.owner=p.owner))"
    visible += ' AND '+moderation.visible_sql('p','post')
    found=rows(f'SELECT p.* FROM posts p WHERE {audience} AND {visible} AND (p.created,p.id)<(?,?) ORDER BY p.created DESC,p.id DESC LIMIT 21',args+(uid,uid)+before)
    items, more = found[:20], len(found) > 20
    return web.json_response({'posts': [post_public(p, uid) for p in items], 'next': next_cursor(items, more)})


async def post_detail(request):
    return web.json_response(post_public(visible_post(request['uid'], request.match_info['pid']), request['uid']))


async def post_changed(owner, pid, action='updated', audience=None):
    if audience is None:audience=(row('SELECT audience FROM posts WHERE id=?',(pid,)) or {}).get('audience','friends')
    targets=set([owner]+[f['peer'] for f in rows("SELECT peer FROM friends WHERE owner=? AND state='accepted'",(owner,))])
    if audience=='public':targets.update(online)
    for target in targets:
        if not social.blocked(owner,target):await emit(target,{'event':'post.changed','id':pid,'action':action})


def client_identity(p):
    value = p.get('client_id')
    if not isinstance(value, str) or not 1 <= len(value) <= 80: reject('请提供有效的发送标识')
    return value


async def publish_post(request):
    uid = request['uid']
    limited('post:' + uid, 30, 3600)
    p = await request.json()
    client_id = client_identity(p)
    old = row('SELECT * FROM posts WHERE owner=? AND client_id=?', (uid, client_id))
    if old: return web.json_response(post_public(old, uid))
    text, photos = p.get('text', ''), p.get('photos', [])
    audience=p.get('audience','friends')  # Old clients explicitly promise friends-only in their UI.
    media=p.get('media','')
    if audience not in ('public','friends'):reject('请选择广场或好友可见')
    if audience=='public' and not public_plaza_open():reject('广场暂时关闭，请选择仅好友可见',403)
    if not isinstance(media,str) or media and not re.fullmatch('[a-f0-9]{32}',media):reject('媒体标识不正确')
    if not isinstance(text, str) or len(text) > 5000: reject('动态最多 5000 个字')
    text = text.strip()
    if not isinstance(photos, list) or len(photos) > 9 or any(not isinstance(f, str) for f in photos) or len(set(photos)) != len(photos): reject('最多选择 9 张不同的图片')
    if photos and media:reject('图片、视频或语音请选择一种')
    if media and not row("SELECT 1 FROM post_photos WHERE id=? AND owner=? AND post_id IS NULL AND kind IN ('video','voice')",(media,uid)):reject('媒体不存在或已被使用，请重新选择')
    if not text and not photos and not media: reject('写点什么，或添加图片、视频、语音吧')
    for fid in photos:
        if not row("SELECT 1 FROM post_photos WHERE id=? AND owner=? AND post_id IS NULL AND kind='image'", (fid, uid)): reject('图片不存在或已被使用，请重新选择', 400)
    pid = secrets.token_hex(16)
    guard.content_charge(uid,len(text.encode())+256)
    with db:
        db.execute('INSERT INTO posts(id,owner,text,created,client_id,audience) VALUES(?,?,?,?,?,?)', (pid, uid, text, now(), client_id,audience))
        for index, fid in enumerate(photos): db.execute('UPDATE post_photos SET post_id=?,position=? WHERE id=?', (pid, index, fid))
        if media:db.execute('UPDATE post_photos SET post_id=? WHERE id=? OR parent_media=?',(pid,media,media))
    await post_changed(uid, pid, 'published')
    return web.json_response(post_public(row('SELECT * FROM posts WHERE id=?', (pid,)), uid))


def prepare_post_photo(data,limit=1600,quality=86):
    from PIL import Image, ImageOps, UnidentifiedImageError
    try:
        with Image.open(BytesIO(data)) as source:
            if source.format not in ('JPEG', 'PNG', 'WEBP') or getattr(source, 'n_frames', 1) != 1: reject('请选择 JPG、PNG 或静态 WebP 图片')
            if max(source.size) > 2048 or source.width * source.height > 4194304: reject('请先将图片压缩到 2048 像素以内')
            source.load()
            picture = ImageOps.exif_transpose(source).convert('RGB')
            picture.thumbnail((limit, limit), Image.Resampling.LANCZOS)
            output = BytesIO(); picture.save(output, 'JPEG', quality=quality, exif=b'')
            return output.getvalue(), picture.size
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError): reject('图片无法读取')


async def post_photo_upload(request):
    uid = request['uid']
    limited('post-photo:' + uid, 120, 3600)
    async with avatar_slots:
        if request.content_length is not None and request.content_length > MAX_AVATAR: reject('图片不能超过 2 MB', 413)
        data = bytearray()
        async for chunk in guard.chunks(request, request['body_limit']):
            data.extend(chunk)
            if len(data) > MAX_AVATAR: reject('图片不能超过 2 MB', 413)
        jpeg, (width, height) = await asyncio.to_thread(prepare_post_photo, bytes(data))
        guard.storage_check(request, len(jpeg))
        fid = secrets.token_hex(16); path = DATA / 'moments' / fid
        try:
            path.write_bytes(jpeg)
            write('INSERT INTO post_photos(id,owner,size,width,height,created) VALUES(?,?,?,?,?,?)', (fid, uid, len(jpeg), width, height, now()))
        except BaseException:
            path.unlink(missing_ok=True); raise
    return web.json_response({'id': fid, 'width': width, 'height': height})


async def post_photo(request):
    uid, fid = request['uid'], request.match_info['fid']
    photo = row("SELECT * FROM post_photos WHERE id=? AND kind='image'", (fid,))
    if not photo: reject('图片不存在', 404)
    if photo['post_id']: visible_post(uid, photo['post_id'])
    elif photo['owner'] != uid: reject('图片不存在', 404)
    path = DATA / 'moments' / fid
    if not path.is_file(): reject('图片不存在', 404)
    return web.FileResponse(path, headers={'Content-Type': 'image/jpeg'})


async def delete_post(request):
    uid, pid = request['uid'], request.match_info['pid']
    p = visible_post(uid, pid)
    if p['owner'] != uid: reject('只能删除自己发布的动态', 403)
    files = rows('SELECT id FROM post_photos WHERE post_id=?', (pid,))
    write('DELETE FROM posts WHERE id=?', (pid,))
    for f in files: (DATA / 'moments' / f['id']).unlink(missing_ok=True)
    await post_changed(uid, pid, 'deleted',p['audience'])
    return web.json_response({'ok': True})


async def like_post(request):
    uid, pid = request['uid'], request.match_info['pid']
    p = visible_post(uid, pid)
    limited('post-like:' + uid, 90, 60)
    liked = (await request.json()).get('liked')
    if not isinstance(liked, bool): reject('点赞状态不正确')
    if liked: write('INSERT OR IGNORE INTO post_likes VALUES(?,?)', (pid, uid))
    else: write('DELETE FROM post_likes WHERE post_id=? AND user_id=?', (pid, uid))
    await post_changed(p['owner'], pid)
    return web.json_response(post_public(p, uid))


async def post_comments(request):
    uid, pid = request['uid'], request.match_info['pid']
    p = visible_post(uid, pid)
    if request.method == 'POST':
        limited('post-comment:' + uid, 60, 60)
        data = await request.json(); client_id = client_identity(data)
        text = data.get('text')
        if not isinstance(text, str) or not 1 <= len(text.strip()) <= 500: reject('评论需为 1–500 个字')
        old = row('SELECT * FROM post_comments WHERE user_id=? AND client_id=?', (uid, client_id))
        if old:
            if old['post_id'] != pid: reject('发送标识已被使用', 409)
            return web.json_response(comment_public(old, uid))
        if row('SELECT count(*) AS n FROM post_comments WHERE post_id=?', (pid,))['n'] >= 1000: reject('这条动态的评论已满')
        cid = secrets.token_hex(16)
        guard.content_charge(uid,len(text.encode())+256)
        write('INSERT INTO post_comments VALUES(?,?,?,?,?,?)', (cid, pid, uid, text.strip(), now(), client_id))
        await post_changed(p['owner'], pid)
        return web.json_response(comment_public(row('SELECT * FROM post_comments WHERE id=?', (cid,)), uid))
    found = rows('SELECT c.* FROM post_comments c WHERE post_id=? AND '+moderation.visible_sql('c','comment')+' AND (created,id)<(?,?) ORDER BY created DESC,id DESC LIMIT 31', (pid,) + page_cursor(request))
    items, more = found[:30], len(found) > 30
    return web.json_response({'comments': [comment_public(c, uid) for c in reversed(items)], 'next': next_cursor(items, more)})


async def delete_post_comment(request):
    uid, pid, cid = request['uid'], request.match_info['pid'], request.match_info['cid']
    p = visible_post(uid, pid)
    c = row('SELECT * FROM post_comments WHERE id=? AND post_id=?', (cid, pid))
    if not c: reject('评论不存在', 404)
    if uid not in (c['user_id'], p['owner']): reject('无权删除这条评论', 403)
    write('DELETE FROM post_comments WHERE id=?', (cid,))
    await post_changed(p['owner'], pid)
    return web.json_response({'ok': True})


async def ice(request):
    limited('ice:' + request['uid'], 30, 3600)
    username = str(int(time.time()) + 3600) + ':' + request['uid']
    password = base64.b64encode(hmac.new(TURN_SECRET.encode(), username.encode(), hashlib.sha1).digest()).decode()
    return web.json_response({'iceServers': [
        {'urls': ['stun:' + PUBLIC_IP + ':3478']},
        {'urls': ['turn:' + PUBLIC_IP + ':443?transport=tcp'], 'username': username, 'credential': password}
    ]})


def call_for(uid):
    return next((c for c in calls.values() if uid in (c['caller'], c['callee'])), None)


def call_public(c):
    value={key:c[key] for key in ('id','caller','callee','video','created','accepted','accepted_at','connected','invite_id') if key in c}
    origin=c.get('accepted_at',c['created'])
    value['expires_in_ms']=max(0,45000-(now()-origin)) if not c.get('connected') else 0
    return value


async def finish_call(c, reason, by=None):
    if calls.pop(c['id'], None) is None: return
    duration = max(0, (now() - c['connected']) // 1000) if c.get('connected') else 0
    event = {'event': 'call.ended', 'call_id': c['id'], 'reason': reason, 'duration': duration, 'video':c['video']}
    for uid in (c['caller'], c['callee']): await emit(uid, event)
    if reason=='busy':text='对方忙线，请稍后重试'
    elif duration: text = ('视频通话' if c['video'] else '语音通话') + f' {duration // 60:02d}:{duration % 60:02d}'
    else: text = {'rejected': '对方已拒绝通话', 'timeout': '未接听', 'cancelled': '通话已取消', 'offline': '通话已断开', 'failed': '连接未成功', 'ended': '通话已结束'}.get(reason, '通话已结束')
    mid = str(uuid.uuid4())
    if not guard.message_space(c['caller'], len(text.encode()), False): return
    write('INSERT INTO messages VALUES(?,?,?,?,?,?,?,?)', (mid, c['caller'], c['callee'], 'call', text, None, message_timestamp(), 'call:' + c['id']))
    m = message_public(row('SELECT * FROM messages WHERE id=?', (mid,)))
    for uid in (c['caller'], c['callee']): await emit(uid, {'event': 'message', 'message': m})


async def signaling(uid, p):
    event = p.get('event', '')
    limited('signal:' + uid, 400, 60)
    # Media itself is SRTP via TURN; bound the much smaller encrypted SDP/ICE signalling plane.
    signal_bytes=len(json.dumps(p,separators=(',',':')).encode())
    guard.limited('signal-bytes:'+uid,guard.MAX_SIGNAL_BYTES_HOUR,3600,signal_bytes)
    guard.limited('signal-bytes:global',guard.MAX_GLOBAL_SIGNAL_BYTES_HOUR,3600,signal_bytes)
    if event == 'call.invite':
        limited('call-invite:' + uid,5,60)
        peer = str(p['peer'])
        friend(uid, peer)
        if call_for(uid):call_busy('你正在通话中，请先结束当前通话',peer,p.get('video',False),p.get('invite_id',''),code='self_busy')
        if call_for(peer):call_busy('对方正在通话中，请稍后重试',peer,p.get('video',False),p.get('invite_id',''))
        botcalls.check_invite(uid,peer,p.get('video',False),p.get('invite_id',''))
        if not online.get(peer) and not bots.get(peer): reject('对方当前离线，请稍后再拨')
        c = {'id': str(uuid.uuid4()), 'caller': uid, 'callee': peer, 'video': p.get('video') is True, 'created': now(), 'accepted': False,'invite_id':str(p.get('invite_id',''))[:64]}
        botcalls.record(c,p)
        calls[c['id']] = c
        await emit(uid, {'event': 'call.waiting', 'call': call_public(c), 'user': user_public(peer, uid)})
        await emit(peer, {'event': 'call.ringing', 'call': call_public(c), 'user': user_public(uid, peer)})
        return c
    c = calls.get(str(p.get('call_id', '')))
    if not c or uid not in (c['caller'], c['callee']): reject('通话已结束')
    if not c['accepted'] and now()-c['created']>45000:
        await finish_call(c,'timeout');reject('来电已过期')
    if c['accepted'] and not c.get('connected') and now()-c['accepted_at']>45000:
        await finish_call(c,'failed');reject('通话连接已超时')
    peer = c['callee'] if uid == c['caller'] else c['caller']
    if event == 'call.accept':
        if uid != c['callee'] or c['accepted']: reject('不能接听此通话')
        c['accepted'] = True
        c['accepted_at'] = now()
        for user in (uid, peer): await emit(user, {'event': 'call.accepted', 'call': call_public(c)})
    elif event == 'call.reject':
        if uid != c['callee'] or c['accepted']: reject('不能拒绝此通话')
        await finish_call(c, 'rejected', uid)
    elif event == 'call.end':
        await finish_call(c, 'ended' if c['accepted'] else 'cancelled', uid)
    elif event == 'call.connected':
        if c['accepted'] and not c.get('connected'):
            c.setdefault('connected_by',set()).add(uid)
            if len(c['connected_by'])==2:c['connected']=now()
    elif event == 'call.signal':
        if not c['accepted']: reject('对方尚未接听')
        signal = p['signal']
        if security.require_e2ee:
            context = 'qingliao:call:1:' + c['id'] + ':' + uid + ':' + peer
            security.check_envelope(uid, peer, signal, context)
        if not isinstance(signal, dict) or len(json.dumps(signal)) > 900000: reject('信令格式不正确')
        await emit(peer, {'event': 'call.signal', 'call_id': c['id'], 'signal': signal})
    else: reject('未知操作')


async def websocket(request):
    ws = web.WebSocketResponse(heartbeat=25, max_msg_size=262144, compress=False)
    await ws.prepare(request)
    uid = None
    try:
        first = await ws.receive(timeout=8)
        if first.type != WSMsgType.TEXT: return ws
        p = json.loads(first.data)
        token = p.get('token', '')
        uid = auth(token)
        if moderation.staff(uid) and moderation.staff(uid)['must_change']:reject('请先修改管理员初始密码',403)
        if len(online.get(uid, set())) >= guard.MAX_WS_USER: reject('此账号连接设备过多，请关闭其他设备后重试', 429)
        ws._session_token = token
        online.setdefault(uid, set()).add(ws)
        await callstate.restored(uid)
        write('UPDATE users SET last_seen=? WHERE id=?', (now(), uid))
        await ws.send_json({'event': 'ready', 'user': user_public(uid, uid)})
        if releases.current: await ws.send_json({'event':'app.update','version_code':releases.current['version_code']})
        await presence(uid)
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    auth(token)
                    limited('ws-packets:' + uid, 80, 10)
                    p = json.loads(msg.data)
                    if not isinstance(p, dict): reject('信令格式不正确')
                    if p.get('event') == 'ping': await ws.send_json({'event': 'pong'})
                    elif p.get('event') == 'typing': await typing_event(uid, ws, p)
                    else: await signaling(uid, p)
                except ApiError as e:
                    await ws.send_json(dict(event='error',error=e.message,**getattr(e,'details',{})))
                    if e.status in (401, 429):
                        await ws.close(code=1008 if e.status==401 else 1013)
                        break
                except (ValueError, TypeError, KeyError): await ws.send_json({'event': 'error', 'error': '信令格式不正确'})
    except ApiError as e:
        await ws.close(code=1013 if e.status==429 else 1008)
    except (ValueError, TypeError, AttributeError, asyncio.TimeoutError):
        await ws.close(code=1008)
    finally:
        if uid:
            for owner, peer in list(typing):
                if owner == uid and ws in typing.get((owner, peer), {}):
                    await set_typing(uid, peer, ws, False)
            online.get(uid, set()).discard(ws)
            if not online.get(uid):
                online.pop(uid, None)
                write('UPDATE users SET last_seen=? WHERE id=?', (now(), uid))
                await presence(uid)
                await callstate.lost(uid)
    return ws


async def maintenance(app):
    last_seen_sync = last_files_sweep = time.monotonic()
    while True:
        await asyncio.sleep(1)
        t = time.monotonic()
        await callstate.tick()
        await botcalls.tick()
        await locations.tick()
        for (uid, peer), sockets in list(typing.items()):
            for ws, expires in list(sockets.items()):
                if expires <= t:
                    sockets.pop(ws, None)
            if not sockets:
                typing.pop((uid, peer), None)
                await emit(peer, {'event': 'typing', 'peer': uid, 'active': False, 'expires_in': 0})
        for c in list(calls.values()):
            if not c['accepted'] and now() - c['created'] > 45000: await finish_call(c, 'timeout')
            elif c['accepted'] and not c.get('connected') and now() - c['accepted_at'] > 45000: await finish_call(c, 'failed')
        if t - last_seen_sync >= 30:
            await membership.expire()
            db.executemany('UPDATE users SET last_seen=? WHERE id=?', [(now(), uid) for uid in online])
            db.commit()
            last_seen_sync = t
        if t - last_files_sweep < 60: continue
        last_files_sweep = t
        guard.reap()
        syncstate.reap()
        social.maintenance_sweep()
        groups.maintenance()
        # Remove incomplete uploads after 24h; sent attachments remain durable.
        for f in rows('SELECT id FROM uploads WHERE created<? AND id NOT IN (SELECT file_id FROM messages WHERE file_id IS NOT NULL) AND id NOT IN (SELECT file_id FROM group_messages WHERE file_id IS NOT NULL)', (now() - 86400000,)):
            (DATA / 'uploads' / f['id']).unlink(missing_ok=True)
            write('DELETE FROM uploads WHERE id=?', (f['id'],))
        for f in rows('SELECT id FROM post_photos WHERE post_id IS NULL AND created<?', (now() - 86400000,)):
            (DATA / 'moments' / f['id']).unlink(missing_ok=True)
            write('DELETE FROM post_photos WHERE id=?', (f['id'],))


async def lifecycle(app):
    task = asyncio.create_task(maintenance(app))
    yield
    task.cancel()
    try: await task
    except asyncio.CancelledError: pass
    for sockets in list(online.values()):
        for ws in tuple(sockets): await ws.close(code=1001)
    db.close()


async def shutdown(app):
    for c in list(calls.values()): await finish_call(c, 'offline')
    sockets = [ws for group in list(online.values()) for ws in tuple(group)]
    if sockets:
        try: await asyncio.wait_for(asyncio.gather(*(ws.close(code=1001) for ws in sockets), return_exceptions=True), timeout=3)
        except asyncio.TimeoutError: pass


from abuse import Defense, WorkGate
guard = Defense(globals())
limits = guard
password_slots = WorkGate(reject, 1, 3)
avatar_slots = WorkGate(reject, 1, 2)
from social import Social
social = Social(globals())
from storage import Storage
storage = Storage(globals())
from security import Security
security = Security(globals())
from recovery import Recovery
recovery = Recovery(globals())
from callstate import CallState
callstate = CallState(globals())
from syncstate import SyncState
syncstate = SyncState(globals())
from bots import Bots
bots = Bots(globals())
from botcalls import BotCalls
botcalls = BotCalls(globals())
from momentmedia import MomentMedia
momentmedia=MomentMedia(globals())
from moderation import Moderation
moderation=Moderation(globals())
from releases import Releases
releases=Releases(globals())
from membership import Membership
membership=Membership(globals())
from groups import Groups
groups=Groups(globals())
from locations import Locations
locations=Locations(globals())
guard.migrate_emails()
guard.reap()
last_message_time = row('SELECT coalesce(max(created),0) AS n FROM messages')['n']
def message_timestamp():
    global last_message_time
    last_message_time = max(now(), last_message_time + 1)
    return last_message_time
app = web.Application(middlewares=[errors, guard.admission, authentication, guard.body, moderation.admission], client_max_size=MAX_FILE + 65536, handler_args={'auto_decompress':False,'read_bufsize':65536,'lingering_time':0})
security.routes(app)
recovery.routes(app)
callstate.routes(app)
syncstate.routes(app)
social.routes(app)
storage.routes(app)
groups.routes(app)
locations.routes(app)
app.cleanup_ctx.append(lifecycle)
bots.routes(app)
botcalls.routes(app)
momentmedia.routes(app)
moderation.routes(app)
releases.routes(app)
membership.routes(app)
app.on_shutdown.append(shutdown)
app.add_routes([
    web.get('/api/health', health), web.post('/api/register', register), web.post('/api/login', login),
    web.post('/api/logout', logout), web.get('/api/me', me), web.patch('/api/me', me),
    web.put('/api/me/avatar', change_avatar), web.delete('/api/me/avatar', change_avatar),
    web.get('/api/avatars/{fid}', avatar_image),
    web.get('/api/users', search), web.get('/api/contacts', contacts),
    web.post('/api/friends/request', request_friend), web.post('/api/friends/respond', respond_friend),
    web.get('/api/conversations', conversations), web.get('/api/messages/{peer}', messages),
    web.post('/api/messages', send_message), web.post('/api/read/{peer}', read_messages),
    web.post('/api/uploads', upload), web.get('/api/files/{fid}', download),
    web.get('/api/posts', posts), web.post('/api/posts', publish_post),
    web.get('/api/posts/{pid}', post_detail), web.delete('/api/posts/{pid}', delete_post),
    web.post('/api/posts/{pid}/like', like_post),
    web.get('/api/posts/{pid}/comments', post_comments), web.post('/api/posts/{pid}/comments', post_comments),
    web.delete('/api/posts/{pid}/comments/{cid}', delete_post_comment),
    web.post('/api/post-photos', post_photo_upload), web.get('/api/post-photos/{fid}', post_photo),
    web.get('/api/ice', ice), web.get('/ws', websocket),
])
if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO)
    web.run_app(app, host='127.0.0.1', port=int(os.environ.get('PORT', '8765')), access_log=None)
