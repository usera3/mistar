import asyncio,json,os,tempfile,threading
from pathlib import Path
from io import BytesIO
from PIL import Image
from aiohttp.test_utils import TestClient,TestServer
from deployment.create_admin import create_admin

async def main():
    with tempfile.TemporaryDirectory(prefix='xingyu-admin-media-') as tmp:
        os.environ.update(QINGLIAO_DATA=tmp,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0',QINGLIAO_PUBLIC_PLAZA='0')
        import server as s
        admin=create_admin(Path(tmp)/'qingliao.sqlite3','moderator',Path(tmp)/'admin.json')
        s.write('UPDATE administrators SET must_change=0')
        secret=json.loads((Path(tmp)/'admin.json').read_text())['initial_password']
        async with TestClient(TestServer(s.app)) as client:
            async def api(method,path,body=None,token='',grant='',status=200):
                r=await client.request(method,'/api'+path,json=body,headers={'Authorization':'Bearer '+token,'X-Admin-Grant':grant})
                data=await r.json();assert r.status==status,(path,r.status,data);return data
            at=(await api('POST','/login',{'username':'moderator','password':secret}))['token']
            grant=(await api('POST','/admin/unlock',{'password':secret},at))['grant']
            a,b=[await api('POST','/register',{'username':'adminmedia_'+x,'nickname':x,'password':'synthetic-password'}) for x in ('a','b')]
            aid,bid=a['user']['id'],b['user']['id'];ta,tb=a['token'],b['token']
            await api('POST','/friends/request',{'peer':bid},ta);await api('POST','/friends/respond',{'peer':aid,'accept':True},tb)
            picture=BytesIO();Image.new('RGB',(64,64),'purple').save(picture,'JPEG')
            for name,kind in [('moment-video.mp4','video'),('moment-voice.m4a','voice')]:
                payload=(Path(__file__).parent/'testdata'/name).read_bytes()
                r=await client.post('/api/post-media?kind='+kind,data=payload,headers={'Authorization':'Bearer '+ta});assert r.status==200
                fid=(await r.json())['id'];cover=''
                if kind=='video':
                    r=await client.post('/api/post-media/'+fid+'/cover',data=picture.getvalue(),headers={'Authorization':'Bearer '+ta});assert r.status==200
                    cover=(await r.json())['id']
                post=await api('POST','/posts',{'media':fid,'audience':'friends','client_id':kind},ta)
                await api('POST','/admin/content/post/'+post['id'],{'hidden':True,'reason':'合成媒体测试'},at,grant)
                assert (await client.get('/api/post-media/'+fid,headers={'Authorization':'Bearer '+tb,'Range':'bytes=0-31'})).status==404
                response=await client.get('/api/admin/media/'+fid,headers={'Authorization':'Bearer '+at,'X-Admin-Grant':grant,'Range':'bytes=0-31'})
                assert response.status==206 and await response.read()==payload[:32]
                if cover:
                    assert (await client.get('/api/post-media/'+fid+'/cover',headers={'Authorization':'Bearer '+tb})).status==404
                    assert (await client.get('/api/admin/media/'+cover,headers={'Authorization':'Bearer '+at,'X-Admin-Grant':grant})).status==200
            # A private-chat upload is never available through the administrator media route.
            r=await client.post('/api/uploads?recipient='+bid,data=b'private-upload',headers={'Authorization':'Bearer '+ta,'Content-Type':'application/octet-stream'});assert r.status==200
            private=(await r.json())['id']
            assert (await client.get('/api/admin/media/'+private,headers={'Authorization':'Bearer '+at,'X-Admin-Grant':grant})).status==404
            # Revoke publishing while the image decoder has yielded; no late file may commit.
            entered=threading.Event();release=threading.Event();original=s.prepare_post_photo
            def blocked(*args):entered.set();assert release.wait(5);return original(*args)
            s.prepare_post_photo=blocked
            pending=asyncio.create_task(client.post('/api/post-photos',data=picture.getvalue(),headers={'Authorization':'Bearer '+ta}))
            assert await asyncio.to_thread(entered.wait,3)
            await api('POST','/admin/users/'+aid+'/action',{'action':'restrict_posts','days':1,'reason':'合成上传中撤权'},at,grant)
            count=s.row('SELECT count(*) AS n FROM post_photos')['n'];release.set();response=await pending
            assert response.status==403 and s.row('SELECT count(*) AS n FROM post_photos')['n']==count
            s.prepare_post_photo=original
            print('PASS hidden video/voice/cover ACL and staff previews; private chat upload denied; publishing revoked during decoder cannot commit')
        s.db.close()
if __name__=='__main__':asyncio.run(main())
