"""Short-lived encrypted location relay. No coordinates or location history in the database."""
import base64
import hashlib
import json
import time
import uuid
from aiohttp import web


class Locations:
    def __init__(self, core): self.c, self.shares, self.closed = core, {}, {}

    def reject(self, text='位置共享已结束或无权查看', status=404): self.c['reject'](text, status)

    def identifier(self, value):
        if not isinstance(value, str) or str(uuid.UUID(value)) != value: self.reject('共享编号不正确', 400)
        return value

    def audience(self, owner, peer):
        self.c['moderation'].ensure_account(owner)
        if self.c['groups'].is_id(peer):
            group = self.c['groups'].member(peer, owner)
            return set(self.c['groups'].recipients(peer, owner)), group['epoch']
        self.c['friend'](owner, peer)
        if self.c['bots'].get(owner) or self.c['bots'].get(peer): self.reject('实时位置仅向普通好友或好友群共享', 403)
        return {owner, peer}, 0

    def valid(self, share):
        if share['deadline'] <= time.monotonic() or time.monotonic()-share['touched'] > 75: return False
        if not self.c['row']('SELECT 1 FROM sessions WHERE hash=? AND user_id=? AND expires>?', (share['session'], share['owner'], self.c['now']())): return False
        try:
            members, epoch = self.audience(share['owner'], share['peer'])
            return members == share['members'] and epoch == share['epoch']
        except Exception: return False

    def public(self, share):
        return {k: share[k] for k in ('id','owner','peer','epoch','until','seq')}

    async def close(self, share, reason='stopped'):
        if self.shares.get(share['id']) is not share: return
        del self.shares[share['id']]
        self.closed[share['id']]=time.monotonic()+9*3600
        share.pop('wire', None)
        event = dict(event='location.closed', share_id=share['id'], owner=share['owner'], peer=share['peer'], reason=reason)
        for uid in sorted(share['members']): await self.c['emit'](uid, event)

    async def tick(self):
        self.closed={sid:until for sid,until in self.closed.items() if until>time.monotonic()}
        for share in list(self.shares.values()):
            if not self.valid(share): await self.close(share, 'ended')

    async def create(self, request):
        uid=request['uid'];self.c['limited']('location-create:'+uid, 12, 60)
        p=await request.json();self.c['moderation'].check_request(request)
        if not isinstance(p, dict) or set(p) != {'id','peer','duration','epoch'}: self.reject('共享参数不正确', 400)
        sid=self.identifier(p['id']);peer=p['peer'];duration=p['duration']
        if not isinstance(peer, str) or type(duration) is not int or duration not in (900,3600,28800): self.reject('请选择共享时长', 400)
        members, epoch=self.audience(uid, peer)
        if type(p['epoch']) is not int or epoch != p['epoch']: self.reject('群成员已变化，请重新开始共享', 409)
        previous=self.shares.get(sid)
        if previous:
            if previous['owner']!=uid or previous['peer']!=peer or previous['duration']!=duration: self.reject('共享编号已使用', 409)
            if not self.valid(previous): await self.close(previous, 'ended');self.reject()
            return web.json_response(dict(share=self.public(previous)))
        if sid in self.closed: self.reject('此共享已结束，请重新开始',409)
        if len(self.closed)>=10000: self.reject('位置共享暂忙，请稍后再试',429)
        if any(s['owner']==uid for s in self.shares.values()): self.reject('请先停止当前的位置共享', 409)
        if len(self.shares)>=128: self.reject('位置共享繁忙，请稍后再试', 429)
        token=request.headers.get('Authorization','').removeprefix('Bearer ')
        now=time.monotonic()
        share=dict(id=sid,owner=uid,peer=peer,members=members,epoch=epoch,duration=duration,until=self.c['now']()+duration*1000,deadline=now+duration,touched=now,last=0,seq=0,wire=None,session=hashlib.sha256(token.encode()).hexdigest())
        self.shares[sid]=share
        return web.json_response(dict(share=self.public(share)),status=201)

    def dto(self, share, viewer):
        p=share['wire'];value=dict(sender=share['owner'],recipient=viewer,type='encrypted',text='',share_id=share['id'],seq=share['seq'],peer=share['peer'],until=share['until'],epoch=share['epoch'])
        if share['epoch']:
            envelopes=p['envelopes'];own=envelopes[viewer]
            manifest=[dict(uid=uid,key_id=e['recipient_key'],hash=hashlib.sha256(base64.b64decode(e['cipher'],validate=True)).hexdigest()) for uid,e in sorted(envelopes.items())]
            value['envelope']=dict(v=1,context=p['context'],sender_key=p['sender_key'],recipient_key=own['recipient_key'],cipher=own['cipher'],manifest=manifest,signature=p['signature'])
        else:value['envelope']=p['envelope']
        return value

    async def item(self, request):
        uid=request['uid'];sid=self.identifier(request.match_info['sid']);share=self.shares.get(sid)
        self.c['limited']('location-access:'+uid, 120, 60)
        if not share or uid not in share['members']: self.reject()
        if not self.valid(share): await self.close(share,'ended');self.reject()
        if request.method=='DELETE':
            if uid!=share['owner']: self.reject('只有发起人可停止自己的共享',403)
            await self.close(share);return web.json_response(dict(ok=True))
        if request.method=='GET':
            return web.json_response(dict(share=self.public(share),update=self.dto(share,uid) if share['wire'] else None))
        if uid!=share['owner']: self.reject('只能更新自己的位置',403)
        p=await request.json();self.c['moderation'].check_request(request)
        if self.shares.get(sid) is not share or not self.valid(share): self.reject()
        if not isinstance(p,dict) or set(p)!={'seq','wire'} or type(p['seq']) is not int or not share['seq']<p['seq']<=100000: self.reject('位置更新编号已过期',409)
        if time.monotonic()-share['last']<5: self.reject('位置更新过于频繁',429)
        wire=p['wire']
        if not isinstance(wire,dict) or len(json.dumps(wire).encode())>96*1024: self.reject('位置更新过大',413)
        context=f"qingliao:location:1:{sid}:{p['seq']}:{share['owner']}:{share['peer']}:{share['epoch']}"
        def encoded(value):
            if not isinstance(value,str) or not 8<=len(value)<=8192 or len(base64.b64decode(value,validate=True))>6144: self.reject('加密位置格式不正确',400)
        if share['epoch']:
            if set(wire)!={'v','context','sender_key','envelopes','signature'} or type(wire.get('v')) is not int or wire['v']!=1 or wire['context']!=context: self.reject('群位置上下文不正确',400)
            identity=self.c['security'].identity_for(uid)
            if not identity or wire['sender_key']!=identity['key_id']: self.reject('位置身份已变化',409)
            envelopes=wire['envelopes']
            if not isinstance(envelopes,dict) or set(envelopes)!=share['members']: self.reject('共享接收范围已变化',409)
            for person,e in envelopes.items():
                key=self.c['security'].identity_for(person)
                if not isinstance(e,dict) or set(e)!={'recipient_key','cipher'} or not key or e['recipient_key']!=key['key_id']: self.reject('群成员身份已变化',409)
                encoded(e['cipher'])
            encoded(wire['signature'])
        else:
            if set(wire)!={'envelope'} or not isinstance(wire['envelope'],dict): self.reject('加密位置格式不正确',400)
            if set(wire['envelope'])!={'v','context','sender_key','recipient_key','sender_cipher','recipient_cipher','signature'} or type(wire['envelope'].get('v')) is not int or wire['envelope']['v']!=1: self.reject('加密位置格式不正确',400)
            for field in ('sender_cipher','recipient_cipher','signature'): encoded(wire['envelope'].get(field))
            self.c['security'].check_envelope(uid,share['peer'],wire,context)
        # Keep only one encrypted update per share; no message/unread/file/history writes.
        if sum(len(json.dumps(s['wire']).encode()) for s in self.shares.values() if s['wire'])+len(json.dumps(wire).encode())>8*1024*1024: self.reject('位置共享暂忙',429)
        share.update(seq=p['seq'],wire=wire,last=time.monotonic(),touched=time.monotonic())
        for person in sorted(share['members']):
            if not self.valid(share): await self.close(share,'ended');self.reject()
            await self.c['emit'](person,dict(event='location.live',update=self.dto(share,person)))
        return web.json_response(dict(ok=True,seq=share['seq']))

    def routes(self, app):
        app.router.add_post('/api/locations',self.create)
        app.router.add_route('GET','/api/locations/{sid}',self.item)
        app.router.add_route('PUT','/api/locations/{sid}',self.item)
        app.router.add_route('DELETE','/api/locations/{sid}',self.item)
