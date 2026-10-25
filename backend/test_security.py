"""Isolated security regression. SMTP is captured in memory; never contacts production."""
import asyncio
import base64
import gc
import hashlib
import os
import tempfile
import sys
from pathlib import Path
from aiohttp.test_utils import TestClient, TestServer


async def main():
    with tempfile.TemporaryDirectory(prefix='qingliao-security-') as directory:
        os.environ.update(QINGLIAO_DATA=directory, QINGLIAO_REQUIRE_EMAIL='1', QINGLIAO_REQUIRE_E2EE='1', OTP_SECRET='isolated-test-secret', SMTP_PASSWORD='fake-not-a-real-password')
        import server as s
        delivered = {}
        s.security.send_mail = lambda email, code, purpose: delivered.update({(email, purpose): code})
        async with TestClient(TestServer(s.app)) as client:
            async def api(path, body=None, token='', status=200, method='POST'):
                response = await client.request(method, '/api' + path, json=body, headers={'Authorization': 'Bearer ' + token})
                result = await response.json()
                assert response.status == status, (path, response.status, result)
                return result

            async def code(email, purpose, **extra):
                p = await api('/auth/code', dict(email=email, purpose=purpose, **extra))
                while s.security.tasks: await asyncio.gather(*tuple(s.security.tasks))
                return dict(email=email, verification_id=p['verification_id'], code=delivered[(email, purpose)])

            async def register(username, email):
                proof = await code(email, 'register')
                return await api('/register', dict(username=username, nickname=username, password='a-strong-test-password', **proof)), proof

            await api('/register', {'username': 'blocked', 'nickname': 'blocked', 'password': 'strong-password'}, status=400)
            a, proof = await register('alice_secure', 'alice@example.test')
            await api('/register', dict(username='replayed',nickname='Replay',password='a-strong-test-password',**proof), status=400)
            b, _ = await register('bob_secure', 'bob@example.test')
            at, bt = a['token'], b['token']
            aid, bid = a['user']['id'], b['user']['id']
            await api('/friends/request', {'peer': bid}, at)
            await api('/friends/respond', {'peer': aid, 'accept': True}, bt)
            await api('/messages', {'recipient': bid, 'text': 'plaintext must be blocked'}, at, 426)
            async with client.post('/api/uploads?recipient='+bid, data=b'plaintext', headers={'Authorization':'Bearer '+at, 'Content-Type':'text/plain'}) as response:
                assert response.status == 426
            assert 'email' not in a['user']
            identity = lambda kind: __import__('json').dumps({'primaryKeyId': 7, 'key': [{'status':'ENABLED','keyId':7,'outputPrefixType':'TINK','keyData':{'typeUrl':'type.googleapis.com/google.crypto.tink.'+kind,'keyMaterialType':'ASYMMETRIC_PUBLIC','value':base64.b64encode(b'x'*48).decode()}}]})
            pub = {'enc':identity('HpkePublicKey'), 'sig':identity('EcdsaPublicKey')}
            await api('/crypto/identity', pub, at)
            await api('/crypto/identity', pub, bt)
            changed = dict(pub, sig=pub['sig'].replace('eHh4','eHl4'))
            await api('/crypto/identity', changed, at, 409)
            private = dict(pub, enc=pub['enc'].replace('ASYMMETRIC_PUBLIC','ASYMMETRIC_PRIVATE'))
            await api('/crypto/identity', private, at, 400)
            kid=hashlib.sha256((pub['enc']+'\n'+pub['sig']).encode()).hexdigest()
            e={'v':1,'context':f'qingliao:message:1:{aid}:{bid}:encrypted-test:','sender_key':kid,'recipient_key':kid,'sender_cipher':base64.b64encode(b'opaque-ciphertext'*6).decode(),'recipient_cipher':base64.b64encode(b'other-ciphertext'*6).decode(),'signature':base64.b64encode(b'signed-ciphertext'*4).decode()}
            sent=await api('/messages',dict(recipient=bid,type='encrypted',client_id='encrypted-test',envelope=e),at)
            assert sent['type']=='encrypted' and sent['text']=='' and sent['envelope']==e
            again=await api('/messages',dict(recipient=bid,type='encrypted',client_id='encrypted-test',envelope=e),at)
            assert again['id']==sent['id']
            # Opaque envelope validation is not signature verification; clients verify it themselves.
            bad=dict(e,context='wrong-context')
            await api('/messages',dict(recipient=bid,type='encrypted',client_id='wrong',envelope=bad),at,400)
            assert 'plaintext must be blocked' not in Path(directory,'qingliao.sqlite3').read_bytes().decode(errors='ignore')
            # Reset requests do not enumerate accounts. Guess counters and TTL persist in SQLite.
            unknown=await api('/auth/code',{'purpose':'reset','username':'unknown','email':'nobody@example.test'})
            assert unknown.get('verification_id') and ('nobody@example.test','reset') not in delivered
            s.db.execute('UPDATE verifications SET created=created-61000 WHERE email=?',('alice@example.test',));s.db.commit()
            reset=await code('alice@example.test','reset',username='alice_secure')
            await api('/password/reset',dict(username='alice_secure',new_password='new-strong-password',**dict(reset,code='invalid')),status=400)
            assert s.row('SELECT attempts FROM verifications WHERE id=?',(reset['verification_id'],))['attempts']==1
            ws=await client.ws_connect('/ws');await ws.send_json({'token':at});assert (await ws.receive_json())['event']=='ready'
            await api('/password/reset',dict(username='alice_secure',new_password='new-strong-password',**reset))
            await ws.receive(timeout=3);assert ws.closed or (await ws.receive(timeout=3)).type.name in ('CLOSE','CLOSED')
            await api('/me', token=at, status=401, method='GET')
            await api('/login',dict(username='alice_secure',password='a-strong-test-password'),status=401)
            newer=await api('/login',dict(username='alice@example.test',password='new-strong-password'))
            await api('/password/reset',dict(username='alice_secure',new_password='yet-another-password',**reset),status=400)
            await api('/password/change',dict(old_password='wrong-password',new_password='another-good-password'),newer['token'],403)
            await api('/password/change',dict(old_password='new-strong-password',new_password='another-good-password'),newer['token'])
            await api('/me',token=newer['token'],status=401,method='GET')
            # Bind a legacy account: current password + proof, and never expose email in public profiles.
            s.write("UPDATE users SET email='' WHERE id=?",(bid,))
            p=await api('/auth/code',dict(purpose='bind',email='legacy@example.test',password='a-strong-test-password'),bt)
            while s.security.tasks: await asyncio.gather(*tuple(s.security.tasks))
            await api('/email/bind',dict(email='legacy@example.test',password='a-strong-test-password',verification_id=p['verification_id'],code=delivered[('legacy@example.test','bind')]),bt)
            assert (await api('/account',token=bt,method='GET'))['email']=='legacy@example.test'
            assert 'email' not in s.user_public(bid,aid)
            # Five failed guesses exhaust proof even when the correct code is supplied afterwards.
            s.limits.clear()  # The earlier assertions deliberately consumed the registration IP budget.
            lock=await code('locked@example.test','register')
            for _ in range(5): await api('/register',dict(username='lock_test',nickname='Lock',password='test-password',**dict(lock,code='invalid')),status=400)
            await api('/register',dict(username='lock_test',nickname='Lock',password='test-password',**lock),status=400)
            expiry=await code('expired@example.test','register')
            s.write('UPDATE verifications SET expires=0 WHERE id=?',(expiry['verification_id'],))
            await api('/register',dict(username='expiry_test',nickname='Expiry',password='test-password',**expiry),status=400)
            print('PASS verified registration; replay/TTL/guess limits; reset enumeration; session revocation; password change; legacy email bind; immutable public keys; private-key rejection; plaintext blocked; ciphertext delivery and deduplication')
        s.db.close()
        gc.collect()


if __name__ == '__main__': asyncio.run(main())
