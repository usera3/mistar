"""Isolated encrypted relay/session tests. Real crypto and GPS UI are exercised natively."""
import asyncio,base64,gc,json,os,tempfile,time,uuid
from aiohttp.test_utils import TestClient,TestServer

async def main():
    with tempfile.TemporaryDirectory(prefix='mistar-locations-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='1',QINGLIAO_REQUIRE_E2EE='1',OTP_SECRET='isolated-location-secret',SMTP_PASSWORD='fake-location-password')
        import server as s
        delivered={};s.security.send_mail=lambda email,code,purpose:delivered.update({(email,purpose):code});checks=0
        async with TestClient(TestServer(s.app)) as client:
            async def api(path,body=None,user=None,status=200,method='POST'):
                response=await client.request(method,'/api'+path,json=body,headers={'Authorization':'Bearer '+(user['token'] if user else '')});value=await response.json();assert response.status==status,(path,response.status,value);return value
            async def get(path,user,status=200):return await api(path,user=user,status=status,method='GET')
            email='location_alice@example.test';v=await api('/auth/code',dict(purpose='register',email=email))
            while s.security.tasks:await asyncio.gather(*tuple(s.security.tasks))
            alice=await api('/register',dict(username='location_alice',nickname='位置测试A',password='location-test-password',email=email,verification_id=v['verification_id'],code=delivered[(email,'register')]));aid=alice['user']['id']
            def seed(uid,name):
                values=s.row('SELECT * FROM users WHERE id=?',(aid,));values.update(id=uid,username=name,nickname=name,email=name+'@example.test',email_key=s.guard.email_key(name+'@example.test'));fields=list(values)
                s.db.execute('INSERT INTO users('+','.join(fields)+') VALUES('+','.join('?' for _ in fields)+')',[values[k] for k in fields]);s.db.commit();return s.new_session(uid)
            bob=seed('90190002','location_bob');carl=seed('90190003','location_carl');outsider=seed('90190004','location_outside')
            for user in (alice,bob,carl,outsider):
                uid=user['user']['id']
                def key(kind,tag):return json.dumps({'primaryKeyId':7,'key':[{'status':'ENABLED','keyId':7,'outputPrefixType':'TINK','keyData':{'typeUrl':'type.googleapis.com/google.crypto.tink.'+kind,'keyMaterialType':'ASYMMETRIC_PUBLIC','value':base64.b64encode((tag+uid).encode()*5).decode()}}]})
                await api('/crypto/identity',dict(enc=key('HpkePublicKey','enc'),sig=key('EcdsaPublicKey','sig')),user)
            for user in (bob,carl):await api('/friends/request',{'peer':user['user']['id']},alice);await api('/friends/respond',{'peer':aid,'accept':True},user)
            s.limits.clear()
            def request(peer,epoch=0):return dict(id=str(uuid.uuid4()),peer=peer,duration=3600,epoch=epoch)
            await api('/locations',request(bob['user']['id']),status=401);checks+=1
            await api('/locations',dict(request(bob['user']['id']),lat=35),alice,status=400);await api('/locations',dict(request(bob['user']['id']),duration=86400),alice,status=400);checks+=1
            await api('/locations',request(outsider['user']['id']),alice,status=403);checks+=1
            p=request(bob['user']['id']);share=(await api('/locations',p,alice,status=201))['share'];sid=share['id'];assert (await api('/locations',p,alice))['share']==share
            await api('/locations',request(bob['user']['id']),alice,status=409);checks+=1
            assert (await get('/locations/'+sid,bob))['update'] is None;await get('/locations/'+sid,outsider,status=404)
            await api('/locations/'+sid,dict(seq=1,wire={}),bob,status=403,method='PUT');await api('/locations/'+sid,user=bob,status=403,method='DELETE');checks+=1
            def encoded(value):return base64.b64encode(value.encode()).decode()
            def context(meta,n):return f"qingliao:location:1:{meta['id']}:{n}:{meta['owner']}:{meta['peer']}:{meta['epoch']}"
            def private_wire(meta,n):return dict(envelope=dict(v=1,context=context(meta,n),sender_key=s.security.identity_for(aid)['key_id'],recipient_key=s.security.identity_for(meta['peer'])['key_id'],sender_cipher=encoded('opaque-sender-cipher'),recipient_cipher=encoded('opaque-recipient-cipher'),signature=encoded('opaque-test-signature')))
            ws=await client.ws_connect('/ws');await ws.send_json(dict(token=bob['token']));assert (await ws.receive_json())['event']=='ready'
            before=s.row('SELECT count(*) AS n FROM messages')['n'];await api('/locations/'+sid,dict(seq=1,wire=private_wire(share,1)),alice,method='PUT');event=await ws.receive_json(timeout=3)
            assert event['event']=='location.live' and event['update']['recipient']==bob['user']['id'] and 'lat' not in event['update']
            assert s.row('SELECT count(*) AS n FROM messages')['n']==before;checks+=1
            assert (await get('/locations/'+sid,bob))['update']['seq']==1;await api('/locations/'+sid,dict(seq=1,wire=private_wire(share,1)),alice,status=409,method='PUT');checks+=1
            s.locations.shares[sid]['last']=0;await api('/locations/'+sid,dict(seq=2,wire=private_wire(share,1)),alice,status=400,method='PUT')
            bad=private_wire(share,2);bad['envelope']['recipient_cipher']='x'*10000;await api('/locations/'+sid,dict(seq=2,wire=bad),alice,status=400,method='PUT');checks+=1
            await api('/locations/'+sid,dict(seq=2,wire={'padding':'x'*140000}),alice,status=413,method='PUT');checks+=1
            await api('/locations/'+sid,user=alice,method='DELETE');assert (await ws.receive_json(timeout=3))['event']=='location.closed';assert sid not in s.locations.shares;await get('/locations/'+sid,bob,status=404);await ws.close();checks+=1
            await api('/locations',p,alice,status=409);checks+=1
            group=(await api('/groups',dict(name='合成位置群',members=[bob['user']['id'],carl['user']['id']],client_id=str(uuid.uuid4())),alice,status=201))['group'];gid=group['id']
            share=(await api('/locations',request(gid,group['epoch']),alice,status=201))['share'];sid=share['id'];members=s.groups.recipients(gid,aid)
            def group_wire(meta,n):return dict(v=1,context=context(meta,n),sender_key=s.security.identity_for(aid)['key_id'],envelopes={uid:dict(recipient_key=s.security.identity_for(uid)['key_id'],cipher=encoded('opaque-distinct-'+uid)) for uid in members},signature=encoded('opaque-group-signature'))
            ws=await client.ws_connect('/ws');await ws.send_json(dict(token=bob['token']));assert (await ws.receive_json())['event']=='ready'
            before=s.row('SELECT count(*) AS n FROM group_messages')['n'];await api('/locations/'+sid,dict(seq=1,wire=group_wire(share,1)),alice,method='PUT');dto=(await ws.receive_json(timeout=3))['update']
            assert dto['envelope']['cipher']==encoded('opaque-distinct-'+bob['user']['id']) and 'envelopes' not in dto['envelope'];assert s.row('SELECT count(*) AS n FROM group_messages')['n']==before;checks+=1
            s.locations.shares[sid]['last']=0;wrong=group_wire(share,2);wrong['envelopes'].pop(carl['user']['id']);await api('/locations/'+sid,dict(seq=2,wire=wrong),alice,status=409,method='PUT');checks+=1
            await api('/groups/'+gid+'/members',dict(action='remove',member=carl['user']['id'],epoch=group['epoch']),alice);await s.locations.tick();assert sid not in s.locations.shares
            await get('/locations/'+sid,bob,status=404);await get('/locations/'+sid,carl,status=404);await ws.close();checks+=1
            s.limits.clear()
            share=(await api('/locations',request(bob['user']['id']),alice,status=201))['share'];sid=share['id']
            await api('/blocks',dict(peer=aid,blocked=True),bob);await s.locations.tick();assert sid not in s.locations.shares;await get('/locations/'+sid,bob,status=404);await api('/blocks',dict(peer=aid,blocked=False),bob);checks+=1
            await api('/friends/request',dict(peer=bob['user']['id']),alice);await api('/friends/respond',dict(peer=aid,accept=True),bob)
            share=(await api('/locations',request(bob['user']['id']),alice,status=201))['share'];sid=share['id'];s.locations.shares[sid]['deadline']=time.monotonic()-1;await s.locations.tick();assert sid not in s.locations.shares;checks+=1
            share=(await api('/locations',request(bob['user']['id']),alice,status=201))['share'];sid=share['id'];s.locations.shares[sid]['touched']=time.monotonic()-76;await s.locations.tick();assert sid not in s.locations.shares;checks+=1
            share=(await api('/locations',request(bob['user']['id']),alice,status=201))['share'];sid=share['id'];s.db.execute('DELETE FROM sessions WHERE hash=?',(s.locations.shares[sid]['session'],));s.db.commit();await s.locations.tick();assert sid not in s.locations.shares;await get('/locations/'+sid,bob,status=404);checks+=1
            assert not s.rows("SELECT name FROM sqlite_master WHERE name LIKE 'location%'");assert s.db.execute('PRAGMA integrity_check').fetchone()[0]=='ok' and not s.db.execute('PRAGMA foreign_key_check').fetchall();checks+=1
        s.db.close();gc.collect();print(f'PASS locations: {checks} relay/session/ACL/replay/expiry/no-history checks')

if __name__=='__main__':asyncio.run(main())
