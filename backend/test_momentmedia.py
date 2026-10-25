"""Isolated real-file HTTP regressions for public/friend moments and bounded media."""
import asyncio
import os
import sqlite3
import tempfile
from io import BytesIO
from PIL import Image
from pathlib import Path
from aiohttp.test_utils import TestClient,TestServer

async def main():
    with tempfile.TemporaryDirectory(prefix='xingyu-moment-media-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0')
        with sqlite3.connect(Path(directory)/'qingliao.sqlite3') as old:
            old.executescript('CREATE TABLE users(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL,nickname TEXT NOT NULL,salt TEXT NOT NULL,password TEXT NOT NULL,created INTEGER NOT NULL);CREATE TABLE posts(id TEXT PRIMARY KEY,owner TEXT NOT NULL,text TEXT NOT NULL,created INTEGER NOT NULL,client_id TEXT NOT NULL,UNIQUE(owner,client_id));')
            old.execute('INSERT INTO users VALUES(?,?,?,?,?,?)',('10000001','legacy','Legacy','00'*16,'00',1))
            old.execute('INSERT INTO posts VALUES(?,?,?,?,?)',('a'*32,'10000001','old private',1,'legacy'))
        import server as s
        assert s.row('SELECT audience FROM posts WHERE id=?',('a'*32,))['audience']=='friends'
        files=Path(__file__).with_name('testdata')
        video=(files/'moment-video.mp4').read_bytes();voice=(files/'moment-voice.m4a').read_bytes();wav=(files/'moment-voice.wav').read_bytes()
        async with TestClient(TestServer(s.app)) as client:
            async def api(method,path,body=None,token='',status=200):
                r=await client.request(method,'/api'+path,json=body,headers={'Authorization':'Bearer '+token})
                value=await r.json();assert r.status==status,(path,r.status,value);return value
            async def upload(body,kind,token,status=200):
                r=await client.post('/api/post-media?kind='+kind,data=body,headers={'Authorization':'Bearer '+token,'Content-Type':'application/octet-stream'})
                p=await r.json();assert r.status==status,(r.status,p);return p
            async def download(fid,token,status=200,extra=None):
                r=await client.get('/api/post-media/'+fid,headers=dict({'Authorization':'Bearer '+token},**(extra or {})))
                data=await r.read();assert r.status==status,(r.status,data[:100]);return r,data
            poster=BytesIO();Image.new('RGB',(1200,800),'#9368cc').save(poster,'JPEG',exif=Image.Exif())
            async def cover(fid,token,body=None,status=200,method=None):
                r=await client.request(method or ('POST' if body is not None else 'GET'),'/api/post-media/'+fid+'/cover',data=body,headers={'Authorization':'Bearer '+token})
                data=await r.read();assert r.status==status,(r.status,data[:100]);return r,data
            a,b,c=[await api('POST','/register',{'username':'momentmedia_'+n,'nickname':n,'password':'isolated-test-password'}) for n in ('a','b','c')]
            at,bt,ct=[u['token'] for u in (a,b,c)];aid,bid,cid=[u['user']['id'] for u in (a,b,c)]
            await api('POST','/friends/request',{'peer':bid},at);await api('POST','/friends/respond',{'peer':aid,'accept':True},bt)
            await upload(video,'video','',401)
            v=await upload(video,'video',at);m=await upload(voice,'voice',at);w=await upload(wav,'voice',at)
            assert v['mime']=='video/mp4' and 1900<=v['duration']<=2100 and v['width']==240
            assert m['mime']=='audio/mp4' and w['mime']=='audio/wav'
            await cover(v['id'],at,status=404);await cover(v['id'],'',status=401)
            await cover(v['id'],bt,poster.getvalue(),404)
            await cover(m['id'],at,poster.getvalue(),404)
            await cover(v['id'],at,b'not an image',400)
            _,body=await cover(v['id'],at,poster.getvalue());poster_info=__import__('json').loads(body)
            _,body=await cover(v['id'],at,poster.getvalue());assert __import__('json').loads(body)==poster_info
            assert s.row('SELECT count(*) AS n FROM post_photos WHERE parent_media=?',(v['id'],))['n']==1
            cover_response,cover_bytes=await cover(v['id'],at)
            with Image.open(BytesIO(cover_bytes)) as thumbnail:
                assert thumbnail.format=='JPEG' and thumbnail.size==(960,640) and not thumbnail.getexif()
            assert cover_response.headers['Content-Type']=='image/jpeg' and 'no-store' in cover_response.headers['Cache-Control'] and cover_response.headers['X-Content-Type-Options']=='nosniff'
            await cover(v['id'],bt,status=404);await cover(v['id'],ct,status=404)
            await api('POST','/posts',{'photos':[poster_info['id']],'client_id':'cover-as-photo'},at,400)
            await api('POST','/posts',{'media':poster_info['id'],'client_id':'cover-as-media'},at,400)
            await api('GET','/post-photos/'+poster_info['id'],token=at,status=404)
            await download(poster_info['id'],at,404)
            assert (await download(v['id'],at))[1]==video
            await download(v['id'],bt,404);await download(v['id'],ct,404)
            await api('POST','/posts',{'media':v['id'],'client_id':'steal'},bt,400)
            await upload(voice,'video',at,400);await upload(video,'voice',at,400)
            for body in (b'not-media-file'*10,video[:32],wav[:50]):await upload(body,'voice',at,400)
            broken=bytearray(voice);offset=broken.index(b'mvhd')+4;scale=int.from_bytes(broken[offset+12:offset+16],'big');broken[offset+16:offset+20]=(scale*3600).to_bytes(4,'big')
            await upload(broken,'voice',at,400)
            external=bytearray(voice);offset=external.index(b'url ')+4;external[offset:offset+4]=b'\0'*4;await upload(external,'voice',at,400)
            ws=await client.ws_connect('/ws');await ws.send_json({'token':ct});assert (await ws.receive_json())['event']=='ready'
            payload={'text':'public video','media':v['id'],'audience':'public','client_id':'public-video'}
            public=await api('POST','/posts',payload,at);pid=public['id']
            portrait=BytesIO();Image.new('RGB',(96,96),'purple').save(portrait,'PNG')
            response=await client.put('/api/me/avatar',data=portrait.getvalue(),headers={'Authorization':'Bearer '+at})
            assert response.status==200
            avatar=(await response.json())['avatar_id']
            assert (await client.get('/api/avatars/'+avatar,headers={'Authorization':'Bearer '+ct})).status==404
            assert (await client.get('/api/avatars/'+avatar+'?post='+pid,headers={'Authorization':'Bearer '+ct})).status==200
            while True:
                event=await ws.receive_json(timeout=3)
                if event['event']=='post.changed':assert event['id']==pid;break
            assert public['audience']=='public' and public['media']['kind']=='video' and not public['photos']
            assert public['media']['cover']==poster_info
            assert s.row('SELECT post_id FROM post_photos WHERE id=?',(poster_info['id'],))['post_id']==pid
            assert (await cover(v['id'],ct))[1]==cover_bytes
            await cover(v['id'],ct,poster.getvalue(),404)
            assert (await api('POST','/posts',payload,at))['id']==pid
            await api('POST','/posts',dict(payload,client_id='reuse'),at,400)
            assert (await api('GET','/posts/'+pid,token=ct))['id']==pid
            assert (await api('GET','/posts?scope=public',token=ct))['posts'][0]['id']==pid
            assert not (await api('GET','/posts?scope=friends',token=ct))['posts']
            assert not (await api('GET','/posts?scope=mine',token=ct))['posts']
            response,data=await download(v['id'],ct,206,{'Range':'bytes=0-31'})
            assert data==video[:32] and response.headers['Content-Type']=='video/mp4' and 'no-store' in response.headers['Cache-Control']
            await api('GET','/post-photos/'+v['id'],token=ct,status=404)
            private=await api('POST','/posts',{'media':m['id'],'audience':'friends','client_id':'private-voice'},at)
            await api('GET','/posts/'+private['id'],token=bt)
            await download(m['id'],bt);await download(m['id'],ct,404);await api('GET','/posts/'+private['id'],token=ct,status=404)
            legacy=await api('POST','/posts',{'text':'old client private','client_id':'old-client'},at)
            assert legacy['audience']=='friends';await api('GET','/posts/'+legacy['id'],token=ct,status=404)
            for bad in ({'media':w['id'],'audience':'everyone'}, {'media':w['id'],'photos':[w['id']]}, {'photos':[w['id']]}, {'media':['bad']}, {'media':'../../private'}):
                await api('POST','/posts',dict(bad,client_id='invalid'),at,400)
            await api('POST','/blocks',{'peer':aid,'blocked':True},ct)
            assert not (await api('GET','/posts?scope=public',token=ct))['posts']
            await download(v['id'],ct,404);await api('POST','/posts/'+pid+'/like',{'liked':True},ct,404)
            await cover(v['id'],ct,status=404)
            assert (await client.get('/api/avatars/'+avatar+'?post='+pid,headers={'Authorization':'Bearer '+ct})).status==404
            await api('POST','/blocks',{'peer':aid,'blocked':False},ct)
            await download(v['id'],ct);await api('GET','/posts/'+private['id'],token=ct,status=404)
            await api('POST','/posts/'+pid+'/comments',{'text':'public reply','client_id':'public-comment'},ct)
            await api('DELETE','/posts/'+pid,token=ct,status=403)
            storage=await api('GET','/storage',token=at)
            assert storage['moments']==len(video)+len(voice)+len(wav)+len(cover_bytes)
            # Covers count towards storage and have the same hard ingress bound as photos.
            assert s.guard.user_media(aid)>=storage['moments']
            # Oversized declared length is rejected before reading any upload bytes.
            reader,writer=await asyncio.open_connection(client.server.host,client.server.port)
            writer.write(('POST /api/post-media?kind=voice HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer '+at+'\r\nContent-Length: 5242881\r\nConnection: close\r\n\r\n').encode());await writer.drain()
            assert b'413' in await asyncio.wait_for(reader.readline(),3);writer.close();await writer.wait_closed()
            reader,writer=await asyncio.open_connection(client.server.host,client.server.port)
            writer.write(('POST /api/post-media/'+v['id']+'/cover HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer '+at+'\r\nContent-Length: 2097153\r\nConnection: close\r\n\r\n').encode());await writer.drain()
            assert b'413' in await asyncio.wait_for(reader.readline(),3);writer.close();await writer.wait_closed()
            for post in (public,private):await api('DELETE','/posts/'+post['id'],token=at)
            for media in (v,m):assert not (Path(directory)/'moments'/media['id']).exists();await download(media['id'],at,404)
            assert not s.row('SELECT 1 FROM post_photos WHERE id=?',(poster_info['id'],)) and not (Path(directory)/'moments'/poster_info['id']).exists()
            await cover(v['id'],at,status=404)
            # A friends-only video cover cannot become a separate public photo or bypass blocks.
            private_video=await upload(video,'video',at)
            await cover(private_video['id'],at,poster.getvalue())
            private_post=await api('POST','/posts',{'media':private_video['id'],'client_id':'private-video-cover','audience':'friends'},at)
            await cover(private_video['id'],bt);await cover(private_video['id'],ct,status=404)
            await api('DELETE','/posts/'+private_post['id'],token=at)
            # An in-flight decoder must not resurrect a video deleted while the worker yields.
            orphan=await upload(video,'video',at);original=s.prepare_post_photo
            def remove_during_decode(data,*args):
                # DB work stays on the event loop, while the decoder is running in a worker.
                import time
                time.sleep(.15)
                return original(data,*args)
            s.momentmedia.c['prepare_post_photo']=remove_during_decode
            pending=asyncio.create_task(cover(orphan['id'],at,poster.getvalue(),404))
            await asyncio.sleep(.05);s.write('DELETE FROM post_photos WHERE id=?',(orphan['id'],));(Path(directory)/'moments'/orphan['id']).unlink()
            await pending;s.momentmedia.c['prepare_post_photo']=original
            assert not s.row('SELECT 1 FROM post_photos WHERE parent_media=?',(orphan['id'],))
            assert not list((Path(directory)/'moments').glob('*.upload'))
            assert s.db.execute('PRAGMA integrity_check').fetchone()[0]=='ok' and not s.db.execute('PRAGMA foreign_key_check').fetchall()
            await ws.close()
            print('PASS actual MP4/M4A/WAV; private upload ownership; type/container/duration/external-reference bounds; declared oversized rejection; Range and storage accounting')
            print('PASS public/friend/mine scopes; old rows and omitted audience stay private; global live events; contextual public author avatar; public comments; mutual blocks; idempotency; deletion/FK/file cleanup')
            print('PASS real static JPEG covers; 960px normalization/metadata stripping; owner-only immutable idempotent binding; prepublish/private/public/block permissions; no reuse as image/media; size/quota/storage; deletion and decode race cleanup')
        s.db.close()

if __name__=='__main__':asyncio.run(main())
