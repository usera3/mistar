"""Bounded adversarial integration tests; fresh temporary DB, no production traffic/mail."""
import asyncio
import hashlib
import json
import os
import tempfile
import time
from pathlib import Path
from aiohttp import ClientSession
from aiohttp.test_utils import TestClient,TestServer


async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-abuse-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0',QINGLIAO_TRUST_PROXY='0',OTP_SECRET='isolated-test-secret',SMTP_PASSWORD='fake-only')
        import server as s
        from abuse import Defense,WorkGate,MIB
        async with TestClient(TestServer(s.app)) as client:
            async def api(path,body=None,token='',status=200,method='POST',headers=None):
                h={'Authorization':'Bearer '+token};h.update(headers or {})
                async with client.request(method,'/api'+path,json=body,headers=h) as r:
                    p=await r.json();assert r.status==status,(path,r.status,p);return p
            async def upload(token,peer,body,status=200,length=None):
                headers={'Authorization':'Bearer '+token,'Content-Type':'text/plain'}
                if length is not None:headers['Content-Length']=str(length)
                async with client.post('/api/uploads?recipient='+peer,data=body,headers=headers) as r:
                    p=await r.json();assert r.status==status,(r.status,p);return p
            accounts=[]
            for name in ('alice','bobby','carol'):
                accounts.append(await api('/register',dict(username=name,nickname=name,password='test-abuse-password')))
            a,b,c=accounts;aid,bid,cid=[u['user']['id'] for u in accounts];at,bt,ct=[u['token'] for u in accounts]
            await api('/register',dict(username='fourth',nickname='Fourth',password='test-password'),status=429)
            for x,y in ((aid,bid),(aid,cid),(bid,cid)):
                s.db.executemany("INSERT INTO friends(owner,peer,state) VALUES(?,?,'accepted')",[(x,y),(y,x)])
            s.db.execute('UPDATE users SET created=0');s.db.commit()
            print('PASS per-source successful-registration cap; normal three-account registration')

            await api('/health',method='GET',headers={'X-Real-IP':'198.51.100.9','X-Forwarded-For':'198.51.100.9'})
            forged=hashlib.sha256(b'http-ip:198.51.100.9').hexdigest()
            assert not s.row('SELECT 1 FROM abuse_buckets WHERE id=?',(forged,))
            for _ in range(3):s.guard.limited('persist-test',3,1000)
            another=Defense(s.__dict__)
            try:another.limited('persist-test',3,1000);raise AssertionError('Restart bypassed limiter')
            except s.ApiError as e:assert e.status==429 and e.retry_after>0
            print('PASS untrusted forwarded IP ignored; limiter survives recreation')

            await api('/login',{'padding':'x'*33000},status=413)
            await api('/login',[],status=400)
            await api('/login',{},status=415,headers={'Content-Encoding':'gzip'})
            await upload(at,bid,b'',413,length=30*MIB)
            assert not list(Path(directory,'uploads').iterdir())
            async def exact_limit():
                for _ in range(s.MAX_FILE//65536):yield b'z'*65536
            exact=await upload(at,bid,exact_limit(),length=s.MAX_FILE)
            assert exact['size']==25*MIB
            Path(directory,'uploads',exact['id']).unlink();s.write('DELETE FROM uploads WHERE id=?',(exact['id'],))
            async def streamed_over_limit():
                for _ in range(402):yield b'z'*65536
            await upload(at,bid,streamed_over_limit(),413)
            assert not list(Path(directory,'uploads').iterdir())
            original=s.MAX_FILE;s.MAX_FILE=4096
            async def too_big():
                for _ in range(12):yield b'x'*1024
            await upload(at,bid,too_big(),413)
            s.MAX_FILE=original
            assert not list(Path(directory,'uploads').iterdir()) and not s.guard.reserved
            s.db.execute('DELETE FROM upload_usage');s.db.commit()
            print('PASS JSON/header and unknown-length streamed size limits; no partial files')

            release=asyncio.Event()
            async def held():yield b'x'*256;await release.wait();yield b'x'*768
            tasks=[asyncio.create_task(upload(at,bid,held(),length=1024)),asyncio.create_task(upload(at,cid,held(),length=1024)),asyncio.create_task(upload(bt,aid,held(),length=1024))]
            for _ in range(100):
                if len(s.guard.reserved)==3:break
                await asyncio.sleep(.01)
            assert len(s.guard.reserved)==3
            await upload(ct,aid,b'z',429)
            release.set();await asyncio.gather(*tasks)
            assert not s.guard.reserved
            # Reserve remaining capacity before writing, so a second upload cannot oversubscribe it.
            capacity=s.MAX_STORAGE;s.MAX_STORAGE=s.storage_used()+3000
            release=asyncio.Event()
            async def held2():yield b'x'*256;await release.wait();yield b'x'*(2048-256)
            first=asyncio.create_task(upload(at,bid,held2(),length=2048))
            for _ in range(100):
                if s.guard.reserved:break
                await asyncio.sleep(.01)
            await upload(bt,aid,b'y'*2048,507)
            release.set();await first
            assert s.storage_used()<=s.MAX_STORAGE;s.MAX_STORAGE=capacity
            maximum=s.guard.MAX_USER_MEDIA;s.guard.MAX_USER_MEDIA=s.guard.user_media(aid)+10
            await upload(at,bid,b'x'*11,507);s.guard.MAX_USER_MEDIA=maximum
            day=int(time.time()//86400);s.write('INSERT OR REPLACE INTO upload_usage VALUES(?,?,?)',(aid,day,128*MIB))
            await upload(at,bid,b'x',429);s.write('DELETE FROM upload_usage')
            count=s.guard.MAX_MEDIA_FILES;s.guard.MAX_MEDIA_FILES=1
            await upload(at,bid,b'x',507);s.guard.MAX_MEDIA_FILES=count
            s.write('INSERT OR REPLACE INTO content_usage VALUES(?,?,?)',(aid,day,8*MIB))
            await api('/messages',dict(recipient=bid,text='over-quota',client_id='metadata-day'),at,429)
            await api('/posts',dict(text='over-quota',photos=[],client_id='metadata-post'),at,429)
            post=await api('/posts',dict(text='allowed',photos=[],client_id='allowed-post'),bt)
            await api('/posts/'+post['id']+'/comments',dict(text='over-quota',client_id='metadata-comment'),at,429)
            await api('/messages/'+bid,token=at,method='GET')
            s.write('DELETE FROM content_usage')
            print('PASS global/account concurrent upload bounds, atomic reservations, per-account and daily quotas')

            idle=s.guard.IDLE_TIMEOUT;total=s.guard.UPLOAD_TIMEOUT
            s.guard.IDLE_TIMEOUT=.1;s.guard.UPLOAD_TIMEOUT=.3
            async def slow():yield b'x';await asyncio.sleep(1);yield b'y'
            await upload(at,bid,slow(),408)
            s.guard.IDLE_TIMEOUT=.1;s.guard.UPLOAD_TIMEOUT=.15
            async def trickle():
                for _ in range(30):yield b'z';await asyncio.sleep(.025)
            await upload(at,bid,trickle(),408)
            s.guard.IDLE_TIMEOUT=idle;s.guard.UPLOAD_TIMEOUT=total
            assert not s.guard.reserved and not list(Path(directory,'uploads').glob('.part-*'))
            print('PASS slow-upload idle and total deadlines; canceled partials and slots reclaimed')

            s.guard.clear()
            gate=WorkGate(s.reject,1,1);hold=asyncio.Event()
            async def worker():
                async with gate:await hold.wait()
            one=asyncio.create_task(worker());await asyncio.sleep(.01)
            two=asyncio.create_task(worker());await asyncio.sleep(.01)
            try:
                async with gate:raise AssertionError('Unbounded work queue')
            except s.ApiError as e:assert e.status==429
            two.cancel()
            try:await two
            except asyncio.CancelledError:pass
            hold.set();await one;assert gate.pending==0
            for _ in range(40):
                async with client.post('/api/login',json=dict(username='unknown',password='wrong-password')) as r:
                    if r.status==429:
                        assert int(r.headers['Retry-After'])>=1
                        break
                    assert r.status==401
            else:raise AssertionError('Login attempts were not throttled')
            await api('/login',dict(username='unknown',password='wrong-password'),status=429)
            s.guard.clear()
            futures={asyncio.get_running_loop().create_future() for _ in range(8)};s.security.tasks.update(futures)
            await api('/auth/code',dict(purpose='register',email='test@example.test'),status=429)
            for future in futures:future.cancel()
            s.security.tasks.clear()
            assert s.guard.email_key('a.li.ce+tag@googlemail.com')==s.guard.email_key('alice@gmail.com')
            print('PASS bounded costly-work/mail queues, cancellation recovery, login throttling and Gmail alias identity')

            sockets=[]
            for _ in range(2):
                ws=await client.ws_connect('/ws');await ws.send_json({'token':at});assert (await ws.receive_json())['event']=='ready';sockets.append(ws)
            extra=await client.ws_connect('/ws');await extra.send_json({'token':at});await extra.receive(timeout=2);assert extra.closed or (await extra.receive(timeout=2)).type.name in ('CLOSE','CLOSED')
            for ws in sockets:await ws.close()
            assert s.guard.ws_active==0
            old=await client.ws_connect('/ws');await old.send_json({'token':at});await old.receive_json()
            for _ in range(5):newest=await api('/login',dict(username='alice',password='test-abuse-password'))
            await old.receive(timeout=3)
            await api('/me',token=at,status=401,method='GET')
            at=newest['token']
            assert s.row('SELECT count(*) AS n FROM sessions WHERE user_id=?',(aid,))['n']==5
            print('PASS per-account WebSocket connection cap and cleanup')

            # Large encrypted rows remain opaque. Pages bound materialization; list summaries keep all contacts.
            start=s.now()-200000
            for i in range(16):
                body=json.dumps({'v':1,'sender_cipher':'a'*150000,'recipient_cipher':'b'*150000})
                s.write('INSERT INTO messages VALUES(?,?,?,?,?,?,?,?)',(f'large-{i}',aid,bid,'encrypted',body,None,start+i,f'large-{i}'))
            seen=set();before=''
            while True:
                result=await api('/messages/'+bid+before,token=at,method='GET')
                assert len(json.dumps(result))<2*MIB+10000
                rows=result['messages'];assert rows
                seen.update(m['id'] for m in rows)
                if not result['has_more']:break
                before='?before='+str(rows[0]['created'])
            assert len(seen)==16
            cap=s.guard.MAX_USER_MESSAGES;s.guard.MAX_USER_MESSAGES=1
            await api('/messages',dict(recipient=bid,text='blocked',client_id='quota-full'),at,507)
            s.guard.MAX_USER_MESSAGES=cap
            for idx,target in enumerate((bid,cid)):
                blob=json.dumps({'v':1,'sender_cipher':'a'*399000,'recipient_cipher':'b'*399000})
                s.write('INSERT INTO messages VALUES(?,?,?,?,?,?,?,?)',(f'last-{idx}',aid,target,'encrypted',blob,None,start+100+idx,f'last-{idx}'))
            conv=await api('/conversations',token=at,method='GET');assert len(conv['conversations'])==2
            orphan=Path(directory,'uploads','f'*32);orphan.write_bytes(b'crash leftovers');os.utime(orphan,(time.time()-600,time.time()-600));s.guard.reap();assert not orphan.exists()
            print('PASS ciphertext history response budgets and pagination, record-space cap, orphan reaping')
        s.db.close()


if __name__=='__main__':asyncio.run(main())
