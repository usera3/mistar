"""Member-scoped groups. User content stays opaque; every delivery has an explicit recipient."""
import base64
import hashlib
import json
import re
import uuid
from aiohttp import web


class Groups:
    MAX_MEMBERS = 20
    MAX_BODY = 6 * 1024 * 1024

    def __init__(self, core):
        self.c, self.db = core, core['db']
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS chat_groups(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES users(id),name TEXT NOT NULL,epoch INTEGER NOT NULL DEFAULT 1,active INTEGER NOT NULL DEFAULT 1,created INTEGER NOT NULL,client_id TEXT NOT NULL,payload_hash TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS group_creations(creator TEXT NOT NULL REFERENCES users(id),client_id TEXT NOT NULL,gid TEXT NOT NULL REFERENCES chat_groups(id),payload_hash TEXT NOT NULL,PRIMARY KEY(creator,client_id));
        CREATE TABLE IF NOT EXISTS group_members(gid TEXT NOT NULL REFERENCES chat_groups(id),uid TEXT NOT NULL REFERENCES users(id),joined INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 1,read_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(gid,uid));
        CREATE INDEX IF NOT EXISTS group_member_user ON group_members(uid,active,gid);
        CREATE TABLE IF NOT EXISTS group_messages(id TEXT PRIMARY KEY,gid TEXT NOT NULL REFERENCES chat_groups(id),sender TEXT NOT NULL REFERENCES users(id),client_id TEXT NOT NULL,epoch INTEGER NOT NULL,type TEXT NOT NULL,body TEXT NOT NULL,file_id TEXT,created INTEGER NOT NULL,n INTEGER NOT NULL,UNIQUE(sender,client_id));
        CREATE INDEX IF NOT EXISTS group_message_time ON group_messages(gid,created DESC);
        CREATE INDEX IF NOT EXISTS group_message_sender ON group_messages(sender,n);
        CREATE INDEX IF NOT EXISTS group_message_file ON group_messages(file_id);
        CREATE TABLE IF NOT EXISTS group_deliveries(mid TEXT NOT NULL REFERENCES group_messages(id) ON DELETE CASCADE,uid TEXT NOT NULL REFERENCES users(id),PRIMARY KEY(mid,uid));
        CREATE INDEX IF NOT EXISTS group_delivery_user ON group_deliveries(uid,mid);
        CREATE TABLE IF NOT EXISTS group_hides(mid TEXT NOT NULL REFERENCES group_messages(id) ON DELETE CASCADE,uid TEXT NOT NULL REFERENCES users(id),PRIMARY KEY(mid,uid));
        CREATE TABLE IF NOT EXISTS group_clears(gid TEXT NOT NULL REFERENCES chat_groups(id),uid TEXT NOT NULL REFERENCES users(id),through INTEGER NOT NULL,PRIMARY KEY(gid,uid));
        CREATE TABLE IF NOT EXISTS group_receipts(sender TEXT NOT NULL,client_id TEXT NOT NULL,gid TEXT NOT NULL,mid TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(sender,client_id));
        ''')
        self.last_time = self.c['row']('SELECT coalesce(max(created),0) AS t FROM group_messages')['t']

    @staticmethod
    def is_id(value): return isinstance(value, str) and re.fullmatch(r'g_[a-f0-9]{32}', value) is not None

    def reject(self, message, status=400): self.c['reject'](message, status)

    def member(self, gid, uid):
        if not self.is_id(gid): self.reject('群聊不存在', 404)
        group = self.c['row']('SELECT * FROM chat_groups WHERE id=? AND active=1', (gid,))
        if not group or not self.c['row']('SELECT 1 FROM group_members WHERE gid=? AND uid=? AND active=1', (gid, uid)):
            self.reject('你已不在此群聊中或群聊已解散', 403)
        self.c['moderation'].ensure_account(uid)
        return group

    def owner(self, gid, uid):
        group = self.member(gid, uid)
        if group['owner'] != uid: self.reject('此操作仅限群主', 403)
        return group

    def ids(self, gid): return [r['uid'] for r in self.c['rows']('SELECT uid FROM group_members WHERE gid=? AND active=1 ORDER BY uid', (gid,))]

    def recipients(self, gid, sender):
        return [uid for uid in self.ids(gid) if uid == sender or (not self.c['social'].blocked(uid, sender) and not self.c['moderation'].active(self.c['moderation'].controls(uid)['banned_until']))]

    def stamp(self):
        self.last_time = max(self.c['now'](), self.last_time + 1)
        return self.last_time

    def descriptor(self, group, viewer, detailed=False):
        gid = group['id'];members = self.ids(gid)
        value = dict(id=gid, group=True, nickname=group['name'], username='', avatar_id='', owner=group['owner'], epoch=group['epoch'], member_count=len(members), state='accepted', friend=True, online=False, last_seen=0, is_owner=group['owner'] == viewer)
        if detailed:
            value['members'] = [dict(self.c['social'].person(uid, viewer), group_owner=uid == group['owner']) for uid in members]
        return value

    def contacts(self, uid):
        return [self.descriptor(g, uid) for g in self.c['rows']('SELECT g.* FROM chat_groups g JOIN group_members m ON m.gid=g.id WHERE m.uid=? AND m.active=1 AND g.active=1 ORDER BY g.created DESC LIMIT 50', (uid,))]

    def check_name(self, value):
        if not isinstance(value, str): self.reject('请输入群名称')
        value = value.strip()
        if not 1 <= len(value) <= 30 or any(ord(c) < 32 or 0xD800<=ord(c)<=0xDFFF or c in '\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069' for c in value): self.reject('群名称需为1至30个普通字符')
        return value

    def check_client(self, value):
        if not isinstance(value, str) or str(uuid.UUID(value)) != value: self.reject('请求编号不正确')
        return value

    def invitations(self, inviter, values, existing=()):
        if not isinstance(values, list) or len(values) > self.MAX_MEMBERS - 1 or len(set(values)) != len(values): self.reject('群成员选择不正确')
        for uid in values:
            if not isinstance(uid, str) or not re.fullmatch(r'[0-9]{8}', uid) or uid == inviter or self.c['bots'].get(uid) or not self.c['social'].accepted(inviter, uid): self.reject('只能邀请已经通过好友验证的普通好友', 403)
            self.c['moderation'].ensure_account(uid)
            if any(self.c['social'].blocked(uid, other) for other in set(existing) | set(values) if other != uid): self.reject('成员之间存在屏蔽关系，无法加入同一群聊', 403)
            if uid not in existing and self.c['row']('SELECT count(*) AS n FROM group_members WHERE uid=? AND active=1', (uid,))['n'] >= 50: self.reject('有成员的群聊数量已达上限', 429)
        return sorted(values)

    async def changed(self, gid, removed=()):
        epoch=(self.c['row']('SELECT epoch FROM chat_groups WHERE id=?',(gid,)) or {}).get('epoch',0)
        for uid in self.ids(gid): await self.c['emit'](uid, dict(event='group.changed', group_id=gid))
        for uid in removed: await self.c['emit'](uid, dict(event='group.removed', group_id=gid,epoch=epoch))

    async def collection(self, request):
        uid = request['uid']
        if request.method == 'GET': return web.json_response({'groups': self.contacts(uid)})
        self.c['limited']('group-create:' + uid, 10, 3600)
        if self.c['bots'].get(uid): self.reject('机器人不能创建群聊', 403)
        p = await request.json();name = self.check_name(p.get('name'));client = self.check_client(p.get('client_id'))
        self.c['moderation'].check_request(request)
        values = p.get('members')
        if not isinstance(values, list) or not 1 <= len(values) < self.MAX_MEMBERS or any(not isinstance(v,str) or not re.fullmatch(r'[0-9]{8}',v) or v==uid for v in values) or len(set(values))!=len(values):self.reject('至少选择一位普通好友，最多19位')
        members = sorted(values)
        fingerprint = hashlib.sha256(json.dumps([name, members], ensure_ascii=False).encode()).hexdigest()
        receipt = self.c['row']('SELECT * FROM group_creations WHERE creator=? AND client_id=?', (uid, client))
        old = self.c['row']('SELECT * FROM chat_groups WHERE id=?', (receipt['gid'],)) if receipt else None
        if old:
            if receipt['payload_hash'] != fingerprint: self.reject('此创建请求的内容已改变，请先查看已有群聊', 409)
            self.member(old['id'], uid)
            return web.json_response({'group': self.descriptor(old, uid, True)})
        self.invitations(uid, members, (uid,))
        if self.c['row']('SELECT count(*) AS n FROM group_members WHERE uid=? AND active=1', (uid,))['n'] >= 50 or self.c['row']('SELECT count(*) AS n FROM chat_groups WHERE owner=? AND active=1', (uid,))['n'] >= 20: self.reject('创建或加入的群聊数量已达上限', 429)
        if self.c['row']('SELECT count(*) AS n FROM chat_groups')['n'] >= 5000: self.reject('服务器群聊数量已满', 507)
        gid = 'g_' + uuid.uuid4().hex;created = self.stamp();mid = str(uuid.uuid4())
        with self.db:
            self.db.execute('INSERT INTO chat_groups VALUES(?,?,?,?,?,?,?,?)', (gid, uid, name, 1, 1, created, client, fingerprint))
            self.db.execute('INSERT INTO group_creations VALUES(?,?,?,?)', (uid, client, gid, fingerprint))
            for person in [uid] + members: self.db.execute('INSERT INTO group_members VALUES(?,?,?,?,?)', (gid, person, created, 1, created if person == uid else 0))
            self.db.execute('INSERT INTO group_messages VALUES(?,?,?,?,?,?,?,?,?,?)', (mid, gid, uid, 'system:' + mid, 1, 'call', json.dumps({'text': '群聊已创建'}), None, created, 64))
            for person in [uid] + members: self.db.execute('INSERT INTO group_deliveries VALUES(?,?)', (mid, person))
        await self.changed(gid)
        group = self.member(gid, uid)
        message=self.c['row']('SELECT * FROM group_messages WHERE id=?',(mid,))
        for person in [uid]+members:await self.c['emit'](person,dict(event='group.message',message=self.public(message,person)))
        return web.json_response({'group': self.descriptor(group, uid, True)}, status=201)

    async def detail(self, request):
        uid, gid = request['uid'], request.match_info['gid'];g = self.member(gid, uid)
        if request.method == 'PATCH':
            self.owner(gid, uid);self.c['limited']('group-edit:' + uid, 60, 3600);p = await request.json()
            self.c['moderation'].check_request(request);g=self.owner(gid,uid)
            if set(p) != {'name'}: self.reject('群资料参数不正确')
            name=self.check_name(p['name'])
            if name!=g['name']:self.c['write']('UPDATE chat_groups SET name=? WHERE id=?', (name, gid));g = self.member(gid, uid);await self.changed(gid)
        return web.json_response({'group': self.descriptor(g, uid, True)})

    async def membership(self, request):
        uid, gid = request['uid'], request.match_info['gid'];g = self.member(gid, uid)
        self.c['limited']('group-members:' + uid, 60, 3600);p = await request.json();action = p.get('action');removed = []
        self.c['moderation'].check_request(request);g=self.member(gid,uid)
        if type(p.get('epoch')) is not int or p.get('epoch') != g['epoch']: self.reject('群成员已变化，请重新打开群资料', 409)
        if action == 'invite':
            self.owner(gid, uid);members = self.invitations(uid, p.get('members'), self.ids(gid))
            added = [v for v in members if v not in self.ids(gid)]
            if not added:return web.json_response({'ok':True})
            if len(self.ids(gid)) + len(added) > self.MAX_MEMBERS: self.reject('群聊最多20人')
            with self.db:
                for person in added:self.db.execute('INSERT INTO group_members VALUES(?,?,?,?,?) ON CONFLICT(gid,uid) DO UPDATE SET active=1,joined=excluded.joined', (gid, person, self.c['now'](), 1, 0))
                if added:self.db.execute('UPDATE chat_groups SET epoch=epoch+1 WHERE id=?', (gid,))
        elif action in ('remove', 'leave', 'dissolve', 'transfer'):
            target = p.get('member') if action in ('remove', 'transfer') else uid
            if action != 'leave':self.owner(gid, uid)
            if action == 'transfer':
                if target == uid or target not in self.ids(gid):self.reject('请选择其他群成员')
                if self.c['row']('SELECT count(*) AS n FROM chat_groups WHERE owner=? AND active=1',(target,))['n']>=20:self.reject('对方的群主数量已达上限',429)
                self.c['write']('UPDATE chat_groups SET owner=?,epoch=epoch+1 WHERE id=?', (target, gid))
            elif action == 'dissolve':
                removed = self.ids(gid)
                with self.db:self.db.execute('UPDATE chat_groups SET active=0,epoch=epoch+1 WHERE id=?', (gid,));self.db.execute('UPDATE group_members SET active=0 WHERE gid=?', (gid,))
            else:
                if target not in self.ids(gid):self.reject('群成员不存在', 404)
                if g['owner'] == target and len(self.ids(gid)) > 1:self.reject('请先转让群主或解散群聊')
                removed = [target]
                with self.db:self.db.execute('UPDATE group_members SET active=0 WHERE gid=? AND uid=?', (gid, target));self.db.execute('UPDATE chat_groups SET epoch=epoch+1,active=? WHERE id=?', (0 if self.ids(gid) == [] else 1, gid))
        else:self.reject('群管理操作不正确')
        with self.db:
            for person in set(self.ids(gid)+removed):self.c['syncstate'].bump(person,peer=gid)
        await self.changed(gid, removed)
        if action!='dissolve':await self.system(gid,uid,{'invite':'新成员加入群聊','remove':'群成员已更新','leave':'有成员退出群聊','transfer':'群主已转让'}[action])
        self.gc(gid)
        return web.json_response({'ok': True})

    async def system(self,gid,sender,text):
        people=self.recipients(gid,sender)
        if not people:return
        g=self.c['row']('SELECT epoch FROM chat_groups WHERE id=?',(gid,));mid=str(uuid.uuid4());created=self.stamp()
        with self.db:
            self.db.execute('INSERT INTO group_messages VALUES(?,?,?,?,?,?,?,?,?,?)',(mid,gid,sender,'system:'+mid,g['epoch'],'call',json.dumps({'text':text}),None,created,64))
            for uid in people:self.db.execute('INSERT INTO group_deliveries VALUES(?,?)',(mid,uid))
        message=self.c['row']('SELECT * FROM group_messages WHERE id=?',(mid,))
        for uid in people:await self.c['emit'](uid,dict(event='group.message',message=self.public(message,uid)))

    async def identities(self, request):
        uid, gid = request['uid'], request.match_info['gid'];g = self.member(gid, uid)
        target = request.match_info.get('peer')
        if target:
            if target not in self.ids(gid) and not self.c['row']('SELECT 1 FROM group_messages m JOIN group_deliveries d ON d.mid=m.id WHERE m.gid=? AND m.sender=? AND d.uid=? LIMIT 1', (gid, target, uid)):self.reject('无权访问群成员身份', 403)
            identity = self.c['security'].identity_for(target) or {};key = request.query.get('key_id', '')
            if key and identity.get('key_id') != key:identity = self.c['recovery'].historical(target, key)
            return web.json_response(identity)
        people = []
        for person in self.recipients(gid, uid):
            identity = self.c['security'].identity_for(person)
            if not identity:self.reject('有成员尚未建立加密聊天身份，请对方登录新版后重试', 426)
            people.append(dict(id=person, **identity))
        return web.json_response({'epoch': g['epoch'], 'recipients': people})

    def visible(self, uid, gid, before=9223372036854775807, limit=50):
        return self.c['rows']('''SELECT m.id,m.gid,m.sender,m.client_id,m.epoch,m.type,m.file_id,m.created,m.n FROM group_messages m JOIN group_deliveries d ON d.mid=m.id WHERE m.gid=? AND d.uid=? AND m.created<?
          AND NOT EXISTS(SELECT 1 FROM group_hides h WHERE h.mid=m.id AND h.uid=?) AND m.created>coalesce((SELECT through FROM group_clears c WHERE c.gid=? AND c.uid=?),0)
          ORDER BY m.created DESC LIMIT ?''', (gid, uid, before, uid, gid, uid, limit))

    def public(self, m, viewer):
        if 'body' not in m:m=dict(m,body=self.c['row']('SELECT body FROM group_messages WHERE id=?',(m['id'],))['body'])
        value = {k: m[k] for k in ('id', 'sender', 'client_id', 'created', 'file_id', 'type')}
        value.update(recipient=viewer, group_id=m['gid'], group_epoch=m['epoch'], sender_user=self.c['social'].person(m['sender'], viewer), text='')
        body = json.loads(m['body'])
        if m['type'] == 'encrypted':
            envelopes = body['envelopes'];own = envelopes.get(viewer)
            if not own:self.reject('消息不属于此接收者', 403)
            manifest = [{'uid': uid, 'key_id': e['recipient_key'], 'hash': hashlib.sha256(base64.b64decode(e['cipher'], validate=True)).hexdigest()} for uid, e in sorted(envelopes.items())]
            value['envelope'] = dict(v=1, context=body['context'], sender_key=body['sender_key'], recipient_key=own['recipient_key'], cipher=own['cipher'], manifest=manifest, signature=body['signature'])
        else:value['text'] = body.get('text', '')
        if body.get('withdrawn'):value['withdrawn'] = True
        if m['file_id']:value['attachment_available'] = (self.c['DATA'] / 'uploads' / m['file_id']).is_file()
        value['readers'] = [r['uid'] for r in self.c['rows']('SELECT r.uid FROM group_members r JOIN group_deliveries d ON d.uid=r.uid AND d.mid=? WHERE r.gid=? AND r.uid<>? AND r.read_at>=?', (m['id'], m['gid'], m['sender'], m['created']))]
        value['read_count'] = len(value['readers'])
        return value

    def conversations(self, uid):
        result = [];budget=0
        for group in self.contacts(uid):
            latest = self.visible(uid, group['id'], limit=1)
            if not latest:continue
            read = self.c['row']('SELECT read_at FROM group_members WHERE gid=? AND uid=?', (group['id'], uid))['read_at']
            unread = self.c['row']('SELECT count(*) AS n FROM group_messages m JOIN group_deliveries d ON d.mid=m.id WHERE m.gid=? AND d.uid=? AND m.sender<>? AND m.created>? AND NOT EXISTS(SELECT 1 FROM group_hides h WHERE h.mid=m.id AND h.uid=?)', (group['id'], uid, uid, read, uid))['n']
            public=self.public(latest[0],uid);amount=len(json.dumps(public).encode())
            if budget+amount>2*1024*1024:
                public={k:public[k] for k in ('id','sender','recipient','group_id','group_epoch','created','client_id')};public.update(type='summary',text='[加密群消息，点开查看]')
            else:budget+=amount
            result.append(dict(user=group, last=public, unread=unread))
        return result

    async def messages(self, request):
        uid, gid = request['uid'], request.match_info['gid'];g = self.member(gid, uid)
        if request.method == 'GET':
            self.c['limited']('group-history:'+uid,60,60)
            before = min(int(request.query.get('before', '9223372036854775807')), 9223372036854775807)
            rows = self.visible(uid, gid, before, 51)
            result=[];budget=0
            for message in rows[:50]:
                public=self.public(message,uid);amount=len(json.dumps(public).encode())
                if result and budget+amount>2*1024*1024:break
                result.append(public);budget+=amount
            return web.json_response({'messages': list(reversed(result)), 'has_more': len(rows)>len(result)})
        self.c['limited']('group-send:' + uid, 60, 60);p = await request.json();client = self.check_client(p.get('client_id'))
        self.c['moderation'].check_request(request);g=self.member(gid,uid)
        existing = self.c['row']('SELECT * FROM group_receipts WHERE sender=? AND client_id=?', (uid, client))
        if existing:
            if existing['gid'] != gid:self.reject('此消息编号已用于另一群聊', 409)
            row = self.c['row']('SELECT * FROM group_messages WHERE id=?', (existing['mid'],))
            return web.json_response(self.public(row, uid) if row else dict(removed=True, client_id=client))
        epoch = p.get('epoch');fid = p.get('file_id') or ''
        if type(epoch) is not int or epoch != g['epoch']:self.reject('群成员已变化，请检查群资料后重试', 409)
        if fid and (not isinstance(fid, str) or not re.fullmatch(r'[a-f0-9]{32}', fid) or not self.c['row']('SELECT 1 FROM uploads WHERE id=? AND owner=? AND recipient=?', (fid, uid, gid))):self.reject('群附件不存在或不属于你', 403)
        context = f'qingliao:group-message:1:{gid}:{epoch}:{uid}:{client}:{fid}'
        if p.get('context') != context or type(p.get('v')) is not int or p.get('v') != 1:self.reject('群消息上下文不正确')
        identity = self.c['security'].identity_for(uid)
        if not identity or p.get('sender_key') != identity['key_id']:self.reject('群消息身份不正确', 409)
        envelopes = p.get('envelopes')
        if not isinstance(envelopes, dict) or set(envelopes) != set(self.recipients(gid, uid)):self.reject('群成员或接收权限已变化，请重试', 409)
        for person, envelope in envelopes.items():
            key = self.c['security'].identity_for(person)
            if not isinstance(envelope, dict) or set(envelope) != {'recipient_key', 'cipher'} or not key or envelope['recipient_key'] != key['key_id']:self.reject('群成员加密身份已变化，请重试', 409)
            cipher = envelope['cipher']
            if not isinstance(cipher, str) or not 8 <= len(cipher) <= 400000 or not 1 <= len(base64.b64decode(cipher, validate=True)) <= 300000:self.reject('群密文格式不正确')
        signature = p.get('signature')
        if not isinstance(signature, str) or not 8 <= len(signature) <= 8192: self.reject('群消息签名不正确')
        base64.b64decode(signature, validate=True)
        body = json.dumps(dict(context=context, sender_key=p['sender_key'], envelopes=envelopes, signature=signature), separators=(',', ':'))
        size = len(body.encode())
        if size > self.MAX_BODY:self.reject('群消息过大', 413)
        if self.c['row']('SELECT count(*) AS n,coalesce(sum(n),0) AS b FROM group_messages WHERE sender=?', (uid,))['b'] + size > 32 * 1024 * 1024 or self.c['row']('SELECT count(*) AS n FROM group_messages WHERE sender=?', (uid,))['n'] >= 10000:self.reject('群消息存储已满，请清理旧消息', 429)
        if self.c['row']('SELECT coalesce(sum(n),0) AS b FROM group_messages')['b'] + size > 192 * 1024 * 1024 or self.c['row']('SELECT count(*) AS n FROM group_receipts')['n'] >= 200000:self.reject('群消息存储暂满', 507)
        self.c['guard'].content_charge(uid, size + 256)
        mid = str(uuid.uuid4());created = self.stamp()
        with self.db:
            self.db.execute('INSERT INTO group_messages VALUES(?,?,?,?,?,?,?,?,?,?)', (mid, gid, uid, client, epoch, 'encrypted', body, fid or None, created, size))
            self.db.execute('INSERT INTO group_receipts VALUES(?,?,?,?,?)', (uid, client, gid, mid, created))
            for person in envelopes:self.db.execute('INSERT INTO group_deliveries VALUES(?,?)', (mid, person))
        message = self.c['row']('SELECT * FROM group_messages WHERE id=?', (mid,))
        for person in envelopes:await self.c['emit'](person, dict(event='group.message', message=self.public(message, person)))
        return web.json_response(self.public(message, uid))

    async def status(self, request):
        uid, gid = request['uid'], request.match_info['gid'];self.member(gid, uid)
        receipt = self.c['row']('SELECT * FROM group_receipts WHERE sender=? AND client_id=? AND gid=?', (uid, request.match_info['client'], gid))
        message = self.c['row']('SELECT * FROM group_messages WHERE id=?', (receipt['mid'],)) if receipt else None
        return web.json_response(dict(acknowledged=bool(receipt), message=self.public(message, uid) if message else None))

    async def read(self, request):
        uid, gid = request['uid'], request.match_info['gid'];self.member(gid, uid)
        self.c['limited']('group-read:' + uid, 300, 60)
        latest = self.c['row']('SELECT coalesce(max(m.created),0) AS t FROM group_messages m JOIN group_deliveries d ON d.mid=m.id WHERE m.gid=? AND d.uid=?', (gid, uid))['t']
        previous=self.c['row']('SELECT read_at FROM group_members WHERE gid=? AND uid=?',(gid,uid))['read_at']
        self.c['write']('UPDATE group_members SET read_at=max(read_at,?) WHERE gid=? AND uid=?', (latest, gid, uid))
        if latest>previous:
            for person in self.ids(gid):await self.c['emit'](person, dict(event='group.read', group_id=gid, reader=uid, at=latest))
        return web.json_response({'ok': True})

    async def clear(self, request):
        uid, gid = request['uid'], request.match_info['gid'];self.member(gid, uid)
        self.c['limited']('group-clear:'+uid,30,3600)
        latest = self.c['row']('SELECT coalesce(max(created),0) AS t FROM group_messages WHERE gid=?', (gid,))['t']
        with self.db:
            self.db.execute('INSERT INTO group_clears VALUES(?,?,?) ON CONFLICT(gid,uid) DO UPDATE SET through=max(through,excluded.through)', (gid, uid, latest))
            self.c['syncstate'].bump(uid,peer=gid)
        await self.c['emit'](uid, dict(event='conversation.cleared', peer=gid, through=latest))
        self.gc(gid)
        return web.json_response({'ok': True, 'through': latest})

    async def message_action(self, request):
        uid, gid, mid = request['uid'], request.match_info['gid'], request.match_info['mid'];self.member(gid, uid)
        message = self.c['row']('SELECT m.* FROM group_messages m JOIN group_deliveries d ON d.mid=m.id WHERE m.id=? AND m.gid=? AND d.uid=?', (mid, gid, uid))
        if not message:self.reject('群消息不存在', 404)
        self.c['limited']('group-action:' + uid, 120, 60)
        if request.method == 'DELETE':
            with self.db:
                self.db.execute('INSERT OR IGNORE INTO group_hides VALUES(?,?)', (mid, uid))
                self.c['syncstate'].bump(uid,peer=gid)
            await self.c['emit'](uid, dict(event='message.deleted', id=mid, peer=gid))
            self.gc(gid)
            return web.json_response({'ok': True})
        if message['sender'] != uid:self.reject('只能撤回自己发送的消息', 403)
        if message['type'] != 'encrypted' or self.c['now']() - message['created'] > 120000:self.reject('发送后2分钟内可以撤回')
        fid = message['file_id']
        with self.db:
            self.db.execute("UPDATE group_messages SET type='call',body=?,file_id=NULL,n=64 WHERE id=?", (json.dumps({'text': '消息已撤回', 'withdrawn': True}), mid))
            for delivery in self.c['rows']('SELECT uid FROM group_deliveries WHERE mid=?',(mid,)):self.c['syncstate'].bump(delivery['uid'],peer=gid)
        self.c['social'].file_gc(fid)
        message = self.c['row']('SELECT * FROM group_messages WHERE id=?', (mid,))
        for person in self.ids(gid):
            if self.c['row']('SELECT 1 FROM group_deliveries WHERE mid=? AND uid=?', (mid, person)):await self.c['emit'](person, dict(event='message.updated', message=self.public(message, person)))
        return web.json_response(self.public(message, uid))

    def file_allowed(self, uid, upload):
        if upload['owner'] == uid:return True
        try:self.member(upload['recipient'], uid)
        except self.c['ApiError']:return False
        return bool(self.c['row']('SELECT 1 FROM group_messages m JOIN group_deliveries d ON d.mid=m.id WHERE m.gid=? AND m.file_id=? AND d.uid=? LIMIT 1', (upload['recipient'], upload['id'], uid)))

    def gc(self,gid):
        items=self.c['rows']('''SELECT m.id,m.file_id FROM group_messages m WHERE m.gid=? AND NOT EXISTS(
          SELECT 1 FROM group_deliveries d JOIN group_members r ON r.uid=d.uid AND r.gid=m.gid WHERE d.mid=m.id AND r.active=1
          AND NOT EXISTS(SELECT 1 FROM group_hides h WHERE h.mid=m.id AND h.uid=d.uid)
          AND m.created>coalesce((SELECT through FROM group_clears c WHERE c.gid=m.gid AND c.uid=d.uid),0)) LIMIT 500''',(gid,))
        with self.db:
            for item in items:self.db.execute('DELETE FROM group_messages WHERE id=?',(item['id'],))
        for item in items:self.c['social'].file_gc(item['file_id'])

    def shared(self,a,b):
        return bool(self.c['row']('SELECT 1 FROM group_members a JOIN group_members b ON b.gid=a.gid JOIN chat_groups g ON g.id=a.gid WHERE a.uid=? AND b.uid=? AND a.active=1 AND b.active=1 AND g.active=1 LIMIT 1',(a,b)))

    def maintenance(self):
        candidates=self.c['rows']('SELECT id FROM chat_groups WHERE (active=0 OR EXISTS(SELECT 1 FROM group_clears c WHERE c.gid=chat_groups.id) OR EXISTS(SELECT 1 FROM group_messages m JOIN group_hides h ON h.mid=m.id WHERE m.gid=chat_groups.id)) AND EXISTS(SELECT 1 FROM group_messages m WHERE m.gid=chat_groups.id) LIMIT 4')
        for group in candidates:self.gc(group['id'])

    def routes(self, app):
        app.add_routes([web.get('/api/groups', self.collection), web.post('/api/groups', self.collection),
            web.get('/api/groups/{gid}', self.detail), web.patch('/api/groups/{gid}', self.detail),
            web.post('/api/groups/{gid}/members', self.membership), web.get('/api/groups/{gid}/keys', self.identities), web.get('/api/groups/{gid}/identities/{peer}', self.identities),
            web.get('/api/groups/{gid}/messages', self.messages), web.post('/api/groups/{gid}/messages', self.messages),
            web.get('/api/groups/{gid}/message-status/{client}', self.status), web.post('/api/groups/{gid}/read', self.read), web.post('/api/groups/{gid}/clear', self.clear),
            web.delete('/api/groups/{gid}/messages/{mid}', self.message_action), web.post('/api/groups/{gid}/messages/{mid}/withdraw', self.message_action)])
