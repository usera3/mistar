"""Adversarial checks for new relationship, media revocation and signalling paths."""
import asyncio,os,tempfile
from io import BytesIO
from PIL import Image
from aiohttp.test_utils import TestClient,TestServer

async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-boundaries-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0')
        import server as s
        async with TestClient(TestServer(s.app)) as client:
            async def api(path,p=None,token='',status=200,method='POST'):
                async with client.request(method,'/api'+path,json=p,headers={'Authorization':'Bearer '+token}) as response:
                    body=await response.json();assert response.status==status,(path,response.status,body);return body
            a,b,c=[await api('/register',dict(username=n,nickname=n,password='boundary-test-password')) for n in ('alice','bobby','carol')]
            at,bt,ct=a['token'],b['token'],c['token'];aid,bid=a['user']['id'],b['user']['id']
            async def friends():
                await api('/friends/request',{'peer':bid},at);await api('/friends/respond',{'peer':aid,'accept':True},bt)
            await friends()
            photo=BytesIO();Image.new('RGB',(32,32),(30,120,160)).save(photo,'PNG')
            async with client.put('/api/me/avatar',data=photo.getvalue(),headers={'Authorization':'Bearer '+at,'Content-Type':'image/png'}) as response:assert response.status==200;avatar=(await response.json())['avatar_id']
            post=await api('/posts',dict(text='private friend feed',photos=[],client_id='owned-post-boundary'),at)
            wa=await client.ws_connect('/ws');await wa.send_json({'token':at});await wa.receive_json()
            wb=await client.ws_connect('/ws');await wb.send_json({'token':bt});await wb.receive_json()
            await s.signaling(aid,dict(event='call.invite',peer=bid));call=s.call_for(aid);await s.signaling(bid,dict(event='call.accept',call_id=call['id']))
            s.guard.MAX_SIGNAL_BYTES_HOUR=4096
            packet=dict(event='call.signal',call_id=call['id'],signal={'type':'candidate','candidate':'x'*1500})
            await s.signaling(aid,packet);await s.signaling(aid,packet)
            try:await s.signaling(aid,packet)
            except s.ApiError as error:assert error.status==429
            else:raise AssertionError('Signal byte budget exceeded')
            await api('/blocks',{'peer':bid,'blocked':True},at);assert s.call_for(aid) is None
            async with client.get('/api/avatars/'+avatar,headers={'Authorization':'Bearer '+bt}) as response:assert response.status==404
            assert not (await api('/posts',token=bt,method='GET'))['posts']
            person=await api('/relationships/'+aid,token=bt,method='GET');assert not person['online'] and not person['last_seen'] and not person['avatar_id']
            print('PASS round 48: signal-byte rate bound, active-call termination on block, blocked avatar/presence/feed isolation')
            await wa.close();await wb.close();await api('/blocks',{'peer':bid,'blocked':False},at);await friends()
            async with client.post('/api/uploads?recipient='+bid,data=b'owned-content',headers={'Authorization':'Bearer '+at,'Content-Type':'application/octet-stream'}) as response:assert response.status==200;fid=(await response.json())['id']
            messages=[await api('/messages',dict(recipient=bid,type='file',file_id=fid,client_id='reuse-'+str(i)),at) for i in range(2)]
            await api('/messages/'+messages[0]['id']+'/withdraw',{},at);assert (s.DATA/'uploads'/fid).exists()
            await api('/messages/'+messages[1]['id']+'/withdraw',{},at);assert not (s.DATA/'uploads'/fid).exists()
            async with client.get('/api/files/'+fid,headers={'Authorization':'Bearer '+bt}) as response:assert response.status==404
            await api('/messages/'+messages[0]['id'],token=ct,method='DELETE',status=404)
            print('PASS round 48: attachment remains while another message references it, disappears after final withdrawal, stranger message deletion denied')
            s.guard.MIN_FREE=10**20
            await api('/blocks',dict(peer=bid,blocked=True),at)
            await api('/storage/cleanup',{},at)
            await api('/conversations/'+bid+'/clear',{},at)
            await api('/messages',dict(recipient=bid,text='low-space content'),at,507)
            print('PASS round 48: low-disk reserve blocks new content while block and cleanup controls remain usable')
        s.db.close()

if __name__=='__main__':asyncio.run(main())
