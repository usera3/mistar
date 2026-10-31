"""Owner-only storage accounting and removal. Never inspects encrypted media content."""
import time
from aiohttp import web


class Storage:
    def __init__(self,core):self.c=core

    async def summary(self,request):
        uid=request['uid'];row=self.c['row']
        media=row('SELECT coalesce(sum(size),0) AS bytes,count(*) AS files FROM uploads WHERE owner=?',(uid,))
        pending=row('SELECT coalesce(sum(size),0) AS bytes,count(*) AS files FROM uploads WHERE owner=? AND NOT EXISTS(SELECT 1 FROM messages WHERE file_id=uploads.id) AND NOT EXISTS(SELECT 1 FROM group_messages WHERE file_id=uploads.id)',(uid,))
        avatar=row('SELECT coalesce(sum(size),0) AS bytes FROM avatars WHERE owner=?',(uid,))['bytes']
        moments=row('SELECT coalesce(sum(size),0) AS bytes FROM post_photos WHERE owner=?',(uid,))['bytes']
        day=int(time.time()//86400)
        today=row('SELECT bytes FROM upload_usage WHERE owner=? AND day=?',(uid,day)) or {'bytes':0}
        fresh=self.c['now']()-row('SELECT created FROM users WHERE id=?',(uid,))['created']<86400000
        return web.json_response({'used':media['bytes']+avatar+moments,'quota':self.c['guard'].media_limit(uid),
            'sent':media['bytes']-pending['bytes'],'pending':pending['bytes'],'pending_files':pending['files'],
            'avatar':avatar,'moments':moments,'files':media['files'],'upload_today':today['bytes'],
            'upload_daily_limit':self.c['guard'].daily_limit(uid,fresh),'resets_at':(day+1)*86400000})

    async def files(self,request):
        uid=request['uid'];before=request.query.get('before','~')
        if len(before)>32:self.c['reject']('分页参数不正确')
        items=self.c['rows']('''SELECT id,recipient,size,created,
            (EXISTS(SELECT 1 FROM messages WHERE file_id=u.id) OR EXISTS(SELECT 1 FROM group_messages WHERE file_id=u.id)) AS sent FROM uploads u
            WHERE owner=? AND id<? ORDER BY id DESC LIMIT 51''',(uid,before))
        return web.json_response({'files':items[:50],'next':items[49]['id'] if len(items)>50 else ''})

    def remove(self,fid):
        (self.c['DATA']/'uploads'/fid).unlink(missing_ok=True)
        self.c['write']('DELETE FROM uploads WHERE id=?',(fid,))

    async def cleanup(self,request):
        uid=request['uid'];self.c['limited']('storage-clean:'+uid,10,3600)
        # Completed but unsent uploads only. Active .part streams are never touched.
        items=self.c['rows']('SELECT id,size FROM uploads WHERE owner=? AND NOT EXISTS(SELECT 1 FROM messages WHERE file_id=uploads.id) AND NOT EXISTS(SELECT 1 FROM group_messages WHERE file_id=uploads.id) LIMIT 5000',(uid,))
        for item in items:self.remove(item['id'])
        return web.json_response({'ok':True,'files':len(items),'bytes':sum(x['size'] for x in items)})

    async def delete(self,request):
        uid,fid=request['uid'],request.match_info['fid']
        self.c['limited']('storage-delete:'+uid,120,60)
        f=self.c['row']('SELECT * FROM uploads WHERE id=? AND owner=?',(fid,uid))
        if not f:self.c['reject']('附件不存在或无权删除',404)
        with self.c['db']:
            self.c['db'].execute('DELETE FROM uploads WHERE id=?',(fid,))
            if self.c['groups'].is_id(f['recipient']):
                for member in self.c['groups'].ids(f['recipient']):self.c['syncstate'].bump(member,peer=f['recipient'])
            else:self.c['syncstate'].bump(uid,f['recipient'])
        (self.c['DATA']/'uploads'/fid).unlink(missing_ok=True)
        people=set([uid]+self.c['groups'].ids(f['recipient'])) if self.c['groups'].is_id(f['recipient']) else (uid,f['recipient'])
        for user in people:await self.c['emit'](user,{'event':'file.removed','id':fid})
        return web.json_response({'ok':True})

    def routes(self,app):
        app.add_routes([web.get('/api/storage',self.summary),web.get('/api/storage/files',self.files),
            web.post('/api/storage/cleanup',self.cleanup),web.delete('/api/storage/files/{fid}',self.delete)])
