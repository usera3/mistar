"""Group ACL/epoch/delivery/storage regressions. Cryptographic primitives are tested natively."""
import asyncio
import base64
import gc
import json
import os
import tempfile
import uuid
from aiohttp.test_utils import TestClient, TestServer


async def main():
    with tempfile.TemporaryDirectory(prefix='mistar-groups-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='1',QINGLIAO_REQUIRE_E2EE='1',OTP_SECRET='isolated-groups-test-secret',SMTP_PASSWORD='fake-groups-test-password')
        import server as s
        delivered={};s.security.send_mail=lambda email,code,purpose:delivered.update({(email,purpose):code})
        checks=0
        async with TestClient(TestServer(s.app)) as client:
            async def api(path,body=None,user=None,status=200,method='POST',features=False):
                headers={'Authorization':'Bearer '+(user['token'] if user else '')}
                if features:headers['X-Mistar-Groups']='1'
                response=await client.request(method,'/api'+path,json=body,headers=headers);value=await response.json()
                assert response.status==status,(path,response.status,value)
                return value
            async def get(path,user,status=200,features=False):return await api(path,user=user,status=status,method='GET',features=features)
            async def register(name):
                email=name+'@example.test';p=await api('/auth/code',dict(purpose='register',email=email))
                while s.security.tasks:await asyncio.gather(*tuple(s.security.tasks))
                return await api('/register',dict(username=name,nickname=name,password='group-test-password',email=email,verification_id=p['verification_id'],code=delivered[(email,'register')]))
            alice=await register('group_alice');aid=alice['user']['id']
            def seed(uid,name):
                # Group tests need multiple valid users; registration rate limits have separate suites.
                values=s.row('SELECT * FROM users WHERE id=?',(aid,));values.update(id=uid,username=name,nickname=name,email=name+'@example.test',email_key=s.guard.email_key(name+'@example.test'))
                fields=list(values);s.db.execute('INSERT INTO users('+','.join(fields)+') VALUES('+','.join('?' for _ in fields)+')',[values[k] for k in fields]);s.db.commit()
                return s.new_session(uid)
            bob=seed('90180002','group_bob');carl=seed('90180003','group_carl');dana=seed('90180004','group_dana');outsider=seed('90180005','group_outside')
            people=[alice,bob,carl,dana,outsider]
            for user in people:
                uid=user['user']['id']
                def key(kind,tag):return json.dumps({'primaryKeyId':7,'key':[{'status':'ENABLED','keyId':7,'outputPrefixType':'TINK','keyData':{'typeUrl':'type.googleapis.com/google.crypto.tink.'+kind,'keyMaterialType':'ASYMMETRIC_PUBLIC','value':base64.b64encode((tag+uid).encode()*5).decode()}}]})
                await api('/crypto/identity',dict(enc=key('HpkePublicKey','enc'),sig=key('EcdsaPublicKey','sig')),user)
            for user in (bob,carl,dana):
                await api('/friends/request',{'peer':user['user']['id']},alice)
                await api('/friends/respond',{'peer':aid,'accept':True},user)
            s.limits.clear()
            creation=dict(name='合成好友群',members=[bob['user']['id'],carl['user']['id']],client_id=str(uuid.uuid4()))
            created=await api('/groups',creation,alice,status=201);gid=created['group']['id'];assert created['group']['member_count']==3;checks+=1
            assert (await api('/groups',creation,alice))['group']['id']==gid
            await api('/groups',dict(creation,name='不同的创建内容'),alice,status=409);checks+=1
            await api('/groups',dict(name='非法邀请',members=[outsider['user']['id']],client_id=str(uuid.uuid4())),alice,status=403)
            await api('/groups',dict(name='太多成员',members=[str(99000000+n) for n in range(20)],client_id=str(uuid.uuid4())),alice,status=400);checks+=1
            assert all(not u.get('group') for u in (await get('/contacts',alice))['contacts'])
            assert any(u.get('id')==gid for u in (await get('/contacts',alice,features=True))['contacts'])
            assert all(not c['user'].get('group') for c in (await get('/conversations',alice))['conversations']);checks+=1
            for suffix in ('','/keys','/messages'):
                await get('/groups/'+gid+suffix,outsider,status=403)
            await get('/crypto/identity/'+carl['user']['id'],bob,status=403)
            assert (await get('/groups/'+gid+'/identities/'+carl['user']['id'],bob))['key_id'];checks+=1
            async def frame(user,group=gid,fid=''):
                roster=await get('/groups/'+group+'/keys',user);epoch=roster['epoch'];uid=user['user']['id'];cid=str(uuid.uuid4())
                envelope={v['id']:{'recipient_key':v['key_id'],'cipher':base64.b64encode(b'\x99opaque-'+v['id'].encode()).decode()} for v in roster['recipients']}
                return dict(v=1,epoch=epoch,client_id=cid,file_id=fid,context=f'qingliao:group-message:1:{group}:{epoch}:{uid}:{cid}:{fid}',sender_key=s.security.identity_for(uid)['key_id'],envelopes=envelope,signature=base64.b64encode(b'synthetic-signature').decode())
            ws_b=await client.ws_connect('/ws');await ws_b.send_json({'token':bob['token']});assert (await ws_b.receive_json())['event']=='ready'
            ws_c=await client.ws_connect('/ws');await ws_c.send_json({'token':carl['token']});assert (await ws_c.receive_json())['event']=='ready'
            first=await frame(alice);msg=await api('/groups/'+gid+'/messages',first,alice)
            pushed_b=await ws_b.receive_json(timeout=3);pushed_c=await ws_c.receive_json(timeout=3)
            assert pushed_b['event']==pushed_c['event']=='group.message'
            assert pushed_b['message']['recipient']==bob['user']['id'] and pushed_c['message']['recipient']==carl['user']['id']
            assert pushed_b['message']['envelope']['cipher']==first['envelopes'][bob['user']['id']]['cipher'] and first['envelopes'][carl['user']['id']]['cipher'] not in json.dumps(pushed_b)
            await ws_b.close();await ws_c.close();checks+=1
            assert msg['recipient']==aid and msg['group_id']==gid and 'envelopes' not in msg['envelope']
            bmsg=next(m for m in (await get('/groups/'+gid+'/messages',bob))['messages'] if m['id']==msg['id'])
            assert bmsg['recipient']==bob['user']['id'] and bmsg['envelope']['cipher']==first['envelopes'][bob['user']['id']]['cipher'];checks+=1
            assert (await api('/groups/'+gid+'/messages',first,alice))['id']==msg['id']
            assert (await get('/groups/'+gid+'/message-status/'+first['client_id'],alice))['acknowledged'];checks+=1
            bad=await frame(alice);bad['envelopes'].pop(bob['user']['id']);await api('/groups/'+gid+'/messages',bad,alice,status=409)
            bad=await frame(alice);bad['context']='wrong-context';await api('/groups/'+gid+'/messages',bad,alice,status=400)
            await api('/groups/'+gid+'/messages',dict(type='text',text='must never be stored',client_id=str(uuid.uuid4())),alice,status=409);checks+=1
            async def upload(user,group,data):
                response=await client.post('/api/uploads?recipient='+group,data=data,headers={'Authorization':'Bearer '+user['token'],'Content-Type':'application/x-qingliao-encrypted'})
                result=await response.json();assert response.status==200,(response.status,result);return result
            encrypted=b'\x88synthetic encrypted group attachment'
            file=await upload(alice,gid,encrypted);fid=file['id']
            response=await client.get('/api/files/'+fid,headers={'Authorization':'Bearer '+bob['token']});assert response.status==404
            filemsg=await api('/groups/'+gid+'/messages',await frame(alice,fid=fid),alice)
            response=await client.get('/api/files/'+fid,headers={'Authorization':'Bearer '+bob['token']});assert response.status==200 and await response.read()==encrypted
            await api('/storage/cleanup',{},alice)
            assert (s.DATA/'uploads'/fid).exists() and (await get('/storage',alice))['sent']>=len(encrypted);checks+=1
            await api('/groups/'+gid+'/read',{},bob)
            row=next(m for m in (await get('/groups/'+gid+'/messages',alice))['messages'] if m['id']==msg['id'])
            assert row['read_count']==1;checks+=1
            stale=await frame(alice)
            group=(await get('/groups/'+gid,alice))['group']
            await api('/groups/'+gid+'/members',dict(action='invite',members=[dana['user']['id']],epoch=group['epoch']),alice)
            newhistory=(await get('/groups/'+gid+'/messages',dana))['messages'];assert all(m['id'] not in (msg['id'],filemsg['id']) for m in newhistory)
            response=await client.get('/api/files/'+fid,headers={'Authorization':'Bearer '+dana['token']});assert response.status==404
            await api('/groups/'+gid+'/messages',stale,alice,status=409)
            assert (await api('/groups/'+gid+'/messages',first,alice))['id']==msg['id'];checks+=1
            fresh=await api('/groups/'+gid+'/messages',await frame(alice),alice)
            assert any(m['id']==fresh['id'] for m in (await get('/groups/'+gid+'/messages',dana))['messages']);checks+=1
            group=(await get('/groups/'+gid,alice))['group']
            await api('/groups/'+gid+'/members',dict(action='remove',member=carl['user']['id'],epoch=group['epoch']),bob,status=403)
            await api('/groups/'+gid+'/members',dict(action='transfer',member=bob['user']['id'],epoch=group['epoch']),alice)
            assert (await api('/groups',creation,alice))['group']['id']==gid
            await api('/groups/'+gid,dict(name='无权改名'),alice,status=403,method='PATCH')
            await api('/groups/'+gid,dict(name='新群名称'),bob,method='PATCH');checks+=1
            group=(await get('/groups/'+gid,bob))['group']
            await api('/groups/'+gid+'/members',dict(action='remove',member=carl['user']['id'],epoch=group['epoch']),bob)
            await get('/groups/'+gid+'/messages',carl,status=403)
            response=await client.get('/api/files/'+fid,headers={'Authorization':'Bearer '+carl['token']});assert response.status==404;checks+=1
            # A member removed while an encrypted stream is uploading must not commit a file.
            started=asyncio.Event();resume=asyncio.Event()
            async def chunks():
                yield b'\x88first';started.set();await resume.wait();yield b'last'
            slow=asyncio.create_task(client.post('/api/uploads?recipient='+gid,data=chunks(),headers={'Authorization':'Bearer '+dana['token'],'Content-Type':'application/x-qingliao-encrypted'}))
            await started.wait();await asyncio.sleep(.1);group=(await get('/groups/'+gid,bob))['group']
            await api('/groups/'+gid+'/members',dict(action='remove',member=dana['user']['id'],epoch=group['epoch']),bob);resume.set();response=await slow;await response.read();assert response.status==403
            assert not s.row('SELECT 1 FROM uploads WHERE owner=?',(dana['user']['id'],));checks+=1
            s.limits.clear()
            second=(await api('/groups',dict(creation,client_id=str(uuid.uuid4())),alice,status=201))['group']['id']
            before=await frame(bob,second)
            await api('/blocks',dict(peer=carl['user']['id'],blocked=True),bob)
            await api('/groups/'+second+'/messages',before,bob,status=409)
            filtered=await frame(bob,second);assert carl['user']['id'] not in filtered['envelopes']
            hidden=await api('/groups/'+second+'/messages',filtered,bob)
            assert all(m['id']!=hidden['id'] for m in (await get('/groups/'+second+'/messages',carl))['messages']);checks+=1
            # An unrelated block must not make an already-entitled attachment look unsent.
            await api('/groups/'+gid+'/messages/'+filemsg['id']+'/withdraw',{},alice)
            assert not (s.DATA/'uploads'/fid).exists();checks+=1
            group=(await get('/groups/'+gid,bob))['group']
            await api('/groups/'+gid+'/members',dict(action='leave',epoch=group['epoch']),alice)
            await get('/groups/'+gid+'/keys',alice,status=403)
            group=(await get('/groups/'+gid,bob))['group']
            await api('/groups/'+gid+'/members',dict(action='dissolve',epoch=group['epoch']),bob)
            await get('/groups/'+gid,bob,status=403);checks+=1
            assert (await get('/health',bob))['groups']
            assert s.db.execute('PRAGMA integrity_check').fetchone()[0]=='ok' and not s.db.execute('PRAGMA foreign_key_check').fetchall()
            print(f'PASS group authority/storage: {checks} cases; scoped delivery, epoch changes, owner transfer, new-member history/file exclusion, stale upload revoke, block filtering, legacy views and SQLite integrity')
        s.db.close();gc.collect()


if __name__=='__main__':asyncio.run(main())
