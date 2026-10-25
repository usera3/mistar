"""Temporary plaza shutdown: existing public data stays intact, every media ACL closes."""
import asyncio
import os
import tempfile
from io import BytesIO
from pathlib import Path
from aiohttp.test_utils import TestClient, TestServer
from PIL import Image


async def main():
    with tempfile.TemporaryDirectory(prefix='xingyu-plaza-closed-') as temp:
        os.environ.update(QINGLIAO_DATA=temp,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0',QINGLIAO_PUBLIC_PLAZA='1')
        import server as s
        async with TestClient(TestServer(s.app)) as client:
            async def api(method,path,token='',body=None,status=200):
                r=await client.request(method,'/api'+path,headers={'Authorization':'Bearer '+token},json=body)
                p=await r.json();assert r.status==status,(path,r.status,p);return p
            a,b,c=[await api('POST','/register',body={'username':'plazaclose_'+name,'nickname':name,'password':'synthetic-password-123'}) for name in ('a','b','c')]
            at,bt,ct=[p['token'] for p in (a,b,c)];aid,bid,cid=[p['user']['id'] for p in (a,b,c)]
            await api('POST','/friends/request',at,{'peer':bid});await api('POST','/friends/respond',bt,{'peer':aid,'accept':True})
            picture=BytesIO();Image.new('RGB',(64,64),'purple').save(picture,'JPEG')
            r=await client.post('/api/post-photos',data=picture.getvalue(),headers={'Authorization':'Bearer '+at});assert r.status==200
            photo=(await r.json())['id']
            public=await api('POST','/posts',at,{'text':'synthetic-public','audience':'public','client_id':'public','photos':[photo]})
            private=await api('POST','/posts',at,{'text':'synthetic-friends','audience':'friends','client_id':'private'})
            for name,kind in [('moment-video.mp4','video'),('moment-voice.m4a','voice')]:
                r=await client.post('/api/post-media?kind='+kind,data=(Path(__file__).parent/'testdata'/name).read_bytes(),headers={'Authorization':'Bearer '+at})
                assert r.status==200;media=(await r.json())['id']
                if kind=='video':
                    r=await client.post('/api/post-media/'+media+'/cover',data=picture.getvalue(),headers={'Authorization':'Bearer '+at});assert r.status==200
                post=await api('POST','/posts',at,{'text':kind,'audience':'public','client_id':kind,'media':media})
            all_posts=s.rows('SELECT id,audience FROM posts ORDER BY id')
            os.environ['QINGLIAO_PUBLIC_PLAZA']='0'
            health=await api('GET','/health');assert health['moments_public'] is False and health['moments_media']
            for token in (at,bt,ct):await api('GET','/posts?scope=public',token,status=403)
            for token in (bt,ct):
                await api('GET','/posts/'+public['id'],token,status=404)
                await api('GET','/posts/'+public['id']+'/comments',token,status=404)
                await api('POST','/posts/'+public['id']+'/comments',token,{'text':'blocked','client_id':'closed'},404)
                assert (await client.get('/api/post-photos/'+photo,headers={'Authorization':'Bearer '+token})).status==404
                for f in s.rows("SELECT id,kind,parent_media FROM post_photos WHERE kind IN ('video','voice')"):
                    assert (await client.get('/api/post-media/'+f['id'],headers={'Authorization':'Bearer '+token,'Range':'bytes=0-31'})).status==404
                    if f['kind']=='video':assert (await client.get('/api/post-media/'+f['id']+'/cover',headers={'Authorization':'Bearer '+token})).status==404
                assert not any(p['audience']=='public' for p in (await api('GET','/posts?scope=friends',token))['posts'])
            await api('POST','/posts',at,{'text':'closed','audience':'public','client_id':'closed'},403)
            await api('POST','/posts',at,{'text':'still private','audience':'friends','client_id':'friends-after-close'})
            await api('GET','/posts/'+private['id'],bt)
            await api('GET','/posts/'+public['id'],at)
            assert len((await api('GET','/posts?scope=mine',at))['posts'])==len(all_posts)+1
            assert s.rows('SELECT id,audience FROM posts WHERE client_id<>? ORDER BY id',('friends-after-close',))==all_posts
            print('PASS plaza browse/publish/detail/comments/photo/video/voice/cover shutdown; friend posts and original audiences preserved')
        s.db.close()

if __name__=='__main__':asyncio.run(main())
