"""Email verification and immutable public identities. No private chat keys live here."""
import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import smtplib
import ssl
from email.message import EmailMessage
from email.utils import formatdate, make_msgid
from aiohttp import web


class Security:
    def __init__(self, core):
        self.c = core
        self.db = core['db']
        self.tasks = set()
        self.mail_slot = asyncio.Semaphore(1)
        self.require_email = os.environ.get('QINGLIAO_REQUIRE_EMAIL', '1') == '1'
        self.require_e2ee = os.environ.get('QINGLIAO_REQUIRE_E2EE', '1') == '1'
        secret = os.environ.get('OTP_SECRET', '')
        # Persistent, non-exportable-to-clients secret; fail closed on missing production config.
        self.secret = secret.encode()
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS verifications(id TEXT PRIMARY KEY,purpose TEXT NOT NULL,email TEXT NOT NULL,user_id TEXT NOT NULL,hash TEXT NOT NULL,expires INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,used INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS verification_email_time ON verifications(email,created);
        CREATE TABLE IF NOT EXISTS crypto_identities(user_id TEXT PRIMARY KEY REFERENCES users(id),key_id TEXT NOT NULL,enc TEXT NOT NULL,sig TEXT NOT NULL,created INTEGER NOT NULL);
        ''')
        if 'email' not in {r['name'] for r in self.db.execute('PRAGMA table_info(users)')}:
            self.db.execute("ALTER TABLE users ADD COLUMN email TEXT NOT NULL DEFAULT ''")
        if 'password_kdf' not in {r['name'] for r in self.db.execute('PRAGMA table_info(users)')}:
            self.db.execute('ALTER TABLE users ADD COLUMN password_kdf INTEGER NOT NULL DEFAULT 1')
        self.db.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_email ON users(email) WHERE email<>''")
        self.db.commit()

    def reject(self, message, status=400):
        self.c['reject'](message, status)

    def email(self, value):
        value = str(value).strip().lower()
        if len(value) > 254 or not re.fullmatch(r'[a-z0-9.!#$%&\'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,63}', value):
            self.reject('请输入有效的邮箱地址')
        return value

    def digest(self, vid, code):
        return hmac.new(self.secret, (vid + ':' + code).encode(), hashlib.sha256).hexdigest()

    def verify(self, p, purpose, email, uid=''):
        vid, code = str(p.get('verification_id', '')), str(p.get('code', ''))
        r = self.c['row']('SELECT * FROM verifications WHERE id=?', (vid,))
        if not r or r['used'] or r['expires'] <= self.c['now']() or r['attempts'] >= 5:
            self.reject('验证码无效或已过期，请重新获取')
        self.c['write']('UPDATE verifications SET attempts=attempts+1 WHERE id=?', (vid,))
        if r['purpose'] != purpose or r['email'] != email or r['user_id'] != uid or not hmac.compare_digest(r['hash'], self.digest(vid, code)):
            self.reject('验证码无效或已过期，请重新获取')
        return vid

    def consume(self, vid):
        if not vid: return
        cur = self.db.execute('UPDATE verifications SET used=1 WHERE id=? AND used=0 AND expires>?', (vid, self.c['now']()))
        if cur.rowcount != 1:
            self.db.rollback()
            self.reject('验证码已使用或已过期，请重新获取')

    def send_mail(self, email, code, purpose):
        host = os.environ.get('SMTP_HOST', 'smtp.qq.com')
        user, password = os.environ.get('SMTP_USER', ''), os.environ.get('SMTP_PASSWORD', '')
        labels = {'register': '注册账号', 'reset': '重置密码', 'bind': '绑定邮箱', 'identity_restart':'重新开始加密聊天'}
        msg = EmailMessage()
        msg['From'] = '弥星 <' + user + '>'
        msg['To'] = email
        msg['Subject'] = '弥星 · ' + labels[purpose] + '验证码'
        msg['Date'] = formatdate(localtime=False)
        msg['Message-ID'] = make_msgid(domain=user.split('@')[-1])
        msg.set_content(f'你正在弥星中{labels[purpose]}。\n\n验证码：{code}\n\n10 分钟内有效，仅可使用一次。请勿向他人提供验证码。如果不是你本人操作，请忽略此邮件。\n\n弥星')
        with smtplib.SMTP_SSL(host, int(os.environ.get('SMTP_PORT', '465')), context=ssl.create_default_context(), timeout=18) as smtp:
            smtp.login(user, password)
            smtp.send_message(msg)

    async def deliver(self, email, code, purpose, vid):
        async with self.mail_slot:
            proof=self.c['row']('SELECT used,expires FROM verifications WHERE id=?', (vid,))
            if not proof or proof['used'] or proof['expires'] <= self.c['now'](): return
            try:
                await asyncio.to_thread(self.send_mail, email, code, purpose)
            except Exception as e:
                # Never log recipients, codes, passwords or provider diagnostic bodies.
                logging.warning('Verification delivery failed (%s)', type(e).__name__)
                self.c['write']('UPDATE verifications SET used=1 WHERE id=?', (vid,))

    async def code(self, request):
        if not self.secret or not os.environ.get('SMTP_PASSWORD'):
            self.reject('邮件服务暂不可用，请稍后重试', 503)
        self.c['limited']('verification-ip:' + request['client_ip'], 12, 3600)
        if len(self.tasks) >= 8: self.reject('邮件服务繁忙，请稍后重试', 429)
        p = await request.json()
        purpose = p.get('purpose')
        if purpose not in ('register', 'reset', 'bind', 'identity_restart'): self.reject('验证码用途不正确')
        if purpose=='register':self.c['guard'].registration(request)
        email = self.email(p.get('email', ''))
        self.c['limited']('mail-address:' + self.c['guard'].email_key(email), 10, 86400)
        uid = ''
        valid = True
        if purpose == 'identity_restart':
            uid=self.c['auth'](request.headers.get('Authorization','').removeprefix('Bearer '))
            u=self.c['row']('SELECT * FROM users WHERE id=?',(uid,))
            if not u['email'] or email!=u['email']:self.reject('请填写账号已验证的邮箱')
            if not await self.password_matches(u,p.get('password','')):self.reject('当前密码不正确',403)
            self.c['auth'](request.headers.get('Authorization','').removeprefix('Bearer '))
        elif purpose == 'bind':
            uid = self.c['auth'](request.headers.get('Authorization', '').removeprefix('Bearer '))
            u = self.c['row']('SELECT * FROM users WHERE id=?', (uid,))
            if u['email']: self.reject('此账号已绑定邮箱')
            if not await self.password_matches(u, p.get('password', '')): self.reject('当前密码不正确', 403)
            self.c['auth'](request.headers.get('Authorization', '').removeprefix('Bearer '))
            valid = not self.c['guard'].email_taken(email)
        elif purpose == 'reset':
            # Verified email is unique. Client-supplied usernames never select the reset target.
            u = self.c['row']('SELECT id FROM users WHERE email=?', (email,))
            uid = u['id'] if u else ''
            valid = bool(u)
        else:
            valid = not self.c['guard'].email_taken(email)
        t = self.c['now']()
        recent = self.c['row']('SELECT max(created) AS t,count(*) AS n FROM verifications WHERE email=? AND created>?', (email, t - 86400000))
        if recent['t'] and t - recent['t'] < 60000: self.reject('请等待 60 秒后再获取验证码', 429)
        if recent['n'] >= 10: self.reject('今日验证码次数已用完，请明天再试', 429)
        if self.c['row']('SELECT count(*) AS n FROM verifications WHERE created>?', (t - 86400000,))['n'] >= 200:
            self.reject('邮件服务繁忙，请稍后再试', 429)
        vid, code = secrets.token_urlsafe(24), f'{secrets.randbelow(1000000):06d}'
        self.db.execute('UPDATE verifications SET used=1 WHERE email=? AND purpose=?', (email, purpose))
        self.db.execute('INSERT INTO verifications(id,purpose,email,user_id,hash,expires,created) VALUES(?,?,?,?,?,?,?)', (vid, purpose, email, uid, self.digest(vid, code), t + 600000, t))
        self.db.commit()
        if valid:
            task = asyncio.create_task(self.deliver(email, code, purpose, vid))
            self.tasks.add(task)
            task.add_done_callback(self.tasks.discard)
        # Uniform response, including unknown reset account and already-bound email.
        return web.json_response({'verification_id': vid, 'retry_after': 60, 'message': '信息匹配时会发送验证码，请检查邮箱及垃圾邮件'})

    async def password_matches(self, u, password):
        password = str(password)
        if not 8 <= len(password) <= 128: return False
        async with self.c['password_slots']:
            value = await asyncio.to_thread(hashlib.scrypt, password.encode(), salt=bytes.fromhex(u['salt']), n=16384, r=8, p=5 if u.get('password_kdf') == 2 else 1)
        return hmac.compare_digest(value.hex(), u['password'])

    async def reset(self, request):
        self.c['limited']('reset-ip:' + request['client_ip'], 20, 3600)
        p = await request.json()
        email = self.email(p.get('email', ''))
        u = self.c['row']('SELECT * FROM users WHERE email=?', (email,))
        vid = self.verify(p, 'reset', email, u['id'] if u else '')
        if not u: self.reject('验证码无效或已过期，请重新获取')
        await self.set_password(u, p.get('new_password', ''), vid)
        return web.json_response({'ok': True})

    async def change(self, request):
        uid = request['uid']
        self.c['limited']('password-change:' + uid, 10, 3600)
        p = await request.json()
        u = self.c['row']('SELECT * FROM users WHERE id=?', (uid,))
        if not await self.password_matches(u, p.get('old_password', '')): self.reject('当前密码不正确', 403)
        await self.set_password(u, p.get('new_password', ''), None)
        return web.json_response({'ok': True})

    async def set_password(self, u, password, vid):
        password = str(password)
        if not 8 <= len(password) <= 128: self.reject('新密码需为 8–128 位')
        if self.c['moderation'].staff(u['id']) and len(password)<12:self.reject('管理员密码至少 12 位')
        salt = secrets.token_bytes(16)
        async with self.c['password_slots']:
            hashed = await asyncio.to_thread(hashlib.scrypt, password.encode(), salt=salt, n=16384, r=8, p=5)
        # Atomic code consumption/password update/session revocation; no await inside transaction.
        latest = self.c['row']('SELECT password FROM users WHERE id=?', (u['id'],))
        if latest['password'] != u['password']: self.reject('密码已更新，请重新登录后操作', 409)
        self.consume(vid)
        self.db.execute('UPDATE users SET salt=?,password=?,password_kdf=2 WHERE id=?', (salt.hex(), hashed.hex(), u['id']))
        self.db.execute('DELETE FROM sessions WHERE user_id=?', (u['id'],))
        self.db.execute('UPDATE verifications SET used=1 WHERE user_id=?', (u['id'],))
        self.c['moderation'].password_changed(u['id'])
        self.db.commit()
        for ws in tuple(self.c['online'].get(u['id'], set())):
            await ws.close(code=1008, message=b'credentials changed')

    async def bind(self, request):
        uid = request['uid']
        p = await request.json()
        u = self.c['row']('SELECT * FROM users WHERE id=?', (uid,))
        if u['email']: self.reject('此账号已绑定邮箱')
        if not await self.password_matches(u, p.get('password', '')): self.reject('当前密码不正确', 403)
        self.c['auth'](request.headers.get('Authorization', '').removeprefix('Bearer '))
        email = self.email(p.get('email', ''))
        vid = self.verify(p, 'bind', email, uid)
        if self.c['guard'].email_taken(email): self.reject('邮箱无法绑定，请使用其他邮箱')
        self.consume(vid)
        self.db.execute('UPDATE users SET email=?,email_key=? WHERE id=? AND email=\'\'', (email, self.c['guard'].email_key(email), uid))
        self.db.commit()
        return web.json_response({'ok': True})

    async def account(self, request):
        u = self.c['row']('SELECT email FROM users WHERE id=?', (request['uid'],))
        return web.json_response({'email': u['email'], 'e2ee': bool(self.identity_for(request['uid']))})

    def identity_for(self, uid):
        return self.c['row']('SELECT key_id,enc,sig FROM crypto_identities WHERE user_id=?', (uid,))

    def validate_public(self, value, kind):
        if not isinstance(value, str) or not 30 <= len(value) <= 8000: self.reject('加密身份格式不正确')
        p = json.loads(value)
        keys = p.get('key', [])
        expected = 'type.googleapis.com/google.crypto.tink.' + kind
        if len(keys) != 1 or keys[0].get('status') != 'ENABLED' or keys[0].get('keyId') != p.get('primaryKeyId'):
            self.reject('加密身份格式不正确')
        data = keys[0].get('keyData', {})
        if data.get('keyMaterialType') != 'ASYMMETRIC_PUBLIC' or data.get('typeUrl') != expected:
            self.reject('只允许上传公钥')
        if not 20 <= len(base64.b64decode(data['value'], validate=True)) <= 2048: self.reject('公钥格式不正确')

    async def identity(self, request):
        uid = request['uid']
        if request.method == 'POST':
            p = await request.json()
            enc, sig = p.get('enc'), p.get('sig')
            self.validate_public(enc, 'HpkePublicKey')
            self.validate_public(sig, 'EcdsaPublicKey')
            kid = hashlib.sha256((enc + '\n' + sig).encode()).hexdigest()
            old = self.identity_for(uid)
            if old and old['key_id'] != kid: self.reject('请从原设备导入聊天身份后再使用加密聊天', 409)
            self.db.execute('INSERT OR IGNORE INTO crypto_identities VALUES(?,?,?,?,?)', (uid, kid, enc, sig, self.c['now']()))
            self.db.commit()
        else:
            peer = request.match_info['uid']
            if peer != uid and not self.c['social'].accepted(uid,peer):
                # Former participants still need their known peer's public key to read history.
                if not self.c['row']('SELECT 1 FROM messages WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?) LIMIT 1',(uid,peer,peer,uid)):
                    self.c['reject']('无权访问聊天身份',403)
            uid = peer
        identity=self.identity_for(uid) or {}
        kid=request.query.get('key_id','')
        if kid and identity.get('key_id')!=kid:identity=self.c['recovery'].historical(uid,kid)
        return web.json_response(identity)

    def check_envelope(self, uid, peer, p, context=None):
        envelope = p.get('envelope')
        if not isinstance(envelope, dict) or envelope.get('v') != 1: self.reject('请升级双方 App 后使用加密聊天', 426)
        a, b = self.identity_for(uid), self.identity_for(peer)
        if not a or not b: self.reject('双方需先升级 App 并登录后才能加密通信', 426)
        if envelope.get('sender_key') != a['key_id'] or envelope.get('recipient_key') != b['key_id']:
            self.reject('无法建立安全连接，请重新登录后重试', 409)
        if context is not None and envelope.get('context') != context: self.reject('加密上下文不正确')
        for key in ('sender_cipher', 'recipient_cipher', 'signature'):
            value = envelope.get(key)
            if not isinstance(value, str) or not 8 <= len(value) <= 400000: self.reject('加密消息格式不正确')
            base64.b64decode(value, validate=True)
        if len(json.dumps(envelope)) > 900000: self.reject('加密消息过大', 413)
        return json.dumps(envelope, separators=(',', ':'))

    async def cleanup(self, app):
        if self.tasks:
            await asyncio.gather(*tuple(self.tasks), return_exceptions=True)

    def routes(self, app):
        app.on_shutdown.append(self.cleanup)
        app.add_routes([
            web.post('/api/auth/code', self.code), web.post('/api/password/reset', self.reset),
            web.post('/api/password/change', self.change), web.post('/api/email/bind', self.bind),
            web.get('/api/account', self.account), web.post('/api/crypto/identity', self.identity),
            web.get('/api/crypto/identity/{uid}', self.identity),
        ])
