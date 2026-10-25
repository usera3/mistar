"""Lost-identity recovery: owned isolated accounts and captured SMTP, never live mail."""
import asyncio,base64,hashlib,json,os,tempfile
from aiohttp.test_utils import TestClient,TestServer


async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-recovery-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0',OTP_SECRET='isolated-recovery-secret',SMTP_PASSWORD='test-only-placeholder')
        import server as s
        delivered={};s.security.send_mail=lambda email,code,purpose:delivered.update({purpose:code})
        def key(kind,value):return json.dumps({'primaryKeyId':7,'key':[{'keyId':7,'status':'ENABLED','outputPrefixType':'TINK','keyData':{'typeUrl':'type.googleapis.com/google.crypto.tink.'+kind,'keyMaterialType':'ASYMMETRIC_PUBLIC','value':base64.b64encode(bytes([value])*48).decode()}}]})
        def identity(value):return dict(enc=key('HpkePublicKey',value),sig=key('EcdsaPublicKey',value))
        async with TestClient(TestServer(s.app)) as client:
            async def api(path,p=None,token='',status=200,method='POST'):
                async with client.request(method,'/api'+path,json=p,headers={'Authorization':'Bearer '+token}) as r:
                    out=await r.json();assert r.status==status,(path,r.status,out);return out
            a,b,c=[await api('/register',dict(username=name,nickname=name,password='owned-recovery-password')) for name in ('alice','bobby','carol')]
            at,bt,ct=a['token'],b['token'],c['token'];aid,bid=a['user']['id'],b['user']['id']
            s.write('UPDATE users SET email=? WHERE id=?',('alice@example.test',aid))
            old=await api('/crypto/identity',identity(1),at);new=identity(2)
            await api('/friends/request',{'peer':bid},at);await api('/friends/respond',{'peer':aid,'accept':True},bt)
            await api('/messages',{'recipient':bid,'text':'preserved old history'},at)
            await api('/blocks',{'peer':c['user']['id'],'blocked':True},at)
            await api('/auth/code',dict(purpose='identity_restart',email='alice@example.test',password='wrong-password'),at,403)
            proof=await api('/auth/code',dict(purpose='identity_restart',email='alice@example.test',password='owned-recovery-password'),at)
            while s.security.tasks:await asyncio.gather(*tuple(s.security.tasks))
            request=dict(new,operation='owned-recovery-operation-0001',password='owned-recovery-password',verification_id=proof['verification_id'],code=delivered['identity_restart'])
            await api('/crypto/restart',dict(request,code='000000' if request['code']!='000000' else '111111'),at,400)
            assert s.security.identity_for(aid)['key_id']==old['key_id']
            result=await api('/crypto/restart',request,at)
            await api('/me',token=at,status=401,method='GET')
            assert s.security.identity_for(aid)['key_id']==result['key_id']!=old['key_id']
            assert not s.rows('SELECT * FROM friends WHERE owner=? OR peer=?',(aid,aid))
            assert s.rows('SELECT * FROM blocks WHERE owner=?',(aid,))
            assert s.rows('SELECT * FROM messages WHERE sender=?',(aid,))
            print('PASS round 18: password + account-bound email OTP, atomic identity restart, old sessions revoked, friends removed, blocks/history retained')
            assert (await api('/crypto/identity/'+aid+'?key_id='+old['key_id'],token=bt,method='GET'))==old
            await api('/crypto/identity/'+aid+'?key_id='+old['key_id'],token=ct,status=403,method='GET')
            newer=await api('/login',dict(username='alice',password='owned-recovery-password'));nt=newer['token']
            assert (await api('/crypto/restart',request,nt))['ok']
            await api('/crypto/restart',dict(request,operation='owned-recovery-operation-0002',**identity(3)),nt,429)
            print('PASS round 17 backend: immutable archived public keys, participant-only lookup, repeat operation recovery and restart cooldown')
            await api('/messages',{'recipient':bid,'text':'cannot silently resume'},nt,403)
            await api('/friends/request',{'peer':bid},nt);await api('/friends/respond',{'peer':aid,'accept':True},bt)
            await api('/messages',{'recipient':bid,'text':'explicitly re-added'},nt)
            print('PASS round 19 backend: new identity does not regain contact until an explicit request and acceptance')
        s.db.close()


if __name__=='__main__':asyncio.run(main())
