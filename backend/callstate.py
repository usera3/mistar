"""Short signalling reconnect grace without permitting call takeover after process restart."""
import time
from aiohttp import web


class CallState:
    GRACE=20
    def __init__(self,core):self.c=core;self.disconnected={}

    async def state(self,request):
        uid=request['uid'];self.c['limited']('call-state:'+uid,60,60)
        call=self.c['call_for'](uid)
        if not call:return web.json_response({'call':None})
        peer=call['callee'] if uid==call['caller'] else call['caller']
        return web.json_response({'call':self.c['call_public'](call),'user':self.c['user_public'](peer,uid),'peer_reconnecting':peer in self.disconnected.get(call['id'],{}),'grace_seconds':self.GRACE})

    async def lost(self,uid):
        call=self.c['call_for'](uid)
        if not call:return
        self.disconnected.setdefault(call['id'],{})[uid]=time.monotonic()+self.GRACE
        peer=call['callee'] if uid==call['caller'] else call['caller']
        await self.c['emit'](peer,{'event':'call.peer_reconnecting','call_id':call['id'],'seconds':self.GRACE})

    async def restored(self,uid):
        call=self.c['call_for'](uid)
        if not call:return
        pending=self.disconnected.get(call['id'],{})
        if uid not in pending:return
        pending.pop(uid,None)
        peer=call['callee'] if uid==call['caller'] else call['caller']
        await self.c['emit'](peer,{'event':'call.peer_reconnected','call_id':call['id']})

    async def tick(self):
        for cid,people in list(self.disconnected.items()):
            call=self.c['calls'].get(cid)
            if not call:self.disconnected.pop(cid,None);continue
            if any(deadline<=time.monotonic() and not self.c['online'].get(uid) for uid,deadline in people.items()):
                await self.c['finish_call'](call,'offline');self.disconnected.pop(cid,None)
            elif not people:self.disconnected.pop(cid,None)

    def routes(self,app):app.add_routes([web.get('/api/call',self.state)])
