"""Server-enforced staff moderation. No private-message/identity-key access or role-grant API."""
import asyncio
import hashlib
import re
import secrets
from pathlib import Path
from aiohttp import web


class Moderation:
    GRANT_MS = 10 * 60 * 1000

    def __init__(self, core):
        self.c, self.db = core, core['db']
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS administrators(
          uid TEXT PRIMARY KEY REFERENCES users(id), must_change INTEGER NOT NULL DEFAULT 1,
          created INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS admin_grants(
          hash TEXT PRIMARY KEY, uid TEXT NOT NULL REFERENCES users(id), session_hash TEXT NOT NULL,
          expires INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS admin_grants_owner ON admin_grants(uid,expires);
        CREATE TABLE IF NOT EXISTS account_controls(
          uid TEXT PRIMARY KEY REFERENCES users(id), banned_until INTEGER NOT NULL DEFAULT 0,
          posting_until INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '',
          actor TEXT NOT NULL, updated INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS moderated_content(
          kind TEXT NOT NULL, target TEXT NOT NULL, hidden INTEGER NOT NULL,
          actor TEXT NOT NULL, reason TEXT NOT NULL, updated INTEGER NOT NULL,
          PRIMARY KEY(kind,target));
        CREATE TABLE IF NOT EXISTS admin_audit(
          seq INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT NOT NULL, action TEXT NOT NULL,
          kind TEXT NOT NULL, target TEXT NOT NULL, reason TEXT NOT NULL,
          created INTEGER NOT NULL, changed INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS admin_audit_target ON admin_audit(kind,target,seq);
        ''')

    def reject(self, text, status=400): self.c['reject'](text, status)
    def staff(self, uid): return self.c['row']('SELECT * FROM administrators WHERE uid=?', (uid,))
    def hidden(self, kind, target):
        return bool(self.c['row']('SELECT 1 FROM moderated_content WHERE kind=? AND target=? AND hidden=1', (kind, target)))
    @staticmethod
    def visible_sql(alias, kind):
        assert alias in ('p', 'c') and kind in ('post', 'comment')
        return "NOT EXISTS(SELECT 1 FROM moderated_content mc WHERE mc.kind='"+kind+"' AND mc.target="+alias+".id AND mc.hidden=1)"
    def controls(self, uid):
        p = self.c['row']('SELECT * FROM account_controls WHERE uid=?', (uid,)) or {}
        for key in ('banned_until', 'posting_until'): p.setdefault(key, 0)
        return p
    def active(self, deadline): return deadline == -1 or deadline > self.c['now']()
    def ensure_account(self, uid):
        if self.active(self.controls(uid)['banned_until']): self.reject('账号已被管理员封禁，请联系管理员处理', 403)
    def check_request(self, request):
        uid = request.get('uid')
        if not uid: return
        token=request.headers.get('Authorization','').removeprefix('Bearer ') if hasattr(request,'headers') else ''
        if token:
            if request.get('bot_token'): self.c['bots'].auth(token)
            else: self.c['auth'](token)
        self.ensure_account(uid)
        staff = self.staff(uid)
        if staff and staff['must_change'] and request.path not in (
            '/api/me', '/api/logout', '/api/account', '/api/password/change', '/api/admin/status'):
            self.reject('管理员首次登录请先修改初始密码', 403)
        publish = request.method in ('POST', 'PUT', 'PATCH') and (
            request.path in ('/api/posts', '/api/post-photos', '/api/post-media') or
            re.fullmatch(r'/api/posts/[a-f0-9]{32}/comments', request.path) or
            re.fullmatch(r'/api/post-media/[a-f0-9]{32}/cover', request.path))
        if publish and self.active(self.controls(uid)['posting_until']):
            self.reject('此账号暂时不能发布动态或评论', 403)

    @web.middleware
    async def admission(self, request, handler):
        self.check_request(request)
        return await handler(request)

    def password_changed(self, uid):
        # Caller owns the password/session transaction; never commit early here.
        self.db.execute('UPDATE administrators SET must_change=0 WHERE uid=?', (uid,))
        self.db.execute('DELETE FROM admin_grants WHERE uid=?', (uid,))

    def require_staff(self, request, unlocked=True):
        uid = request['uid']
        if request.get('bot_token') or not self.staff(uid): self.reject('需要管理员权限', 403)
        self.ensure_account(uid)
        self.c['limited']('admin-read:'+uid, 120, 60)
        if unlocked:
            if self.staff(uid)['must_change']: self.reject('请先修改管理员初始密码', 403)
            token = request.headers.get('Authorization', '').removeprefix('Bearer ')
            grant = request.headers.get('X-Admin-Grant', '')
            if len(grant) > 100: self.reject('管理验证已过期，请重新验证密码', 403)
            valid = self.c['row']('''SELECT 1 FROM admin_grants g JOIN sessions s ON s.hash=g.session_hash
              WHERE g.hash=? AND g.uid=? AND g.session_hash=? AND g.expires>? AND s.expires>? AND s.user_id=g.uid''',
                (hashlib.sha256(grant.encode()).hexdigest(), uid, hashlib.sha256(token.encode()).hexdigest(), self.c['now'](), self.c['now']()))
            if not valid: self.reject('管理验证已过期，请重新验证密码', 403)
        return uid

    def mutation(self, request, body):
        actor = self.require_staff(request)
        self.c['limited']('admin-write:'+actor, 40, 60)
        reason = body.get('reason')
        if not isinstance(reason, str) or not 2 <= len(reason.strip()) <= 300:
            self.reject('请填写 2–300 个字的处理原因')
        return actor, reason.strip()

    def audit(self, actor, action, kind, target, reason, changed):
        self.db.execute('INSERT INTO admin_audit(actor,action,kind,target,reason,created,changed) VALUES(?,?,?,?,?,?,?)',
            (actor, action, kind, target, reason, self.c['now'](), changed))
        # Bounded on this small server; only root can modify audit history directly.
        self.db.execute('DELETE FROM admin_audit WHERE seq < coalesce((SELECT seq FROM admin_audit ORDER BY seq DESC LIMIT 1 OFFSET 19999),0)')

    async def status(self, request):
        uid = self.require_staff(request, False)
        return web.json_response({'administrator': True, 'must_change_password': bool(self.staff(uid)['must_change']),
            'counts': {'users': self.c['row']('SELECT count(*) AS n FROM users')['n'],
                       'posts': self.c['row']('SELECT count(*) AS n FROM posts')['n'],
                       'hidden_posts': self.c['row']("SELECT count(*) AS n FROM moderated_content m JOIN posts p ON p.id=m.target WHERE m.kind='post' AND m.hidden=1")['n']}})

    async def unlock(self, request):
        uid = self.require_staff(request, False)
        self.c['limited']('admin-unlock:'+uid, 8, 300)
        body = await request.json()
        u = self.c['row']('SELECT * FROM users WHERE id=?', (uid,))
        if not await self.c['security'].password_matches(u, body.get('password', '')):
            self.reject('管理员密码不正确', 403)
        # A ban, password change or role removal while scrypt ran must win.
        self.c['auth'](request.headers.get('Authorization', '').removeprefix('Bearer '))
        self.require_staff(request, False)
        if self.staff(uid)['must_change']: self.reject('请先修改管理员初始密码', 403)
        token_hash = hashlib.sha256(request.headers['Authorization'].removeprefix('Bearer ').encode()).hexdigest()
        grant = secrets.token_urlsafe(32); expires = self.c['now']()+self.GRANT_MS
        with self.db:
            self.db.execute('DELETE FROM admin_grants WHERE expires<=? OR (uid=? AND session_hash=?)', (self.c['now'](), uid, token_hash))
            self.db.execute('INSERT INTO admin_grants VALUES(?,?,?,?)', (hashlib.sha256(grant.encode()).hexdigest(), uid, token_hash, expires))
            self.db.execute('DELETE FROM admin_grants WHERE uid=? AND hash NOT IN (SELECT hash FROM admin_grants WHERE uid=? ORDER BY expires DESC LIMIT 4)', (uid, uid))
        return web.json_response({'grant': grant, 'expires': expires})

    def cursor(self, request):
        value = request.query.get('before', '')
        if not value: return (9223372036854775807, 'z'*32)
        if not re.fullmatch(r'[0-9]{1,16}:[a-z0-9-]{1,36}', value): self.reject('分页参数不正确')
        t, key = value.split(':'); return (int(t), key)
    def page(self, records):
        items, more = records[:30], len(records)>30
        return items, (str(items[-1]['created'])+':'+items[-1]['id']) if more else ''
    def text_filter(self, request):
        q = request.query.get('q', '').strip()
        if len(q)>100: self.reject('搜索内容过长')
        return '%'+q.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')+'%'
    def admin_user(self, uid):
        u = self.c['row']('SELECT id,username,nickname,created,last_seen,avatar_id FROM users WHERE id=?', (uid,))
        if not u: self.reject('账号不存在', 404)
        u.update(self.controls(uid)); u.pop('actor', None)
        u['administrator'] = bool(self.staff(uid)); u['bot'] = bool(self.c['bots'].get(uid))
        u['online'] = bool(self.c['online'].get(uid))
        u['post_count'] = self.c['row']('SELECT count(*) AS n FROM posts WHERE owner=?', (uid,))['n']
        return u
    def admin_post(self, p):
        result = self.c['post_public'](p, p['owner'])
        result['user'] = self.admin_user(p['owner'])
        result['moderation'] = self.c['row']("SELECT hidden,reason,actor,updated FROM moderated_content WHERE kind='post' AND target=?", (p['id'],)) or {'hidden': False}
        return result

    async def posts(self, request):
        self.require_staff(request)
        state, owner, query = request.query.get('state', 'all'), request.query.get('owner', ''), self.text_filter(request)
        if state not in ('all', 'visible', 'hidden') or owner and not re.fullmatch('[0-9]{8}', owner): self.reject('筛选参数不正确')
        where = "(p.created,p.id)<(?,?) AND (p.text LIKE ? ESCAPE '\\' OR u.username LIKE ? ESCAPE '\\' OR u.id LIKE ? ESCAPE '\\' OR u.nickname LIKE ? ESCAPE '\\')"
        params = self.cursor(request)+(query,)*4
        if owner: where += ' AND p.owner=?'; params += (owner,)
        visible = self.visible_sql('p', 'post')
        if state!='all': where += ' AND '+(visible if state=='visible' else 'NOT ('+visible+')')
        found = self.c['rows']('SELECT p.* FROM posts p JOIN users u ON u.id=p.owner WHERE '+where+' ORDER BY p.created DESC,p.id DESC LIMIT 31', params)
        items, next_cursor = self.page(found)
        return web.json_response({'posts': [self.admin_post(p) for p in items], 'next': next_cursor})

    async def users(self, request):
        self.require_staff(request); query = self.text_filter(request)
        found = self.c['rows']("SELECT id,created FROM users WHERE (created,id)<(?,?) AND (username LIKE ? ESCAPE '\\' OR id LIKE ? ESCAPE '\\' OR nickname LIKE ? ESCAPE '\\') ORDER BY created DESC,id DESC LIMIT 31", self.cursor(request)+(query,)*3)
        items, next_cursor = self.page(found)
        return web.json_response({'users': [self.admin_user(u['id']) for u in items], 'next': next_cursor})

    async def comments(self, request):
        self.require_staff(request); pid = request.match_info['pid']
        post = self.c['row']('SELECT * FROM posts WHERE id=?', (pid,))
        if not post: self.reject('动态不存在', 404)
        found = self.c['rows']('SELECT * FROM post_comments WHERE post_id=? AND (created,id)<(?,?) ORDER BY created DESC,id DESC LIMIT 31', (pid,)+self.cursor(request))
        items, next_cursor = self.page(found)
        result=[]
        for item in items:
            p=self.c['comment_public'](item, item['user_id']); p['user']=self.admin_user(item['user_id'])
            p['moderation']=self.c['row']("SELECT hidden,reason,actor,updated FROM moderated_content WHERE kind='comment' AND target=?", (item['id'],)) or {'hidden': False}
            result.append(p)
        return web.json_response({'post': self.admin_post(post), 'comments': result, 'next': next_cursor})

    async def content_action(self, request):
        body=await request.json(); actor, reason=self.mutation(request, body)
        kind, target = request.match_info['kind'], request.match_info['target']
        if kind not in ('post', 'comment') or not re.fullmatch('[a-f0-9]{32}', target): self.reject('内容不存在', 404)
        hidden=body.get('hidden')
        if not isinstance(hidden, bool): self.reject('处理状态不正确')
        table='posts' if kind=='post' else 'post_comments'
        content=self.c['row']('SELECT * FROM '+table+' WHERE id=?', (target,))
        if not content: self.reject('内容不存在', 404)
        changed=int(self.hidden(kind, target)!=hidden)
        with self.db:
            self.db.execute('INSERT INTO moderated_content VALUES(?,?,?,?,?,?) ON CONFLICT(kind,target) DO UPDATE SET hidden=excluded.hidden,actor=excluded.actor,reason=excluded.reason,updated=excluded.updated', (kind,target,int(hidden),actor,reason,self.c['now']()))
            self.audit(actor,'hide' if hidden else 'restore',kind,target,reason,changed)
        pid=target if kind=='post' else content['post_id']
        post=self.c['row']('SELECT * FROM posts WHERE id=?', (pid,))
        if post: await self.c['post_changed'](post['owner'],pid,'deleted' if kind=='post' and hidden else 'updated',post['audience'])
        return web.json_response({'ok': True, 'hidden': hidden, 'changed': bool(changed)})

    def deadline(self, body):
        days=body.get('days')
        if isinstance(days,bool) or not isinstance(days,int) or days not in (0,1,3,7,30,90,365): self.reject('请选择处理期限')
        return -1 if days==0 else self.c['now']()+days*86400000

    async def user_action(self, request):
        body=await request.json(); actor, reason=self.mutation(request, body); target=request.match_info['uid']
        if not re.fullmatch('[0-9]{8}',target) or not self.c['row']('SELECT 1 FROM users WHERE id=?',(target,)): self.reject('账号不存在',404)
        if self.staff(target): self.reject('管理员账号受保护，不能通过管理接口处理',403)
        action=body.get('action')
        if action not in ('ban','unban','restrict_posts','restore_posts','logout','hide_posts'): self.reject('处理类型不正确')
        current=self.controls(target); banned=current['banned_until']; posting=current['posting_until']
        if action=='ban': banned=self.deadline(body)
        elif action=='unban': banned=0
        elif action=='restrict_posts': posting=self.deadline(body)
        elif action=='restore_posts': posting=0
        changed=0
        with self.db:
            if action in ('ban','unban','restrict_posts','restore_posts'):
                changed=int(banned!=current['banned_until'] or posting!=current['posting_until'])
                self.db.execute('INSERT INTO account_controls VALUES(?,?,?,?,?,?) ON CONFLICT(uid) DO UPDATE SET banned_until=excluded.banned_until,posting_until=excluded.posting_until,reason=excluded.reason,actor=excluded.actor,updated=excluded.updated', (target,banned,posting,reason,actor,self.c['now']()))
            if action in ('ban','logout'):
                changed+=self.db.execute('DELETE FROM sessions WHERE user_id=?',(target,)).rowcount
                self.db.execute('DELETE FROM admin_grants WHERE uid=?',(target,))
            if action=='hide_posts':
                changed=self.c['row']("SELECT count(*) AS n FROM posts p WHERE p.owner=? AND "+self.visible_sql('p','post'),(target,))['n']
                self.db.execute("INSERT INTO moderated_content(kind,target,hidden,actor,reason,updated) SELECT 'post',id,1,?,?,? FROM posts WHERE owner=? ON CONFLICT(kind,target) DO UPDATE SET hidden=1,actor=excluded.actor,reason=excluded.reason,updated=excluded.updated",(actor,reason,self.c['now'](),target))
            self.audit(actor,action,'user',target,reason,changed)
        if action in ('ban','logout'):
            for call in list(self.c['calls'].values()):
                if target in (call['caller'],call['callee']): await self.c['finish_call'](call,'offline')
            await asyncio.gather(*(self.c['close_socket'](ws,1008) for ws in tuple(self.c['online'].get(target,set()))))
        if action=='hide_posts':
            # One small event invalidates every feed, rather than emitting N×users packets.
            for uid in tuple(self.c['online']): await self.c['emit'](uid,{'event':'post.changed','id':'','action':'refresh'})
        return web.json_response({'ok':True,'changed':changed,'user':self.admin_user(target)})

    async def media(self, request):
        self.require_staff(request); fid=request.match_info['fid']
        if not re.fullmatch('[a-f0-9]{32}',fid): self.reject('媒体不存在',404)
        file=self.c['row']('SELECT id,kind,mime,post_id FROM post_photos WHERE id=? AND post_id IS NOT NULL',(fid,))
        folder='moments'
        if not file:
            file=self.c['row']('SELECT id FROM avatars WHERE id=?',(fid,)); folder='avatars'
        if not file: self.reject('已发布媒体不存在',404)
        path=self.c['DATA']/folder/fid
        if not path.is_file(): self.reject('媒体不存在',404)
        return web.FileResponse(path,headers={'Content-Type':file.get('mime','image/jpeg'),'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff'})

    async def logs(self, request):
        self.require_staff(request); before=request.query.get('before','9223372036854775807')
        if not re.fullmatch('[0-9]{1,19}',before) or int(before)>9223372036854775807: self.reject('分页参数不正确')
        entries=self.c['rows']('SELECT * FROM admin_audit WHERE seq<? ORDER BY seq DESC LIMIT 31',(int(before),))
        return web.json_response({'logs':entries[:30],'next':str(entries[29]['seq']) if len(entries)>30 else ''})

    def routes(self, app):
        app.add_routes([web.get('/api/admin/status',self.status),web.post('/api/admin/unlock',self.unlock),
            web.get('/api/admin/posts',self.posts),web.get('/api/admin/users',self.users),
            web.get('/api/admin/posts/{pid}/comments',self.comments),
            web.post('/api/admin/content/{kind}/{target}',self.content_action),
            web.post('/api/admin/users/{uid}/action',self.user_action),
            web.get('/api/admin/media/{fid}',self.media),web.get('/api/admin/audit',self.logs)])
