"""Isolated relationship/lifecycle tests; no real accounts or SMTP."""
import asyncio
import os
import tempfile
from aiohttp.test_utils import TestClient,TestServer


async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-social-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0')
        import server as s
        async with TestClient(TestServer(s.app)) as client:
            async def api(path,p=None,token='',status=200,method='POST'):
                async with client.request(method,'/api'+path,json=p,headers={'Authorization':'Bearer '+token}) as r:
                    body=await r.json();assert r.status==status,(path,r.status,body);return body
            a,b,c=[await api('/register',dict(username=n,nickname=n,password='test-social-password')) for n in ('alice','bobby','carol')]
            at,bt,ct=[u['token'] for u in (a,b,c)];aid,bid,cid=[u['user']['id'] for u in (a,b,c)]
            async def connect():
                await api('/friends/request',{'peer':bid},at)
                await api('/friends/respond',{'peer':aid,'accept':True},bt)
            async def send(text='hello'):
                return await api('/messages',{'recipient':bid,'text':text},at)
            await connect();m=await send('private old history')
            await api('/blocks',{'peer':bid,'blocked':True},at)
            blocked=await api('/blocks',token=at,method='GET');assert blocked['blocks'][0]['id']==bid
            assert not (await api('/blocks',token=bt,method='GET'))['blocks']
            await api('/blocks',{'peer':aid,'blocked':True},at,400)
            await api('/blocks',{'peer':cid,'blocked':'yes'},at,400)
            print('PASS round 01: owned persistent block list, explicit unblock input, no cross-account list access')
            await api('/messages',{'recipient':bid,'text':'blocked'},at,403)
            await api('/messages',{'recipient':aid,'text':'blocked'},bt,403)
            await api('/friends/request',{'peer':aid},bt,403)
            assert (await api('/users?q=alice',token=bt,method='GET'))['user'] is None
            assert not (await api('/contacts',token=bt,method='GET'))['contacts']
            old=await api('/messages/'+aid,token=bt,method='GET');assert old['messages'][0]['id']==m['id']
            summary=await api('/conversations',token=bt,method='GET');assert summary['conversations'][0]['user']['online'] is False
            print('PASS round 02: blocked send/request/profile visibility; history remains only for participants')
            await api('/blocks',{'peer':bid,'blocked':False},at)
            await api('/messages',{'recipient':bid,'text':'not restored implicitly'},at,403)
            await connect();await api('/friends/remove',{'peer':bid},at)
            await api('/messages',{'recipient':aid,'text':'not friends'},bt,403)
            assert (await api('/messages/'+aid,token=bt,method='GET'))['messages']
            assert (await api('/conversations',token=at,method='GET'))['conversations']
            await connect()
            print('PASS round 04: mutual friend removal, preserved participant history, explicit re-add')
            mark=(s.row('SELECT read_at FROM friends WHERE owner=? AND peer=?',(bid,aid)))['read_at']
            await api('/conversations/'+aid+'/clear',{},bt)
            assert not (await api('/messages/'+aid,token=bt,method='GET'))['messages']
            assert not (await api('/conversations',token=bt,method='GET'))['conversations']
            assert (await api('/messages/'+bid,token=at,method='GET'))['messages']
            assert s.row('SELECT read_at FROM friends WHERE owner=? AND peer=?',(bid,aid))['read_at']==mark
            fresh=await send('after clear')
            assert (await api('/messages/'+aid,token=bt,method='GET'))['messages'][0]['id']==fresh['id']
            print('PASS round 06: account-local clear, unread reset without fabricated read receipt, new messages visible')
            await api('/messages/'+fresh['id'],token=ct,status=404,method='DELETE')
            await api('/messages/'+fresh['id'],token=bt,method='DELETE')
            assert not (await api('/messages/'+aid,token=bt,method='GET'))['messages']
            assert any(x['id']==fresh['id'] for x in (await api('/messages/'+bid,token=at,method='GET'))['messages'])
            await api('/messages/'+fresh['id'],token=at,method='DELETE')
            assert not s.row('SELECT 1 FROM messages WHERE id=?',(fresh['id'],))
            print('PASS round 08: per-participant deletion, stranger denial, shared garbage collection')
            recent=await send('withdraw this '*100)
            await api('/messages/'+recent['id']+'/withdraw',{},bt,403)
            before=s.row('SELECT bytes FROM message_usage WHERE owner=?',(aid,))['bytes']
            gone=await api('/messages/'+recent['id']+'/withdraw',{},at)
            assert gone['withdrawn'] and gone['type']=='call'
            assert s.row('SELECT bytes FROM message_usage WHERE owner=?',(aid,))['bytes']<=before
            assert s.row('SELECT bytes FROM message_usage WHERE owner=?',(aid,))['bytes']==s.row('SELECT sum(length(CAST(text AS BLOB))) AS n FROM messages WHERE sender=?',(aid,))['n']
            await api('/messages/'+recent['id']+'/withdraw',{},at)
            s.write('UPDATE messages SET created=? WHERE id=?',(s.now()-121000,m['id']))
            await api('/messages/'+m['id']+'/withdraw',{},at,400)
            print('PASS round 09: sender/time authorization, idempotent tombstone and usage accounting')
        s.db.close()


if __name__=='__main__':asyncio.run(main())
