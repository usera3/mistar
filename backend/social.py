"""User-controlled relationships and message lifecycle. Ciphertext is never decrypted here."""
import json
from aiohttp import web


class Social:
    def __init__(self,core):
        self.c,self.db=core,core['db']
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS blocks(owner TEXT NOT NULL,peer TEXT NOT NULL,nickname TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(owner,peer));
        CREATE INDEX IF NOT EXISTS blocks_peer ON blocks(peer,owner);
        CREATE TABLE IF NOT EXISTS conversation_clears(owner TEXT NOT NULL,peer TEXT NOT NULL,through INTEGER NOT NULL,PRIMARY KEY(owner,peer));
        CREATE TABLE IF NOT EXISTS message_hides(owner TEXT NOT NULL,message_id TEXT NOT NULL,PRIMARY KEY(owner,message_id));
        CREATE TABLE IF NOT EXISTS message_tombstones(message_id TEXT PRIMARY KEY,withdrawn INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS conversation_reads(owner TEXT NOT NULL,peer TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(owner,peer));
        INSERT OR IGNORE INTO conversation_reads SELECT owner,peer,read_at FROM friends;
        CREATE INDEX IF NOT EXISTS messages_recipient ON messages(recipient,sender,created);
        CREATE INDEX IF NOT EXISTS messages_file ON messages(file_id);
        CREATE INDEX IF NOT EXISTS messages_visibility ON messages(sender,recipient,created,id,file_id,client_id,type);
        CREATE TRIGGER IF NOT EXISTS message_usage_edit AFTER UPDATE OF text ON messages BEGIN
          UPDATE message_usage SET bytes=max(0,bytes-length(CAST(OLD.text AS BLOB))+length(CAST(NEW.text AS BLOB))) WHERE owner=NEW.sender;
        END;
        CREATE TRIGGER IF NOT EXISTS message_visibility_remove AFTER DELETE ON messages BEGIN
          DELETE FROM message_hides WHERE message_id=OLD.id;
          DELETE FROM message_tombstones WHERE message_id=OLD.id;
        END;
        ''')

    def blocked(self,a,b):
        return bool(self.c['row']('SELECT 1 FROM blocks WHERE (owner=? AND peer=?) OR (owner=? AND peer=?)',(a,b,b,a)))

    def accepted(self,a,b):
        return not self.blocked(a,b) and bool(self.c['row']("SELECT 1 FROM friends WHERE owner=? AND peer=? AND state='accepted'",(a,b)))

    def require_friend(self,a,b):
        if not self.accepted(a,b):self.c['reject']('当前无法联系对方，请先添加并通过好友验证',403)

    def person(self,uid,viewer):
        if uid!=viewer and self.blocked(uid,viewer):
            return {'id':uid,'username':'','nickname':'已屏蔽的联系人','online':False,'last_seen':0,'avatar_id':'','blocked':True,'friend':False}
        u=self.c['user_public'](uid,viewer)
        if u:u['friend']=self.accepted(viewer,uid)
        return u

    async def block_list(self,request):
        return web.json_response({'blocks':self.c['rows']('SELECT peer AS id,nickname,created FROM blocks WHERE owner=? ORDER BY created DESC',(request['uid'],))})

    async def relationship(self,request):
        uid,peer=request['uid'],request.match_info['peer']
        person=self.person(peer,uid)
        if not person:self.c['reject']('联系人不存在',404)
        person['blocked_by_me']=bool(self.c['row']('SELECT 1 FROM blocks WHERE owner=? AND peer=?',(uid,peer)))
        if person.get('bot'):person['bot_calls_allowed']=self.c['botcalls'].permission(uid,peer)
        return web.json_response(person)

    async def block(self,request):
        uid=request['uid'];p=await request.json();peer=str(p.get('peer',''));active=p.get('blocked')
        self.c['limited']('block:'+uid,30,3600)
        u=self.c['row']('SELECT nickname FROM users WHERE id=?',(peer,))
        if not u or peer==uid or not isinstance(active,bool):self.c['reject']('联系人不存在或操作无效')
        with self.db:
            if active:
                self.db.execute('INSERT OR IGNORE INTO blocks VALUES(?,?,?,?)',(uid,peer,u['nickname'],self.c['now']()))
                self.db.execute('DELETE FROM friends WHERE (owner=? AND peer=?) OR (owner=? AND peer=?)',(uid,peer,peer,uid))
                self.db.execute('DELETE FROM bot_call_permissions WHERE (owner=? AND bot=?) OR (owner=? AND bot=?)',(uid,peer,peer,uid))
            else:self.db.execute('DELETE FROM blocks WHERE owner=? AND peer=?',(uid,peer))
        if active:
            for owner,other in ((uid,peer),(peer,uid)):
                await self.c['set_typing'](owner,other,None,False)
            call=self.c['call_for'](uid)
            if call and {call['caller'],call['callee']}=={uid,peer}:await self.c['finish_call'](call,'ended',uid)
        for user in (uid,peer):await self.c['emit'](user,{'event':'relationship.changed','peer':peer if user==uid else uid})
        return web.json_response({'ok':True,'blocked':active})

    async def remove_friend(self,request):
        uid=request['uid'];peer=str((await request.json()).get('peer',''))
        if peer==uid or not self.c['row']('SELECT 1 FROM users WHERE id=?',(peer,)):self.c['reject']('联系人不存在',404)
        self.c['limited']('remove-friend:'+uid,30,3600)
        self.c['write']('DELETE FROM friends WHERE (owner=? AND peer=?) OR (owner=? AND peer=?)',(uid,peer,peer,uid))
        self.c['write']('DELETE FROM bot_call_permissions WHERE (owner=? AND bot=?) OR (owner=? AND bot=?)',(uid,peer,peer,uid))
        call=self.c['call_for'](uid)
        if call and {call['caller'],call['callee']}=={uid,peer}:await self.c['finish_call'](call,'ended',uid)
        for user in (uid,peer):await self.c['emit'](user,{'event':'relationship.changed','peer':peer if user==uid else uid})
        return web.json_response({'ok':True})

    def through(self,uid,peer):
        r=self.c['row']('SELECT through FROM conversation_clears WHERE owner=? AND peer=?',(uid,peer))
        return r['through'] if r else 0

    def read_at(self,uid,peer):
        a=self.c['row']('SELECT at FROM conversation_reads WHERE owner=? AND peer=?',(uid,peer))
        b=self.c['row']('SELECT read_at FROM friends WHERE owner=? AND peer=?',(uid,peer))
        return max(a['at'] if a else 0,b['read_at'] if b else 0)

    def history_ids(self,uid,peer,before,limit=51):
        return self.c['rows']('''SELECT id,created FROM messages m WHERE
        ((sender=? AND recipient=?) OR (sender=? AND recipient=?)) AND created<? AND created>?
        AND NOT EXISTS(SELECT 1 FROM message_hides h WHERE h.owner=? AND h.message_id=m.id)
        ORDER BY created DESC,rowid DESC LIMIT ?''',(uid,peer,peer,uid,before,self.through(uid,peer),uid,limit))

    async def clear(self,request):
        uid,peer=request['uid'],request.match_info['peer']
        if not self.c['row']('SELECT 1 FROM users WHERE id=?',(peer,)):self.c['reject']('联系人不存在',404)
        if not self.accepted(uid,peer) and not self.through(uid,peer) and not self.c['row']('SELECT 1 FROM messages WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?) LIMIT 1',(uid,peer,peer,uid)):self.c['reject']('聊天记录不存在',404)
        self.c['limited']('clear:'+uid,30,3600)
        top=self.c['row']('SELECT coalesce(max(created),0) AS t FROM messages WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?)',(uid,peer,peer,uid))['t']
        with self.db:
            self.db.execute('INSERT INTO conversation_clears VALUES(?,?,?) ON CONFLICT(owner,peer) DO UPDATE SET through=max(through,excluded.through)',(uid,peer,top))
            self.c['syncstate'].bump(uid,peer=peer)
        await self.c['emit'](uid,{'event':'conversation.cleared','peer':peer,'through':top})
        self.sweep(uid,peer)
        return web.json_response({'ok':True,'through':top})

    def owned_message(self,uid,mid):
        m=self.c['row']('SELECT * FROM messages WHERE id=?',(mid,))
        if not m or uid not in (m['sender'],m['recipient']):self.c['reject']('消息不存在',404)
        return m

    async def hide(self,request):
        uid,mid=request['uid'],request.match_info['mid'];m=self.owned_message(uid,mid)
        self.c['limited']('hide:'+uid,120,60)
        with self.db:
            self.db.execute('INSERT OR IGNORE INTO message_hides VALUES(?,?)',(uid,mid))
            self.c['syncstate'].bump(uid,peer=m['recipient'] if uid==m['sender'] else m['sender'])
        await self.c['emit'](uid,{'event':'message.deleted','id':mid,'peer':m['recipient'] if uid==m['sender'] else m['sender']})
        self.sweep(m['sender'],m['recipient'])
        return web.json_response({'ok':True})

    def file_gc(self,fid):
        if not fid or self.c['row']('SELECT 1 FROM messages WHERE file_id=? LIMIT 1',(fid,)):return
        (self.c['DATA']/'uploads'/fid).unlink(missing_ok=True)
        self.c['write']('DELETE FROM uploads WHERE id=?',(fid,))

    def sweep(self,a,b):
        ca,cb=self.through(a,b),self.through(b,a)
        items=self.c['rows']('''SELECT id,file_id,sender,recipient,client_id,created,type FROM messages m WHERE
        ((sender=? AND recipient=?) OR (sender=? AND recipient=?))
        AND (created<=? OR EXISTS(SELECT 1 FROM message_hides WHERE owner=? AND message_id=m.id))
        AND (created<=? OR EXISTS(SELECT 1 FROM message_hides WHERE owner=? AND message_id=m.id)) LIMIT 500''',(a,b,b,a,ca,a,cb,b))
        self.dispose(items)

    def dispose(self,items):
        files=set()
        with self.db:
            for item in items:
                # Preserve retry protection when cleaning records predating delivery_receipts.
                user_message=item['type']!='call' or self.c['row']('SELECT 1 FROM message_tombstones WHERE message_id=?',(item['id'],))
                if user_message and item['client_id'] and not self.c['row']('SELECT 1 FROM delivery_receipts WHERE owner=? AND client=?',(item['sender'],item['client_id'])):
                    try:self.c['syncstate'].reserve(item['sender'])
                    except self.c['ApiError']:continue
                    self.c['syncstate'].record(item['sender'],item['client_id'],item['recipient'],item['id'],self.c['now']())
                self.db.execute('UPDATE delivery_receipts SET created=max(created,?) WHERE owner=? AND client=?',(self.c['now'](),item['sender'],item['client_id']))
                self.db.execute('DELETE FROM messages WHERE id=?',(item['id'],))
                if item['file_id']:files.add(item['file_id'])
        for fid in files:self.file_gc(fid)

    def maintenance_sweep(self):
        # Covering metadata index avoids loading ciphertext; each tick deletes at most 500 rows.
        items=self.c['rows']('''SELECT id,file_id,sender,recipient,client_id,created,type FROM messages AS m INDEXED BY messages_visibility WHERE
        (created<=coalesce((SELECT through FROM conversation_clears WHERE owner=m.sender AND peer=m.recipient),0) OR EXISTS(SELECT 1 FROM message_hides WHERE owner=m.sender AND message_id=m.id))
        AND (created<=coalesce((SELECT through FROM conversation_clears WHERE owner=m.recipient AND peer=m.sender),0) OR EXISTS(SELECT 1 FROM message_hides WHERE owner=m.recipient AND message_id=m.id))
        AND (type='call' OR EXISTS(SELECT 1 FROM delivery_receipts WHERE owner=m.sender AND client=m.client_id)
          OR (coalesce((SELECT n FROM receipt_usage WHERE owner=m.sender),0)<100000 AND (SELECT coalesce(sum(n),0) FROM receipt_usage)<200000))
        LIMIT 500''')
        self.dispose(items)

    async def withdraw(self,request):
        uid,mid=request['uid'],request.match_info['mid'];m=self.owned_message(uid,mid)
        if m['sender']!=uid:self.c['reject']('只能撤回自己发送的消息',403)
        if self.c['row']('SELECT 1 FROM message_tombstones WHERE message_id=?',(mid,)):return web.json_response(self.c['message_public'](m))
        if m['type']=='call' or self.c['now']()-m['created']>120000:self.c['reject']('发送后 2 分钟内可以撤回')
        self.c['limited']('withdraw:'+uid,30,60)
        with self.db:
            self.db.execute('INSERT INTO message_tombstones VALUES(?,?)',(mid,self.c['now']()))
            self.db.execute("UPDATE messages SET type='call',text='消息已撤回',file_id=NULL WHERE id=?",(mid,))
            self.c['syncstate'].bump(m['sender'],m['recipient'])
        self.file_gc(m.get('file_id'))
        result=self.c['message_public'](self.c['row']('SELECT * FROM messages WHERE id=?',(mid,)))
        for user in (m['sender'],m['recipient']):await self.c['emit'](user,{'event':'message.updated','message':result})
        return web.json_response(result)

    def decorate(self,m):
        if self.c['row']('SELECT 1 FROM message_tombstones WHERE message_id=?',(m['id'],)):m['withdrawn']=True
        if m.get('file_id'):
            m['attachment_available']=(self.c['DATA']/'uploads'/m['file_id']).is_file()
        return m

    def routes(self,app):
        app.add_routes([web.get('/api/relationships/{peer}',self.relationship),web.get('/api/blocks',self.block_list),web.post('/api/blocks',self.block),
            web.post('/api/friends/remove',self.remove_friend),web.post('/api/conversations/{peer}/clear',self.clear),
            web.delete('/api/messages/{mid}',self.hide),web.post('/api/messages/{mid}/withdraw',self.withdraw)])
