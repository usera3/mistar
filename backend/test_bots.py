"""Isolated real-Tink/API/Webhook regressions. No production traffic or SMTP."""
import asyncio
import hashlib
import hmac
import json
import os
import tempfile
import uuid
from pathlib import Path
from unittest.mock import AsyncMock, patch
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from botcrypto import compact, b64, un64, signed
from bots import PublicResolver, public_ip


async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-bots-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='1')
        import server as s
        async def idle(): await asyncio.Event().wait()
        s.bots.worker=idle  # Deterministic delivery, real queue/crypto/HTTP signature paths below.
        hooks=[]; hook_secret=''; fail=False; redirect=False
        async def callback(request):
            raw=await request.read(); ts=request.headers['X-Qingliao-Timestamp']; eid=request.headers['X-Qingliao-Event-Id']
            expected='sha256='+hmac.new(hook_secret.encode(),ts.encode()+b'.'+eid.encode()+b'.'+raw,hashlib.sha256).hexdigest()
            assert hmac.compare_digest(expected,request.headers['X-Qingliao-Signature'])
            p=json.loads(raw); assert p['id']==eid; hooks.append(p)
            if redirect:return web.Response(status=302,headers={'Location':'http://127.0.0.1/private'})
            if fail:return web.Response(status=503)
            return web.json_response({'challenge':p['challenge']} if p['event']=='webhook.verify' else {'ok':True})
        hook_app=web.Application();hook_app.router.add_post('/webhook',callback)
        async with TestClient(TestServer(hook_app)) as hook, TestClient(TestServer(s.app)) as client:
            class LocalTestTransport:
                def post(self,url,**kw):
                    assert url=='https://developer.example.com/webhook' and kw.pop('allow_redirects') is False
                    return hook.post('/webhook',**kw)
            production_http=s.bots.http; s.bots.http=LocalTestTransport()
            async def api(path,body=None,token='',method='POST',status=200):
                r=await client.request(method,'/api'+path,json=body,headers={'Authorization':'Bearer '+token})
                p=await r.json();assert r.status==status,(path,r.status,p);return p
            async def register(name):
                r=await api('/register',{'username':name,'password':'isolated-strong-password','nickname':name})
                return r['user']['id'],r['token']
            a,at=await register('bot_owner'); b,bt=await register('bot_user'); c,ct=await register('other_owner')
            await api('/developer/bots',{'nickname':'Demo'},at,status=403)
            s.write('UPDATE users SET email=? WHERE id=?',('owner@example.test',a))
            s.write('UPDATE users SET email=? WHERE id=?',('other@example.test',c))
            created=await api('/developer/bots',{'nickname':'接口助手','description':'Webhook test'},at,status=201)
            bot=created['bot']['id'];token=created['token'];base='/developer/bots/'+bot
            assert created['bot']['bot'] and not created['bot']['bot_ready']
            assert token not in compact(s.get('') if False else s.bots.get(bot))
            assert 'HpkePrivateKey' not in s.bots.get(bot)['identity']
            assert (Path(directory)/'bot-master.key').stat().st_mode&0o777==0o600
            await api(base,{'enabled':False},ct,method='PATCH',status=404)
            await api('/bot/me',token=at,method='GET',status=403)
            for path,method in (('/me','GET'),('/contacts','GET'),('/account','GET'),('/developer/bots','GET'),('/friends/request','POST'),('/posts','POST'),('/crypto/identity','POST'),('/messages','POST'),('/ice','GET')):
                await api(path,{},token,method=method,status=403)
            await api('/friends/request',{'peer':bot,'bot_consent':True},bt,status=409)
            for bad in ('http://example.com/hook','https://127.0.0.1/hook','https://[::1]/hook','https://169.254.169.254/hook','https://47.245.2.254/hook','https://example.com:8443/hook','https://u:p@example.com/hook','https://example.com/hook?a=secret','https://example.com/hook#x','https://[::ffff:8.8.8.8]/hook'):
                s.limits.clear();await api(base+'/webhook',{'url':bad},at,method='PUT',status=400)
            resolver=PublicResolver(s.PUBLIC_IP)
            with patch('aiohttp.resolver.ThreadedResolver.resolve',new=AsyncMock(return_value=[{'host':'127.0.0.1'}])):
                try:await resolver.resolve('developer.example.com');raise AssertionError('private DNS accepted')
                except OSError:pass
            await resolver.close()
            assert not public_ip('224.0.0.1') and not public_ip('100.64.0.1') and public_ip('8.8.8.8')
            s.limits.clear()
            configured=await api(base+'/webhook',{'url':'https://developer.example.com/webhook'},at,method='PUT')
            hook_secret=configured['webhook_secret']
            assert hook_secret not in s.bots.get(bot)['secret']
            redirect=True;await api(base+'/verify',{},at,status=400);redirect=False
            await api(base+'/verify',{},at)
            await api('/friends/request',{'peer':bot},bt,status=400)
            await api('/friends/request',{'peer':bot,'bot_consent':True},bt)
            assert (await api('/bot/contacts',token=token,method='GET'))['contacts'][0]['id']==b
            private,public=s.bots.crypto.create();await api('/crypto/identity',public,bt)
            bot_public=s.security.identity_for(bot);crypto=s.bots.crypto
            def human_payload(cid,inner,fid=''):
                context=f'qingliao:message:1:{b}:{bot}:{cid}:{fid}';data=compact(inner).encode()
                e={'v':1,'context':context,'sender_key':public['key_id'],'recipient_key':bot_public['key_id'],
                    'sender_cipher':b64(crypto.read(public['enc']).primitive(crypto.hybrid.HybridEncrypt).encrypt(data,context.encode())),
                    'recipient_cipher':b64(crypto.read(bot_public['enc']).primitive(crypto.hybrid.HybridEncrypt).encrypt(data,context.encode()))}
                e['signature']=b64(crypto.read(private['sig']).primitive(crypto.signature.PublicKeySign).sign(signed(e)))
                return {'recipient':bot,'client_id':cid,'type':'encrypted','envelope':e,**({'file_id':fid} if fid else {})}
            def decrypt_bot(m):
                e=m['envelope'];crypto.read(bot_public['sig']).primitive(crypto.signature.PublicKeyVerify).verify(un64(e['signature']),signed(e))
                return json.loads(crypto.read(private['enc']).primitive(crypto.hybrid.HybridDecrypt).decrypt(un64(e['recipient_cipher']),e['context'].encode()))
            body={'recipient':b,'client_id':'reply-1','type':'html','text':'<b>机器人卡片</b>'}
            sent=await api('/bot/messages',body,token)
            again=await api('/bot/messages',body,token);assert again['id']==sent['id']
            message=s.message_public(s.row('SELECT * FROM messages WHERE id=?',(sent['id'],)))
            assert decrypt_bot(message)=={'type':'html','text':'<b>机器人卡片</b>'}
            assert '机器人卡片' not in s.row('SELECT text FROM messages WHERE id=?',(sent['id'],))['text']
            await api('/bot/messages',dict(body,recipient=c),token,status=403)
            await api('/bot/messages',dict(body,client_id='oversized',text='x'*50001),token,status=400)
            inbound=await api('/messages',human_payload('incoming-1',{'type':'text','text':'Webhook 入站'}),bt)
            duplicate=await api('/messages',human_payload('incoming-1',{'type':'text','text':'Webhook 入站'}),bt)
            assert inbound['id']==duplicate['id'] and s.row('SELECT count(*) AS n FROM bot_deliveries')['n']==1
            item=s.row('SELECT * FROM bot_deliveries');fail=True;await s.bots.deliver(item)
            retried=s.row('SELECT * FROM bot_deliveries');assert retried['attempts']==1 and retried['due']>s.now()
            fail=False;await s.bots.deliver(retried);assert not s.row('SELECT * FROM bot_deliveries')
            assert hooks[-2]['id']==hooks[-1]['id'] and hooks[-1]['message']['text']=='Webhook 入站'
            # Server restart reopens only managed bot keys. Android/human keys remain outside storage.
            from botcrypto import BotCrypto
            reopened=BotCrypto(s.__dict__);assert reopened.private(bot)==crypto.private(bot)
            # Streaming media spans more than one Tink segment and decrypts byte-for-byte both ways.
            raw=b'\x89PNG\r\n\x1a\n'+os.urandom(1100000)
            upload=await client.post('/api/bot/uploads',params={'recipient':b,'name':'photo.png'},data=raw,headers={'Authorization':'Bearer '+token,'Content-Type':'image/png'})
            assert upload.status==200,await upload.text();media=await upload.json()
            file_sent=await api('/bot/messages',{'recipient':b,'client_id':'image-1','type':'image','file_id':media['id']},token)
            fm=decrypt_bot(s.message_public(s.row('SELECT * FROM messages WHERE id=?',(file_sent['id'],))))['file']
            with (Path(directory)/'uploads'/media['id']).open('rb') as src, crypto.decrypting(src,fm['crypto'],fm['context']) as stream:assert stream.read()==raw
            download=await client.get('/api/bot/files/'+media['id'],headers={'Authorization':'Bearer '+token});assert await download.read()==raw
            # Human encrypted upload -> callback contains a scoped file path, never private crypto material.
            source=Path(directory)/'human-encrypted';key=crypto.stream_key();context='qingliao:attachment:1:'+str(uuid.uuid4())
            with source.open('wb') as dest:
                with crypto.encrypting(dest,key,context) as stream:stream.write(raw)
            r=await client.post('/api/uploads',params={'recipient':bot},data=source.read_bytes(),headers={'Authorization':'Bearer '+bt,'Content-Type':'application/x-qingliao-encrypted'})
            assert r.status==200;fid=(await r.json())['id']
            meta={'id':fid,'name':'用户图片.png','mime':'image/png','size':len(raw),'crypto':key,'context':context}
            await api('/messages',human_payload('human-image',{'type':'image','text':'','file':meta},fid),bt)
            await s.bots.deliver(s.row('SELECT * FROM bot_deliveries'))
            assert hooks[-1]['message']['file_path']=='/api/bot/files/'+fid and 'crypto' not in hooks[-1]['message']['file']
            r=await client.get('/api/bot/files/'+fid,headers={'Authorization':'Bearer '+token});assert await r.read()==raw
            r=await client.post('/api/bot/uploads?recipient='+b,data=b'',headers={'Authorization':'Bearer '+token,'Content-Length':str(s.MAX_FILE+1)});assert r.status==413
            # Invalid signatures are never delivered, withdrawn pending messages are dropped.
            p=human_payload('bad-signature',{'type':'text','text':'untrusted'});p['envelope']['signature']=b64(b'invalid-signature')
            bad=await api('/messages',p,bt);before=len(hooks);await s.bots.deliver(s.row('SELECT * FROM bot_deliveries'))
            assert len(hooks)==before
            await api('/messages/'+bad['id']+'/withdraw',{},bt);await s.bots.deliver(s.row('SELECT * FROM bot_deliveries'));assert not s.row('SELECT * FROM bot_deliveries')
            await api(base,{'enabled':False},at,method='PATCH');await api('/bot/me',token=token,method='GET',status=401)
            await api('/messages',human_payload('paused',{'type':'text','text':'paused'}),bt,status=409)
            await api(base,{'enabled':True},at,method='PATCH')
            new=(await api(base+'/rotate',{},at))['token'];await api('/bot/me',token=token,method='GET',status=401)
            assert (await api('/bot/me',token=new,method='GET'))['id']==bot
            await api('/blocks',{'peer':bot,'blocked':True},bt)
            await api('/bot/messages',dict(body,client_id='blocked'),new,status=403)
            assert s.db.execute('PRAGMA integrity_check').fetchone()[0]=='ok' and not s.db.execute('PRAGMA foreign_key_check').fetchall()
            s.bots.http=production_http
            print('PASS bots: real Tink text/HTML/media, consent, scope/ownership, SSRF/DNS/redirect, challenge/HMAC, durable retry/idempotency, revoke/pause/block/withdraw, limits and master-key recovery')


if __name__=='__main__':asyncio.run(main())
