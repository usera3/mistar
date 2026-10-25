"""Owned storage, privacy, cleanup and immutable daily allowance regression."""
import asyncio,os,tempfile
from aiohttp.test_utils import TestClient,TestServer


async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-storage-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0')
        import server as s
        async with TestClient(TestServer(s.app)) as client:
            async def api(path,token='',p=None,method='GET',status=200):
                async with client.request(method,'/api'+path,json=p,headers={'Authorization':'Bearer '+token}) as r:
                    data=await r.json();assert r.status==status,(path,r.status,data);return data
            a,b=[await api('/register',p=dict(username=n,nickname=n,password='storage-test-password'),method='POST') for n in ('alice','bobby')]
            at,bt=a['token'],b['token'];aid,bid=a['user']['id'],b['user']['id']
            await api('/friends/request',at,{'peer':bid},'POST');await api('/friends/respond',bt,{'peer':aid,'accept':True},'POST')
            async def upload():
                async with client.post('/api/uploads?recipient='+bid,data=b'private file content',headers={'Authorization':'Bearer '+at,'Content-Type':'application/octet-stream'}) as r:
                    assert r.status==200;return await r.json()
            sent,pending=await upload(),await upload()
            m=await api('/messages',at,{'recipient':bid,'type':'file','file_id':sent['id']},'POST')
            usage=await api('/storage',at);assert usage['used']==40 and usage['sent']==20 and usage['pending']==20 and usage['quota']==200*1024*1024
            assert not (await api('/storage/files',bt))['files'];assert len((await api('/storage/files',at))['files'])==2
            print('PASS round 11: exact per-owner quota/categories and bounded private file listing')
            await api('/storage/cleanup',at,{},'POST');assert not (s.DATA/'uploads'/pending['id']).exists()
            assert (s.DATA/'uploads'/sent['id']).exists();assert (await api('/storage',at))['upload_today']==usage['upload_today']
            print('PASS round 12: unsent cleanup retains sent attachments and cannot reset daily abuse budget')
            await api('/storage/files/'+sent['id'],bt,method='DELETE',status=404)
            await api('/storage/files/'+sent['id'],at,method='DELETE')
            assert (await api('/storage',at))['used']==0
            messages=(await api('/messages/'+aid,bt))['messages'];assert messages[0]['id']==m['id'] and messages[0]['attachment_available'] is False
            async with client.get('/api/files/'+sent['id'],headers={'Authorization':'Bearer '+bt}) as r:assert r.status==404
            print('PASS round 13: owner-only cloud removal, download revoked, text envelope retained with availability metadata')
        s.db.close()


if __name__=='__main__':asyncio.run(main())
