"""Bounded delivery receipts survive user-visible deletion; no message plaintext is copied."""
from aiohttp import web


class SyncState:
    def __init__(self,core):
        self.c,self.db=core,core['db']
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS history_versions(owner TEXT PRIMARY KEY,revision INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS history_changes(owner TEXT NOT NULL,revision INTEGER NOT NULL,peer TEXT NOT NULL,PRIMARY KEY(owner,revision));
        CREATE TABLE IF NOT EXISTS delivery_receipts(owner TEXT NOT NULL,client TEXT NOT NULL,peer TEXT NOT NULL,message_id TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(owner,client));
        CREATE INDEX IF NOT EXISTS receipt_expiry ON delivery_receipts(created);
        CREATE TABLE IF NOT EXISTS receipt_usage(owner TEXT PRIMARY KEY,n INTEGER NOT NULL);
        INSERT OR IGNORE INTO receipt_usage SELECT owner,count(*) FROM delivery_receipts GROUP BY owner;
        CREATE TRIGGER IF NOT EXISTS receipt_added AFTER INSERT ON delivery_receipts BEGIN
          INSERT INTO receipt_usage VALUES(NEW.owner,1) ON CONFLICT(owner) DO UPDATE SET n=n+1;
        END;
        CREATE TRIGGER IF NOT EXISTS receipt_removed AFTER DELETE ON delivery_receipts BEGIN
          UPDATE receipt_usage SET n=max(0,n-1) WHERE owner=OLD.owner;
        END;
        ''')

    def bump(self,*users,peer=None):
        for uid in set(users):
            self.db.execute('INSERT INTO history_versions VALUES(?,1) ON CONFLICT(owner) DO UPDATE SET revision=revision+1',(uid,))
            revision=self.c['row']('SELECT revision FROM history_versions WHERE owner=?',(uid,))['revision']
            other=peer or next((x for x in users if x!=uid),'*')
            self.db.execute('INSERT INTO history_changes VALUES(?,?,?)',(uid,revision,other))
            self.db.execute('DELETE FROM history_changes WHERE owner=? AND revision<=?',(uid,revision-100))

    def reserve(self,uid):
        own=self.c['row']('SELECT n FROM receipt_usage WHERE owner=?',(uid,)) or {'n':0}
        if own['n']>=100000 or self.c['row']('SELECT coalesce(sum(n),0) AS n FROM receipt_usage')['n']>=200000:
            self.c['reject']('发送确认记录空间已满，请稍后再试',507)

    def record(self,uid,client,peer,mid,created):self.db.execute('INSERT INTO delivery_receipts VALUES(?,?,?,?,?)',(uid,client,peer,mid,created))

    def lookup(self,uid,client):
        receipt=self.c['row']('SELECT * FROM delivery_receipts WHERE owner=? AND client=?',(uid,client))
        message=self.c['row']('SELECT * FROM messages WHERE sender=? AND client_id=?',(uid,client))
        if not receipt and not message:return None,None
        peer=receipt['peer'] if receipt else message['recipient']
        found=receipt or {'peer':peer,'message_id':message['id'],'created':message['created']}
        if message and (message['created']<=self.c['social'].through(uid,peer) or self.c['row']('SELECT 1 FROM message_hides WHERE owner=? AND message_id=?',(uid,message['id']))):message=None
        return found,message

    async def status(self,request):
        uid,client=request['uid'],request.match_info['client']
        if not 1<=len(client)<=80:self.c['reject']('发送标识不正确')
        found,message=self.lookup(uid,client)
        return web.json_response({'acknowledged':found is not None,'message':self.c['message_public'](message) if message else None})

    async def version(self,request):
        uid=request['uid'];after=int(request.query.get('after','0'))
        if not 0<=after<2**63:self.c['reject']('同步标识不正确')
        value=self.c['row']('SELECT revision FROM history_versions WHERE owner=?',(uid,));revision=value['revision'] if value else 0
        changes=self.c['rows']('SELECT revision,peer FROM history_changes WHERE owner=? AND revision>? ORDER BY revision',(uid,after))
        reset=after>revision or bool(changes and changes[0]['revision']>after+1) or any(c['peer']=='*' for c in changes)
        return web.json_response({'revision':revision,'reset':reset,'peers':sorted({c['peer'] for c in changes if c['peer']!='*'})})

    def reap(self):
        self.c['write']('DELETE FROM delivery_receipts WHERE rowid IN (SELECT rowid FROM delivery_receipts WHERE created<? LIMIT 5000)',(self.c['now']()-90*86400000,))

    def routes(self,app):app.add_routes([web.get('/api/message-status/{client}',self.status),web.get('/api/history-version',self.version)])
