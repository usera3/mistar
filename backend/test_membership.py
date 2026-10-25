"""Synthetic payments only: no production gateway, money, mail or user accounts."""
import asyncio
import json
import os
import tempfile
import uuid
from pathlib import Path
from aiohttp.test_utils import TestClient, TestServer
from membership import Membership, sign, cents, DAY, MIB


async def main():
    with tempfile.TemporaryDirectory(prefix='mistar-membership-') as directory:
        os.environ.update(QINGLIAO_DATA=directory,QINGLIAO_REQUIRE_EMAIL='0',QINGLIAO_REQUIRE_E2EE='0',MISTAR_PAY_ENABLED='1',
            MISTAR_PAY_PID='unit-merchant',MISTAR_PAY_KEY='test-only-key',QINGLIAO_PUBLIC_PLAZA='0')
        import server as s
        m=s.membership;gateway={};calls=[];responses={};barrier=None;arrived=None;fail_after_wait=False
        async def transport(path,fields):
            nonlocal barrier,arrived
            assert fields['sign']==sign(fields,'test-only-key')
            assert not any(k in fields for k in ('token','owner','user','email','password'))
            calls.append((path,dict(fields)))
            oid=fields['out_trade_no']
            if path=='/mapi.php':
                gateway.setdefault(oid,dict(fields));f=gateway[oid]
                if arrived:arrived.set()
                if barrier:await barrier.wait()
                if fail_after_wait:raise ValueError('Synthetic provider failure')
                return {'code':1,'trade_no':'trade_'+oid,'payurl':responses.get('payurl',m.gateway+'/pay/'+'token_'+oid)}
            f=gateway[oid]
            return {'code':1,'trade_no':'trade_'+oid,'out_trade_no':oid,'param':f['param'],
                'money':responses.get('money',f['money']),'status':responses.get('status',0),'trade_status':responses.get('trade_status','WAIT_BUYER_PAY')}
        m.transport=transport;checks=[]
        async with TestClient(TestServer(s.app)) as client:
            async def api(method,path,body=None,token='',expected=200):
                r=await client.request(method,'/api'+path,json=body,headers={'Authorization':'Bearer '+token})
                p=await r.json();assert r.status==expected,(r.status,path,p)
                return p
            async def register(name):return await api('POST','/register',dict(username=name,nickname=name,password='synthetic-safe-password'))
            a=await register('member_alice');b=await register('member_bob');uid=a['user']['id'];at=a['token'];bt=b['token']
            async def create(plan='month',client_id=None,token=at):return await api('POST','/payments/orders',{'plan_id':plan,'client_id':client_id or str(uuid.uuid4())},token)
            def fields(oid,**changes):
                f=gateway[oid]
                result={k:f[k] for k in ('pid','out_trade_no','type','name','money','param','sign_type')}
                result.update(trade_no='trade_'+oid,trade_status='TRADE_SUCCESS');result.update(changes)
                result['sign']=sign(result,'test-only-key');return result
            async def notify(f,expected=200):
                r=await client.post('/api/payments/notify',data=f)
                text=await r.text();assert r.status==expected,(r.status,text)
                if expected==200:assert text=='success'
            async def refresh(oid,expected=200):
                s.write('UPDATE membership_orders SET checked=0 WHERE id=?',(oid,))
                return await api('POST','/payments/orders/'+oid+'/refresh',{},at,expected)
            catalog=await api('GET','/membership',token=at)
            assert [p['cents'] for p in catalog['plans']]==[990,2500,8800] and not catalog['auto_renew']
            assert not catalog['membership']['active'] and catalog['membership']['storage_bytes']==200*MIB
            await api('GET','/membership',expected=401)
            await api('POST','/payments/orders',{'plan_id':['month'],'client_id':str(uuid.uuid4())},at,400)
            await api('POST','/payments/orders',{'plan_id':'month','client_id':str(uuid.uuid4()),'money':'0.01'},at,400)
            checks.append('authenticated catalog; immutable prices/duration; no auto-renew; amount/plan tampering rejected')
            client_id=str(uuid.uuid4());order=(await create(client_id=client_id))['order'];oid=order['id']
            again=(await create(client_id=client_id))['order'];assert again['id']==oid
            assert len([p for p,f in calls if p=='/mapi.php'])==1
            await api('POST','/payments/orders',{'plan_id':'year','client_id':client_id},at,409)
            for suffix in ('','/refresh','/retry'):
                await api('GET' if not suffix else 'POST','/payments/orders/'+oid+suffix,None,bt,404)
            assert (await api('GET','/payments/orders',token=bt))['orders']==[]
            checks.append('stable client/order idempotency and cross-account history/detail/query/retry rejection')
            broken=fields(oid);broken['sign']='0'*32
            await notify(broken,403)
            for change in ({'money':'0.01'},{'pid':'other'},{'name':'other'},{'type':'wxpay'},{'param':'bad'},{'trade_status':'WAIT_BUYER_PAY'}):
                await notify(fields(oid,**change),409)
            f=fields(oid);f['extra']='value';f['sign']=sign(f,'test-only-key');await notify(f,400)
            f=fields(oid);f['param']='x&money=9.90';f['sign']=sign(f,'test-only-key');await notify(f,400)
            f=fields(oid);duplicates=list(f.items())+[('money','0.01')];await notify(duplicates,400)
            r=await client.post('/api/payments/notify',json=fields(oid));assert r.status==415
            r=await client.post('/api/payments/notify',data='x'*8193,headers={'Content-Type':'application/x-www-form-urlencoded'});assert r.status==413
            r=await client.get('/api/payments/notify?trade_status=TRADE_SUCCESS');assert r.status==405
            assert not m.active(uid)
            checks.append('callback MAC, merchant/type/subject/amount/nonce/status, duplicate fields, delimiter ambiguity, body bounds; return/GET cannot grant')
            await notify(fields(oid));until=m.status(uid)['expires_at'];assert until>s.now()+29*DAY
            await notify(fields(oid));assert m.status(uid)['expires_at']==until
            assert s.user_public(uid,uid)['member'] and s.user_public(uid,uid)['membership']['active']
            assert 'membership' not in s.user_public(uid,b['user']['id'])
            storage=await api('GET','/storage',token=at);assert storage['quota']==500*MIB and storage['upload_daily_limit']==256*MIB
            await api('POST','/friends/request',{'peer':b['user']['id']},at)
            await api('POST','/friends/respond',{'peer':uid,'accept':True},bt)
            original_media=s.guard.user_media
            try:
                s.guard.user_media=lambda who:250*MIB
                r=await client.post('/api/uploads?recipient='+b['user']['id']+'&name=quota-test.bin',data=b'x',headers={'Authorization':'Bearer '+at,'Content-Type':'application/octet-stream'})
                assert r.status==200,(r.status,await r.text())
                r=await client.post('/api/uploads?recipient='+uid+'&name=quota-test.bin',data=b'x',headers={'Authorization':'Bearer '+bt,'Content-Type':'application/octet-stream'})
                assert r.status==507
                s.guard.user_media=original_media
                import time
                day=int(time.time()//86400)
                s.write('INSERT OR REPLACE INTO upload_usage VALUES(?,?,?)',(uid,day,200*MIB))
                r=await client.post('/api/uploads?recipient='+b['user']['id']+'&name=daily-test.bin',data=b'x',headers={'Authorization':'Bearer '+at,'Content-Type':'application/octet-stream'})
                assert r.status==200,(r.status,await r.text())
                s.write('INSERT OR REPLACE INTO upload_usage VALUES(?,?,?)',(b['user']['id'],day,32*MIB))
                r=await client.post('/api/uploads?recipient='+uid+'&name=daily-test.bin',data=b'x',headers={'Authorization':'Bearer '+bt,'Content-Type':'application/octet-stream'})
                assert r.status==429
            finally:s.guard.user_media=original_media
            checks.append('verified payment atomically grants membership and live server-enforced quota; repeated callback never extends twice')
            reopened=Membership(s.__dict__);assert reopened.active(uid)
            assert not reopened.settle(reopened.row(oid),'trade_'+oid) and reopened.status(uid)['expires_at']==until
            qid=(await create('quarter'))['order']['id'];await notify(fields(qid));assert m.status(uid)['expires_at']==until+90*DAY
            checks.append('database-backed idempotency survives a new service object; renewal extends existing expiry by purchased duration')
            yid=(await create('year'))['order']['id']
            responses.update(status=1,trade_status='TRADE_SUCCESS',money='0.01');before=m.status(uid)['expires_at']
            await refresh(yid,409);assert m.status(uid)['expires_at']==before
            responses.pop('money');await refresh(yid);assert m.status(uid)['expires_at']==before+365*DAY
            await notify(fields(yid));assert m.status(uid)['expires_at']==before+365*DAY
            checks.append('manual server-to-provider reconciliation verifies exact amount/order/nonce/status and is callback-idempotent')
            responses.clear();cid=(await create())['order']['id'];responses.update(status=0,trade_status='TRADE_CLOSED')
            closed=await refresh(cid);assert closed['order']['state']=='closed'
            await notify(fields(cid));assert m.row(cid)['state']=='paid'
            checks.append('closed unpaid orders grant nothing; genuine late confirmed payment is fulfilled once')
            original_notice=s.profile_updated;notice_release=asyncio.Event();notice_started=asyncio.Event()
            async def blocked_notice(_):notice_started.set();await notice_release.wait()
            s.profile_updated=blocked_notice
            try:
                sid=(await create())['order']['id']
                await asyncio.wait_for(notify(fields(sid)),2)
                await asyncio.wait_for(notice_started.wait(),2)
                assert m.row(sid)['state']=='paid'
            finally:s.profile_updated=original_notice;notice_release.set()
            checks.append('slow notification socket cannot block durable payment ACK or call-maintenance scheduling')
            responses.clear();responses['payurl']='https://attacker.example/pay/abcdefghijklmnop'
            failed_id=str(uuid.uuid4());p=await api('POST','/payments/orders',{'plan_id':'month','client_id':failed_id},at,502)
            failed=s.row('SELECT * FROM membership_orders WHERE owner=? AND client=?',(uid,failed_id));assert failed['state']=='failed'
            responses.clear();p=await api('POST','/payments/orders/'+failed['id']+'/retry',{},at);assert p['order']['id']==failed['id']
            checks.append('untrusted checkout URL rejected; failed creation retained and retried with the original stable request')
            await notify(fields(failed['id']))
            barrier=asyncio.Event();arrived=asyncio.Event();fail_after_wait=True;race_client=str(uuid.uuid4())
            task=asyncio.create_task(create(client_id=race_client));await arrived.wait()
            race=s.row('SELECT * FROM membership_orders WHERE owner=? AND client=?',(uid,race_client));await notify(fields(race['id']))
            paid_until=m.status(uid)['expires_at'];barrier.set();race_result=await task;assert race_result['order']['state']=='paid' and m.status(uid)['expires_at']==paid_until
            barrier=arrived=None;fail_after_wait=False
            checks.append('callback-before-create-response plus late transport failure cannot lose/downgrade a paid order')
            atomic=(await create(token=bt))['order']['id'];buid=b['user']['id']
            s.db.execute("CREATE TEMP TRIGGER membership_fail BEFORE INSERT ON memberships WHEN NEW.owner='"+buid+"' BEGIN SELECT RAISE(ABORT,'synthetic transaction failure');END")
            await notify(fields(atomic),500);assert m.row(atomic)['state']!='paid' and not m.active(buid)
            s.db.execute('DROP TRIGGER membership_fail');await notify(fields(atomic));assert m.active(buid)
            checks.append('injected entitlement-write failure rolls back payment transition; replay safely completes the same purchase')
            now=s.now;clock=now()+1000*DAY;s.now=lambda:clock
            marker=Path(directory)/'uploads'/'owned-membership-marker';marker.write_bytes(b'Owned synthetic file')
            await m.expire();assert not m.active(uid) and m.media_limit(uid)==200*MIB and marker.read_bytes()==b'Owned synthetic file'
            assert s.row('SELECT state FROM membership_orders WHERE id=?',(oid,))['state']=='paid'
            s.now=now
            m.enabled=False;await api('POST','/payments/orders',{'plan_id':'month','client_id':str(uuid.uuid4())},at,503)
            await notify(fields(oid));assert m.row(oid)['state']=='paid'
            checks.append('expiry changes entitlement without deleting files/payment history; disabled checkout still acknowledges verified historic callbacks')
        for value in ('','NaN','-1','1e3','9.900',None,True,1.5):
            try:cents(value)
            except ValueError:pass
            else:raise AssertionError('Malformed currency accepted')
        assert cents('9.9')==cents('9.90')==990
        vector={'pid':'1000','type':'alipay','out_trade_no':'ORD202605230001','notify_url':'https://merchant.example/notify','return_url':'https://merchant.example/return','name':'VIP','money':'100','sign_type':'MD5'}
        assert sign(vector,'epusdt_secret_key')=='b865b0acbb2b01554c35a1bd33351452'
        print(json.dumps({'passed':True,'checks':len(checks),'details':checks,'realGatewayRequests':0,'realMoneyMoved':False,'productionDataUsed':False},ensure_ascii=False,indent=2))


if __name__=='__main__':asyncio.run(main())
