"""Delivery receipts must not resurrect records cleared while a sender was offline."""
import asyncio,os,tempfile
from aiohttp.test_utils import TestClient,TestServer

async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-sync-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0')
        import server as s
        async with TestClient(TestServer(s.app)) as client:
            async def api(path,p=None,token='',status=200,method='POST'):
                async with client.request(method,'/api'+path,json=p,headers={'Authorization':'Bearer '+token}) as response:
                    data=await response.json();assert response.status==status,(path,response.status,data);return data
            a,b,c=[await api('/register',dict(username=n,nickname=n,password='syncstate-test-password')) for n in ('alice','bobby','carol')]
            at,bt,ct=a['token'],b['token'],c['token'];aid,bid,cid=a['user']['id'],b['user']['id'],c['user']['id']
            for peer,token in ((bid,bt),(cid,ct)):
                await api('/friends/request',{'peer':peer},at);await api('/friends/respond',{'peer':aid,'accept':True},token)
            request=dict(recipient=bid,text='uncertain delivery',client_id='owned-stable-client-id')
            sent=await api('/messages',request,at)
            assert (await api('/messages',request,at))['id']==sent['id']
            assert not (await api('/message-status/owned-stable-client-id',token=ct,method='GET'))['acknowledged']
            await api('/conversations/'+bid+'/clear',{},at);await api('/conversations/'+aid+'/clear',{},bt)
            assert not s.row('SELECT 1 FROM messages WHERE id=?',(sent['id'],))
            status=await api('/message-status/owned-stable-client-id',token=at,method='GET');assert status['acknowledged'] and status['message'] is None
            again=await api('/messages',request,at);assert again['removed'] and not s.rows('SELECT * FROM messages')
            await api('/messages',dict(request,recipient=cid),at,409)
            print('PASS round 39 backend: stable delivery receipt, owner isolation, uncertain retry after mutual deletion cannot resurrect content, cross-chat identifier rejected')
            state=await api('/history-version?after=0',token=at,method='GET');assert state['peers']==[bid] and state['revision']==1 and not state['reset']
            assert (await api('/history-version?after=0',token=ct,method='GET'))['revision']==0
            with s.db:
                for _ in range(110):s.syncstate.bump(aid,peer=bid)
            assert s.row('SELECT count(*) AS n FROM history_changes WHERE owner=?',(aid,))['n']==100
            assert (await api('/history-version?after=0',token=at,method='GET'))['reset']
            print('PASS round 40 backend: private per-account cache revision, affected-peer delta, bounded change retention and safe reset after a missed interval')
            s.write('UPDATE receipt_usage SET n=100000 WHERE owner=?',(aid,))
            await api('/messages',dict(request,client_id='receipt-quota-boundary'),at,507)
            s.write('UPDATE receipt_usage SET n=1 WHERE owner=?',(aid,));s.write('UPDATE delivery_receipts SET created=?',(s.now()-91*86400000,));s.syncstate.reap()
            assert not s.rows('SELECT * FROM delivery_receipts') and s.row('SELECT n FROM receipt_usage WHERE owner=?',(aid,))['n']==0
            print('PASS receipt space cap, ninety-day metadata expiry and durable usage accounting')
            # A record created by a pre-receipt backend still receives a durable tombstone at GC.
            s.write('INSERT INTO messages VALUES(?,?,?,?,?,?,?,?)',('legacy-owned-id',aid,bid,'text','legacy private text',None,s.now()-365*86400000,'legacy-owned-client'))
            s.write('INSERT OR REPLACE INTO conversation_clears VALUES(?,?,?)',(aid,bid,s.now()+100))
            s.write('INSERT OR REPLACE INTO conversation_clears VALUES(?,?,?)',(bid,aid,s.now()+100))
            s.social.maintenance_sweep()
            assert (await api('/message-status/legacy-owned-client',token=at,method='GET'))['acknowledged']
            assert not s.row('SELECT 1 FROM messages WHERE id=?',('legacy-owned-id',))
            assert (await api('/messages',dict(recipient=bid,text='legacy retry',client_id='legacy-owned-client'),at))['removed']
            s.syncstate.reap();assert s.row('SELECT 1 FROM delivery_receipts WHERE owner=? AND client=?',(aid,'legacy-owned-client'))
            print('PASS legacy cleanup creates a receipt before deletion and maintenance uses bounded metadata-only work')
        s.db.close()

if __name__=='__main__':asyncio.run(main())
