"""Real owned WebSocket reconnect and expiry with a controllable grace deadline."""
import asyncio,os,tempfile,time
from aiohttp.test_utils import TestClient,TestServer

async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-callstate-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0')
        import server as s
        async with TestClient(TestServer(s.app)) as client:
            async def api(path,p=None,token='',status=200,method='POST'):
                async with client.request(method,'/api'+path,json=p,headers={'Authorization':'Bearer '+token}) as r:
                    data=await r.json();assert r.status==status,(path,r.status,data);return data
            a,b,c=[await api('/register',dict(username=n,nickname=n,password='callstate-test-password')) for n in ('alice','bobby','carol')]
            at,bt,ct=a['token'],b['token'],c['token'];aid,bid=a['user']['id'],b['user']['id']
            await api('/friends/request',{'peer':bid},at);await api('/friends/respond',{'peer':aid,'accept':True},bt)
            async def socket(token):
                ws=await client.ws_connect('/ws');await ws.send_json({'token':token});assert (await ws.receive_json())['event']=='ready';return ws
            wa,wb=await socket(at),await socket(bt)
            await s.signaling(aid,{'event':'call.invite','peer':bid})
            call=s.call_for(aid);cid=call['id'];await s.signaling(bid,{'event':'call.accept','call_id':cid})
            try:await s.signaling(aid,{'event':'call.invite','peer':bid})
            except s.ApiError as error:assert '通话中' in error.message
            else:raise AssertionError('Busy call accepted')
            assert (await api('/call',token=ct,method='GET'))['call'] is None
            await wa.close();await asyncio.sleep(.05)
            assert s.call_for(aid) and aid in s.callstate.disconnected[cid]
            view=await api('/call',token=bt,method='GET');assert view['call']['id']==cid and view['peer_reconnecting']
            wa=await socket(at);assert aid not in s.callstate.disconnected.get(cid,{})
            assert (await api('/call',token=at,method='GET'))['call']['accepted']
            await wa.close();await asyncio.sleep(.05);s.callstate.disconnected[cid][aid]=time.monotonic()-1;await s.callstate.tick()
            assert s.call_for(aid) is None and (await api('/call',token=bt,method='GET'))['call'] is None
            assert s.row("SELECT count(*) AS n FROM messages WHERE client_id=?",('call:'+cid,))['n']==1
            await wb.close()
            print('PASS round 28: authenticated own-call resync, stranger isolation, real WS grace/rejoin, deadline expiry and one durable call record')
            wa,wb=await socket(at),await socket(bt)
            await s.signaling(aid,{'event':'call.invite','peer':bid});late=s.call_for(aid);late['created']=s.now()-45001
            assert s.call_public(late)['expires_in_ms']==0
            try:await s.signaling(bid,{'event':'call.accept','call_id':late['id']})
            except s.ApiError:pass
            else:raise AssertionError('Expired invitation accepted')
            await s.signaling(aid,{'event':'call.invite','peer':bid});half=s.call_for(aid)
            await s.signaling(bid,{'event':'call.accept','call_id':half['id']})
            await s.signaling(aid,{'event':'call.connected','call_id':half['id']});assert not half.get('connected')
            half['accepted_at']=s.now()-45001
            try:await s.signaling(bid,{'event':'call.connected','call_id':half['id']})
            except s.ApiError:pass
            else:raise AssertionError('Expired media handshake accepted')
            assert not s.call_for(aid);await wa.close();await wb.close()
            print('PASS round 37: busy rejection, server-relative countdown, expired accept blocked and one-sided media cannot disable connection timeout')
        s.db.close()

if __name__=='__main__':asyncio.run(main())
