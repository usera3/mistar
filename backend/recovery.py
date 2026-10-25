"""Verified owner may start a new identity; past ciphertext is never decrypted or rekeyed."""
import hashlib,re
from aiohttp import web


class Recovery:
    def __init__(self,core):
        self.c,self.db=core,core['db']
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS identity_archive(user_id TEXT NOT NULL,key_id TEXT NOT NULL,enc TEXT NOT NULL,sig TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(user_id,key_id));
        CREATE TABLE IF NOT EXISTS identity_restarts(user_id TEXT NOT NULL,operation TEXT NOT NULL,key_id TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(user_id,operation));
        ''')

    async def restart(self,request):
        uid=request['uid'];p=await request.json();sec=self.c['security']
        operation=p.get('operation','')
        if not isinstance(operation,str) or not re.fullmatch(r'[a-zA-Z0-9-]{16,64}',operation):self.c['reject']('操作编号不正确')
        self.c['limited']('identity-attempt:'+uid,6,3600)
        user=self.c['row']('SELECT * FROM users WHERE id=?',(uid,))
        if not user['email']:self.c['reject']('请先绑定并验证邮箱')
        if not await sec.password_matches(user,p.get('password','')):self.c['reject']('当前密码不正确',403)
        self.c['auth'](request.headers.get('Authorization','').removeprefix('Bearer '))
        enc,sig=p.get('enc'),p.get('sig');sec.validate_public(enc,'HpkePublicKey');sec.validate_public(sig,'EcdsaPublicKey')
        kid=hashlib.sha256((enc+'\n'+sig).encode()).hexdigest()
        prior=self.c['row']('SELECT key_id FROM identity_restarts WHERE user_id=? AND operation=?',(uid,operation))
        if prior:
            if prior['key_id']!=kid:self.c['reject']('操作编号已用于其他身份',409)
            return web.json_response({'ok':True,'key_id':kid,'relogin':True})
        old=sec.identity_for(uid)
        if not old or old['key_id']==kid:self.c['reject']('请先使用或导入当前聊天身份')
        if self.c['row']('SELECT count(*) AS n FROM identity_archive WHERE user_id=?',(uid,))['n']>=32 or self.c['row']('SELECT count(*) AS n FROM identity_archive')['n']>=1024:
            self.c['reject']('聊天身份归档空间已满，请联系管理员',507)
        if self.c['row']('SELECT 1 FROM identity_restarts WHERE user_id=? AND created>?',(uid,self.c['now']()-86400000)):
            self.c['reject']('24 小时内只能重新开始一次加密聊天',429)
        vid=sec.verify(p,'identity_restart',user['email'],uid)
        peers=[r['peer'] for r in self.c['rows']('SELECT peer FROM friends WHERE owner=?',(uid,))]
        with self.db:
            sec.consume(vid)
            self.db.execute('INSERT OR IGNORE INTO identity_archive VALUES(?,?,?,?,?)',(uid,old['key_id'],old['enc'],old['sig'],self.c['now']()))
            self.db.execute('UPDATE crypto_identities SET key_id=?,enc=?,sig=?,created=? WHERE user_id=?',(kid,enc,sig,self.c['now'](),uid))
            self.db.execute('INSERT INTO identity_restarts VALUES(?,?,?,?)',(uid,operation,kid,self.c['now']()))
            self.db.execute('DELETE FROM friends WHERE owner=? OR peer=?',(uid,uid))
            self.db.execute('DELETE FROM bot_call_permissions WHERE owner=? OR bot=?',(uid,uid))
            self.db.execute('DELETE FROM sessions WHERE user_id=?',(uid,))
            self.c['syncstate'].bump(uid)
            self.db.execute('UPDATE verifications SET used=1 WHERE user_id=?',(uid,))
        call=self.c['call_for'](uid)
        if call:await self.c['finish_call'](call,'ended',uid)
        for peer in peers:await self.c['emit'](peer,{'event':'relationship.changed','peer':uid})
        for ws in tuple(self.c['online'].get(uid,set())):await ws.close(code=1008,message=b'credentials changed')
        return web.json_response({'ok':True,'key_id':kid,'relogin':True})

    def historical(self,uid,kid):
        if not re.fullmatch(r'[a-f0-9]{64}',kid):self.c['reject']('身份编号不正确')
        return self.c['row']('SELECT key_id,enc,sig FROM identity_archive WHERE user_id=? AND key_id=?',(uid,kid)) or {}

    def routes(self,app):app.add_routes([web.post('/api/crypto/restart',self.restart)])
